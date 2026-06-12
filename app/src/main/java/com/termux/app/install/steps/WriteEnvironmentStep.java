package com.termux.app.install.steps;

import com.termux.app.install.InstallContext;
import com.termux.app.install.InstallErrno;
import com.termux.app.install.InstallState;
import com.termux.app.install.InstallStep;
import com.termux.shared.errors.Error;
import com.termux.shared.termux.shell.command.environment.TermuxShellEnvironment;

/**
 * Writes the environment file after bootstrap installation.
 * <p>
 * This is needed because the prefix was wiped and recreated during installation,
 * so the environment file ({@code termux.env}) must be regenerated.
 */
public class WriteEnvironmentStep implements InstallStep {

    @Override
    public String getName() {
        return "Write Environment File";
    }

    @Override
    public InstallState getResultingState() {
        return InstallState.POST_INSTALL;
    }

    @Override
    public Error execute(InstallContext context) {
        try {
            TermuxShellEnvironment.writeEnvironmentToFile(context.getAndroidContext());
            return null;
        } catch (Exception e) {
            return InstallErrno.ERRNO_ENV_WRITE_FAILED.getError(e, e.getMessage());
        }
    }
}
