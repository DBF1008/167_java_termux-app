package com.termux.app.install;

import com.termux.shared.errors.Error;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static com.termux.app.install.InstallState.*;
import static org.junit.Assert.*;

/**
 * Pure unit tests for {@link InstallCoordinator} orchestration logic.
 * Uses fake steps to test pipeline flow, failure handling, retry,
 * concurrent prevention, rollback, and skip behavior.
 */
public class InstallCoordinatorStateMachineTest {

    @Rule
    public TemporaryFolder tempFolder = new TemporaryFolder();

    private InstallContext testContext;

    @Before
    public void setUp() throws Exception {
        File root = tempFolder.getRoot();
        testContext = new InstallContext(
            null, // No Android context needed for pure logic tests
            new File(root, "files").getAbsolutePath(),
            new File(root, "prefix").getAbsolutePath(),
            new File(root, "prefix"),
            new File(root, "staging").getAbsolutePath(),
            new File(root, "staging"),
            new File(root, "storage").getAbsolutePath(),
            new File(root, "storage"),
            new File(root, "home").getAbsolutePath()
        );
    }

    // ─── Successful pipeline ───────────────────────────────────

    @Test
    public void successfulPipelineTransitionsThroughAllStates() {
        RecordingListener listener = new RecordingListener();
        List<InstallStep> steps = Arrays.asList(
            new FakeStep("step1", VALIDATING, null),
            new FakeStep("step2", PREPARING, null),
            new FakeStep("step3", EXTRACTING, null)
        );
        InstallCoordinator coordinator = createCoordinator(steps);
        coordinator.addListener(listener);

        Error result = coordinator.runFullInstall();

        assertNull(result);
        assertEquals(SUCCESS, coordinator.getCurrentState());
        assertTrue(listener.stateHistory.contains(VALIDATING));
        assertTrue(listener.stateHistory.contains(PREPARING));
        assertTrue(listener.stateHistory.contains(EXTRACTING));
        assertTrue(listener.stateHistory.contains(SUCCESS));
    }

    // ─── Failed step stops pipeline ────────────────────────────

    @Test
    public void failedStepStopsPipeline() {
        Error expectedError = new Error(999, "boom");
        List<InstallStep> steps = Arrays.asList(
            new FakeStep("ok", VALIDATING, null),
            new FakeStep("fail", PREPARING, expectedError),
            new FakeStep("never", EXTRACTING, null)
        );
        InstallCoordinator coordinator = createCoordinator(steps);

        Error result = coordinator.runFullInstall();

        assertNotNull(result);
        assertEquals("boom", result.getMessage());
        assertEquals(FAILED, coordinator.getCurrentState());
    }

    @Test
    public void failedStep_preventsSubsequentSteps() {
        FakeStep step1 = new FakeStep("ok", VALIDATING, null);
        FakeStep step2 = new FakeStep("fail", PREPARING, new Error(999, "boom"));
        FakeStep step3 = new FakeStep("never", EXTRACTING, null);

        List<InstallStep> steps = Arrays.asList(step1, step2, step3);
        InstallCoordinator coordinator = createCoordinator(steps);

        coordinator.runFullInstall();

        assertTrue(step1.wasExecuted());
        assertTrue(step2.wasExecuted());
        assertFalse("Step after failure should not execute", step3.wasExecuted());
    }

    // ─── Retry: FAILED → IDLE → re-run ────────────────────────

    @Test
    public void retryResetsStateAndReruns() {
        AtomicInteger attempts = new AtomicInteger(0);
        FakeStep flakyStep = new FakeStep("flaky", VALIDATING,
            () -> attempts.incrementAndGet() == 1
                ? new Error(999, "transient") : null);

        List<InstallStep> steps = Collections.singletonList(flakyStep);
        InstallCoordinator coordinator = createCoordinator(steps);

        // First attempt fails
        Error firstResult = coordinator.runFullInstall();
        assertNotNull(firstResult);
        assertEquals(FAILED, coordinator.getCurrentState());

        // Retry succeeds
        Error retryResult = coordinator.retryFullInstall();
        assertNull(retryResult);
        assertEquals(SUCCESS, coordinator.getCurrentState());
        assertEquals(2, attempts.get());
    }

