package com.termux.shared.termux.settings.properties;

/**
 * The resolved on/off state of the optional Termux app components that are driven by
 * {@code termux.properties} values.
 *
 * <p>This is a pure value object with no Android dependencies. It centralizes the mapping from the
 * raw property values (some of which use inverted "disable-*" semantics) to a single, consistent
 * notion of whether each component should be <i>enabled/running</i>. Keeping this decision separate
 * from the side effects of applying it (toggling {@code PackageManager} component states, starting or
 * stopping the {@code termux-am} socket server) lets the policy be unit tested without an Android
 * runtime, and gives the configuration orchestration layer a single source of truth to consult.
 *
 * <p>Construct it from the loaded {@link TermuxAppSharedProperties} via {@link #from(TermuxSharedProperties)},
 * or directly from primitive inputs in tests.
 */
public final class TermuxConfigurationState {

    private final boolean mFileShareReceiverEnabled;
    private final boolean mFileViewReceiverEnabled;
    private final boolean mAmSocketServerEnabled;

    /**
     * @param disableFileShareReceiver The value of {@link TermuxPropertyConstants#KEY_DISABLE_FILE_SHARE_RECEIVER}.
     * @param disableFileViewReceiver  The value of {@link TermuxPropertyConstants#KEY_DISABLE_FILE_VIEW_RECEIVER}.
     * @param runAmSocketServer        The value of {@link TermuxPropertyConstants#KEY_RUN_TERMUX_AM_SOCKET_SERVER}.
     */
    public TermuxConfigurationState(boolean disableFileShareReceiver, boolean disableFileViewReceiver,
                                    boolean runAmSocketServer) {
        // The file receiver properties use inverted "disable-*" semantics; normalize to "enabled".
        mFileShareReceiverEnabled = !disableFileShareReceiver;
        mFileViewReceiverEnabled = !disableFileViewReceiver;
        mAmSocketServerEnabled = runAmSocketServer;
    }

    /** Build the state from the currently loaded {@link TermuxSharedProperties}. */
    public static TermuxConfigurationState from(TermuxSharedProperties properties) {
        return new TermuxConfigurationState(
            properties.isFileShareReceiverDisabled(),
            properties.isFileViewReceiverDisabled(),
            properties.shouldRunTermuxAmSocketServer());
    }

    /** Whether the {@code FILE_SHARE_RECEIVER} activity component should be enabled. */
    public boolean isFileShareReceiverEnabled() {
        return mFileShareReceiverEnabled;
    }

    /** Whether the {@code FILE_VIEW_RECEIVER} activity component should be enabled. */
    public boolean isFileViewReceiverEnabled() {
        return mFileViewReceiverEnabled;
    }

    /** Whether the {@code termux-am} socket server should be running. */
    public boolean isAmSocketServerEnabled() {
        return mAmSocketServerEnabled;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof TermuxConfigurationState)) return false;
        TermuxConfigurationState that = (TermuxConfigurationState) o;
        return mFileShareReceiverEnabled == that.mFileShareReceiverEnabled
            && mFileViewReceiverEnabled == that.mFileViewReceiverEnabled
            && mAmSocketServerEnabled == that.mAmSocketServerEnabled;
    }

    @Override
    public int hashCode() {
        int result = (mFileShareReceiverEnabled ? 1 : 0);
        result = 31 * result + (mFileViewReceiverEnabled ? 1 : 0);
        result = 31 * result + (mAmSocketServerEnabled ? 1 : 0);
        return result;
    }

    @Override
    public String toString() {
        return "TermuxConfigurationState{" +
            "fileShareReceiverEnabled=" + mFileShareReceiverEnabled +
            ", fileViewReceiverEnabled=" + mFileViewReceiverEnabled +
            ", amSocketServerEnabled=" + mAmSocketServerEnabled +
            '}';
    }

}
