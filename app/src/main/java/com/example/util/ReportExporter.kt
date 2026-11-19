package com.example.util

import android.app.Activity
import android.content.ClipData
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.CancellationSignal
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.print.PageRange
import android.print.PrintAttributes
import android.print.PrintDocumentAdapter
import android.print.PrintDocumentInfo
import android.print.PrintManager
import android.provider.MediaStore
import android.text.TextPaint
import android.text.TextUtils
import android.util.Base64
import androidx.core.content.FileProvider
import com.example.data.model.BillingCalculator
import com.example.data.model.Business
import com.example.data.model.BusinessSubscription
import com.example.data.model.Customer
import com.example.data.model.ReceiptRecord
import com.example.data.model.StorageUsage
import com.example.data.model.SubscriptionInvoice
import com.example.data.model.SubscriptionPayment
import com.example.data.model.UsageQuota
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

object ReportExporter {

    private fun authority(context: Context): String = "${context.packageName}.fileprovider"

    private fun sanitizeFilePart(raw: String, fallback: String = "receipt"): String =
        raw.replace(Regex("[^A-Za-z0-9._-]"), "_")
            .replace(Regex("_+"), "_")
            .trim('_', '.')
            .take(64)
            .ifBlank { fallback }

    private fun pruneOldCacheFiles(dir: File, maxAgeMillis: Long = 2L * 60L * 60L * 1000L) {
        val cutoff = System.currentTimeMillis() - maxAgeMillis
        dir.listFiles()?.forEach { f ->
            if (f.isFile && f.lastModified() < cutoff) {
                runCatching { f.delete() }
            }
        }
    }

    private fun toOpaqueBitmap(source: Bitmap): Bitmap {
        val out = Bitmap.createBitmap(
            source.width.coerceAtLeast(1),
            source.height.coerceAtLeast(1),
            Bitmap.Config.ARGB_8888
        )
        val canvas = Canvas(out)
        canvas.drawColor(Color.WHITE)
        canvas.drawBitmap(source, 0f, 0f, null)
        return out
    }

