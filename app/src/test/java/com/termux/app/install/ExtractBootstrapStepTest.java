package com.termux.app.install;

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
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.Assert.*;

/**
 * Robolectric tests for {@link ExtractBootstrapStep}.
 * Tests zip extraction, SYMLINKS.txt parsing, and error handling.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class ExtractBootstrapStepTest {

    @Rule
    public TemporaryFolder tempFolder = new TemporaryFolder();

    private InstallContext context;

    @Before
    public void setUp() throws Exception {
        File root = tempFolder.getRoot();
        File staging = new File(root, "staging");
        staging.mkdirs();
        File prefix = new File(root, "prefix");
        prefix.mkdirs();

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

    // ─── Successful extraction ─────────────────────────────────

    @Test
    public void extractsValidZipToStaging() throws Exception {
        byte[] zipBytes = createTestZip(
            zipEntry("bin/bash", "#!/bin/sh\necho hello"),
            zipEntry("SYMLINKS.txt", "/bin/sh←bin/login")
        );

        ExtractBootstrapStep step = new ExtractBootstrapStep(() -> zipBytes);
        Error error = step.execute(context);

        assertNull(error);

        // Verify extracted file
        File bashFile = new File(context.getStagingDir(), "bin/bash");
        assertTrue("bin/bash should be extracted", bashFile.exists());

        // Verify parsed symlinks
        assertEquals(1, context.getParsedSymlinks().size());
        assertEquals("/bin/sh", context.getParsedSymlinks().get(0)[0]);
        assertTrue(context.getParsedSymlinks().get(0)[1].endsWith("bin/login"));
    }

    @Test
    public void extractsMultipleFiles() throws Exception {
        byte[] zipBytes = createTestZip(
            zipEntry("bin/bash", "bash-content"),
            zipEntry("bin/ls", "ls-content"),
            zipEntry("lib/libc.so", "libc-content"),
            zipEntry("SYMLINKS.txt",
                "/bin/bash←bin/sh\n/bin/bash←bin/dash")
        );

        ExtractBootstrapStep step = new ExtractBootstrapStep(() -> zipBytes);
        Error error = step.execute(context);

        assertNull(error);
        assertTrue(new File(context.getStagingDir(), "bin/bash").exists());
        assertTrue(new File(context.getStagingDir(), "bin/ls").exists());
        assertTrue(new File(context.getStagingDir(), "lib/libc.so").exists());
        assertEquals(2, context.getParsedSymlinks().size());
    }

    @Test
    public void createsNestedDirectories() throws Exception {
        byte[] zipBytes = createTestZip(
            zipEntry("share/terminfo/a/alacritty", "content"),
            zipEntry("SYMLINKS.txt", "/target←link")
        );

        ExtractBootstrapStep step = new ExtractBootstrapStep(() -> zipBytes);
        Error error = step.execute(context);

        assertNull(error);
        assertTrue(new File(context.getStagingDir(), "share/terminfo/a/alacritty").exists());
    }

    // ─── SYMLINKS.txt error cases ──────────────────────────────

    @Test
    public void malformedSymlinksTxtReturnsError() throws Exception {
        byte[] zipBytes = createTestZip(
            zipEntry("SYMLINKS.txt", "malformed-line-no-arrow")
        );

        ExtractBootstrapStep step = new ExtractBootstrapStep(() -> zipBytes);
        Error error = step.execute(context);

        assertNotNull(error);
        assertTrue("Error should mention malformed SYMLINKS.txt",
            error.getMessage().contains("SYMLINKS.txt"));
    }

    @Test
    public void missingSymlinksTxtReturnsError() throws Exception {
        byte[] zipBytes = createTestZip(
            zipEntry("bin/bash", "#!/bin/sh")
        );

        ExtractBootstrapStep step = new ExtractBootstrapStep(() -> zipBytes);
        Error error = step.execute(context);

        assertNotNull(error);
        assertTrue("Error should mention missing SYMLINKS.txt",
            error.getMessage().contains("SYMLINKS.txt"));
    }

    // ─── Zip corruption error ──────────────────────────────────

    @Test
    public void corruptZipReturnsError() throws Exception {
        byte[] corruptBytes = new byte[]{0x50, 0x4B, 0x03, 0x04, (byte) 0xFF,
            (byte) 0xFF, (byte) 0xFF, (byte) 0xFF};

        ExtractBootstrapStep step = new ExtractBootstrapStep(() -> corruptBytes);
        Error error = step.execute(context);

        assertNotNull(error);
    }

    // ─── Zip provider exception ────────────────────────────────

    @Test
    public void zipLoadExceptionReturnsError() {
        ExtractBootstrapStep step = new ExtractBootstrapStep(
            () -> { throw new UnsatisfiedLinkError("no native lib"); });
        Error error = step.execute(context);

        assertNotNull(error);
        assertTrue("Error should mention zip load failure",
            error.getMessage().contains("load bootstrap zip"));
    }

    @Test
    public void zipProviderRuntimeExceptionReturnsError() {
        ExtractBootstrapStep step = new ExtractBootstrapStep(
            () -> { throw new RuntimeException("unexpected"); });
        Error error = step.execute(context);

        assertNotNull(error);
    }

    // ─── Idempotent re-execution ───────────────────────────────

    @Test
    public void idempotentReExecution() throws Exception {
        byte[] zipBytes = createTestZip(
            zipEntry("bin/test", "content"),
            zipEntry("SYMLINKS.txt", "/target←link")
        );

        ExtractBootstrapStep step = new ExtractBootstrapStep(() -> zipBytes);

        // First execution
        assertNull(step.execute(context));
        assertEquals(1, context.getParsedSymlinks().size());

        // Re-execution — parsed symlinks should be cleared and re-populated
        assertNull(step.execute(context));
        assertEquals(1, context.getParsedSymlinks().size());
    }

    // ─── Rollback ──────────────────────────────────────────────

    @Test
    public void rollbackDeletesStagingDirectory() throws Exception {
        byte[] zipBytes = createTestZip(
            zipEntry("bin/test", "content"),
            zipEntry("SYMLINKS.txt", "/target←link")
        );

        ExtractBootstrapStep step = new ExtractBootstrapStep(() -> zipBytes);
        step.execute(context);

        assertTrue(context.getStagingDir().exists());

        Error rollbackError = step.rollback(context);
        assertNull(rollbackError);
        assertFalse("Staging should be deleted on rollback",
            context.getStagingDir().exists());
    }

    // ─── Helper methods ────────────────────────────────────────

    private static String[] zipEntry(String name, String content) {
        return new String[]{name, content};
    }

    private byte[] createTestZip(String[]... entries) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(baos)) {
            for (String[] entry : entries) {
                ZipEntry ze = new ZipEntry(entry[0]);
                zos.putNextEntry(ze);
                zos.write(entry[1].getBytes(StandardCharsets.UTF_8));
                zos.closeEntry();
            }
        }
        return baos.toByteArray();
    }
}
