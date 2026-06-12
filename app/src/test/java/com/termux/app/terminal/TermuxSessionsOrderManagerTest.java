package com.termux.app.terminal;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.termux.shared.termux.shell.TermuxSessionsOrderManager;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

/**
 * Regression coverage for {@link TermuxSessionsOrderManager}, the engine that backs session pinning
 * and reordering in the sidebar.
 * <p/>
 * The engine is deliberately Android-free and keyed by session handles, so these are plain JUnit
 * tests. Real {@code TerminalSession}s require a forked process / JNI and cannot be created in a unit
 * test, so the required scenarios (new / rename / delete / service reconnect / max-session boundary)
 * are exercised at the handle level, which is exactly what the service feeds the engine at runtime.
 */
public class TermuxSessionsOrderManagerTest {

    private static List<String> order(String... handles) {
        return Arrays.asList(handles);
    }

    // ---------------------------------------------------------------------------------------------
    // 新建 / new session
    // ---------------------------------------------------------------------------------------------

    @Test
    public void newSession_appendsToTailInCreationOrderWhenNothingPinned() {
        TermuxSessionsOrderManager m = new TermuxSessionsOrderManager();

        // With nothing pinned, display order is exactly creation order.
        assertEquals(order("a", "b", "c"), m.computeOrder(order("a", "b", "c")));

        // A newly created session is appended at the end.
        assertEquals(order("a", "b", "c", "d"), m.computeOrder(order("a", "b", "c", "d")));
    }

    @Test
    public void newSession_appendsToBottomOfUnpinnedGroup() {
        TermuxSessionsOrderManager m = new TermuxSessionsOrderManager();
        m.computeOrder(order("a", "b", "c"));
        m.setPinned("a", true);

        // New session "d" lands after the unpinned ones, never inside the pinned group.
        assertEquals(order("a", "b", "c", "d"), m.computeOrder(order("a", "b", "c", "d")));
    }

    // ---------------------------------------------------------------------------------------------
    // 重命名 / rename — order is keyed by handle, so a name change must not perturb it
    // ---------------------------------------------------------------------------------------------

    @Test
    public void rename_preservesOrderAndPinsBecauseKeyedByHandle() {
        TermuxSessionsOrderManager m = new TermuxSessionsOrderManager();
        m.computeOrder(order("a", "b", "c"));
        m.setPinned("b", true);

        List<String> before = m.computeOrder(order("a", "b", "c"));
        assertEquals(order("b", "a", "c"), before);

        // Renaming a session only changes its display name; the engine is never told about names,
        // so re-computing the order yields an identical result and the pin survives.
        List<String> after = m.computeOrder(order("a", "b", "c"));
        assertEquals(before, after);
        assertTrue(m.isPinned("b"));
    }

    // ---------------------------------------------------------------------------------------------
    // 删除 / delete
    // ---------------------------------------------------------------------------------------------

    @Test
    public void delete_forgetRemovesFromOrderAndPinned() {
        TermuxSessionsOrderManager m = new TermuxSessionsOrderManager();
        m.computeOrder(order("a", "b", "c"));
        m.setPinned("b", true);

        m.forget("b");

        assertFalse(m.isPinned("b"));
        assertEquals(order("a", "c"), m.computeOrder(order("a", "c")));
    }

    @Test
    public void delete_keepsRelativeOrderOfRemaining() {
        TermuxSessionsOrderManager m = new TermuxSessionsOrderManager();
        m.computeOrder(order("a", "b", "c", "d"));
        m.moveUp("d", order("a", "b", "c", "d")); // -> a, b, d, c

        m.forget("b");

        assertEquals(order("a", "d", "c"), m.computeOrder(order("a", "d", "c")));
    }

    // ---------------------------------------------------------------------------------------------
    // 重连服务 / service reconnect — order must be re-applied deterministically
    // ---------------------------------------------------------------------------------------------

    @Test
    public void reconnect_orderIsStableRegardlessOfLiveListOrder() {
        TermuxSessionsOrderManager m = new TermuxSessionsOrderManager();
        m.computeOrder(order("a", "b", "c", "d"));
        m.setPinned("c", true);
        m.moveUp("d", order("a", "b", "c", "d")); // display c,a,b,d -> swap d,b -> c,a,d,b

        List<String> arranged = m.computeOrder(order("a", "b", "c", "d"));
        assertEquals(order("c", "a", "d", "b"), arranged);

        // Reconnect with the live list in original creation order -> same arrangement.
        assertEquals(arranged, m.computeOrder(order("a", "b", "c", "d")));
        // Reconnect with the live list already in the previously-applied order -> still the same.
        assertEquals(arranged, m.computeOrder(order("c", "a", "d", "b")));
    }

