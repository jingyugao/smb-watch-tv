package com.smbwatch.tv;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * SMB 连接与播放列表的唯一持久化入口。
 * JSON 映射是纯函数，可在 JVM 单元测试中直接验证。
 */
final class PlaylistStore {
    static final String KEY_CONNECTIONS = "connections";
    static final String KEY_PLAYLISTS = "playlists";

    static final class Connection {
        final String name;
        final String url;
        final String username;
        final String password;

        Connection(String name, String url, String username, String password) {
            this.name = name;
            this.url = url;
            this.username = username;
            this.password = password;
        }
    }

    static final class Playlist {
        final String title;
        final String connectionName;
        final String sourceUrl;
        final String username;
        final String password;
        final String dirPath;
        final String lastEpisodeName;
        final String lastEpisodePath;
        final long lastEpisodePositionMs;
        final long createdAt;

        Playlist(String title, String connectionName, String sourceUrl, String username, String password,
                 String dirPath, String lastEpisodeName, String lastEpisodePath,
                 long lastEpisodePositionMs, long createdAt) {
            this.title = title;
            this.connectionName = connectionName;
            this.sourceUrl = sourceUrl;
            this.username = username;
            this.password = password;
            this.dirPath = dirPath;
            this.lastEpisodeName = lastEpisodeName;
            this.lastEpisodePath = lastEpisodePath;
            this.lastEpisodePositionMs = lastEpisodePositionMs;
            this.createdAt = createdAt;
        }

        boolean hasProgress() {
            return !SmbUrls.isEmpty(lastEpisodePath) || lastEpisodePositionMs > 0L;
        }
    }

    private PlaylistStore() { }

    // ---- 持久化 ----

    static List<Connection> loadConnections(Context context) {
        return connectionsFromJson(SecurePreferences.get(context).getString(KEY_CONNECTIONS, ""));
    }

    static void saveConnections(Context context, List<Connection> connections) {
        SecurePreferences.get(context).edit()
                .putString(KEY_CONNECTIONS, connectionsToJson(connections))
                .apply();
    }

    static List<Playlist> loadPlaylists(Context context) {
        return playlistsFromJson(SecurePreferences.get(context).getString(KEY_PLAYLISTS, ""));
    }

    static void savePlaylists(Context context, List<Playlist> playlists) {
        SecurePreferences.get(context).edit()
                .putString(KEY_PLAYLISTS, playlistsToJson(playlists))
                .apply();
    }

    static Playlist findByDirectory(Context context, String dirPath) {
        for (Playlist playlist : loadPlaylists(context)) {
            if (SmbUrls.sameDirectory(playlist.dirPath, dirPath)) return playlist;
        }
        return null;
    }

    /** 更新或追加某个目录的播放进度记录，其余条目原样保留。 */
    static void saveProgress(Context context, Playlist updated) {
        List<Playlist> merged = new ArrayList<>();
        boolean found = false;
        for (Playlist item : loadPlaylists(context)) {
            if (SmbUrls.sameDirectory(item.dirPath, updated.dirPath)
                    && item.sourceUrl.equalsIgnoreCase(updated.sourceUrl)) {
                merged.add(updated);
                found = true;
            } else {
                merged.add(item);
            }
        }
        if (!found) merged.add(updated);
        savePlaylists(context, merged);
    }

    // ---- JSON 映射（纯函数） ----

    static List<Connection> connectionsFromJson(String raw) {
        List<Connection> result = new ArrayList<>();
        if (SmbUrls.isEmpty(raw)) return result;
        try {
            JSONArray array = new JSONArray(raw);
            for (int i = 0; i < array.length(); i++) {
                JSONObject obj = array.optJSONObject(i);
                if (obj == null) continue;
                String url = obj.optString("url", "");
                if (SmbUrls.isEmpty(url)) continue;
                try {
                    url = SmbUrls.normalize(url);
                } catch (IllegalArgumentException ignored) {
                    continue;
                }
                result.add(new Connection(
                        obj.optString("name", ""),
                        url,
                        obj.optString("username", ""),
                        obj.optString("password", "")));
            }
        } catch (JSONException ignored) {
            // 数据损坏时按空列表处理
        }
        return result;
    }

    static String connectionsToJson(List<Connection> connections) {
        JSONArray array = new JSONArray();
        for (Connection connection : connections) {
            try {
                array.put(new JSONObject()
                        .put("name", connection.name)
                        .put("url", connection.url)
                        .put("username", connection.username)
                        .put("password", connection.password));
            } catch (JSONException ignored) {
            }
        }
        return array.toString();
    }