    @Test
    public void retryFromNonFailedStateReturnsError() {
        FakeStep okStep = new FakeStep("ok", VALIDATING, null);
        InstallCoordinator coordinator = createCoordinator(
            Collections.singletonList(okStep));

        // First run succeeds
        assertNull(coordinator.runFullInstall());
        assertEquals(SUCCESS, coordinator.getCurrentState());

        // Retry from SUCCESS should fail
        Error retryError = coordinator.retryFullInstall();
        assertNotNull(retryError);
        assertTrue(retryError.getMessage().contains("SUCCESS"));
    }

    @Test
    public void retryFromIdleStateReturnsError() {
        InstallCoordinator coordinator = createCoordinator(Collections.emptyList());

        // State is IDLE, retry should fail
        Error retryError = coordinator.retryFullInstall();
        assertNotNull(retryError);
        assertTrue(retryError.getMessage().contains("IDLE"));
    }

    // ─── Concurrent install prevention ─────────────────────────

    @Test
    public void concurrentInstallReturnsAlreadyRunningError() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch proceed = new CountDownLatch(1);

        List<InstallStep> blockingSteps = Collections.singletonList(
            new BlockingStep(started, proceed, VALIDATING)
        );
        InstallCoordinator coordinator = createCoordinator(blockingSteps);

        // Start first install on background thread
        ExecutorService executor = Executors.newSingleThreadExecutor();
        Future<Error> first = executor.submit(coordinator::runFullInstall);

        // Wait for first install to start
        assertTrue("First install should start within 5s",
            started.await(5, TimeUnit.SECONDS));

        // Second install should fail immediately
        Error secondResult = coordinator.runFullInstall();
        assertNotNull(secondResult);
        assertTrue(secondResult.getMessage().contains("already in progress"));

        // Let first install complete
        proceed.countDown();
        assertNull(first.get(5, TimeUnit.SECONDS));

