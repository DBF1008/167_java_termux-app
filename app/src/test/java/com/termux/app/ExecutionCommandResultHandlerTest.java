package com.termux.app;

import android.content.Context;

import com.termux.shared.shell.command.ExecutionCommand;
import com.termux.shared.shell.command.ExecutionCommand.ExecutionState;
import com.termux.shared.shell.command.runner.ExecutionCommandResultHandler;
import com.termux.shared.shell.command.runner.ExecutionCommandRunner;
import com.termux.shared.shell.command.runner.ExecutionCommandRunnerClient;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.*;

/**
 * Regression tests for {@link ExecutionCommandResultHandler}, covering:
 * - Foreground session result processing
 * - Background task result processing
 * - Pending result scenarios
 * - Failure callback scenarios
 * - Kill-if-executing logic
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class ExecutionCommandResultHandlerTest {

    private Context context;

    @Before
    public void setUp() {
        context = RuntimeEnvironment.getApplication();
    }

    // ========================================================================
    //  processResult tests
    // ========================================================================

    @Test
    public void testProcessResult_withRunnerAndClient_invokesCallback() {
        ExecutionCommand command = new ExecutionCommand(1);
        command.setState(ExecutionState.EXECUTING);
        command.setState(ExecutionState.EXECUTED);
        command.commandLabel = "Test Command";

        AtomicBoolean callbackInvoked = new AtomicBoolean(false);
        ExecutionCommandRunner runner = createMockRunner(command);
        ExecutionCommandRunnerClient client = r -> callbackInvoked.set(true);

        ExecutionCommandResultHandler.processResult(runner, null, "TestTag", client);

        assertTrue("Callback should be invoked when runner and client are present", callbackInvoked.get());
        // State should NOT be set to SUCCESS by the handler when a client is present
        // (the client is responsible for setting the final state)
        assertNotEquals("State should not be SUCCESS when client handles the callback",
            ExecutionState.SUCCESS, getCurrentState(command));
    }

    @Test
    public void testProcessResult_withRunnerNoClient_setsSuccess() {
        ExecutionCommand command = new ExecutionCommand(2);
        command.setState(ExecutionState.EXECUTING);
        command.setState(ExecutionState.EXECUTED);
        command.commandLabel = "Test Command No Client";

        ExecutionCommandRunner runner = createMockRunner(command);

        ExecutionCommandResultHandler.processResult(runner, null, "TestTag", null);

        assertEquals("State should be SUCCESS when no client is present",
            ExecutionState.SUCCESS, getCurrentState(command));
    }

    @Test
    public void testProcessResult_withFailedCommand_doesNotSetSuccess() {
        ExecutionCommand command = new ExecutionCommand(3);
        command.setState(ExecutionState.EXECUTING);
        command.setStateFailed(com.termux.shared.errors.Errno.ERRNO_FAILED.getCode(), "Test failure");
        command.commandLabel = "Test Failed Command";

        AtomicBoolean callbackInvoked = new AtomicBoolean(false);
        ExecutionCommandRunner runner = createMockRunner(command);
        ExecutionCommandRunnerClient client = r -> callbackInvoked.set(true);

        ExecutionCommandResultHandler.processResult(runner, null, "TestTag", client);

        assertTrue("Callback should be invoked even for failed commands", callbackInvoked.get());
        assertEquals("State should remain FAILED",
            ExecutionState.FAILED, getCurrentState(command));
    }

    @Test
    public void testProcessResult_noRunnerNoClient_setsSuccessOnNonFailed() {
        ExecutionCommand command = new ExecutionCommand(4);
        command.setState(ExecutionState.EXECUTING);
        command.setState(ExecutionState.EXECUTED);
        command.commandLabel = "Standalone Command";

        ExecutionCommandResultHandler.processResult(null, command, "TestTag", null);

        assertEquals("State should be SUCCESS for standalone command without client",
            ExecutionState.SUCCESS, getCurrentState(command));
    }

    @Test
    public void testProcessResult_duplicateCall_secondCallIgnored() {
        ExecutionCommand command = new ExecutionCommand(5);
        command.setState(ExecutionState.EXECUTING);
        command.setState(ExecutionState.EXECUTED);
        command.commandLabel = "Duplicate Command";

        AtomicInteger callbackCount = new AtomicInteger(0);
        ExecutionCommandRunner runner = createMockRunner(command);
        ExecutionCommandRunnerClient client = r -> callbackCount.incrementAndGet();

        // First call should invoke callback
        ExecutionCommandResultHandler.processResult(runner, null, "TestTag", client);
        assertEquals("First call should invoke callback", 1, callbackCount.get());

        // Second call should be ignored (shouldNotProcessResults returns true)
        ExecutionCommandResultHandler.processResult(runner, null, "TestTag", client);
        assertEquals("Second call should be ignored", 1, callbackCount.get());
    }

    @Test
    public void testProcessResult_nullExecutionCommand_noOp() {
        // Should not throw
        ExecutionCommandResultHandler.processResult(null, null, "TestTag", null);
    }

    // ========================================================================
    //  performKillIfExecuting tests
    // ========================================================================

    @Test
    public void testPerformKillIfExecuting_executingCommand_setsFailedAndCallsResult() {
        ExecutionCommand command = new ExecutionCommand(10);
        command.setState(ExecutionState.EXECUTING);
        command.commandLabel = "Kill Test Command";

        AtomicBoolean processResultCalled = new AtomicBoolean(false);
        AtomicBoolean doKillCalled = new AtomicBoolean(false);

        ExecutionCommandResultHandler.performKillIfExecuting(
            command, context, "TestTag", "TestRunner", true,
            null,
            () -> processResultCalled.set(true),
            () -> doKillCalled.set(true));

        assertEquals("State should be FAILED after kill",
            ExecutionState.FAILED, getCurrentState(command));
        assertEquals("Exit code should be 137 (SIGKILL)",
            Integer.valueOf(137), command.resultData.exitCode);
        assertTrue("processResult should be called", processResultCalled.get());
        assertTrue("doKill should be called", doKillCalled.get());
    }

    @Test
    public void testPerformKillIfExecuting_alreadyExecuted_skipsKill() {
        ExecutionCommand command = new ExecutionCommand(11);
        command.setState(ExecutionState.EXECUTING);
        command.setState(ExecutionState.EXECUTED);
        command.commandLabel = "Already Executed Command";

        AtomicBoolean processResultCalled = new AtomicBoolean(false);
        AtomicBoolean doKillCalled = new AtomicBoolean(false);

        ExecutionCommandResultHandler.performKillIfExecuting(
            command, context, "TestTag", "TestRunner", true,
            null,
            () -> processResultCalled.set(true),
            () -> doKillCalled.set(true));

        assertFalse("processResult should NOT be called for already executed command",
            processResultCalled.get());
        assertFalse("doKill should NOT be called for already executed command",
            doKillCalled.get());
    }

    @Test
    public void testPerformKillIfExecuting_processResultFalse_doesNotProcessResult() {
        ExecutionCommand command = new ExecutionCommand(12);
        command.setState(ExecutionState.EXECUTING);
        command.commandLabel = "Kill No Process Command";

        AtomicBoolean processResultCalled = new AtomicBoolean(false);
        AtomicBoolean doKillCalled = new AtomicBoolean(false);

        ExecutionCommandResultHandler.performKillIfExecuting(
            command, context, "TestTag", "TestRunner", false,
            null,
            () -> processResultCalled.set(true),
            () -> doKillCalled.set(true));

        assertEquals("State should be FAILED",
            ExecutionState.FAILED, getCurrentState(command));
        assertFalse("processResult should NOT be called when processResult=false",
            processResultCalled.get());
        assertTrue("doKill should still be called", doKillCalled.get());
    }

    @Test
    public void testPerformKillIfExecuting_withOutputCollection_collectsBeforeResult() {
        ExecutionCommand command = new ExecutionCommand(13);
        command.setState(ExecutionState.EXECUTING);
        command.commandLabel = "Output Collection Command";

        AtomicBoolean outputCollected = new AtomicBoolean(false);
        AtomicBoolean resultProcessed = new AtomicBoolean(false);

        ExecutionCommandResultHandler.performKillIfExecuting(
            command, context, "TestTag", "TestRunner", true,
            () -> {
                // Verify that output is collected before result is processed
                assertFalse("Result should not be processed yet during output collection",
                    resultProcessed.get());
                outputCollected.set(true);
            },
            () -> {
                // Verify that output was collected before result processing
                assertTrue("Output should be collected before result processing",
                    outputCollected.get());
                resultProcessed.set(true);
            },
            () -> {});

        assertTrue("Output collection should be called", outputCollected.get());
        assertTrue("Result processing should be called", resultProcessed.get());
    }

    // ========================================================================
    //  Integration scenario tests
    // ========================================================================

    @Test
    public void testScenario_backgroundTask_normalExit() {
        // Simulates: AppShell finishes normally → processResult → SUCCESS
        ExecutionCommand command = new ExecutionCommand(100);
        command.isPluginExecutionCommand = false;
        command.setState(ExecutionState.EXECUTING);
        command.setState(ExecutionState.EXECUTED);
        command.resultData.exitCode = 0;
        command.commandLabel = "Background Task";

        // No client (internal command, not plugin)
        ExecutionCommandResultHandler.processResult(null, command, "TestTag", null);

        assertEquals("Background task should reach SUCCESS",
            ExecutionState.SUCCESS, getCurrentState(command));
    }

    @Test
    public void testScenario_pluginCommand_pendingResult() {
        // Simulates: Plugin command with pending result → processResult → callback handles it
        ExecutionCommand command = new ExecutionCommand(101);
        command.isPluginExecutionCommand = true;
        command.setState(ExecutionState.EXECUTING);
        command.setState(ExecutionState.EXECUTED);
        command.resultData.exitCode = 0;
        command.commandLabel = "Plugin Command";

        AtomicBoolean callbackInvoked = new AtomicBoolean(false);
        ExecutionCommandRunner runner = createMockRunner(command);
        ExecutionCommandRunnerClient client = r -> {
            callbackInvoked.set(true);
            // In real code, this would call TermuxPluginUtils.processPluginExecutionCommandResult
            // and then set SUCCESS
            ExecutionCommand ec = r.getExecutionCommand();
            if (!ec.isStateFailed())
                ec.setState(ExecutionState.SUCCESS);
        };

        ExecutionCommandResultHandler.processResult(runner, null, "TestTag", client);

        assertTrue("Plugin callback should be invoked", callbackInvoked.get());
        assertEquals("Plugin command should reach SUCCESS after callback",
            ExecutionState.SUCCESS, getCurrentState(command));
    }

    @Test
    public void testScenario_foregroundSession_killedByUser() {
        // Simulates: User kills foreground session → killIfExecuting → FAILED + exitCode 137
        ExecutionCommand command = new ExecutionCommand(102);
        command.isPluginExecutionCommand = false;
        command.setState(ExecutionState.EXECUTING);
        command.commandLabel = "Foreground Session";

        AtomicBoolean killCalled = new AtomicBoolean(false);

        ExecutionCommandResultHandler.performKillIfExecuting(
            command, context, "TestTag", "TermuxSession", true,
            () -> command.resultData.stdout.append("partial output"),
            () -> {
                // Result processing
                if (!command.isStateFailed())
                    command.setState(ExecutionState.SUCCESS);
            },
            () -> killCalled.set(true));

        assertEquals("Killed session should be FAILED",
            ExecutionState.FAILED, getCurrentState(command));
        assertEquals("Exit code should be 137",
            Integer.valueOf(137), command.resultData.exitCode);
        assertTrue("Kill should be called", killCalled.get());
    }

    @Test
    public void testScenario_pluginCommand_creationFails() {
        // Simulates: Plugin command fails before shell is created → processResult with null runner
        ExecutionCommand command = new ExecutionCommand(103);
        command.isPluginExecutionCommand = true;
        command.setState(ExecutionState.PRE_EXECUTION);
        command.setStateFailed(com.termux.shared.errors.Errno.ERRNO_FAILED.getCode(), "Creation failed");
        command.commandLabel = "Failed Creation";

        // No runner created, but client is available for error handling
        AtomicBoolean callbackInvoked = new AtomicBoolean(false);
        ExecutionCommandRunnerClient client = r -> callbackInvoked.set(true);

        ExecutionCommandResultHandler.processResult(null, command, "TestTag", client);

        // With null runner, the callback is NOT invoked (handler goes to else branch)
        assertFalse("Callback should NOT be invoked when runner is null",
            callbackInvoked.get());
        // State should remain FAILED (not set to SUCCESS since it's already failed)
        assertEquals("State should remain FAILED",
            ExecutionState.FAILED, getCurrentState(command));
    }

    // ========================================================================
    //  Helpers
    // ========================================================================

    private ExecutionCommandRunner createMockRunner(ExecutionCommand command) {
        return new ExecutionCommandRunner() {
            @Override
            public ExecutionCommand getExecutionCommand() {
                return command;
            }

            @Override
            public void killIfExecuting(Context context, boolean processResult) {
                // Not used in processResult tests
            }
        };
    }

    private ExecutionState getCurrentState(ExecutionCommand command) {
        // Use reflection or the available state methods to check current state
        // The ExecutionCommand doesn't expose currentState directly, but we can
        // infer it from the available methods
        if (command.isSuccessful()) return ExecutionState.SUCCESS;
        if (command.isStateFailed()) return ExecutionState.FAILED;
        if (command.isExecuting()) return ExecutionState.EXECUTING;
        if (command.hasExecuted()) return ExecutionState.EXECUTED;
        return ExecutionState.PRE_EXECUTION;
    }
}
