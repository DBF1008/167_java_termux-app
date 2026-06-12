package com.termux.shared.termux.shell;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.test.core.app.ApplicationProvider;

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
 * Unit tests for {@link SessionOrderManager}.
 * Uses Robolectric for SharedPreferences and Mockito for TermuxSession mocks.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, manifest = Config.NONE)
public class SessionOrderManagerTest {

    private SessionOrderManager mManager;
    private SharedPreferences mPrefs;

    private static final String PREFS_NAME = "test_session_order_prefs";
    private static final String PREF_KEY = "session_order_state";

    @Before
    public void setUp() {
        Context context = ApplicationProvider.getApplicationContext();
        mPrefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        mPrefs.edit().clear().commit();
        mManager = new SessionOrderManager(mPrefs);
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    /** Create a mock TermuxSession wrapping a real TerminalSession. */
    private TermuxSession createMockSession() {
        TerminalSession terminalSession = new TerminalSession("/bin/sh", "/tmp",
            new String[]{}, new String[]{}, 24, null);
        TermuxSession mockSession = mock(TermuxSession.class);
        when(mockSession.getTerminalSession()).thenReturn(terminalSession);
        return mockSession;
    }

    /** Create N mock sessions, filling outHandles with their auto-generated handles. */
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

    // ── Test 1: Empty sessions ──────────────────────────────────────────

    @Test
    public void buildDisplayList_emptySessions_returnsEmpty() {
        List<TermuxSession> result = mManager.buildDisplayList(Collections.emptyList());
        assertNotNull(result);
        assertTrue(result.isEmpty());
    }

    // ── Test 2: No stored state → insertion order ───────────────────────

    @Test
    public void buildDisplayList_noStoredState_returnsInsertionOrder() {
        List<String> h = new ArrayList<>();
        List<TermuxSession> sessions = createSessions(3, h);
        List<TermuxSession> result = mManager.buildDisplayList(sessions);

        assertEquals(3, result.size());
        assertEquals(h.get(0), result.get(0).getTerminalSession().mHandle);
        assertEquals(h.get(1), result.get(1).getTerminalSession().mHandle);
        assertEquals(h.get(2), result.get(2).getTerminalSession().mHandle);
    }

    // ── Test 3: Pinned sessions appear first ────────────────────────────

    @Test
    public void buildDisplayList_withPinnedAndUnpinned_pinnedFirst() {
        List<String> h = new ArrayList<>();
        List<TermuxSession> sessions = createSessions(3, h);

        mManager.onNewSession(h.get(0));
        mManager.onNewSession(h.get(1));
        mManager.onNewSession(h.get(2));
        mManager.setPinned(h.get(1), true);

        List<TermuxSession> result = mManager.buildDisplayList(sessions);
        List<String> order = getDisplayOrder(result);

        assertEquals(h.get(1), order.get(0)); // Pinned first
        assertEquals(h.get(0), order.get(1));
        assertEquals(h.get(2), order.get(2));
    }

    // ── Test 4: Pin a session ───────────────────────────────────────────

    @Test
    public void setPinned_true_movesToPinnedGroup() {
        List<String> h = new ArrayList<>();
        List<TermuxSession> sessions = createSessions(3, h);

        mManager.onNewSession(h.get(0));
        mManager.onNewSession(h.get(1));
        mManager.onNewSession(h.get(2));

        assertFalse(mManager.isPinned(h.get(1)));
        mManager.setPinned(h.get(1), true);
        assertTrue(mManager.isPinned(h.get(1)));

        List<TermuxSession> result = mManager.buildDisplayList(sessions);
        List<String> order = getDisplayOrder(result);
        assertEquals(h.get(1), order.get(0));
    }

    // ── Test 5: Unpin a session ────────────────────────────────────────

    @Test
    public void setPinned_false_movesToUnpinnedGroup() {
        List<String> h = new ArrayList<>();
        createSessions(2, h);

        mManager.onNewSession(h.get(0));
        mManager.onNewSession(h.get(1));
        mManager.setPinned(h.get(1), true);
        mManager.setPinned(h.get(1), false);
        assertFalse(mManager.isPinned(h.get(1)));
    }

    // ── Test 6: Move up within unpinned group ───────────────────────────

    @Test
    public void moveUp_withinUnpinned_swapsPositions() {
        List<String> h = new ArrayList<>();
        List<TermuxSession> sessions = createSessions(3, h);

        mManager.onNewSession(h.get(0));
        mManager.onNewSession(h.get(1));
        mManager.onNewSession(h.get(2));

        mManager.moveUp(h.get(2));

        List<TermuxSession> result = mManager.buildDisplayList(sessions);
        List<String> order = getDisplayOrder(result);
        assertEquals(h.get(0), order.get(0));
        assertEquals(h.get(2), order.get(1));
        assertEquals(h.get(1), order.get(2));
    }

    // ── Test 7: Move up at boundary → no-op ─────────────────────────────

    @Test
    public void moveUp_atBoundary_noOp() {
        List<String> h = new ArrayList<>();
        List<TermuxSession> sessions = createSessions(2, h);

        mManager.onNewSession(h.get(0));
        mManager.onNewSession(h.get(1));

        mManager.moveUp(h.get(0));

        List<TermuxSession> result = mManager.buildDisplayList(sessions);
        List<String> order = getDisplayOrder(result);
        assertEquals(h.get(0), order.get(0));
        assertEquals(h.get(1), order.get(1));
    }

    // ── Test 8: Move down within pinned group ───────────────────────────

    @Test
    public void moveDown_withinPinned_swapsPositions() {
        List<String> h = new ArrayList<>();
        List<TermuxSession> sessions = createSessions(2, h);

        mManager.onNewSession(h.get(0));
        mManager.onNewSession(h.get(1));
        mManager.setPinned(h.get(0), true);
        mManager.setPinned(h.get(1), true);

        mManager.moveDown(h.get(0));

        List<TermuxSession> result = mManager.buildDisplayList(sessions);
        List<String> order = getDisplayOrder(result);
        assertEquals(h.get(1), order.get(0));
        assertEquals(h.get(0), order.get(1));
    }

    // ── Test 9: Move up does not cross pin boundary ─────────────────────

    @Test
    public void moveUp_doesNotCrossPinBoundary() {
        List<String> h = new ArrayList<>();
        List<TermuxSession> sessions = createSessions(2, h);

        mManager.onNewSession(h.get(0));
        mManager.onNewSession(h.get(1));
        mManager.setPinned(h.get(0), true);

        mManager.moveUp(h.get(1));

        assertFalse(mManager.isPinned(h.get(1)));

        List<TermuxSession> result = mManager.buildDisplayList(sessions);
        List<String> order = getDisplayOrder(result);
        assertEquals(h.get(0), order.get(0));
        assertEquals(h.get(1), order.get(1));
    }

    // ── Test 10: Remove entry compacts positions ────────────────────────

    @Test
    public void removeEntry_cleansUpAndCompacts() {
        List<String> h = new ArrayList<>();
        List<TermuxSession> sessions = createSessions(3, h);

        mManager.onNewSession(h.get(0));
        mManager.onNewSession(h.get(1));
        mManager.onNewSession(h.get(2));

        mManager.removeEntry(h.get(1));

        List<SessionOrderManager.OrderEntry> entries = mManager.loadEntries();
        assertEquals(2, entries.size());

        // Remove the deleted session from the session list for display
        List<TermuxSession> remaining = new ArrayList<>();
        remaining.add(sessions.get(0));
        remaining.add(sessions.get(2));

        List<TermuxSession> result = mManager.buildDisplayList(remaining);
        List<String> order = getDisplayOrder(result);
        assertEquals(h.get(0), order.get(0));
        assertEquals(h.get(2), order.get(1));
    }

    // ── Test 11: New session appends to unpinned end ────────────────────

    @Test
    public void onNewSession_appendsToUnpinnedEnd() {
        List<String> h = new ArrayList<>();
        List<TermuxSession> sessions = createSessions(3, h);

        mManager.onNewSession(h.get(0));
        mManager.onNewSession(h.get(1));
        mManager.onNewSession(h.get(2));

        List<TermuxSession> result = mManager.buildDisplayList(sessions);
        List<String> order = getDisplayOrder(result);
        assertEquals(h.get(0), order.get(0));
        assertEquals(h.get(1), order.get(1));
        assertEquals(h.get(2), order.get(2));
    }

    // ── Test 12: Stale entries removed on rebuild ───────────────────────

    @Test
    public void staleEntries_removedOnRebuild() {
        List<String> h = new ArrayList<>();
        List<TermuxSession> sessions = createSessions(2, h);

        mManager.onNewSession(h.get(0));
        mManager.onNewSession(h.get(1));
        mManager.onNewSession("stale_handle_xyz");

        List<TermuxSession> result = mManager.buildDisplayList(sessions);

        List<SessionOrderManager.OrderEntry> entries = mManager.loadEntries();
        assertEquals(2, entries.size());
        for (SessionOrderManager.OrderEntry e : entries) {
            assertFalse("stale_handle_xyz".equals(e.handle));
        }
    }

    // ── Test 13: Corrupt JSON → graceful degradation ────────────────────

    @Test
    public void corruptJson_degradesGracefully() {
        mPrefs.edit().putString(PREF_KEY, "not valid json!!!").commit();

        List<SessionOrderManager.OrderEntry> entries = mManager.loadEntries();
        assertNotNull(entries);
        assertTrue(entries.isEmpty());

        mManager.onNewSession("recovery_handle");
        entries = mManager.loadEntries();
        assertEquals(1, entries.size());
    }

    // ── Test 14: Persistence round-trip ─────────────────────────────────

    @Test
    public void persistence_roundTrips() {
        mManager.onNewSession("handle_a");
        mManager.onNewSession("handle_b");
        mManager.setPinned("handle_a", true);

        SessionOrderManager manager2 = new SessionOrderManager(mPrefs);
        List<SessionOrderManager.OrderEntry> entries = manager2.loadEntries();
        assertEquals(2, entries.size());

        boolean foundA = false, foundB = false;
        for (SessionOrderManager.OrderEntry e : entries) {
            if ("handle_a".equals(e.handle)) {
                assertTrue(e.pinned);
                foundA = true;
            }
            if ("handle_b".equals(e.handle)) {
                assertFalse(e.pinned);
                foundB = true;
            }
        }
        assertTrue(foundA);
        assertTrue(foundB);
    }

    // ── Test 15: MAX_SESSIONS boundary ──────────────────────────────────

    @Test
    public void maxSessions_eightEntries_correctOrder() {
        List<String> h = new ArrayList<>();
        List<TermuxSession> sessions = createSessions(8, h);

        for (String handle : h) {
            mManager.onNewSession(handle);
        }

        // Pin sessions at index 2 and 6
        mManager.setPinned(h.get(2), true);
        mManager.setPinned(h.get(6), true);

        // Move session 6 up in pinned group (should swap with session 2)
        mManager.moveUp(h.get(6));

        List<TermuxSession> result = mManager.buildDisplayList(sessions);
        List<String> order = getDisplayOrder(result);

        assertEquals(8, order.size());
        // Pinned: h[6] (moved up), h[2]
        assertEquals(h.get(6), order.get(0));
        assertEquals(h.get(2), order.get(1));
        // Unpinned: rest in original order
        assertEquals(h.get(0), order.get(2));
        assertEquals(h.get(1), order.get(3));
        assertEquals(h.get(3), order.get(4));
        assertEquals(h.get(4), order.get(5));
        assertEquals(h.get(5), order.get(6));
        assertEquals(h.get(7), order.get(7));
    }

    // ── Additional edge case tests ──────────────────────────────────────

    @Test
    public void moveDown_atBoundary_noOp() {
        List<String> h = new ArrayList<>();
        List<TermuxSession> sessions = createSessions(2, h);

        mManager.onNewSession(h.get(0));
        mManager.onNewSession(h.get(1));

        mManager.moveDown(h.get(1));

        List<TermuxSession> result = mManager.buildDisplayList(sessions);
        List<String> order = getDisplayOrder(result);
        assertEquals(h.get(0), order.get(0));
        assertEquals(h.get(1), order.get(1));
    }

    @Test
    public void pinToggle_noOpWhenSameState() {
        mManager.onNewSession("handle_x");
        mManager.setPinned("handle_x", false); // Already unpinned
        assertFalse(mManager.isPinned("handle_x"));
    }

    @Test
    public void removeEntry_nonExistent_noOp() {
        mManager.onNewSession("handle_y");
        mManager.removeEntry("nonexistent");
        List<SessionOrderManager.OrderEntry> entries = mManager.loadEntries();
        assertEquals(1, entries.size());
    }

    @Test
    public void isPinned_unknownHandle_returnsFalse() {
        assertFalse(mManager.isPinned("unknown"));
    }

    @Test
    public void multiplePins_preservesRelativeOrder() {
        List<String> h = new ArrayList<>();
        List<TermuxSession> sessions = createSessions(3, h);

        mManager.onNewSession(h.get(0));
        mManager.onNewSession(h.get(1));
        mManager.onNewSession(h.get(2));

        mManager.setPinned(h.get(0), true);
        mManager.setPinned(h.get(2), true);

        List<TermuxSession> result = mManager.buildDisplayList(sessions);
        List<String> order = getDisplayOrder(result);

        assertEquals(h.get(0), order.get(0)); // Pinned first
        assertEquals(h.get(2), order.get(1)); // Pinned second
        assertEquals(h.get(1), order.get(2)); // Unpinned last
    }

    @Test
    public void removePinnedSession_compactsPinnedGroup() {
        List<String> h = new ArrayList<>();
        List<TermuxSession> sessions = createSessions(3, h);

        mManager.onNewSession(h.get(0));
        mManager.onNewSession(h.get(1));
        mManager.onNewSession(h.get(2));
        mManager.setPinned(h.get(0), true);
        mManager.setPinned(h.get(1), true);
        mManager.setPinned(h.get(2), true);

        mManager.removeEntry(h.get(1));

        List<TermuxSession> remaining = new ArrayList<>();
        remaining.add(sessions.get(0));
        remaining.add(sessions.get(2));

        List<TermuxSession> result = mManager.buildDisplayList(remaining);
        List<String> order = getDisplayOrder(result);

        assertEquals(2, order.size());
        assertEquals(h.get(0), order.get(0));
        assertEquals(h.get(2), order.get(1));

        assertTrue(mManager.isPinned(h.get(0)));
        assertTrue(mManager.isPinned(h.get(2)));
    }
}
