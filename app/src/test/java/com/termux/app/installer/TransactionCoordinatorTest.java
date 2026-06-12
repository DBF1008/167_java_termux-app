package com.termux.app.installer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.termux.shared.errors.Error;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Engine-level tests for {@link TransactionCoordinator} using simple recording steps, independent of
 * any real filesystem or Android behavior. Runs under Robolectric only so the coordinator's
 * {@code android.util.Log}-backed logging does not throw.
 */
@RunWith(RobolectricTestRunner.class)
public class TransactionCoordinatorTest {

    private TransactionCoordinator coordinator;
    private InstallContext context;
    private List<String> executionLog;
    private List<String> rollbackLog;

    @Before
    public void setUp() {
        coordinator = new TransactionCoordinator();
        // Recording steps ignore the context; a minimal one (no Android Context) suffices.
        context = new InstallContext(null, "/prefix", "/staging", "/home", "/env", "/storage",
            new LocalInstallEnvironment(), "test");
        executionLog = new ArrayList<>();
        rollbackLog = new ArrayList<>();
    }

    @Test
    public void runsAllStepsInOrderOnSuccess() {
        InstallTransaction transaction = new InstallTransaction("t",
            Arrays.asList(step("a"), step("b"), step("c")));
        CapturingListener listener = new CapturingListener();

        TransactionState result = coordinator.execute(transaction, context, listener);

        assertEquals(TransactionState.SUCCEEDED, result);
        assertEquals(Arrays.asList("a", "b", "c"), executionLog);
        assertTrue(rollbackLog.isEmpty());
        assertTrue(listener.succeeded);
        assertFalse(listener.failed);
        assertTrue(listener.observedStates.contains(TransactionState.RUNNING));
        assertTrue(listener.observedStates.contains(TransactionState.SUCCEEDED));
    }

    @Test
    public void stopsAtFirstFailingStep() {
        Error sentinel = InstallationErrno.ERRNO_NO_SYMLINKS_FOUND.getError();
        InstallTransaction transaction = new InstallTransaction("t",
            Arrays.asList(step("a"), failingStep("b", sentinel), step("c")));
        CapturingListener listener = new CapturingListener();

        TransactionState result = coordinator.execute(transaction, context, listener);

        assertEquals(TransactionState.FAILED, result);
        assertEquals(Arrays.asList("a", "b"), executionLog); // "c" never ran
        assertSame(sentinel, transaction.getError());
        assertTrue(listener.failed);
        assertSame(sentinel, listener.failError);
    }

    @Test
    public void rollsBackExecutedStepsInReverseOnFailure() {
        Error sentinel = InstallationErrno.ERRNO_NO_SYMLINKS_FOUND.getError();
        InstallTransaction transaction = new InstallTransaction("t",
            Arrays.asList(step("a"), step("b"), failingStep("c", sentinel)));

        coordinator.execute(transaction, context, null);

        // The failing step and all earlier steps are rolled back, in reverse order.
        assertEquals(Arrays.asList("c", "b", "a"), rollbackLog);
    }

    @Test
    public void rollsBackOnlyPreCommitStepsWhenFailureBeforeBoundary() {
        Error sentinel = InstallationErrno.ERRNO_NO_SYMLINKS_FOUND.getError();
        // commitBoundaryIndex = 2: steps 0,1 are pre-commit; 2,3 are post-commit.
        InstallTransaction transaction = new InstallTransaction("t",
            Arrays.asList(step("a"), failingStep("b", sentinel), step("c"), step("d")), 2);

        coordinator.execute(transaction, context, null);

        assertEquals(Arrays.asList("a", "b"), executionLog); // c, d never ran
        assertEquals(Arrays.asList("b", "a"), rollbackLog);
    }

    @Test
    public void doesNotRollBackWhenFailureAtOrAfterCommitBoundary() {
        Error sentinel = InstallationErrno.ERRNO_PREFIX_COMMIT_FAILED.getError("s", "p");
        // commitBoundaryIndex = 2: a failure at index 2 must not unwind the committed work.
        InstallTransaction transaction = new InstallTransaction("t",
            Arrays.asList(step("a"), step("b"), failingStep("c", sentinel), step("d")), 2);

        TransactionState result = coordinator.execute(transaction, context, null);

        assertEquals(TransactionState.FAILED, result);
        assertEquals(Arrays.asList("a", "b", "c"), executionLog); // d never ran
        assertTrue("no rollback should run after the commit boundary", rollbackLog.isEmpty());
    }

    @Test
    public void incrementsAttemptAcrossRuns() {
        InstallTransaction transaction = new InstallTransaction("t", Arrays.asList(step("a")));

        coordinator.execute(transaction, context, null);
        assertEquals(1, transaction.getAttempt());

        coordinator.execute(transaction, context, null);
        assertEquals(2, transaction.getAttempt());
    }

    @Test
    public void wrapsThrownExceptionIntoError() {
        InstallTransaction transaction = new InstallTransaction("t",
            Arrays.asList(throwingStep("a", new IllegalStateException("boom"))));
        CapturingListener listener = new CapturingListener();

        TransactionState result = coordinator.execute(transaction, context, listener);

        assertEquals(TransactionState.FAILED, result);
        assertTrue(listener.failed);
        assertEquals(InstallationErrno.TYPE, transaction.getError().getType());
    }

    // --- helpers ---

    private RecordingStep step(String name) {
        return new RecordingStep(name, executionLog, rollbackLog, null, null);
    }

    private RecordingStep failingStep(String name, Error failWith) {
        return new RecordingStep(name, executionLog, rollbackLog, failWith, null);
    }

    private RecordingStep throwingStep(String name, RuntimeException throwWith) {
        return new RecordingStep(name, executionLog, rollbackLog, null, throwWith);
    }

    private static final class RecordingStep implements InstallStep {
        private final String name;
        private final List<String> executionLog;
        private final List<String> rollbackLog;
        private final Error failWith;
        private final RuntimeException throwWith;

        RecordingStep(String name, List<String> executionLog, List<String> rollbackLog, Error failWith, RuntimeException throwWith) {
            this.name = name;
            this.executionLog = executionLog;
            this.rollbackLog = rollbackLog;
            this.failWith = failWith;
            this.throwWith = throwWith;
        }

        @Override
        public String getName() {
            return name;
        }

        @Override
        public Error execute(InstallContext context) {
            executionLog.add(name);
            if (throwWith != null)
                throw throwWith;
            return failWith;
        }

        @Override
        public void rollback(InstallContext context) {
            rollbackLog.add(name);
        }
    }

    private static final class CapturingListener implements TransactionListener {
        final List<TransactionState> observedStates = new ArrayList<>();
        boolean succeeded;
        boolean failed;
        Error failError;

        @Override
        public void onStateChanged(InstallTransaction transaction, TransactionState previous, TransactionState current) {
            observedStates.add(current);
        }

        @Override
        public void onSucceeded(InstallTransaction transaction) {
            succeeded = true;
        }

        @Override
        public void onFailed(InstallTransaction transaction, Error error) {
            failed = true;
            failError = error;
        }
    }
}
