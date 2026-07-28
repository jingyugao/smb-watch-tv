package com.smbwatch.tv;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class SmbEpisodeScannerTest {

    @Test
    public void recognizesVideoExtensionsCaseInsensitively() {
        assertTrue(SmbEpisodeScanner.isVideo("ep1.mkv"));
        assertTrue(SmbEpisodeScanner.isVideo("Movie.MP4"));
        assertTrue(SmbEpisodeScanner.isVideo("show.WebM"));
        assertTrue(SmbEpisodeScanner.isVideo("record.ts"));
    }

    @Test
    public void rejectsNonVideoFiles() {
        assertFalse(SmbEpisodeScanner.isVideo("cover.jpg"));
        assertFalse(SmbEpisodeScanner.isVideo("subtitle.srt"));
        assertFalse(SmbEpisodeScanner.isVideo("mkv"));
        assertFalse(SmbEpisodeScanner.isVideo(""));
        assertFalse(SmbEpisodeScanner.isVideo(null));
    }

    @Test
    public void recognizesSubtitleExtensions() {
        assertTrue(SmbEpisodeScanner.isSubtitle("ep1.srt"));
        assertTrue(SmbEpisodeScanner.isSubtitle("ep1.chs.ASS"));
        assertFalse(SmbEpisodeScanner.isSubtitle("ep1.mkv"));
    }
}
