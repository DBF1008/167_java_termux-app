package com.termux.app.api.file;

import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManager;

import com.termux.shared.termux.TermuxConstants;
import com.termux.shared.termux.TermuxConstants.TERMUX_APP;

import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

/**
 * Regression tests for the safe hot-reload of the file share/view receiver entry components.
 *
 * Exercises {@link FileReceiverActivity#setFileReceiverComponentsState(Context, boolean, boolean)}
 * (the synchronous core of {@code updateFileReceiverActivityComponentsState}) and asserts the actual
 * component enabled-settings via the {@link PackageManager}.
 *
 * Covered scenarios: enable, disable, repeated reload (idempotency), and independent toggling of the
 * two entries. Note that {@code PackageUtils.setComponentState} only flips a component when the
 * desired state differs from the current one, so enabling is asserted by first disabling to force a
 * real transition to {@link PackageManager#COMPONENT_ENABLED_STATE_ENABLED}.
 */
@RunWith(RobolectricTestRunner.class)
public class FileReceiverActivityReloadTest {

    private Context context;
    private PackageManager packageManager;

    private static final ComponentName SHARE_RECEIVER = new ComponentName(
        TermuxConstants.TERMUX_PACKAGE_NAME, TERMUX_APP.FILE_SHARE_RECEIVER_ACTIVITY_CLASS_NAME);
    private static final ComponentName VIEW_RECEIVER = new ComponentName(
        TermuxConstants.TERMUX_PACKAGE_NAME, TERMUX_APP.FILE_VIEW_RECEIVER_ACTIVITY_CLASS_NAME);

    @Before
    public void setUp() {
        context = RuntimeEnvironment.getApplication();
        packageManager = context.getPackageManager();
    }

    /** Disabling hides both the file share and file view entries. */
    @Test
    public void testDisablingHidesShareAndViewEntries() {
        FileReceiverActivity.setFileReceiverComponentsState(context, true, true);

        Assert.assertEquals(PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
            packageManager.getComponentEnabledSetting(SHARE_RECEIVER));
        Assert.assertEquals(PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
            packageManager.getComponentEnabledSetting(VIEW_RECEIVER));
    }

    /** Enabling (after a previous disable) restores both entries. */
    @Test
    public void testEnablingShowsShareAndViewEntries() {
        // disable first so that enabling forces a real transition to ENABLED
        FileReceiverActivity.setFileReceiverComponentsState(context, true, true);
        FileReceiverActivity.setFileReceiverComponentsState(context, false, false);

        Assert.assertEquals(PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
            packageManager.getComponentEnabledSetting(SHARE_RECEIVER));
        Assert.assertEquals(PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
            packageManager.getComponentEnabledSetting(VIEW_RECEIVER));
    }

    /** Repeating the same reload does not change the resulting component state. */
    @Test
    public void testRepeatedReloadIsIdempotent() {
        // enabled state stays enabled when reloaded again
        FileReceiverActivity.setFileReceiverComponentsState(context, true, true);
        FileReceiverActivity.setFileReceiverComponentsState(context, false, false);
        FileReceiverActivity.setFileReceiverComponentsState(context, false, false);
        Assert.assertEquals(PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
            packageManager.getComponentEnabledSetting(SHARE_RECEIVER));
        Assert.assertEquals(PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
            packageManager.getComponentEnabledSetting(VIEW_RECEIVER));

        // disabled state stays disabled when reloaded again
        FileReceiverActivity.setFileReceiverComponentsState(context, true, true);
        FileReceiverActivity.setFileReceiverComponentsState(context, true, true);
        Assert.assertEquals(PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
            packageManager.getComponentEnabledSetting(SHARE_RECEIVER));
        Assert.assertEquals(PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
            packageManager.getComponentEnabledSetting(VIEW_RECEIVER));
    }

    /** The share and view entries can be toggled independently of each other. */
    @Test
    public void testShareAndViewTogglesAreIndependent() {
        // disable both so each subsequent change forces a real transition
        FileReceiverActivity.setFileReceiverComponentsState(context, true, true);
        // keep share disabled, re-enable view
        FileReceiverActivity.setFileReceiverComponentsState(context, true, false);

        Assert.assertEquals(PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
            packageManager.getComponentEnabledSetting(SHARE_RECEIVER));
        Assert.assertEquals(PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
            packageManager.getComponentEnabledSetting(VIEW_RECEIVER));
    }

}
