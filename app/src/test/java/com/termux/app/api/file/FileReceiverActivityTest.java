package com.termux.app.api.file;

import com.termux.app.api.file.ShareStrategy.ContentSource;

import org.junit.After;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

@RunWith(RobolectricTestRunner.class)
public class FileReceiverActivityTest {

    @Rule
    public TemporaryFolder tempFolder = new TemporaryFolder();

    @After
    public void resetReceiveDirectory() {
        ShareStrategy.setReceiveDirectoryPathOverride(null);
    }

    // ---- text vs URL routing ---------------------------------------------

    @Test
    public void testIsSharedTextAnUrl() {
        List<String> validUrls = new ArrayList<>();
        validUrls.add("http://example.com");
        validUrls.add("https://example.com");
        validUrls.add("https://example.com/path/parameter=foo");
        validUrls.add("magnet:?xt=urn:btih:d540fc48eb12f2833163eed6421d449dd8f1ce1f&dn=Ubuntu+desktop+19.04+%2864bit%29&tr=udp%3A%2F%2Ftracker.openbittorrent.com%3A80&tr=udp%3A%2F%2Ftracker.publicbt.com%3A80&tr=udp%3A%2F%2Ftracker.ccc.de%3A80");
        for (String url : validUrls) {
            Assert.assertTrue(FileReceiverActivity.isSharedTextAnUrl(url));
        }

        List<String> invalidUrls = new ArrayList<>();
        invalidUrls.add("a test with example.com");
        invalidUrls.add("");
        invalidUrls.add(null);
        // Plain shared text is not auto-opened as a URL; it is saved as a text file instead.
        invalidUrls.add("just some shared notes");
        for (String url : invalidUrls) {
            Assert.assertFalse(FileReceiverActivity.isSharedTextAnUrl(url));
        }
    }

    // ---- saving received content (per source + no overwrite) -------------

    /** Shared text is written verbatim to a safely named file in the receive directory. */
    @Test
    public void savesSharedText() throws Exception {
        ShareStrategy.setReceiveDirectoryPathOverride(tempFolder.getRoot().getAbsolutePath());
        FileReceiverActivity activity = Robolectric.buildActivity(FileReceiverActivity.class).get();

        File out = activity.saveStreamWithName(stream("hello world"), "note.txt", ContentSource.TEXT);

        Assert.assertNotNull(out);
        Assert.assertEquals("note.txt", out.getName());
        Assert.assertEquals("hello world", readFile(out));
    }

    /** Shared text with no usable name still saves, using the per-source default name. */
    @Test
    public void savesSharedTextWithDefaultNameWhenMissing() throws Exception {
        ShareStrategy.setReceiveDirectoryPathOverride(tempFolder.getRoot().getAbsolutePath());
        FileReceiverActivity activity = Robolectric.buildActivity(FileReceiverActivity.class).get();

        File out = activity.saveStreamWithName(stream("body"), null, ContentSource.TEXT);

        Assert.assertNotNull(out);
        Assert.assertEquals("shared-text.txt", out.getName());
        Assert.assertEquals("body", readFile(out));
    }

    /** A content-uri whose name collides with an existing file must not overwrite it. */
    @Test
    public void savingDuplicateContentUriDoesNotOverwrite() throws Exception {
        ShareStrategy.setReceiveDirectoryPathOverride(tempFolder.getRoot().getAbsolutePath());
        FileReceiverActivity activity = Robolectric.buildActivity(FileReceiverActivity.class).get();

        File first = activity.saveStreamWithName(stream("v1"), "report.txt", ContentSource.CONTENT_URI);
        File second = activity.saveStreamWithName(stream("v2"), "report.txt", ContentSource.CONTENT_URI);
        File third = activity.saveStreamWithName(stream("v3"), "report.txt", ContentSource.CONTENT_URI);

        Assert.assertEquals("report.txt", first.getName());
        Assert.assertEquals("report (1).txt", second.getName());
        Assert.assertEquals("report (2).txt", third.getName());
        // All three files coexist with their own content - nothing was overwritten.
        Assert.assertEquals("v1", readFile(first));
        Assert.assertEquals("v2", readFile(second));
        Assert.assertEquals("v3", readFile(third));
    }

    /** A file-uri name containing path traversal is reduced to a safe basename inside the receive dir. */
    @Test
    public void savingFileUriWithTraversalStaysInsideReceiveDir() throws Exception {
        File receiveDir = tempFolder.getRoot();
        ShareStrategy.setReceiveDirectoryPathOverride(receiveDir.getAbsolutePath());
        FileReceiverActivity activity = Robolectric.buildActivity(FileReceiverActivity.class).get();

        File out = activity.saveStreamWithName(stream("data"), "../../evil.txt", ContentSource.FILE_URI);

        Assert.assertNotNull(out);
        Assert.assertEquals("evil.txt", out.getName());
        Assert.assertEquals(receiveDir.getCanonicalFile(), out.getParentFile().getCanonicalFile());
        Assert.assertEquals("data", readFile(out));
    }

    private static ByteArrayInputStream stream(String content) {
        return new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8));
    }

    private static String readFile(File file) throws Exception {
        return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
    }
}