    @Suppress("DEPRECATION")
    private fun grantUriPermissionsForIntent(
        context: Context,
        intent: Intent,
        uri: Uri,
        extraPackages: List<String> = emptyList()
    ) {
        val pm = context.packageManager
        val resolvedPackages = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.queryIntentActivities(
                    intent,
                    PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_DEFAULT_ONLY.toLong())
                )
            } else {
                pm.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
            }.mapNotNull { it.activityInfo?.packageName }
        }.getOrDefault(emptyList())

        (resolvedPackages + extraPackages)
            .filter { it.isNotBlank() }
            .distinct()
            .forEach { pkg ->
                runCatching {
                    context.grantUriPermission(pkg, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
            }
    }

    private fun launchShareIntent(context: Context, intent: Intent, chooserTitle: String?) {
        if (context !is Activity) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        if (chooserTitle != null) {
            val chooser = Intent.createChooser(intent, chooserTitle).apply {
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                if (context !is Activity) {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            }
            context.startActivity(chooser)
        } else {
            context.startActivity(intent)
        }
    }

    fun saveReceiptImageToGallery(
        context: Context,
        bitmap: Bitmap,
        receiptNumber: String,
        asJpeg: Boolean
    ): Result<Uri> = runCatching {
        require(!bitmap.isRecycled && bitmap.width > 0 && bitmap.height > 0) {
            "Receipt image is not ready to save."
        }
        val cleanName = sanitizeFilePart(receiptNumber)
        val ext = if (asJpeg) "jpg" else "png"
        val mime = if (asJpeg) "image/jpeg" else "image/png"
        val format = if (asJpeg) Bitmap.CompressFormat.JPEG else Bitmap.CompressFormat.PNG
        val quality = if (asJpeg) 95 else 100
        val fileName = "${cleanName}_${System.currentTimeMillis()}.$ext"

        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
            put(MediaStore.Images.Media.MIME_TYPE, mime)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/ReceiptsNow")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
        }
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw IllegalStateException("Could not create MediaStore image entry.")
        try {
            val sourceToWrite = if (asJpeg) toOpaqueBitmap(bitmap) else bitmap
            try {
                resolver.openOutputStream(uri)?.use { out ->
                    check(sourceToWrite.compress(format, quality, out)) {
                        "Failed to encode receipt image."
                    }
                    out.flush()
                } ?: throw IllegalStateException("Could not open output stream for receipt image.")
            } finally {
                if (sourceToWrite !== bitmap && !sourceToWrite.isRecycled) {
                    sourceToWrite.recycle()
                }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                values.clear()
                values.put(MediaStore.Images.Media.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
            }
            uri
        } catch (err: Throwable) {
            runCatching { resolver.delete(uri, null, null) }
            throw err
        }
    }

    fun writeReceiptCacheUri(
        context: Context,
        bitmap: Bitmap,
        receiptNumber: String,
        asJpeg: Boolean = true
    ): Result<Uri> = runCatching {
        require(!bitmap.isRecycled && bitmap.width > 0 && bitmap.height > 0) {
            "Receipt image is not ready to share."
        }
        val dir = File(context.cacheDir, "receipts").apply { mkdirs() }
        pruneOldCacheFiles(dir)
        val clean = sanitizeFilePart(receiptNumber)
        val ext = if (asJpeg) "jpg" else "png"
        val file = File(dir, "${clean}_${System.currentTimeMillis()}.$ext")
        val sourceToWrite = if (asJpeg) toOpaqueBitmap(bitmap) else bitmap
        try {
            FileOutputStream(file).use { out ->
                val ok = sourceToWrite.compress(
                    if (asJpeg) Bitmap.CompressFormat.JPEG else Bitmap.CompressFormat.PNG,
                    if (asJpeg) 94 else 100,
                    out
                )
                check(ok) { "Could not encode receipt image for sharing." }
                out.flush()
            }
        } finally {
            if (sourceToWrite !== bitmap && !sourceToWrite.isRecycled) {
                sourceToWrite.recycle()
            }
        }
        check(file.exists() && file.length() > 0L) { "Generated receipt file is empty." }
        FileProvider.getUriForFile(context, authority(context), file)
    }

    fun shareReceiptImage(
        context: Context,
        bitmap: Bitmap,
        business: Business,
        receipt: ReceiptRecord,
        targetPackage: String? = null
    ): Result<Unit> = runCatching {
        val uri = writeReceiptCacheUri(context, bitmap, receipt.receiptNumber, asJpeg = true).getOrThrow()
        val msg = buildShareMessage(business, receipt)
        val subject = "Payment Receipt ${receipt.receiptNumber} — ${business.tradingName.ifBlank { business.registeredName }}"
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "image/jpeg"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_TEXT, msg)
            putExtra(Intent.EXTRA_SUBJECT, subject)
            clipData = ClipData.newUri(context.contentResolver, "Official Payment Receipt", uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            if (!targetPackage.isNullOrBlank()) {
                setPackage(targetPackage)
            }
        }
        val extraPkgs = listOfNotNull(targetPackage, "com.whatsapp", "com.whatsapp.w4b")
        grantUriPermissionsForIntent(context, intent, uri, extraPkgs)

        if (!targetPackage.isNullOrBlank()) {
            try {
                launchShareIntent(context, intent, chooserTitle = null)
            } catch (_: Exception) {
                intent.setPackage(null)
                grantUriPermissionsForIntent(context, intent, uri, extraPkgs)
                launchShareIntent(context, intent, chooserTitle = "Share Official Receipt")
            }
        } else {
            launchShareIntent(context, intent, chooserTitle = "Share Official Receipt")
        }
    }

    @Suppress("DEPRECATION")
    fun shareReceiptViaWhatsApp(
        context: Context,
        bitmap: Bitmap,
        business: Business,
        receipt: ReceiptRecord
    ): Result<Unit> = runCatching {
        val uri = writeReceiptCacheUri(context, bitmap, receipt.receiptNumber, asJpeg = true).getOrThrow()
        val msg = buildShareMessage(business, receipt)
        val subject = "Payment Receipt ${receipt.receiptNumber} — ${business.tradingName.ifBlank { business.registeredName }}"
        val baseIntent = Intent(Intent.ACTION_SEND).apply {
            type = "image/jpeg"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_TEXT, msg)
            putExtra(Intent.EXTRA_SUBJECT, subject)
            clipData = ClipData.newUri(context.contentResolver, "Official Payment Receipt", uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        val pm = context.packageManager
        val candidates = listOf("com.whatsapp", "com.whatsapp.w4b")
        val installedWhatsApp = candidates.filter { pkg ->
            val canResolve = runCatching {
                val probe = Intent(baseIntent).setPackage(pkg)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    pm.queryIntentActivities(
                        probe,
                        PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_DEFAULT_ONLY.toLong())
                    ).isNotEmpty()
                } else {
                    pm.queryIntentActivities(probe, PackageManager.MATCH_DEFAULT_ONLY).isNotEmpty()
                }
            }.getOrDefault(false)
            canResolve || runCatching { pm.getPackageInfo(pkg, 0) }.isSuccess
        }

        grantUriPermissionsForIntent(context, baseIntent, uri, candidates)

        when {
            installedWhatsApp.size == 1 -> {
                val pkg = installedWhatsApp.first()
                val direct = Intent(baseIntent).setPackage(pkg)
                grantUriPermissionsForIntent(context, direct, uri, listOf(pkg))
                try {
                    launchShareIntent(context, direct, chooserTitle = null)
                } catch (_: Exception) {
                    launchShareIntent(context, baseIntent, chooserTitle = "Share Receipt via WhatsApp")
                }
            }
            installedWhatsApp.size > 1 -> {
                // Both WhatsApp and WhatsApp Business are installed; try standard WhatsApp first or chooser
                val primary = Intent(baseIntent).setPackage(installedWhatsApp.first())
                val secondary = Intent(baseIntent).setPackage(installedWhatsApp[1])
                val chooser = Intent.createChooser(primary, "Send Receipt via WhatsApp").apply {
                    putExtra(Intent.EXTRA_INITIAL_INTENTS, arrayOf(secondary))
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    if (context !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(chooser)
            }
            else -> {
                launchShareIntent(context, baseIntent, chooserTitle = "Share Official Receipt")
            }
        }
    }

    fun shareReceiptViaEmail(
        context: Context,
        bitmap: Bitmap,
        business: Business,
        receipt: ReceiptRecord
    ): Result<Unit> = runCatching {
        val uri = writeReceiptCacheUri(context, bitmap, receipt.receiptNumber, asJpeg = true).getOrThrow()
        val msg = buildShareMessage(business, receipt)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "image/jpeg"
            if (receipt.customerEmail.isNotBlank()) {
                putExtra(Intent.EXTRA_EMAIL, arrayOf(receipt.customerEmail))
            }
            putExtra(
                Intent.EXTRA_SUBJECT,
                "Official Payment Receipt ${receipt.receiptNumber} — ${business.tradingName.ifBlank { business.registeredName }}"
            )
            putExtra(Intent.EXTRA_TEXT, msg)
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = ClipData.newUri(context.contentResolver, "Official Payment Receipt", uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        grantUriPermissionsForIntent(context, intent, uri)
        launchShareIntent(context, intent, chooserTitle = "Send Receipt via Email")
    }

    fun shareTransferProofViaEmail(
        context: Context,
        toEmail: String,
        subject: String,
        body: String,
        receiptImageBase64: String,
        receiptFileName: String
    ): Result<Unit> = runCatching {
        val cleanBase64 = receiptImageBase64
            .substringAfter("base64,", receiptImageBase64)
            .trim()
        val decodedBytes = if (cleanBase64.isNotBlank()) {
            runCatching { Base64.decode(cleanBase64, Base64.DEFAULT) }.getOrNull()
        } else null

        if (decodedBytes != null && decodedBytes.isNotEmpty()) {
            val dir = File(context.cacheDir, "receipts").apply { mkdirs() }
            pruneOldCacheFiles(dir)
            val safeName = sanitizeFilePart(
                receiptFileName.substringBeforeLast('.', receiptFileName),
                fallback = "transfer_receipt"
            )
            val file = File(dir, "${safeName}_${System.currentTimeMillis()}.jpg")
            FileOutputStream(file).use { out ->
                out.write(decodedBytes)
                out.flush()
            }
            val uri = FileProvider.getUriForFile(context, authority(context), file)
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "image/jpeg"
                putExtra(Intent.EXTRA_EMAIL, arrayOf(toEmail.ifBlank { "info.1803consult@gmail.com" }))
                putExtra(Intent.EXTRA_SUBJECT, subject)
                putExtra(Intent.EXTRA_TEXT, body)
                putExtra(Intent.EXTRA_STREAM, uri)
                clipData = ClipData.newUri(context.contentResolver, "Bank Transfer Receipt", uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            grantUriPermissionsForIntent(context, intent, uri)
            launchShareIntent(context, intent, chooserTitle = "Email Receipt to ${toEmail.ifBlank { "Support" }}")
        } else {
            val mailto = Intent(Intent.ACTION_SENDTO).apply {
                data = Uri.parse("mailto:${Uri.encode(toEmail.ifBlank { "info.1803consult@gmail.com" })}")
                putExtra(Intent.EXTRA_SUBJECT, subject)
                putExtra(Intent.EXTRA_TEXT, body)
            }
            launchShareIntent(context, mailto, chooserTitle = "Email ${toEmail.ifBlank { "Support" }}")
        }
    }

    fun printReceiptBitmap(
        context: Context,
        bitmap: Bitmap,
        receiptNumber: String
    ): Result<Unit> = runCatching {
        val printManager = context.getSystemService(Context.PRINT_SERVICE) as? PrintManager
            ?: throw IllegalStateException("Android Print service unavailable on this device.")
        val jobName = "ReceiptsNow_${receiptNumber.replace("/", "_")}"
        val adapter = object : PrintDocumentAdapter() {
            override fun onLayout(
                oldAttributes: PrintAttributes?,
                newAttributes: PrintAttributes,
                cancellationSignal: CancellationSignal?,
                callback: LayoutResultCallback,
                extras: Bundle?
            ) {
                if (cancellationSignal?.isCanceled == true) {
                    callback.onLayoutCancelled()
                    return
                }
                val info = PrintDocumentInfo.Builder("$jobName.pdf")
                    .setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT)
                    .setPageCount(1)
                    .build()
                callback.onLayoutFinished(info, true)
            }

            override fun onWrite(
                pages: Array<out PageRange>?,
                destination: ParcelFileDescriptor,
                cancellationSignal: CancellationSignal?,
                callback: WriteResultCallback
            ) {
                if (cancellationSignal?.isCanceled == true) {
                    callback.onWriteCancelled()
                    return
                }
                val pdf = PdfDocument()
                try {
                    val pageWidth = 842 // A4 landscape pt
                    val pageHeight = 595
                    val pageInfo = PdfDocument.PageInfo.Builder(pageWidth, pageHeight, 1).create()
                    val page = pdf.startPage(pageInfo)
                    val margin = 20f
                    val scale = minOf(
                        (pageWidth - margin * 2f) / bitmap.width,
                        (pageHeight - margin * 2f) / bitmap.height
                    )
                    val drawW = bitmap.width * scale
                    val drawH = bitmap.height * scale
                    val left = (pageWidth - drawW) / 2f
                    val top = (pageHeight - drawH) / 2f
                    val dst = android.graphics.RectF(left, top, left + drawW, top + drawH)
                    page.canvas.drawBitmap(bitmap, null, dst, Paint(Paint.FILTER_BITMAP_FLAG))
                    pdf.finishPage(page)
                    FileOutputStream(destination.fileDescriptor).use { out ->
                        pdf.writeTo(out)
                    }
                    callback.onWriteFinished(arrayOf(PageRange.ALL_PAGES))
                } catch (e: Exception) {
                    callback.onWriteFailed(e.message ?: "Print failed")
                } finally {
                    pdf.close()
                }
            }
        }
        val attrs = PrintAttributes.Builder()
            .setMediaSize(PrintAttributes.MediaSize.ISO_A4.asLandscape())
            .build()
        printManager.print(jobName, adapter, attrs)
    }

    fun exportCsvAndShare(
        context: Context,
        filePrefix: String,
        headers: List<String>,
        rows: List<List<String>>
    ): Result<Uri> = runCatching {
        val sb = StringBuilder()
        sb.appendLine(headers.joinToString(",") { escapeCsv(it) })
        rows.forEach { row ->
            sb.appendLine(row.joinToString(",") { escapeCsv(it) })
        }
        val dir = File(context.cacheDir, "exports").apply { mkdirs() }
        val file = File(dir, "${filePrefix}_${timestampTag()}.csv")
        file.writeText(sb.toString(), Charsets.UTF_8)
        val uri = FileProvider.getUriForFile(context, authority(context), file)
        launchExportShare(context, uri, "text/csv", "Export CSV Report")
        uri
    }

    fun exportXlsxAndShare(
        context: Context,
        filePrefix: String,
        sheetTitle: String,
        headers: List<String>,
        rows: List<List<String>>
    ): Result<Uri> = runCatching {
        val dir = File(context.cacheDir, "exports").apply { mkdirs() }
        val file = File(dir, "${filePrefix}_${timestampTag()}.xlsx")
        val allRows = listOf(headers) + rows
        writeGenuineXlsx(file, sheetTitle.take(31).ifBlank { "Report" }, allRows)
        val uri = FileProvider.getUriForFile(context, authority(context), file)
        launchExportShare(
            context,
            uri,
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            "Export Excel (.xlsx) Report"
        )
        uri
    }

    fun exportPdfReportAndShare(
        context: Context,
        filePrefix: String,
        title: String,
        subtitle: String,
        headers: List<String>,
        rows: List<List<String>>
    ): Result<Uri> = runCatching {
        val dir = File(context.cacheDir, "exports").apply { mkdirs() }
        val file = File(dir, "${filePrefix}_${timestampTag()}.pdf")
        val pdf = PdfDocument()
        var wroteNativePdf = false
        try {
            val pageW = 842
            val pageH = 595
            val rowsPerPage = 16
            val totalPages = ((rows.size + rowsPerPage - 1) / rowsPerPage).coerceAtLeast(1)

            val headerPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#0B203B")
                textSize = 18f
                isFakeBoldText = true
            }
            val subPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#475569")
                textSize = 11f
            }
            val tableHeaderBg = Paint().apply { color = Color.parseColor("#0B203B") }
            val tableHeaderTxt = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                textSize = 10f
                isFakeBoldText = true
            }
            val cellPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#0F172A")
                textSize = 9.5f
            }
            val altRowBg = Paint().apply { color = Color.parseColor("#F1F5F9") }

            val colCount = headers.size.coerceAtLeast(1)
            val usableW = pageW - 60f
            val colW = usableW / colCount
            val maxCellDrawW = (colW - 10f).coerceAtLeast(16f)

            for (pageIdx in 0 until totalPages) {
                val pageInfo = PdfDocument.PageInfo.Builder(pageW, pageH, pageIdx + 1).create()
                val page = pdf.startPage(pageInfo)
                val canvas: Canvas = page.canvas

                val fitTitle = TextUtils.ellipsize(title, headerPaint, pageW - 60f, TextUtils.TruncateAt.END).toString()
                val fitSub = TextUtils.ellipsize("$subtitle  •  Page ${pageIdx + 1} of $totalPages", subPaint, pageW - 60f, TextUtils.TruncateAt.END).toString()
                canvas.drawText(fitTitle, 30f, 40f, headerPaint)
                canvas.drawText(fitSub, 30f, 58f, subPaint)

                // Header Bar
                val topY = 78f
                canvas.drawRect(30f, topY, pageW - 30f, topY + 24f, tableHeaderBg)
                headers.forEachIndexed { idx, h ->
                    val fitHeader = TextUtils.ellipsize(h, tableHeaderTxt, maxCellDrawW, TextUtils.TruncateAt.END).toString()
                    canvas.drawText(fitHeader, 36f + idx * colW, topY + 16f, tableHeaderTxt)
                }

                val sliceStart = pageIdx * rowsPerPage
                val sliceEnd = minOf(sliceStart + rowsPerPage, rows.size)
                var rowY = topY + 26f
                for (r in sliceStart until sliceEnd) {
                    if ((r - sliceStart) % 2 == 1) {
                        canvas.drawRect(30f, rowY, pageW - 30f, rowY + 26f, altRowBg)
                    }
                    val row = rows[r]
                    headers.indices.forEach { idx ->
                        val rawCell = row.getOrNull(idx).orEmpty().replace('\n', ' ')
                        val fitCell = TextUtils.ellipsize(rawCell, cellPaint, maxCellDrawW, TextUtils.TruncateAt.END).toString()
                        canvas.drawText(fitCell, 36f + idx * colW, rowY + 17f, cellPaint)
                    }
                    rowY += 27f
                }

                canvas.drawText(
                    "Generated by ReceiptsNow • Africa/Lagos (WAT)",
                    30f,
                    pageH - 22f,
                    subPaint
                )
                pdf.finishPage(page)
            }
            FileOutputStream(file).use { out -> pdf.writeTo(out) }
            wroteNativePdf = true
        } catch (_: Throwable) {
            wroteNativePdf = false
        } finally {
            runCatching { pdf.close() }
        }
        if (!wroteNativePdf || !file.exists() || file.length() == 0L) {
            val fallbackLines = buildList {
                add(title)
                add(subtitle)
                add(headers.joinToString(" | "))
                rows.forEach { r -> add(r.joinToString(" | ") { it.replace('\n', ' ') }) }
                add("Generated by ReceiptsNow • Africa/Lagos (WAT)")
            }
            writeFallbackPdf(file, fallbackLines)
        }
        val uri = FileProvider.getUriForFile(context, authority(context), file)
        launchExportShare(context, uri, "application/pdf", "Export PDF Report")
        uri
    }

    fun exportJsonBackupAndShare(
        context: Context,
        business: Business,
        customers: List<Customer>,
        receipts: List<ReceiptRecord>,
        payments: List<SubscriptionPayment>
    ): Result<Uri> = runCatching {
        val root = JSONObject().apply {
            put("exportedAtUtc", Date().toString())
            put("platform", "ReceiptsNow")
            put("businessId", business.id)
            put("businessName", business.tradingName.ifBlank { business.registeredName })
            put("receiptsCount", receipts.size)
            put("customersCount", customers.size)
            put("customers", JSONArray().apply {
                customers.forEach { c ->
                    put(JSONObject().apply {
                        put("id", c.id)
                        put("fullName", c.fullName)
                        put("phone", c.phone)
                        put("email", c.email)
                        put("totalVerifiedAmount", c.totalVerifiedAmount)
                        put("receiptCount", c.receiptCount)
                    })
                }
            })
            put("receipts", JSONArray().apply {
                receipts.forEach { r ->
                    put(JSONObject().apply {
                        put("id", r.id)
                        put("receiptNumber", r.receiptNumber)
                        put("verificationCode", r.verificationCode)
                        put("customerName", r.customerName)
                        put("amountPaid", r.amountPaid)
                        put("currency", r.currency)
                        put("paymentPurpose", r.paymentPurpose)
                        put("verificationStatus", r.verificationStatus)
                        put("transactionRefSnapshot", r.transactionRefSnapshot)
                        put("transactionDateSnapshot", r.transactionDateSnapshot)
                    })
                }
            })
            put("subscriptionPayments", JSONArray().apply {
                payments.forEach { p ->
                    put(JSONObject().apply {
                        put("id", p.id)
                        put("planCode", p.planCode)
                        put("amountNgn", p.amountNgn)
                        put("txRef", p.txRef)
                        put("paymentStatus", p.paymentStatus)
                    })
                }
            })
        }
        val dir = File(context.cacheDir, "exports").apply { mkdirs() }
        val file = File(dir, "ReceiptsNow_Backup_${business.receiptPrefix}_${timestampTag()}.json")
        file.writeText(root.toString(2), Charsets.UTF_8)
        val uri = FileProvider.getUriForFile(context, authority(context), file)
        launchExportShare(context, uri, "application/json", "Export Full Business JSON Backup")
        uri
    }

    private fun launchExportShare(context: Context, uri: Uri, mimeType: String, title: String) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = mimeType
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = ClipData.newUri(context.contentResolver, title, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        grantUriPermissionsForIntent(context, intent, uri)
        launchShareIntent(context, intent, chooserTitle = title)
    }

    private fun buildShareMessage(business: Business, receipt: ReceiptRecord): String {
        val bizName = business.tradingName.ifBlank { business.registeredName }
        return buildString {
            appendLine("Official Payment Receipt from $bizName")
            appendLine("Receipt No: ${receipt.receiptNumber}")
            appendLine("Customer: ${receipt.customerName}")
            appendLine("Amount: ₦${"%,.2f".format(receipt.amountPaid)}")
            appendLine("Purpose: ${receipt.paymentPurpose}")
            appendLine("Status: ${receipt.verificationStatus.replace("_", " ")}")
            append("Verify Code: ${receipt.verificationCode} (receiptsnow://verify?code=${receipt.verificationCode})")
        }
    }

    private fun escapeCsv(value: String): String {
        val clean = value.replace("\r", " ").replace("\n", " ")
        return if (clean.contains(",") || clean.contains("\"")) {
            "\"${clean.replace("\"", "\"\"")}\""
        } else {
            clean
        }
    }

    private fun timestampTag(): String {
        val fmt = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.ENGLISH).apply {
            timeZone = TimeZone.getTimeZone("Africa/Lagos")
        }
        return fmt.format(Date())
    }

    private fun writeGenuineXlsx(file: File, sheetName: String, rows: List<List<String>>) {
        ZipOutputStream(FileOutputStream(file)).use { zip ->
            fun putEntry(path: String, xml: String) {
                zip.putNextEntry(ZipEntry(path))
                zip.write(xml.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }

            putEntry(
                "[Content_Types].xml",
                """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
  <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
  <Default Extension="xml" ContentType="application/xml"/>
  <Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>
  <Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>
  <Override PartName="/xl/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml"/>
</Types>"""
            )

            putEntry(
                "_rels/.rels",
                """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
  <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/>
</Relationships>"""
            )

            putEntry(
                "xl/workbook.xml",
                """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">
  <sheets>
    <sheet name="${escapeXml(sheetName)}" sheetId="1" r:id="rId1"/>
  </sheets>
</workbook>"""
            )

            putEntry(
                "xl/_rels/workbook.xml.rels",
                """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
  <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/>
  <Relationship Id="rId2" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/>
</Relationships>"""
            )

            putEntry(
                "xl/styles.xml",
                """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<styleSheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
  <fonts count="2">
    <font><sz val="11"/><name val="Calibri"/></font>
    <font><b/><sz val="11"/><name val="Calibri"/></font>
  </fonts>
  <fills count="2">
    <fill><patternFill patternType="none"/></fill>
    <fill><patternFill patternType="gray125"/></fill>
  </fills>
  <borders count="1"><border><left/><right/><top/><bottom/><diagonal/></border></borders>
  <cellStyleXfs count="1"><xf numFmtId="0" fontId="0" fillId="0" borderId="0"/></cellStyleXfs>
  <cellXfs count="2">
    <xf numFmtId="0" fontId="0" fillId="0" borderId="0" xfId="0"/>
    <xf numFmtId="0" fontId="1" fillId="0" borderId="0" xfId="0" applyFont="1"/>
  </cellXfs>
</styleSheet>"""
            )

            val sheetXml = buildString {
                append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
                append("""<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><sheetData>""")
                rows.forEachIndexed { rIdx, row ->
                    val rowNum = rIdx + 1
                    val styleIdx = if (rIdx == 0) 1 else 0
                    append("""<row r="$rowNum">""")
                    row.forEachIndexed { cIdx, cellVal ->
                        val colRef = columnLetter(cIdx) + rowNum
                        val numeric = if (rIdx > 0) cellVal.toDoubleOrNull() else null
                        val isNumericCell = numeric != null &&
                            numeric.isFinite() &&
                            (!cellVal.startsWith("0") || cellVal == "0" || cellVal.startsWith("0."))
                        if (isNumericCell) {
                            append("""<c r="$colRef" s="$styleIdx"><v>$numeric</v></c>""")
                        } else {
                            append("""<c r="$colRef" t="inlineStr" s="$styleIdx"><is><t>${escapeXml(cellVal)}</t></is></c>""")
                        }
                    }
                    append("</row>")
                }
                append("</sheetData></worksheet>")
            }
            putEntry("xl/worksheets/sheet1.xml", sheetXml)
        }
    }

    private fun columnLetter(index: Int): String {
        var idx = index
        val sb = StringBuilder()
        while (idx >= 0) {
            sb.insert(0, ('A' + (idx % 26)))
            idx = idx / 26 - 1
        }
        return sb.toString()
    }

    private fun escapeXml(input: String): String =
        input.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;")

    fun exportSubscriptionInvoicePdfAndShare(
        context: Context,
        invoice: SubscriptionInvoice,
        business: Business?
    ): Result<Uri> = runCatching {
        val doc = PdfDocument()
        val dir = File(context.cacheDir, "exports").apply { mkdirs() }
        pruneOldCacheFiles(dir)
        val safeNum = sanitizeFilePart(invoice.invoiceNumber, "invoice")
        val file = File(dir, "Invoice_${safeNum}_${timestampTag()}.pdf")
        val dateFmt = SimpleDateFormat("dd MMM yyyy, HH:mm", Locale.ENGLISH).apply {
            timeZone = TimeZone.getTimeZone("Africa/Lagos")
        }
        val cycleText = if (invoice.cycleStartMillis > 0L && invoice.cycleEndMillis > 0L) {
            "${dateFmt.format(Date(invoice.cycleStartMillis)).substringBefore(",")} -> ${dateFmt.format(Date(invoice.cycleEndMillis)).substringBefore(",")}"
        } else "Active Cycle"
        val bizDisplay = invoice.businessName.ifBlank { business?.tradingName ?: business?.registeredName ?: "Subscriber" }

        var wroteNativePdf = false
        try {
            val pageInfo = PdfDocument.PageInfo.Builder(595, 842, 1).create()
            val page = doc.startPage(pageInfo)
            val canvas = page.canvas

            val headerBg = Paint().apply { color = Color.parseColor("#0B203B") }
            val accentPaint = Paint().apply { color = Color.parseColor("#C89B3C") }
            val cardFill = Paint().apply { color = Color.parseColor("#F8FAFC") }
            val borderPaint = Paint().apply {
                color = Color.parseColor("#CBD5E1")
                style = Paint.Style.STROKE
                strokeWidth = 1f
            }
            val titlePaint = TextPaint().apply {
                color = Color.WHITE
                textSize = 20f
                isFakeBoldText = true
                isAntiAlias = true
            }
            val subHeaderPaint = TextPaint().apply {
                color = Color.parseColor("#CBD5E1")
                textSize = 11f
                isAntiAlias = true
            }
            val sectionTitle = TextPaint().apply {
                color = Color.parseColor("#0B203B")
                textSize = 13f
                isFakeBoldText = true
                isAntiAlias = true
            }
            val labelPaint = TextPaint().apply {
                color = Color.parseColor("#475569")
                textSize = 11f
                isAntiAlias = true
            }
            val valuePaint = TextPaint().apply {
                color = Color.parseColor("#0F172A")
                textSize = 11.5f
                isFakeBoldText = true
                isAntiAlias = true
            }

            canvas.drawRect(0f, 0f, 595f, 118f, headerBg)
            canvas.drawRect(0f, 118f, 595f, 123f, accentPaint)
            canvas.drawText("ReceiptsNow Billing Invoice", 36f, 52f, titlePaint)
            canvas.drawText("Official Subscription Tax & Billing Statement", 36f, 74f, subHeaderPaint)
            val fitInvoiceNum = TextUtils.ellipsize("Invoice #: ${invoice.invoiceNumber}", subHeaderPaint, 395f, TextUtils.TruncateAt.END).toString()
            canvas.drawText(fitInvoiceNum, 36f, 98f, subHeaderPaint)

            val badgePaint = Paint().apply {
                color = if (invoice.status == "PAID") Color.parseColor("#047857") else Color.parseColor("#B45309")
            }
            canvas.drawRoundRect(445f, 40f, 559f, 70f, 12f, 12f, badgePaint)
            canvas.drawText(invoice.status, 480f, 59f, titlePaint.apply { textSize = 13f })

            var y = 158f
            canvas.drawRoundRect(36f, y, 559f, y + 115f, 10f, 10f, cardFill)
            canvas.drawRoundRect(36f, y, 559f, y + 115f, 10f, 10f, borderPaint)
            canvas.drawText("Billed To", 52f, y + 24f, sectionTitle)
            canvas.drawText(TextUtils.ellipsize(bizDisplay, valuePaint, 490f, TextUtils.TruncateAt.END).toString(), 52f, y + 46f, valuePaint)
            canvas.drawText(
                TextUtils.ellipsize("Owner Email: ${invoice.ownerEmail.ifBlank { business?.ownerEmail.orEmpty() }}", labelPaint, 490f, TextUtils.TruncateAt.END).toString(),
                52f,
                y + 66f,
                labelPaint
            )
            canvas.drawText("Issued At: ${if (invoice.issuedAtMillis > 0L) dateFmt.format(Date(invoice.issuedAtMillis)) else "Verified"}", 52f, y + 86f, labelPaint)
            canvas.drawText(
                TextUtils.ellipsize("Payment Ref: ${invoice.paymentId.ifBlank { "-" }}", labelPaint, 490f, TextUtils.TruncateAt.END).toString(),
                52f,
                y + 104f,
                labelPaint
            )

            y += 140f
            canvas.drawText("Subscription Cycle & Charges", 36f, y, sectionTitle)
            y += 16f
            canvas.drawRoundRect(36f, y, 559f, y + 185f, 10f, 10f, borderPaint)

            fun drawLineItem(rowY: Float, label: String, value: String, bold: Boolean = false) {
                val lPaint = if (bold) sectionTitle else labelPaint
                val vPaint = if (bold) sectionTitle else valuePaint
                canvas.drawText(TextUtils.ellipsize(label, lPaint, 290f, TextUtils.TruncateAt.END).toString(), 52f, rowY, lPaint)
                canvas.drawText(TextUtils.ellipsize(value, vPaint, 195f, TextUtils.TruncateAt.END).toString(), 350f, rowY, vPaint)
            }

            drawLineItem(y + 30f, "Plan Snapshot", "${invoice.planNameSnapshot.ifBlank { invoice.planCode }} (${invoice.billingFrequencySnapshot})")
            drawLineItem(y + 58f, "Billing Period", cycleText)
            drawLineItem(y + 86f, "Plan Subtotal", BillingCalculator.formatMinorCurrency(invoice.subtotalMinor, invoice.currency))
            drawLineItem(
                y + 114f,
                "Proration Credit Applied",
                if (invoice.prorationCreditMinor > 0L) "-${BillingCalculator.formatMinorCurrency(invoice.prorationCreditMinor, invoice.currency)}"
                else BillingCalculator.formatMinorCurrency(0L, invoice.currency)
            )
            drawLineItem(y + 142f, "Taxes / Fees", BillingCalculator.formatMinorCurrency(invoice.taxMinor, invoice.currency))
            canvas.drawLine(52f, y + 154f, 543f, y + 154f, borderPaint)
            drawLineItem(y + 174f, "Total Paid", BillingCalculator.formatMinorCurrency(invoice.totalMinor, invoice.currency), bold = true)

            canvas.drawText("Generated by ReceiptsNow Billing Engine • Support: info.1803consult@gmail.com", 36f, 795f, labelPaint)
            doc.finishPage(page)

            FileOutputStream(file).use { out -> doc.writeTo(out) }
            wroteNativePdf = true
        } catch (_: Throwable) {
            wroteNativePdf = false
        } finally {
            runCatching { doc.close() }
        }

        if (!wroteNativePdf || !file.exists() || file.length() == 0L) {
            writeFallbackPdf(
                file,
                listOf(
                    "ReceiptsNow Billing Invoice",
                    "Invoice #: ${invoice.invoiceNumber} (${invoice.status})",
                    "Billed To: $bizDisplay",
                    "Owner Email: ${invoice.ownerEmail.ifBlank { business?.ownerEmail.orEmpty() }}",
                    "Issued At: ${if (invoice.issuedAtMillis > 0L) dateFmt.format(Date(invoice.issuedAtMillis)) else "Verified"}",
                    "Payment Ref: ${invoice.paymentId.ifBlank { "-" }}",
                    "Plan Snapshot: ${invoice.planNameSnapshot.ifBlank { invoice.planCode }} (${invoice.billingFrequencySnapshot})",
                    "Billing Period: $cycleText",
                    "Subtotal: ${BillingCalculator.formatMinorCurrency(invoice.subtotalMinor, invoice.currency)}",
                    "Proration Credit: ${BillingCalculator.formatMinorCurrency(invoice.prorationCreditMinor, invoice.currency)}",
                    "Taxes / Fees: ${BillingCalculator.formatMinorCurrency(invoice.taxMinor, invoice.currency)}",
                    "Total Paid: ${BillingCalculator.formatMinorCurrency(invoice.totalMinor, invoice.currency)}",
                    "Generated by ReceiptsNow Billing Engine"
                )
            )
        }

        val uri = FileProvider.getUriForFile(context, authority(context), file)
        launchExportShare(context, uri, "application/pdf", "Download or Share Subscription Invoice")
        uri
    }

    private fun writeFallbackPdf(file: File, lines: List<String>) {
        val streamText = buildString {
            appendLine("BT")
            appendLine("/F1 11 Tf")
            appendLine("40 800 Td")
            appendLine("16 TL")
            lines.take(45).forEachIndexed { idx, line ->
                val ascii = line.replace("₦", "NGN ")
                    .replace("•", "-")
                    .replace("\\", "\\\\")
                    .replace("(", "\\(")
                    .replace(")", "\\)")
                    .replace(Regex("[^\\x20-\\x7E]"), " ")
                if (idx == 0) appendLine("($ascii) Tj") else appendLine("T* ($ascii) Tj")
            }
            appendLine("ET")
        }
        val objects = listOf(
            "1 0 obj\n<< /Type /Catalog /Pages 2 0 R >>\nendobj\n",
            "2 0 obj\n<< /Type /Pages /Kids [3 0 R] /Count 1 >>\nendobj\n",
            "3 0 obj\n<< /Type /Page /Parent 2 0 R /MediaBox [0 0 595 842] /Contents 4 0 R /Resources << /Font << /F1 5 0 R >> >> >>\nendobj\n",
            "4 0 obj\n<< /Length ${streamText.toByteArray(Charsets.US_ASCII).size} >>\nstream\n$streamText\nendstream\nendobj\n",
            "5 0 obj\n<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>\nendobj\n"
        )
        val out = StringBuilder("%PDF-1.4\n")
        val offsets = mutableListOf<Int>()
        objects.forEach { obj ->
            offsets += out.toString().toByteArray(Charsets.US_ASCII).size
            out.append(obj)
        }
        val xrefPos = out.toString().toByteArray(Charsets.US_ASCII).size
        out.append("xref\n0 ${objects.size + 1}\n0000000000 65535 f \n")
        offsets.forEach { off ->
            out.append(String.format(Locale.US, "%010d 00000 n \n", off))
        }
        out.append("trailer\n<< /Size ${objects.size + 1} /Root 1 0 R >>\nstartxref\n$xrefPos\n%%EOF\n")
        file.writeBytes(out.toString().toByteArray(Charsets.US_ASCII))
    }

    fun exportAdminSubscriptionsCsvAndShare(
        context: Context,
        businesses: List<Business>,
        subscriptions: List<BusinessSubscription>,
        invoices: List<SubscriptionInvoice>,
        quotas: List<UsageQuota>,
        storageUsages: List<StorageUsage>
    ): Result<Uri> = runCatching {
        val sb = StringBuilder()
        sb.appendLine("Business ID,Business Name,Owner Email,Plan Name,Plan Code,Status,Cycle Price (NGN),Cycle End (ms),Receipts Used,Receipts Allowed,OCR Used,OCR Allowed,Storage Used (MB),Storage Limit (MB),Latest Invoice")
        val subByBiz = subscriptions.associateBy { it.businessId }
        val quotaByBiz = quotas.associateBy { it.businessId }
        val storeByBiz = storageUsages.associateBy { it.businessId }
        val latestInvByBiz = invoices.groupBy { it.businessId }.mapValues { (_, list) -> list.maxByOrNull { it.issuedAtMillis } }

        businesses.forEach { biz ->
            val sub = subByBiz[biz.id]
            val quota = quotaByBiz[biz.id]
            val store = storeByBiz[biz.id]
            val inv = latestInvByBiz[biz.id]
            val priceMajor = (sub?.agreedPriceMinor ?: 0L) / 100.0
            val storageUsedMb = ((store?.usedBytes ?: biz.storageUsedBytes) / (1024.0 * 1024.0))
            val storageLimitMb = ((store?.allocatedBytes ?: biz.storageLimitBytes) / (1024.0 * 1024.0))
            val cols = listOf(
                biz.id,
                biz.tradingName.ifBlank { biz.registeredName },
                biz.ownerEmail,
                sub?.planNameSnapshot.orEmpty(),
                sub?.planCode ?: biz.subscriptionPlanCode,
                sub?.status ?: biz.subscriptionStatus,
                "%.2f".format(Locale.US, priceMajor),
                (sub?.cycleEndMillis ?: biz.subscriptionExpiryMillis).toString(),
                (quota?.receiptsUsed ?: 0).toString(),
                (quota?.receiptsAllocated ?: 0).toString(),
                (quota?.ocrUsed ?: 0).toString(),
                (quota?.ocrAllocated ?: 0).toString(),
                "%.2f".format(Locale.US, storageUsedMb),
                if (storageLimitMb < 0) "Unlimited" else "%.2f".format(Locale.US, storageLimitMb),
                inv?.invoiceNumber.orEmpty()
            ).joinToString(",") { escapeCsv(it) }
            sb.appendLine(cols)
        }

        val dir = File(context.cacheDir, "exports").apply { mkdirs() }
        pruneOldCacheFiles(dir)
        val file = File(dir, "ReceiptsNow_Subscriptions_${timestampTag()}.csv")
        file.writeText(sb.toString(), Charsets.UTF_8)
        val uri = FileProvider.getUriForFile(context, authority(context), file)
        launchExportShare(context, uri, "text/csv", "Export Subscription Report CSV")
        uri
    }

    fun exportDailyLedgerPdfAndShare(
        context: Context,
        business: Business,
        title: String,
        ledgerDate: String,
        entries: List<com.example.data.model.LedgerEntryItem>,
        summary: com.example.data.model.LedgerCalculationSummary
    ): Result<Uri> = runCatching {
        val dir = File(context.cacheDir, "exports").apply { mkdirs() }
        pruneOldCacheFiles(dir)
        val bizName = business.tradingName.ifBlank { business.registeredName }
        val file = File(dir, "ReceiptsNow_DayTally_${sanitizeFilePart(ledgerDate, "sheet")}_${timestampTag()}.pdf")
        val doc = PdfDocument()
        var wroteNativePdf = false
        try {
            val pageW = 595
            val pageH = 842
            val page1Info = PdfDocument.PageInfo.Builder(pageW, pageH, 1).create()
            val page1 = doc.startPage(page1Info)
            val canvas = page1.canvas

            val emeraldHeader = Paint().apply { color = Color.parseColor("#054230") }
            val mintAccent = Paint().apply { color = Color.parseColor("#10B981") }
            val boxBg = Paint().apply { color = Color.parseColor("#F2FBF7") }
            val altBg = Paint().apply { color = Color.parseColor("#F8FAFC") }
            val borderPaint = Paint().apply {
                color = Color.parseColor("#CEE8DC")
                style = Paint.Style.STROKE
                strokeWidth = 1f
            }
            val whiteTitle = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                textSize = 18f
                isFakeBoldText = true
            }
            val whiteSub = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#D1FAE5")
                textSize = 11f
            }
            val sectionPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#054230")
                textSize = 13f
                isFakeBoldText = true
            }
            val labelPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#475569")
                textSize = 10f
            }
            val valuePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#0A221A")
                textSize = 11f
                isFakeBoldText = true
            }
            val profitPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                color = if (summary.netProfit >= 0.0) Color.parseColor("#047857") else Color.parseColor("#DC2626")
                textSize = 14f
                isFakeBoldText = true
            }

            canvas.drawRect(0f, 0f, pageW.toFloat(), 98f, emeraldHeader)
            canvas.drawRect(0f, 98f, pageW.toFloat(), 102f, mintAccent)
            canvas.drawText(
                TextUtils.ellipsize(title.ifBlank { "End-of-Day Receipt Breakdown Sheet" }, whiteTitle, pageW - 64f, TextUtils.TruncateAt.END).toString(),
                32f,
                40f,
                whiteTitle
            )
            canvas.drawText(
                TextUtils.ellipsize("$bizName  •  Date: $ledgerDate  •  Entries: ${summary.entryCount}", whiteSub, pageW - 64f, TextUtils.TruncateAt.END).toString(),
                32f,
                62f,
                whiteSub
            )
            canvas.drawText("ReceiptsNow Automated Profit & Expenditure Reconciliation", 32f, 82f, whiteSub)

            var y = 122f
            canvas.drawRoundRect(32f, y, pageW - 32f, y + 132f, 12f, 12f, boxBg)
            canvas.drawRoundRect(32f, y, pageW - 32f, y + 132f, 12f, 12f, borderPaint)
            canvas.drawText("Financial Breakdown Summary", 48f, y + 22f, sectionPaint)

            fun drawMetric(x: Float, rowY: Float, lbl: String, amt: Double, boldColor: TextPaint = valuePaint) {
                canvas.drawText(lbl, x, rowY, labelPaint)
                val formatted = TextUtils.ellipsize("₦${"%,.2f".format(amt)}", boldColor, 160f, TextUtils.TruncateAt.END).toString()
                canvas.drawText(formatted, x, rowY + 15f, boldColor)
            }
            drawMetric(48f, y + 44f, "Total Sales / Income", summary.totalSales)
            drawMetric(220f, y + 44f, "Purchases / Stock", summary.totalPurchases)
            drawMetric(395f, y + 44f, "Expenditure / Bills", summary.totalExpenditure)
            drawMetric(48f, y + 88f, "Refunds / Other", summary.totalRefunds + summary.totalOther)
            drawMetric(220f, y + 88f, "Gross Profit", summary.grossProfit)
            canvas.drawText("Net Profit (${"%.1f".format(summary.profitMarginPercent)}% margin)", 395f, y + 88f, labelPaint)
            canvas.drawText(
                TextUtils.ellipsize("₦${"%,.2f".format(summary.netProfit)}", profitPaint, 160f, TextUtils.TruncateAt.END).toString(),
                395f,
                y + 106f,
                profitPaint
            )

            y += 152f
            canvas.drawText("Itemized Receipt Breakdown", 32f, y, sectionPaint)
            y += 12f

            val tableHeaderPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                textSize = 9.5f
                isFakeBoldText = true
            }
            val cellTxt = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#0F172A")
                textSize = 9f
            }

            fun drawTableHeader(topY: Float) {
                canvas.drawRect(32f, topY, pageW - 32f, topY + 22f, emeraldHeader)
                canvas.drawText("Category", 40f, topY + 15f, tableHeaderPaint)
                canvas.drawText("Party / Item", 132f, topY + 15f, tableHeaderPaint)
                canvas.drawText("Method / Ref", 315f, topY + 15f, tableHeaderPaint)
                canvas.drawText("Amount", 475f, topY + 15f, tableHeaderPaint)
            }

            drawTableHeader(y)
            y += 24f

            var currentPage = page1
            var currentCanvas = canvas
            var pageNum = 1
            entries.forEachIndexed { idx, item ->
                if (y > pageH - 56f) {
                    currentCanvas.drawText("ReceiptsNow Breakdown • Page $pageNum", 32f, pageH - 20f, labelPaint)
                    doc.finishPage(currentPage)
                    pageNum += 1
                    currentPage = doc.startPage(PdfDocument.PageInfo.Builder(pageW, pageH, pageNum).create())
                    currentCanvas = currentPage.canvas
                    y = 36f
                    drawTableHeader(y)
                    y += 24f
                }
                if (idx % 2 == 1) {
                    currentCanvas.drawRect(32f, y, pageW - 32f, y + 22f, altBg)
                }
                val catTxt = TextUtils.ellipsize(item.categoryEnum().shortLabel, cellTxt, 84f, TextUtils.TruncateAt.END).toString()
                val partyTxt = TextUtils.ellipsize(
                    listOf(item.merchantOrParty, item.notes).filter { it.isNotBlank() }.joinToString(" - "),
                    cellTxt,
                    174f,
                    TextUtils.TruncateAt.END
                ).toString()
                val refTxt = TextUtils.ellipsize(
                    listOf(item.paymentMethod, item.reference).filter { it.isNotBlank() }.joinToString(" • ").ifBlank { item.sourceType },
                    cellTxt,
                    150f,
                    TextUtils.TruncateAt.END
                ).toString()
                val amtPrefix = if (item.categoryEnum().isInflow) "+" else "-"
                val amtTxt = "$amtPrefix₦${"%,.2f".format(item.amount)}"

                currentCanvas.drawText(catTxt, 40f, y + 15f, cellTxt)
                currentCanvas.drawText(partyTxt, 132f, y + 15f, cellTxt)
                currentCanvas.drawText(refTxt, 315f, y + 15f, cellTxt)
                currentCanvas.drawText(amtTxt, 475f, y + 15f, cellTxt)
                y += 22f
            }

            currentCanvas.drawText(
                "Generated by ReceiptsNow Day Tally • $bizName • Page $pageNum",
                32f,
                pageH - 20f,
                labelPaint
            )
            doc.finishPage(currentPage)
            FileOutputStream(file).use { out -> doc.writeTo(out) }
            wroteNativePdf = true
        } catch (_: Throwable) {
            wroteNativePdf = false
        } finally {
            runCatching { doc.close() }
        }

        if (!wroteNativePdf || !file.exists() || file.length() == 0L) {
            val fallbackLines = buildList {
                add(title.ifBlank { "End-of-Day Receipt Breakdown Sheet" })
                add("$bizName | Date: $ledgerDate | Items: ${summary.entryCount}")
                add("Total Sales: NGN ${"%,.2f".format(summary.totalSales)}")
                add("Purchases: NGN ${"%,.2f".format(summary.totalPurchases)}")
                add("Expenditure: NGN ${"%,.2f".format(summary.totalExpenditure)}")
                add("Refunds/Other: NGN ${"%,.2f".format(summary.totalRefunds + summary.totalOther)}")
                add("Gross Profit: NGN ${"%,.2f".format(summary.grossProfit)}")
                add("Net Profit: NGN ${"%,.2f".format(summary.netProfit)} (${"%.1f".format(summary.profitMarginPercent)}%)")
                add("--- Itemized Breakdown ---")
                entries.forEach { e ->
                    add("${e.categoryEnum().shortLabel} | ${e.merchantOrParty} | ${"%.2f".format(Locale.US, e.amount)} | ${e.notes}")
                }
            }
            writeFallbackPdf(file, fallbackLines)
        }

        val uri = FileProvider.getUriForFile(context, authority(context), file)
        launchExportShare(context, uri, "application/pdf", "Export Day Tally PDF Sheet")
        uri
    }

    fun exportDailyLedgerXlsxAndShare(
        context: Context,
        business: Business,
        title: String,
        ledgerDate: String,
        entries: List<com.example.data.model.LedgerEntryItem>,
        summary: com.example.data.model.LedgerCalculationSummary
    ): Result<Uri> {
        val headers = listOf("Category", "Party / Merchant", "Amount", "Currency", "Payment Method", "Reference", "Date", "Notes", "Source")
        val itemRows = entries.map { item ->
            listOf(
                item.categoryEnum().label,
                item.merchantOrParty,
                "%.2f".format(Locale.US, item.amount),
                item.currency.ifBlank { business.defaultCurrency.ifBlank { "NGN" } },
                item.paymentMethod,
                item.reference,
                item.transactionDate.ifBlank { ledgerDate },
                item.notes,
                item.documentName.ifBlank { item.sourceType }
            )
        }
        val summaryRows = listOf(
            listOf("--- SUMMARY ($title) ---", ledgerDate, "", "", "", "", "", "", ""),
            listOf("Total Sales / Income", "", "%.2f".format(Locale.US, summary.totalSales), "NGN", "", "", "", "", ""),
            listOf("Total Purchases / Stock", "", "%.2f".format(Locale.US, summary.totalPurchases), "NGN", "", "", "", "", ""),
            listOf("Total Expenditure / Bills", "", "%.2f".format(Locale.US, summary.totalExpenditure), "NGN", "", "", "", "", ""),
            listOf("Refunds & Other", "", "%.2f".format(Locale.US, summary.totalRefunds + summary.totalOther), "NGN", "", "", "", "", ""),
            listOf("Gross Profit (Sales - Purchases)", "", "%.2f".format(Locale.US, summary.grossProfit), "NGN", "", "", "", "", ""),
            listOf("Net Profit / (Loss)", "", "%.2f".format(Locale.US, summary.netProfit), "NGN", "${"%.1f".format(Locale.US, summary.profitMarginPercent)}%", "", "", "", "")
        )
        return exportXlsxAndShare(
            context = context,
            filePrefix = "ReceiptsNow_DayTally_${sanitizeFilePart(ledgerDate, "sheet")}",
            sheetTitle = "Day Tally",
            headers = headers,
            rows = itemRows + summaryRows
        )
    }
}
