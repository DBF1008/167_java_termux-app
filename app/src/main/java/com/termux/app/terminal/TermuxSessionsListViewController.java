package com.termux.app.terminal;

import android.annotation.SuppressLint;
import android.app.AlertDialog;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.style.StyleSpan;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;

import com.termux.R;
import com.termux.app.TermuxActivity;
import com.termux.shared.termux.shell.SessionOrderManager;
import com.termux.shared.termux.shell.command.runner.terminal.TermuxSession;
import com.termux.shared.theme.NightMode;
import com.termux.shared.theme.ThemeUtils;
import com.termux.terminal.TerminalSession;

import java.util.ArrayList;
import java.util.List;

public class TermuxSessionsListViewController extends ArrayAdapter<TermuxSession> implements AdapterView.OnItemClickListener, AdapterView.OnItemLongClickListener {

    final TermuxActivity mActivity;
    private final SessionOrderManager mOrderManager;
    private final List<TermuxSession> mDisplayList = new ArrayList<>();
    private final List<TermuxSession> mSourceList;

    final StyleSpan boldSpan = new StyleSpan(Typeface.BOLD);
    final StyleSpan italicSpan = new StyleSpan(Typeface.ITALIC);

    public TermuxSessionsListViewController(TermuxActivity activity, List<TermuxSession> sessionList,
                                            SessionOrderManager orderManager) {
        super(activity.getApplicationContext(), R.layout.item_terminal_sessions_list, new ArrayList<>());
        this.mActivity = activity;
        this.mSourceList = sessionList;
        this.mOrderManager = orderManager;
        refreshDisplayList();
    }

    /**
     * Rebuild the display list from the source session list using the order manager's
     * stored pin/order state. Call this after any session add/remove/reorder/pin change.
     */
    public void refreshDisplayList() {
        mDisplayList.clear();
        if (mOrderManager != null) {
            mDisplayList.addAll(mOrderManager.buildDisplayList(mSourceList));
        } else {
            mDisplayList.addAll(mSourceList);
        }
        clear();
        addAll(mDisplayList);
        notifyDataSetChanged();
    }

    /**
     * Get the current display list (sorted by pin/order state).
     */
    public List<TermuxSession> getDisplayList() {
        return mDisplayList;
    }

    @SuppressLint("SetTextI18n")
    @NonNull
    @Override
    public View getView(int position, View convertView, @NonNull ViewGroup parent) {
        View sessionRowView = convertView;
        if (sessionRowView == null) {
            LayoutInflater inflater = mActivity.getLayoutInflater();
            sessionRowView = inflater.inflate(R.layout.item_terminal_sessions_list, parent, false);
        }

        TextView sessionTitleView = sessionRowView.findViewById(R.id.session_title);

        TermuxSession termuxSession = getItem(position);
        if (termuxSession == null) {
            sessionTitleView.setText("null session");
            return sessionRowView;
        }

        TerminalSession sessionAtRow = termuxSession.getTerminalSession();
        if (sessionAtRow == null) {
            sessionTitleView.setText("null session");
            return sessionRowView;
        }

        boolean shouldEnableDarkTheme = ThemeUtils.shouldEnableDarkTheme(mActivity, NightMode.getAppNightMode().getName());

        if (shouldEnableDarkTheme) {
            sessionTitleView.setBackground(
                ContextCompat.getDrawable(mActivity, R.drawable.session_background_black_selected)
            );
        }

        String name = sessionAtRow.mSessionName;
        String sessionTitle = sessionAtRow.getTitle();

        // Check if this session is pinned
        boolean isPinned = mOrderManager != null && mOrderManager.isPinned(sessionAtRow.mHandle);
        String pinPrefix = isPinned ? "📌 " : "";

        String numberPart = pinPrefix + "[" + (position + 1) + "] ";
        String sessionNamePart = (TextUtils.isEmpty(name) ? "" : name);
        String sessionTitlePart = (TextUtils.isEmpty(sessionTitle) ? "" : ((sessionNamePart.isEmpty() ? "" : "\n") + sessionTitle));

        String fullSessionTitle = numberPart + sessionNamePart + sessionTitlePart;
        SpannableString fullSessionTitleStyled = new SpannableString(fullSessionTitle);
        fullSessionTitleStyled.setSpan(boldSpan, 0, numberPart.length() + sessionNamePart.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        fullSessionTitleStyled.setSpan(italicSpan, numberPart.length() + sessionNamePart.length(), fullSessionTitle.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);

        sessionTitleView.setText(fullSessionTitleStyled);

        boolean sessionRunning = sessionAtRow.isRunning();

        if (sessionRunning) {
            sessionTitleView.setPaintFlags(sessionTitleView.getPaintFlags() & ~Paint.STRIKE_THRU_TEXT_FLAG);
        } else {
            sessionTitleView.setPaintFlags(sessionTitleView.getPaintFlags() | Paint.STRIKE_THRU_TEXT_FLAG);
        }
        int defaultColor = shouldEnableDarkTheme ? Color.WHITE : Color.BLACK;
        int color = sessionRunning || sessionAtRow.getExitStatus() == 0 ? defaultColor : Color.RED;
        sessionTitleView.setTextColor(color);
        return sessionRowView;
    }

    @Override
    public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
        TermuxSession clickedSession = getItem(position);
        if (clickedSession == null) return;
        mActivity.getTermuxTerminalSessionClient().setCurrentSession(clickedSession.getTerminalSession());
        mActivity.getDrawer().closeDrawers();
    }

