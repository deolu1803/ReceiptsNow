package com.example.data.ai

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.pdf.PdfRenderer
import android.media.ExifInterface
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.Log
import com.example.data.model.ExtractedBankReceipt
import com.example.security.refreshFirebaseAppCheckToken
import com.google.firebase.Firebase
import com.google.firebase.ai.GenerativeModel
import com.google.firebase.ai.ai
import com.google.firebase.ai.type.Content
import com.google.firebase.ai.type.GenerateContentResponse
import com.google.firebase.ai.type.Schema
import com.google.firebase.ai.type.content
import com.google.firebase.ai.type.generationConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.max
import kotlin.math.roundToInt

private const val TAG = "ReceiptOcrService"
private const val PRIMARY_OCR_MODEL = "gemini-3.5-flash-lite"
private const val FALLBACK_OCR_MODEL = "gemini-3.8-flash"
private const val OCR_QUOTA_COOLDOWN_MS = 10 * 60 * 1000L
private val ocrPrimaryCooldownUntilMs = AtomicLong(0L)

class ReceiptOcrService {

    private val ocrSchema = Schema.obj(
        properties = mapOf(
            "amount" to Schema.double(description = "Exact numeric transaction amount without currency symbol, or null if absent", nullable = true),
            "currency" to Schema.string(description = "3-letter currency code such as NGN"),
            "transactionDate" to Schema.string(description = "Transaction date exactly as shown on receipt, or empty string if absent"),
            "transactionTime" to Schema.string(description = "Transaction time exactly as shown on receipt, or empty string if absent"),
            "transactionReference" to Schema.string(description = "Transaction reference / NIP reference, or empty string if absent"),
            "sessionId" to Schema.string(description = "NIP Session ID if present, else empty string"),
            "transactionId" to Schema.string(description = "Transaction ID if separate from reference, else empty string"),
            "bankReceiptNumber" to Schema.string(description = "Bank-generated receipt number if shown, else empty string"),
            "paymentNarration" to Schema.string(description = "Payment narration / remark / description, else empty string"),
            "transferChannel" to Schema.string(description = "Transfer channel e.g. Mobile App, USSD, NIP, POS, Web, Bank Transfer"),
            "transactionStatus" to Schema.string(description = "Status shown on receipt e.g. Successful, Completed, Pending"),
            "transferFee" to Schema.double(description = "Transfer fee/charge if displayed, or null if absent", nullable = true),
            "senderName" to Schema.string(description = "Sender full name if displayed, else empty string"),
            "senderAccountName" to Schema.string(description = "Sender account name if displayed, else empty string"),
            "senderBank" to Schema.string(description = "Sending bank or fintech name e.g. GTBank, OPay, Moniepoint, Access Bank, Kuda, UBA, Zenith, PalmPay, FirstBank"),
            "senderAccountNumber" to Schema.string(description = "Sender account number (including masked digits as shown), else empty string"),
            "beneficiaryName" to Schema.string(description = "Beneficiary name if displayed, else empty string"),
            "beneficiaryAccountName" to Schema.string(description = "Beneficiary account name if displayed, else empty string"),
            "beneficiaryBank" to Schema.string(description = "Beneficiary bank or fintech name, else empty string"),
            "beneficiaryAccountNumber" to Schema.string(description = "Beneficiary account number (including masked digits as shown), else empty string"),
            "confidenceScore" to Schema.double(description = "Extraction confidence from 0.0 to 1.0"),
            "uncertainFields" to Schema.array(
                items = Schema.string(),
                description = "List of field names that were blurry, partially occluded, or ambiguous"
            ),
            "extractionNotes" to Schema.string(description = "Short note describing document clarity or any uncertainty")
        ),
        optionalProperties = listOf("amount", "transferFee")
    )

    private val primaryModel: GenerativeModel by lazy {
        Firebase.ai.generativeModel(
            modelName = PRIMARY_OCR_MODEL,
            generationConfig = generationConfig {
                responseMimeType = "application/json"
                responseSchema = ocrSchema
            }
        )
    }

    private val fallbackModel: GenerativeModel by lazy {
        Firebase.ai.generativeModel(
            modelName = FALLBACK_OCR_MODEL,
            generationConfig = generationConfig {
                responseMimeType = "application/json"
                responseSchema = ocrSchema
            }
        )
    }

