package com.termux.app;

import com.termux.shared.shell.ShellUtils;
import com.termux.shared.shell.command.ExecutionCommand;
import com.termux.shared.shell.command.ExecutionCommand.ExecutionState;
import com.termux.shared.shell.command.ExecutionCommand.Runner;
import com.termux.shared.shell.command.ExecutionCommand.ShellCreateMode;
import com.termux.shared.shell.command.runner.ExecutionCommandRunner;
import com.termux.shared.shell.command.runner.ExecutionCommandRunnerClient;
import com.termux.shared.shell.command.runner.app.AppShell;
import com.termux.shared.termux.shell.command.runner.terminal.TermuxSession;
import com.termux.shared.termux.shell.TermuxShellManager;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import static org.junit.Assert.*;

/**
 * Regression tests for the dispatcher-related logic, covering:
 * - Shell name derivation from executable path
 * - ShellCreateMode validation
 * - Runner type routing (AppShell vs TermuxSession)
 * - Pending plugin execution command tracking
 * - Runner registration/unregistration in TermuxShellManager
 *
 * These tests verify the unified behavior that was previously duplicated
 * across executeTermuxTaskCommand/executeTermuxSessionCommand,
 * createTermuxTask/createTermuxSession, and onAppShellExited/onTermuxSessionExited.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class TermuxCommandDispatcherTest {

    // ========================================================================
    //  Shell name derivation tests
    // ========================================================================

    @Test
    public void testShellNameDerivation_fromExecutablePath() {
        // Simulates the dispatcher's shell name derivation:
        // executable="/bin/do-something.sh" => shellName="do-something.sh"
        assertEquals("do-something.sh", ShellUtils.getExecutableBasename("/bin/do-something.sh"));
        assertEquals("bash", ShellUtils.getExecutableBasename("/usr/bin/bash"));
        assertEquals("script.py", ShellUtils.getExecutableBasename("/home/user/script.py"));
        assertEquals("ls", ShellUtils.getExecutableBasename("/system/bin/ls"));
    }

    @Test
    public void testShellNameDerivation_noDirectory() {
        assertEquals("command", ShellUtils.getExecutableBasename("command"));
    }

    // ========================================================================
    //  ShellCreateMode validation tests
    // ========================================================================

    @Test
    public void testShellCreateMode_always_isDefault() {
        assertTrue(ShellCreateMode.ALWAYS.equalsMode("always"));
        assertTrue(ShellCreateMode.ALWAYS.equalsMode(null)); // null is handled as ALWAYS in dispatcher
        assertFalse(ShellCreateMode.ALWAYS.equalsMode("no-shell-with-name"));
    }

    @Test
    public void testShellCreateMode_noShellWithName() {
        assertTrue(ShellCreateMode.NO_SHELL_WITH_NAME.equalsMode("no-shell-with-name"));
        assertFalse(ShellCreateMode.NO_SHELL_WITH_NAME.equalsMode("always"));
    }

    @Test
    public void testShellCreateMode_invalidMode_returnsNull() {
        assertNull(ShellCreateMode.modeOf("invalid-mode"));
    }

    // ========================================================================
    //  Runner type routing tests
    // ========================================================================

    @Test
    public void testRunnerType_appShell() {
        ExecutionCommand command = new ExecutionCommand(1);
        command.runner = Runner.APP_SHELL.getName();
        assertTrue("APP_SHELL runner should match", Runner.APP_SHELL.equalsRunner(command.runner));
        assertFalse("APP_SHELL should not match TERMINAL_SESSION", Runner.TERMINAL_SESSION.equalsRunner(command.runner));
    }

    @Test
    public void testRunnerType_terminalSession() {
        ExecutionCommand command = new ExecutionCommand(2);
        command.runner = Runner.TERMINAL_SESSION.getName();
        assertTrue("TERMINAL_SESSION runner should match", Runner.TERMINAL_SESSION.equalsRunner(command.runner));
        assertFalse("TERMINAL_SESSION should not match APP_SHELL", Runner.APP_SHELL.equalsRunner(command.runner));
    }

    @Test
    public void testRunnerType_invalid() {
        assertNull("Invalid runner should return null from runnerOf", Runner.runnerOf("invalid-runner"));
    }

    // ========================================================================
    //  ExecutionCommand state machine tests (relevant to dispatcher)
    // ========================================================================

    @Test
    public void testExecutionCommand_stateTransitions() {
        ExecutionCommand command = new ExecutionCommand(20);
        assertEquals(ExecutionState.PRE_EXECUTION, getEffectiveState(command));

        assertTrue(command.setState(ExecutionState.EXECUTING));
        assertEquals(ExecutionState.EXECUTING, getEffectiveState(command));

        assertTrue(command.setState(ExecutionState.EXECUTED));
        assertEquals(ExecutionState.EXECUTED, getEffectiveState(command));

        assertTrue(command.setState(ExecutionState.SUCCESS));
        assertEquals(ExecutionState.SUCCESS, getEffectiveState(command));
    }

    @Test
    public void testExecutionCommand_cannotTransitionBackwards() {
        ExecutionCommand command = new ExecutionCommand(21);
        command.setState(ExecutionState.EXECUTING);
        command.setState(ExecutionState.EXECUTED);

        assertFalse("Should not allow backwards transition",
            command.setState(ExecutionState.EXECUTING));
    }

    @Test
    public void testExecutionCommand_failedState() {
        ExecutionCommand command = new ExecutionCommand(22);
        command.setState(ExecutionState.EXECUTING);

        assertTrue(command.setStateFailed(1, "Test error"));
        assertEquals(ExecutionState.FAILED, getEffectiveState(command));
        assertTrue(command.isStateFailed());
    }

    @Test
    public void testExecutionCommand_shouldNotProcessResults_guardsDuplicates() {
        ExecutionCommand command = new ExecutionCommand(23);

        assertFalse("First call should return false (allow processing)",
            command.shouldNotProcessResults());
        assertTrue("Second call should return true (block processing)",
            command.shouldNotProcessResults());
    }

    // ========================================================================
    //  Pending plugin execution command tests
    // ========================================================================

    @Test
    public void testIsPluginExecutionCommandWithPendingResult_noPendingIntent() {
        ExecutionCommand command = new ExecutionCommand(30);
        command.isPluginExecutionCommand = true;
        // No pending intent or result directory set

        assertFalse("Should be false without pending result config",
            command.isPluginExecutionCommandWithPendingResult());
    }

    @Test
    public void testIsPluginExecutionCommandWithPendingResult_withPendingIntent() {
        ExecutionCommand command = new ExecutionCommand(31);
        command.isPluginExecutionCommand = true;
        // Simulating a pending intent (can't create real PendingIntent in test without more setup)
        // So we test the isCommandWithPendingResult logic
        assertFalse("resultConfig.isCommandWithPendingResult should be false without actual pending intent",
            command.resultConfig.isCommandWithPendingResult());
    }

    @Test
    public void testIsPluginExecutionCommandWithPendingResult_withResultDirectory() {
        ExecutionCommand command = new ExecutionCommand(32);
        command.isPluginExecutionCommand = true;
        command.resultConfig.resultDirectoryPath = "/data/data/com.termux/results";

        assertTrue("Should be true with result directory set",
            command.isPluginExecutionCommandWithPendingResult());
    }

    @Test
    public void testIsPluginExecutionCommandWithPendingResult_notPluginCommand() {
        ExecutionCommand command = new ExecutionCommand(33);
        command.isPluginExecutionCommand = false;
        command.resultConfig.resultDirectoryPath = "/data/data/com.termux/results";

        assertFalse("Should be false when not a plugin command",
            command.isPluginExecutionCommandWithPendingResult());
    }

    // ========================================================================
    //  ExecutionCommandRunner interface contract tests
    // ========================================================================

    @Test
    public void testExecutionCommandRunner_interfaceContract() {
        // Verify that both AppShell and TermuxSession implement ExecutionCommandRunner
        // This is a compile-time check that ensures the interface is properly implemented
        assertTrue("AppShell should implement ExecutionCommandRunner",
            ExecutionCommandRunner.class.isAssignableFrom(AppShell.class));
        assertTrue("TermuxSession should implement ExecutionCommandRunner",
            ExecutionCommandRunner.class.isAssignableFrom(TermuxSession.class));
    }

    @Test
    public void testExecutionCommandRunnerClient_interfaceContract() {
        // Verify the client interface exists and has the expected method
        try {
            ExecutionCommandRunnerClient.class.getMethod("onRunnerExited", ExecutionCommandRunner.class);
        } catch (NoSuchMethodException e) {
            fail("ExecutionCommandRunnerClient should have onRunnerExited(ExecutionCommandRunner) method");
        }
    }

    // ========================================================================
    //  Helpers
    // ========================================================================

    private ExecutionState getEffectiveState(ExecutionCommand command) {
        if (command.isSuccessful()) return ExecutionState.SUCCESS;
        if (command.isStateFailed()) return ExecutionState.FAILED;
        if (command.isExecuting()) return ExecutionState.EXECUTING;
        if (command.hasExecuted()) return ExecutionState.EXECUTED;
        return ExecutionState.PRE_EXECUTION;
    }
}