    @Override
    public boolean onItemLongClick(AdapterView<?> parent, View view, int position, long id) {
        final TermuxSession selectedSession = getItem(position);
        if (selectedSession == null) return false;

        final TerminalSession terminalSession = selectedSession.getTerminalSession();
        if (terminalSession == null) return false;

        if (mOrderManager == null) {
            // Fallback to original behavior if no order manager
            mActivity.getTermuxTerminalSessionClient().renameSession(terminalSession);
            return true;
        }

        final boolean isPinned = mOrderManager.isPinned(terminalSession.mHandle);

        // Determine if move up/down is possible within the group
        boolean canMoveUp = canMoveInDirection(terminalSession.mHandle, true);
        boolean canMoveDown = canMoveInDirection(terminalSession.mHandle, false);

        List<String> options = new ArrayList<>();
        options.add(mActivity.getString(R.string.action_rename_session));
        options.add(isPinned
            ? mActivity.getString(R.string.action_unpin_session)
            : mActivity.getString(R.string.action_pin_session));
        if (canMoveUp) {
            options.add(mActivity.getString(R.string.action_move_session_up));
        }
        if (canMoveDown) {
            options.add(mActivity.getString(R.string.action_move_session_down));
        }

        final String[] optionArray = options.toArray(new String[0]);

        new AlertDialog.Builder(mActivity)
            .setItems(optionArray, (dialog, which) -> {
                String chosen = optionArray[which];
                if (chosen.equals(mActivity.getString(R.string.action_rename_session))) {
                    mActivity.getTermuxTerminalSessionClient().renameSession(terminalSession);
                } else if (chosen.equals(mActivity.getString(R.string.action_pin_session))) {
                    mOrderManager.setPinned(terminalSession.mHandle, true);
                    refreshDisplayList();
                } else if (chosen.equals(mActivity.getString(R.string.action_unpin_session))) {
                    mOrderManager.setPinned(terminalSession.mHandle, false);
                    refreshDisplayList();
                } else if (chosen.equals(mActivity.getString(R.string.action_move_session_up))) {
                    mOrderManager.moveUp(terminalSession.mHandle);
                    refreshDisplayList();
                } else if (chosen.equals(mActivity.getString(R.string.action_move_session_down))) {
                    mOrderManager.moveDown(terminalSession.mHandle);
                    refreshDisplayList();
                }
            })
            .show();

        return true;
    }

    /**
     * Check if a session can be moved in the given direction within its pin group.
     */
    private boolean canMoveInDirection(String handle, boolean up) {
        List<SessionOrderManager.OrderEntry> entries = mOrderManager.loadEntries();
        boolean targetPinned = false;
        int targetPos = -1;

        for (SessionOrderManager.OrderEntry e : entries) {
            if (e.handle.equals(handle)) {
                targetPinned = e.pinned;
                targetPos = e.position;
                break;
            }
        }

        if (targetPos < 0) return false;

        // Find if there's another entry in the same group above/below
        for (SessionOrderManager.OrderEntry e : entries) {
            if (e.pinned == targetPinned && e.handle.equals(handle)) continue;
            if (up && e.pinned == targetPinned && e.position < targetPos) return true;
            if (!up && e.pinned == targetPinned && e.position > targetPos) return true;
        }

        return false;
    }
}