    @Test
    public void reconnect_sessionAddedWhileDetachedAppends() {
        TermuxSessionsOrderManager m = new TermuxSessionsOrderManager();
        m.computeOrder(order("a", "b", "c", "d"));
        m.setPinned("c", true);
        m.moveUp("d", order("a", "b", "c", "d")); // -> c, a, d, b

        // A session "e" created while the activity was detached shows up on the next compute and is
        // appended to the bottom of the unpinned group without disturbing the rest.
        assertEquals(order("c", "a", "d", "b", "e"), m.computeOrder(order("a", "b", "c", "d", "e")));
    }

    // ---------------------------------------------------------------------------------------------
    // 最大会话数边界 / max session count boundary
    // ---------------------------------------------------------------------------------------------

    @Test
    public void maxSessions_capacityBoundary() {
        assertEquals(8, TermuxSessionsOrderManager.MAX_SESSIONS);
        assertFalse(TermuxSessionsOrderManager.isAtCapacity(0));
        assertFalse(TermuxSessionsOrderManager.isAtCapacity(7));
        assertTrue(TermuxSessionsOrderManager.isAtCapacity(8));
        assertTrue(TermuxSessionsOrderManager.isAtCapacity(9));
    }

    @Test
    public void maxSessions_pinAndReorderStillWorkAtCapacity() {
        TermuxSessionsOrderManager m = new TermuxSessionsOrderManager();
        List<String> full = order("s1", "s2", "s3", "s4", "s5", "s6", "s7", "s8");
        m.computeOrder(full);

        m.setPinned("s8", true);
        List<String> pinnedFirst = m.computeOrder(full);
        assertEquals("s8", pinnedFirst.get(0));
        assertEquals(8, pinnedFirst.size());

        assertTrue(m.moveDown("s1", full));
        assertEquals(order("s8", "s2", "s1", "s3", "s4", "s5", "s6", "s7"), m.computeOrder(full));
    }

    // ---------------------------------------------------------------------------------------------
    // pin / move mechanics
    // ---------------------------------------------------------------------------------------------

    @Test
    public void pin_floatsToTopPreservingRelativeOrder() {
        TermuxSessionsOrderManager m = new TermuxSessionsOrderManager();
        m.computeOrder(order("a", "b", "c", "d"));
        m.setPinned("b", true);
        m.setPinned("d", true);

        // Pinned b,d float above, each group keeps relative order.
        assertEquals(order("b", "d", "a", "c"), m.computeOrder(order("a", "b", "c", "d")));
    }

    @Test
    public void togglePin_twiceRestoresOrder() {
        TermuxSessionsOrderManager m = new TermuxSessionsOrderManager();
        List<String> original = m.computeOrder(order("a", "b", "c"));

        assertTrue(m.togglePinned("b"));
        assertTrue(m.togglePinned("b"));

        assertFalse(m.isPinned("b"));
        assertEquals(original, m.computeOrder(order("a", "b", "c")));
    }

    @Test
    public void move_withinGroupSwapsNeighbours() {
        TermuxSessionsOrderManager m = new TermuxSessionsOrderManager();
        m.computeOrder(order("a", "b", "c"));

        assertTrue(m.moveUp("c", order("a", "b", "c")));
        assertEquals(order("a", "c", "b"), m.computeOrder(order("a", "b", "c")));

        assertTrue(m.moveDown("a", order("a", "b", "c"))); // display a,c,b -> c,a,b
        assertEquals(order("c", "a", "b"), m.computeOrder(order("a", "b", "c")));
    }

    @Test
    public void move_acrossPinBoundaryIsRejected() {
        TermuxSessionsOrderManager m = new TermuxSessionsOrderManager();
        m.computeOrder(order("a", "b", "c"));
        m.setPinned("a", true);

        // Unpinned b cannot move above pinned a, and pinned a cannot move below into unpinned.
        assertFalse(m.moveUp("b", order("a", "b", "c")));
        assertFalse(m.moveDown("a", order("a", "b", "c")));
        assertEquals(order("a", "b", "c"), m.computeOrder(order("a", "b", "c")));
    }

    @Test
    public void move_atListEndsIsRejected() {
        TermuxSessionsOrderManager m = new TermuxSessionsOrderManager();
        m.computeOrder(order("a", "b", "c"));

        assertFalse(m.moveUp("a", order("a", "b", "c")));
        assertFalse(m.moveDown("c", order("a", "b", "c")));
    }

    @Test
    public void nullAndUnknownHandlesAreSafe() {
        TermuxSessionsOrderManager m = new TermuxSessionsOrderManager();

        assertFalse(m.isPinned(null));
        assertFalse(m.togglePinned(null));
        assertFalse(m.moveUp(null, order("a")));
        assertFalse(m.moveUp("unknown", order("a", "b")));
        m.forget(null);
        m.forget("unknown");

        assertEquals(order("a", "b"), m.computeOrder(order("a", "b")));
    }

}
