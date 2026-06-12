package com.termux.app.install;

import com.termux.app.install.steps.AtomicRenameStep;
import com.termux.app.install.steps.CleanDirectoriesStep;
import com.termux.app.install.steps.CreateBootstrapSymlinksStep;
import com.termux.app.install.steps.ExtractBootstrapStep;
import com.termux.shared.errors.Error;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static com.termux.app.install.InstallState.*;
import static org.junit.Assert.*;

/**
 * End-to-end integration tests for the {@link InstallCoordinator} pipeline
 * using real file system operations with Robolectric.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class InstallCoordinatorIntegrationTest {

    @Rule
    public TemporaryFolder tempFolder = new TemporaryFolder();

    private InstallContext context;

    @Before
    public void setUp() throws Exception {
        File root = tempFolder.getRoot();
        File staging = new File(root, "staging");
        staging.mkdirs();
        File prefix = new File(root, "prefix");
        // Don't create prefix — AtomicRenameStep needs it absent

        context = new InstallContext(
            null,
            new File(root, "files").getAbsolutePath(),
            prefix.getAbsolutePath(),
            prefix,
            staging.getAbsolutePath(),
            staging,
            new File(root, "storage").getAbsolutePath(),
            new File(root, "storage"),
            new File(root, "home").getAbsolutePath()
        );
    }

    // ─── Full pipeline with real file system ───────────────────

    @Test
    public void fullPipeline_extractSymlinkRename() throws Exception {
        byte[] zipBytes = createValidBootstrapZip();

        List<InstallStep> steps = Arrays.asList(
            new CleanDirectoriesStep(),
            new ExtractBootstrapStep(() -> zipBytes),
            new CreateBootstrapSymlinksStep(),
            new AtomicRenameStep()
        );

        InstallCoordinator coordinator = new InstallCoordinator(
            context, steps, Collections.emptyList());

        RecordingListener listener = new RecordingListener();
        coordinator.addListener(listener);

        Error result = coordinator.runFullInstall();

        assertNull(result);
        assertEquals(SUCCESS, coordinator.getCurrentState());

        // Verify prefix has the content
        assertTrue("Prefix should exist", context.getPrefixDir().exists());
        assertTrue("bin/bash should be in prefix",
            new File(context.getPrefixDir(), "bin/bash").exists());
    }

    // ─── Extraction failure triggers rollback ──────────────────

    @Test
    public void extractionFailure_triggersRollback() throws Exception {
        List<InstallStep> steps = Arrays.asList(
            new CleanDirectoriesStep(),
            new ExtractBootstrapStep(() -> {
                throw new RuntimeException("corrupt zip");
            })
        );

        InstallCoordinator coordinator = new InstallCoordinator(
            context, steps, Collections.emptyList());

        Error result = coordinator.runFullInstall();

        assertNotNull(result);
        assertEquals(FAILED, coordinator.getCurrentState());
    }

    // ─── Rename failure after extraction ───────────────────────

    @Test
    public void renameFailure_afterSuccessfulExtraction() throws Exception {
        byte[] zipBytes = createValidBootstrapZip();

        // Block rename by creating a file at the prefix path
        File prefixFile = context.getPrefixDir();
        prefixFile.getParentFile().mkdirs();
        try (java.io.FileWriter w = new java.io.FileWriter(prefixFile)) {
            w.write("blocking");
        }

        List<InstallStep> steps = Arrays.asList(
            new CleanDirectoriesStep(),
            new ExtractBootstrapStep(() -> zipBytes),
            new CreateBootstrapSymlinksStep(),
            new AtomicRenameStep()
        );

        InstallCoordinator coordinator = new InstallCoordinator(
            context, steps, Collections.emptyList());

        Error result = coordinator.runFullInstall();

        assertNotNull(result);
        assertEquals(FAILED, coordinator.getCurrentState());
        assertTrue("Error should mention rename",
            result.getMessage().contains("staging") || result.getMessage().contains("prefix"));
    }

    // ─── Multi-storage directory scenario ──────────────────────

    @Test
    public void storageSetup_clearsAndRecreates() throws Exception {
        File storageDir = context.getStorageHomeDir();
        storageDir.mkdirs();

        // Pre-create stale files
        new File(storageDir, "stale1").createNewFile();
        new File(storageDir, "stale2").createNewFile();

        // Use only the storage step with null android context
        // (will create fixed symlinks but skip dynamic ones)
        com.termux.app.install.steps.SetupStorageSymlinksStep storageStep =
            new com.termux.app.install.steps.SetupStorageSymlinksStep();

        List<InstallStep> steps = Collections.singletonList(storageStep);
        InstallCoordinator coordinator = new InstallCoordinator(
            context, Collections.emptyList(), steps);

        Error result = coordinator.runStorageSetup();

        assertNull(result);
        assertFalse("Stale file should be cleared",
            new File(storageDir, "stale1").exists());
        assertFalse("Stale file should be cleared",
            new File(storageDir, "stale2").exists());
        assertTrue("shared symlink should exist",
            new File(storageDir, "shared").exists());
    }

    // ─── Retry after failure ───────────────────────────────────

    @Test
    public void retryAfterExtractionFailure() throws Exception {
        byte[] validZip = createValidBootstrapZip();
        boolean[] firstAttempt = {true};

        ExtractBootstrapStep.ZipBytesProvider flakyProvider = () -> {
            if (firstAttempt[0]) {
                firstAttempt[0] = false;
                throw new RuntimeException("transient failure");
            }
            return validZip;
        };

        List<InstallStep> steps = Arrays.asList(
            new CleanDirectoriesStep(),
            new ExtractBootstrapStep(flakyProvider),
            new CreateBootstrapSymlinksStep(),
            new AtomicRenameStep()
        );

        InstallCoordinator coordinator = new InstallCoordinator(
            context, steps, Collections.emptyList());

        // First attempt fails
        Error firstResult = coordinator.runFullInstall();
        assertNotNull(firstResult);
        assertEquals(FAILED, coordinator.getCurrentState());

        // Retry succeeds
        Error retryResult = coordinator.retryFullInstall();
        assertNull(retryResult);
        assertEquals(SUCCESS, coordinator.getCurrentState());
        assertTrue("Prefix should exist after retry",
            context.getPrefixDir().exists());
    }

    // ─── Listener receives all state transitions ───────────────

    @Test
    public void listenerReceivesAllTransitions() throws Exception {
        byte[] zipBytes = createValidBootstrapZip();

        List<InstallStep> steps = Arrays.asList(
            new CleanDirectoriesStep(),    // PREPARING
            new ExtractBootstrapStep(() -> zipBytes), // EXTRACTING
            new AtomicRenameStep()          // FINALIZING
        );

        InstallCoordinator coordinator = new InstallCoordinator(
            context, steps, Collections.emptyList());

        RecordingListener listener = new RecordingListener();
        coordinator.addListener(listener);

        coordinator.runFullInstall();

        assertTrue(listener.stateHistory.contains(PREPARING));
        assertTrue(listener.stateHistory.contains(EXTRACTING));
        assertTrue(listener.stateHistory.contains(FINALIZING));
        assertTrue(listener.stateHistory.contains(SUCCESS));
        assertTrue(listener.installCompleteCalled);
        assertTrue(listener.installCompleteSuccess);
    }

    // ─── Helpers ───────────────────────────────────────────────

    private byte[] createValidBootstrapZip() throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(baos)) {
            // Add bin/bash
            addZipEntry(zos, "bin/bash", "#!/bin/sh\necho hello");
            // Add SYMLINKS.txt
            addZipEntry(zos, "SYMLINKS.txt", "/bin/sh←bin/login");
        }
        return baos.toByteArray();
    }

    private void addZipEntry(ZipOutputStream zos, String name, String content)
            throws IOException {
        zos.putNextEntry(new ZipEntry(name));
        zos.write(content.getBytes(StandardCharsets.UTF_8));
        zos.closeEntry();
    }

    static class RecordingListener implements InstallStateListener {
        final java.util.List<InstallState> stateHistory = new java.util.ArrayList<>();
        boolean installCompleteCalled = false;
        boolean installCompleteSuccess = false;
        Error installCompleteError = null;

        @Override
        public void onStateChanged(InstallState newState) {
            stateHistory.add(newState);
        }

        @Override
        public void onInstallComplete(boolean success, Error error) {
            installCompleteCalled = true;
            installCompleteSuccess = success;
            installCompleteError = error;
        }
    }
}
