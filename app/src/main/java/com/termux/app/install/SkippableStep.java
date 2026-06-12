package com.termux.app.install;

/**
 * Mixin interface for an {@link InstallStep} that can determine installation is unnecessary.
 * <p>
 * When a step implementing this interface returns {@code true} from
 * {@link #shouldSkipRemaining()}, the coordinator short-circuits the pipeline
 * and transitions directly to {@link InstallState#SUCCESS}.
 * <p>
 * Primary use case: {@code CheckPrefixStep} detects that the prefix directory
 * already exists and is non-empty, meaning bootstrap installation is not needed.
 */
public interface SkippableStep {

    /**
     * Returns {@code true} if this step determined that remaining installation
     * steps should be skipped.
     * <p>
     * Only meaningful after {@link InstallStep#execute(InstallContext)} has been
     * called and returned {@code null} (success).
     */
    boolean shouldSkipRemaining();
}
