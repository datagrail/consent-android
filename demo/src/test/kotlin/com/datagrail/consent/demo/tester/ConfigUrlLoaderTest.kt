package com.datagrail.consent.demo.tester

import com.datagrail.consent.demo.tester.ConfigUrlLoadResult.Failure
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.URL

class ConfigUrlLoaderTest {
    // 2026-09-24T10:15:00Z signing time + 900 s = 2026-09-24T10:30:00Z.
    private val signedAt = 1_790_244_900_000L
    private val expiresAt = signedAt + 900_000L
    private val testUrl =
        "https://bucket.s3.amazonaws.com/mobile-test/uuid/42/config.v1.json" +
            "?X-Amz-Algorithm=AWS4-HMAC-SHA256&X-Amz-Date=20260924T101500Z&X-Amz-Expires=900" +
            "&X-Amz-SignedHeaders=host&X-Amz-Signature=abc123"

    private val fixture: String =
        requireNotNull(javaClass.classLoader?.getResource("test-config.json")).readText()

    private var transportCalls = 0

    private fun loader(
        response: HttpResult? = null,
        now: Long = signedAt,
        error: Exception? = null,
    ) = ConfigUrlLoader(
        sdkSchemaVersion = "v1",
        clock = { now },
        transport = {
            transportCalls++
            error?.let { throw it }
            requireNotNull(response)
        },
    )

    private fun s3Error(
        code: String,
        message: String,
    ) = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<Error><Code>$code</Code><Message>$message</Message>" +
        "<RequestId>X</RequestId></Error>"

    // validatedUrl

    @Test
    fun `validatedUrl trims whitespace and newlines`() {
        val url = ConfigUrlLoader.validatedUrl("  https://example.com/mobile-test/u/1/config.v1.json\n")
        assertEquals("https://example.com/mobile-test/u/1/config.v1.json", url.toString())
    }

    @Test
    fun `validatedUrl rejects non-https, hostless, and garbage input`() {
        listOf("http://example.com/config.json", "not a url", "https://", "", "   ").forEach {
            assertNull("expected $it to be rejected", ConfigUrlLoader.validatedUrl(it))
        }
    }

    // metadata

    @Test
    fun `metadata reads schema, test mode, and UTC expiry from a presigned test URL`() {
        val metadata = ConfigUrlLoader.metadata(URL(testUrl))
        assertEquals(ConfigUrlMetadata("v1", ArtifactMode.TEST, expiresAt), metadata)
    }

    @Test
    fun `metadata recognizes live artifacts`() {
        val metadata = ConfigUrlLoader.metadata(URL("https://b.s3.amazonaws.com/mobile-live/u/7/default/config.v1.json"))
        assertEquals(ArtifactMode.LIVE, metadata.artifactMode)
    }

    @Test
    fun `metadata treats legacy config json as unversioned and other`() {
        val metadata = ConfigUrlLoader.metadata(URL("https://api.example.com/consent/a/b/config.json"))
        assertNull(metadata.schemaVersion)
        assertEquals(ArtifactMode.OTHER, metadata.artifactMode)
    }

    @Test
    fun `metadata has no expiry when X-Amz-Date is missing or malformed`() {
        listOf(
            "https://b.example.com/mobile-test/u/1/config.v1.json?X-Amz-Expires=900",
            "https://b.example.com/mobile-test/u/1/config.v1.json?X-Amz-Date=garbage&X-Amz-Expires=900",
            "https://b.example.com/mobile-test/u/1/config.v1.json?X-Amz-Date=20260924T101500Zjunk&X-Amz-Expires=900",
            "https://b.example.com/mobile-test/u/1/config.v1.json?X-Amz-Date=20260924T101500Z&X-Amz-Expires=soon",
        ).forEach {
            assertNull("expected no expiry for $it", ConfigUrlLoader.metadata(URL(it)).expiresAtMillis)
        }
    }

    // load

    @Test
    fun `invalid URL fails without calling the transport`() =
        runTest {
            assertEquals(Failure.InvalidUrl, loader(HttpResult(200, fixture)).load("not a url"))
            assertEquals(0, transportCalls)
        }

    @Test
    fun `a schema this build cannot render is refused before fetching`() =
        runTest {
            val result = loader(HttpResult(200, fixture)).load(testUrl.replace("config.v1.json", "config.v2.json"))
            assertEquals(Failure.UnsupportedSchema(config = "v2", sdk = "v1"), result)
            assertEquals(0, transportCalls)
        }

