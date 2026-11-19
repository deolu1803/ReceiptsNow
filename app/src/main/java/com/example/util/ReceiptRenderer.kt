package com.example.util

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import com.example.data.model.Business
import com.example.data.model.ReceiptRecord
import com.example.data.model.ReceiptTemplateType
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

object ReceiptRenderer {

    private const val WIDTH = 1600
    private const val MIN_HEIGHT = 1120

    fun renderLandscapeReceipt(
        business: Business,
        receipt: ReceiptRecord,
        templateOverride: ReceiptTemplateType? = null
    ): Bitmap {
        val template = templateOverride
            ?: runCatching { ReceiptTemplateType.valueOf(receipt.templateType) }.getOrNull()
            ?: runCatching { ReceiptTemplateType.valueOf(business.defaultTemplate) }.getOrNull()
            ?: ReceiptTemplateType.STANDARD

        val primaryColor = BrandColorExtractor.parseColorSafe(
            business.primaryColorHex.ifBlank { receipt.businessPrimaryHexSnapshot },
            Color.parseColor("#0B203B")
        )
        val secondaryColor = BrandColorExtractor.parseColorSafe(
            business.secondaryColorHex,
            Color.parseColor("#047857")
        )
        val accentColor = BrandColorExtractor.parseColorSafe(
            business.accentColorHex.ifBlank { receipt.businessAccentHexSnapshot },
            Color.parseColor("#C89B3C")
        )
        val bgColor = BrandColorExtractor.parseColorSafe(business.backgroundColorHex, Color.WHITE)
        val inkColor = BrandColorExtractor.parseColorSafe(business.textColorHex, Color.parseColor("#0F172A"))
        val onPrimary = BrandColorExtractor.contrastTextFor(primaryColor)

        val logoBitmap = BrandColorExtractor.decodeBase64Bitmap(business.logoBase64)
        val signatureBitmap = BrandColorExtractor.decodeBase64Bitmap(business.signatureBase64)
        val qrUrl = "receiptsnow://verify?code=${receipt.verificationCode}"
        val qrBitmap = if (business.showQrCode) generateQrBitmap(qrUrl, 210) else null

        // Calculate extra height dynamically if narration or purpose is long
        val extraTextLines = ((receipt.paymentPurpose.length + receipt.narrationSnapshot.length + receipt.notes.length) / 90).coerceAtLeast(0)
        val height = (MIN_HEIGHT + extraTextLines * 26).coerceAtLeast(MIN_HEIGHT)

        val bitmap = Bitmap.createBitmap(WIDTH, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(bgColor)

        try {
            when (template) {
                ReceiptTemplateType.STANDARD -> drawStandardTemplate(
                    canvas, height, business, receipt, primaryColor, secondaryColor, accentColor,
                    inkColor, onPrimary, logoBitmap, signatureBitmap, qrBitmap
                )
                ReceiptTemplateType.PREMIUM_CORPORATE -> drawCorporateTemplate(
                    canvas, height, business, receipt, primaryColor, secondaryColor, accentColor,
                    inkColor, onPrimary, logoBitmap, signatureBitmap, qrBitmap
                )
                ReceiptTemplateType.COMPACT -> drawCompactTemplate(
                    canvas, height, business, receipt, primaryColor, secondaryColor, accentColor,
                    inkColor, onPrimary, logoBitmap, signatureBitmap, qrBitmap
                )
                ReceiptTemplateType.MODERN_CLASSIC -> drawModernClassicTemplate(
                    canvas, height, business, receipt, primaryColor, secondaryColor, accentColor,
                    inkColor, onPrimary, logoBitmap, signatureBitmap, qrBitmap
                )
                ReceiptTemplateType.ROYAL_CERTIFICATE -> drawRoyalCertificateTemplate(
                    canvas, height, business, receipt, primaryColor, secondaryColor, accentColor,
                    inkColor, onPrimary, logoBitmap, signatureBitmap, qrBitmap
                )
                ReceiptTemplateType.FINTECH_WAVE -> drawFintechWaveTemplate(
                    canvas, height, business, receipt, primaryColor, secondaryColor, accentColor,
                    inkColor, onPrimary, logoBitmap, signatureBitmap, qrBitmap
                )
            }
        } finally {
            if (logoBitmap != null && !logoBitmap.isRecycled) logoBitmap.recycle()
            if (signatureBitmap != null && !signatureBitmap.isRecycled) signatureBitmap.recycle()
            if (qrBitmap != null && !qrBitmap.isRecycled) qrBitmap.recycle()
        }

        return bitmap
    }

    private fun drawStandardTemplate(
        canvas: Canvas,
        height: Int,
        business: Business,
        receipt: ReceiptRecord,
        primaryColor: Int,
        secondaryColor: Int,
        accentColor: Int,
        inkColor: Int,
        onPrimary: Int,
        logoBitmap: Bitmap?,
        signatureBitmap: Bitmap?,
        qrBitmap: Bitmap?
    ) {
        val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = primaryColor
            style = Paint.Style.STROKE
            strokeWidth = 4f
        }
        canvas.drawRoundRect(RectF(24f, 24f, WIDTH - 24f, height - 24f), 18f, 18f, borderPaint)

        // Header Banner
        val headerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = primaryColor }
        canvas.drawRoundRect(RectF(40f, 40f, WIDTH - 40f, 235f), 14f, 14f, headerPaint)

