package com.termux.app;

import android.view.ViewGroup;
import android.widget.RelativeLayout;

import androidx.annotation.NonNull;
import androidx.viewpager.widget.ViewPager;

import com.termux.R;
import com.termux.app.api.file.FileReceiverActivity;
import com.termux.app.terminal.TermuxTerminalSessionActivityClient;
import com.termux.app.terminal.TermuxTerminalViewClient;
import com.termux.app.terminal.io.TermuxTerminalExtraKeys;
import com.termux.shared.activity.media.AppCompatActivityUtils;
import com.termux.shared.logger.Logger;
import com.termux.shared.termux.extrakeys.ExtraKeysView;
import com.termux.shared.termux.settings.properties.TermuxAppSharedProperties;
import com.termux.shared.termux.shell.am.TermuxAmSocketServer;
import com.termux.shared.termux.theme.TermuxThemeUtils;
import com.termux.shared.theme.NightMode;
import com.termux.shared.view.ViewUtils;

/**
 * The single configuration orchestration layer for {@link TermuxActivity}.
 *
 * <p>Historically the steps needed to apply {@code termux.properties}/preference driven configuration —
 * reloading properties, the night theme, terminal margins, the extra keys, the toolbar height, the
 * file-share/-view receiver components and the {@code termux-am} socket server — were spread across
 * {@link TermuxApplication#onCreate}, {@link TermuxActivity#onCreate} and the old
 * {@code reloadActivityStyling()} method, and had drifted apart. This class centralizes them so the
 * three entry points that need to (re)apply configuration share one consistent flow:
 *
 * <ul>
 *   <li><b>UI rebuild</b> — the {@code ACTION_RELOAD_STYLE} broadcast (sent by {@code termux-reload-settings}
 *       and {@link TermuxActivity#updateTermuxActivityStyling}) routes to {@link #reloadConfiguration(boolean)}.</li>
 *   <li><b>Component start/stop</b> — {@link #updateComponentsState()} reconciles the file-receiver
 *       components and the socket server with the current properties.</li>
 *   <li><b>Subsequent new-session initialization</b> — {@link com.termux.app.TermuxService} fires the same
 *       reload broadcast after each session is created, so a new session inherits the same applied state.</li>
 * </ul>
 *
 * <p>The instance only holds a reference to its {@link TermuxActivity} and reads everything else through
 * the activity's existing accessors, so it owns no duplicated state.
 */
public final class TermuxActivityConfigurer {

    private final TermuxActivity mActivity;

    private static final String LOG_TAG = "TermuxActivityConfigurer";

    public TermuxActivityConfigurer(@NonNull TermuxActivity activity) {
        this.mActivity = activity;
    }

    private TermuxAppSharedProperties getProperties() {
        return mActivity.getProperties();
    }

    /**
     * Apply the activity night theme. Must be called from {@link TermuxActivity#onCreate} <b>before</b>
     * {@code super.onCreate()} so the AppCompat delegate applies the day/night mode while creating its
     * views in a single pass. Sets both the app-wide {@link NightMode} and the activity-local mode.
     */
    public void applyActivityTheme() {
        // Update NightMode.APP_NIGHT_MODE
        TermuxThemeUtils.setAppNightMode(getProperties().getNightMode());

        // Set activity night mode. If NightMode.SYSTEM is set, then android will automatically
        // trigger recreation of activity when uiMode/dark mode configuration is changed so that
        // day or night theme takes affect.
        AppCompatActivityUtils.setNightMode(mActivity, NightMode.getAppNightMode().getName(), true);
    }

    /** Reload {@code termux.properties} from disk and notify the terminal view client. */
    public void reloadProperties() {
        getProperties().loadTermuxPropertiesFromDisk();

        TermuxTerminalViewClient viewClient = mActivity.getTermuxTerminalViewClient();
        if (viewClient != null)
            viewClient.onReloadProperties();
    }

    /** Apply the terminal margins from properties. Requires the content view to be inflated. */
    public void setMargins() {
        RelativeLayout relativeLayout = mActivity.findViewById(R.id.activity_termux_root_relative_layout);
        int marginHorizontal = getProperties().getTerminalMarginHorizontal();
        int marginVertical = getProperties().getTerminalMarginVertical();
        ViewUtils.setLayoutMarginsInDp(relativeLayout, marginHorizontal, marginVertical, marginHorizontal, marginVertical);
    }

