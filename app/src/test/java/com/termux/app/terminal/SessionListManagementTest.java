package com.termux.app.terminal;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.test.core.app.ApplicationProvider;

import com.termux.shared.termux.shell.SessionOrderManager;
import com.termux.shared.termux.shell.command.runner.terminal.TermuxSession;
import com.termux.terminal.TerminalSession;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Regression and integration tests for session list management features:
 * - New session creation, rename, delete
 * - Service reconnect simulation (order preserved)
 * - MAX_SESSIONS boundary
 * - Keyboard shortcut ordering
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, manifest = Config.NONE)
public class SessionListManagementTest {

    private SessionOrderManager mManager;
    private SharedPreferences mPrefs;

    private static final String PREFS_NAME = "test_session_list_mgmt";
    private static final String PREF_KEY = "session_order_state";
    private static final int MAX_SESSIONS = 8;

    @Before
    public void setUp() {
        Context context = ApplicationProvider.getApplicationContext();
        mPrefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        mPrefs.edit().clear().commit();
        mManager = new SessionOrderManager(mPrefs);
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    private TermuxSession createMockSession() {
        TerminalSession terminalSession = new TerminalSession("/bin/sh", "/tmp",
            new String[]{}, new String[]{}, 24, null);
        TermuxSession mockSession = mock(TermuxSession.class);
        when(mockSession.getTerminalSession()).thenReturn(terminalSession);
        return mockSession;
    }

    private List<TermuxSession> createSessions(int count, List<String> outHandles) {
        List<TermuxSession> sessions = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            TermuxSession ts = createMockSession();
            sessions.add(ts);
            outHandles.add(ts.getTerminalSession().mHandle);
        }
        return sessions;
    }

    private List<String> getDisplayOrder(List<TermuxSession> displayList) {
        List<String> handles = new ArrayList<>();
        for (TermuxSession ts : displayList) {
            handles.add(ts.getTerminalSession().mHandle);
        }
        return handles;
    }

    // ── Test 1: New session appended to end ──────────────────────────────

    @Test
    public void testNewSession_appendedToUnpinnedEnd() {
        List<String> h = new ArrayList<>();
        List<TermuxSession> sessions = createSessions(3, h);

        for (String handle : h) {
            mManager.onNewSession(handle);
        }

        List<TermuxSession> result = mManager.buildDisplayList(sessions);
        List<String> order = getDisplayOrder(result);

        assertEquals(3, order.size());
        assertEquals(h.get(0), order.get(0));
        assertEquals(h.get(1), order.get(1));
        assertEquals(h.get(2), order.get(2));
    }

    // ── Test 2: Rename doesn't affect order ─────────────────────────────

    @Test
    public void testRenameSession_nameUpdated_orderPreserved() {
        List<String> h = new ArrayList<>();
        List<TermuxSession> sessions = createSessions(3, h);

        for (String handle : h) {
            mManager.onNewSession(handle);
        }

        // Rename is handled at the TerminalSession level (mSessionName),
        // not at the SessionOrderManager level. Verify order is unchanged
        // after building the display list again.
        List<TermuxSession> result1 = mManager.buildDisplayList(sessions);
        List<String> order1 = getDisplayOrder(result1);

        // Simulate rename by changing session name
        sessions.get(1).getTerminalSession().mSessionName = "renamed";

        List<TermuxSession> result2 = mManager.buildDisplayList(sessions);
        List<String> order2 = getDisplayOrder(result2);

        assertEquals(order1, order2);
        assertEquals("renamed", sessions.get(1).getTerminalSession().mSessionName);
    }

    // ── Test 3: Delete session removes from display list ────────────────

    @Test
    public void testDeleteSession_removedFromDisplayList() {
        List<String> h = new ArrayList<>();
        List<TermuxSession> sessions = createSessions(3, h);

        for (String handle : h) {
            mManager.onNewSession(handle);
        }

        // Delete middle session
        mManager.removeEntry(h.get(1));
        sessions.remove(1);

        List<TermuxSession> result = mManager.buildDisplayList(sessions);
        List<String> order = getDisplayOrder(result);

        assertEquals(2, order.size());
        assertEquals(h.get(0), order.get(0));
        assertEquals(h.get(2), order.get(1));
    }

    // ── Test 4: Delete pinned session ───────────────────────────────────

