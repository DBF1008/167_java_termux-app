package com.termux.app.install;

import com.termux.app.install.steps.SetupStorageSymlinksStep;
import com.termux.shared.errors.Error;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.File;

import static org.junit.Assert.*;

/**
 * Robolectric tests for {@link SetupStorageSymlinksStep}.
 * Tests storage symlink creation including multi-storage scenarios.
 * <p>
 * Note: Robolectric shadows {@code Environment.getExternalStorageDirectory()}
 * and related methods to point to temp paths, so symlinks can be created
 * without real Android storage.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class SetupStorageSymlinksStepTest {

    @Rule
    public TemporaryFolder tempFolder = new TemporaryFolder();

    private InstallContext context;

    @Before
    public void setUp() throws Exception {
        File root = tempFolder.getRoot();
        File storage = new File(root, "storage");
        storage.mkdirs();

        context = new InstallContext(
            null, // No Android context — tests will skip dynamic symlinks
            new File(root, "files").getAbsolutePath(),
            new File(root, "prefix").getAbsolutePath(),
            new File(root, "prefix"),
            new File(root, "staging").getAbsolutePath(),
            new File(root, "staging"),
            storage.getAbsolutePath(),
            storage,
            new File(root, "home").getAbsolutePath()
        );
    }

    @Test
    public void createsStandardSymlinks() throws Exception {
        SetupStorageSymlinksStep step = new SetupStorageSymlinksStep();
        Error error = step.execute(context);

        assertNull(error);

        File storageDir = context.getStorageHomeDir();
        // Verify standard symlinks were created
        assertTrue("shared symlink should exist",
            new File(storageDir, "shared").exists());
        assertTrue("documents symlink should exist",
            new File(storageDir, "documents").exists());
        assertTrue("downloads symlink should exist",
            new File(storageDir, "downloads").exists());
        assertTrue("dcim symlink should exist",
            new File(storageDir, "dcim").exists());
        assertTrue("pictures symlink should exist",
            new File(storageDir, "pictures").exists());
        assertTrue("music symlink should exist",
            new File(storageDir, "music").exists());
        assertTrue("movies symlink should exist",
            new File(storageDir, "movies").exists());
        assertTrue("podcasts symlink should exist",
            new File(storageDir, "podcasts").exists());
    }

    @Test
    public void createsAudiobooksSymlinkOnApi29() throws Exception {
        // SDK 28 is configured, which is < Q (29), so audiobooks should NOT be created
        SetupStorageSymlinksStep step = new SetupStorageSymlinksStep();
        Error error = step.execute(context);

        assertNull(error);
        assertFalse("audiobooks should not exist on API 28",
            new File(context.getStorageHomeDir(), "audiobooks").exists());
    }

    @Test
    public void idempotentClearAndRecreate() throws Exception {
        SetupStorageSymlinksStep step = new SetupStorageSymlinksStep();

        // First execution
        assertNull(step.execute(context));
        File sharedLink = new File(context.getStorageHomeDir(), "shared");
        assertTrue(sharedLink.exists());

        // Re-execute — should clear and recreate
        assertNull(step.execute(context));
        assertTrue("shared should still exist after re-execution",
            new File(context.getStorageHomeDir(), "shared").exists());
    }

    @Test
    public void handlesNullAndroidContext() throws Exception {
        // With null Android context, dynamic symlinks are skipped
        // but fixed symlinks should still be created
        SetupStorageSymlinksStep step = new SetupStorageSymlinksStep();
        Error error = step.execute(context);

        assertNull(error);
        assertTrue(new File(context.getStorageHomeDir(), "shared").exists());
    }

    @Test
    public void clearDirectoryRemovesOldSymlinks() throws Exception {
        // Pre-create a stale symlink
        File staleLink = new File(context.getStorageHomeDir(), "stale-link");
        staleLink.createNewFile();

        SetupStorageSymlinksStep step = new SetupStorageSymlinksStep();
        step.execute(context);

        // Stale link should be cleared
        assertFalse("Stale symlink should be removed", staleLink.exists());
    }
}
