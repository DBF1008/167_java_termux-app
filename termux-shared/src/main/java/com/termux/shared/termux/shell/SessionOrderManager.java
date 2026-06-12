package com.termux.shared.termux.shell;

import android.content.SharedPreferences;

import androidx.annotation.NonNull;

import com.termux.shared.termux.settings.preferences.TermuxPreferenceConstants;
import com.termux.shared.termux.shell.command.runner.terminal.TermuxSession;
import com.termux.terminal.TerminalSession;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Manages session display order and pin state for the session list sidebar.
 *
 * Sessions are identified by their {@link TerminalSession#mHandle} (UUID string).
 * Order and pin state are persisted to SharedPreferences as a JSON array.
 *
 * Display ordering rules:
 * - Pinned sessions appear first, sorted by position ascending.
 * - Unpinned sessions appear after, sorted by position ascending.
 * - New sessions are appended to the end of the unpinned group.
 * - Move up/down operates within the same pin group only.
 */
public class SessionOrderManager {

    private static final String PREF_KEY = TermuxPreferenceConstants.TERMUX_APP.KEY_SESSION_ORDER_STATE;

    private final SharedPreferences mPrefs;

    public SessionOrderManager(@NonNull SharedPreferences prefs) {
        this.mPrefs = prefs;
    }

    // ── Data class ──────────────────────────────────────────────────────

    /**
     * Immutable value object for one session's order metadata.
     */
    public static class OrderEntry {
        public final String handle;
        public final boolean pinned;
        public final int position;

        public OrderEntry(String handle, boolean pinned, int position) {
            this.handle = handle;
            this.pinned = pinned;
            this.position = position;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            OrderEntry that = (OrderEntry) o;
            return handle.equals(that.handle);
        }

        @Override
        public int hashCode() {
            return handle.hashCode();
        }
    }

    // ── Public API ──────────────────────────────────────────────────────

    /**
     * Build a sorted display list from the live session list and stored order state.
     * Sessions not yet in stored state get default entries (unpinned, appended at end).
     * Stale entries (handles not in the live list) are dropped and the state is re-saved.
     */
    @NonNull
    public synchronized List<TermuxSession> buildDisplayList(@NonNull List<TermuxSession> sessions) {
        if (sessions.isEmpty()) {
            return Collections.emptyList();
        }

        // Build a map from handle to live session for quick lookup
        Map<String, TermuxSession> sessionMap = new HashMap<>();
        for (TermuxSession ts : sessions) {
            TerminalSession terminal = ts.getTerminalSession();
            if (terminal != null) {
                sessionMap.put(terminal.mHandle, ts);
            }
        }

        // Load stored entries, filter out stale ones
        List<OrderEntry> storedEntries = loadEntries();
        Map<String, OrderEntry> storedMap = new HashMap<>();
        for (OrderEntry entry : storedEntries) {
            if (sessionMap.containsKey(entry.handle)) {
                storedMap.put(entry.handle, entry);
            }
        }

        // Create entries for new sessions not yet in stored state
        int maxPinnedPos = 0;
        int maxUnpinnedPos = 0;
        for (OrderEntry entry : storedMap.values()) {
            if (entry.pinned) {
                maxPinnedPos = Math.max(maxPinnedPos, entry.position);
            } else {
                maxUnpinnedPos = Math.max(maxUnpinnedPos, entry.position);
            }
        }

        List<OrderEntry> allEntries = new ArrayList<>(storedMap.values());
        for (String handle : sessionMap.keySet()) {
            if (!storedMap.containsKey(handle)) {
                maxUnpinnedPos++;
                allEntries.add(new OrderEntry(handle, false, maxUnpinnedPos));
            }
        }

        // Sort: pinned first (desc), then by position (asc)
        Collections.sort(allEntries, new Comparator<OrderEntry>() {
            @Override
            public int compare(OrderEntry a, OrderEntry b) {
                if (a.pinned != b.pinned) {
                    return a.pinned ? -1 : 1; // pinned first
                }
                return Integer.compare(a.position, b.position);
            }
        });

        // Save cleaned state (stale entries removed, new entries added)
        saveEntries(allEntries);

        // Build result list in sorted order
        List<TermuxSession> result = new ArrayList<>(allEntries.size());
        for (OrderEntry entry : allEntries) {
            TermuxSession ts = sessionMap.get(entry.handle);
            if (ts != null) {
                result.add(ts);
            }
        }

        return result;
    }

    /**
     * Set the pinned state of a session. When pinning, the session is placed at
     * the end of the pinned group. When unpinning, it is placed at the end of
     * the unpinned group.
     */
    public synchronized void setPinned(@NonNull String handle, boolean pinned) {
        List<OrderEntry> entries = loadEntries();
        int index = findEntryIndex(entries, handle);
        if (index < 0) return;

        OrderEntry old = entries.get(index);
        if (old.pinned == pinned) return; // No change

        // Remove old entry, recompute, and re-insert at end of target group
        entries.remove(index);

        int maxPos = 0;
        for (OrderEntry e : entries) {
            if (e.pinned == pinned) {
                maxPos = Math.max(maxPos, e.position);
            }
        }

        entries.add(new OrderEntry(handle, pinned, maxPos + 1));

        // Compact positions within each group
        entries = compactPositions(entries);
        saveEntries(entries);
    }

    /**
     * Check if a session is pinned.
     */
    public synchronized boolean isPinned(@NonNull String handle) {
        List<OrderEntry> entries = loadEntries();
        for (OrderEntry e : entries) {
            if (e.handle.equals(handle)) {
                return e.pinned;
            }
        }
        return false;
    }

    /**
     * Move a session up within its pin group. No-op if already at the top of its group.
     */
    public synchronized void moveUp(@NonNull String handle) {
        List<OrderEntry> entries = loadEntries();
        moveWithinGroup(entries, handle, -1);
        saveEntries(entries);
    }

    /**
     * Move a session down within its pin group. No-op if already at the bottom of its group.
     */
    public synchronized void moveDown(@NonNull String handle) {
        List<OrderEntry> entries = loadEntries();
        moveWithinGroup(entries, handle, 1);
        saveEntries(entries);
    }

    /**
     * Register a newly created session. It is added to the end of the unpinned group.
     */
    public synchronized void onNewSession(@NonNull String handle) {
        List<OrderEntry> entries = loadEntries();

        // Check if already exists (shouldn't happen, but guard)
        for (OrderEntry e : entries) {
            if (e.handle.equals(handle)) return;
        }

        int maxUnpinnedPos = 0;
        for (OrderEntry e : entries) {
            if (!e.pinned) {
                maxUnpinnedPos = Math.max(maxUnpinnedPos, e.position);
            }
        }

        entries.add(new OrderEntry(handle, false, maxUnpinnedPos + 1));
        saveEntries(entries);
    }

    /**
     * Remove a session's order entry. Positions in the affected group are compacted.
     */
    public synchronized void removeEntry(@NonNull String handle) {
        List<OrderEntry> entries = loadEntries();
        int index = findEntryIndex(entries, handle);
        if (index < 0) return;

        entries.remove(index);
        entries = compactPositions(entries);
        saveEntries(entries);
    }

    // ── Persistence ─────────────────────────────────────────────────────

    /**
     * Load order entries from SharedPreferences. Returns empty list on null/parse error.
     */
    @NonNull
    public List<OrderEntry> loadEntries() {
        String json = mPrefs.getString(PREF_KEY, null);
        if (json == null || json.isEmpty()) {
            return new ArrayList<>();
        }

        try {
            JSONArray array = new JSONArray(json);
            List<OrderEntry> entries = new ArrayList<>(array.length());
            for (int i = 0; i < array.length(); i++) {
                JSONObject obj = array.getJSONObject(i);
                entries.add(new OrderEntry(
                    obj.getString("h"),
                    obj.getBoolean("p"),
                    obj.getInt("o")
                ));
            }
            return entries;
        } catch (JSONException e) {
            // Graceful degradation: corrupt data → empty list → insertion order
            return new ArrayList<>();
        }
    }

    /**
     * Save order entries to SharedPreferences as JSON.
     */
    public void saveEntries(@NonNull List<OrderEntry> entries) {
        try {
            JSONArray array = new JSONArray();
            for (OrderEntry entry : entries) {
                JSONObject obj = new JSONObject();
                obj.put("h", entry.handle);
                obj.put("p", entry.pinned);
                obj.put("o", entry.position);
                array.put(obj);
            }
            mPrefs.edit().putString(PREF_KEY, array.toString()).apply();
        } catch (JSONException e) {
            // JSONObject.put only throws on NaN/Infinity values, which we don't have
        }
    }

    // ── Internal helpers ────────────────────────────────────────────────

    private static int findEntryIndex(@NonNull List<OrderEntry> entries, @NonNull String handle) {
        for (int i = 0; i < entries.size(); i++) {
            if (entries.get(i).handle.equals(handle)) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Move a session within its pin group by the given direction (-1 = up, +1 = down).
     * Does not cross pin group boundaries.
     */
    private static void moveWithinGroup(@NonNull List<OrderEntry> entries, @NonNull String handle, int direction) {
        int myIndex = findEntryIndex(entries, handle);
        if (myIndex < 0) return;

        OrderEntry myEntry = entries.get(myIndex);

        // Find all entries in the same pin group, sorted by position
        List<Integer> groupIndices = new ArrayList<>();
        for (int i = 0; i < entries.size(); i++) {
            if (entries.get(i).pinned == myEntry.pinned) {
                groupIndices.add(i);
            }
        }

        // Sort group indices by position
        Collections.sort(groupIndices, new Comparator<Integer>() {
            @Override
            public int compare(Integer a, Integer b) {
                return Integer.compare(entries.get(a).position, entries.get(b).position);
            }
        });

        // Find my position within the group
        int myGroupPos = -1;
        for (int i = 0; i < groupIndices.size(); i++) {
            if (groupIndices.get(i) == myIndex) {
                myGroupPos = i;
                break;
            }
        }

        int swapGroupPos = myGroupPos + direction;
        if (swapGroupPos < 0 || swapGroupPos >= groupIndices.size()) {
            return; // At boundary, no-op
        }

        int swapIndex = groupIndices.get(swapGroupPos);
        OrderEntry swapEntry = entries.get(swapIndex);

        // Swap positions
        entries.set(myIndex, new OrderEntry(myEntry.handle, myEntry.pinned, swapEntry.position));
        entries.set(swapIndex, new OrderEntry(swapEntry.handle, swapEntry.pinned, myEntry.position));
    }

    /**
     * Compact positions within each pin group so they are consecutive starting from 0.
     */
    @NonNull
    private static List<OrderEntry> compactPositions(@NonNull List<OrderEntry> entries) {
        // Separate into pinned and unpinned, sort by position, reassign
        List<OrderEntry> pinned = new ArrayList<>();
        List<OrderEntry> unpinned = new ArrayList<>();

        for (OrderEntry e : entries) {
            if (e.pinned) pinned.add(e);
            else unpinned.add(e);
        }

        Collections.sort(pinned, new Comparator<OrderEntry>() {
            @Override
            public int compare(OrderEntry a, OrderEntry b) {
                return Integer.compare(a.position, b.position);
            }
        });
        Collections.sort(unpinned, new Comparator<OrderEntry>() {
            @Override
            public int compare(OrderEntry a, OrderEntry b) {
                return Integer.compare(a.position, b.position);
            }
        });

        List<OrderEntry> result = new ArrayList<>(entries.size());
        for (int i = 0; i < pinned.size(); i++) {
            OrderEntry e = pinned.get(i);
            result.add(new OrderEntry(e.handle, true, i));
        }
        for (int i = 0; i < unpinned.size(); i++) {
            OrderEntry e = unpinned.get(i);
            result.add(new OrderEntry(e.handle, false, i));
        }

        return result;
    }
}
