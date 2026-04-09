package com.datagrail.consent.utils

import android.util.Log
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.mockito.MockedStatic
import org.mockito.Mockito.mockStatic
import org.mockito.Mockito.times

/**
 * Tests for ConsentLogger log level gating
 */
class ConsentLoggerTest {
    private lateinit var mockLog: MockedStatic<Log>

    @Before
    fun setUp() {
        mockLog = mockStatic(Log::class.java)
        mockLog.`when`<Int> { Log.d(org.mockito.kotlin.any(), org.mockito.kotlin.any()) }.thenReturn(0)
        mockLog.`when`<Int> { Log.e(org.mockito.kotlin.any(), org.mockito.kotlin.any()) }.thenReturn(0)
        mockLog.`when`<Int> { Log.w(org.mockito.kotlin.any<String>(), org.mockito.kotlin.any<String>()) }.thenReturn(0)
        mockLog.`when`<Int> { Log.i(org.mockito.kotlin.any<String>(), org.mockito.kotlin.any<String>()) }.thenReturn(0)
        ConsentLogger.level = LogLevel.NONE
    }

    @After
    fun tearDown() {
        ConsentLogger.level = LogLevel.NONE
        mockLog.close()
    }

    // MARK: - Default Level

    @Test
    fun `default log level is NONE`() {
        assertEquals(LogLevel.NONE, ConsentLogger.level)
    }

    // MARK: - NONE Level

    @Test
    fun `d() at NONE level does not call Log_d`() {
        ConsentLogger.level = LogLevel.NONE

        ConsentLogger.d("test message")

        mockLog.verify({ Log.d(org.mockito.kotlin.any(), org.mockito.kotlin.any()) }, times(0))
    }

    @Test
    fun `e() at NONE level does not call Log_e`() {
        ConsentLogger.level = LogLevel.NONE

        ConsentLogger.e("test error")

        mockLog.verify({ Log.e(org.mockito.kotlin.any(), org.mockito.kotlin.any()) }, times(0))
    }

    // MARK: - ERROR Level

    @Test
    fun `d() at ERROR level does not call Log_d`() {
        ConsentLogger.level = LogLevel.ERROR

        ConsentLogger.d("test message")

        mockLog.verify({ Log.d(org.mockito.kotlin.any(), org.mockito.kotlin.any()) }, times(0))
    }

    @Test
    fun `e() at ERROR level calls Log_e`() {
        ConsentLogger.level = LogLevel.ERROR

        ConsentLogger.e("test error")

        mockLog.verify({ Log.e("DataGrailConsent", "test error") }, times(1))
    }

    // MARK: - WARN Level

    @Test
    fun `w() at WARN level calls Log_w`() {
        ConsentLogger.level = LogLevel.WARN

        ConsentLogger.w("test warning")

        mockLog.verify({ Log.w("DataGrailConsent", "test warning") }, times(1))
    }

    @Test
    fun `e() at WARN level calls Log_e`() {
        ConsentLogger.level = LogLevel.WARN

        ConsentLogger.e("test error")

        mockLog.verify({ Log.e("DataGrailConsent", "test error") }, times(1))
    }

    @Test
    fun `i() at WARN level does not call Log_i`() {
        ConsentLogger.level = LogLevel.WARN

        ConsentLogger.i("test info")

        mockLog.verify({ Log.i(org.mockito.kotlin.any(), org.mockito.kotlin.any()) }, times(0))
    }

    @Test
    fun `d() at WARN level does not call Log_d`() {
        ConsentLogger.level = LogLevel.WARN

        ConsentLogger.d("test message")

        mockLog.verify({ Log.d(org.mockito.kotlin.any(), org.mockito.kotlin.any()) }, times(0))
    }

    // MARK: - INFO Level

    @Test
    fun `i() at INFO level calls Log_i`() {
        ConsentLogger.level = LogLevel.INFO

        ConsentLogger.i("test info")

        mockLog.verify({ Log.i("DataGrailConsent", "test info") }, times(1))
    }

    @Test
    fun `w() at INFO level calls Log_w`() {
        ConsentLogger.level = LogLevel.INFO

        ConsentLogger.w("test warning")

        mockLog.verify({ Log.w("DataGrailConsent", "test warning") }, times(1))
    }

    @Test
    fun `e() at INFO level calls Log_e`() {
        ConsentLogger.level = LogLevel.INFO

        ConsentLogger.e("test error")

        mockLog.verify({ Log.e("DataGrailConsent", "test error") }, times(1))
    }

    @Test
    fun `d() at INFO level does not call Log_d`() {
        ConsentLogger.level = LogLevel.INFO

        ConsentLogger.d("test message")

        mockLog.verify({ Log.d(org.mockito.kotlin.any(), org.mockito.kotlin.any()) }, times(0))
    }

    // MARK: - DEBUG Level

    @Test
    fun `d() at DEBUG level calls Log_d`() {
        ConsentLogger.level = LogLevel.DEBUG

        ConsentLogger.d("test message")

        mockLog.verify({ Log.d("DataGrailConsent", "test message") }, times(1))
    }

    @Test
    fun `e() at DEBUG level calls Log_e`() {
        ConsentLogger.level = LogLevel.DEBUG

        ConsentLogger.e("test error")

        mockLog.verify({ Log.e("DataGrailConsent", "test error") }, times(1))
    }

    // MARK: - Level Transitions

    @Test
    fun `changing level from NONE to DEBUG enables logging`() {
        ConsentLogger.level = LogLevel.NONE
        ConsentLogger.d("should not log")
        mockLog.verify({ Log.d(org.mockito.kotlin.any(), org.mockito.kotlin.any()) }, times(0))

        ConsentLogger.level = LogLevel.DEBUG
        ConsentLogger.d("should log")
        mockLog.verify({ Log.d("DataGrailConsent", "should log") }, times(1))
    }

    @Test
    fun `changing level from DEBUG to NONE disables logging`() {
        ConsentLogger.level = LogLevel.DEBUG
        ConsentLogger.d("should log")
        mockLog.verify({ Log.d("DataGrailConsent", "should log") }, times(1))

        ConsentLogger.level = LogLevel.NONE
        ConsentLogger.d("should not log")
        // Still only 1 call total
        mockLog.verify({ Log.d(org.mockito.kotlin.any(), org.mockito.kotlin.any()) }, times(1))
    }
}
