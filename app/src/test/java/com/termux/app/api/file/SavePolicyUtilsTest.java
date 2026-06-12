package com.termux.app.api.file;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;

import static org.junit.Assert.assertEquals;

public class SavePolicyUtilsTest {

    @Rule
    public TemporaryFolder tempFolder = new TemporaryFolder();

    // --- getSafeFileName: no conflict ---
    @Test
    public void testGetSafeFileName_noConflict() {
        String result = SavePolicyUtils.getSafeFileName(tempFolder.getRoot(), "photo.jpg");
        assertEquals("photo.jpg", result);
    }

    // --- getSafeFileName: single conflict ---
    @Test
    public void testGetSafeFileName_oneConflict() throws IOException {
        tempFolder.newFile("photo.jpg");
        String result = SavePolicyUtils.getSafeFileName(tempFolder.getRoot(), "photo.jpg");
        assertEquals("photo(1).jpg", result);
    }

    // --- getSafeFileName: multiple conflicts ---
    @Test
    public void testGetSafeFileName_multipleConflicts() throws IOException {
        tempFolder.newFile("photo.jpg");
        tempFolder.newFile("photo(1).jpg");
        tempFolder.newFile("photo(2).jpg");
        String result = SavePolicyUtils.getSafeFileName(tempFolder.getRoot(), "photo.jpg");
        assertEquals("photo(3).jpg", result);
    }

    // --- getSafeFileName: no extension ---
    @Test
    public void testGetSafeFileName_noExtension() throws IOException {
        tempFolder.newFile("README");
        String result = SavePolicyUtils.getSafeFileName(tempFolder.getRoot(), "README");
        assertEquals("README(1)", result);
    }

    // --- getSafeFileName: multiple dots (compound extension) ---
    @Test
    public void testGetSafeFileName_multipleDots() throws IOException {
        tempFolder.newFile("archive.tar.gz");
        String result = SavePolicyUtils.getSafeFileName(tempFolder.getRoot(), "archive.tar.gz");
        assertEquals("archive.tar(1).gz", result);
    }

    // --- getSafeFileName: dot-only filename (.hidden) ---
    @Test
    public void testGetSafeFileName_hiddenFile() throws IOException {
        tempFolder.newFile(".gitignore");
        String result = SavePolicyUtils.getSafeFileName(tempFolder.getRoot(), ".gitignore");
        assertEquals(".gitignore(1)", result);
    }

    // --- getSafeFileName: starts with dot but has extension (.config.bak) ---
    @Test
    public void testGetSafeFileName_hiddenFileWithExtension() throws IOException {
        tempFolder.newFile(".config.bak");
        String result = SavePolicyUtils.getSafeFileName(tempFolder.getRoot(), ".config.bak");
        assertEquals(".config(1).bak", result);
    }

    // --- getSafeFileName: null/empty input ---
    @Test(expected = IllegalArgumentException.class)
    public void testGetSafeFileName_nullThrows() {
        SavePolicyUtils.getSafeFileName(tempFolder.getRoot(), null);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testGetSafeFileName_emptyThrows() {
        SavePolicyUtils.getSafeFileName(tempFolder.getRoot(), "");
    }

    // --- Policy constants sanity ---
    @Test
    public void testPolicyConstants() {
        assertEquals("prompt", SavePolicyUtils.POLICY_PROMPT);
        assertEquals("auto-rename", SavePolicyUtils.POLICY_AUTO_RENAME);
        assertEquals("overwrite", SavePolicyUtils.POLICY_OVERWRITE);
    }
}