    @Test
    fun `200 with a valid config loads it with the SDK model`() =
        runTest {
            val result = loader(HttpResult(200, fixture)).load(testUrl)
            assertTrue(result is ConfigUrlLoadResult.Loaded)
            result as ConfigUrlLoadResult.Loaded
            assertEquals("cc959465-747d-4c81-8bc1-5dcd34dc3756", result.config.version)
            assertEquals(ArtifactMode.TEST, result.metadata.artifactMode)
            assertEquals(1, transportCalls)
        }

    @Test
    fun `403 AccessDenied with an expired message is Expired`() =
        runTest {
            val result = loader(HttpResult(403, s3Error("AccessDenied", "Request has expired"))).load(testUrl)
            assertEquals(Failure.Expired(expiresAt), result)
        }

    @Test
    fun `403 AccessDenied after the local expiry is Expired`() =
        runTest {
            val result = loader(HttpResult(403, s3Error("AccessDenied", "Access Denied")), now = expiresAt + 1).load(testUrl)
            assertEquals(Failure.Expired(expiresAt), result)
        }

    @Test
    fun `403 AccessDenied before the local expiry is NotFoundOrDenied`() =
        runTest {
            val result = loader(HttpResult(403, s3Error("AccessDenied", "Access Denied")), now = expiresAt - 1).load(testUrl)
            assertEquals(Failure.NotFoundOrDenied(403), result)
        }

    @Test
    fun `400 ExpiredToken is Expired`() =
        runTest {
            val result = loader(HttpResult(400, s3Error("ExpiredToken", "The provided token has expired."))).load(testUrl)
            assertEquals(Failure.Expired(expiresAt), result)
        }

    @Test
    fun `403 SignatureDoesNotMatch is UrlAltered`() =
        runTest {
            val result = loader(HttpResult(403, s3Error("SignatureDoesNotMatch", "The signature does not match"))).load(testUrl)
            assertEquals(Failure.UrlAltered, result)
        }

    @Test
    fun `404 NoSuchKey is NotFoundOrDenied`() =
        runTest {
            val result = loader(HttpResult(404, s3Error("NoSuchKey", "The specified key does not exist."))).load(testUrl)
            assertEquals(Failure.NotFoundOrDenied(404), result)
        }

    @Test
    fun `500 is an unexpected Http failure`() =
        runTest {
            assertEquals(Failure.Http(500, null), loader(HttpResult(500, "")).load(testUrl))
        }

    @Test
    fun `IOException from the transport is Unreachable`() =
        runTest {
            val result = loader(error = IOException("Unable to resolve host")).load(testUrl)
            assertEquals(Failure.Unreachable("Unable to resolve host"), result)
        }

    @Test
    fun `load never throws when the transport throws a RuntimeException`() =
        runTest {
            assertTrue(loader(error = IllegalStateException("boom")).load(testUrl) is Failure)
        }

    @Test
    fun `200 with JSON that is not a config is Parse with a body preview`() =
        runTest {
            val result = loader(HttpResult(200, """{"foo":1}""")).load(testUrl)
            assertTrue(result is Failure.Parse)
            assertTrue((result as Failure.Parse).detail.contains("""{"foo":1}"""))
        }

    @Test
    fun `200 with an empty body is Parse`() =
        runTest {
            assertTrue(loader(HttpResult(200, "")).load(testUrl) is Failure.Parse)
        }

    @Test
    fun `a v2-shaped body behind an unversioned URL is Parse, not a crash`() =
        runTest {
            val v2Body = fixture.replace("\"p\":", "\"publishedAtMs\":")
            val result = loader(HttpResult(200, v2Body)).load("https://api.example.com/consent/a/b/config.json")
            assertTrue(result is Failure.Parse)
        }

    @Test
    fun `a config that fails ConfigValidator is Invalid`() =
        runTest {
            val invalid = fixture.replace("\"first_layer_id\": \"26259ccb-e5e0-4305-b696-fa2b7413c239\"", "\"first_layer_id\": \"missing\"")
            val result = loader(HttpResult(200, invalid)).load(testUrl)
            assertTrue(result is Failure.Invalid)
            assertTrue((result as Failure.Invalid).detail.contains("firstLayerId"))
        }
}
