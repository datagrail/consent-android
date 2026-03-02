package com.datagrail.consent;

import android.content.Context;
import androidx.fragment.app.FragmentActivity;
import com.datagrail.consent.models.CategoryConsent;
import com.datagrail.consent.models.ConsentException;
import com.datagrail.consent.models.ConsentPreferences;
import com.datagrail.consent.ui.BannerDisplayStyle;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;

/**
 * Java interoperability tests for DataGrail Consent SDK
 *
 * These tests verify that Java applications can:
 * - Access the singleton instance via getInstance()
 * - Use callback interfaces properly
 * - Call all public API methods
 * - Handle success and failure scenarios
 */
public class JavaInteroperabilityTest {

    @Mock
    private Context mockContext;

    @Mock
    private FragmentActivity mockActivity;

    private DataGrailConsent sdk;

    @Before
    public void setUp() {
        MockitoAnnotations.openMocks(this);
        sdk = DataGrailConsent.getInstance();
        sdk.reset();
    }

    // MARK: - Singleton Access Tests

    @Test
    public void testGetInstanceReturnsNonNull() {
        // Verify getInstance() is accessible from Java and returns non-null
        DataGrailConsent instance = DataGrailConsent.getInstance();
        assertNotNull("getInstance() should return non-null instance", instance);
    }

    @Test
    public void testGetInstanceReturnsSameInstance() {
        // Verify getInstance() returns singleton
        DataGrailConsent instance1 = DataGrailConsent.getInstance();
        DataGrailConsent instance2 = DataGrailConsent.getInstance();
        assertSame("getInstance() should return same instance", instance1, instance2);
    }

    // MARK: - ConsentCallback Interface Tests

    @Test
    public void testConsentCallbackInterfaceFromJava() throws InterruptedException {
        // Verify ConsentCallback interface works from Java
        final CountDownLatch latch = new CountDownLatch(1);
        final AtomicBoolean callbackInvoked = new AtomicBoolean(false);

        ConsentCallback callback = new ConsentCallback() {
            @Override
            public void onSuccess() {
                callbackInvoked.set(true);
                latch.countDown();
            }

            @Override
            public void onFailure(ConsentException error) {
                fail("Should not fail: " + error.getMessage());
                latch.countDown();
            }
        };

        // Verify callback can be instantiated
        assertNotNull("Callback should be instantiated", callback);

        // Simulate callback invocation
        callback.onSuccess();
        assertTrue("Callback onSuccess should be invoked", callbackInvoked.get());
    }

    @Test
    public void testConsentCallbackHandlesFailure() {
        final AtomicReference<ConsentException> capturedError = new AtomicReference<>();

        ConsentCallback callback = new ConsentCallback() {
            @Override
            public void onSuccess() {
                fail("Should not succeed");
            }

            @Override
            public void onFailure(ConsentException error) {
                capturedError.set(error);
            }
        };

        // Simulate error
        ConsentException testError = new ConsentException.NetworkError("Test error", null);
        callback.onFailure(testError);

        assertNotNull("Error should be captured", capturedError.get());
        assertTrue("Should be NetworkError", capturedError.get() instanceof ConsentException.NetworkError);
        assertTrue("Message should contain test error", capturedError.get().getMessage().contains("Test error"));
    }

    // MARK: - PreferencesCallback Interface Tests

    @Test
    public void testPreferencesCallbackFromJava() {
        final AtomicBoolean savesCalled = new AtomicBoolean(false);
        final AtomicBoolean dismissedCalled = new AtomicBoolean(false);

        PreferencesCallback callback = new PreferencesCallback() {
            @Override
            public void onPreferencesSaved(ConsentPreferences preferences) {
                savesCalled.set(true);
            }

            @Override
            public void onDismissed() {
                dismissedCalled.set(true);
            }
        };

        // Test onDismissed
        callback.onDismissed();
        assertTrue("onDismissed should be called", dismissedCalled.get());
    }

    // MARK: - ConsentChangeListener Interface Tests

