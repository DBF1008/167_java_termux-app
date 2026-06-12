package com.termux.shared.termux.shell;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Tracks the user controlled display order and "pin" state of terminal sessions so that frequently
 * used sessions can be kept in a stable, predictable place in the sessions list instead of always
 * following raw creation order.
 * <p/>
 * Sessions are identified by their stable {@link com.termux.terminal.TerminalSession#mHandle} (a
 * {@code UUID} string), never by their name or list index, so the order survives renames and is
 * unaffected by which order the live list happens to be presented in when the activity is rebuilt or
 * the service is rebound.
 * <p/>
 * The display order is computed as: pinned sessions first (in their manual order), then unpinned
 * sessions (in their manual order). Newly seen sessions are appended to the end of their group. This
 * class is intentionally free of any Android dependencies so the ordering logic can be unit tested in
 * isolation; callers are responsible for re-applying {@link #computeOrder(List)} to the actual list of
 * sessions and refreshing the UI.
 */
public class TermuxSessionsOrderManager {

    /** The maximum number of concurrent terminal sessions that may exist. */
    public static final int MAX_SESSIONS = 8;

    /** All handles ever seen, in the user's manual order (independent of pin state). */
    private final List<String> mOrder = new ArrayList<>();

    /** The subset of handles that are pinned. */
    private final Set<String> mPinned = new LinkedHashSet<>();

    /** Whether a list with {@code currentSessionCount} sessions has reached {@link #MAX_SESSIONS}. */
    public static boolean isAtCapacity(int currentSessionCount) {
        return currentSessionCount >= MAX_SESSIONS;
    }

    public boolean isPinned(@Nullable String handle) {
        return handle != null && mPinned.contains(handle);
    }

    /**
     * Set the pin state of a session.
     *
     * @return {@code true} if the pin state actually changed.
     */
    public boolean setPinned(@Nullable String handle, boolean pinned) {
        if (handle == null) return false;
        boolean changed = pinned ? mPinned.add(handle) : mPinned.remove(handle);
        // Make sure a (newly) pinned handle is known so it participates in ordering.
        if (changed && !mOrder.contains(handle)) mOrder.add(handle);
        return changed;
    }

    /**
     * Toggle the pin state of a session.
     *
     * @return {@code true} if the pin state actually changed.
     */
    public boolean togglePinned(@Nullable String handle) {
        if (handle == null) return false;
        return setPinned(handle, !isPinned(handle));
    }

    /** Drop all ordering and pin state for a session that no longer exists. */
    public void forget(@Nullable String handle) {
        if (handle == null) return;
        mOrder.remove(handle);
        mPinned.remove(handle);
    }

    /**
     * Compute the canonical display order for the given live session handles.
     * <p/>
     * Any live handle not seen before is first recorded at the end of the manual order (this is how
     * newly created sessions are picked up). The result is then the manual order filtered to live
     * handles and stable-partitioned so that pinned handles come first, each group keeping its
     * relative manual order. The order of {@code liveHandles} itself only matters for handles that
     * have never been seen before; previously known handles always keep their stored position, which
     * is what makes the order stable across activity rebuilds and service reconnects.
     */
    @NonNull
    public List<String> computeOrder(@NonNull List<String> liveHandles) {
        ingest(liveHandles);

        Set<String> live = new LinkedHashSet<>(liveHandles);
        List<String> pinned = new ArrayList<>();
        List<String> unpinned = new ArrayList<>();
        for (String handle : mOrder) {
            if (!live.contains(handle)) continue;
            if (mPinned.contains(handle)) pinned.add(handle);
            else unpinned.add(handle);
        }

        List<String> result = new ArrayList<>(pinned.size() + unpinned.size());
        result.addAll(pinned);
        result.addAll(unpinned);
        return result;
    }

    /** Move a session one step towards the top of its pin-group in the display order. */
    public boolean moveUp(@Nullable String handle, @NonNull List<String> liveHandles) {
        return move(handle, liveHandles, true);
    }

    /** Move a session one step towards the bottom of its pin-group in the display order. */
    public boolean moveDown(@Nullable String handle, @NonNull List<String> liveHandles) {
        return move(handle, liveHandles, false);
    }

    /**
     * Swap a session with its neighbour in the display order, but only within the same pin-group so
     * that pinned sessions always remain above unpinned ones. To move a session across the pin
     * boundary, toggle its pin state instead.
     *
     * @return {@code true} if a swap happened.
     */
    private boolean move(@Nullable String handle, @NonNull List<String> liveHandles, boolean up) {
        if (handle == null) return false;

        List<String> display = computeOrder(liveHandles);
        int from = display.indexOf(handle);
        if (from < 0) return false;

        int to = up ? from - 1 : from + 1;
        if (to < 0 || to >= display.size()) return false;

        // Refuse to cross the pinned/unpinned boundary.
        if (mPinned.contains(display.get(from)) != mPinned.contains(display.get(to))) return false;

        Collections.swap(display, from, to);
        rewriteOrder(display);
        return true;
    }

    /** Record any not-yet-seen live handle at the end of the manual order. */
    private void ingest(@NonNull List<String> liveHandles) {
        for (String handle : liveHandles) {
            if (handle != null && !mOrder.contains(handle)) mOrder.add(handle);
        }
    }

    /**
     * Replace the manual order with the given (live) display order, keeping any known-but-not-live
     * handles parked at the end so their state is not lost until they are explicitly forgotten.
     */
    private void rewriteOrder(@NonNull List<String> newDisplayOrder) {
        List<String> rebuilt = new ArrayList<>(newDisplayOrder);
        for (String handle : mOrder) {
            if (!rebuilt.contains(handle)) rebuilt.add(handle);
        }
        mOrder.clear();
        mOrder.addAll(rebuilt);
    }

}
