package com.termux.app.installer;

import com.termux.shared.errors.Error;

/**
 * A single unit of work in an {@link InstallTransaction}.
 *
 * <p>A step follows the codebase-wide {@link Error} convention: {@link #execute(InstallContext)}
 * returns {@code null} on success and a non-null {@link Error} on failure. A step may also throw;
 * the {@link TransactionCoordinator} wraps any thrown {@link Throwable} into an {@link Error} so the
 * failure path is uniform.
 *
 * <p>{@link #rollback(InstallContext)} is a best-effort cleanup of this step's partial effects. It
 * is only invoked by the coordinator for executed steps when a <em>pre-commit</em> failure occurs
 * (see {@link InstallTransaction#getCommitBoundaryIndex()}). To keep recovery safe, a step's
 * rollback must never delete already-committed state (notably {@code ctx.getPrefixPath()}).
 */
public interface InstallStep {

    /** A short, stable, human-readable name used in logs and progress reporting. */
    String getName();

    /**
     * Perform this step's work.
     *
     * @return {@code null} on success, otherwise the {@link Error} describing the failure.
     */
    Error execute(InstallContext context);

    /**
     * Best-effort undo of this step's effects. Default is a no-op. Implementations must be tolerant
     * of partial state (the step may have failed midway) and must never throw fatally — the
     * coordinator swallows and logs any throwable raised here.
     */
    default void rollback(InstallContext context) {
        // No-op by default.
    }
}
