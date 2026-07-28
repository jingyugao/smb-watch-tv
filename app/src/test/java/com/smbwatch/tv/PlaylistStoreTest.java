package com.smbwatch.tv;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

public class PlaylistStoreTest {

    private static PlaylistStore.Playlist playlist(String dirPath, String lastEpisodePath, long createdAt) {
        return new PlaylistStore.Playlist("剧名", "conn", "smb://h/share/", "user", "pw",
                dirPath, "", lastEpisodePath, 0L, createdAt);
    }

    @Test
    public void playlistsRoundTripKeepsFields() {
        PlaylistStore.Playlist original = new PlaylistStore.Playlist(
                "西部世界", "nas", "smb://h/share/", "u", "p",
                "smb://h/share/西部世界/", "ep03.mkv", "smb://h/share/西部世界/ep03.mkv",
                123456L, 42L);
        List<PlaylistStore.Playlist> loaded =
                PlaylistStore.playlistsFromJson(PlaylistStore.playlistsToJson(Arrays.asList(original)));

        assertEquals(1, loaded.size());
        PlaylistStore.Playlist restored = loaded.get(0);
        assertEquals(original.title, restored.title);
        assertEquals(original.connectionName, restored.connectionName);
        assertEquals(original.sourceUrl, restored.sourceUrl);
        assertEquals(original.dirPath, restored.dirPath);
        assertEquals(original.username, restored.username);
        assertEquals(original.password, restored.password);
        assertEquals(original.lastEpisodeName, restored.lastEpisodeName);
        assertEquals(original.lastEpisodePath, restored.lastEpisodePath);
        assertEquals(original.lastEpisodePositionMs, restored.lastEpisodePositionMs);
        assertEquals(original.createdAt, restored.createdAt);
    }

    @Test
    public void legacyFileOnlyEntriesAreMigrated() {
        String raw = "[{\"path\":\"smb://h/share/ep1.mkv\",\"lastEpisodePositionMs\":900}]";
        List<PlaylistStore.Playlist> loaded = PlaylistStore.playlistsFromJson(raw);

        assertEquals(1, loaded.size());
        PlaylistStore.Playlist migrated = loaded.get(0);
        assertEquals("smb://h/share/", migrated.dirPath);
        assertEquals("smb://h/share/ep1.mkv", migrated.lastEpisodePath);
        assertEquals("ep1.mkv", migrated.lastEpisodeName);
        assertEquals("share", migrated.title);
        assertEquals(900L, migrated.lastEpisodePositionMs);
    }

    @Test
    public void entriesWithoutDirectoryOrInvalidUrlAreDropped() {
        String raw = "[{\"title\":\"没有目录\"},{\"dirPath\":\"smb:///bad\"}]";
        assertTrue(PlaylistStore.playlistsFromJson(raw).isEmpty());
    }

    @Test
    public void corruptJsonYieldsEmptyList() {
        assertTrue(PlaylistStore.playlistsFromJson("not json").isEmpty());
        assertTrue(PlaylistStore.connectionsFromJson("{broken").isEmpty());
    }

    @Test
    public void userInfoIsStrippedOnLoad() {
        String raw = "[{\"dirPath\":\"smb://user:pw@h/share/dir\"}]";
        List<PlaylistStore.Playlist> loaded = PlaylistStore.playlistsFromJson(raw);
        assertEquals("smb://h/share/dir/", loaded.get(0).dirPath);
    }

    @Test
    public void deduplicateKeepsEntryWithProgress() {
        PlaylistStore.Playlist noProgress = playlist("smb://h/share/A/", "", 100L);
        PlaylistStore.Playlist withProgress = playlist("smb://h/share/a/", "smb://h/share/a/e1.mkv", 50L);
        List<PlaylistStore.Playlist> unique =
                PlaylistStore.deduplicate(Arrays.asList(noProgress, withProgress));

        assertEquals(1, unique.size());
        assertSame(withProgress, unique.get(0));
    }

    @Test
    public void deduplicateKeepsNewerWhenProgressEqual() {
        PlaylistStore.Playlist older = playlist("smb://h/share/a/", "", 100L);
        PlaylistStore.Playlist newer = playlist("smb://h/share/a/", "", 200L);
        List<PlaylistStore.Playlist> unique = PlaylistStore.deduplicate(Arrays.asList(older, newer));

        assertEquals(1, unique.size());
        assertSame(newer, unique.get(0));
    }

    @Test
    public void connectionsRoundTripAndSkipInvalid() {
        String json = PlaylistStore.connectionsToJson(Arrays.asList(
                new PlaylistStore.Connection("nas", "smb://192.168.1.5/media/", "u", "p")));
        List<PlaylistStore.Connection> loaded = PlaylistStore.connectionsFromJson(json);
        assertEquals(1, loaded.size());
        assertEquals("smb://192.168.1.5/media/", loaded.get(0).url);

        assertTrue(PlaylistStore.connectionsFromJson("[{\"url\":\"\"},{\"name\":\"x\"}]").isEmpty());
    }
}
