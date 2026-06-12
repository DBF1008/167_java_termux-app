package com.termux.app.install.steps;

import com.termux.app.install.InstallContext;
import com.termux.app.install.InstallErrno;
import com.termux.app.install.InstallState;
import com.termux.app.install.InstallStep;
import com.termux.shared.errors.Error;

/**
 * Atomically renames the staging directory to become the prefix directory.
 * <p>
 * <b>Key change from original</b>: The original code throws
 * {@code RuntimeException("Moving termux prefix staging to prefix directory failed")}.
 * This step returns a structured {@link Error} instead.
 */
public class AtomicRenameStep implements InstallStep {

    @Override
    public String getName() {
        return "Atomic Rename Staging to Prefix";
    }

    @Override
    public InstallState getResultingState() {
        return InstallState.FINALIZING;
    }

    @Override
    public Error execute(InstallContext context) {
        if (!context.getStagingDir().renameTo(context.getPrefixDir())) {
            return InstallErrno.ERRNO_ATOMIC_RENAME_FAILED.getError(
                context.getStagingDirPath(), context.getPrefixDirPath());
        }
        return null;
    }
}