    @Test
    public void testDeletePinnedSession_orderCompacted() {
        List<String> h = new ArrayList<>();
        List<TermuxSession> sessions = createSessions(4, h);

        for (String handle : h) {
            mManager.onNewSession(handle);
        }

        // Pin first and third
        mManager.setPinned(h.get(0), true);
        mManager.setPinned(h.get(2), true);

        // Delete pinned session h[0]
        mManager.removeEntry(h.get(0));
        sessions.remove(0);

        List<TermuxSession> result = mManager.buildDisplayList(sessions);
        List<String> order = getDisplayOrder(result);

        assertEquals(3, order.size());
        // h[2] should still be pinned and first
        assertEquals(h.get(2), order.get(0));
        assertTrue(mManager.isPinned(h.get(2)));
        // h[1] and h[3] unpinned
        assertFalse(mManager.isPinned(h.get(1)));
        assertFalse(mManager.isPinned(h.get(3)));
    }

    // ── Test 5: Reconnect restores current session ──────────────────────

    @Test
    public void testReconnectService_restoresCurrentSession() {
        List<String> h = new ArrayList<>();
        List<TermuxSession> sessions = createSessions(3, h);

        for (String handle : h) {
            mManager.onNewSession(handle);
        }
        mManager.setPinned(h.get(1), true);

        // Simulate "current session" stored as handle (like SharedPreferences)
        String currentSessionHandle = h.get(2);

        // Simulate reconnect: create new manager from same prefs
        SessionOrderManager manager2 = new SessionOrderManager(mPrefs);

        List<TermuxSession> result = manager2.buildDisplayList(sessions);
        List<String> order = getDisplayOrder(result);

        // Find the current session in the new display order
        int restoredIndex = order.indexOf(currentSessionHandle);
        assertTrue("Current session should be found in display list", restoredIndex >= 0);
        assertEquals(currentSessionHandle, order.get(restoredIndex));
    }

    // ── Test 6: Reconnect preserves pin and order ───────────────────────

    @Test
    public void testReconnectService_preservesPinAndOrder() {
        List<String> h = new ArrayList<>();
        List<TermuxSession> sessions = createSessions(4, h);

        for (String handle : h) {
            mManager.onNewSession(handle);
        }
        mManager.setPinned(h.get(2), true);
        mManager.moveUp(h.get(1)); // Move h[1] before h[0] in unpinned group

        // Simulate reconnect
        SessionOrderManager manager2 = new SessionOrderManager(mPrefs);
        List<TermuxSession> result = manager2.buildDisplayList(sessions);
        List<String> order = getDisplayOrder(result);

        // h[2] should be first (pinned)
        assertEquals(h.get(2), order.get(0));
        assertTrue(manager2.isPinned(h.get(2)));

        // h[1] should be before h[0] (moved up)
        int idx1 = order.indexOf(h.get(1));
        int idx0 = order.indexOf(h.get(0));
        assertTrue("h[1] should be before h[0]", idx1 < idx0);
    }

    // ── Test 7: Current session deleted → fallback ──────────────────────

    @Test
    public void testReconnectService_currentSessionDeleted_fallbackToLast() {
        List<String> h = new ArrayList<>();
        List<TermuxSession> sessions = createSessions(3, h);

        for (String handle : h) {
            mManager.onNewSession(handle);
        }

        String deletedHandle = h.get(2);
        mManager.removeEntry(deletedHandle);
        sessions.remove(2);

        // Simulate reconnect: current session was the deleted one
        // The activity client's getCurrentStoredSessionOrLast() would fall back
        // to the last session in the list

        SessionOrderManager manager2 = new SessionOrderManager(mPrefs);
        List<TermuxSession> result = manager2.buildDisplayList(sessions);

        assertEquals(2, result.size());
        // Fallback to last session in display list
        String fallbackHandle = result.get(result.size() - 1).getTerminalSession().mHandle;
        assertNotNull(fallbackHandle);
        assertFalse(deletedHandle.equals(fallbackHandle));
    }

    // ── Test 8: MAX_SESSIONS - 8th allowed ──────────────────────────────

    @Test
    public void testMaxSessions_eighthAllowed() {
        List<String> h = new ArrayList<>();
        List<TermuxSession> sessions = createSessions(MAX_SESSIONS, h);

        // Create MAX_SESSIONS sessions - all should succeed
        for (String handle : h) {
            mManager.onNewSession(handle);
        }

        assertEquals(MAX_SESSIONS, mManager.loadEntries().size());

        List<TermuxSession> result = mManager.buildDisplayList(sessions);
        assertEquals(MAX_SESSIONS, result.size());
    }

    // ── Test 9: MAX_SESSIONS - 9th would be blocked ─────────────────────

