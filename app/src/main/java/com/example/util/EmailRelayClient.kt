package com.example.util

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException

/**
 * Validates, normalizes, and executes HTTP POST delivery to the Super Admin
 * Email Relay Webhook (Google Apps Script Web App or Firebase Cloud Function).
 *
 * Handles:
 * - Multi-account Google Apps Script URLs (/macros/u/0/s/ -> /macros/s/)
 * - Common pasted URL mistakes (/edit, /dev, Firebase Hosting root URL)
 * - Redirect preservation so POST -> 301/302 does NOT silently downgrade to GET (HTTP 405),
 *   except for Google Apps Script's expected 302 -> script.googleusercontent.com/macros/echo
 * - Detection of Google Sign-In redirect / HTML login pages
 * - Full HTTP 400/401/403/404/405/5xx response parsing & actionable error messages
 */
object EmailRelayClient {

    data class RelayOutcome(
        val statusCode: Int,
        val finalUrl: String,
        val responseSnippet: String
    )

    private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    private val MULTI_USER_MACROS_REGEX = Regex("/macros/u/\\d+/s/")
    private val HTML_TAG_REGEX = Regex("<[^>]+>")
    private val WHITESPACE_REGEX = Regex("\\s+")
    private const val MAX_REDIRECT_HOPS = 5

    fun normalizeAndValidateUrl(rawUrl: String): Result<HttpUrl> {
        var cleaned = rawUrl
            .trim()
            .trim('"', '\'', '`')
            .replace("\r", "")
            .replace("\n", "")
            .trim()

        if (cleaned.isBlank()) {
            return Result.failure(
                IllegalArgumentException(
                    "Email Relay Webhook URL is empty. Paste your Google Apps Script (/exec) or Cloud Function URL first."
                )
            )
        }

        if (!cleaned.startsWith("http://", ignoreCase = true) &&
            !cleaned.startsWith("https://", ignoreCase = true)
        ) {
            cleaned = "https://$cleaned"
        }

        // Normalize Google Apps Script multi-account paths: /macros/u/0/s/... -> /macros/s/...
        if (cleaned.contains("script.google.com", ignoreCase = true)) {
            cleaned = cleaned.replace(MULTI_USER_MACROS_REGEX, "/macros/s/")
        }

        // Upgrade http:// -> https:// for non-local hosts to prevent cleartext failures & 301/302 POST->GET downgrades
        if (cleaned.startsWith("http://", ignoreCase = true)) {
            val hostPreview = cleaned.removePrefix("http://").substringBefore("/").substringBefore(":").lowercase()
            if (hostPreview != "localhost" && hostPreview != "127.0.0.1") {
                cleaned = "https://" + cleaned.substring(7)
            }
        }

        val parsed = cleaned.toHttpUrlOrNull()
            ?: return Result.failure(
                IllegalArgumentException(
                    "Invalid Email Relay URL format. Must start with https:// (for example https://script.google.com/macros/s/.../exec)."
                )
            )

        val host = parsed.host.lowercase()
        val path = parsed.encodedPath.lowercase()

        if (host == "script.google.com") {
            if (path.endsWith("/edit") || path.contains("/home/projects") || path.startsWith("/d/")) {
                return Result.failure(
                    IllegalArgumentException(
                        "You pasted a Google Apps Script editor URL, not a deployed Web App URL. Open Deploy → New deployment → Web app → Who has access: Anyone, and copy the URL ending in /exec."
                    )
                )
            }
            if (path.endsWith("/dev")) {
                return Result.failure(
                    IllegalArgumentException(
                        "Apps Script /dev URLs only work in your logged-in browser and reject app requests. Copy the production Web App URL ending in /exec."
                    )
                )
            }
            if (!path.contains("/macros/s/") || !path.endsWith("/exec")) {
                return Result.failure(
                    IllegalArgumentException(
                        "Incomplete Google Apps Script URL. It must follow https://script.google.com/macros/s/<ID>/exec."
                    )
                )
            }
        }

        if ((host.endsWith(".web.app") || host.endsWith(".firebaseapp.com")) &&
            (path.isBlank() || path == "/" || path == "/index.html")
        ) {
            return Result.failure(
                IllegalArgumentException(
                    "Firebase Hosting web portal URLs reject POST requests (HTTP 405). Paste your Cloud Function sendSubscriptionNotificationWebhook URL or Apps Script /exec URL instead."
                )
            )
        }

        return Result.success(parsed)
    }