        executor.shutdown();
    }

    // ─── SkippableStep short-circuits pipeline ─────────────────

    @Test
    public void skippableStepShortCircuitsPipeline() {
        SkippableFakeStep skipper = new SkippableFakeStep(
            "prefix-check", VALIDATING, null, true);
        FakeStep shouldNotRun = new FakeStep(
            "should-not-run", PREPARING, null);

        List<InstallStep> steps = Arrays.asList(skipper, shouldNotRun);
        InstallCoordinator coordinator = createCoordinator(steps);

        Error result = coordinator.runFullInstall();

        assertNull(result);
        assertEquals(SUCCESS, coordinator.getCurrentState());
        assertTrue(skipper.wasExecuted());
        assertFalse("Step after skip should not execute", shouldNotRun.wasExecuted());
    }

    // ─── Rollback: reverse order on failure ────────────────────

    @Test
    public void rollbackCalledInReverseOrderOnFailure() {
        List<String> rollbackOrder = new ArrayList<>();

        List<InstallStep> steps = Arrays.asList(
            new TrackingRollbackStep("a", VALIDATING, null, rollbackOrder),
            new TrackingRollbackStep("b", PREPARING, null, rollbackOrder),
            new TrackingRollbackStep("c", EXTRACTING, new Error(999, "fail"), rollbackOrder)
        );
        InstallCoordinator coordinator = createCoordinator(steps);

        Error result = coordinator.runFullInstall();

        assertNotNull(result);
        assertEquals(FAILED, coordinator.getCurrentState());
        // Rollback should be called for "b" then "a" (reverse of execution order)
        // "c" is not rolled back because it failed (only already-completed steps roll back)
        assertEquals(Arrays.asList("b", "a"), rollbackOrder);
    }

    @Test
    public void rollbackExceptionDoesNotStopOtherRollbacks() {
        List<String> rollbackOrder = new ArrayList<>();

        List<InstallStep> steps = Arrays.asList(
            new TrackingRollbackStep("a", VALIDATING, null, rollbackOrder),
            new ThrowingRollbackStep("b", PREPARING, null, rollbackOrder),
            new TrackingRollbackStep("c", EXTRACTING, new Error(999, "fail"), rollbackOrder)
        );
        InstallCoordinator coordinator = createCoordinator(steps);

        coordinator.runFullInstall();

        // Even though "b" throws during rollback, "a" should still be called
        assertTrue("a's rollback should execute", rollbackOrder.contains("a"));
    }

    // ─── Listener notifications ────────────────────────────────

    @Test
    public void listenerReceivesInstallCompleteCallback() {
        RecordingListener listener = new RecordingListener();

        List<InstallStep> steps = Collections.singletonList(
            new FakeStep("ok", VALIDATING, null));
        InstallCoordinator coordinator = createCoordinator(steps);
        coordinator.addListener(listener);

        coordinator.runFullInstall();

        assertTrue(listener.installCompleteSuccess);
        assertNull(listener.installCompleteError);
    }

    @Test
    public void listenerReceivesInstallCompleteWithErrorCallback() {
        RecordingListener listener = new RecordingListener();
        Error expected = new Error(999, "fail");

        List<InstallStep> steps = Collections.singletonList(
            new FakeStep("fail", VALIDATING, expected));
        InstallCoordinator coordinator = createCoordinator(steps);
        coordinator.addListener(listener);

        coordinator.runFullInstall();

        assertFalse(listener.installCompleteSuccess);
        assertNotNull(listener.installCompleteError);
    }

    @Test
    public void listenerExceptionDoesNotBreakPipeline() {
        InstallStateListener badListener = new InstallStateListener() {
            @Override
            public void onStateChanged(InstallState newState) {
                throw new RuntimeException("listener boom");
            }
        };

        List<InstallStep> steps = Collections.singletonList(
            new FakeStep("ok", VALIDATING, null));
        InstallCoordinator coordinator = createCoordinator(steps);
        coordinator.addListener(badListener);

        // Should not throw despite bad listener
        Error result = coordinator.runFullInstall();
        assertNull(result);
    }

    // ─── Storage-only pipeline ─────────────────────────────────

    @Test
    public void storageSetupRunsIndependentSteps() {
        FakeStep storageStep = new FakeStep("storage", POST_INSTALL, null);
        List<InstallStep> storageSteps = Collections.singletonList(storageStep);

        InstallCoordinator coordinator = new InstallCoordinator(
            testContext, Collections.emptyList(), storageSteps);

        Error result = coordinator.runStorageSetup();

        assertNull(result);
        assertEquals(SUCCESS, coordinator.getCurrentState());
        assertTrue(storageStep.wasExecuted());
    }

    // ─── Last error tracking ──────────────────────────────────

    @Test
    public void lastErrorIsTracked() {
        Error expected = new Error(999, "tracked");
        List<InstallStep> steps = Collections.singletonList(
            new FakeStep("fail", VALIDATING, expected));
        InstallCoordinator coordinator = createCoordinator(steps);

        coordinator.runFullInstall();

        assertNotNull(coordinator.getLastError());
        assertEquals("tracked", coordinator.getLastError().getMessage());
    }

    // ─── isRunning() ──────────────────────────────────────────

    @Test
    public void isRunningReturnsFalseWhenIdle() {
        InstallCoordinator coordinator = createCoordinator(Collections.emptyList());
        assertFalse(coordinator.isRunning());
    }

    // ─── Helper methods ────────────────────────────────────────

    private InstallCoordinator createCoordinator(List<InstallStep> steps) {
        return new InstallCoordinator(testContext, steps, Collections.emptyList());
    }

    // ─── Test Helper Classes ───────────────────────────────────

    /**
     * A configurable fake step that returns a fixed result.
     */
    static class FakeStep implements InstallStep {
        private final String name;
        private final InstallState resultingState;
        private final ErrorSupplier resultSupplier;
        private boolean executed = false;

        FakeStep(String name, InstallState state, Error result) {
            this.name = name;
            this.resultingState = state;
            this.resultSupplier = () -> result;
        }

        FakeStep(String name, InstallState state, ErrorSupplier supplier) {
            this.name = name;
            this.resultingState = state;
            this.resultSupplier = supplier;
        }

        @Override
        public String getName() { return name; }

        @Override
        public InstallState getResultingState() { return resultingState; }

        @Override
        public Error execute(InstallContext context) {
            executed = true;
            return resultSupplier.get();
        }

        boolean wasExecuted() { return executed; }
    }

    @FunctionalInterface
    interface ErrorSupplier {
        Error get();
    }

    /**
     * A step that signals a latch on execute, then waits for another latch.
     */
    static class BlockingStep implements InstallStep {
        private final CountDownLatch started;
        private final CountDownLatch proceed;
        private final InstallState state;

        BlockingStep(CountDownLatch started, CountDownLatch proceed, InstallState state) {
            this.started = started;
            this.proceed = proceed;
            this.state = state;
        }

        @Override
        public String getName() { return "blocking"; }

        @Override
        public InstallState getResultingState() { return state; }

        @Override
        public Error execute(InstallContext context) {
            started.countDown();
            try {
                proceed.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return null;
        }
    }

    /**
     * A step that records when its rollback is called.
     */
    static class TrackingRollbackStep implements InstallStep {
        private final String name;
        private final InstallState state;
        private final Error result;
        private final List<String> rollbackLog;

        TrackingRollbackStep(String name, InstallState state, Error result,
                             List<String> rollbackLog) {
            this.name = name;
            this.state = state;
            this.result = result;
            this.rollbackLog = rollbackLog;
        }

        @Override
        public String getName() { return name; }

        @Override
        public InstallState getResultingState() { return state; }

        @Override
        public Error execute(InstallContext context) { return result; }

        @Override
        public Error rollback(InstallContext context) {
            rollbackLog.add(name);
            return null;
        }
    }

    /**
     * A step whose rollback throws an exception.
     */
    static class ThrowingRollbackStep implements InstallStep {
        private final String name;
        private final InstallState state;
        private final Error result;
        private final List<String> rollbackLog;

        ThrowingRollbackStep(String name, InstallState state, Error result,
                             List<String> rollbackLog) {
            this.name = name;
            this.state = state;
            this.result = result;
            this.rollbackLog = rollbackLog;
        }

        @Override
        public String getName() { return name; }

        @Override
        public InstallState getResultingState() { return state; }

        @Override
        public Error execute(InstallContext context) { return result; }

        @Override
        public Error rollback(InstallContext context) {
            rollbackLog.add(name);
            throw new RuntimeException("rollback boom from " + name);
        }
    }

    /**
     * Records listener callbacks.
     */
    static class RecordingListener implements InstallStateListener {
        final List<InstallState> stateHistory = new ArrayList<>();
        boolean installCompleteSuccess = false;
        Error installCompleteError = null;
        boolean installCompleteCalled = false;

        @Override
        public void onStateChanged(InstallState newState) {
            stateHistory.add(newState);
        }

        @Override
        public void onInstallComplete(boolean success, Error error) {
            installCompleteCalled = true;
            installCompleteSuccess = success;
            installCompleteError = error;
        }
    }

    /**
     * A skippable fake step.
     */
    static class SkippableFakeStep extends FakeStep implements SkippableStep {
        private final boolean skip;

        SkippableFakeStep(String name, InstallState state, Error result, boolean skip) {
            super(name, state, result);
            this.skip = skip;
        }

        @Override
        public boolean shouldSkipRemaining() { return skip; }
    }
}
