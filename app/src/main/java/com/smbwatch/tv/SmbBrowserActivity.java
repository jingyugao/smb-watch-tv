package com.smbwatch.tv;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.UUID;

import jcifs.CIFSContext;
import jcifs.smb.SmbFile;

public class SmbBrowserActivity extends Activity {

    public static final String EXTRA_CONN_NAME = "extra_conn_name";
    public static final String EXTRA_SMB_URL = "extra_smb_url";
    public static final String EXTRA_USERNAME = "extra_username";
    public static final String EXTRA_PASSWORD = "extra_password";
    public static final String EXTRA_START_DIRECTORY = "extra_start_directory";
    public static final String EXTRA_RESUME_FILE_PATH = "extra_resume_file_path";
    public static final String EXTRA_RESUME_POSITION_MS = "extra_resume_position_ms";

    public static final String RESULT_FILE_NAME = "result_file_name";
    public static final String RESULT_FILE_PATH = "result_file_path";
    public static final String RESULT_CONN_NAME = "result_conn_name";
    public static final String RESULT_CONN_URL = "result_conn_url";
    public static final String RESULT_USERNAME = "result_username";
    public static final String RESULT_PASSWORD = "result_password";
    public static final String RESULT_DIR_PATH = "result_dir_path";
    public static final String RESULT_DIR_NAME = "result_dir_name";
    public static final String RESULT_LAST_EPISODE_NAME = "result_last_episode_name";
    public static final String RESULT_LAST_EPISODE_PATH = "result_last_episode_path";
    public static final String RESULT_LAST_POSITION_MS = "result_last_position_ms";

    private static final String[] VIDEO_EXTS = {
            ".mp4", ".mkv", ".mov", ".flv", ".avi", ".ts", ".m4v", ".webm"
    };

    private TextView tvCurrentPath;
    private Button btnAddCurrentDirectory;
    private ListView lvFiles;
    private ArrayAdapter<BrowserItem> fileAdapter;

