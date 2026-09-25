package com.datagrail.consent.demo.tester

import com.datagrail.consent.BuildConfig
import com.datagrail.consent.models.ConsentConfig
import com.datagrail.consent.models.ConsentException
import com.datagrail.consent.utils.ConfigValidator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.net.URLDecoder
import java.text.ParsePosition
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

enum class ArtifactMode { TEST, LIVE, OTHER }

data class ConfigUrlMetadata(
    val schemaVersion: String?,
    val artifactMode: ArtifactMode,
    val expiresAtMillis: Long?,
)

sealed class ConfigUrlLoadResult {
    data class Loaded(val config: ConsentConfig, val metadata: ConfigUrlMetadata) : ConfigUrlLoadResult()

    sealed class Failure : ConfigUrlLoadResult() {
        object InvalidUrl : Failure()

        data class Expired(val expiresAtMillis: Long?) : Failure()

        object UrlAltered : Failure()

        data class NotFoundOrDenied(val status: Int) : Failure()

        data class Unreachable(val detail: String) : Failure()

        data class Http(val status: Int, val code: String?) : Failure()

        data class UnsupportedSchema(val config: String, val sdk: String) : Failure()

        data class Parse(val detail: String) : Failure()

        data class Invalid(val detail: String) : Failure()
    }
}

data class HttpResult(val status: Int, val body: String)

/**
 * Fetches a pre-signed config URL (from the Mobile tab's "View config" link) and parses it with the
 * SDK's own model, bypassing `DataGrailConsent.initialize`: the SDK's `ConfigService` falls back to a
 * cached config on any failure and retries every error, which would mask an expired or altered URL.
 */