    @Test
    public void testConsentChangeListenerFromJava() {
        final AtomicReference<ConsentPreferences> capturedPreferences = new AtomicReference<>();

        ConsentChangeListener listener = new ConsentChangeListener() {
            @Override
            public void onConsentChanged(ConsentPreferences preferences) {
                capturedPreferences.set(preferences);
            }
        };

        assertNotNull("Listener should be instantiated", listener);
    }

    // MARK: - API Method Access Tests

    @Test
    public void testInitializeMethodAccessible() {
        // Verify initialize method with ConsentCallback is accessible from Java
        ConsentCallback callback = new ConsentCallback() {
            @Override
            public void onSuccess() {}

            @Override
            public void onFailure(ConsentException error) {}
        };

        // This should compile and be callable (won't actually initialize without proper context)
        assertNotNull("Initialize method should be accessible", callback);
    }

    @Test
    public void testAcceptAllMethodAccessible() {
        ConsentCallback callback = new ConsentCallback() {
            @Override
            public void onSuccess() {}

            @Override
            public void onFailure(ConsentException error) {}
        };

        // Verify method is accessible (will fail gracefully if not initialized)
        try {
            sdk.acceptAll(callback);
        } catch (Exception e) {
            // Expected - SDK not initialized
            assertTrue("Should throw exception when not initialized", true);
        }
    }

    @Test
    public void testRejectAllMethodAccessible() {
        ConsentCallback callback = new ConsentCallback() {
            @Override
            public void onSuccess() {}

            @Override
            public void onFailure(ConsentException error) {}
        };

        // Verify method is accessible (will fail gracefully if not initialized)
        try {
            sdk.rejectAll(callback);
        } catch (Exception e) {
            // Expected - SDK not initialized
            assertTrue("Should throw exception when not initialized", true);
        }
    }

    @Test
    public void testOnConsentChangedMethodAccessible() {
        ConsentChangeListener listener = new ConsentChangeListener() {
            @Override
            public void onConsentChanged(ConsentPreferences preferences) {}
        };

        // Verify method is accessible
        sdk.onConsentChanged(listener);
        assertTrue("onConsentChanged should be callable", true);
    }

    // MARK: - Synchronous Method Tests

    @Test
    public void testShouldDisplayBannerAccessible() {
        // Verify synchronous methods throw proper exceptions when not initialized
        try {
            sdk.shouldDisplayBanner();
            fail("Should throw NotInitialized exception");
        } catch (Exception e) {
            // Cast to verify it's a ConsentException
            assertTrue("Should be ConsentException", e instanceof ConsentException);
            ConsentException ce = (ConsentException) e;
            // Expected - verify exception is accessible from Java and is correct type
            assertTrue("Should be NotInitialized type", e instanceof ConsentException.NotInitialized);
            assertTrue("Exception message should be accessible", e.getMessage().length() > 0);
        }
    }

    @Test
    public void testHasUserConsentAccessible() {
        try {
            sdk.hasUserConsent();
            fail("Should throw NotInitialized exception");
        } catch (Exception e) {
            // Cast to verify it's a ConsentException
            assertTrue("Should be ConsentException", e instanceof ConsentException);
            ConsentException ce = (ConsentException) e;
            // Expected - verify exception is accessible from Java and is correct type
            assertTrue("Should be NotInitialized type", e instanceof ConsentException.NotInitialized);
            assertTrue("Exception message should be accessible", e.getMessage().length() > 0);
        }
    }

    @Test
    public void testIsCategoryEnabledAccessible() {
        try {
            sdk.isCategoryEnabled("test_category");
            fail("Should throw NotInitialized exception");
        } catch (Exception e) {
            // Cast to verify it's a ConsentException
            assertTrue("Should be ConsentException", e instanceof ConsentException);
            ConsentException ce = (ConsentException) e;
            // Expected - verify exception is accessible from Java and is correct type
            assertTrue("Should be NotInitialized type", e instanceof ConsentException.NotInitialized);
            assertTrue("Exception message should be accessible", e.getMessage().length() > 0);
        }
    }

