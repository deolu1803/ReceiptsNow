package com.example.data.ai

import android.graphics.Bitmap
import android.util.Log
import com.example.data.model.Business
import com.example.data.model.Customer
import com.example.data.model.EffectiveEntitlements
import com.example.data.model.ReceiptTemplateType
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
import kotlin.math.roundToInt

private const val TAG = "AiAssistantService"
private const val PRIMARY_AI_MODEL = "gemini-3.5-flash-lite"
private const val FALLBACK_AI_MODEL = "gemini-3.8-flash"
private const val QUOTA_COOLDOWN_MS = 10 * 60 * 1000L

enum class AssistantActionType {
    NONE,
    GENERATE_RECEIPT_NOW,
    PREPARE_RECEIPT_DRAFT,
    REPORT_COMPLAINT,
    CHECK_QUOTA_SUMMARY,
    OPEN_SCREEN
}

data class AssistantHistoryTurn(
    val isUser: Boolean,
    val text: String
)

data class AssistantActionResult(
    val reply: String,
    val action: AssistantActionType = AssistantActionType.NONE,
    val customerName: String = "",
    val customerPhone: String = "",
    val customerEmail: String = "",
    val amount: Double? = null,
    val discountAmount: Double? = null,
    val currency: String = "NGN",
    val paymentPurpose: String = "",
    val senderName: String = "",
    val senderAccountName: String = "",
    val senderBank: String = "",
    val beneficiaryBank: String = "",
    val transactionReference: String = "",
    val sessionId: String = "",
    val transactionDate: String = "",
    val transactionTime: String = "",
    val transferChannel: String = "",
    val paymentNarration: String = "",
    val invoiceNumber: String = "",
    val templateType: String = "",
    val notes: String = "",
    val complaintSubject: String = "",
    val complaintCategory: String = "TECHNICAL",
    val complaintDescription: String = "",
    val targetScreen: String = ""
)

class AiAssistantService {

    private val assistantSchema = Schema.obj(
        properties = mapOf(
            "reply" to Schema.string(description = "Concise, helpful natural-language response to the business owner"),
            "action" to Schema.enumeration(
                values = ACTION_ENUM_VALUES,
                description = "Fast action to execute in ReceiptsNow"
            ),
            "customerName" to Schema.string(description = "Customer full name for receipt, or senderName if generating from an uploaded transfer proof and customerName wasn't specified separately"),
            "customerPhone" to Schema.string(description = "Customer phone number if given, else empty string"),
            "customerEmail" to Schema.string(description = "Customer email if given, else empty string"),
            "amount" to Schema.double(description = "Numeric receipt amount paid/gross extracted from text or attached transfer proof, or null if not visible", nullable = true),
            "discountAmount" to Schema.double(description = "Numeric discount amount, or null if none", nullable = true),
            "currency" to Schema.string(description = "3-letter currency code such as NGN"),
            "paymentPurpose" to Schema.string(description = "Purpose/service/items paid for, or narration/default payment description when generating from a transfer proof"),
            "senderName" to Schema.string(description = "Bank transfer sender name if visible or given, else empty string"),
            "senderAccountName" to Schema.string(description = "Bank transfer sender account name if visible, else empty string"),
            "senderBank" to Schema.string(description = "Sending bank or fintech name e.g. GTBank, OPay, Moniepoint, Kuda, Access, UBA, Zenith, PalmPay, FirstBank, else empty string"),
            "beneficiaryBank" to Schema.string(description = "Beneficiary bank name if visible on transfer proof, else empty string"),
            "transactionReference" to Schema.string(description = "Bank transfer reference / NIP ref if visible or given, else empty string"),
            "sessionId" to Schema.string(description = "NIP Session ID if visible on transfer proof, else empty string"),
            "transactionDate" to Schema.string(description = "Transaction date as shown on transfer proof, else empty string"),
            "transactionTime" to Schema.string(description = "Transaction time as shown on transfer proof, else empty string"),
            "transferChannel" to Schema.string(description = "Transfer channel e.g. Mobile App, NIP, POS, Web, Bank Transfer, else empty string"),
            "paymentNarration" to Schema.string(description = "Transfer narration if visible or given, else empty string"),
            "invoiceNumber" to Schema.string(description = "Invoice number if given, else empty string"),
            "templateType" to Schema.enumeration(
                values = ReceiptTemplateType.entries.map { it.name },
                description = "Receipt template style (leave as business default unless user asked for a specific style)"
            ),
            "notes" to Schema.string(description = "Optional receipt notes, else empty string"),
            "complaintSubject" to Schema.string(description = "Short subject line when action is REPORT_COMPLAINT, else empty string"),
            "complaintCategory" to Schema.enumeration(
                values = COMPLAINT_CATEGORY_VALUES,
                description = "Complaint category when reporting an issue"
            ),
            "complaintDescription" to Schema.string(description = "Clear detailed description of the customer's issue for Super Admin when action is REPORT_COMPLAINT, else empty string"),
            "targetScreen" to Schema.enumeration(
                values = TARGET_SCREEN_VALUES,
                description = "Screen route key when action is OPEN_SCREEN, otherwise NONE"
            )
        ),
        optionalProperties = listOf(
            "amount",
            "discountAmount",
            "customerPhone",
            "customerEmail",
            "senderName",
            "senderAccountName",
            "senderBank",
            "beneficiaryBank",
            "transactionReference",
            "sessionId",
            "transactionDate",
            "transactionTime",
            "transferChannel",
            "paymentNarration",
            "invoiceNumber",
            "templateType",
            "notes",
            "complaintSubject",
            "complaintCategory",
            "complaintDescription",
            "targetScreen"
        )
    )

