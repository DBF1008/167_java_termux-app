package com.termux.app.installer;

import com.termux.app.installer.steps.CommitStagingStep;
import com.termux.app.installer.steps.CreatePrefixDirStep;
import com.termux.app.installer.steps.CreateStagingDirStep;
import com.termux.app.installer.steps.CreateStorageSymlinksStep;
import com.termux.app.installer.steps.DeletePrefixStep;
import com.termux.app.installer.steps.DeleteStagingStep;
import com.termux.app.installer.steps.ExtractBootstrapStep;
import com.termux.app.installer.steps.PrepareStorageHomeStep;
import com.termux.app.installer.steps.WriteEnvFileStep;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Factory for the Termux install/storage transactions. Both are expressed as ordered
 * {@link InstallStep} sequences run through the shared {@link TransactionCoordinator}.
 */
public final class TermuxInstallTransactions {

    public static final String BOOTSTRAP_TRANSACTION_NAME = "bootstrap-install";
    public static final String STORAGE_TRANSACTION_NAME = "storage-symlinks";

    private TermuxInstallTransactions() {
    }

    /** The first-install / retry bootstrap transaction. */
    public static InstallTransaction createBootstrapTransaction() {
        CommitStagingStep commit = new CommitStagingStep();
        List<InstallStep> steps = new ArrayList<>(Arrays.asList(
            new DeleteStagingStep(),
            new DeletePrefixStep(),
            new CreateStagingDirStep(),
            new CreatePrefixDirStep(),
            new ExtractBootstrapStep(),
            commit,
            new WriteEnvFileStep()
        ));
        // Everything strictly after the commit (rename) step is post-commit and must not be unwound.
        int commitBoundaryIndex = steps.indexOf(commit) + 1;
        return new InstallTransaction(BOOTSTRAP_TRANSACTION_NAME, steps, commitBoundaryIndex);
    }

    /** The storage symlink transaction triggered by the storage-permission flow. */
    public static InstallTransaction createStorageSymlinkTransaction() {
        List<InstallStep> steps = new ArrayList<>(Arrays.asList(
            new PrepareStorageHomeStep(),
            new CreateStorageSymlinksStep()
        ));
        return new InstallTransaction(STORAGE_TRANSACTION_NAME, steps);
    }
}