        // Accent strip
        val accentPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = accentColor }
        canvas.drawRect(40f, 225f, WIDTH - 40f, 237f, accentPaint)

        // Business Logo
        var textStartX = 72f
        if (logoBitmap != null) {
            val logoBox = RectF(68f, 64f, 212f, 208f)
            val whiteBg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
            canvas.drawRoundRect(logoBox, 14f, 14f, whiteBg)
            drawBitmapCenterFit(canvas, logoBitmap, RectF(78f, 74f, 202f, 198f))
            textStartX = 240f
        }

        // Business Title & Contact
        val bizName = business.tradingName.ifBlank { business.registeredName }
        drawText(canvas, bizName.uppercase(), textStartX, 74f, 34f, onPrimary, bold = true, maxWidth = 800)
        val contactLine1 = listOfNotNull(
            business.address.takeIf { it.isNotBlank() },
            business.cacNumber.takeIf { it.isNotBlank() }?.let { "CAC: $it" }
        ).joinToString("  |  ")
        if (contactLine1.isNotBlank()) {
            drawText(canvas, contactLine1, textStartX, 124f, 19f, onPrimary, alpha = 230, maxWidth = 800)
        }
        val contactLine2 = listOfNotNull(
            business.phone.takeIf { it.isNotBlank() },
            business.email.takeIf { it.isNotBlank() },
            business.website.takeIf { it.isNotBlank() }
        ).joinToString("  •  ")
        if (contactLine2.isNotBlank()) {
            drawText(canvas, contactLine2, textStartX, 158f, 18f, onPrimary, alpha = 215, maxWidth = 800)
        }

        // Right Badge: Official Payment Receipt
        val badgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = accentColor }
        canvas.drawRoundRect(RectF(WIDTH - 460f, 64f, WIDTH - 68f, 122f), 10f, 10f, badgePaint)
        drawText(canvas, "OFFICIAL PAYMENT RECEIPT", WIDTH - 442f, 79f, 22f, Color.parseColor("#0F172A"), bold = true, maxWidth = 370)
        drawText(canvas, "NO: ${receipt.receiptNumber}", WIDTH - 460f, 138f, 21f, onPrimary, bold = true, mono = true, maxWidth = 400, maxLines = 1)
        drawText(canvas, "Issued: ${formatIssuedDate(receipt)}", WIDTH - 460f, 174f, 17f, onPrimary, alpha = 225, maxWidth = 400, maxLines = 1)

        // Two Column Metadata Cards
        val cardFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#F8FAFC") }
        val cardStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#CBD5E1")
            style = Paint.Style.STROKE
            strokeWidth = 2f
        }

        val leftCard = RectF(44f, 265f, 780f, 540f)
        val rightCard = RectF(808f, 265f, WIDTH - 44f, 540f)
        canvas.drawRoundRect(leftCard, 12f, 12f, cardFill)
        canvas.drawRoundRect(leftCard, 12f, 12f, cardStroke)
        canvas.drawRoundRect(rightCard, 12f, 12f, cardFill)
        canvas.drawRoundRect(rightCard, 12f, 12f, cardStroke)

        // Left: Customer & Payment Summary (dynamic Y to prevent overlap)
        var ly = 282f
        ly += drawText(canvas, "RECEIVED FROM (CUSTOMER)", 68f, ly, 16f, secondaryColor, bold = true, maxWidth = 680)
        ly += 6f
        ly += drawText(canvas, receipt.customerName, 68f, ly, 25f, inkColor, bold = true, maxWidth = 680, maxLines = 1)
        ly += 4f
        if (receipt.customerPhone.isNotBlank()) {
            ly += drawText(canvas, "Phone: ${receipt.customerPhone}", 68f, ly, 18f, Color.parseColor("#475569"), maxWidth = 680, maxLines = 1)
        }
        if (receipt.customerEmail.isNotBlank()) {
            ly += drawText(canvas, "Email: ${receipt.customerEmail}", 68f, ly, 18f, Color.parseColor("#475569"), maxWidth = 680, maxLines = 1)
        }
        ly = maxOf(ly + 10f, 412f)
        ly += drawText(canvas, "PURPOSE OF PAYMENT", 68f, ly, 16f, secondaryColor, bold = true, maxWidth = 680)
        ly += 4f
        drawText(canvas, receipt.paymentPurpose, 68f, ly, 19f, inkColor, bold = true, maxWidth = 680, maxLines = 2)
        val docRefs = listOfNotNull(
            receipt.invoiceNumber.takeIf { it.isNotBlank() }?.let { "Invoice: $it" },
            receipt.orderNumber.takeIf { it.isNotBlank() }?.let { "Order: $it" }
        ).joinToString("  |  ")
        if (docRefs.isNotBlank()) {
            drawText(canvas, docRefs, 68f, 506f, 16f, Color.parseColor("#475569"), maxWidth = 680, maxLines = 1)
        }

        // Right: Bank Transaction Details
        drawText(canvas, "BANK TRANSACTION TRACEABILITY", 832f, 286f, 17f, secondaryColor, bold = true)
        val bankRows = listOf(
            "Sending Bank" to receipt.senderBankSnapshot.ifBlank { "—" },
            "Sender Account" to receipt.senderAccountNameSnapshot.ifBlank { "—" },
            "Beneficiary Bank" to receipt.beneficiaryBankSnapshot.ifBlank { "—" },
            "Tx Date / Time" to "${receipt.transactionDateSnapshot} ${receipt.transactionTimeSnapshot}".trim().ifBlank { "—" },
            "Reference" to receipt.transactionRefSnapshot.ifBlank { "—" },
            "Session ID" to receipt.sessionIdSnapshot.ifBlank { "—" },
            "Channel / Status" to "${receipt.transferChannelSnapshot.ifBlank { "Bank Transfer" }} • ${receipt.verificationStatus.replace("_", " ")}"
        )
        var ry = 320f
        for ((label, value) in bankRows) {
            drawText(canvas, "$label:", 832f, ry, 17f, Color.parseColor("#64748B"), bold = true)
            drawText(canvas, value, 1020f, ry, 17f, inkColor, mono = (label == "Reference" || label == "Session ID"), maxWidth = 500)
            ry += 30f
        }

        // Amount Box
        val amountBox = RectF(44f, 565f, WIDTH - 44f, 700f)
        val amountBg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = primaryColor
            alpha = 18
        }
        canvas.drawRoundRect(amountBox, 14f, 14f, amountBg)
        canvas.drawRoundRect(amountBox, 14f, 14f, borderPaint)

        drawText(canvas, "AMOUNT RECEIVED", 72f, 586f, 18f, secondaryColor, bold = true)
        val amountFormatted = "${currencySymbol(receipt.currency)}${"%,.2f".format(receipt.amountPaid)}"
        drawText(canvas, amountFormatted, 72f, 616f, 44f, primaryColor, bold = true, mono = true)

        drawText(canvas, "AMOUNT IN WORDS", 540f, 586f, 17f, secondaryColor, bold = true)
        drawText(
            canvas,
            receipt.amountInWords.ifBlank { AmountInWords.toNairaWords(receipt.amountPaid, receipt.currency) },
            540f,
            618f,
            21f,
            inkColor,
            bold = true,
            maxWidth = 980
        )

        // Narration & Acknowledgement
        var midY = 724f
        if (receipt.narrationSnapshot.isNotBlank()) {
            drawText(canvas, "Bank Narration: ${receipt.narrationSnapshot}", 48f, midY, 18f, Color.parseColor("#475569"), maxWidth = WIDTH - 96)
            midY += 40f
        }
        val ack = business.defaultAcknowledgement.ifBlank {
            "Received with thanks from the above-named customer the sum stated herein for the service or goods described in this receipt."
        }
        drawText(canvas, ack, 48f, midY, 19f, inkColor, maxWidth = WIDTH - 96)

        drawCommonFooter(canvas, height, business, receipt, primaryColor, accentColor, inkColor, signatureBitmap, qrBitmap)
    }

    private fun drawCorporateTemplate(
        canvas: Canvas,
        height: Int,
        business: Business,
        receipt: ReceiptRecord,
        primaryColor: Int,
        secondaryColor: Int,
        accentColor: Int,
        inkColor: Int,
        onPrimary: Int,
        logoBitmap: Bitmap?,
        signatureBitmap: Bitmap?,
        qrBitmap: Bitmap?
    ) {
        // Left Executive Vertical Pillar
        val pillarPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = primaryColor }
        canvas.drawRect(0f, 0f, 340f, height.toFloat(), pillarPaint)
        val goldLine = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = accentColor }
        canvas.drawRect(340f, 0f, 352f, height.toFloat(), goldLine)

        // Logo inside left pillar
        if (logoBitmap != null) {
            val whiteCircle = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
            canvas.drawRoundRect(RectF(60f, 60f, 280f, 280f), 24f, 24f, whiteCircle)
            drawBitmapCenterFit(canvas, logoBitmap, RectF(76f, 76f, 264f, 264f))
        }

        val bizName = business.tradingName.ifBlank { business.registeredName }
        var py = 310f
        py += drawText(canvas, bizName, 40f, py, 25f, onPrimary, bold = true, maxWidth = 265, maxLines = 2)
        py += 10f
        if (business.businessSlogan.isNotBlank()) {
            py += drawText(canvas, business.businessSlogan, 40f, py, 16f, accentColor, maxWidth = 265, maxLines = 3)
            py += 14f
        }
        val pillarContact = listOfNotNull(
            business.address.takeIf { it.isNotBlank() },
            business.phone.takeIf { it.isNotBlank() },
            business.email.takeIf { it.isNotBlank() },
            business.website.takeIf { it.isNotBlank() },
            business.cacNumber.takeIf { it.isNotBlank() }?.let { "RC/BN: $it" }
        ).joinToString("\n\n")
        val maxContactLines = (((height - 320f) - py) / 22f).toInt().coerceAtLeast(3)
        drawText(canvas, pillarContact, 40f, py, 15.5f, onPrimary, alpha = 220, maxWidth = 265, maxLines = maxContactLines)

        if (qrBitmap != null) {
            val qrBg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
            canvas.drawRoundRect(RectF(65f, height - 295f, 275f, height - 85f), 14f, 14f, qrBg)
            canvas.drawBitmap(qrBitmap, null, RectF(78f, height - 282f, 262f, height - 98f), null)
            drawText(canvas, "Scan to Verify", 102f, height - 72f, 16f, onPrimary, bold = true)
        }

        // Right Corporate Canvas
        drawText(canvas, "OFFICIAL PAYMENT RECEIPT", 396f, 58f, 34f, primaryColor, bold = true)
        drawText(canvas, "RECEIPT NO: ${receipt.receiptNumber}", 396f, 108f, 21f, secondaryColor, bold = true, mono = true)
        drawText(canvas, "Issued: ${formatIssuedDate(receipt)}  |  Status: ${receipt.verificationStatus.replace("_", " ")}", 396f, 142f, 18f, Color.parseColor("#475569"))

        // Amount Highlight Pill
        val amtBox = RectF(396f, 190f, WIDTH - 48f, 325f)
        val amtFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#F1F5F9") }
        val amtStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = accentColor
            style = Paint.Style.STROKE
            strokeWidth = 3f
        }
        canvas.drawRoundRect(amtBox, 14f, 14f, amtFill)
        canvas.drawRoundRect(amtBox, 14f, 14f, amtStroke)
        drawText(canvas, "TOTAL AMOUNT PAID", 424f, 212f, 16f, secondaryColor, bold = true)
        drawText(canvas, "${currencySymbol(receipt.currency)}${"%,.2f".format(receipt.amountPaid)}", 424f, 240f, 42f, primaryColor, bold = true, mono = true, maxWidth = 400, maxLines = 1)
        drawText(canvas, receipt.amountInWords.ifBlank { AmountInWords.toNairaWords(receipt.amountPaid, receipt.currency) }, 840f, 224f, 18f, inkColor, bold = true, maxWidth = 680, maxLines = 3)

        // Customer & Banking Audit Grid
        drawText(canvas, "CUSTOMER INFORMATION", 396f, 360f, 17f, secondaryColor, bold = true)
        drawText(canvas, receipt.customerName, 396f, 392f, 25f, inkColor, bold = true, maxWidth = 520, maxLines = 1)
        drawText(canvas, "${receipt.customerPhone}  •  ${receipt.customerEmail}".trim(' ', '•'), 396f, 430f, 18f, Color.parseColor("#475569"), maxWidth = 520, maxLines = 1)
        drawText(canvas, "Purpose: ${receipt.paymentPurpose}", 396f, 466f, 19f, inkColor, bold = true, maxWidth = 520, maxLines = 3)

        drawText(canvas, "BANK SETTLEMENT AUDIT", 970f, 360f, 17f, secondaryColor, bold = true)
        val rows = listOf(
            "Sender: ${receipt.senderAccountNameSnapshot.ifBlank { "—" }} (${receipt.senderBankSnapshot.ifBlank { "—" }})",
            "Beneficiary Bank: ${receipt.beneficiaryBankSnapshot.ifBlank { "—" }}",
            "Tx Reference: ${receipt.transactionRefSnapshot.ifBlank { "—" }}",
            "Session ID: ${receipt.sessionIdSnapshot.ifBlank { "—" }}",
            "Tx Timestamp: ${receipt.transactionDateSnapshot} ${receipt.transactionTimeSnapshot}".trim(),
            "Channel: ${receipt.transferChannelSnapshot.ifBlank { "Bank Transfer" }}"
        )
        var y = 392f
        for (row in rows) {
            y += drawText(canvas, row, 970f, y, 17f, inkColor, maxWidth = 570, maxLines = 2) + 8f
        }

        val ack = business.defaultAcknowledgement.ifBlank {
            "Received with thanks from the above-named customer the sum stated herein for the service or goods described in this receipt."
        }
        drawText(canvas, ack, 396f, maxOf(640f, y + 28f), 19f, inkColor, maxWidth = WIDTH - 444, maxLines = 4)

        // Corporate Footer Signatory
        if (business.showSignature) {
            if (signatureBitmap != null) {
                drawBitmapCenterFit(canvas, signatureBitmap, RectF(396f, height - 210f, 620f, height - 115f))
            }
            val linePaint = Paint().apply { color = Color.parseColor("#94A3B8"); strokeWidth = 2f }
            canvas.drawLine(396f, height - 108f, 700f, height - 108f, linePaint)
            drawText(canvas, "Authorised Signatory: ${business.authorisedRepName.ifBlank { business.ownerName }}", 396f, height - 96f, 17f, inkColor, bold = true)
        }
        if (business.stampText.isNotBlank()) {
            val stampPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = secondaryColor
                style = Paint.Style.STROKE
                strokeWidth = 3f
            }
            canvas.drawRoundRect(RectF(WIDTH - 360f, height - 190f, WIDTH - 60f, height - 100f), 12f, 12f, stampPaint)
            drawText(canvas, business.stampText.uppercase(), WIDTH - 340f, height - 160f, 18f, secondaryColor, bold = true, maxWidth = 260)
        }
        drawText(canvas, "Business-issued payment acknowledgement powered by ReceiptsNow.", 396f, height - 48f, 15f, Color.parseColor("#64748B"))
    }

    private fun drawCompactTemplate(
        canvas: Canvas,
        height: Int,
        business: Business,
        receipt: ReceiptRecord,
        primaryColor: Int,
        secondaryColor: Int,
        accentColor: Int,
        inkColor: Int,
        onPrimary: Int,
        logoBitmap: Bitmap?,
        signatureBitmap: Bitmap?,
        qrBitmap: Bitmap?
    ) {
        // Bold top & bottom accent ribbons
        val topPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = primaryColor }
        canvas.drawRect(0f, 0f, WIDTH.toFloat(), 165f, topPaint)
        val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = accentColor }
        canvas.drawRect(0f, 165f, WIDTH.toFloat(), 177f, barPaint)

        var xStart = 56f
        if (logoBitmap != null) {
            val logoBg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
            canvas.drawRoundRect(RectF(50f, 28f, 158f, 136f), 16f, 16f, logoBg)
            drawBitmapCenterFit(canvas, logoBitmap, RectF(58f, 36f, 150f, 128f))
            xStart = 184f
        }
        val bizName = business.tradingName.ifBlank { business.registeredName }
        drawText(canvas, bizName, xStart, 42f, 32f, onPrimary, bold = true, maxWidth = 760)
        drawText(canvas, listOf(business.phone, business.email).filter { it.isNotBlank() }.joinToString("  •  "), xStart, 90f, 19f, onPrimary, alpha = 220, maxWidth = 760)

        drawText(canvas, "EXPRESS PAYMENT RECEIPT", WIDTH - 460f, 40f, 21f, accentColor, bold = true, maxWidth = 420)
        drawText(canvas, receipt.receiptNumber, WIDTH - 460f, 76f, 23f, onPrimary, bold = true, mono = true, maxWidth = 420, maxLines = 1)
        drawText(canvas, formatIssuedDate(receipt), WIDTH - 460f, 114f, 17f, onPrimary, alpha = 210, maxWidth = 420, maxLines = 1)

        // Hero Amount Banner
        val heroBox = RectF(56f, 215f, WIDTH - 56f, 385f)
        val heroBg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = secondaryColor; alpha = 22 }
        val heroStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = secondaryColor; style = Paint.Style.STROKE; strokeWidth = 3f }
        canvas.drawRoundRect(heroBox, 18f, 18f, heroBg)
        canvas.drawRoundRect(heroBox, 18f, 18f, heroStroke)

        drawText(canvas, "PAID BY ${receipt.customerName.uppercase()}", 88f, 240f, 24f, inkColor, bold = true, maxWidth = 740, maxLines = 1)
        drawText(canvas, receipt.paymentPurpose, 88f, 286f, 21f, Color.parseColor("#475569"), maxWidth = 740, maxLines = 2)
        drawText(canvas, "${currencySymbol(receipt.currency)}${"%,.2f".format(receipt.amountPaid)}", WIDTH - 520f, 245f, 48f, primaryColor, bold = true, mono = true, maxWidth = 440, maxLines = 1)
        drawText(canvas, receipt.verificationStatus.replace("_", " "), WIDTH - 520f, 322f, 19f, secondaryColor, bold = true, maxWidth = 440)

        // Essential Banking Strip
        val stripY = 430f
        drawText(canvas, "ESSENTIAL BANKING DETAILS", 56f, stripY, 18f, secondaryColor, bold = true)
        drawText(canvas, "Sending Bank: ${receipt.senderBankSnapshot.ifBlank { "—" }}   |   Sender Name: ${receipt.senderAccountNameSnapshot.ifBlank { "—" }}", 56f, stripY + 38f, 20f, inkColor, maxWidth = WIDTH - 112)
        drawText(canvas, "Beneficiary Bank: ${receipt.beneficiaryBankSnapshot.ifBlank { "—" }}   |   Reference: ${receipt.transactionRefSnapshot.ifBlank { "—" }}", 56f, stripY + 76f, 20f, inkColor, mono = false, maxWidth = WIDTH - 112)
        drawText(canvas, "Session ID: ${receipt.sessionIdSnapshot.ifBlank { "—" }}   |   Bank Tx Date: ${receipt.transactionDateSnapshot} ${receipt.transactionTimeSnapshot}", 56f, stripY + 114f, 19f, Color.parseColor("#475569"), maxWidth = WIDTH - 112)

        drawText(canvas, "In Words: ${receipt.amountInWords.ifBlank { AmountInWords.toNairaWords(receipt.amountPaid, receipt.currency) }}", 56f, stripY + 170f, 20f, primaryColor, bold = true, maxWidth = WIDTH - 112)

        drawCommonFooter(canvas, height, business, receipt, primaryColor, accentColor, inkColor, signatureBitmap, qrBitmap)
    }

    private fun drawModernClassicTemplate(
        canvas: Canvas,
        height: Int,
        business: Business,
        receipt: ReceiptRecord,
        primaryColor: Int,
        secondaryColor: Int,
        accentColor: Int,
        inkColor: Int,
        onPrimary: Int,
        logoBitmap: Bitmap?,
        signatureBitmap: Bitmap?,
        qrBitmap: Bitmap?
    ) {
        // Editorial top dual hairline + soft tinted canvas card
        val topBar = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = primaryColor }
        val goldBar = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = accentColor }
        canvas.drawRoundRect(RectF(40f, 32f, WIDTH - 40f, 44f), 6f, 6f, topBar)
        canvas.drawRoundRect(RectF(40f, 48f, WIDTH - 40f, 54f), 3f, 3f, goldBar)

        // Centered Circular / Rounded Crest Logo
        val bizName = business.tradingName.ifBlank { business.registeredName }
        if (logoBitmap != null) {
            val badgeBg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#F8FAFC") }
            val badgeBorder = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = primaryColor
                style = Paint.Style.STROKE
                strokeWidth = 3f
            }
            canvas.drawRoundRect(RectF(64f, 76f, 184f, 196f), 60f, 60f, badgeBg)
            canvas.drawRoundRect(RectF(64f, 76f, 184f, 196f), 60f, 60f, badgeBorder)
            drawBitmapCenterFit(canvas, logoBitmap, RectF(80f, 92f, 168f, 180f))
        } else {
            val crestPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = primaryColor }
            canvas.drawRoundRect(RectF(64f, 80f, 176f, 192f), 56f, 56f, crestPaint)
            val initials = bizName.split(" ").filter { it.isNotBlank() }.take(2)
                .joinToString("") { it.first().uppercase() }.ifBlank { "RCN" }
            drawText(canvas, initials, 88f, 114f, 36f, onPrimary, bold = true, maxWidth = 80, maxLines = 1)
        }

        // Clean typographic header beside crest
        drawText(canvas, bizName.uppercase(), 216f, 84f, 33f, primaryColor, bold = true, maxWidth = 760, maxLines = 1)
        val subLine1 = listOfNotNull(
            business.address.takeIf { it.isNotBlank() },
            business.cacNumber.takeIf { it.isNotBlank() }?.let { "RC/BN: $it" }
        ).joinToString("  •  ")
        if (subLine1.isNotBlank()) {
            drawText(canvas, subLine1, 216f, 130f, 18f, Color.parseColor("#475569"), maxWidth = 760, maxLines = 1)
        }
        val subLine2 = listOfNotNull(
            business.phone.takeIf { it.isNotBlank() },
            business.email.takeIf { it.isNotBlank() },
            business.website.takeIf { it.isNotBlank() }
        ).joinToString("  |  ")
        if (subLine2.isNotBlank()) {
            drawText(canvas, subLine2, 216f, 160f, 17f, Color.parseColor("#64748B"), maxWidth = 760, maxLines = 1)
        }

        // Right Editorial Pill Summary
        val pillBg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = secondaryColor; alpha = 24 }
        val pillStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = secondaryColor
            style = Paint.Style.STROKE
            strokeWidth = 2.5f
        }
        canvas.drawRoundRect(RectF(WIDTH - 510f, 76f, WIDTH - 56f, 196f), 24f, 24f, pillBg)
        canvas.drawRoundRect(RectF(WIDTH - 510f, 76f, WIDTH - 56f, 196f), 24f, 24f, pillStroke)
        drawText(canvas, "PAYMENT RECEIPT", WIDTH - 480f, 92f, 18f, secondaryColor, bold = true, maxWidth = 390)
        drawText(canvas, receipt.receiptNumber, WIDTH - 480f, 122f, 22f, inkColor, bold = true, mono = true, maxWidth = 400, maxLines = 1)
        drawText(canvas, "${formatIssuedDate(receipt)}  •  ${receipt.verificationStatus.replace("_", " ")}", WIDTH - 480f, 158f, 15.5f, Color.parseColor("#475569"), maxWidth = 410, maxLines = 1)

        // Hero Rounded Amount Strip
        val heroCard = RectF(56f, 226f, WIDTH - 56f, 368f)
        val heroFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = primaryColor }
        canvas.drawRoundRect(heroCard, 28f, 28f, heroFill)
        drawText(canvas, "TOTAL AMOUNT SETTLED", 92f, 248f, 16f, accentColor, bold = true)
        drawText(canvas, "${currencySymbol(receipt.currency)}${"%,.2f".format(receipt.amountPaid)}", 92f, 278f, 48f, onPrimary, bold = true, mono = true, maxWidth = 520, maxLines = 1)

        drawText(canvas, "IN WORDS", 650f, 248f, 16f, accentColor, bold = true)
        drawText(
            canvas,
            receipt.amountInWords.ifBlank { AmountInWords.toNairaWords(receipt.amountPaid, receipt.currency) },
            650f,
            278f,
            20f,
            onPrimary,
            bold = true,
            maxWidth = 850,
            maxLines = 3
        )

        // Two Soft Rounded Detail Cards
        val boxBg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#F8FAFC") }
        val boxBorder = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#E2E8F0")
            style = Paint.Style.STROKE
            strokeWidth = 2f
        }
        val cBox = RectF(56f, 396f, 770f, 740f)
        val bBox = RectF(800f, 396f, WIDTH - 56f, 740f)
        canvas.drawRoundRect(cBox, 26f, 26f, boxBg)
        canvas.drawRoundRect(cBox, 26f, 26f, boxBorder)
        canvas.drawRoundRect(bBox, 26f, 26f, boxBg)
        canvas.drawRoundRect(bBox, 26f, 26f, boxBorder)

        var cy = 420f
        cy += drawText(canvas, "CUSTOMER & DESCRIPTION", 84f, cy, 16f, secondaryColor, bold = true)
        cy += 8f
        cy += drawText(canvas, receipt.customerName, 84f, cy, 26f, inkColor, bold = true, maxWidth = 640, maxLines = 1)
        cy += 6f
        val contact = listOfNotNull(
            receipt.customerPhone.takeIf { it.isNotBlank() },
            receipt.customerEmail.takeIf { it.isNotBlank() }
        ).joinToString("  •  ")
        if (contact.isNotBlank()) {
            cy += drawText(canvas, contact, 84f, cy, 18f, Color.parseColor("#475569"), maxWidth = 640, maxLines = 1)
            cy += 10f
        }
        cy = maxOf(cy + 10f, 520f)
        cy += drawText(canvas, "ITEM / SERVICE SETTLED", 84f, cy, 15.5f, secondaryColor, bold = true)
        cy += 6f
        cy += drawText(canvas, receipt.paymentPurpose, 84f, cy, 20f, inkColor, bold = true, maxWidth = 640, maxLines = 3)
        if (receipt.narrationSnapshot.isNotBlank()) {
            drawText(canvas, "Bank Narration: ${receipt.narrationSnapshot}", 84f, minOf(cy + 12f, 675f), 16.5f, Color.parseColor("#64748B"), maxWidth = 640, maxLines = 2)
        }

        drawText(canvas, "SETTLEMENT TRACEABILITY", 828f, 420f, 16f, secondaryColor, bold = true)
        val traceRows = listOf(
            "Sender Name" to receipt.senderAccountNameSnapshot.ifBlank { "—" },
            "Sending Bank" to receipt.senderBankSnapshot.ifBlank { "—" },
            "Beneficiary Bank" to receipt.beneficiaryBankSnapshot.ifBlank { "—" },
            "Reference" to receipt.transactionRefSnapshot.ifBlank { "—" },
            "Session ID" to receipt.sessionIdSnapshot.ifBlank { "—" },
            "Tx Date & Time" to "${receipt.transactionDateSnapshot} ${receipt.transactionTimeSnapshot}".trim().ifBlank { "—" },
            "Channel" to receipt.transferChannelSnapshot.ifBlank { "Bank Transfer" }
        )
        var ty = 456f
        for ((k, v) in traceRows) {
            drawText(canvas, k, 828f, ty, 17f, Color.parseColor("#64748B"), bold = true)
            drawText(canvas, v, 1020f, ty, 17f, inkColor, mono = (k == "Reference" || k == "Session ID"), maxWidth = 480, maxLines = 1)
            ty += 37f
        }

        val ack = business.defaultAcknowledgement.ifBlank {
            "Received with thanks from the above-named customer the sum stated herein for the service or goods described in this receipt."
        }
        drawText(canvas, ack, 56f, 768f, 18.5f, inkColor, maxWidth = WIDTH - 112, maxLines = 3)

        drawCommonFooter(canvas, height, business, receipt, primaryColor, accentColor, inkColor, signatureBitmap, qrBitmap)
    }

    private fun drawRoyalCertificateTemplate(
        canvas: Canvas,
        height: Int,
        business: Business,
        receipt: ReceiptRecord,
        primaryColor: Int,
        secondaryColor: Int,
        accentColor: Int,
        inkColor: Int,
        onPrimary: Int,
        logoBitmap: Bitmap?,
        signatureBitmap: Bitmap?,
        qrBitmap: Bitmap?
    ) {
        // Ceremonial outer double-line frame
        val outerFrame = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = primaryColor
            style = Paint.Style.STROKE
            strokeWidth = 6f
        }
        val innerFrame = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = accentColor
            style = Paint.Style.STROKE
            strokeWidth = 3f
        }
        canvas.drawRoundRect(RectF(24f, 24f, WIDTH - 24f, height - 24f), 22f, 22f, outerFrame)
        canvas.drawRoundRect(RectF(38f, 38f, WIDTH - 38f, height - 38f), 16f, 16f, innerFrame)

        // Header crest & ceremonial title
        var leftX = 72f
        if (logoBitmap != null) {
            val lBox = RectF(72f, 64f, 198f, 190f)
            val lBorder = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = accentColor
                style = Paint.Style.STROKE
                strokeWidth = 2.5f
            }
            canvas.drawRoundRect(lBox, 18f, 18f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE })
            canvas.drawRoundRect(lBox, 18f, 18f, lBorder)
            drawBitmapCenterFit(canvas, logoBitmap, RectF(82f, 74f, 188f, 180f))
            leftX = 224f
        }

        val bizName = business.tradingName.ifBlank { business.registeredName }
        drawText(canvas, bizName.uppercase(), leftX, 72f, 34f, primaryColor, bold = true, maxWidth = 760, maxLines = 1)
        val bizContacts = listOfNotNull(
            business.address.takeIf { it.isNotBlank() },
            business.phone.takeIf { it.isNotBlank() },
            business.email.takeIf { it.isNotBlank() },
            business.cacNumber.takeIf { it.isNotBlank() }?.let { "CAC: $it" }
        ).joinToString("  •  ")
        if (bizContacts.isNotBlank()) {
            drawText(canvas, bizContacts, leftX, 122f, 18f, Color.parseColor("#475569"), maxWidth = 760, maxLines = 2)
        }

        // Ceremonial Certificate Ribbon on Right
        val certBanner = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = primaryColor }
        canvas.drawRoundRect(RectF(WIDTH - 510f, 64f, WIDTH - 64f, 192f), 18f, 18f, certBanner)
        drawText(canvas, "OFFICIAL CERTIFICATE OF RECEIPT", WIDTH - 486f, 82f, 18.5f, accentColor, bold = true, maxWidth = 400)
        drawText(canvas, receipt.receiptNumber, WIDTH - 486f, 114f, 22f, onPrimary, bold = true, mono = true, maxWidth = 400, maxLines = 1)
        drawText(canvas, "Issued: ${formatIssuedDate(receipt)}", WIDTH - 486f, 150f, 16f, onPrimary, alpha = 225, maxWidth = 400, maxLines = 1)

        // Formal Structured Table Grid
        val tableRect = RectF(64f, 224f, WIDTH - 64f, 710f)
        val tableStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = primaryColor
            style = Paint.Style.STROKE
            strokeWidth = 2.5f
        }
        val rowFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = primaryColor
            alpha = 18
        }
        val gridLine = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#CBD5E1")
            strokeWidth = 2f
        }
        canvas.drawRoundRect(tableRect, 18f, 18f, tableStroke)
        canvas.drawRoundRect(RectF(66f, 226f, WIDTH - 66f, 290f), 16f, 16f, rowFill)

        drawText(canvas, "PAYER / CUSTOMER", 92f, 246f, 18f, primaryColor, bold = true)
        drawText(canvas, "PAYMENT PARTICULARS", 600f, 246f, 18f, primaryColor, bold = true)
        drawText(canvas, "AMOUNT SETTLED", 1220f, 246f, 18f, primaryColor, bold = true)

        canvas.drawLine(64f, 292f, WIDTH - 64f, 292f, gridLine)
        canvas.drawLine(570f, 224f, 570f, 505f, gridLine)
        canvas.drawLine(1180f, 224f, 1180f, 505f, gridLine)

        // Row 1: Customer, Purpose, Amount
        drawText(canvas, receipt.customerName, 92f, 320f, 24f, inkColor, bold = true, maxWidth = 450, maxLines = 2)
        val cMeta = listOfNotNull(
            receipt.customerPhone.takeIf { it.isNotBlank() },
            receipt.customerEmail.takeIf { it.isNotBlank() }
        ).joinToString("\n")
        if (cMeta.isNotBlank()) {
            drawText(canvas, cMeta, 92f, 396f, 17f, Color.parseColor("#475569"), maxWidth = 450, maxLines = 2)
        }
        drawText(canvas, receipt.paymentPurpose, 600f, 320f, 21f, inkColor, bold = true, maxWidth = 550, maxLines = 3)
        val refs = listOfNotNull(
            receipt.invoiceNumber.takeIf { it.isNotBlank() }?.let { "Invoice: $it" },
            receipt.orderNumber.takeIf { it.isNotBlank() }?.let { "Order: $it" }
        ).joinToString("  |  ")
        if (refs.isNotBlank()) {
            drawText(canvas, refs, 600f, 442f, 16.5f, secondaryColor, bold = true, maxWidth = 550)
        }
        drawText(canvas, "${currencySymbol(receipt.currency)}${"%,.2f".format(receipt.amountPaid)}", 1210f, 325f, 36f, primaryColor, bold = true, mono = true, maxWidth = 310, maxLines = 1)
        drawText(canvas, receipt.verificationStatus.replace("_", " "), 1210f, 386f, 17f, secondaryColor, bold = true, maxWidth = 310)

        // Row 2: Amount in words strip
        canvas.drawLine(64f, 505f, WIDTH - 64f, 505f, gridLine)
        canvas.drawRect(66f, 506f, WIDTH - 66f, 578f, rowFill)
        drawText(canvas, "SUM IN WORDS:", 92f, 530f, 17f, secondaryColor, bold = true)
        drawText(
            canvas,
            receipt.amountInWords.ifBlank { AmountInWords.toNairaWords(receipt.amountPaid, receipt.currency) },
            280f,
            528f,
            19.5f,
            inkColor,
            bold = true,
            maxWidth = 1230,
            maxLines = 2
        )

        // Row 3: Banking traceability row
        canvas.drawLine(64f, 578f, WIDTH - 64f, 578f, gridLine)
        drawText(canvas, "Sending Bank: ${receipt.senderBankSnapshot.ifBlank { "—" }}   |   Sender Account: ${receipt.senderAccountNameSnapshot.ifBlank { "—" }}", 92f, 602f, 18f, inkColor, maxWidth = WIDTH - 184)
        drawText(canvas, "Beneficiary Bank: ${receipt.beneficiaryBankSnapshot.ifBlank { "—" }}   |   Reference: ${receipt.transactionRefSnapshot.ifBlank { "—" }}   |   Session: ${receipt.sessionIdSnapshot.ifBlank { "—" }}", 92f, 642f, 17.5f, Color.parseColor("#475569"), maxWidth = WIDTH - 184)

        val ack = business.defaultAcknowledgement.ifBlank {
            "Received with thanks from the above-named customer the sum stated herein for the service or goods described in this receipt."
        }
        drawText(canvas, ack, 64f, 740f, 18.5f, inkColor, maxWidth = WIDTH - 128, maxLines = 3)

        drawCommonFooter(canvas, height, business, receipt, primaryColor, accentColor, inkColor, signatureBitmap, qrBitmap)
    }

    private fun drawFintechWaveTemplate(
        canvas: Canvas,
        height: Int,
        business: Business,
        receipt: ReceiptRecord,
        primaryColor: Int,
        secondaryColor: Int,
        accentColor: Int,
        inkColor: Int,
        onPrimary: Int,
        logoBitmap: Bitmap?,
        signatureBitmap: Bitmap?,
        qrBitmap: Bitmap?
    ) {
        // Sweeping curved header wave canvas
        val wavePath = Path().apply {
            moveTo(0f, 0f)
            lineTo(WIDTH.toFloat(), 0f)
            lineTo(WIDTH.toFloat(), 245f)
            cubicTo(WIDTH * 0.72f, 310f, WIDTH * 0.30f, 195f, 0f, 285f)
            close()
        }
        val wavePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = primaryColor }
        canvas.drawPath(wavePath, wavePaint)

        // Secondary accent curve highlight
        val accentCurve = Path().apply {
            moveTo(0f, 275f)
            cubicTo(WIDTH * 0.30f, 185f, WIDTH * 0.72f, 300f, WIDTH.toFloat(), 235f)
            lineTo(WIDTH.toFloat(), 249f)
            cubicTo(WIDTH * 0.72f, 314f, WIDTH * 0.30f, 199f, 0f, 289f)
            close()
        }
        canvas.drawPath(accentCurve, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = accentColor })

        var headerX = 64f
        if (logoBitmap != null) {
            val logoPill = RectF(64f, 52f, 188f, 176f)
            canvas.drawRoundRect(logoPill, 28f, 28f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE })
            drawBitmapCenterFit(canvas, logoBitmap, RectF(74f, 62f, 178f, 166f))
            headerX = 216f
        }
        val bizName = business.tradingName.ifBlank { business.registeredName }
        drawText(canvas, bizName, headerX, 58f, 34f, onPrimary, bold = true, maxWidth = 760, maxLines = 1)
        val meta = listOfNotNull(
            business.address.takeIf { it.isNotBlank() },
            business.phone.takeIf { it.isNotBlank() },
            business.email.takeIf { it.isNotBlank() }
        ).joinToString("  •  ")
        if (meta.isNotBlank()) {
            drawText(canvas, meta, headerX, 108f, 18f, onPrimary, alpha = 225, maxWidth = 760, maxLines = 2)
        }

        // Receipt number badge on top-right of wave
        drawText(canvas, "DIGITAL PAYMENT RECEIPT", WIDTH - 480f, 56f, 17f, accentColor, bold = true, maxWidth = 420)
        drawText(canvas, receipt.receiptNumber, WIDTH - 480f, 88f, 22f, onPrimary, bold = true, mono = true, maxWidth = 420, maxLines = 1)
        drawText(canvas, formatIssuedDate(receipt), WIDTH - 480f, 126f, 16.5f, onPrimary, alpha = 215, maxWidth = 420, maxLines = 1)

        // Elevated Floating Rounded Summary Card overlapping wave curve
        val floatCard = RectF(64f, 224f, WIDTH - 64f, 400f)
        canvas.drawRoundRect(
            RectF(floatCard.left + 4f, floatCard.top + 8f, floatCard.right + 4f, floatCard.bottom + 8f),
            30f, 30f,
            Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; alpha = 22 }
        )
        canvas.drawRoundRect(floatCard, 30f, 30f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE })
        canvas.drawRoundRect(
            floatCard,
            30f,
            30f,
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = secondaryColor
                style = Paint.Style.STROKE
                strokeWidth = 3f
            }
        )

        drawText(canvas, "RECEIVED FROM", 104f, 252f, 16f, secondaryColor, bold = true)
        drawText(canvas, receipt.customerName, 104f, 282f, 28f, inkColor, bold = true, maxWidth = 650, maxLines = 1)
        drawText(canvas, receipt.paymentPurpose, 104f, 326f, 19.5f, Color.parseColor("#475569"), maxWidth = 650, maxLines = 2)

        drawText(canvas, "${currencySymbol(receipt.currency)}${"%,.2f".format(receipt.amountPaid)}", WIDTH - 610f, 254f, 48f, primaryColor, bold = true, mono = true, maxWidth = 510, maxLines = 1)
        // Status pill
        val statusPill = RectF(WIDTH - 610f, 326f, WIDTH - 320f, 372f)
        canvas.drawRoundRect(statusPill, 999f, 999f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = secondaryColor; alpha = 28 })
        drawText(canvas, "STATUS: ${receipt.verificationStatus.replace("_", " ")}", WIDTH - 590f, 338f, 16f, secondaryColor, bold = true, maxWidth = 260, maxLines = 1)

        // Amount in words rounded bar
        val wordsBox = RectF(64f, 424f, WIDTH - 64f, 504f)
        canvas.drawRoundRect(wordsBox, 20f, 20f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = primaryColor; alpha = 16 })
        drawText(canvas, "Amount in Words:", 92f, 450f, 17f, secondaryColor, bold = true)
        drawText(
            canvas,
            receipt.amountInWords.ifBlank { AmountInWords.toNairaWords(receipt.amountPaid, receipt.currency) },
            290f,
            448f,
            19.5f,
            inkColor,
            bold = true,
            maxWidth = 1220,
            maxLines = 2
        )

        // Two Traceability Pill Cards
        val chipBg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#F8FAFC") }
        val chipStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#CBD5E1")
            style = Paint.Style.STROKE
            strokeWidth = 2f
        }
        val leftChip = RectF(64f, 530f, 780f, 710f)
        val rightChip = RectF(820f, 530f, WIDTH - 64f, 710f)
        canvas.drawRoundRect(leftChip, 24f, 24f, chipBg)
        canvas.drawRoundRect(leftChip, 24f, 24f, chipStroke)
        canvas.drawRoundRect(rightChip, 24f, 24f, chipBg)
        canvas.drawRoundRect(rightChip, 24f, 24f, chipStroke)

        drawText(canvas, "SENDER & CHANNEL", 92f, 554f, 16f, secondaryColor, bold = true)
        drawText(canvas, "Sender Name: ${receipt.senderAccountNameSnapshot.ifBlank { "—" }}", 92f, 588f, 18.5f, inkColor, bold = true, maxWidth = 650, maxLines = 1)
        drawText(canvas, "Sending Bank: ${receipt.senderBankSnapshot.ifBlank { "—" }}", 92f, 622f, 18f, Color.parseColor("#475569"), maxWidth = 650, maxLines = 1)
        drawText(canvas, "Channel: ${receipt.transferChannelSnapshot.ifBlank { "Bank Transfer" }}  •  Date: ${receipt.transactionDateSnapshot} ${receipt.transactionTimeSnapshot}", 92f, 656f, 17f, Color.parseColor("#64748B"), maxWidth = 650, maxLines = 1)

        drawText(canvas, "BENEFICIARY & REFERENCE", 848f, 554f, 16f, secondaryColor, bold = true)
        drawText(canvas, "Beneficiary Bank: ${receipt.beneficiaryBankSnapshot.ifBlank { "—" }}", 848f, 588f, 18.5f, inkColor, bold = true, maxWidth = 650, maxLines = 1)
        drawText(canvas, "Reference: ${receipt.transactionRefSnapshot.ifBlank { "—" }}", 848f, 622f, 17.5f, inkColor, mono = true, maxWidth = 650, maxLines = 1)
        drawText(canvas, "Session ID: ${receipt.sessionIdSnapshot.ifBlank { "—" }}", 848f, 656f, 17f, Color.parseColor("#64748B"), mono = true, maxWidth = 650, maxLines = 1)

        val ack = business.defaultAcknowledgement.ifBlank {
            "Received with thanks from the above-named customer the sum stated herein for the service or goods described in this receipt."
        }
        drawText(canvas, ack, 64f, 742f, 18.5f, inkColor, maxWidth = WIDTH - 128, maxLines = 3)

        drawCommonFooter(canvas, height, business, receipt, primaryColor, accentColor, inkColor, signatureBitmap, qrBitmap)
    }

    private fun drawCommonFooter(
        canvas: Canvas,
        height: Int,
        business: Business,
        receipt: ReceiptRecord,
        primaryColor: Int,
        accentColor: Int,
        inkColor: Int,
        signatureBitmap: Bitmap?,
        qrBitmap: Bitmap?
    ) {
        val divider = Paint().apply { color = Color.parseColor("#CBD5E1"); strokeWidth = 2f }
        canvas.drawLine(48f, height - 255f, WIDTH - 48f, height - 255f, divider)

        if (business.businessSlogan.isNotBlank()) {
            drawText(canvas, "\"${business.businessSlogan}\"", 48f, height - 235f, 18f, primaryColor, bold = true, maxWidth = 680)
        }

        if (business.showSignature) {
            if (signatureBitmap != null) {
                drawBitmapCenterFit(canvas, signatureBitmap, RectF(48f, height - 195f, 280f, height - 115f))
            }
            val sigLine = Paint().apply { color = Color.parseColor("#64748B"); strokeWidth = 2f }
            canvas.drawLine(48f, height - 108f, 380f, height - 108f, sigLine)
            drawText(canvas, "Authorised Rep: ${business.authorisedRepName.ifBlank { business.ownerName }}", 48f, height - 96f, 17f, inkColor, bold = true)
        }

        if (business.stampText.isNotBlank()) {
            val stampPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = accentColor
                style = Paint.Style.STROKE
                strokeWidth = 3f
            }
            canvas.drawRoundRect(RectF(560f, height - 190f, 880f, height - 100f), 12f, 12f, stampPaint)
            drawText(canvas, business.stampText.uppercase(), 580f, height - 158f, 18f, primaryColor, bold = true, maxWidth = 280)
        }

        if (qrBitmap != null) {
            canvas.drawBitmap(qrBitmap, null, RectF(WIDTH - 250f, height - 235f, WIDTH - 60f, height - 45f), null)
            drawText(canvas, "Verify: ${receipt.verificationCode}", WIDTH - 520f, height - 102f, 14.5f, Color.parseColor("#475569"), mono = true, maxWidth = 250, maxLines = 2)
        }

        drawText(
            canvas,
            "Business payment acknowledgement (not a bank statement). Issued via ReceiptsNow.",
            48f,
            height - 54f,
            15f,
            Color.parseColor("#64748B"),
            maxWidth = WIDTH - 580
        )
    }

    fun generateQrBitmap(content: String, sizePx: Int): Bitmap {
        val hints = mapOf(EncodeHintType.MARGIN to 1)
        val bitMatrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, sizePx, sizePx, hints)
        val bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        for (x in 0 until sizePx) {
            for (y in 0 until sizePx) {
                bmp.setPixel(x, y, if (bitMatrix[x, y]) Color.BLACK else Color.WHITE)
            }
        }
        return bmp
    }

    private fun drawText(
        canvas: Canvas,
        text: String,
        x: Float,
        y: Float,
        sizePx: Float,
        color: Int,
        bold: Boolean = false,
        mono: Boolean = false,
        alpha: Int = 255,
        maxWidth: Int = 1400,
        maxLines: Int = Int.MAX_VALUE
    ): Float {
        if (text.isBlank()) return 0f
        val tp = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            this.alpha = alpha
            this.textSize = sizePx
            this.typeface = when {
                mono && bold -> Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
                mono -> Typeface.MONOSPACE
                bold -> Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
                else -> Typeface.SANS_SERIF
            }
        }
        val builder = StaticLayout.Builder.obtain(text, 0, text.length, tp, maxWidth.coerceAtLeast(40))
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setLineSpacing(2f, 1.05f)
        if (maxLines < Int.MAX_VALUE) {
            builder.setMaxLines(maxLines).setEllipsize(TextUtils.TruncateAt.END)
        }
        val layout = builder.build()
        canvas.save()
        canvas.translate(x, y)
        layout.draw(canvas)
        canvas.restore()
        return layout.height.toFloat()
    }

    private fun drawBitmapCenterFit(canvas: Canvas, bmp: Bitmap, dst: RectF) {
        val ratio = minOf(dst.width() / bmp.width, dst.height() / bmp.height)
        val w = bmp.width * ratio
        val h = bmp.height * ratio
        val left = dst.left + (dst.width() - w) / 2f
        val top = dst.top + (dst.height() - h) / 2f
        canvas.drawBitmap(bmp, Rect(0, 0, bmp.width, bmp.height), RectF(left, top, left + w, top + h), Paint(Paint.FILTER_BITMAP_FLAG))
    }

    private fun formatIssuedDate(receipt: ReceiptRecord): String {
        val date = receipt.createdAt?.toDate() ?: Date()
        val fmt = SimpleDateFormat("dd MMM yyyy, hh:mm a", Locale.ENGLISH).apply {
            timeZone = TimeZone.getTimeZone("Africa/Lagos")
        }
        return "${fmt.format(date)} WAT"
    }

    private fun currencySymbol(currency: String): String = when (currency.uppercase()) {
        "NGN" -> "₦"
        "USD" -> "$"
        "GBP" -> "£"
        "EUR" -> "€"
        else -> "$currency "
    }
}
