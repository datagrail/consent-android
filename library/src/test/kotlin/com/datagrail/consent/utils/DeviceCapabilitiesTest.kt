package com.datagrail.consent.utils

import android.content.Context
import android.content.pm.PackageManager
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.mockito.Mock
import org.mockito.Mockito.`when`
import org.mockito.MockitoAnnotations

/**
 * Tests for DeviceCapabilities TV detection
 */
class DeviceCapabilitiesTest {
    @Mock
    private lateinit var mockContext: Context

    @Mock
    private lateinit var mockPackageManager: PackageManager

    @Before
    fun setUp() {
        MockitoAnnotations.openMocks(this)
        `when`(mockContext.packageManager).thenReturn(mockPackageManager)
    }

    @Test
    fun `isTv returns true when FEATURE_LEANBACK is present`() {
        // Given
        `when`(mockPackageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)).thenReturn(true)

        // When
        val result = DeviceCapabilities.isTv(mockContext)

        // Then
        assertTrue("Should detect TV when Leanback feature is present", result)
    }

    @Test
    fun `isTv returns false when FEATURE_LEANBACK is not present`() {
        // Given
        `when`(mockPackageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)).thenReturn(false)

        // When
        val result = DeviceCapabilities.isTv(mockContext)

        // Then
        assertFalse("Should not detect TV when Leanback feature is absent", result)
    }
}
