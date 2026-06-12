package com.termux.app;

import android.content.Context;

import com.termux.shared.termux.shell.am.TermuxAmSocketServer;
import com.termux.shared.termux.shell.am.TermuxAmSocketServer.SocketServerAction;
import com.termux.shared.termux.shell.command.environment.TermuxAppShellEnvironment;

import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import java.util.HashMap;
import java.util.Map;

/**
 * Regression tests for the safe hot-reload of the termux-am socket server state.
 *
 * The native socket layer ({@link com.termux.shared.net.socket.local.LocalSocketManager#start()})
 * loads a JNI library and cannot run on the host JVM, so these tests verify the reload
 * <b>policy</b> (start/stop/no-op decisions) and the <b>exported-state / session-env consistency</b>
 * that {@link TermuxAmSocketServer#updateState(Context)} relies on, rather than a real socket.
 *
 * Covered scenarios: enable, disable, repeated reload (idempotency/convergence), and existing
 * session compatibility (a session keeps the env snapshot it captured; only new sessions read the
 * updated value).
 */
@RunWith(RobolectricTestRunner.class)
public class TermuxAmSocketServerReloadTest {

    private static final String ENV_KEY = TermuxAppShellEnvironment.ENV_TERMUX_APP__AM_SOCKET_SERVER_ENABLED;

    /** An unrelated app env key used to verify it is preserved across socket-server env updates. */
    private static final String UNRELATED_ENV_KEY = TermuxAppShellEnvironment.ENV_TERMUX_APP__PID;

    private Context context;

    @Before
    public void setUp() {
        context = RuntimeEnvironment.getApplication();
        // Seed the app env cache as it would be after TermuxAppShellEnvironment.init(), including an
        // unrelated key so we can assert the socket-server update does not clobber other variables.
        HashMap<String, String> environment = new HashMap<>();
        environment.put(UNRELATED_ENV_KEY, "12345");
        TermuxAppShellEnvironment.termuxAppEnvironment = environment;
    }

    @After
    public void tearDown() {
        // Reset process-global static state so it does not leak into other tests.
        TermuxAppShellEnvironment.termuxAppEnvironment = null;
        TermuxAmSocketServer.setTermuxAppAMSocketServerEnabled(context, false);
    }

    /** Reload policy: the right action is chosen for enable, disable, and repeated reload. */
    @Test
    public void testSocketServerActionPolicy() {
        // enable: should run but not running yet -> start
        Assert.assertEquals(SocketServerAction.START,
            TermuxAmSocketServer.getSocketServerActionForState(true, false));
        // disable: should not run but is running -> stop
        Assert.assertEquals(SocketServerAction.STOP,
            TermuxAmSocketServer.getSocketServerActionForState(false, true));
        // repeated reload while enabled: already running -> no-op
        Assert.assertEquals(SocketServerAction.NONE,
            TermuxAmSocketServer.getSocketServerActionForState(true, true));
        // repeated reload while disabled: already stopped -> no-op
        Assert.assertEquals(SocketServerAction.NONE,
            TermuxAmSocketServer.getSocketServerActionForState(false, false));
    }

    /**
     * Enable, disable, and repeated reload keep the exported flag and the env value read by new
     * sessions in sync, without clobbering unrelated env variables.
     */
    @Test
    public void testExportedStateAndEnvStayConsistentAcrossReloads() {
        // enable
        TermuxAmSocketServer.setTermuxAppAMSocketServerEnabled(context, true);
        Assert.assertEquals(Boolean.TRUE, TermuxAmSocketServer.getTermuxAppAMSocketServerEnabled(context));
        Assert.assertEquals("true", TermuxAppShellEnvironment.termuxAppEnvironment.get(ENV_KEY));
        // unrelated env variable is preserved
        Assert.assertEquals("12345", TermuxAppShellEnvironment.termuxAppEnvironment.get(UNRELATED_ENV_KEY));

        // disable (reload after changing the property)
        TermuxAmSocketServer.setTermuxAppAMSocketServerEnabled(context, false);
        Assert.assertEquals(Boolean.FALSE, TermuxAmSocketServer.getTermuxAppAMSocketServerEnabled(context));
        Assert.assertEquals("false", TermuxAppShellEnvironment.termuxAppEnvironment.get(ENV_KEY));

        // repeated reload while still disabled is idempotent
        TermuxAmSocketServer.setTermuxAppAMSocketServerEnabled(context, false);
        Assert.assertEquals("false", TermuxAppShellEnvironment.termuxAppEnvironment.get(ENV_KEY));

        // re-enable converges back to the enabled value
        TermuxAmSocketServer.setTermuxAppAMSocketServerEnabled(context, true);
        Assert.assertEquals("true", TermuxAppShellEnvironment.termuxAppEnvironment.get(ENV_KEY));
    }

    /**
     * Existing session compatibility: a session captures the env when it is created; a later reload
     * that flips the state must not retroactively change that captured snapshot, while the shared
     * cache that new sessions read does reflect the new value.
     */
    @Test
    public void testExistingSessionEnvSnapshotUnaffectedByReload() {
        // server enabled when the "existing" session is created
        TermuxAmSocketServer.setTermuxAppAMSocketServerEnabled(context, true);

        // a running session captures its environment (a copy) at creation time
        Map<String, String> existingSessionEnv = new HashMap<>(TermuxAppShellEnvironment.termuxAppEnvironment);
        Assert.assertEquals("true", existingSessionEnv.get(ENV_KEY));

        // user disables the server and reloads settings
        TermuxAmSocketServer.setTermuxAppAMSocketServerEnabled(context, false);

        // the already-running session keeps its snapshot ...
        Assert.assertEquals("true", existingSessionEnv.get(ENV_KEY));
        // ... while the shared cache that subsequent new sessions read reflects the new state
        Assert.assertEquals("false", TermuxAppShellEnvironment.termuxAppEnvironment.get(ENV_KEY));
    }

}