    fun postJson(
        baseClient: OkHttpClient,
        rawUrl: String,
        payload: JSONObject
    ): Result<RelayOutcome> = runCatching {
        val initialUrl = normalizeAndValidateUrl(rawUrl).getOrThrow()
        val noRedirectClient = baseClient.newBuilder()
            .followRedirects(false)
            .followSslRedirects(false)
            .build()

        val payloadJson = payload.toString()
        var currentUrl: HttpUrl = initialUrl
        var useGetForNextHop = false

        for (hop in 0..MAX_REDIRECT_HOPS) {
            val reqBuilder = Request.Builder()
                .url(currentUrl)
                .header("Accept", "application/json, text/plain, */*")
                .header("User-Agent", "ReceiptsNow/1.0 (Android)")

            if (useGetForNextHop) {
                reqBuilder.get()
            } else {
                reqBuilder.post(payloadJson.toRequestBody(JSON_MEDIA_TYPE))
            }

            noRedirectClient.newCall(reqBuilder.build()).execute().use { resp ->
                val code = resp.code
                val locationHeader = resp.header("Location")?.trim().orEmpty()

                if (code in 300..399 && locationHeader.isNotBlank()) {
                    val nextUrl = currentUrl.resolve(locationHeader)
                        ?: throw IllegalStateException("Email relay returned an invalid redirect target: $locationHeader")
                    val nextHost = nextUrl.host.lowercase()

                    if (nextHost == "accounts.google.com" ||
                        nextUrl.encodedPath.contains("ServiceLogin", ignoreCase = true)
                    ) {
                        throw IllegalStateException(
                            "Google Apps Script required sign-in (redirected to accounts.google.com). Redeploy the Web App with 'Execute as: Me' and 'Who has access: Anyone'."
                        )
                    }

                    // Google Apps Script runs doPost(e) on the initial POST and returns 302 to
                    // script.googleusercontent.com/macros/echo?... which must be fetched via GET.
                    val isAppsScriptEcho = currentUrl.host.equals("script.google.com", ignoreCase = true) &&
                        nextHost.endsWith("googleusercontent.com")

                    useGetForNextHop = isAppsScriptEcho || code == 303
                    currentUrl = nextUrl
                    return@use
                }

                val rawBody = resp.body?.string().orEmpty().trim()
                val contentType = resp.header("Content-Type").orEmpty().lowercase()

                if (!resp.isSuccessful) {
                    throw IllegalStateException(formatHttpFailure(code, rawBody, currentUrl.toString()))
                }

                if (looksLikeHtml(rawBody, contentType)) {
                    val lower = rawBody.lowercase()
                    if (lower.contains("servicelogin") ||
                        lower.contains("accounts.google.com") ||
                        lower.contains("sign in")
                    ) {
                        throw IllegalStateException(
                            "Google Apps Script returned a Sign-In page instead of running the relay. Set 'Who has access: Anyone' in your Web App deployment."
                        )
                    }
                    if (lower.contains("script function not found: dopost")) {
                        throw IllegalStateException(
                            "Google Apps Script is missing function doPost(e). Paste the full script from 'Setup Gmail Relay' and redeploy."
                        )
                    }
                    val snippet = extractReadableSnippet(rawBody)
                    throw IllegalStateException(
                        "Email relay returned an unexpected HTML response instead of JSON${if (snippet.isNotBlank()) " ($snippet)" else ""}."
                    )
                }

                if (rawBody.startsWith("{")) {
                    val json = runCatching { JSONObject(rawBody) }.getOrNull()
                    if (json != null) {
                        if (json.has("ok") && !json.optBoolean("ok", true)) {
                            val errMsg = json.optString("error").ifBlank {
                                json.optString("message").ifBlank { "Email relay rejected the request." }
                            }
                            throw IllegalStateException(errMsg)
                        }
                        if (json.has("error") && json.optString("error").isNotBlank()) {
                            throw IllegalStateException(json.optString("error"))
                        }
                    }
                }

                return@runCatching RelayOutcome(
                    statusCode = code,
                    finalUrl = currentUrl.toString(),
                    responseSnippet = extractReadableSnippet(rawBody)
                )
            }
        }

        throw IOException("Email relay exceeded $MAX_REDIRECT_HOPS redirects.")
    }

    internal fun formatHttpFailure(statusCode: Int, rawBody: String, url: String): String {
        val serverMsg = extractServerErrorMessage(rawBody)
        val detailSuffix = if (serverMsg.isNotBlank()) " — $serverMsg" else ""
        val hint = when (statusCode) {
            400 -> "Verify the Apps Script /exec or Cloud Function URL and JSON payload."
            401 -> "Unauthorized. Redeploy Google Apps Script with 'Who has access: Anyone'."
            403 -> if (url.contains("cloudfunctions.net", ignoreCase = true) || url.contains("run.app", ignoreCase = true)) {
                "Cloud Function denied access. Redeploy functions with invoker: \"public\"."
            } else {
                "Access forbidden. In Google Apps Script set 'Execute as: Me' and 'Who has access: Anyone' and deploy a new version."
            }
            404 -> "Webhook endpoint not found. Check that your /exec deployment ID or Cloud Function URL is valid and active."
            405 -> "Endpoint rejected HTTP POST. Ensure you copied the /exec Web App URL (not /edit) and implemented doPost(e)."
            500, 502, 503 -> "Relay server failed while sending email (check GMAIL_APP_PASSWORD secret or Apps Script MailApp quota)."
            else -> "Verify the webhook deployment is active and reachable."
        }
        return "Email relay returned HTTP $statusCode$detailSuffix. $hint"
    }

    internal fun extractServerErrorMessage(rawBody: String): String {
        val trimmed = rawBody.trim()
        if (trimmed.isBlank()) return ""
        if (trimmed.startsWith("{")) {
            val json = runCatching { JSONObject(trimmed) }.getOrNull()
            if (json != null) {
                val msg = json.optString("error")
                    .ifBlank { json.optString("message") }
                    .ifBlank { json.optString("detail") }
                    .trim()
                if (msg.isNotBlank()) return msg.take(140)
            }
        }
        return extractReadableSnippet(trimmed)
    }

    private fun looksLikeHtml(rawBody: String, contentType: String): Boolean {
        if (contentType.contains("text/html")) return true
        val lower = rawBody.trimStart().lowercase()
        return lower.startsWith("<!doctype html") ||
            lower.startsWith("<html") ||
            lower.contains("<title>")
    }

    private fun extractReadableSnippet(rawBody: String): String {
        if (rawBody.isBlank()) return ""
        val cleaned = rawBody
            .replace(Regex("(?is)<style.*?>.*?</style>"), " ")
            .replace(Regex("(?is)<script.*?>.*?</script>"), " ")
            .replace(HTML_TAG_REGEX, " ")
            .replace(WHITESPACE_REGEX, " ")
            .trim()
        return cleaned.take(120)
    }
}
