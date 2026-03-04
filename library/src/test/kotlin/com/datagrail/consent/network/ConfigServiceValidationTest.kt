package com.datagrail.consent.network

import com.datagrail.consent.models.*
import com.datagrail.consent.storage.ConsentStorage
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.mockito.Mock
import org.mockito.Mockito
import org.mockito.MockitoAnnotations
import org.mockito.kotlin.*

/**
 * Tests for ConfigService validation wiring:
 * - ConfigValidator.validate() is called after deserialization
 * - Validation failure falls back to cache
 * - Validation failure with no cache propagates error
 */
class ConfigServiceValidationTest {
    @Mock
    private lateinit var mockNetworkClient: NetworkClient

    @Mock
    private lateinit var mockStorage: ConsentStorage

    private lateinit var configService: ConfigService

    private val json = Json { ignoreUnknownKeys = true }

    private lateinit var closeable: AutoCloseable

    @Before
    fun setUp() {
        closeable = MockitoAnnotations.openMocks(this)
        configService = ConfigService(mockNetworkClient, mockStorage)
    }

    @After
    fun tearDown() {
        Mockito.reset(mockNetworkClient, mockStorage)
        closeable.close()
    }

    // MARK: - Valid Config

    @Test
    fun `fetchConfig with valid config caches and returns it`() =
        runTest {
            val validConfig = ConsentServiceSecurityTest.createTestConfig()
            val configJson = json.encodeToString(validConfig)

            whenever(mockNetworkClient.request(any(), any(), anyOrNull(), anyOrNull()))
                .thenReturn(configJson)

            val result = configService.fetchConfig("https://example.com/config.json")

            assertEquals(validConfig.version, result.version)
            verify(mockStorage).saveConfigCache(any())
        }

    // MARK: - Validation Failure with Cache

    @Test
    fun `fetchConfig with invalid config falls back to cache`() =
        runTest {
            // Config with empty version — fails ConfigValidator
            val invalidConfig = ConsentServiceSecurityTest.createTestConfig().copy(version = "")
            val configJson = json.encodeToString(invalidConfig)
            val cachedConfig = ConsentServiceSecurityTest.createTestConfig().copy(version = "cached-v1")

            whenever(mockNetworkClient.request(any(), any(), anyOrNull(), anyOrNull()))
                .thenReturn(configJson)
            whenever(mockStorage.loadConfigCache()).thenReturn(cachedConfig)

            val result = configService.fetchConfig("https://example.com/config.json")

            assertEquals("cached-v1", result.version)
            // Should NOT cache the invalid config
            verify(mockStorage, never()).saveConfigCache(any())
        }

    @Test
    fun `fetchConfig with invalid consentMode falls back to cache`() =
        runTest {
            val invalidConfig = ConsentServiceSecurityTest.createTestConfig().copy(consentMode = "invalid")
            val configJson = json.encodeToString(invalidConfig)
            val cachedConfig = ConsentServiceSecurityTest.createTestConfig()

            whenever(mockNetworkClient.request(any(), any(), anyOrNull(), anyOrNull()))
                .thenReturn(configJson)
            whenever(mockStorage.loadConfigCache()).thenReturn(cachedConfig)

            val result = configService.fetchConfig("https://example.com/config.json")

            assertEquals(cachedConfig.version, result.version)
        }

    // MARK: - Validation Failure without Cache

    @Test
    fun `fetchConfig with invalid config and no cache throws ValidationError`() =
        runTest {
            val invalidConfig = ConsentServiceSecurityTest.createTestConfig().copy(version = "")
            val configJson = json.encodeToString(invalidConfig)

            whenever(mockNetworkClient.request(any(), any(), anyOrNull(), anyOrNull()))
                .thenReturn(configJson)
            whenever(mockStorage.loadConfigCache()).thenReturn(null)

            try {
                configService.fetchConfig("https://example.com/config.json")
                fail("Expected ValidationError")
            } catch (e: ConsentException.ValidationError) {
                assertTrue(e.message!!.contains("version"))
            }
        }

    // MARK: - Network Failure

    @Test
    fun `fetchConfig with network failure falls back to cache`() =
        runTest {
            val cachedConfig = ConsentServiceSecurityTest.createTestConfig()

            whenever(mockNetworkClient.request(any(), any(), anyOrNull(), anyOrNull()))
                .thenAnswer { throw ConsentException.NetworkError("timeout") }
            whenever(mockStorage.loadConfigCache()).thenReturn(cachedConfig)

            val result = configService.fetchConfig("https://example.com/config.json")

            assertEquals(cachedConfig.version, result.version)
        }

    @Test
    fun `fetchConfig with network failure and no cache throws NetworkError`() =
        runTest {
            whenever(mockNetworkClient.request(any(), any(), anyOrNull(), anyOrNull()))
                .thenAnswer { throw ConsentException.NetworkError("timeout") }
            whenever(mockStorage.loadConfigCache()).thenReturn(null)

            try {
                configService.fetchConfig("https://example.com/config.json")
                fail("Expected NetworkError")
            } catch (e: ConsentException.NetworkError) {
                assertTrue(e.message!!.contains("timeout"))
            }
        }
}