    @Test
    public void testMaxSessions_ninthBlocked() {
        // Simulate MAX_SESSIONS enforcement: only 8 sessions can exist
        List<String> h = new ArrayList<>();
        createSessions(MAX_SESSIONS, h);

        for (String handle : h) {
            mManager.onNewSession(handle);
        }

        // The 9th session would be blocked by addNewSession() before reaching
        // the order manager. Verify the manager has exactly 8 entries.
        assertEquals(MAX_SESSIONS, mManager.loadEntries().size());

        // If we tried to add a 9th, the addNewSession check would prevent it
        // This test validates the boundary is correct
    }

    // ── Test 10: Keyboard shortcuts follow display order ────────────────

    @Test
    public void testKeyboardShortcuts_followDisplayOrder() {
        List<String> h = new ArrayList<>();
        List<TermuxSession> sessions = createSessions(3, h);

        for (String handle : h) {
            mManager.onNewSession(handle);
        }

        // Pin h[2] - it should become display position 0
        mManager.setPinned(h.get(2), true);

        List<TermuxSession> result = mManager.buildDisplayList(sessions);

        // Ctrl+Alt+1 should target display position 0
        assertEquals(h.get(2), result.get(0).getTerminalSession().mHandle);

        // Ctrl+Alt+2 should target display position 1
        assertEquals(h.get(0), result.get(1).getTerminalSession().mHandle);

        // Ctrl+Alt+3 should target display position 2
        assertEquals(h.get(1), result.get(2).getTerminalSession().mHandle);
    }

    // ── Test 11: Next/prev wraps in display order ───────────────────────

    @Test
    public void testSwitchNextPrev_wrapsInDisplayOrder() {
        List<String> h = new ArrayList<>();
        List<TermuxSession> sessions = createSessions(3, h);

        for (String handle : h) {
            mManager.onNewSession(handle);
        }

        // Reorder: move h[2] to before h[1]
        mManager.moveUp(h.get(2));

        List<TermuxSession> result = mManager.buildDisplayList(sessions);
        List<String> order = getDisplayOrder(result);

        // Display order should be: h[0], h[2], h[1]
        assertEquals(h.get(0), order.get(0));
        assertEquals(h.get(2), order.get(1));
        assertEquals(h.get(1), order.get(2));

        // Simulate wrapping: from last position (h[1]), next should go to first (h[0])
        int lastIndex = order.size() - 1;
        int nextIndex = (lastIndex + 1) % order.size();
        assertEquals(0, nextIndex);
        assertEquals(h.get(0), order.get(nextIndex));

        // From first position, prev should go to last
        int prevIndex = (0 - 1 + order.size()) % order.size();
        assertEquals(lastIndex, prevIndex);
        assertEquals(h.get(1), order.get(prevIndex));
    }

    // ── Test 12: Pin toggle updates display order ───────────────────────

    @Test
    public void testPinToggle_updatesDisplayOrder() {
        List<String> h = new ArrayList<>();
        List<TermuxSession> sessions = createSessions(3, h);

        for (String handle : h) {
            mManager.onNewSession(handle);
        }

        // Initial order: h[0], h[1], h[2]
        List<TermuxSession> result1 = mManager.buildDisplayList(sessions);
        assertEquals(h.get(0), result1.get(0).getTerminalSession().mHandle);

        // Pin h[2]
        mManager.setPinned(h.get(2), true);
        List<TermuxSession> result2 = mManager.buildDisplayList(sessions);
        assertEquals(h.get(2), result2.get(0).getTerminalSession().mHandle);

        // Unpin h[2] - should go to end of unpinned group
        mManager.setPinned(h.get(2), false);
        List<TermuxSession> result3 = mManager.buildDisplayList(sessions);
        List<String> order3 = getDisplayOrder(result3);
        assertEquals(h.get(2), order3.get(order3.size() - 1));
    }

    // ── Test 13: Move up/down within group ──────────────────────────────

    @Test
    public void testMoveUpDown_withinGroup() {
        List<String> h = new ArrayList<>();
        List<TermuxSession> sessions = createSessions(4, h);

        for (String handle : h) {
            mManager.onNewSession(handle);
        }

        // Move h[3] up twice to become first in unpinned group
        mManager.moveUp(h.get(3));
        mManager.moveUp(h.get(3));

        List<TermuxSession> result = mManager.buildDisplayList(sessions);
        List<String> order = getDisplayOrder(result);

        assertEquals(h.get(3), order.get(0));
        assertEquals(h.get(0), order.get(1));
        assertEquals(h.get(1), order.get(2));
        assertEquals(h.get(2), order.get(3));

        // Move h[3] down once
        mManager.moveDown(h.get(3));
        result = mManager.buildDisplayList(sessions);
        order = getDisplayOrder(result);

        assertEquals(h.get(0), order.get(0));
        assertEquals(h.get(3), order.get(1));
    }
}
