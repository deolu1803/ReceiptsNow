package com.example.data.billing

import com.example.data.model.BillingFrequency
import com.example.data.model.PlatformSetting
import com.example.data.model.SubscriptionPlan
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import java.util.concurrent.TimeUnit

data class PaystackVerificationResult(
    val verified: Boolean,
    val reference: String = "",
    val transactionId: String = "",
    val amountMinor: Long = 0L,
    val currency: String = "NGN",
    val channel: String = "",
    val bank: String = "",
    val cardSummary: String = "",
    val customerEmail: String = "",
    val gatewayResponse: String = "",
    val paidAtIso: String = "",
    val paidAtMillis: Long = 0L,
    val verificationSource: String = "",
    val rawStatus: String = "",
    val errorMessage: String = ""
)

data class DetectedPaystackPageCompletion(
    val reference: String,
    val channelHint: String = "",
    val summaryText: String = "",
    val detectedFromRedirect: Boolean = false
)

object PaystackPaymentService {
    const val DEFAULT_PAYSTACK_MONTHLY_URL = "https://paystack.shop/pay/1803reciepts"
    const val DEFAULT_PAYSTACK_ANNUAL_URL = "https://paystack.shop/pay/1803receipt"
    const val PAYSTACK_VERIFY_API_BASE = "https://api.paystack.co/transaction/verify/"

    const val SOURCE_PAYSTACK_API = "PAYSTACK_API_VERIFIED"
    const val SOURCE_CHECKOUT_CONFIRMED = "CHECKOUT_CONFIRMED"
    const val SOURCE_MANUAL_UNVERIFIED = "MANUAL_REF_UNVERIFIED"
    const val SOURCE_ADMIN_VERIFIED = "ADMIN_VERIFIED"

    private val moshi: Moshi = Moshi.Builder().build()
    private val mapAdapter = moshi.adapter<Map<String, Any?>>(
        Types.newParameterizedType(Map::class.java, String::class.java, Any::class.java)
    )

    private val defaultHttpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(25, TimeUnit.SECONDS)
            .writeTimeout(20, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
    }

    fun resolvePlanUrl(plan: SubscriptionPlan, setting: PlatformSetting): String {
        val explicit = plan.paystackPaymentUrl.trim()
        if (explicit.startsWith("https://", ignoreCase = true) ||
            explicit.startsWith("http://", ignoreCase = true)
        ) {
            return explicit
        }
        val isAnnual = plan.frequencyEnum() == BillingFrequency.ANNUAL ||
            plan.planCode.contains("ANNUAL", ignoreCase = true) ||
            plan.planCode.contains("YEAR", ignoreCase = true) ||
            plan.name.contains("ANNUAL", ignoreCase = true) ||
            plan.name.contains("YEAR", ignoreCase = true) ||
            plan.intervalDays >= 300
        return if (isAnnual) {
            setting.paystackAnnualUrl.trim().ifBlank { DEFAULT_PAYSTACK_ANNUAL_URL }
        } else {
            setting.paystackMonthlyUrl.trim().ifBlank { DEFAULT_PAYSTACK_MONTHLY_URL }
        }
    }

    fun buildCheckoutUrl(baseUrl: String, customerEmail: String = ""): String {
        val trimmedBase = baseUrl.trim().ifBlank { DEFAULT_PAYSTACK_MONTHLY_URL }
        val cleanEmail = customerEmail.trim()
        if (cleanEmail.isBlank() || !cleanEmail.contains("@")) return trimmedBase
        return runCatching {
            val uri = URI(trimmedBase)
            val existingQuery = uri.rawQuery.orEmpty()
            if (existingQuery.contains("email=", ignoreCase = true)) {
                trimmedBase
            } else {
                val encodedEmail = URLEncoder.encode(cleanEmail, Charsets.UTF_8.name())
                val separator = if (existingQuery.isBlank()) "?" else "&"
                "$trimmedBase${separator}email=$encodedEmail"
            }
        }.getOrDefault(trimmedBase)
    }

    fun isTrustedPaystackHost(host: String?): Boolean {
        val normalized = host?.trim()?.lowercase(Locale.US).orEmpty()
        if (normalized.isBlank()) return false
        return normalized == "paystack.shop" ||
            normalized.endsWith(".paystack.shop") ||
            normalized == "paystack.com" ||
            normalized.endsWith(".paystack.com") ||
            normalized == "paystack.co" ||
            normalized.endsWith(".paystack.co")
    }