    suspend fun decodeDocumentPreview(
        context: Context,
        uri: Uri,
        mimeType: String?
    ): Bitmap? = withContext(Dispatchers.IO) {
        val resolvedMime = mimeType ?: context.contentResolver.getType(uri).orEmpty()
        if (resolvedMime.contains("pdf", ignoreCase = true)) {
            renderFirstPdfPage(context, uri)
        } else {
            decodeScaledBitmapWithExif(context, uri, 1200)
        }
    }

    suspend fun extractFromDocumentUri(
        context: Context,
        uri: Uri,
        mimeType: String?,
        preDecodedPreview: Bitmap? = null
    ): Result<Pair<ExtractedBankReceipt, Bitmap?>> = withContext(Dispatchers.IO) {
        try {
            val resolvedMime = mimeType ?: context.contentResolver.getType(uri).orEmpty()
            val previewBitmap: Bitmap? = preDecodedPreview ?: decodeDocumentPreview(context, uri, resolvedMime)
            val rawPdfBytes: ByteArray? = if (resolvedMime.contains("pdf", ignoreCase = true)) {
                context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            } else {
                null
            }

            if (rawPdfBytes == null && previewBitmap == null) {
                return@withContext Result.failure(IllegalArgumentException("Unable to read selected bank document."))
            }

            val extraction = if (rawPdfBytes != null && rawPdfBytes.size <= 15 * 1024 * 1024) {
                extractWithPdfBytes(rawPdfBytes, previewBitmap)
            } else {
                extractWithBitmap(checkNotNull(previewBitmap))
            }

            Result.success(extraction to previewBitmap)
        } catch (e: TimeoutCancellationException) {
            Log.e(TAG, "OCR extraction timed out", e)
            Result.failure(Exception("Receipt scan timed out on your connection (no OCR credit used). Tap Retry AI Scan or fill the fields below."))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "OCR extraction failed", e)
            val friendly = when {
                AiAssistantService.isAppCheckError(e) ->
                    "AI verification token expired or invalid (Firebase App Check — no OCR credit used). Check internet or update to the latest app build, then tap Retry AI Scan."
                AiAssistantService.isQuotaOrRateLimitError(e) ->
                    "AI OCR service daily/rate quota reached on the server (no OCR credit used). Tap Retry AI Scan shortly or fill the transaction fields manually below."
                AiAssistantService.isTransientModelError(e) ->
                    "AI OCR servers are momentarily busy (no OCR credit used). Tap Retry AI Scan in a moment or enter details manually."
                else -> e.localizedMessage ?: "Failed to analyze bank receipt."
            }
            Result.failure(Exception(friendly))
        }
    }

    suspend fun extractWithBitmap(bitmap: Bitmap): ExtractedBankReceipt {
        val scaled = scaleDownForUpload(bitmap, 1200)
        val jpegBytes = try {
            val out = ByteArrayOutputStream()
            scaled.compress(Bitmap.CompressFormat.JPEG, 84, out)
            out.toByteArray()
        } finally {
            if (scaled !== bitmap && !scaled.isRecycled) {
                scaled.recycle()
            }
        }
        val prompt = buildNigerianBankOcrPrompt()
        val response = generateOcrWithFallback {
            content {
                inlineData(jpegBytes, "image/jpeg")
                text(prompt)
            }
        }
        val jsonText = response.text ?: throw IllegalStateException("AI OCR returned an empty response.")
        return parseExtractionJson(jsonText)
    }

    private suspend fun extractWithPdfBytes(pdfBytes: ByteArray, fallbackBitmap: Bitmap?): ExtractedBankReceipt {
        val prompt = buildNigerianBankOcrPrompt()
        return try {
            val response = generateOcrWithFallback {
                content {
                    inlineData(pdfBytes, "application/pdf")
                    text(prompt)
                }
            }
            val jsonText = response.text ?: throw IllegalStateException("AI OCR returned an empty response.")
            parseExtractionJson(jsonText)
        } catch (e: CancellationException) {
            if (e !is TimeoutCancellationException) throw e
            if (fallbackBitmap != null) {
                Log.w(TAG, "PDF inline extraction timed out; falling back to rendered page image")
                extractWithBitmap(fallbackBitmap)
            } else {
                throw e
            }
        } catch (e: Exception) {
            if (fallbackBitmap != null) {
                Log.w(TAG, "PDF inline extraction fallback to rendered page bitmap: ${e.message}")
                extractWithBitmap(fallbackBitmap)
            } else {
                throw e
            }
        }
    }

    private suspend fun generateOcrWithFallback(
        buildContent: () -> Content
    ): GenerateContentResponse {
        var didRefreshAppCheck = false
        while (true) {
            try {
                return executeOcrWithCooldown(buildContent)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (!didRefreshAppCheck && AiAssistantService.isAppCheckError(e)) {
                    didRefreshAppCheck = true
                    Log.w(TAG, "App Check rejected OCR request; forcing App Check token refresh and retrying once.")
                    if (refreshFirebaseAppCheckToken()) continue
                }
                throw e
            }
        }
    }

    private suspend fun executeOcrWithCooldown(
        buildContent: () -> Content
    ): GenerateContentResponse {
        val now = System.currentTimeMillis()
        if (now < ocrPrimaryCooldownUntilMs.get()) {
            return withTimeout(32_000L) {
                fallbackModel.generateContent(buildContent())
            }
        }
        return try {
            withTimeout(28_000L) {
                primaryModel.generateContent(buildContent())
            }
        } catch (e: TimeoutCancellationException) {
            Log.w(TAG, "$PRIMARY_OCR_MODEL timed out; retrying OCR with $FALLBACK_OCR_MODEL")
            withTimeout(32_000L) {
                fallbackModel.generateContent(buildContent())
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (AiAssistantService.isTransientModelError(e)) {
                if (AiAssistantService.isQuotaOrRateLimitError(e)) {
                    ocrPrimaryCooldownUntilMs.set(System.currentTimeMillis() + OCR_QUOTA_COOLDOWN_MS)
                }
                Log.w(TAG, "$PRIMARY_OCR_MODEL transient/quota error (${e.message}); retrying OCR with $FALLBACK_OCR_MODEL")
                withTimeout(32_000L) {
                    fallbackModel.generateContent(buildContent())
                }
            } else {
                throw e
            }
        }
    }

    private fun buildNigerianBankOcrPrompt(): String = """
        You are an expert Nigerian financial document intelligence engine for ReceiptsNow.
        Analyze the attached Nigerian bank or fintech transfer receipt (e.g., GTBank, Access Bank, UBA, Zenith Bank, FirstBank, Fidelity Bank, FCMB, Union Bank, Sterling Bank, Moniepoint, OPay, PalmPay, Kuda, Wema/ALAT, Stanbic IBTC, Providus, Polaris, Jaiz, etc.).

        STRICT EXTRACTION RULES:
        1. Preserve original transaction information verbatim.
        2. NEVER invent, guess, or fabricate missing fields, references, session IDs, or bank names. Use empty string "" or null if not clearly visible.
        3. NEVER alter or round the transaction amount.
        4. NEVER assume the bank sender is the customer; extract sender and beneficiary separately.
        5. List any blurry, clipped, or uncertain field names in `uncertainFields`.
        6. Provide a realistic `confidenceScore` between 0.0 and 1.0 based on image legibility.
        7. Default currency to "NGN" only if Naira (₦, NGN, Naira) is clearly indicated.
    """.trimIndent()

    private fun parseExtractionJson(jsonString: String): ExtractedBankReceipt {
        val json = JSONObject(AiAssistantService.stripJsonMarkdownFences(jsonString))
        val uncertainList = mutableListOf<String>()
        val uncertainArray = json.optJSONArray("uncertainFields")
        if (uncertainArray != null) {
            for (i in 0 until uncertainArray.length()) {
                val item = uncertainArray.optString(i)
                if (item.isNotBlank()) uncertainList.add(item)
            }
        }
        return ExtractedBankReceipt(
            amount = if (json.isNull("amount")) null else json.optDouble("amount").takeIf { !it.isNaN() },
            currency = json.optString("currency", "NGN").ifBlank { "NGN" },
            transactionDate = json.optString("transactionDate", ""),
            transactionTime = json.optString("transactionTime", ""),
            transactionReference = json.optString("transactionReference", ""),
            sessionId = json.optString("sessionId", ""),
            transactionId = json.optString("transactionId", ""),
            bankReceiptNumber = json.optString("bankReceiptNumber", ""),
            paymentNarration = json.optString("paymentNarration", ""),
            transferChannel = json.optString("transferChannel", ""),
            transactionStatus = json.optString("transactionStatus", ""),
            transferFee = if (json.isNull("transferFee")) null else json.optDouble("transferFee").takeIf { !it.isNaN() },
            senderName = json.optString("senderName", ""),
            senderAccountName = json.optString("senderAccountName", ""),
            senderBank = json.optString("senderBank", ""),
            senderAccountNumber = json.optString("senderAccountNumber", ""),
            beneficiaryName = json.optString("beneficiaryName", ""),
            beneficiaryAccountName = json.optString("beneficiaryAccountName", ""),
            beneficiaryBank = json.optString("beneficiaryBank", ""),
            beneficiaryAccountNumber = json.optString("beneficiaryAccountNumber", ""),
            confidenceScore = json.optDouble("confidenceScore", 0.0).coerceIn(0.0, 1.0),
            uncertainFields = uncertainList,
            extractionNotes = json.optString("extractionNotes", "")
        )
    }

    private fun renderFirstPdfPage(context: Context, uri: Uri): Bitmap? {
        return try {
            val pfd: ParcelFileDescriptor = context.contentResolver.openFileDescriptor(uri, "r") ?: return null
            pfd.use { descriptor ->
                PdfRenderer(descriptor).use { renderer ->
                    if (renderer.pageCount <= 0) return null
                    renderer.openPage(0).use { page ->
                        val longest = max(page.width, page.height).coerceAtLeast(1)
                        val scale = (1600f / longest.toFloat()).coerceIn(1f, 3f)
                        val width = (page.width * scale).roundToInt().coerceIn(1, 2000)
                        val height = (page.height * scale).roundToInt().coerceIn(1, 2000)
                        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                        val canvas = android.graphics.Canvas(bitmap)
                        canvas.drawColor(android.graphics.Color.WHITE)
                        page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        bitmap
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to render PDF preview", e)
            null
        }
    }

    private fun decodeScaledBitmapWithExif(context: Context, uri: Uri, maxDim: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, bounds)
        }
        val maxSide = max(bounds.outWidth, bounds.outHeight)
        var sampleSize = 1
        while (maxSide > 0 && maxSide / sampleSize > maxDim * 2) {
            sampleSize *= 2
        }
        val opts = BitmapFactory.Options().apply { inSampleSize = sampleSize }
        val decoded = context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, opts)
        } ?: return null

        val orientation = runCatching {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                ExifInterface(stream).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL
                )
            }
        }.getOrNull() ?: ExifInterface.ORIENTATION_NORMAL

        val rotated = applyExifOrientation(decoded, orientation)
        if (rotated !== decoded && !decoded.isRecycled) {
            decoded.recycle()
        }
        val scaled = scaleDownForUpload(rotated, maxDim)
        if (scaled !== rotated && !rotated.isRecycled) {
            rotated.recycle()
        }
        return scaled
    }

    private fun applyExifOrientation(source: Bitmap, orientation: Int): Bitmap {
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.preScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.preScale(1f, -1f)
            else -> return source
        }
        return Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
    }

    private fun scaleDownForUpload(bitmap: Bitmap, maxDim: Int): Bitmap {
        val w = bitmap.width
        val h = bitmap.height
        if (w <= maxDim && h <= maxDim) return bitmap
        val ratio = minOf(maxDim.toFloat() / w, maxDim.toFloat() / h)
        val newW = (w * ratio).roundToInt().coerceAtLeast(1)
        val newH = (h * ratio).roundToInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(bitmap, newW, newH, true)
    }

    fun encodeThumbnailBase64(bitmap: Bitmap?): String {
        if (bitmap == null || bitmap.isRecycled) return ""
        val thumb = scaleDownForUpload(bitmap, 480)
        return try {
            val out = ByteArrayOutputStream()
            thumb.compress(Bitmap.CompressFormat.JPEG, 70, out)
            android.util.Base64.encodeToString(out.toByteArray(), android.util.Base64.NO_WRAP)
        } finally {
            if (thumb !== bitmap && !thumb.isRecycled) {
                thumb.recycle()
            }
        }
    }
}
