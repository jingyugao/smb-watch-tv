package com.smbwatch.tv;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class SmbUrlsTest {

    @Test
    public void normalizeAddsSchemeAndStripsUserInfo() {
        assertEquals("smb://192.168.1.5/media", SmbUrls.normalize("192.168.1.5/media"));
        assertEquals("smb://host/share/", SmbUrls.normalize("smb://user:pw@host/share/"));
        assertEquals("smb://host:8445/share", SmbUrls.normalize("smb://host:8445/share"));
    }

    @Test(expected = IllegalArgumentException.class)
    public void normalizeRejectsEmpty() {
        SmbUrls.normalize("");
    }

    @Test(expected = IllegalArgumentException.class)
    public void normalizeRejectsMissingHost() {
        SmbUrls.normalize("smb:///share");
    }

    @Test
    public void stripUserInfoKeepsHostPortAndPath() {
        assertEquals("smb://h:445/a/b.mkv", SmbUrls.stripUserInfo("smb://u:p@h:445/a/b.mkv"));
        assertEquals("smb://h/", SmbUrls.stripUserInfo("smb://u@h"));
        assertEquals("smb://h/a/", SmbUrls.stripUserInfo("smb://h/a/"));
    }

    @Test
    public void hostOfIgnoresUserInfoAndPort() {
        assertEquals("h", SmbUrls.hostOf("smb://u:p@h:8445/x"));
        assertEquals("192.168.0.100", SmbUrls.hostOf("smb://192.168.0.100/media/"));
        assertEquals("", SmbUrls.hostOf("not-a-url"));
    }

    @Test
    public void parentDirectoryWalksUpOneLevel() {
        assertEquals("smb://h/share/a/", SmbUrls.parentDirectory("smb://h/share/a/b.mkv"));
        assertEquals("smb://h/", SmbUrls.parentDirectory("smb://h/x.mkv"));
        assertEquals("smb://h:8445/share/", SmbUrls.parentDirectory("smb://h:8445/share/ep1.mkv"));
    }

    @Test
    public void directoryNameReturnsLastPathSegment() {
        assertEquals("b", SmbUrls.directoryName("smb://h/a/b/"));
        assertEquals("b", SmbUrls.directoryName("smb://h/a/b"));
    }

    @Test
    public void fileNameReturnsLastSegment() {
        assertEquals("ep1.mkv", SmbUrls.fileName("smb://h/share/ep1.mkv"));
        assertEquals("", SmbUrls.fileName(""));
    }

    @Test
    public void sameDirectoryIgnoresCaseAndTrailingSlash() {
        assertTrue(SmbUrls.sameDirectory("smb://h/A/", "smb://h/a"));
        assertFalse(SmbUrls.sameDirectory("smb://h/a/", "smb://h/b/"));
        assertFalse(SmbUrls.sameDirectory("", ""));
    }

    @Test
    public void ensureAndRemoveTrailingSlashAreInverse() {
        assertEquals("smb://h/a/", SmbUrls.ensureTrailingSlash("smb://h/a"));
        assertEquals("smb://h/a/", SmbUrls.ensureTrailingSlash("smb://h/a/"));
        assertEquals("smb://h/a", SmbUrls.removeTrailingSlash("smb://h/a/"));
        assertEquals("", SmbUrls.ensureTrailingSlash(null));
    }
}
