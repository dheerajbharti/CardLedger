package com.dheerajbharti.cardledger.gmail

import android.text.Html
import android.util.Base64
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit

class GmailClient(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .build()
) {
    data class GmailMessage(
        val id: String,
        val internalDateMillis: Long,
        val bodyText: String
    )

    fun getProfileEmail(accessToken: String): String? {
        val url = HttpUrl.Builder()
            .scheme("https")
            .host("gmail.googleapis.com")
            .addPathSegments("gmail/v1/users/me/profile")
            .build()
        val json = executeJson(url, accessToken)
        return json.optString("emailAddress").takeIf { it.isNotBlank() }
    }

    fun listTransactionMessageIds(accessToken: String, fullRescan: Boolean): List<String> {
        val query = buildString {
            append("from:credit_cards@icici.bank.in ")
            append("\"has been used for a transaction of\"")
            if (!fullRescan) append(" newer_than:120d")
        }

        val ids = LinkedHashSet<String>()
        var pageToken: String? = null
        var pageCount = 0

        do {
            val builder = HttpUrl.Builder()
                .scheme("https")
                .host("gmail.googleapis.com")
                .addPathSegments("gmail/v1/users/me/messages")
                .addQueryParameter("q", query)
                .addQueryParameter("maxResults", "100")
                .addQueryParameter("fields", "messages/id,nextPageToken")
            pageToken?.let { builder.addQueryParameter("pageToken", it) }

            val json = executeJson(builder.build(), accessToken)
            val messages = json.optJSONArray("messages") ?: JSONArray()
            for (i in 0 until messages.length()) {
                val id = messages.optJSONObject(i)?.optString("id").orEmpty()
                if (id.isNotBlank()) ids += id
            }
            pageToken = json.optString("nextPageToken").takeIf { it.isNotBlank() }
            pageCount++
        } while (pageToken != null && pageCount < 100)

        return ids.toList()
    }

    fun getMessage(accessToken: String, messageId: String): GmailMessage {
        val url = HttpUrl.Builder()
            .scheme("https")
            .host("gmail.googleapis.com")
            .addPathSegments("gmail/v1/users/me/messages")
            .addPathSegment(messageId)
            .addQueryParameter("format", "full")
            .build()

        val json = executeJson(url, accessToken)
        val payload = json.optJSONObject("payload") ?: JSONObject()
        val body = extractBestBody(payload)
        return GmailMessage(
            id = json.optString("id", messageId),
            internalDateMillis = json.optString("internalDate").toLongOrNull() ?: 0L,
            bodyText = body
        )
    }

    private fun executeJson(url: HttpUrl, accessToken: String): JSONObject {
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $accessToken")
            .header("Accept", "application/json")
            .build()

        client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw GmailApiException(response.code, body.take(800))
            }
            return JSONObject(body)
        }
    }

    private fun extractBestBody(payload: JSONObject): String {
        val plainParts = mutableListOf<String>()
        val htmlParts = mutableListOf<String>()
        collectBodies(payload, plainParts, htmlParts)

        val plain = plainParts.joinToString("\n").trim()
        if (plain.isNotBlank()) return plain

        val html = htmlParts.joinToString("\n").trim()
        if (html.isNotBlank()) {
            return Html.fromHtml(html, Html.FROM_HTML_MODE_LEGACY).toString()
        }
        return ""
    }

    private fun collectBodies(
        part: JSONObject,
        plainParts: MutableList<String>,
        htmlParts: MutableList<String>
    ) {
        val mimeType = part.optString("mimeType").lowercase()
        val encoded = part.optJSONObject("body")?.optString("data").orEmpty()
        if (encoded.isNotBlank()) {
            val decoded = decodeBase64Url(encoded)
            when {
                mimeType.startsWith("text/plain") -> plainParts += decoded
                mimeType.startsWith("text/html") -> htmlParts += decoded
                mimeType.isBlank() -> plainParts += decoded
            }
        }

        val parts = part.optJSONArray("parts") ?: return
        for (i in 0 until parts.length()) {
            parts.optJSONObject(i)?.let { collectBodies(it, plainParts, htmlParts) }
        }
    }

    private fun decodeBase64Url(value: String): String {
        return try {
            val bytes = Base64.decode(
                value,
                Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
            )
            String(bytes, StandardCharsets.UTF_8)
        } catch (e: IllegalArgumentException) {
            throw IOException("Gmail returned an invalid base64url message body", e)
        }
    }
}

class GmailApiException(
    val statusCode: Int,
    responseBody: String
) : IOException("Gmail API HTTP $statusCode: $responseBody")