class ConfigUrlLoader(
    private val sdkSchemaVersion: String = BuildConfig.SCHEMA_VERSION,
    private val clock: () -> Long = System::currentTimeMillis,
    private val transport: suspend (URL) -> HttpResult = { url -> httpGet(url) },
) {
    // Mirrors the decode settings in library/.../network/ConfigService.kt; keep the two in sync.
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun load(raw: String): ConfigUrlLoadResult {
        val url = validatedUrl(raw) ?: return ConfigUrlLoadResult.Failure.InvalidUrl
        val metadata = metadata(url)

        val configSchema = metadata.schemaVersion
        if (configSchema != null && configSchema != sdkSchemaVersion) {
            return ConfigUrlLoadResult.Failure.UnsupportedSchema(config = configSchema, sdk = sdkSchemaVersion)
        }

        val response =
            try {
                transport(url)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                return ConfigUrlLoadResult.Failure.Unreachable(e.message ?: e.javaClass.simpleName)
            }

        if (response.status !in 200..299) {
            return classifyError(response, metadata)
        }
        return decode(response.body, metadata)
    }

    private fun classifyError(
        response: HttpResult,
        metadata: ConfigUrlMetadata,
    ): ConfigUrlLoadResult.Failure {
        val code = XML_CODE.find(response.body)?.groupValues?.get(1)
        val message = XML_MESSAGE.find(response.body)?.groupValues?.get(1).orEmpty()
        val expiresAt = metadata.expiresAtMillis
        val expiredByClock = response.status == 403 && expiresAt != null && expiresAt < clock()

        return when {
            (code == "AccessDenied" && message.contains("expired", ignoreCase = true)) ||
                code == "ExpiredToken" ||
                expiredByClock -> ConfigUrlLoadResult.Failure.Expired(expiresAt)
            code in URL_ALTERED_CODES -> ConfigUrlLoadResult.Failure.UrlAltered
            response.status == 404 || code == "NoSuchKey" || response.status == 403 ->
                ConfigUrlLoadResult.Failure.NotFoundOrDenied(response.status)
            else -> ConfigUrlLoadResult.Failure.Http(response.status, code)
        }
    }

    private fun decode(
        body: String,
        metadata: ConfigUrlMetadata,
    ): ConfigUrlLoadResult =
        try {
            val config = json.decodeFromString<ConsentConfig>(body)
            ConfigValidator.validate(config)
            ConfigUrlLoadResult.Loaded(config, metadata)
        } catch (e: ConsentException.ValidationError) {
            ConfigUrlLoadResult.Failure.Invalid(e.message.orEmpty())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ConfigUrlLoadResult.Failure.Parse(parseDetail(e, body))
        }

    private fun parseDetail(
        e: Exception,
        body: String,
    ): String {
        val reason = e.message?.lineSequence()?.firstOrNull()?.take(PREVIEW_CHARS) ?: e.javaClass.simpleName
        val preview = if (body.isBlank()) "(empty response body)" else body.take(PREVIEW_CHARS)
        return "$reason\nResponse began: $preview"
    }

    companion object {
        const val TIMEOUT_MS = 15_000
        private const val MAX_ERROR_BODY_BYTES = 4 * 1024
        private const val PREVIEW_CHARS = 200

        private val SCHEMA_IN_PATH = Regex("""config\.(v\d+)\.json$""")
        private val XML_CODE = Regex("<Code>([^<]*)</Code>")
        private val XML_MESSAGE = Regex("<Message>([^<]*)</Message>")
        private val URL_ALTERED_CODES = setOf("SignatureDoesNotMatch", "AuthorizationQueryParametersError", "InvalidToken")

        fun validatedUrl(raw: String): URL? {
            val uri =
                try {
                    URI(raw.trim())
                } catch (_: Exception) {
                    return null
                }
            if (!uri.scheme.equals("https", ignoreCase = true) || uri.host.isNullOrEmpty()) return null
            return try {
                uri.toURL()
            } catch (_: Exception) {
                null
            }
        }

        fun metadata(url: URL): ConfigUrlMetadata {
            val path = url.path.orEmpty()
            val mode =
                when {
                    path.startsWith("/mobile-test/") -> ArtifactMode.TEST
                    path.startsWith("/mobile-live/") -> ArtifactMode.LIVE
                    else -> ArtifactMode.OTHER
                }
            val params = queryParams(url.query)
            return ConfigUrlMetadata(
                schemaVersion = SCHEMA_IN_PATH.find(path)?.groupValues?.get(1),
                artifactMode = mode,
                expiresAtMillis = expiresAt(params["X-Amz-Date"], params["X-Amz-Expires"]),
            )
        }

        private fun queryParams(query: String?): Map<String, String> =
            query.orEmpty()
                .split('&')
                .filter { it.isNotEmpty() }
                .associate { pair ->
                    val name = pair.substringBefore('=')
                    val value = pair.substringAfter('=', "")
                    name to URLDecoder.decode(value, "UTF-8")
                }

        // SimpleDateFormat rather than java.time: minSdk is 23 and core library desugaring is off.
        private fun expiresAt(
            amzDate: String?,
            amzExpires: String?,
        ): Long? {
            val seconds = amzExpires?.toLongOrNull()?.takeIf { it >= 0 } ?: return null
            amzDate ?: return null
            val format =
                SimpleDateFormat("yyyyMMdd'T'HHmmss'Z'", Locale.US).apply {
                    timeZone = TimeZone.getTimeZone("UTC")
                    isLenient = false
                }
            val position = ParsePosition(0)
            val signedAt = format.parse(amzDate, position)
            if (signedAt == null || position.index != amzDate.length) return null
            return signedAt.time + seconds * 1000
        }

        private suspend fun httpGet(url: URL): HttpResult =
            withContext(Dispatchers.IO) {
                val connection = url.openConnection() as HttpURLConnection
                try {
                    connection.requestMethod = "GET"
                    connection.useCaches = false
                    connection.connectTimeout = TIMEOUT_MS
                    connection.readTimeout = TIMEOUT_MS
                    val status = connection.responseCode
                    val body =
                        if (status in 200..299) {
                            connection.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
                        } else {
                            connection.errorStream?.use { it.readCapped(MAX_ERROR_BODY_BYTES) }.orEmpty()
                        }
                    HttpResult(status, body)
                } finally {
                    connection.disconnect()
                }
            }

        @Throws(IOException::class)
        private fun InputStream.readCapped(maxBytes: Int): String {
            val buffer = ByteArray(maxBytes)
            var total = 0
            while (total < maxBytes) {
                val read = read(buffer, total, maxBytes - total)
                if (read < 0) break
                total += read
            }
            return String(buffer, 0, total, Charsets.UTF_8)
        }
    }
}