    private val primaryModel: GenerativeModel by lazy {
        Firebase.ai.generativeModel(
            modelName = PRIMARY_AI_MODEL,
            generationConfig = generationConfig {
                responseMimeType = "application/json"
                responseSchema = assistantSchema
            }
        )
    }

    private val fallbackModel: GenerativeModel by lazy {
        Firebase.ai.generativeModel(
            modelName = FALLBACK_AI_MODEL,
            generationConfig = generationConfig {
                responseMimeType = "application/json"
                responseSchema = assistantSchema
            }
        )
    }

    suspend fun sendMessage(
        userMessage: String,
        history: List<AssistantHistoryTurn>,
        business: Business,
        entitlements: EffectiveEntitlements?,
        customers: List<Customer>,
        attachedImage: Bitmap? = null,
        attachedPdfBytes: ByteArray? = null
    ): Result<AssistantActionResult> = withContext(Dispatchers.IO) {
        try {
            val systemContext = buildSystemPrompt(
                business = business,
                entitlements = entitlements,
                customers = customers,
                hasAttachment = (attachedImage != null || attachedPdfBytes != null)
            )
            val transcript = history.takeLast(8).joinToString("\n") { turn ->
                val speaker = if (turn.isUser) "BusinessOwner" else "Assistant"
                "$speaker: ${turn.text}"
            }
            val fullPrompt = buildString {
                appendLine(systemContext)
                if (transcript.isNotBlank()) {
                    appendLine()
                    appendLine("RECENT CONVERSATION:")
                    appendLine(transcript)
                }
                appendLine()
                appendLine("CURRENT USER MESSAGE:")
                appendLine(
                    userMessage.ifBlank {
                        "Extract all details from this uploaded bank transfer receipt and immediately generate an official customer receipt (GENERATE_RECEIPT_NOW)."
                    }
                )
            }

            val compressedJpeg = if (attachedImage != null && !attachedImage.isRecycled) {
                encodeJpegForAi(attachedImage, maxDim = 1200, quality = 82)
            } else {
                null
            }

            val response = if (attachedPdfBytes != null && attachedPdfBytes.size <= 15 * 1024 * 1024) {
                try {
                    generateWithResilientFallback {
                        content {
                            inlineData(attachedPdfBytes, "application/pdf")
                            text(fullPrompt)
                        }
                    }
                } catch (pdfErr: Exception) {
                    if (pdfErr is CancellationException && pdfErr !is TimeoutCancellationException) throw pdfErr
                    if (compressedJpeg != null) {
                        Log.w(TAG, "PDF inline AI chat fallback to compressed preview JPEG: ${pdfErr.message}")
                        generateWithResilientFallback {
                            content {
                                inlineData(compressedJpeg, "image/jpeg")
                                text(fullPrompt)
                            }
                        }
                    } else {
                        throw pdfErr
                    }
                }
            } else {
                generateWithResilientFallback {
                    content {
                        if (compressedJpeg != null) {
                            inlineData(compressedJpeg, "image/jpeg")
                        }
                        text(fullPrompt)
                    }
                }
            }
            val jsonText = response.text ?: throw IllegalStateException("AI Assistant returned an empty response.")
            Result.success(parseAssistantResponseJson(jsonText))
        } catch (e: TimeoutCancellationException) {
            Log.e(TAG, "AI Assistant timed out", e)
            Result.failure(Exception("AI Assistant timed out due to slow network. Please tap Send again."))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "AI Assistant generation failed", e)
            Result.failure(Exception(formatFriendlyAiError(e)))
        }
    }

    private suspend fun generateWithResilientFallback(
        buildContent: () -> Content
    ): GenerateContentResponse {
        var didRefreshAppCheck = false
        while (true) {
            try {
                return executeModelWithCooldownAndFallback(buildContent)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (!didRefreshAppCheck && isAppCheckError(e)) {
                    didRefreshAppCheck = true
                    Log.w(TAG, "App Check token rejected (${e.message}); forcing App Check token refresh and retrying once.")
                    val refreshed = refreshFirebaseAppCheckToken()
                    if (refreshed) continue
                }
                throw e
            }
        }
    }

    private suspend fun executeModelWithCooldownAndFallback(
        buildContent: () -> Content
    ): GenerateContentResponse {
        val now = System.currentTimeMillis()
        if (now < primaryQuotaCooldownUntilMs.get()) {
            return withTimeout(32_000L) {
                fallbackModel.generateContent(buildContent())
            }
        }
        return try {
            withTimeout(28_000L) {
                primaryModel.generateContent(buildContent())
            }
        } catch (e: TimeoutCancellationException) {
            Log.w(TAG, "$PRIMARY_AI_MODEL timed out; retrying with $FALLBACK_AI_MODEL")
            withTimeout(32_000L) {
                fallbackModel.generateContent(buildContent())
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (isTransientModelError(e)) {
                if (isQuotaOrRateLimitError(e)) {
                    primaryQuotaCooldownUntilMs.set(System.currentTimeMillis() + QUOTA_COOLDOWN_MS)
                }
                Log.w(TAG, "$PRIMARY_AI_MODEL transient/quota failure (${e.message}); retrying with $FALLBACK_AI_MODEL")
                withTimeout(32_000L) {
                    fallbackModel.generateContent(buildContent())
                }
            } else {
                throw e
            }
        }
    }

    private fun buildSystemPrompt(
        business: Business,
        entitlements: EffectiveEntitlements?,
        customers: List<Customer>,
        hasAttachment: Boolean
    ): String {
        val bizName = business.tradingName.ifBlank { business.registeredName }
        val defaultTpl = business.defaultTemplate.ifBlank { ReceiptTemplateType.STANDARD.name }
        val knownCustomers = customers.take(12).joinToString("; ") { c ->
            "${c.fullName}${if (c.phone.isNotBlank()) " (${c.phone})" else ""}"
        }.ifBlank { "None yet" }
        val quotaInfo = if (entitlements != null) {
            val rLimit = if (entitlements.maxReceiptsPerCycle < 0) "Unlimited" else "${entitlements.receiptsUsed}/${entitlements.totalReceiptAllowance} (${entitlements.receiptsRemaining} left)"
            val oLimit = if (entitlements.maxOcrPerCycle < 0) "Unlimited" else "${entitlements.ocrUsed}/${entitlements.maxOcrPerCycle}"
            "Plan=${entitlements.planName}, Active=${business.isSubscriptionActive(gracePeriodEndMillis = entitlements.gracePeriodEndMillis)}, Receipts=$rLimit, OCR=$oLimit, AllowedTemplates=${entitlements.allowedTemplates.joinToString(",")}"
        } else {
            "Plan=${business.subscriptionPlanCode}, Status=${business.subscriptionStatus}"
        }

        return """
            You are the fast, reliable AI operations assistant for "$bizName" inside ReceiptsNow (Nigerian business receipt & financial verification platform).
            
            WORKSPACE CONTEXT:
            - Business Name: $bizName
            - Default Currency: ${business.defaultCurrency.ifBlank { "NGN" }}
            - Default Receipt Template: $defaultTpl
            - Subscription & Quotas: $quotaInfo
            - Recent Saved Customers: $knownCustomers
            - Attachment Included In This Turn: $hasAttachment

            FAST ACTIONS YOU CAN TRIGGER:
            1. GENERATE_RECEIPT_NOW:
               - ALWAYS choose GENERATE_RECEIPT_NOW whenever an uploaded bank transfer screenshot/PDF shows a valid positive numeric `amount` (unless the user explicitly says "save draft", "prepare draft only", or "let me edit first").
               - ALSO choose GENERATE_RECEIPT_NOW when the user provides receipt details in text or conversation history with a positive `amount` and customer name.
               - Extract ALL visible transaction details from the attached proof accurately: `amount`, `senderName`, `senderAccountName`, `senderBank`, `beneficiaryBank`, `transactionReference`, `sessionId`, `transactionDate`, `transactionTime`, `transferChannel`, and `paymentNarration`.
               - If the user did not specify a separate `customerName` in text, set `customerName` to the extracted `senderName` (or `senderAccountName`).
               - If the user did not specify `paymentPurpose` in text, use the extracted `paymentNarration` or "Bank Transfer Payment".
               - Set `templateType` to "$defaultTpl" unless the user requests a specific template style.
               - Set `targetScreen` to "NONE".
               - NEVER invent numbers or fabricate fields that are not provided in text or visible in the uploaded document.
            2. PREPARE_RECEIPT_DRAFT: Use ONLY when the user explicitly asks to prepare/edit a draft first, OR when `amount` cannot be determined (> 0) from text or attachment. Set `targetScreen` to "NONE".
            3. REPORT_COMPLAINT: Use whenever the business owner reports a bug, error, difficulty using the app, payment confirmation issue, WhatsApp sharing problem, or asks to complain / contact / escalate to the Platform Admin. Always populate `complaintSubject`, `complaintCategory`, and a clear `complaintDescription`.
            4. CHECK_QUOTA_SUMMARY: Use when the user asks about their remaining receipts, OCR credits, storage usage, or subscription plan status.
            5. OPEN_SCREEN: Use when the user simply asks to open a section (GENERATE, RECEIPTS, CUSTOMERS, BILLING, VERIFY_QUEUE, REPORTS, CALCULATOR, BRANDING, STORAGE, HELP). Use CALCULATOR for End-of-Day receipt tallying, expenditure/purchase/profit breakdown.
            6. NONE: Use only for general how-to questions when no attachment or action is needed. Set `targetScreen` to "NONE".

            Keep replies crisp, polite, accurate, and tailored to ReceiptsNow.
        """.trimIndent()
    }

    private fun encodeJpegForAi(bitmap: Bitmap, maxDim: Int, quality: Int): ByteArray {
        val w = bitmap.width
        val h = bitmap.height
        val scaled = if (w <= maxDim && h <= maxDim) {
            bitmap
        } else {
            val ratio = minOf(maxDim.toFloat() / w, maxDim.toFloat() / h)
            val newW = (w * ratio).roundToInt().coerceAtLeast(1)
            val newH = (h * ratio).roundToInt().coerceAtLeast(1)
            Bitmap.createScaledBitmap(bitmap, newW, newH, true)
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
        val ACTION_ENUM_VALUES = listOf(
            "NONE",
            "GENERATE_RECEIPT_NOW",
            "PREPARE_RECEIPT_DRAFT",
            "REPORT_COMPLAINT",
            "CHECK_QUOTA_SUMMARY",
            "OPEN_SCREEN"
        )
        val COMPLAINT_CATEGORY_VALUES = listOf(
            "TECHNICAL", "BILLING", "RECEIPT_GENERATION", "WHATSAPP_SHARE", "ACCOUNT", "OTHER"
        )
        val TARGET_SCREEN_VALUES = listOf(
            "NONE", "GENERATE", "RECEIPTS", "CUSTOMERS", "BILLING",
            "VERIFY_QUEUE", "REPORTS", "CALCULATOR", "BRANDING", "STORAGE", "HELP"
        )

        private val primaryQuotaCooldownUntilMs = AtomicLong(0L)

        fun isQuotaOrRateLimitError(err: Throwable?): Boolean {
            var cur = err
            while (cur != null) {
                val msg = cur.message.orEmpty().lowercase()
                if (msg.contains("quota") ||
                    msg.contains("exceeded") ||
                    msg.contains("rate-limit") ||
                    msg.contains("rate limit") ||
                    msg.contains("rate_limit") ||
                    msg.contains("free_tier") ||
                    msg.contains("please retry") ||
                    msg.contains("retry in") ||
                    msg.contains("too many requests") ||
                    msg.contains("resource exhausted") ||
                    msg.contains("resource_exhausted") ||
                    msg.contains("429")
                ) {
                    return true
                }
                cur = cur.cause
            }
            return false
        }

        fun isTransientModelError(err: Throwable?): Boolean {
            if (isQuotaOrRateLimitError(err)) return true
            var cur = err
            while (cur != null) {
                val msg = cur.message.orEmpty().lowercase()
                if (msg.contains("high demand") ||
                    msg.contains("overloaded") ||
                    msg.contains("temporarily unavailable") ||
                    msg.contains("unavailable") ||
                    msg.contains("503") ||
                    msg.contains("deadline") ||
                    msg.contains("timeout") ||
                    msg.contains("try again")
                ) {
                    return true
                }
                cur = cur.cause
            }
            return false
        }

        fun isAppCheckError(err: Throwable?): Boolean {
            var cur = err
            while (cur != null) {
                val msg = cur.message.orEmpty().lowercase()
                if (msg.contains("app check") ||
                    msg.contains("appcheck") ||
                    msg.contains("attestation")
                ) {
                    return true
                }
                cur = cur.cause
            }
            return false
        }

        fun formatFriendlyAiError(err: Throwable): String {
            if (isAppCheckError(err)) {
                return "AI verification token needs a fresh session (Firebase App Check). Check your internet connection, install the latest signed update, and tap Send again — or issue the receipt directly on the Generate tab."
            }
            if (isQuotaOrRateLimitError(err)) {
                return "AI service daily/rate quota reached on the server. Wait a few minutes and try again, or enter receipt details directly on the Generate tab."
            }
            if (isTransientModelError(err)) {
                return "AI service is currently busy on the network. Please tap Send again in a moment."
            }
            return err.localizedMessage ?: "AI Assistant could not process your request."
        }

        fun stripJsonMarkdownFences(raw: String): String {
            val trimmed = raw.trim()
            if (!trimmed.startsWith("```")) return trimmed
            val withoutOpening = trimmed
                .removePrefix("```json")
                .removePrefix("```JSON")
                .removePrefix("```")
                .trimStart()
            return if (withoutOpening.endsWith("```")) {
                withoutOpening.dropLast(3).trim()
            } else {
                withoutOpening.trim()
            }
        }

        fun parseAssistantResponseJson(jsonString: String): AssistantActionResult {
            val json = JSONObject(stripJsonMarkdownFences(jsonString))
            val action = runCatching {
                AssistantActionType.valueOf(json.optString("action", "NONE").uppercase())
            }.getOrDefault(AssistantActionType.NONE)
            val rawAmount = if (json.isNull("amount")) null else json.optDouble("amount").takeIf { !it.isNaN() && it > 0.0 }
            val rawDiscount = if (json.isNull("discountAmount")) null else json.optDouble("discountAmount").takeIf { !it.isNaN() && it >= 0.0 }
            val rawTarget = json.optString("targetScreen", "").trim().uppercase()

            return AssistantActionResult(
                reply = json.optString("reply", "").ifBlank { "Done." },
                action = action,
                customerName = json.optString("customerName", "").trim(),
                customerPhone = json.optString("customerPhone", "").trim(),
                customerEmail = json.optString("customerEmail", "").trim(),
                amount = rawAmount,
                discountAmount = rawDiscount,
                currency = json.optString("currency", "NGN").ifBlank { "NGN" },
                paymentPurpose = json.optString("paymentPurpose", "").trim(),
                senderName = json.optString("senderName", "").trim(),
                senderAccountName = json.optString("senderAccountName", "").trim(),
                senderBank = json.optString("senderBank", "").trim(),
                beneficiaryBank = json.optString("beneficiaryBank", "").trim(),
                transactionReference = json.optString("transactionReference", "").trim(),
                sessionId = json.optString("sessionId", "").trim(),
                transactionDate = json.optString("transactionDate", "").trim(),
                transactionTime = json.optString("transactionTime", "").trim(),
                transferChannel = json.optString("transferChannel", "").trim(),
                paymentNarration = json.optString("paymentNarration", "").trim(),
                invoiceNumber = json.optString("invoiceNumber", "").trim(),
                templateType = json.optString("templateType", "").trim(),
                notes = json.optString("notes", "").trim(),
                complaintSubject = json.optString("complaintSubject", "").trim(),
                complaintCategory = json.optString("complaintCategory", "TECHNICAL").ifBlank { "TECHNICAL" },
                complaintDescription = json.optString("complaintDescription", "").trim(),
                targetScreen = if (rawTarget == "NONE") "" else rawTarget
            )
        }
    }
}