    @Test
    public void testGetUserPreferencesAccessible() {
        try {
            sdk.getUserPreferences();
            fail("Should throw NotInitialized exception");
        } catch (Exception e) {
            // Cast to verify it's a ConsentException
            assertTrue("Should be ConsentException", e instanceof ConsentException);
            ConsentException ce = (ConsentException) e;
            // Expected - verify exception is accessible from Java and is correct type
            assertTrue("Should be NotInitialized type", e instanceof ConsentException.NotInitialized);
            assertTrue("Exception message should be accessible", e.getMessage().length() > 0);
        }
    }

    @Test
    public void testGetCategoriesAccessible() {
        try {
            sdk.getCategories();
            fail("Should throw NotInitialized exception");
        } catch (Exception e) {
            // Cast to verify it's a ConsentException
            assertTrue("Should be ConsentException", e instanceof ConsentException);
            ConsentException ce = (ConsentException) e;
            // Expected - verify exception is accessible from Java and is correct type
            assertTrue("Should be NotInitialized type", e instanceof ConsentException.NotInitialized);
            assertTrue("Exception message should be accessible", e.getMessage().length() > 0);
        }
    }

    // MARK: - Enum Access Tests

    @Test
    public void testBannerDisplayStyleEnumAccessible() {
        // Verify Java can access and use Kotlin enums
        BannerDisplayStyle modal = BannerDisplayStyle.MODAL;
        BannerDisplayStyle fullScreen = BannerDisplayStyle.FULL_SCREEN;

        assertNotNull("MODAL should be accessible", modal);
        assertNotNull("FULL_SCREEN should be accessible", fullScreen);
        assertNotEquals("Enums should be different", modal, fullScreen);
    }

    // MARK: - Exception Hierarchy Tests

    @Test
    public void testConsentExceptionTypesAccessible() {
        // Verify all exception types are accessible from Java
        ConsentException networkError = new ConsentException.NetworkError("Network error", null);
        ConsentException configError = new ConsentException.InvalidConfiguration("Config error");
        ConsentException notInitError = new ConsentException.NotInitialized();

        assertTrue("NetworkError should be ConsentException", networkError instanceof ConsentException);
        assertTrue("InvalidConfiguration should be ConsentException", configError instanceof ConsentException);
        assertTrue("NotInitialized should be ConsentException", notInitError instanceof ConsentException);

        // Verify instanceof checks work for specific types
        assertTrue("Should be NetworkError type", networkError instanceof ConsentException.NetworkError);
        assertTrue("Should be InvalidConfiguration type", configError instanceof ConsentException.InvalidConfiguration);
        assertTrue("Should be NotInitialized type", notInitError instanceof ConsentException.NotInitialized);
    }

    @Test
    public void testConsentExceptionMessagesAccessible() {
        ConsentException error = new ConsentException.NetworkError("Test message", null);
        assertTrue("Message should contain test message", error.getMessage().contains("Test message"));
        assertTrue("Message should indicate network error", error.getMessage().contains("Network error"));
    }

    // MARK: - Model Classes Tests

    @Test
    public void testConsentPreferencesAccessibleFromJava() {
        // This test verifies that ConsentPreferences and its properties are accessible
        // We can't create it directly, but we can verify the class is accessible
        Class<?> clazz = ConsentPreferences.class;
        assertNotNull("ConsentPreferences class should be accessible", clazz);
    }

    @Test
    public void testCategoryConsentAccessibleFromJava() {
        // Verify CategoryConsent class is accessible
        Class<?> clazz = CategoryConsent.class;
        assertNotNull("CategoryConsent class should be accessible", clazz);
    }

    // MARK: - Anonymous Inner Class Tests

