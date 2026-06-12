package com.termux.app.install;

/**
 * States for the install/init lifecycle state machine.
 * <p>
 * Modeled after {@code ExecutionCommand.ExecutionState} with finer granularity
 * for multi-step installation. Transitions are validated via
 * {@link #isValidTransition(InstallState, InstallState)}.
 * <p>
 * Valid forward flow:
 * <pre>
 *   IDLE → VALIDATING → PREPARING → EXTRACTING → FINALIZING → POST_INSTALL → SUCCESS
 * </pre>
 * <p>
 * Any non-terminal state can transition to FAILED.
 * FAILED can transition to IDLE (reset for retry).
 * VALIDATING can shortcut to SUCCESS (prefix already exists).
 */
public enum InstallState {

    /** No installation has been requested yet. */
    IDLE("Idle", 0),

    /** Pre-condition checks (files dir, primary user, prefix existence). */
    VALIDATING("Validating", 1),

    /** Cleaning staging/prefix directories and creating fresh ones. */
    PREPARING("Preparing", 2),

    /** Extracting bootstrap zip into staging directory. */
    EXTRACTING("Extracting", 3),

    /** Creating symlinks from SYMLINKS.txt and renaming staging to prefix. */
    FINALIZING("Finalizing", 4),

    /** Writing environment file and setting up storage symlinks. */
    POST_INSTALL("Post-Install", 5),

    /** All steps completed successfully. Terminal state. */
    SUCCESS("Success", 6),

    /** A step failed. Terminal state until reset via FAILED → IDLE. */
    FAILED("Failed", 7);


    private final String name;
    private final int value;

    InstallState(String name, int value) {
        this.name = name;
        this.value = value;
    }

    public String getName() {
        return name;
    }

    public int getValue() {
        return value;
    }

    /**
     * Returns {@code true} if this state is terminal (no further forward progress).
     */
    public boolean isTerminal() {
        return this == SUCCESS || this == FAILED;
    }

    /**
     * Returns {@code true} if transitioning from {@code current} to {@code next} is valid.
     * <p>
     * Rules:
     * <ul>
     *   <li>Any non-terminal state can transition to FAILED</li>
     *   <li>IDLE → VALIDATING</li>
     *   <li>VALIDATING → PREPARING | SUCCESS (shortcut when prefix exists)</li>
     *   <li>PREPARING → EXTRACTING</li>
     *   <li>EXTRACTING → FINALIZING</li>
     *   <li>FINALIZING → POST_INSTALL</li>
     *   <li>POST_INSTALL → SUCCESS</li>
     *   <li>FAILED → IDLE (reset for retry)</li>
     *   <li>SUCCESS has no outgoing transitions</li>
     * </ul>
     */
    public static boolean isValidTransition(InstallState current, InstallState next) {
        // Any non-terminal state can fail
        if (next == FAILED && !current.isTerminal()) {
            return true;
        }

        switch (current) {
            case IDLE:
                return next == VALIDATING;
            case VALIDATING:
                return next == PREPARING || next == SUCCESS;
            case PREPARING:
                return next == EXTRACTING;
            case EXTRACTING:
                return next == FINALIZING;
            case FINALIZING:
                return next == POST_INSTALL;
            case POST_INSTALL:
                return next == SUCCESS;
            case FAILED:
                return next == IDLE;
            case SUCCESS:
                return false;
            default:
                return false;
        }
    }
}