    fun extractReferenceFromUrl(url: String): String? {
        val raw = url.trim()
        if (raw.isBlank()) return null
        val query = runCatching { URI(raw).rawQuery }.getOrNull()
            ?: raw.substringAfter('?', "").substringBefore('#')
        if (query.isBlank()) return null
        val params = query.split('&')
        for (pair in params) {
            val key = pair.substringBefore('=').trim().lowercase(Locale.US)
            if (key == "trxref" || key == "reference" || key == "txref") {
                val value = runCatching {
                    URLDecoder.decode(pair.substringAfter('=', ""), Charsets.UTF_8.name())
                }.getOrDefault(pair.substringAfter('=', "")).trim()
                if (value.length >= 6 && !value.contains("cancel", ignoreCase = true)) {
                    return value.take(120)
                }
            }
        }
        return null
    }

    fun isCancelledOrFailedUrl(url: String): Boolean {
        val lower = url.lowercase(Locale.US)
        return lower.contains("status=cancelled") ||
            lower.contains("status=failed") ||
            lower.contains("cancelled=true") ||
            lower.contains("/cancel")
    }

    fun detectCompletionFromPageText(
        url: String,
        pageText: String
    ): DetectedPaystackPageCompletion? {
        if (isCancelledOrFailedUrl(url)) return null

        val urlRef = extractReferenceFromUrl(url)
        if (!urlRef.isNullOrBlank()) {
            return DetectedPaystackPageCompletion(
                reference = urlRef,
                summaryText = "Paystack callback reference detected",
                detectedFromRedirect = true
            )
        }

        val host = runCatching { URI(url).host }.getOrNull()
        if (!isTrustedPaystackHost(host)) return null

        val normalizedText = pageText
            .replace(Regex("\\s+"), " ")
            .trim()
            .lowercase(Locale.US)
        if (normalizedText.isBlank()) return null

        val failurePhrases = listOf(
            "payment failed",
            "transaction failed",
            "card declined",
            "your payment could not be processed",
            "insufficient funds",
            "transaction was not successful",
            "payment unsuccessful",
            "invalid card",
            "unable to process"
        )
        if (failurePhrases.any { normalizedText.contains(it) }) return null

        val successPhrases = listOf(
            "payment successful",
            "transaction successful",
            "thank you for your payment",
            "your payment was successful",
            "you just paid",
            "payment confirmed",
            "payment received",
            "receipt has been sent",
            "paid successfully",
            "you paid "
        )
        val matchedSuccess = successPhrases.firstOrNull { normalizedText.contains(it) } ?: return null

        val refRegex = Regex(
            "(?:reference|transaction\\s*ref(?:erence)?|trxref|ref)\\s*[:#\\-]?\\s*([A-Za-z0-9_\\-]{6,64})",
            RegexOption.IGNORE_CASE
        )
        val extractedRef = refRegex.find(pageText)?.groupValues?.getOrNull(1)?.trim()
            ?.takeUnless { candidate ->
                candidate.equals("number", true) ||
                    candidate.equals("code", true) ||
                    candidate.equals("id", true) ||
                    candidate.equals("payment", true)
            }
            ?: generateCheckoutSessionRef()

        val channelHint = when {
            normalizedText.contains("opay") -> "opay"
            normalizedText.contains("transfer") || normalizedText.contains("bank") -> "bank_transfer"
            normalizedText.contains("ussd") -> "ussd"
            normalizedText.contains("visa") || normalizedText.contains("mastercard") || normalizedText.contains("verve") || normalizedText.contains("card") -> "card"
            else -> "paystack_checkout"
        }

        return DetectedPaystackPageCompletion(
            reference = extractedRef,
            channelHint = channelHint,
            summaryText = matchedSuccess.replaceFirstChar { it.uppercaseChar() },
            detectedFromRedirect = false
        )
    }

    fun generateCheckoutSessionRef(prefix: String = "PSK_RNW"): String {
        val token = UUID.randomUUID().toString().replace("-", "").take(12).uppercase(Locale.US)
        return "${prefix}_$token"
    }