    private final List<BrowserItem> files = new ArrayList<>();
    private final ExecutorService ioExecutor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private String connectionName;
    private String username;
    private String password;
    private String rootUrl;
    private String currentUrl;
    private String resumeFilePath = "";
    private String resumeEpisodeName = "";
    private long resumePositionMs = 0L;
    private boolean resumeHintShown = false;
    private String loadRequestId = "";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_smb_browser);

        tvCurrentPath = findViewById(R.id.tv_browser_path);
        btnAddCurrentDirectory = findViewById(R.id.btn_add_current_directory);
        lvFiles = findViewById(R.id.lv_browser_files);

        connectionName = getIntent().getStringExtra(EXTRA_CONN_NAME);
        String rawUrl = getIntent().getStringExtra(EXTRA_SMB_URL);
        username = getIntent().getStringExtra(EXTRA_USERNAME);
        password = getIntent().getStringExtra(EXTRA_PASSWORD);
        String startDirectory = getIntent().getStringExtra(EXTRA_START_DIRECTORY);
        String inputResumePath = getIntent().getStringExtra(EXTRA_RESUME_FILE_PATH);
        resumeFilePath = TextUtils.isEmpty(inputResumePath) ? "" : normalizeFilePath(inputResumePath);
        resumePositionMs = Math.max(0L, getIntent().getLongExtra(EXTRA_RESUME_POSITION_MS, 0L));
        if (!TextUtils.isEmpty(resumeFilePath)) {
            int slash = resumeFilePath.lastIndexOf('/');
            if (slash >= 0 && slash < resumeFilePath.length() - 1) {
                resumeEpisodeName = resumeFilePath.substring(slash + 1);
            }
        }

        if (TextUtils.isEmpty(rawUrl)) {
            toast("SMB 地址无效");
            finish();
            return;
        }

        rootUrl = ensureSmbDir(removeSmbUserInfo(rawUrl));
        currentUrl = resolveStartDirectory(startDirectory, rootUrl);

        fileAdapter = new BrowserAdapter();
        lvFiles.setAdapter(fileAdapter);
        lvFiles.setOnItemClickListener((parent, view, position, id) -> {
            if (position < 0 || position >= files.size()) {
                return;
            }
            BrowserItem item = files.get(position);
            if (item.isParent) {
                loadDirectory(parentPath(currentUrl));
                return;
            }
            if (item.isDirectory) {
                loadDirectory(item.path);
            } else {
                openPlayer(currentUrl, item.path, item.name);
            }
        });
        btnAddCurrentDirectory.setOnClickListener(v -> returnCurrentDirectoryResult());

        loadDirectory(currentUrl);
    }

    private void loadDirectory(String targetUrl) {
        if (TextUtils.isEmpty(targetUrl)) {
            return;
        }

        String next = ensureSmbDir(targetUrl);
        final String normalizedTarget = removeSmbUserInfo(next);
        final String requestId = UUID.randomUUID().toString();
        loadRequestId = requestId;
        tvCurrentPath.setText("加载中...");
        ioExecutor.execute(() -> {
            try {
                SmbFile dir = new SmbFile(normalizedTarget, buildContext());
                if (!dir.exists() || !dir.isDirectory()) {
                    throw new IllegalStateException("不是有效目录");
                }

                SmbFile[] arr = dir.listFiles();
                if (arr == null) {
                    throw new IllegalStateException("目录为空或无权限");
                }

                List<BrowserItem> temp = new ArrayList<>();
                if (!TextUtils.equals(removeTrailingSlash(normalizedTarget), removeTrailingSlash(rootUrl))) {
                    temp.add(BrowserItem.parent());
                }

                List<BrowserItem> dirs = new ArrayList<>();
                List<BrowserItem> mediaFiles = new ArrayList<>();
                for (SmbFile file : arr) {
                    if (file == null) {
                        continue;
                    }
                    String name = normalizeName(file.getName());
                    if (TextUtils.isEmpty(name)) {
                        continue;
                    }
                    boolean isDir = file.isDirectory();
                    if (isDir) {
                        dirs.add(new BrowserItem(name, file.getPath(), true, false));
                    } else if (isMediaFile(name)) {
                        mediaFiles.add(new BrowserItem(name, file.getPath(), false, false));
                    }
                }
                sortList(dirs);
                sortList(mediaFiles);
                temp.addAll(dirs);
                temp.addAll(mediaFiles);

                mainHandler.post(() -> {
                    if (!requestId.equals(loadRequestId)) {
                        return;
                    }
                    currentUrl = ensureSmbDir(normalizedTarget);
                    tvCurrentPath.setText(currentUrl);
                    files.clear();
                    files.addAll(temp);
                    fileAdapter.notifyDataSetChanged();
                    updateResumeHint();
                });
            } catch (Exception e) {
                mainHandler.post(() -> {
                    if (!requestId.equals(loadRequestId)) {
                        return;
                    }
                    toast("打开失败：" + e.getMessage());
                });
            }
        });
    }

    private void returnEpisodeResult(String directoryPath, String episodePath, String name, long positionMs) {
        Intent result = buildResult(directoryPath, name, episodePath, positionMs);
        result.putExtra(RESULT_FILE_NAME, name);
        result.putExtra(RESULT_FILE_PATH, episodePath);
        setResult(RESULT_OK, result);
        finish();
    }

    private void openPlayer(String directoryPath, String episodePath, String episodeName) {
        Intent intent = new Intent(this, PlayerActivity.class);
        intent.putExtra(PlayerActivity.EXTRA_CONN_NAME, connectionName);
        intent.putExtra(PlayerActivity.EXTRA_SOURCE_URL, rootUrl);
        intent.putExtra(PlayerActivity.EXTRA_SMB_DIRECTORY, TextUtils.isEmpty(directoryPath) ? currentUrl : directoryPath);
        intent.putExtra(PlayerActivity.EXTRA_USERNAME, TextUtils.isEmpty(username) ? "" : username);
        intent.putExtra(PlayerActivity.EXTRA_PASSWORD, TextUtils.isEmpty(password) ? "" : password);
        intent.putExtra(PlayerActivity.EXTRA_RESUME_FILE_PATH, episodePath);
        intent.putExtra(PlayerActivity.EXTRA_RESUME_POSITION_MS, 0L);
        startActivity(intent);
    }

    private void returnCurrentDirectoryResult() {
        if (TextUtils.isEmpty(currentUrl)) {
            toast("当前目录无效");
            return;
        }
        boolean inResumeDirectory = isResumeInCurrentDirectory();
        String resumedName = inResumeDirectory ? resumeEpisodeName : "";
        long resumedPosition = inResumeDirectory ? resumePositionMs : 0L;
        Intent result = buildResult(currentUrl, resumedName, inResumeDirectory ? resumeFilePath : "", resumedPosition);
        result.putExtra(RESULT_FILE_NAME, "");
        result.putExtra(RESULT_FILE_PATH, "");
        setResult(RESULT_OK, result);
        finish();
    }

    private Intent buildResult(String dirPath, String lastEpisodeName, String lastEpisodePath, long lastEpisodePositionMs) {
        Intent result = new Intent();
        result.putExtra(RESULT_CONN_NAME, TextUtils.isEmpty(connectionName) ? "SMB" : connectionName);
        result.putExtra(RESULT_CONN_URL, rootUrl);
        result.putExtra(RESULT_USERNAME, TextUtils.isEmpty(username) ? "" : username);
        result.putExtra(RESULT_PASSWORD, TextUtils.isEmpty(password) ? "" : password);
        result.putExtra(RESULT_DIR_PATH, ensureSmbDir(dirPath));
        result.putExtra(RESULT_DIR_NAME, extractDirectoryName(dirPath));
        result.putExtra(RESULT_LAST_EPISODE_NAME, TextUtils.isEmpty(lastEpisodeName) ? "" : lastEpisodeName);
        result.putExtra(RESULT_LAST_EPISODE_PATH, TextUtils.isEmpty(lastEpisodePath) ? "" : normalizeFilePath(lastEpisodePath));
        result.putExtra(RESULT_LAST_POSITION_MS, Math.max(0L, lastEpisodePositionMs));
        return result;
    }

    private boolean isMediaFile(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        for (String ext : VIDEO_EXTS) {
            if (lower.endsWith(ext)) {
                return true;
            }
        }
        return false;
    }

    private void sortList(List<BrowserItem> list) {
        Collections.sort(list, Comparator.comparing(a -> a.name.toLowerCase(Locale.ROOT)));
    }

    private boolean isResumeInCurrentDirectory() {
        if (TextUtils.isEmpty(currentUrl) || TextUtils.isEmpty(resumeFilePath)) {
            return false;
        }
        String resumeDir = parentDirectoryFromFile(resumeFilePath);
        String currentDir = removeTrailingSlash(ensureSmbDir(currentUrl));
        return !TextUtils.isEmpty(resumeDir) && TextUtils.equals(removeTrailingSlash(resumeDir), currentDir);
    }

    private void updateResumeHint() {
        if (resumeHintShown || TextUtils.isEmpty(resumeFilePath) || TextUtils.isEmpty(currentUrl) || TextUtils.isEmpty(resumeEpisodeName)) {
            return;
        }
        if (!isResumeInCurrentDirectory()) {
            return;
        }
        String txt = "上次记忆："
                + resumeEpisodeName
                + "，进度 "
                + formatMs(resumePositionMs);
        Toast toast = Toast.makeText(this, txt, Toast.LENGTH_LONG);
        toast.setGravity(Gravity.CENTER_VERTICAL, 0, 0);
        toast.show();
        resumeHintShown = true;
    }

    private String extractDirectoryName(String smbPath) {
        if (TextUtils.isEmpty(smbPath)) {
            return "未命名目录";
        }
        Uri uri = Uri.parse(smbPath);
        if (uri == null) {
            return "未命名目录";
        }
        String path = uri.getPath();
        if (TextUtils.isEmpty(path)) {
            return "未命名目录";
        }
        String trimmed = removeTrailingSlash(path);
        int slash = trimmed.lastIndexOf('/');
        if (slash < 0 || slash + 1 >= trimmed.length()) {
            return trimmed;
        }
        return trimmed.substring(slash + 1);
    }

    private String normalizePath(String rawPath) {
        if (TextUtils.isEmpty(rawPath)) {
            return "";
        }
        return ensureSmbDir(removeSmbUserInfo(rawPath));
    }

    private String resolveStartDirectory(String startDirectory, String rootUrl) {
        if (TextUtils.isEmpty(startDirectory)) {
            return rootUrl;
        }
        String candidate = normalizePath(startDirectory);
        return isSameHost(candidate, rootUrl) ? candidate : rootUrl;
    }

    private boolean isSameHost(String urlA, String urlB) {
        Uri uriA = Uri.parse(urlA);
        Uri uriB = Uri.parse(urlB);
        if (uriA == null || uriB == null) {
            return false;
        }
        String hostA = uriA.getHost();
        String hostB = uriB.getHost();
        if (TextUtils.isEmpty(hostA) || TextUtils.isEmpty(hostB)) {
            return false;
        }
        int portA = uriA.getPort();
        int portB = uriB.getPort();
        return hostA.equalsIgnoreCase(hostB) && portA == portB;
    }

    private String normalizeFilePath(String rawPath) {
        if (TextUtils.isEmpty(rawPath)) {
            return "";
        }
        return removeTrailingSlash(removeSmbUserInfo(rawPath));
    }

    private String parentDirectoryFromFile(String filePath) {
        if (TextUtils.isEmpty(filePath)) {
            return "";
        }
        Uri uri = Uri.parse(filePath);
        if (uri == null || TextUtils.isEmpty(uri.getHost())) {
            return "";
        }
        String fileUrlPath = uri.getPath();
        if (TextUtils.isEmpty(fileUrlPath)) {
            return "";
        }
        String trimmed = removeTrailingSlash(fileUrlPath);
        int lastSlash = trimmed.lastIndexOf('/');
        if (lastSlash < 0) {
            return "";
        }
        String parentPath = trimmed.substring(0, lastSlash + 1);
        StringBuilder base = new StringBuilder("smb://");
        base.append(uri.getHost());
        if (uri.getPort() > 0) {
            base.append(":").append(uri.getPort());
        }
        base.append(parentPath);
        return base.toString();
    }

    private String removeSmbUserInfo(String url) {
        Uri uri = Uri.parse(url);
        if (uri == null || TextUtils.isEmpty(uri.getHost())) {
            return ensureSmbDir(url);
        }
        String host = uri.getHost();
        int port = uri.getPort();
        String path = TextUtils.isEmpty(uri.getPath()) ? "/" : uri.getPath();
        if (port > 0) {
            return "smb://" + host + ":" + port + ensureSmbDir(path);
        }
        return "smb://" + host + ensureSmbDir(path);
    }

    private String removeTrailingSlash(String path) {
        if (TextUtils.isEmpty(path)) {
            return "";
        }
        if (path.endsWith("/")) {
            return path.substring(0, path.length() - 1);
        }
        return path;
    }

    private String ensureSmbDir(String url) {
        if (TextUtils.isEmpty(url)) {
            return "";
        }
        if (url.endsWith("/")) {
            return url;
        }
        return url + "/";
    }

    private String parentPath(String url) {
        if (TextUtils.isEmpty(url) || TextUtils.isEmpty(rootUrl)) {
            return rootUrl;
        }
        Uri currentUri = Uri.parse(url);
        Uri rootUri = Uri.parse(rootUrl);
        if (currentUri == null || rootUri == null) {
            return rootUrl;
        }

        String path = currentUri.getPath();
        String normalized = ensureSmbDir(TextUtils.isEmpty(path) ? "" : path);
        String trimmedPath = removeTrailingSlash(normalized);
        String rootPath = removeTrailingSlash(TextUtils.isEmpty(rootUri.getPath()) ? "/" : rootUri.getPath());
        if (TextUtils.isEmpty(trimmedPath) || TextUtils.equals(trimmedPath, rootPath)) {
            return rootUrl;
        }

        int split = trimmedPath.lastIndexOf('/');
        if (split <= 0) {
            return rootUrl;
        }
        String parent = trimmedPath.substring(0, split + 1);
        return "smb://" + rootUri.getHost() + (rootUri.getPort() > 0 ? ":" + rootUri.getPort() : "") + parent;
    }

    private String normalizeName(String name) {
        if (TextUtils.isEmpty(name)) {
            return "";
        }
        if (name.endsWith("/")) {
            return name.substring(0, name.length() - 1);
        }
        return name;
    }

    private CIFSContext buildContext() {
        return SmbContexts.withCredentials(username, password);
    }

    private String formatMs(long ms) {
        long totalSeconds = Math.max(0L, ms / 1000L);
        long minutes = totalSeconds / 60L;
        long seconds = totalSeconds % 60L;
        return minutes + ":" + String.format(Locale.ROOT, "%02d", seconds);
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        ioExecutor.shutdownNow();
    }

    private static class BrowserItem {
        final String name;
        final String path;
        final boolean isDirectory;
        final boolean isParent;

        private BrowserItem(String name, String path, boolean isDirectory, boolean isParent) {
            this.name = name;
            this.path = path;
            this.isDirectory = isDirectory;
            this.isParent = isParent;
        }

        static BrowserItem parent() {
            return new BrowserItem("返回上级", "", false, true);
        }
    }

    private class BrowserAdapter extends ArrayAdapter<BrowserItem> {
        BrowserAdapter() {
            super(SmbBrowserActivity.this, R.layout.item_smb_file, files);
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            View view = convertView;
            if (view == null) {
                view = LayoutInflater.from(getContext()).inflate(R.layout.item_smb_file, parent, false);
            }

            BrowserItem item = getItem(position);
            if (item == null) {
                return view;
            }

            TextView tvName = view.findViewById(R.id.tv_file_name);
            TextView tvMeta = view.findViewById(R.id.tv_file_meta);
            tvName.setText(item.name);

            if (item.isParent) {
                tvMeta.setText("上级目录");
                return view;
            }

            if (item.isDirectory) {
                tvMeta.setText("文件夹");
                return view;
            }

            String suffix = "视频文件";
            if (!TextUtils.isEmpty(resumeFilePath) && TextUtils.equals(normalizeFilePath(item.path), resumeFilePath)) {
                suffix = "上次播放";
            }
            tvMeta.setText(suffix);
            return view;
        }
    }
}
