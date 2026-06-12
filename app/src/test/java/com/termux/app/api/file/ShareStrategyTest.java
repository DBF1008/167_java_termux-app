package com.termux.app.api.file;

import com.termux.app.api.file.ShareStrategy.ContentSource;
import com.termux.app.api.file.ShareStrategy.SaveAction;

import org.junit.After;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.io.File;
import java.io.IOException;

@RunWith(RobolectricTestRunner.class)
public class ShareStrategyTest {

    @Rule
    public TemporaryFolder tempFolder = new TemporaryFolder();

    @After
    public void resetOverride() {
        ShareStrategy.setReceiveDirectoryPathOverride(null);
    }

    // ---- safe naming ------------------------------------------------------

    @Test
    public void sanitizeFileName_reducesPathToBasename() {
        // file:// / content:// names can contain separators or traversal; they must not be able to
        // escape the receive directory or create unexpected subdirectories.
        Assert.assertEquals("passwd", ShareStrategy.sanitizeFileName("../../etc/passwd", ContentSource.FILE_URI));
        Assert.assertEquals("file.txt", ShareStrategy.sanitizeFileName("sub/dir/file.txt", ContentSource.CONTENT_URI));
        Assert.assertEquals("file.txt", ShareStrategy.sanitizeFileName("C:\\Users\\x\\file.txt", ContentSource.FILE_URI));
    }

    @Test
    public void sanitizeFileName_replacesIllegalAndControlChars() {
        // ':' and '?' are reserved on common filesystems; the tab is a control char.
        Assert.assertEquals("a_b_c.txt", ShareStrategy.sanitizeFileName("a:b?c.txt", ContentSource.CONTENT_URI));
        Assert.assertEquals("name_with.txt", ShareStrategy.sanitizeFileName("name\twith.txt", ContentSource.CONTENT_URI));
    }

    @Test
    public void sanitizeFileName_fallsBackToPerSourceDefault() {
        Assert.assertEquals("shared-text.txt", ShareStrategy.sanitizeFileName(null, ContentSource.TEXT));
        Assert.assertEquals("shared-text.txt", ShareStrategy.sanitizeFileName("   ", ContentSource.TEXT));
        Assert.assertEquals("shared-url.txt", ShareStrategy.sanitizeFileName("", ContentSource.URL));
        // "." / ".." / "..." are not valid names and must not be usable as traversal either.
        Assert.assertEquals("shared-file", ShareStrategy.sanitizeFileName("..", ContentSource.CONTENT_URI));
        Assert.assertEquals("shared-file", ShareStrategy.sanitizeFileName("...", ContentSource.FILE_URI));
    }

    @Test
    public void sanitizeFileName_keepsDotfiles() {
        Assert.assertEquals(".bashrc", ShareStrategy.sanitizeFileName(".bashrc", ContentSource.FILE_URI));
    }

    @Test
    public void sanitizeFileName_boundsLengthButKeepsExtension() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 400; i++) sb.append('a');
        String longName = sb + ".txt";

        String result = ShareStrategy.sanitizeFileName(longName, ContentSource.CONTENT_URI);

        Assert.assertTrue(result.length() <= ShareStrategy.MAX_FILE_NAME_LENGTH);
        Assert.assertTrue(result.endsWith(".txt"));
    }

    // ---- no overwrite -----------------------------------------------------

    @Test
    public void getNonCollidingFile_returnsNameWhenFree() {
        File dir = tempFolder.getRoot();
        Assert.assertEquals("a.txt", ShareStrategy.getNonCollidingFile(dir, "a.txt").getName());
    }

    @Test
    public void getNonCollidingFile_appendsCounterOnCollision() throws IOException {
        File dir = tempFolder.getRoot();

        Assert.assertTrue(new File(dir, "a.txt").createNewFile());
        Assert.assertEquals("a (1).txt", ShareStrategy.getNonCollidingFile(dir, "a.txt").getName());

        Assert.assertTrue(new File(dir, "a (1).txt").createNewFile());
        Assert.assertEquals("a (2).txt", ShareStrategy.getNonCollidingFile(dir, "a.txt").getName());
    }

    @Test
    public void getNonCollidingFile_handlesNoExtensionAndDotfiles() throws IOException {
        File dir = tempFolder.getRoot();

        Assert.assertTrue(new File(dir, "noext").createNewFile());
        Assert.assertEquals("noext (1)", ShareStrategy.getNonCollidingFile(dir, "noext").getName());

        Assert.assertTrue(new File(dir, ".bashrc").createNewFile());
        Assert.assertEquals(".bashrc (1)", ShareStrategy.getNonCollidingFile(dir, ".bashrc").getName());
    }

    // ---- per-source post-save action -------------------------------------

    @Test
    public void getDefaultAction_isSourceAware() {
        Assert.assertEquals(SaveAction.EDIT, ShareStrategy.getDefaultAction(ContentSource.TEXT));
        Assert.assertEquals(SaveAction.EDIT, ShareStrategy.getDefaultAction(ContentSource.URL));
        Assert.assertEquals(SaveAction.OPEN_DIRECTORY, ShareStrategy.getDefaultAction(ContentSource.CONTENT_URI));
        Assert.assertEquals(SaveAction.OPEN_DIRECTORY, ShareStrategy.getDefaultAction(ContentSource.FILE_URI));
    }

    // ---- configurable directory ------------------------------------------

    @Test
    public void getReceiveDirectoryPath_defaultsAndHonorsOverride() {
        Assert.assertEquals(ShareStrategy.DEFAULT_RECEIVE_DIR_PATH, ShareStrategy.getReceiveDirectoryPath());

        ShareStrategy.setReceiveDirectoryPathOverride("/tmp/received");
        Assert.assertEquals("/tmp/received", ShareStrategy.getReceiveDirectoryPath());

        // Blank override falls back to the default.
        ShareStrategy.setReceiveDirectoryPathOverride("   ");
        Assert.assertEquals(ShareStrategy.DEFAULT_RECEIVE_DIR_PATH, ShareStrategy.getReceiveDirectoryPath());
    }
}
