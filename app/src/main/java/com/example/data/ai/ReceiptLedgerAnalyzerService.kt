package com.example.data.ai

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import com.example.data.model.Business
import com.example.data.model.LedgerCategory
import com.example.data.model.LedgerEntryItem
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
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.roundToInt

private const val TAG = "ReceiptLedgerAnalyzer"
private const val PRIMARY_LEDGER_MODEL = "gemini-3.5-flash-lite"
private const val FALLBACK_LEDGER_MODEL = "gemini-3.8-flash"
private const val LEDGER_QUOTA_COOLDOWN_MS = 10 * 60 * 1000L
private val ledgerPrimaryCooldownUntilMs = AtomicLong(0L)

class ReceiptLedgerAnalyzerService(
    private val ocrHelper: ReceiptOcrService = ReceiptOcrService()
) {

    private val ledgerItemSchema = Schema.obj(
        properties = mapOf(
            "merchantOrParty" to Schema.string(description = "Customer name (for Sales), supplier/vendor name (for Purchases/Expenses), or counterparty shown on receipt"),
            "category" to Schema.enumeration(
                values = LEDGER_CATEGORY_VALUES,
                description = "Financial classification of this receipt item: SALES_INCOME, PURCHASE, EXPENDITURE, REFUND_REVERSAL, or OTHER"
            ),
            "amount" to Schema.double(description = "Exact positive numeric amount on the receipt/line without currency symbol"),
            "currency" to Schema.string(description = "3-letter currency code e.g. NGN"),
            "paymentMethod" to Schema.string(description = "e.g. Bank Transfer, POS, Cash, OPay, GTBank, Moniepoint, Kuda"),
            "reference" to Schema.string(description = "Transaction reference / receipt number if visible, else empty string"),
            "transactionDate" to Schema.string(description = "Date on receipt if visible, else empty string"),
            "notes" to Schema.string(description = "Short item description / narration e.g. Diesel for generator, Wholesale stock, POS withdrawal charge, Customer order"),
            "confidenceScore" to Schema.double(description = "Confidence score from 0.0 to 1.0")
        ),
        optionalProperties = listOf("paymentMethod", "reference", "transactionDate", "notes")
    )

    private val ledgerResponseSchema = Schema.obj(
        properties = mapOf(
            "items" to Schema.array(
                items = ledgerItemSchema,
                description = "One or more transaction entries extracted from the uploaded receipt document"
            )
        )
    )

    private val primaryModel: GenerativeModel by lazy {
        Firebase.ai.generativeModel(
            modelName = PRIMARY_LEDGER_MODEL,
            generationConfig = generationConfig {
                responseMimeType = "application/json"
                responseSchema = ledgerResponseSchema
            }
        )
    }

    private val fallbackModel: GenerativeModel by lazy {
        Firebase.ai.generativeModel(
            modelName = FALLBACK_LEDGER_MODEL,
            generationConfig = generationConfig {
                responseMimeType = "application/json"
                responseSchema = ledgerResponseSchema
            }
        )
    }

    suspend fun analyzeDocumentUri(
        context: Context,
        uri: Uri,
        business: Business
    ): Result<List<LedgerEntryItem>> = withContext(Dispatchers.IO) {
        try {
            val mime = context.contentResolver.getType(uri).orEmpty()
            var displayName = ""
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0) displayName = cursor.getString(idx).orEmpty()
                }
            }
            val isPdf = mime.contains("pdf", ignoreCase = true) || displayName.endsWith(".pdf", ignoreCase = true)
            val resolvedName = displayName.ifBlank { if (isPdf) "receipt_doc.pdf" else "receipt_img.jpg" }
            val resolvedMime = mime.ifBlank { if (isPdf) "application/pdf" else "image/*" }

            var previewBitmap: Bitmap? = null
            try {
                previewBitmap = ocrHelper.decodeDocumentPreview(context, uri, resolvedMime)
                val pdfBytes = if (isPdf) {
                    context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                } else null

                if (pdfBytes == null && previewBitmap == null) {
                    return@withContext Result.failure(IllegalArgumentException("Could not open $resolvedName"))
                }

                val prompt = buildLedgerPrompt(business, resolvedName)
                val response = if (pdfBytes != null && pdfBytes.size <= 15 * 1024 * 1024) {
                    try {
                        generateWithAppCheckRetry {
                            content {
                                inlineData(pdfBytes, "application/pdf")
                                text(prompt)
                            }
                        }
                    } catch (pdfErr: Exception) {
                        if (pdfErr is CancellationException && pdfErr !is TimeoutCancellationException) throw pdfErr
                        val bmp = previewBitmap ?: throw pdfErr
                        val jpeg = encodeJpeg(bmp)
                        generateWithAppCheckRetry {
                            content {
                                inlineData(jpeg, "image/jpeg")
                                text(prompt)
                            }
                        }
                    }
                } else {
                    val bmp = checkNotNull(previewBitmap)
                    val jpeg = encodeJpeg(bmp)
                    generateWithAppCheckRetry {
                        content {
                            inlineData(jpeg, "image/jpeg")
                            text(prompt)
                        }
                    }
                }

                val jsonText = response.text ?: throw IllegalStateException("AI returned empty response for $resolvedName")
                val entries = parseLedgerItemsJson(jsonText, resolvedName, business.defaultCurrency.ifBlank { "NGN" })
                Result.success(entries)
            } finally {
                if (previewBitmap != null && !previewBitmap.isRecycled) {
                    previewBitmap.recycle()
                }
            }
        } catch (e: TimeoutCancellationException) {
            Result.failure(Exception("Timed out reading receipt. Please check connectivity and try again."))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Batch receipt ledger analysis failed", e)
            Result.failure(Exception(AiAssistantService.formatFriendlyAiError(e)))
        }
    }

    suspend fun analyzeBitmap(
        bitmap: Bitmap,
        documentName: String,
        business: Business
    ): Result<List<LedgerEntryItem>> = withContext(Dispatchers.IO) {
        try {
            val jpeg = encodeJpeg(bitmap)
            val prompt = buildLedgerPrompt(business, documentName)
            val response = generateWithAppCheckRetry {
                content {
                    inlineData(jpeg, "image/jpeg")
                    text(prompt)
                }
            }
            val jsonText = response.text ?: throw IllegalStateException("AI returned empty response.")
            val entries = parseLedgerItemsJson(jsonText, documentName, business.defaultCurrency.ifBlank { "NGN" })
            Result.success(entries)
        } catch (e: TimeoutCancellationException) {
            Result.failure(Exception("Timed out reading $documentName. Try again."))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Bitmap receipt ledger analysis failed", e)
            Result.failure(Exception(AiAssistantService.formatFriendlyAiError(e)))
        }
    }

    private suspend fun generateWithAppCheckRetry(
        buildContent: () -> Content
    ): GenerateContentResponse {
        var didRefreshAppCheck = false
        while (true) {
            try {
                return executeWithFallback(buildContent)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (!didRefreshAppCheck && AiAssistantService.isAppCheckError(e)) {
                    didRefreshAppCheck = true
                    Log.w(TAG, "App Check rejected ledger scan; refreshing token once.")
                    if (refreshFirebaseAppCheckToken()) continue
                }
                throw e
            }
        }
    }

    private suspend fun executeWithFallback(
        buildContent: () -> Content
    ): GenerateContentResponse {
        val now = System.currentTimeMillis()
        if (now < ledgerPrimaryCooldownUntilMs.get()) {
            return withTimeout(32_000L) { fallbackModel.generateContent(buildContent()) }
        }
        return try {
            withTimeout(28_000L) { primaryModel.generateContent(buildContent()) }
        } catch (e: TimeoutCancellationException) {
            Log.w(TAG, "$PRIMARY_LEDGER_MODEL timed out; retrying with $FALLBACK_LEDGER_MODEL")
            withTimeout(32_000L) { fallbackModel.generateContent(buildContent()) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (AiAssistantService.isTransientModelError(e)) {
                if (AiAssistantService.isQuotaOrRateLimitError(e)) {
                    ledgerPrimaryCooldownUntilMs.set(System.currentTimeMillis() + LEDGER_QUOTA_COOLDOWN_MS)
                }
                withTimeout(32_000L) { fallbackModel.generateContent(buildContent()) }
            } else {
                throw e
            }
        }
    }

    private fun buildLedgerPrompt(business: Business, documentName: String): String {
        val bizName = business.tradingName.ifBlank { business.registeredName }
        val ownerName = business.ownerName
        return """
            You are an end-of-day financial bookkeeper & receipt reconciliation AI for "$bizName" (Owner: $ownerName).
            Analyze the attached receipt, bank transfer proof, POS slip, invoice, or expense voucher ($documentName) and extract each transaction item.

            CLASSIFICATION RULES FOR THIS BUSINESS ("$bizName"):
            1. SALES_INCOME:
               - Money received INTO "$bizName" / "$ownerName" (beneficiary is our business/owner), OR a customer sales receipt issued for goods/services sold.
            2. PURCHASE:
               - Inventory stock purchases, wholesale restocking, raw materials, goods bought for resale, supplier purchase orders paid BY "$bizName".
            3. EXPENDITURE:
               - Operating expenses & bills paid BY "$bizName": fuel/diesel, electricity/PHCN, transport/logistics/waybill, rent, airtime/data, bank/POS charges, repairs, staff lunch/wages, packaging supplies.
            4. REFUND_REVERSAL:
               - Reversed transactions, failed debits, or customer refunds issued back.
            5. OTHER:
               - Any valid financial item that does not fit the four categories above.

            ACCURACY RULES:
            - Extract the exact total transaction amount shown; never invent numbers.
            - If the document is a multi-line tally sheet or statement with multiple separate transactions, output multiple items in `items`.
            - If a single transaction receipt is uploaded, output 1 item with the clean net/total amount.
            - Use default currency "${business.defaultCurrency.ifBlank { "NGN" }}" unless another currency is explicitly printed.
        """.trimIndent()
    }

    private fun encodeJpeg(bitmap: Bitmap, maxDim: Int = 1200, quality: Int = 84): ByteArray {
        val w = bitmap.width
        val h = bitmap.height
        val scaled = if (w <= maxDim && h <= maxDim) bitmap else {
            val ratio = minOf(maxDim.toFloat() / w, maxDim.toFloat() / h)
            Bitmap.createScaledBitmap(bitmap, (w * ratio).roundToInt().coerceAtLeast(1), (h * ratio).roundToInt().coerceAtLeast(1), true)
        }
        return try {
            val out = ByteArrayOutputStream()
            scaled.compress(Bitmap.CompressFormat.JPEG, quality, out)
            out.toByteArray()
        } finally {
            if (scaled !== bitmap && !scaled.isRecycled) {
                scaled.recycle()
            }
        }
    }

    companion object {
        val LEDGER_CATEGORY_VALUES = LedgerCategory.entries.map { it.name }

        fun parseLedgerItemsJson(
            jsonString: String,
            documentName: String,
            defaultCurrency: String = "NGN"
        ): List<LedgerEntryItem> {
            val cleaned = AiAssistantService.stripJsonMarkdownFences(jsonString)
            val json = JSONObject(cleaned)
            val arr = json.optJSONArray("items") ?: return emptyList()
            val results = mutableListOf<LedgerEntryItem>()
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val rawAmt = obj.optDouble("amount", 0.0)
                if (rawAmt.isNaN() || rawAmt <= 0.0) continue
                val catStr = obj.optString("category", "OTHER").trim().uppercase()
                val validCat = runCatching { LedgerCategory.valueOf(catStr).name }.getOrDefault(LedgerCategory.OTHER.name)
                results += LedgerEntryItem(
                    id = "le_${UUID.randomUUID().toString().replace("-", "").take(10)}",
                    documentName = documentName,
                    merchantOrParty = obj.optString("merchantOrParty", "").trim().ifBlank { "Receipt Item" },
                    category = validCat,
                    amount = rawAmt,
                    currency = obj.optString("currency", defaultCurrency).ifBlank { defaultCurrency },
                    paymentMethod = obj.optString("paymentMethod", "").trim(),
                    reference = obj.optString("reference", "").trim(),
                    transactionDate = obj.optString("transactionDate", "").trim(),
                    notes = obj.optString("notes", "").trim(),
                    confidenceScore = obj.optDouble("confidenceScore", 0.9).coerceIn(0.0, 1.0),
                    sourceType = "UPLOADED_RECEIPT"
                )
            }
            return results
        }
    }
}
