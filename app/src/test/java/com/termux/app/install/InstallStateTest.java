package com.termux.app.install;

import org.junit.Test;

import static com.termux.app.install.InstallState.*;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Pure unit tests for {@link InstallState} transition rules.
 * No Android dependencies required.
 */
public class InstallStateTest {

    // ─── Valid forward transitions ─────────────────────────────

    @Test
    public void validForwardTransitions() {
        assertTrue("IDLE → VALIDATING", isValidTransition(IDLE, VALIDATING));
        assertTrue("VALIDATING → PREPARING", isValidTransition(VALIDATING, PREPARING));
        assertTrue("PREPARING → EXTRACTING", isValidTransition(PREPARING, EXTRACTING));
        assertTrue("EXTRACTING → FINALIZING", isValidTransition(EXTRACTING, FINALIZING));
        assertTrue("FINALIZING → POST_INSTALL", isValidTransition(FINALIZING, POST_INSTALL));
        assertTrue("POST_INSTALL → SUCCESS", isValidTransition(POST_INSTALL, SUCCESS));
    }

    // ─── Shortcut: prefix already exists ───────────────────────

    @Test
    public void validatingCanShortcutToSuccess() {
        assertTrue("VALIDATING → SUCCESS (skip)", isValidTransition(VALIDATING, SUCCESS));
    }

    // ─── Failure transitions ───────────────────────────────────

    @Test
    public void anyNonTerminalCanTransitionToFailed() {
        assertTrue("IDLE → FAILED", isValidTransition(IDLE, FAILED));
        assertTrue("VALIDATING → FAILED", isValidTransition(VALIDATING, FAILED));
        assertTrue("PREPARING → FAILED", isValidTransition(PREPARING, FAILED));
        assertTrue("EXTRACTING → FAILED", isValidTransition(EXTRACTING, FAILED));
        assertTrue("FINALIZING → FAILED", isValidTransition(FINALIZING, FAILED));
        assertTrue("POST_INSTALL → FAILED", isValidTransition(POST_INSTALL, FAILED));
    }

    // ─── Retry: FAILED → IDLE ─────────────────────────────────

    @Test
    public void failedCanResetToIdle() {
        assertTrue("FAILED → IDLE (retry reset)", isValidTransition(FAILED, IDLE));
    }

    // ─── Terminal states ───────────────────────────────────────

    @Test
    public void successIsTerminal_noOutgoingTransitions() {
        assertFalse("SUCCESS → IDLE", isValidTransition(SUCCESS, IDLE));
        assertFalse("SUCCESS → FAILED", isValidTransition(SUCCESS, FAILED));
        assertFalse("SUCCESS → VALIDATING", isValidTransition(SUCCESS, VALIDATING));
        assertFalse("SUCCESS → PREPARING", isValidTransition(SUCCESS, PREPARING));
    }

    @Test
    public void failedCannotGoForwardDirectly() {
        assertFalse("FAILED → VALIDATING", isValidTransition(FAILED, VALIDATING));
        assertFalse("FAILED → PREPARING", isValidTransition(FAILED, PREPARING));
        assertFalse("FAILED → SUCCESS", isValidTransition(FAILED, SUCCESS));
    }

    // ─── Backward transitions rejected ─────────────────────────

    @Test
    public void backwardTransitionsRejected() {
        assertFalse("PREPARING → VALIDATING", isValidTransition(PREPARING, VALIDATING));
        assertFalse("EXTRACTING → PREPARING", isValidTransition(EXTRACTING, PREPARING));
        assertFalse("FINALIZING → EXTRACTING", isValidTransition(FINALIZING, EXTRACTING));
        assertFalse("POST_INSTALL → FINALIZING", isValidTransition(POST_INSTALL, FINALIZING));
    }

    // ─── Self-transitions rejected ─────────────────────────────

    @Test
    public void selfTransitionsRejected() {
        for (InstallState s : InstallState.values()) {
            assertFalse("Self-transition should be rejected for " + s.getName(),
                isValidTransition(s, s));
        }
    }

    // ─── Invalid cross-transitions ─────────────────────────────

    @Test
    public void invalidCrossTransitions() {
        assertFalse("IDLE → PREPARING", isValidTransition(IDLE, PREPARING));
        assertFalse("IDLE → EXTRACTING", isValidTransition(IDLE, EXTRACTING));
        assertFalse("IDLE → SUCCESS", isValidTransition(IDLE, SUCCESS));
        assertFalse("VALIDATING → EXTRACTING", isValidTransition(VALIDATING, EXTRACTING));
        assertFalse("PREPARING → FINALIZING", isValidTransition(PREPARING, FINALIZING));
        assertFalse("EXTRACTING → POST_INSTALL", isValidTransition(EXTRACTING, POST_INSTALL));
    }

    // ─── isTerminal() ──────────────────────────────────────────

    @Test
    public void isTerminalCorrect() {
        assertTrue("SUCCESS is terminal", SUCCESS.isTerminal());
        assertTrue("FAILED is terminal", FAILED.isTerminal());
        assertFalse("IDLE is not terminal", IDLE.isTerminal());
        assertFalse("VALIDATING is not terminal", VALIDATING.isTerminal());
        assertFalse("PREPARING is not terminal", PREPARING.isTerminal());
        assertFalse("EXTRACTING is not terminal", EXTRACTING.isTerminal());
        assertFalse("FINALIZING is not terminal", FINALIZING.isTerminal());
        assertFalse("POST_INSTALL is not terminal", POST_INSTALL.isTerminal());
    }

    // ─── Full happy-path sequence ──────────────────────────────

    @Test
    public void fullHappyPathSequenceIsValid() {
        InstallState[] path = {IDLE, VALIDATING, PREPARING, EXTRACTING, FINALIZING, POST_INSTALL, SUCCESS};
        for (int i = 0; i < path.length - 1; i++) {
            assertTrue(path[i].getName() + " → " + path[i + 1].getName(),
                isValidTransition(path[i], path[i + 1]));
        }
    }

    // ─── Retry sequence ────────────────────────────────────────

    @Test
    public void retrySequenceIsValid() {
        // Install fails, then retries successfully
        InstallState[] path = {
            IDLE, VALIDATING, PREPARING, EXTRACTING, FAILED,  // first attempt fails
            IDLE, VALIDATING, PREPARING, EXTRACTING, FINALIZING, POST_INSTALL, SUCCESS  // retry
        };
        for (int i = 0; i < path.length - 1; i++) {
            assertTrue(path[i].getName() + " → " + path[i + 1].getName(),
                isValidTransition(path[i], path[i + 1]));
        }
    }
}