    static List<Playlist> playlistsFromJson(String raw) {
        List<Playlist> result = new ArrayList<>();
        if (SmbUrls.isEmpty(raw)) return result;
        try {
            JSONArray array = new JSONArray(raw);
            for (int i = 0; i < array.length(); i++) {
                JSONObject obj = array.optJSONObject(i);
                if (obj == null) continue;
                Playlist playlist = playlistFromJson(obj);
                if (playlist != null) result.add(playlist);
            }
        } catch (JSONException ignored) {
            // 数据损坏时按空列表处理
        }
        return result;
    }

    private static Playlist playlistFromJson(JSONObject obj) {
        String title = obj.optString("title", "");
        String connectionName = obj.optString("connectionName", "");
        String sourceUrl = obj.optString("sourceUrl", "");
        String dirPath = obj.optString("dirPath", "");
        String lastEpisodeName = obj.optString("lastEpisodeName", "");
        String lastEpisodePath = obj.optString("lastEpisodePath", "");

        // 兼容早期版本仅存文件路径（"path"）的格式
        if (SmbUrls.isEmpty(dirPath)) {
            String legacyPath = obj.optString("path", "");
            if (!SmbUrls.isEmpty(legacyPath)) {
                dirPath = SmbUrls.parentDirectory(legacyPath);
                if (SmbUrls.isEmpty(lastEpisodeName)) lastEpisodeName = SmbUrls.fileName(legacyPath);
                if (SmbUrls.isEmpty(lastEpisodePath)) lastEpisodePath = legacyPath;
                if (SmbUrls.isEmpty(title)) title = SmbUrls.directoryName(dirPath);
            }
        }
        if (SmbUrls.isEmpty(dirPath)) return null;
        if (SmbUrls.isEmpty(sourceUrl)) sourceUrl = dirPath;
        try {
            sourceUrl = SmbUrls.normalize(sourceUrl);
            dirPath = SmbUrls.normalize(dirPath);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
        if (SmbUrls.isEmpty(connectionName)) {
            String host = SmbUrls.hostOf(sourceUrl);
            connectionName = SmbUrls.isEmpty(host) ? "SMB" : host;
        }
        return new Playlist(
                SmbUrls.isEmpty(title) ? SmbUrls.directoryName(dirPath) : title,
                connectionName,
                sourceUrl,
                obj.optString("username", ""),
                obj.optString("password", ""),
                SmbUrls.ensureTrailingSlash(dirPath),
                lastEpisodeName,
                lastEpisodePath,
                obj.optLong("lastEpisodePositionMs", 0L),
                obj.optLong("createdAt", System.currentTimeMillis()));
    }

    static String playlistsToJson(List<Playlist> playlists) {
        JSONArray array = new JSONArray();
        for (Playlist playlist : playlists) {
            try {
                array.put(new JSONObject()
                        .put("title", playlist.title)
                        .put("connectionName", playlist.connectionName)
                        .put("sourceUrl", playlist.sourceUrl)
                        .put("dirPath", playlist.dirPath)
                        .put("username", playlist.username)
                        .put("password", playlist.password)
                        .put("lastEpisodeName", playlist.lastEpisodeName)
                        .put("lastEpisodePath", playlist.lastEpisodePath)
                        .put("lastEpisodePositionMs", playlist.lastEpisodePositionMs)
                        .put("createdAt", playlist.createdAt));
            } catch (JSONException ignored) {
            }
        }
        return array.toString();
    }

    /** 目录相同的条目只保留一条，优先保留有播放进度、其次较新的。 */
    static List<Playlist> deduplicate(List<Playlist> playlists) {
        List<Playlist> unique = new ArrayList<>();
        for (Playlist candidate : playlists) {
            int duplicateIndex = -1;
            for (int i = 0; i < unique.size(); i++) {
                if (SmbUrls.sameDirectory(unique.get(i).dirPath, candidate.dirPath)) {
                    duplicateIndex = i;
                    break;
                }
            }
            if (duplicateIndex < 0) {
                unique.add(candidate);
            } else {
                unique.set(duplicateIndex, preferred(unique.get(duplicateIndex), candidate));
            }
        }
        return unique;
    }

    static Playlist preferred(Playlist left, Playlist right) {
        if (left == null) return right;
        if (right == null) return left;
        if (left.hasProgress() != right.hasProgress()) return right.hasProgress() ? right : left;
        return right.createdAt >= left.createdAt ? right : left;
    }
}
