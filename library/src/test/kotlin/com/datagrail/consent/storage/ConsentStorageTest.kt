package com.datagrail.consent.storage

import android.content.Context
import android.content.SharedPreferences
import com.datagrail.consent.models.CategoryConsent
import com.datagrail.consent.models.ConsentPreferences
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.Mockito
import org.mockito.junit.MockitoJUnitRunner
import org.mockito.kotlin.any
import org.mockito.kotlin.whenever

@RunWith(MockitoJUnitRunner::class)
class ConsentStorageTest {
    @Mock
    private lateinit var mockContext: Context

    @Mock
    private lateinit var mockSharedPreferences: SharedPreferences

    @Mock
    private lateinit var mockEditor: SharedPreferences.Editor

    private val json = Json { ignoreUnknownKeys = true }

    private lateinit var storage: ConsentStorage

    @Before
    fun setUp() {
        whenever(mockContext.getSharedPreferences(any(), any())).thenReturn(mockSharedPreferences)
        whenever(mockSharedPreferences.edit()).thenReturn(mockEditor)
        whenever(mockEditor.putString(any(), any())).thenReturn(mockEditor)
        whenever(mockEditor.remove(any())).thenReturn(mockEditor)
        whenever(mockEditor.apply()).then { }

        storage = ConsentStorage(mockContext)
    }

    @After
    fun tearDown() {
        Mockito.reset(mockContext, mockSharedPreferences, mockEditor)
    }

    @Test
    fun testSavePreferences() {
        val prefs =
            ConsentPreferences(
                isCustomised = true,
                cookieOptions =
                    listOf(
                        CategoryConsent("category_marketing", true),
                    ),
            )

        storage.savePreferences(prefs)

        // Verify putString was called with the correct key
        Mockito.verify(mockEditor).putString(
            Mockito.eq("datagrail_consent_preferences"),
            any(),
        )
        Mockito.verify(mockEditor).apply()
    }

    @Test
    fun testLoadPreferences() {
        val prefs =
            ConsentPreferences(
                isCustomised = true,
                cookieOptions =
                    listOf(
                        CategoryConsent("category_marketing", true),
                    ),
            )
        val jsonString = json.encodeToString(prefs)

        whenever(mockSharedPreferences.getString("datagrail_consent_preferences", null))
            .thenReturn(jsonString)

        val loaded = storage.loadPreferences()
        assertNotNull(loaded)
        assertEquals(prefs, loaded)
    }

    @Test
    fun testLoadPreferencesWhenNoneExist() {
        whenever(mockSharedPreferences.getString("datagrail_consent_preferences", null))
            .thenReturn(null)

        val loaded = storage.loadPreferences()
        assertNull(loaded)
    }

    @Test
    fun testGetOrCreateUniqueId() {
        val testId = "test-uuid-12345"

        // First call returns null, second call returns the ID
        whenever(mockSharedPreferences.getString("datagrail_consent_id", null))
            .thenReturn(null)
            .thenReturn(testId)

        val id1 = storage.getOrCreateUniqueId()
        assertTrue(id1.isNotEmpty())

        // Verify ID was saved
        Mockito.verify(mockEditor).putString(
            Mockito.eq("datagrail_consent_id"),
            any(),
        )
    }

    @Test
    fun testSaveConfigVersion() {
        val version = "1.2.3"
        storage.saveConfigVersion(version)

        Mockito.verify(mockEditor).putString("datagrail_consent_version", version)
        Mockito.verify(mockEditor).apply()
    }

    @Test
    fun testLoadConfigVersion() {
        val version = "1.2.3"
        whenever(mockSharedPreferences.getString("datagrail_consent_version", null))
            .thenReturn(version)

        val loaded = storage.loadConfigVersion()
        assertEquals(version, loaded)
    }

    @Test
    fun testClearAll() {
        whenever(mockEditor.clear()).thenReturn(mockEditor)

        storage.clearAll()

        // Verify clear was called
        Mockito.verify(mockEditor).clear()
        Mockito.verify(mockEditor).apply()
    }
}