    /**
     * Apply the toolbar height from the number of extra-keys rows and the height scale factor property.
     * Must run after the extra keys exist and, on a reload, after {@link TermuxTerminalExtraKeys#refresh()}
     * so the row count reflects the current properties.
     */
    public void setTerminalToolbarHeight() {
        final ViewPager terminalToolbarViewPager = mActivity.getTerminalToolbarViewPager();
        if (terminalToolbarViewPager == null) return;

        TermuxTerminalExtraKeys extraKeys = mActivity.getTermuxTerminalExtraKeys();
        if (extraKeys == null) return;

        ViewGroup.LayoutParams layoutParams = terminalToolbarViewPager.getLayoutParams();
        layoutParams.height = Math.round(mActivity.getTerminalToolbarDefaultHeight() *
            (extraKeys.getExtraKeysInfo() == null ? 0 : extraKeys.getExtraKeysInfo().getMatrix().length) *
            getProperties().getTerminalToolbarHeightScaleFactor());
        terminalToolbarViewPager.setLayoutParams(layoutParams);
    }

    /**
     * Reconcile the optional components that are driven by properties with their desired state:
     * the file-share/-view receiver activity components and the {@code termux-am} socket server.
     *
     * <p>Both operations touch slow/native resources (the file-receiver method spawns its own thread to
     * call {@code PackageManager}; the socket server may open/close a filesystem socket), so the socket
     * refresh is dispatched off the main thread here. Both are idempotent — when the relevant property is
     * unchanged they are effectively no-ops.
     */
    public void updateComponentsState() {
        // Toggle the file-share/-view receiver components (self-threaded inside the method).
        FileReceiverActivity.updateFileReceiverActivityComponentsState(mActivity);

        // Start/stop the termux-am socket server to match the property, off the main thread.
        final TermuxActivity context = mActivity;
        new Thread(() -> TermuxAmSocketServer.updateState(context.getApplicationContext())).start();
    }

    /**
     * The single unified configuration reload, invoked for the {@code ACTION_RELOAD_STYLE} broadcast.
     *
     * @param recreateActivity If {@code true}, the activity is recreated so that the theme and all of
     *                         {@link TermuxActivity#onCreate}'s setup is re-applied (used for theme
     *                         changes and the default {@code termux-reload-settings} path). If
     *                         {@code false}, configuration is applied in place without rebuilding the
     *                         activity (preserves the visible terminal, used after a new session is created).
     */
    public void reloadConfiguration(boolean recreateActivity) {
        reloadProperties();

        // Component start/stop always reflects the current properties, independent of recreate.
        updateComponentsState();

        // Keep the app-wide night mode static in sync; a recreated activity reads it in applyActivityTheme().
        TermuxThemeUtils.setAppNightMode(getProperties().getNightMode());

        if (recreateActivity) {
            // Recreating destroys and rebuilds the activity, re-running onCreate() which re-applies the
            // full configuration (theme, margins, fullscreen, extra keys, components). Extra keys input
            // text, terminal sessions and transcripts are preserved across the recreate.
            Logger.logDebug(LOG_TAG, "Recreating activity to apply configuration");
            mActivity.recreate();
            return;
        }

        applyInPlaceStyling();
    }

    /** Apply everything that can change without recreating the activity. */
    private void applyInPlaceStyling() {
        setMargins();

        // Rebuild the extra keys from the freshly loaded properties. refresh() mutates the existing
        // TermuxTerminalExtraKeys in place so the ExtraKeysView client reference stays valid; then
        // re-apply the rebuilt info and the dependent toolbar height. Without the refresh, a changed
        // extra-keys/extra-keys-style would not take effect until the activity was recreated.
        TermuxTerminalExtraKeys extraKeys = mActivity.getTermuxTerminalExtraKeys();
        if (extraKeys != null) {
            extraKeys.refresh();

            ExtraKeysView extraKeysView = mActivity.getExtraKeysView();
            if (extraKeysView != null) {
                extraKeysView.setButtonTextAllCaps(getProperties().shouldExtraKeysTextBeAllCaps());
                extraKeysView.reload(extraKeys.getExtraKeysInfo(), mActivity.getTerminalToolbarDefaultHeight());
            }
        }

        setTerminalToolbarHeight();

        TermuxTerminalSessionActivityClient sessionClient = mActivity.getTermuxTerminalSessionClient();
        if (sessionClient != null)
            sessionClient.onReloadActivityStyling();

        TermuxTerminalViewClient viewClient = mActivity.getTermuxTerminalViewClient();
        if (viewClient != null)
            viewClient.onReloadActivityStyling();
    }

}
