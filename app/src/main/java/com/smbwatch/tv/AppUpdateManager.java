package com.smbwatch.tv;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 从公开的 Gitee Release 镜像检查和下载更新。安装授权与界面交互由 Activity 处理。
 */
final class AppUpdateManager {
    private static final String RELEASES_URL =
            "https://gitee.com/ggyy00/smb-watch-tv/raw/update-mirror/releases.json";
    private static final String USER_AGENT = "smb-watch-tv-updater";
    private static final int CONNECT_TIMEOUT_MS = 12_000;
    private static final int READ_TIMEOUT_MS = 30_000;
    private static final int MAX_METADATA_BYTES = 2 * 1024 * 1024;
    private static final Pattern SHA256_PATTERN =
            Pattern.compile("(?i)(?:^|\\s)([0-9a-f]{64})(?:\\s|$)");

    interface Listener {
        void onNoUpdate();
        void onUpdateAvailable(Release release);
        default void onDownloadPreparing() { }
        void onDownloadProgress(int percent);
        default void onDownloadVerifying() { }
        void onDownloadReady(File apk);
        void onError(String message);
    }

    static final class Release {
        final String tagName;
        final int versionCode;
        final String apkUrl;
        final String checksumUrl;
        final long apkSize;
        final boolean prerelease;

        Release(String tagName, int versionCode, String apkUrl, String checksumUrl,
                long apkSize, boolean prerelease) {
            this.tagName = tagName;
            this.versionCode = versionCode;
            this.apkUrl = apkUrl;
            this.checksumUrl = checksumUrl;
            this.apkSize = apkSize;
            this.prerelease = prerelease;
        }
    }

    private final Context context;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private volatile boolean closed;

    AppUpdateManager(Context context) {
        this.context = context.getApplicationContext();
    }

    void check(boolean includePrereleases, int currentVersionCode, Listener listener) {
        executor.execute(() -> {
            try {
                String json = readText(RELEASES_URL, MAX_METADATA_BYTES);
                Release release = selectUpdate(json, includePrereleases, currentVersionCode);
                if (release == null) {
                    post(listener::onNoUpdate);
                } else {
                    post(() -> listener.onUpdateAvailable(release));
                }
            } catch (Exception e) {
                post(() -> listener.onError(messageOf(e)));
            }
        });
    }

    void download(Release release, Listener listener) {
        executor.execute(() -> {
            File partial = null;
            try {
                post(listener::onDownloadPreparing);
                if (isEmpty(release.checksumUrl)) {
                    throw new IllegalStateException("发布版本缺少 SHA-256 校验文件");
                }
                String checksumText = readText(release.checksumUrl, 1024 * 1024);
                String expectedHash = checksumFrom(checksumText);
                if (expectedHash == null) {
                    throw new IllegalStateException("无法读取 APK 校验值");
                }

                File directory = context.getExternalFilesDir("updates");
                if (directory == null) directory = new File(context.getCacheDir(), "updates");
                if (!directory.exists() && !directory.mkdirs()) {
                    throw new IllegalStateException("无法创建更新下载目录");
                }

                File apk = new File(directory, "smb-watch-tv-update.apk");
                partial = new File(directory, "smb-watch-tv-update.apk.part");
                if (partial.exists() && !partial.delete()) {
                    throw new IllegalStateException("无法清理上次未完成的下载");
                }

                String actualHash = downloadApk(
                        release.apkUrl, partial, release.apkSize, listener);
                post(listener::onDownloadVerifying);
                if (!expectedHash.equalsIgnoreCase(actualHash)) {
                    throw new SecurityException("APK SHA-256 校验失败");
                }
                if (apk.exists() && !apk.delete()) {
                    throw new IllegalStateException("无法替换旧的更新文件");
                }
                if (!partial.renameTo(apk)) {
                    throw new IllegalStateException("无法保存下载的 APK");
                }
                post(() -> listener.onDownloadReady(apk));
            } catch (Exception e) {
                if (partial != null && partial.exists()) {
                    //noinspection ResultOfMethodCallIgnored
                    partial.delete();
                }
                post(() -> listener.onError(messageOf(e)));
            }
        });
    }

    void close() {
        closed = true;
        executor.shutdownNow();
        mainHandler.removeCallbacksAndMessages(null);
    }

    static Release selectUpdate(String rawJson, boolean includePrereleases,
                                int currentVersionCode) throws Exception {
        JSONArray releases = new JSONArray(rawJson);
        Release best = null;
        for (int i = 0; i < releases.length(); i++) {
            JSONObject item = releases.optJSONObject(i);
            if (item == null || item.optBoolean("draft", false)) continue;

            boolean prerelease = item.optBoolean("prerelease", false);
            if (prerelease && !includePrereleases) continue;

            String tagName = item.optString("tag_name", "");
            int versionCode = versionCodeForTag(tagName, prerelease);
            if (versionCode <= currentVersionCode) continue;

            String apkUrl = "";
            String checksumUrl = "";
            long apkSize = 0L;
            JSONArray assets = item.optJSONArray("assets");
            if (assets != null) {
                for (int j = 0; j < assets.length(); j++) {
                    JSONObject asset = assets.optJSONObject(j);
                    if (asset == null) continue;
                    String name = asset.optString("name", "").toLowerCase(Locale.ROOT);
                    String url = asset.optString("browser_download_url", "");
                    if (name.endsWith(".apk") && isEmpty(apkUrl)) {
                        apkUrl = url;
                        apkSize = asset.optLong("size", 0L);
                    } else if (name.endsWith(".sha256") && isEmpty(checksumUrl)) {
                        checksumUrl = url;
                    }
                }
            }
            if (isEmpty(apkUrl) || isEmpty(checksumUrl)) continue;
            if (best == null || versionCode > best.versionCode) {
                best = new Release(tagName, versionCode, apkUrl, checksumUrl, apkSize, prerelease);
            }
        }
        return best;
    }