    fun parsePaystackVerifyJson(
        jsonString: String,
        expectedAmountMinor: Long = 0L,
        fallbackReference: String = ""
    ): PaystackVerificationResult {
        val root = runCatching { mapAdapter.fromJson(jsonString) }.getOrNull()
            ?: return PaystackVerificationResult(
                verified = false,
                reference = fallbackReference,
                errorMessage = "Invalid JSON response from Paystack verification."
            )

        val apiStatus = root["status"] as? Boolean ?: false
        val topMessage = (root["message"] as? String).orEmpty()

        @Suppress("UNCHECKED_CAST")
        val data = root["data"] as? Map<String, Any?>
        if (!apiStatus || data == null) {
            return PaystackVerificationResult(
                verified = false,
                reference = fallbackReference,
                rawStatus = "error",
                errorMessage = topMessage.ifBlank { "Paystack verification did not return transaction data." }
            )
        }

        val txStatus = (data["status"] as? String).orEmpty().trim().lowercase(Locale.US)
        val gatewayResponse = (data["gateway_response"] as? String).orEmpty().ifBlank { topMessage }
        val ref = (data["reference"] as? String).orEmpty().ifBlank { fallbackReference }
        val txId = when (val rawId = data["id"]) {
            is Number -> rawId.toLong().toString()
            is String -> rawId
            else -> ""
        }
        val amountMinor = (data["amount"] as? Number)?.toLong() ?: 0L
        val currency = (data["currency"] as? String).orEmpty().ifBlank { "NGN" }
        val channel = (data["channel"] as? String).orEmpty()
        val paidAtIso = (data["paid_at"] as? String).orEmpty().ifBlank {
            (data["paidAt"] as? String).orEmpty()
        }
        val paidAtMillis = parseIsoTimestampMillis(paidAtIso)

        @Suppress("UNCHECKED_CAST")
        val auth = data["authorization"] as? Map<String, Any?>
        val bank = (auth?.get("bank") as? String).orEmpty()
        val brand = (auth?.get("brand") as? String).orEmpty()
            .ifBlank { (auth?.get("card_type") as? String).orEmpty() }
            .trim()
        val last4 = (auth?.get("last4") as? String).orEmpty().trim()
        val cardSummary = listOfNotNull(
            brand.takeIf { it.isNotBlank() }?.replaceFirstChar { it.uppercaseChar() },
            last4.takeIf { it.isNotBlank() }?.let { "•••• $it" }
        ).joinToString(" ")

        @Suppress("UNCHECKED_CAST")
        val customer = data["customer"] as? Map<String, Any?>
        val customerEmail = (customer?.get("email") as? String).orEmpty()

        if (txStatus != "success") {
            return PaystackVerificationResult(
                verified = false,
                reference = ref,
                transactionId = txId,
                amountMinor = amountMinor,
                currency = currency,
                channel = channel,
                bank = bank,
                cardSummary = cardSummary,
                customerEmail = customerEmail,
                gatewayResponse = gatewayResponse,
                paidAtIso = paidAtIso,
                paidAtMillis = paidAtMillis,
                verificationSource = SOURCE_PAYSTACK_API,
                rawStatus = txStatus,
                errorMessage = "Paystack status is '${txStatus.ifBlank { "unknown" }}' ($gatewayResponse)."
            )
        }

        if (expectedAmountMinor > 0L && amountMinor < expectedAmountMinor) {
            return PaystackVerificationResult(
                verified = false,
                reference = ref,
                transactionId = txId,
                amountMinor = amountMinor,
                currency = currency,
                channel = channel,
                bank = bank,
                cardSummary = cardSummary,
                customerEmail = customerEmail,
                gatewayResponse = gatewayResponse,
                paidAtIso = paidAtIso,
                paidAtMillis = paidAtMillis,
                verificationSource = SOURCE_PAYSTACK_API,
                rawStatus = txStatus,
                errorMessage = "Paid amount (${amountMinor / 100.0}) is below required plan amount (${expectedAmountMinor / 100.0})."
            )
        }

        return PaystackVerificationResult(
            verified = true,
            reference = ref,
            transactionId = txId,
            amountMinor = amountMinor,
            currency = currency,
            channel = channel,
            bank = bank,
            cardSummary = cardSummary,
            customerEmail = customerEmail,
            gatewayResponse = gatewayResponse.ifBlank { "Approved" },
            paidAtIso = paidAtIso,
            paidAtMillis = paidAtMillis,
            verificationSource = SOURCE_PAYSTACK_API,
            rawStatus = txStatus,
            errorMessage = ""
        )
    }