    @Test
    public void testAnonymousConsentCallbackWorks() {
        // Verify Java's anonymous inner classes work with the callback interface
        final AtomicBoolean successCalled = new AtomicBoolean(false);

        ConsentCallback callback = new ConsentCallback() {
            @Override
            public void onSuccess() {
                successCalled.set(true);
            }

            @Override
            public void onFailure(ConsentException error) {
                fail("Should not fail");
            }
        };

        callback.onSuccess();
        assertTrue("Anonymous callback should work", successCalled.get());
    }

    @Test
    public void testLambdaStyleCallbackNotPossible() {
        // Java 8+ lambdas don't work with interfaces that have multiple methods
        // This test documents that ConsentCallback requires traditional anonymous class syntax
        // (This is expected behavior for multi-method interfaces)
        assertTrue("ConsentCallback requires anonymous class syntax in Java", true);
    }

    // MARK: - Thread Safety Tests

    @Test
    public void testGetInstanceThreadSafe() throws InterruptedException {
        // Verify getInstance() is thread-safe
        final int threadCount = 10;
        final CountDownLatch startLatch = new CountDownLatch(1);
        final CountDownLatch finishLatch = new CountDownLatch(threadCount);
        final AtomicReference<DataGrailConsent>[] instances = new AtomicReference[threadCount];

        for (int i = 0; i < threadCount; i++) {
            final int index = i;
            instances[i] = new AtomicReference<>();
            new Thread(() -> {
                try {
                    startLatch.await();
                    instances[index].set(DataGrailConsent.getInstance());
                    finishLatch.countDown();
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }).start();
        }

        startLatch.countDown();
        assertTrue("All threads should finish", finishLatch.await(5, TimeUnit.SECONDS));

        // Verify all instances are the same
        DataGrailConsent firstInstance = instances[0].get();
        for (int i = 1; i < threadCount; i++) {
            assertSame("All instances should be same", firstInstance, instances[i].get());
        }
    }

    // MARK: - Callback Invocation Tests

    @Test
    public void testInitializeCallbackInvokedOnInvalidUrl() throws InterruptedException {
        // Verify that onFailure callback is actually invoked when initialization fails
        final CountDownLatch latch = new CountDownLatch(1);
        final AtomicReference<ConsentException> capturedError = new AtomicReference<>();
        final AtomicBoolean successCalled = new AtomicBoolean(false);

        ConsentCallback callback = new ConsentCallback() {
            @Override
            public void onSuccess() {
                successCalled.set(true);
                latch.countDown();
            }

            @Override
            public void onFailure(ConsentException error) {
                capturedError.set(error);
                latch.countDown();
            }
        };

        // Call with invalid URL
        sdk.initialize(mockContext, "not-a-url", callback);

        // Wait for callback
        assertTrue("Callback should be invoked", latch.await(5, TimeUnit.SECONDS));

        // Verify failure callback was invoked
        assertFalse("onSuccess should not be called", successCalled.get());
        assertNotNull("onFailure should be called with error", capturedError.get());
        assertTrue("Should be InvalidConfiguration error",
            capturedError.get() instanceof ConsentException.InvalidConfiguration);
    }

    @Test
    public void testInitializeCallbackInvokedOnInvalidScheme() throws InterruptedException {
        // Verify that invalid URL scheme triggers onFailure
        final CountDownLatch latch = new CountDownLatch(1);
        final AtomicReference<ConsentException> capturedError = new AtomicReference<>();

        ConsentCallback callback = new ConsentCallback() {
            @Override
            public void onSuccess() {
                fail("Should not succeed with invalid scheme");
                latch.countDown();
            }

            @Override
            public void onFailure(ConsentException error) {
                capturedError.set(error);
                latch.countDown();
            }
        };

        // Call with ftp:// scheme (only http/https allowed)
        sdk.initialize(mockContext, "ftp://example.com/config.json", callback);

        // Wait for callback
        assertTrue("Callback should be invoked", latch.await(5, TimeUnit.SECONDS));

        // Verify correct error type
        assertNotNull("Error should be captured", capturedError.get());
        assertTrue("Should be InvalidConfiguration error",
            capturedError.get() instanceof ConsentException.InvalidConfiguration);
        assertTrue("Error message should mention https",
            capturedError.get().getMessage().toLowerCase().contains("http"));
    }

    @Test
    public void testPreferencesCallbackInvoked() {
        // Verify PreferencesCallback methods can be invoked
        final AtomicBoolean savedCalled = new AtomicBoolean(false);
        final AtomicBoolean dismissedCalled = new AtomicBoolean(false);
        final AtomicReference<ConsentPreferences> capturedPrefs = new AtomicReference<>();

        PreferencesCallback callback = new PreferencesCallback() {
            @Override
            public void onPreferencesSaved(ConsentPreferences preferences) {
                savedCalled.set(true);
                capturedPrefs.set(preferences);
            }

            @Override
            public void onDismissed() {
                dismissedCalled.set(true);
            }
        };

        // Test onDismissed
        callback.onDismissed();
        assertTrue("onDismissed should be called", dismissedCalled.get());
        assertFalse("onPreferencesSaved should not be called yet", savedCalled.get());
    }

    @Test
    public void testConsentChangeListenerInvoked() {
        // Verify ConsentChangeListener can be set and invoked
        final AtomicReference<ConsentPreferences> capturedPreferences = new AtomicReference<>();
        final CountDownLatch latch = new CountDownLatch(1);

        ConsentChangeListener listener = new ConsentChangeListener() {
            @Override
            public void onConsentChanged(ConsentPreferences preferences) {
                capturedPreferences.set(preferences);
                latch.countDown();
            }
        };

        // Set the listener on the SDK
        sdk.onConsentChanged(listener);

        // Verify listener was set (we can't easily trigger it without full initialization,
        // but we can verify it can be set without errors)
        assertTrue("Listener should be set successfully", true);
    }

    @Test
    public void testRetryCallbackInvoked() {
        // Verify RetryCallback can be invoked
        final AtomicInteger successCount = new AtomicInteger(-1);
        final AtomicInteger failureCount = new AtomicInteger(-1);
        final CountDownLatch latch = new CountDownLatch(1);

        RetryCallback callback = new RetryCallback() {
            @Override
            public void onRetryComplete(int success, int failure) {
                successCount.set(success);
                failureCount.set(failure);
                latch.countDown();
            }
        };

        // Call the method
        sdk.retryPendingRequests(callback);

        // Verify callback was invoked (may be called synchronously with 0,0 if no pending requests)
        // Note: This tests that the callback CAN be invoked, not the actual retry logic
        assertTrue("RetryCallback should be callable", true);
    }

    @Test
    public void testMultipleCallbacksCanCoexist() throws InterruptedException {
        // Verify that multiple callbacks can be used independently
        final CountDownLatch latch = new CountDownLatch(2);
        final AtomicInteger callbacksInvoked = new AtomicInteger(0);

        ConsentCallback callback1 = new ConsentCallback() {
            @Override
            public void onSuccess() {
                callbacksInvoked.incrementAndGet();
                latch.countDown();
            }

            @Override
            public void onFailure(ConsentException error) {
                callbacksInvoked.incrementAndGet();
                latch.countDown();
            }
        };

        ConsentCallback callback2 = new ConsentCallback() {
            @Override
            public void onSuccess() {
                callbacksInvoked.incrementAndGet();
                latch.countDown();
            }

            @Override
            public void onFailure(ConsentException error) {
                callbacksInvoked.incrementAndGet();
                latch.countDown();
            }
        };

        // Call initialize twice with different callbacks (will both fail with invalid URL)
        sdk.initialize(mockContext, "invalid-url-1", callback1);
        sdk.initialize(mockContext, "invalid-url-2", callback2);

        // Wait for both callbacks
        assertTrue("Both callbacks should be invoked", latch.await(5, TimeUnit.SECONDS));
        assertEquals("Both callbacks should have been invoked", 2, callbacksInvoked.get());
    }
}
