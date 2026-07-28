package com.smbwatch.tv;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class AppUpdateManagerTest {

    @Test
    public void versionCodeMatchesReleaseWorkflow() {
        assertEquals(1_040_099, AppUpdateManager.versionCodeForTag("v1.4.0", false));
        assertEquals(1_040_001, AppUpdateManager.versionCodeForTag("v1.4.0-beta.1", true));
        assertEquals(1_040_012, AppUpdateManager.versionCodeForTag("v1.4.0-beta.12", true));
        assertEquals(1_040_001, AppUpdateManager.versionCodeForTag("v1.4.0-preview", true));
        assertEquals(-1, AppUpdateManager.versionCodeForTag("invalid", false));
    }

    @Test
    public void stableChannelIgnoresPrereleases() throws Exception {
        AppUpdateManager.Release release = AppUpdateManager.selectUpdate(releasesJson(),
                false, 1_030_099);

        assertEquals("v1.4.0", release.tagName);
        assertEquals(1_040_099, release.versionCode);
        assertFalse(release.prerelease);
    }

    @Test
    public void devChannelCanUsePrerelease() throws Exception {
        AppUpdateManager.Release release = AppUpdateManager.selectUpdate(releasesJson(),
                true, 1_040_099);

        assertEquals("v1.5.0-beta.2", release.tagName);
        assertEquals(1_050_002, release.versionCode);
        assertTrue(release.prerelease);
    }

    @Test
    public void releasesWithoutApkOrChecksumAreRejected() throws Exception {
        String json = "[{\"tag_name\":\"v9.0.0\",\"prerelease\":false,"
                + "\"assets\":[{\"name\":\"app.apk\",\"browser_download_url\":\"https://x/app.apk\"}]}]";

        assertNull(AppUpdateManager.selectUpdate(json, false, 1));
    }

    @Test
    public void currentOrOlderReleaseDoesNotUpdate() throws Exception {
        assertNull(AppUpdateManager.selectUpdate(releasesJson(), false, 1_040_099));
    }

    @Test
    public void checksumIsParsedFromSha256sumFile() {
        String hash = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
        assertEquals(hash, AppUpdateManager.checksumFrom(hash + "  smb-watch-tv.apk\n"));
        assertNull(AppUpdateManager.checksumFrom("not a checksum"));
    }

    @Test
    public void releaseSizeIsUsedWhenDownloadResponseHasNoLength() {
        assertEquals(4_372_543L, AppUpdateManager.effectiveTotalSize(-1L, 4_372_543L));
        assertEquals(123L, AppUpdateManager.effectiveTotalSize(123L, 4_372_543L));
        assertEquals(-1L, AppUpdateManager.effectiveTotalSize(-1L, 0L));
    }

    private static String releasesJson() {
        return "["
                + release("v1.5.0-beta.2", true)
                + ","
                + release("v1.4.0", false)
                + ","
                + release("v1.4.0-beta.1", true)
                + "]";
    }

    private static String release(String tag, boolean prerelease) {
        return "{\"tag_name\":\"" + tag + "\",\"draft\":false,\"prerelease\":"
                + prerelease + ",\"assets\":["
                + "{\"name\":\"smb-watch-tv-" + tag + ".apk\","
                + "\"browser_download_url\":\"https://example.com/" + tag + ".apk\","
                + "\"size\":10485760},"
                + "{\"name\":\"smb-watch-tv-" + tag + ".sha256\","
                + "\"browser_download_url\":\"https://example.com/" + tag + ".sha256\"}"
                + "]}";
    }
}