    suspend fun verifyTransaction(
        reference: String,
        expectedAmountMinor: Long,
        verifyEndpointUrl: String = "",
        secretKey: String = "",
        client: OkHttpClient = defaultHttpClient
    ): PaystackVerificationResult = withContext(Dispatchers.IO) {
        val cleanRef = reference.trim()
        if (cleanRef.isBlank()) {
            return@withContext PaystackVerificationResult(
                verified = false,
                errorMessage = "Missing Paystack payment reference."
            )
        }

        val cleanEndpoint = verifyEndpointUrl.trim()
        if (cleanEndpoint.startsWith("https://", ignoreCase = true) ||
            cleanEndpoint.startsWith("http://", ignoreCase = true)
        ) {
            val endpointResult = runCatching {
                val jsonBody = mapAdapter.toJson(
                    mapOf(
                        "reference" to cleanRef,
                        "expectedAmountMinor" to expectedAmountMinor
                    )
                )
                val req = Request.Builder()
                    .url(cleanEndpoint)
                    .post(jsonBody.toRequestBody("application/json; charset=utf-8".toMediaType()))
                    .header("Accept", "application/json")
                    .build()
                client.newCall(req).execute().use { resp ->
                    val bodyStr = resp.body?.string().orEmpty()
                    if (!resp.isSuccessful && bodyStr.isBlank()) {
                        PaystackVerificationResult(
                            verified = false,
                            reference = cleanRef,
                            errorMessage = "Paystack verify endpoint returned HTTP ${resp.code}."
                        )
                    } else {
                        parsePaystackVerifyJson(bodyStr, expectedAmountMinor, cleanRef)
                    }
                }
            }.getOrElse { err ->
                PaystackVerificationResult(
                    verified = false,
                    reference = cleanRef,
                    errorMessage = err.message ?: "Failed reaching Paystack verify endpoint."
                )
            }
            if (endpointResult.verified || endpointResult.rawStatus.isNotBlank()) {
                return@withContext endpointResult
            }
        }

        val cleanSecret = secretKey.trim()
            .takeUnless { it.equals("PAYSTACK_SECRET_KEY_PLACEHOLDER", ignoreCase = true) }
            .orEmpty()
        if (cleanSecret.isNotBlank()) {
            val encodedRef = URLEncoder.encode(cleanRef, Charsets.UTF_8.name())
            return@withContext runCatching {
                val req = Request.Builder()
                    .url("$PAYSTACK_VERIFY_API_BASE$encodedRef")
                    .get()
                    .header("Authorization", "Bearer $cleanSecret")
                    .header("Accept", "application/json")
                    .build()
                client.newCall(req).execute().use { resp ->
                    val bodyStr = resp.body?.string().orEmpty()
                    if (!resp.isSuccessful && bodyStr.isBlank()) {
                        PaystackVerificationResult(
                            verified = false,
                            reference = cleanRef,
                            errorMessage = "Paystack API returned HTTP ${resp.code}."
                        )
                    } else {
                        parsePaystackVerifyJson(bodyStr, expectedAmountMinor, cleanRef)
                    }
                }
            }.getOrElse { err ->
                PaystackVerificationResult(
                    verified = false,
                    reference = cleanRef,
                    errorMessage = err.message ?: "Network error contacting Paystack API."
                )
            }
        }

        PaystackVerificationResult(
            verified = false,
            reference = cleanRef,
            errorMessage = "No server verify endpoint or PAYSTACK_SECRET_KEY configured for API lookup."
        )
    }

    private fun parseIsoTimestampMillis(iso: String): Long {
        if (iso.isBlank()) return 0L
        val formats = listOf(
            "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
            "yyyy-MM-dd'T'HH:mm:ss'Z'",
            "yyyy-MM-dd'T'HH:mm:ss.SSSXXX",
            "yyyy-MM-dd'T'HH:mm:ssXXX"
        )
        for (pattern in formats) {
            val parsed = runCatching {
                SimpleDateFormat(pattern, Locale.US).apply {
                    timeZone = TimeZone.getTimeZone("UTC")
                }.parse(iso)?.time
            }.getOrNull()
            if (parsed != null && parsed > 0L) return parsed
        }
        return 0L
    }
}
