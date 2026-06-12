package com.termux.app.install;

import com.termux.shared.errors.Error;

/**
 * A single atomic unit of work in the installation lifecycle.
 * <p>
 * Each step must be:
 * <ul>
 *   <li><b>Idempotent</b>: safe to re-execute from scratch</li>
 *   <li><b>Self-contained</b>: returns {@link Error} on failure instead of throwing</li>
 *   <li><b>Rollback-aware</b>: can clean up partial work if a later step fails</li>
 * </ul>
 */
public interface InstallStep {

    /**
     * Human-readable name for logging and error reporting.
     */
    String getName();

    /**
     * Execute this step.
     *
     * @param context The install context providing paths, Android context, etc.
     * @return {@code null} on success, or an {@link Error} describing what went wrong.
     */
    Error execute(InstallContext context);

    /**
     * Roll back any partial work done by this step.
     * Called when a <b>later</b> step fails, not when this step fails.
     * <p>
     * Default implementation is a no-op for steps that don't need cleanup.
     *
     * @param context The install context.
     * @return {@code null} on success, or an {@link Error} describing rollback failure.
     */
    default Error rollback(InstallContext context) {
        return null;
    }

    /**
     * The {@link InstallState} this step transitions the coordinator INTO upon successful completion.
     */
    InstallState getResultingState();
}