    /**
     * 必须与 .github/workflows/release.yml 中的版本号计算保持一致。
     */
    static int versionCodeForTag(String rawTag, boolean prerelease) {
        if (isEmpty(rawTag)) return -1;
        String version = rawTag.startsWith("v") ? rawTag.substring(1) : rawTag;
        String[] versionAndSuffix = version.split("-", 2);
        String[] core = versionAndSuffix[0].split("\\.");
        if (core.length != 3) return -1;
        try {
            long major = Long.parseLong(core[0]);
            long minor = Long.parseLong(core[1]);
            long patch = Long.parseLong(core[2]);
            if (major < 0 || minor < 0 || patch < 0) return -1;
            long code = major * 1_000_000L + minor * 10_000L + patch * 100L;
            if (prerelease || versionAndSuffix.length > 1) {
                int sequence = 1;
                if (versionAndSuffix.length > 1) {
                    String suffix = versionAndSuffix[1];
                    int dot = suffix.lastIndexOf('.');
                    String candidate = dot >= 0 ? suffix.substring(dot + 1) : suffix;
                    try {
                        sequence = Integer.parseInt(candidate);
                    } catch (NumberFormatException ignored) {
                        sequence = 1;
                    }
                }
                code += Math.max(1, sequence);
            } else {
                code += 99;
            }
            return code <= Integer.MAX_VALUE ? (int) code : -1;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    static String checksumFrom(String text) {
        if (text == null) return null;
        Matcher matcher = SHA256_PATTERN.matcher(text);
        return matcher.find() ? matcher.group(1).toLowerCase(Locale.ROOT) : null;
    }

    private String downloadApk(String url, File destination, long releaseSize,
                               Listener listener) throws Exception {
        HttpURLConnection connection = open(url);
        long total = effectiveTotalSize(contentLength(connection), releaseSize);
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        long downloaded = 0L;
        int lastPercent = -1;
        post(() -> listener.onDownloadProgress(0));
        try (InputStream input = connection.getInputStream();
             FileOutputStream output = new FileOutputStream(destination)) {
            byte[] buffer = new byte[64 * 1024];
            int count;
            while (!closed && (count = input.read(buffer)) >= 0) {
                if (count == 0) continue;
                output.write(buffer, 0, count);
                digest.update(buffer, 0, count);
                downloaded += count;
                if (total > 0) {
                    int percent = (int) Math.min(100L, downloaded * 100L / total);
                    if (percent != lastPercent) {
                        lastPercent = percent;
                        int progress = percent;
                        post(() -> listener.onDownloadProgress(progress));
                    }
                }
            }
            output.getFD().sync();
        } finally {
            connection.disconnect();
        }
        if (closed) throw new InterruptedException("更新下载已取消");
        return hex(digest.digest());
    }

    private String readText(String url, int maxBytes) throws Exception {
        HttpURLConnection connection = open(url);
        try (InputStream input = connection.getInputStream();
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int total = 0;
            int count;
            while ((count = input.read(buffer)) >= 0) {
                if (count == 0) continue;
                total += count;
                if (total > maxBytes) throw new IllegalStateException("服务器响应过大");
                output.write(buffer, 0, count);
            }
            return new String(output.toByteArray(), StandardCharsets.UTF_8);
        } finally {
            connection.disconnect();
        }
    }

    private HttpURLConnection open(String rawUrl) throws Exception {
        URL url = new URL(rawUrl);
        for (int redirect = 0; redirect < 6; redirect++) {
            if (!"https".equalsIgnoreCase(url.getProtocol())) {
                throw new SecurityException("更新地址不是 HTTPS");
            }
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(READ_TIMEOUT_MS);
            connection.setInstanceFollowRedirects(false);
            connection.setUseCaches(false);
            connection.setRequestProperty("Accept", "application/json, application/octet-stream");
            connection.setRequestProperty("Cache-Control", "no-cache");
            connection.setRequestProperty("User-Agent", USER_AGENT);
            int status = connection.getResponseCode();
            if (status >= 300 && status < 400) {
                String location = connection.getHeaderField("Location");
                connection.disconnect();
                if (isEmpty(location)) throw new IllegalStateException("更新下载重定向无效");
                url = new URL(url, location);
                continue;
            }
            if (status < 200 || status >= 300) {
                connection.disconnect();
                throw new IllegalStateException("更新服务器返回 HTTP " + status);
            }
            return connection;
        }
        throw new IllegalStateException("更新下载重定向次数过多");
    }

    private void post(Runnable runnable) {
        if (!closed) mainHandler.post(runnable);
    }

    private static long contentLength(HttpURLConnection connection) {
        String value = connection.getHeaderField("Content-Length");
        if (isEmpty(value)) return -1L;
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException ignored) {
            return -1L;
        }
    }

    static long effectiveTotalSize(long responseLength, long releaseSize) {
        if (responseLength > 0L) return responseLength;
        return releaseSize > 0L ? releaseSize : -1L;
    }

    private static String hex(byte[] bytes) {
        StringBuilder result = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            result.append(String.format(Locale.ROOT, "%02x", value & 0xff));
        }
        return result.toString();
    }

    private static String messageOf(Exception error) {
        String message = error.getMessage();
        return isEmpty(message) ? error.getClass().getSimpleName() : message;
    }

    private static boolean isEmpty(String value) {
        return value == null || value.trim().isEmpty();
    }
}
