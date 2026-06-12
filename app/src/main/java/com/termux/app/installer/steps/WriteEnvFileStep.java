package com.termux.app.installer.steps;

import com.termux.app.installer.InstallContext;
import com.termux.app.installer.InstallStep;
import com.termux.shared.errors.Error;
import com.termux.shared.logger.Logger;

/**
 * (Re)writes the Termux environment file after the prefix has been committed.
 *
 * <p>This step runs after the commit boundary and is intentionally non-fatal: a failure to write the
 * environment file must never turn an otherwise-successful install into a failure. Any error is
 * logged and the step reports success so the install completes and the caller's completion callback
 * still runs.
 */
public final class WriteEnvFileStep implements InstallStep {

    private static final String LOG_TAG = "WriteEnvFileStep";

    @Override
    public String getName() {
        return "write-environment-file";
    }

    @Override
    public Error execute(InstallContext context) {
        try {
            context.getEnvironment().writeEnvironmentFile(context.getAndroidContext());
        } catch (Throwable t) {
            Logger.logStackTraceWithMessage(LOG_TAG, "Writing the environment file failed (non-fatal)", t);
        }
        return null;
    }
}
