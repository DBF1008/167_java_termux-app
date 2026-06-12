package com.termux.shared.termux.settings.reload;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import androidx.test.core.app.ApplicationProvider;

import com.termux.shared.termux.TermuxConstants;
import com.termux.shared.termux.settings.properties.TermuxAppSharedProperties;
import com.termux.shared.termux.settings.properties.TermuxPropertyConstants;
import com.termux.shared.termux.shell.am.TermuxAmSocketServer;

import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.File;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * Regression tests for {@link TermuxSettingsReloader}.
 * <p>
 * Covers: enable/disable toggling, idempotent repeated reloads, concurrent reload thread safety,
 * graceful handling of missing or malformed properties files, callback ordering, callback
 * exception isolation, null-callback handling, and existing-session environment staleness flagging.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class TermuxSettingsReloaderTest {

    private static final File PROPS_FILE = TermuxConstants.TERMUX_PROPERTIES_PRIMARY_FILE;

    private Context context;

    // -----------------------------------------------------------------------
    // Lifecycle
    // -----------------------------------------------------------------------

    @BeforeClass
    public static void classSetUp() {
        // Ensure a clean singleton before the first test.
        resetPropertiesSingleton();
    }

    @Before
    public void setUp() {
        context = ApplicationProvider.getApplicationContext();
    }

    @After
    public void tearDown() {
        TestPropertiesHelper.deletePropertiesFile(PROPS_FILE);
    }

    @AfterClass
    public static void classTearDown() {
        TestPropertiesHelper.deletePropertiesFile(PROPS_FILE);
        resetPropertiesSingleton();
    }

    // -----------------------------------------------------------------------
    // ReloadResult value-class tests
    // -----------------------------------------------------------------------

    @Test
    public void reloadResult_success_hasCorrectFields() {
        TermuxSettingsReloader.ReloadResult result =
            TermuxSettingsReloader.ReloadResult.success(true, true);

        assertTrue(result.success);
        assertTrue(result.amSocketServerStateChanged);
        assertTrue(result.amSocketServerEnvStale);
        assertNull(result.errorMessage);
    }

    @Test
    public void reloadResult_failure_hasCorrectFields() {
        TermuxSettingsReloader.ReloadResult result =
            TermuxSettingsReloader.ReloadResult.failure("boom");

        assertFalse(result.success);
        assertFalse(result.amSocketServerStateChanged);
        assertFalse(result.amSocketServerEnvStale);
        assertEquals("boom", result.errorMessage);
    }

    // -----------------------------------------------------------------------
    // Null-callback handling
    // -----------------------------------------------------------------------

    @Test
    public void reload_nullCallback_succeeds() throws Exception {
        writeDefaultProps();
        ensurePropertiesInitialized();

        TermuxSettingsReloader.ReloadResult result =
            TermuxSettingsReloader.reload(context, null);

        assertTrue("Reload should succeed even with null callback", result.success);
        assertNull(result.errorMessage);
    }

    // -----------------------------------------------------------------------
    // No-change / idempotent reload
    // -----------------------------------------------------------------------

    @Test
    public void reload_noPropertyChange_reportsNoStateChange() throws Exception {
        writeDefaultProps();
        ensurePropertiesInitialized();

        TermuxSettingsReloader.ReloadResult result =
            TermuxSettingsReloader.reload(context, new NoOpCallback());

        assertTrue(result.success);
        // AM socket server state should not change since the property hasn't changed.
        // (Under Robolectric the server can't actually start, so wasRunning==nowRunning==false.)
        assertFalse("No state change expected when property hasn't changed",
            result.amSocketServerStateChanged);
        assertFalse(result.amSocketServerEnvStale);
    }

    @Test
    public void reload_calledThreeTimes_idempotent() throws Exception {
        writeDefaultProps();
        ensurePropertiesInitialized();

        NoOpCallback cb = new NoOpCallback();

        for (int i = 0; i < 3; i++) {
            TermuxSettingsReloader.ReloadResult result =
                TermuxSettingsReloader.reload(context, cb);
            assertTrue("Reload #" + (i + 1) + " should succeed", result.success);
        }

        // All three reloads should have completed without error.
        assertEquals(3, cb.completeCount);
    }

    // -----------------------------------------------------------------------
    // Properties actually get reloaded from disk
    // -----------------------------------------------------------------------

    @Test
    public void reload_picksUpNewPropertyValue() throws Exception {
        // Write initial properties with the AM socket server enabled (the default).
        Map<String, String> props = new HashMap<>();
        props.put(TermuxPropertyConstants.KEY_RUN_TERMUX_AM_SOCKET_SERVER, "true");
        TestPropertiesHelper.writeProperties(PROPS_FILE, props);
        ensurePropertiesInitialized();

        // Sanity check: initial value.
        assertTrue(TermuxAppSharedProperties.getProperties().shouldRunTermuxAmSocketServer());

        // Change the file to disable the server.
        TestPropertiesHelper.writeSingleProperty(PROPS_FILE,
            TermuxPropertyConstants.KEY_RUN_TERMUX_AM_SOCKET_SERVER, "false");

        TermuxSettingsReloader.ReloadResult result =
            TermuxSettingsReloader.reload(context, new NoOpCallback());

        assertTrue(result.success);
        // The in-memory cache should now reflect the new value.
        assertFalse("Property should now be false after reload",
            TermuxAppSharedProperties.getProperties().shouldRunTermuxAmSocketServer());
    }

    // -----------------------------------------------------------------------
    // Enable → Disable / Disable → Enable (properties level)
    // -----------------------------------------------------------------------

    @Test
    public void reload_disableAmSocket_propertiesReflectsChange() throws Exception {
        Map<String, String> props = new HashMap<>();
        props.put(TermuxPropertyConstants.KEY_RUN_TERMUX_AM_SOCKET_SERVER, "true");
        TestPropertiesHelper.writeProperties(PROPS_FILE, props);
        ensurePropertiesInitialized();
        assertTrue(TermuxAppSharedProperties.getProperties().shouldRunTermuxAmSocketServer());

        // Now disable.
        TestPropertiesHelper.writeSingleProperty(PROPS_FILE,
            TermuxPropertyConstants.KEY_RUN_TERMUX_AM_SOCKET_SERVER, "false");

        TermuxSettingsReloader.reload(context, new NoOpCallback());

        assertFalse(TermuxAppSharedProperties.getProperties().shouldRunTermuxAmSocketServer());
    }

    @Test
    public void reload_enableAmSocket_propertiesReflectsChange() throws Exception {
        Map<String, String> props = new HashMap<>();
        props.put(TermuxPropertyConstants.KEY_RUN_TERMUX_AM_SOCKET_SERVER, "false");
        TestPropertiesHelper.writeProperties(PROPS_FILE, props);
        ensurePropertiesInitialized();
        assertFalse(TermuxAppSharedProperties.getProperties().shouldRunTermuxAmSocketServer());

        // Now enable.
        TestPropertiesHelper.writeSingleProperty(PROPS_FILE,
            TermuxPropertyConstants.KEY_RUN_TERMUX_AM_SOCKET_SERVER, "true");

        TermuxSettingsReloader.reload(context, new NoOpCallback());

        assertTrue(TermuxAppSharedProperties.getProperties().shouldRunTermuxAmSocketServer());
    }

    // -----------------------------------------------------------------------
    // Missing / malformed properties file
    // -----------------------------------------------------------------------

    @Test
    public void reload_propertiesFileMissing_handlesGracefully() {
        // Delete the file so there is no properties file on disk.
        TestPropertiesHelper.deletePropertiesFile(PROPS_FILE);
        ensurePropertiesInitialized();

        TermuxSettingsReloader.ReloadResult result =
            TermuxSettingsReloader.reload(context, new NoOpCallback());

        // Should not crash – defaults are used when the file is missing.
        assertTrue("Reload should succeed even without properties file", result.success);
    }

    @Test
    public void reload_propertiesFileMalformed_handlesGracefully() throws Exception {
        ensurePropertiesInitialized();

        TestPropertiesHelper.writeMalformedContent(PROPS_FILE);

        TermuxSettingsReloader.ReloadResult result =
            TermuxSettingsReloader.reload(context, new NoOpCallback());

        // The Properties.load() parser is lenient – it may or may not reject the content.
        // Either way, the reloader must not crash.
        assertNotNull(result);
    }

    // -----------------------------------------------------------------------
    // Callback ordering
    // -----------------------------------------------------------------------

    @Test
    public void reload_callbackOrder_propertiesBeforeComplete() throws Exception {
        writeDefaultProps();
        ensurePropertiesInitialized();

        final List<String> callOrder = Collections.synchronizedList(new ArrayList<>());

        TermuxSettingsReloader.reload(context, new TermuxSettingsReloader.ReloadCallback() {
            @Override
            public void onPropertiesReloaded() {
                callOrder.add("onPropertiesReloaded");
            }

            @Override
            public void onReloadComplete(TermuxSettingsReloader.ReloadResult result) {
                callOrder.add("onReloadComplete");
            }
        });

        assertEquals(2, callOrder.size());
        assertEquals("onPropertiesReloaded", callOrder.get(0));
        assertEquals("onReloadComplete", callOrder.get(1));
    }

    @Test
    public void reload_callbackReceivesResultInOnComplete() throws Exception {
        writeDefaultProps();
        ensurePropertiesInitialized();

        final TermuxSettingsReloader.ReloadResult[] captured = new TermuxSettingsReloader.ReloadResult[1];

        TermuxSettingsReloader.reload(context, new TermuxSettingsReloader.ReloadCallback() {
            @Override
            public void onPropertiesReloaded() { }

            @Override
            public void onReloadComplete(TermuxSettingsReloader.ReloadResult result) {
                captured[0] = result;
            }
        });

        assertNotNull("onReloadComplete should receive a non-null result", captured[0]);
        assertTrue(captured[0].success);
    }

    // -----------------------------------------------------------------------
    // Callback exception isolation
    // -----------------------------------------------------------------------

    @Test
    public void reload_onPropertiesReloadedThrows_continuesExecution() throws Exception {
        writeDefaultProps();
        ensurePropertiesInitialized();

        final boolean[] completeCalled = {false};

        TermuxSettingsReloader.ReloadResult result = TermuxSettingsReloader.reload(context,
            new TermuxSettingsReloader.ReloadCallback() {
                @Override
                public void onPropertiesReloaded() {
                    throw new RuntimeException("Intentional test exception in onPropertiesReloaded");
                }

                @Override
                public void onReloadComplete(TermuxSettingsReloader.ReloadResult r) {
                    completeCalled[0] = true;
                }
            });

        assertTrue("Reload should succeed despite callback exception", result.success);
        assertTrue("onReloadComplete should still be called", completeCalled[0]);
    }

    @Test
    public void reload_onReloadCompleteThrows_doesNotCrash() throws Exception {
        writeDefaultProps();
        ensurePropertiesInitialized();

        // This should not throw, even though the callback throws.
        TermuxSettingsReloader.ReloadResult result = TermuxSettingsReloader.reload(context,
            new TermuxSettingsReloader.ReloadCallback() {
                @Override
                public void onPropertiesReloaded() { }

                @Override
                public void onReloadComplete(TermuxSettingsReloader.ReloadResult r) {
                    throw new RuntimeException("Intentional test exception in onReloadComplete");
                }
            });

        assertTrue(result.success);
    }

    // -----------------------------------------------------------------------
    // Failure path: properties singleton not initialized
    // -----------------------------------------------------------------------

    @Test
    public void reload_propertiesNotInitialized_reportsFailure() {
        resetPropertiesSingleton();
        // Do NOT initialize – getProperties() will return null.

        TermuxSettingsReloader.ReloadResult result =
            TermuxSettingsReloader.reload(context, new NoOpCallback());

        assertFalse("Should report failure when singleton is null", result.success);
        assertNotNull(result.errorMessage);
        assertTrue(result.errorMessage.contains("singleton is null"));
    }

    // -----------------------------------------------------------------------
    // Thread safety: concurrent reload calls
    // -----------------------------------------------------------------------

    @Test
    public void reload_rapidSuccessiveCalls_threadSafe() throws Exception {
        writeDefaultProps();
        ensurePropertiesInitialized();

        int threadCount = 10;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        List<Future<TermuxSettingsReloader.ReloadResult>> futures = new ArrayList<>();

        for (int i = 0; i < threadCount; i++) {
            futures.add(executor.submit(() -> {
                startLatch.await(); // Wait so all threads start at roughly the same time.
                return TermuxSettingsReloader.reload(context, new NoOpCallback());
            }));
        }

        // Release all threads at once.
        startLatch.countDown();

        executor.shutdown();
        assertTrue("All threads should finish within 30 seconds",
            executor.awaitTermination(30, TimeUnit.SECONDS));

        // Every future should have completed with success.
        for (Future<TermuxSettingsReloader.ReloadResult> future : futures) {
            TermuxSettingsReloader.ReloadResult result = future.get();
            assertTrue("Each concurrent reload should succeed", result.success);
        }
    }

    // -----------------------------------------------------------------------
    // AM Socket Server: isServerRunning() consistency
    // -----------------------------------------------------------------------

    @Test
    public void isServerRunning_beforeSetup_returnsFalse() {
        // Without any setup, the server should not be running.
        assertFalse(TermuxAmSocketServer.isServerRunning());
    }

    @Test
    public void reload_envStaleOnlyWhenStateChanged() throws Exception {
        writeDefaultProps();
        ensurePropertiesInitialized();

        // Under Robolectric, the socket server can't actually start, so
        // amSocketServerStateChanged should be false and amSocketServerEnvStale should be false.
        TermuxSettingsReloader.ReloadResult result =
            TermuxSettingsReloader.reload(context, new NoOpCallback());

        assertTrue(result.success);
        // If state didn't change, env should NOT be flagged as stale.
        if (!result.amSocketServerStateChanged) {
            assertFalse("Env stale should be false when state didn't change",
                result.amSocketServerEnvStale);
        }
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private void writeDefaultProps() throws Exception {
        Map<String, String> props = new HashMap<>();
        props.put(TermuxPropertyConstants.KEY_RUN_TERMUX_AM_SOCKET_SERVER, "true");
        TestPropertiesHelper.writeProperties(PROPS_FILE, props);
    }

    /**
     * Ensure the {@link TermuxAppSharedProperties} singleton is initialized.
     * If already initialized (from a previous test), this is a no-op.
     */
    private void ensurePropertiesInitialized() {
        if (TermuxAppSharedProperties.getProperties() == null) {
            TermuxAppSharedProperties.init(context);
        }
    }

    /**
     * Use reflection to null out the static singleton field so tests can start fresh.
     */
    private static void resetPropertiesSingleton() {
        try {
            Field field = TermuxAppSharedProperties.class.getDeclaredField("properties");
            field.setAccessible(true);
            field.set(null, null);
        } catch (Exception e) {
            // Silently ignore – the test may still pass if the singleton was already null.
        }
    }

    /** Simple no-op callback that counts invocations. */
    private static class NoOpCallback implements TermuxSettingsReloader.ReloadCallback {
        int propertiesReloadedCount = 0;
        int completeCount = 0;

        @Override
        public void onPropertiesReloaded() {
            propertiesReloadedCount++;
        }

        @Override
        public void onReloadComplete(TermuxSettingsReloader.ReloadResult result) {
            completeCount++;
        }
    }
}
