package com.termux.app.install;

import com.termux.app.install.steps.AtomicRenameStep;
import com.termux.shared.errors.Error;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.File;
import java.io.FileWriter;

import static org.junit.Assert.*;

/**
 * Robolectric tests for {@link AtomicRenameStep}.
 * Tests the staging→prefix rename operation.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class AtomicRenameStepTest {

    @Rule
    public TemporaryFolder tempFolder = new TemporaryFolder();

    private InstallContext context;

    @Before
    public void setUp() throws Exception {
        File root = tempFolder.getRoot();
        File staging = new File(root, "staging");
        staging.mkdirs();
        File prefix = new File(root, "prefix");
        // Don't create prefix yet — renameTo needs target to not exist

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

    @Test
    public void successfulRename() throws Exception {
        // Create content in staging
        File testFile = new File(context.getStagingDir(), "test.txt");
        try (FileWriter writer = new FileWriter(testFile)) {
            writer.write("hello");
        }

        AtomicRenameStep step = new AtomicRenameStep();
        Error error = step.execute(context);

        assertNull(error);
        assertTrue("Prefix should exist after rename", context.getPrefixDir().exists());
        assertTrue("Content should be in prefix",
            new File(context.getPrefixDir(), "test.txt").exists());
        assertFalse("Staging should not exist after rename",
            context.getStagingDir().exists());
    }

    @Test
    public void renameFailureReturnsError() throws Exception {
        // Create a file at prefix path so renameTo will fail
        // (can't rename directory to existing file)
        File prefixFile = new File(tempFolder.getRoot(), "prefix");
        try (FileWriter writer = new FileWriter(prefixFile)) {
            writer.write("blocking file");
        }

        AtomicRenameStep step = new AtomicRenameStep();
        Error error = step.execute(context);

        assertNotNull("Should return error when rename fails", error);
        assertTrue("Error should mention rename failure",
            error.getMessage().contains("staging") || error.getMessage().contains("prefix"));
    }

    @Test
    public void renameWithNestedContent() throws Exception {
        // Create nested directory structure in staging
        File nested = new File(context.getStagingDir(), "bin/libexec/apt");
        nested.mkdirs();
        new File(nested, "apt-helper").createNewFile();

        AtomicRenameStep step = new AtomicRenameStep();
        Error error = step.execute(context);

        assertNull(error);
        assertTrue(new File(context.getPrefixDir(), "bin/libexec/apt/apt-helper").exists());
    }
}
