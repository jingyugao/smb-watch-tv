package com.smbwatch.tv;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import jcifs.CIFSContext;
import jcifs.context.SingletonContext;
import jcifs.smb.NtlmPasswordAuthenticator;
import jcifs.smb.SmbFile;

public class MainActivity extends Activity {

    private static final String PREF_NAME = "smb_pref";
    private static final String KEY_CONNECTIONS = "connections";
    private static final String KEY_PLAYLISTS = "playlists";
    private static final int REQ_BROWSE_SMB = 2001;
    private static final int TAB_RECENT = 0;
    private static final int TAB_PLAYLIST = 1;
    private static final int TAB_SMB = 2;

    public static final String DEFAULT_USERNAME = "";
    public static final String DEFAULT_PASSWORD = "";

    private EditText etSmbHost;
    private EditText etSmbUsername;
    private EditText etSmbPassword;
    private TextView tvStatus;
    private ListView lvConnections;
    private ListView lvPlaylists;
    private ListView lvRecentPlaylists;
    private Button btnAddConnection;
    private Button btnTestConnection;
    private Button btnDiscoverSmb;
    private Button btnCleanInvalidPlaylists;
    private Button btnRemoteControl;
    private Button btnTabRecent;
    private Button btnTabPlaylist;
    private Button btnTabSmb;
    private View sectionRecent;
    private View sectionPlaylists;
    private View sectionSmb;
    private ArrayAdapter<SmbConnection> connectionAdapter;
    private PlaylistAdapter playlistAdapter;
    private PlaylistAdapter recentPlaylistAdapter;

    private final List<SmbConnection> connections = new ArrayList<>();
    private final List<PlaylistItem> playlists = new ArrayList<>();
    private final List<PlaylistItem> recentPlaylists = new ArrayList<>();
    private final ExecutorService ioExecutor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private boolean isSavingConnection;
    private boolean isTestingConnection;
    private boolean isCleaningPlaylists;
    private SmbDiscovery smbDiscovery;
    private final Map<String, SmbDiscovery.Device> discoveredDevices = new LinkedHashMap<>();
    private int selectedTab = TAB_RECENT;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        getWindow().getDecorView().setBackgroundColor(Color.parseColor("#0A2540"));

        etSmbHost = findViewById(R.id.et_smb_host);
        etSmbUsername = findViewById(R.id.et_smb_username);
        etSmbPassword = findViewById(R.id.et_smb_password);
        tvStatus = findViewById(R.id.tv_status);
        lvConnections = findViewById(R.id.lv_connections);
        lvPlaylists = findViewById(R.id.lv_playlists);
        lvRecentPlaylists = findViewById(R.id.lv_recent_playlists);

        btnAddConnection = findViewById(R.id.btn_add_connection);
        btnTestConnection = findViewById(R.id.btn_test_connection);
        btnDiscoverSmb = findViewById(R.id.btn_discover_smb);
        btnCleanInvalidPlaylists = findViewById(R.id.btn_clean_invalid_playlists);
        btnRemoteControl = findViewById(R.id.btn_remote_control);
        btnTabRecent = findViewById(R.id.btn_tab_recent);
        btnTabPlaylist = findViewById(R.id.btn_tab_playlist);
        btnTabSmb = findViewById(R.id.btn_tab_smb);
        sectionRecent = findViewById(R.id.section_recent);
        sectionPlaylists = findViewById(R.id.section_playlists);
        sectionSmb = findViewById(R.id.section_smb);

        if (TextUtils.isEmpty(etSmbUsername.getText())) {
            etSmbUsername.setText(DEFAULT_USERNAME);
        }
        if (TextUtils.isEmpty(etSmbPassword.getText())) {
            etSmbPassword.setText(DEFAULT_PASSWORD);
        }

        reloadDataFromStorage();

        connectionAdapter = new ConnectionAdapter();
        lvConnections.setAdapter(connectionAdapter);
        refreshConnections();

        playlistAdapter = new PlaylistAdapter();
        lvPlaylists.setAdapter(playlistAdapter);
        refreshPlaylists();

        recentPlaylistAdapter = new PlaylistAdapter(recentPlaylists);
        lvRecentPlaylists.setAdapter(recentPlaylistAdapter);
        refreshRecentPlaylists();

        etSmbHost.setSelectAllOnFocus(true);
        etSmbUsername.setSelectAllOnFocus(true);
        etSmbPassword.setSelectAllOnFocus(true);

        btnAddConnection.setOnClickListener(v -> {
            if (isSavingConnection) {
                toast("正在保存连接，请稍候");
                return;
            }
            saveConnection();
        });
        btnTestConnection.setOnClickListener(v -> {
            if (isTestingConnection) {
                toast("正在测试连接，请稍候");
                return;
            }
            testCurrentInput();
        });
        btnDiscoverSmb.setOnClickListener(v -> startSmbDiscovery());
        btnCleanInvalidPlaylists.setOnClickListener(v -> cleanInvalidPlaylists());
        btnRemoteControl.setOnClickListener(v -> showRemoteControl());
        RemoteControlManager.get(this).setLibraryChangeListener(() -> {
            reloadDataFromStorage();
            refreshConnections();
            refreshPlaylists();
            refreshRecentPlaylists();
        });
        btnTabRecent.setOnClickListener(v -> showTab(TAB_RECENT));
        btnTabPlaylist.setOnClickListener(v -> showTab(TAB_PLAYLIST));
        btnTabSmb.setOnClickListener(v -> showTab(TAB_SMB));
        btnTabRecent.setOnFocusChangeListener((v, hasFocus) -> {
            if (hasFocus) showTab(TAB_RECENT, false);
        });
        btnTabPlaylist.setOnFocusChangeListener((v, hasFocus) -> {
            if (hasFocus) showTab(TAB_PLAYLIST, false);
        });
        btnTabSmb.setOnFocusChangeListener((v, hasFocus) -> {
            if (hasFocus) showTab(TAB_SMB, false);
        });

        lvConnections.setOnItemClickListener((parent, view, position, id) -> {
            if (position < 0 || position >= connections.size()) {
                return;
            }
            openBrowser(connections.get(position));
        });

        lvConnections.setOnItemLongClickListener((parent, view, position, id) -> {
            if (position < 0 || position >= connections.size()) {
                return true;
            }
            SmbConnection conn = connections.get(position);
            new AlertDialog.Builder(this)
                    .setMessage("删除连接 " + conn.name + "?")
                    .setPositiveButton("删除", (d, which) -> {
                        connections.remove(position);
                        saveConnections();
                        refreshConnections();
                        tvStatus.setText("已删除：" + conn.name);
                    })
                    .setNegativeButton("取消", null)
                    .show();
            return true;
        });

        lvPlaylists.setOnItemClickListener((parent, view, position, id) -> {
            if (position < 0 || position >= playlists.size()) {
                return;
            }
            openPlaylistDetail(playlists.get(position));
        });
        lvRecentPlaylists.setOnItemClickListener((parent, view, position, id) -> {
            if (position < 0 || position >= recentPlaylists.size()) {
                return;
            }
            openPlaylist(recentPlaylists.get(position));
        });

        showTab(TAB_RECENT);
        if (!TextUtils.isEmpty(etSmbHost.getText())) {
            etSmbHost.requestFocus();
        } else {
            btnTabRecent.requestFocus();
        }
    }

    private void openBrowser(SmbConnection conn) {
        Intent intent = new Intent(this, SmbBrowserActivity.class);
        intent.putExtra(SmbBrowserActivity.EXTRA_CONN_NAME, conn.name);
        intent.putExtra(SmbBrowserActivity.EXTRA_SMB_URL, conn.url);
        intent.putExtra(SmbBrowserActivity.EXTRA_USERNAME, conn.username);
        intent.putExtra(SmbBrowserActivity.EXTRA_PASSWORD, conn.password);
        startActivityForResult(intent, REQ_BROWSE_SMB);
    }

    private void openPlaylist(PlaylistItem item) {
        Intent intent = new Intent(this, PlayerActivity.class);
        intent.putExtra(PlayerActivity.EXTRA_CONN_NAME, item.connectionName);
        intent.putExtra(PlayerActivity.EXTRA_SOURCE_URL, item.sourceUrl);
        intent.putExtra(PlayerActivity.EXTRA_SMB_DIRECTORY, item.dirPath);
        intent.putExtra(PlayerActivity.EXTRA_USERNAME, item.username);
        intent.putExtra(PlayerActivity.EXTRA_PASSWORD, item.password);
        intent.putExtra(PlayerActivity.EXTRA_RESUME_FILE_PATH, item.lastEpisodePath);
        intent.putExtra(PlayerActivity.EXTRA_RESUME_POSITION_MS, item.lastEpisodePositionMs);
        startActivity(intent);
    }

    private void openPlaylistDetail(PlaylistItem item) {
        Intent intent = new Intent(this, PlaylistDetailActivity.class);
        intent.putExtra(PlaylistDetailActivity.EXTRA_TITLE, item.title);
        intent.putExtra(PlaylistDetailActivity.EXTRA_CONN_NAME, item.connectionName);
        intent.putExtra(PlaylistDetailActivity.EXTRA_SOURCE_URL, item.sourceUrl);
        intent.putExtra(PlaylistDetailActivity.EXTRA_DIRECTORY, item.dirPath);
        intent.putExtra(PlaylistDetailActivity.EXTRA_USERNAME, item.username);
        intent.putExtra(PlaylistDetailActivity.EXTRA_PASSWORD, item.password);
        intent.putExtra(PlaylistDetailActivity.EXTRA_RESUME_PATH, item.lastEpisodePath);
        intent.putExtra(PlaylistDetailActivity.EXTRA_RESUME_POSITION, item.lastEpisodePositionMs);
        startActivity(intent);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_BROWSE_SMB || resultCode != RESULT_OK || data == null) {
            return;
        }

        String fileName = data.getStringExtra(SmbBrowserActivity.RESULT_FILE_NAME);
        String filePath = data.getStringExtra(SmbBrowserActivity.RESULT_FILE_PATH);
        String dirPath = data.getStringExtra(SmbBrowserActivity.RESULT_DIR_PATH);
        String dirName = data.getStringExtra(SmbBrowserActivity.RESULT_DIR_NAME);
        String connectionName = data.getStringExtra(SmbBrowserActivity.RESULT_CONN_NAME);
        String sourceUrl = data.getStringExtra(SmbBrowserActivity.RESULT_CONN_URL);
        String username = data.getStringExtra(SmbBrowserActivity.RESULT_USERNAME);
        String password = data.getStringExtra(SmbBrowserActivity.RESULT_PASSWORD);
        String lastEpisodeName = data.getStringExtra(SmbBrowserActivity.RESULT_LAST_EPISODE_NAME);
        String lastEpisodePath = data.getStringExtra(SmbBrowserActivity.RESULT_LAST_EPISODE_PATH);
        long lastEpisodePos = data.getLongExtra(SmbBrowserActivity.RESULT_LAST_POSITION_MS, 0L);

        if (TextUtils.isEmpty(dirPath) && !TextUtils.isEmpty(filePath)) {
            dirPath = parentDirectoryOfFile(filePath);
        }

        if (TextUtils.isEmpty(dirPath)) {
            return;
        }
        if (TextUtils.isEmpty(fileName) && TextUtils.isEmpty(lastEpisodePath)) {
            // 仅保留目录，不选集
            lastEpisodePath = "";
            lastEpisodeName = "";
        }

        if (TextUtils.isEmpty(sourceUrl)) {
            sourceUrl = dirPath;
        }
        if (TextUtils.isEmpty(connectionName)) {
            connectionName = extractConnectionName(sourceUrl);
        }
        if (TextUtils.isEmpty(username)) {
            username = "";
        }
        if (TextUtils.isEmpty(password)) {
            password = "";
        }
        if (TextUtils.isEmpty(lastEpisodePath)) {
            fileName = "";
        }
        if (TextUtils.isEmpty(lastEpisodeName) && !TextUtils.isEmpty(fileName)) {
            lastEpisodeName = fileName;
        }
        if (TextUtils.isEmpty(dirName)) {
            dirName = extractDirectoryName(dirPath);
        }

        scanAndAddPlaylistFolders(connectionName, sourceUrl, username, password, dirPath,
                lastEpisodeName, lastEpisodePath, lastEpisodePos);
    }

    private void scanAndAddPlaylistFolders(String connectionName, String sourceUrl, String username,
                                           String password, String rootPath, String resumeName,
                                           String resumePath, long resumePosition) {
        final String safeConnectionName = connectionName;
        final String safeSourceUrl = sourceUrl;
        final String safeUsername = username;
        final String safePassword = password;
        final String safeRootPath = ensureTrailingSlash(rootPath);
        tvStatus.setText("正在扫描文件夹并生成播放列表...");
        ioExecutor.execute(() -> {
            try {
                List<SmbPlaylistScanner.Candidate> candidates = SmbPlaylistScanner.scan(
                        safeRootPath, buildSmbContext(safeUsername, safePassword));
                mainHandler.post(() -> {
                    for (SmbPlaylistScanner.Candidate candidate : candidates) {
                        boolean isSelectedRoot = TextUtils.equals(
                                ensureTrailingSlash(candidate.directoryPath), safeRootPath);
                        upsertPlaylistItem(
                                candidate.title,
                                safeConnectionName,
                                safeSourceUrl,
                                safeUsername,
                                safePassword,
                                candidate.directoryPath,
                                isSelectedRoot ? resumeName : "",
                                isSelectedRoot ? resumePath : "",
                                isSelectedRoot ? resumePosition : 0L
                        );
                    }
                    if (candidates.isEmpty()) {
                        tvStatus.setText("没有找到直接包含视频的文件夹");
                        toast("没有找到视频列表");
                    } else {
                        tvStatus.setText("已生成 " + candidates.size() + " 个播放列表");
                        toast("已添加 " + candidates.size() + " 个播放列表");
                    }
                });
            } catch (Exception e) {
                mainHandler.post(() -> {
                    tvStatus.setText("扫描失败：" + e.getMessage());
                    toast("扫描目录失败");
                });
            }
        });
    }

    private void reloadDataFromStorage() {
        connections.clear();
        playlists.clear();
        recentPlaylists.clear();
        loadConnections();
        loadPlaylists();
    }

    private void showTab(int tab) {
        showTab(tab, true);
    }

    private void showTab(int tab, boolean focusContent) {
        selectedTab = tab;
        if (sectionRecent == null || sectionPlaylists == null || sectionSmb == null) {
            return;
        }
        sectionRecent.setVisibility(tab == TAB_RECENT ? View.VISIBLE : View.GONE);
        sectionPlaylists.setVisibility(tab == TAB_PLAYLIST ? View.VISIBLE : View.GONE);
        sectionSmb.setVisibility(tab == TAB_SMB ? View.VISIBLE : View.GONE);

        if (btnTabRecent != null) {
            btnTabRecent.setSelected(tab == TAB_RECENT);
        }
        if (btnTabPlaylist != null) {
            btnTabPlaylist.setSelected(tab == TAB_PLAYLIST);
        }
        if (btnTabSmb != null) {
            btnTabSmb.setSelected(tab == TAB_SMB);
        }

        if (tab == TAB_RECENT) {
            refreshRecentPlaylists();
            if (!focusContent) return;
            if (lvRecentPlaylists != null && lvRecentPlaylists.getCount() > 0) {
                lvRecentPlaylists.requestFocus();
            } else {
                btnTabPlaylist.requestFocus();
            }
            return;
        }
        if (tab == TAB_PLAYLIST) {
            refreshPlaylists();
            if (!focusContent) return;
            if (lvPlaylists != null && lvPlaylists.getCount() > 0) {
                lvPlaylists.requestFocus();
            } else {
                btnTabSmb.requestFocus();
            }
            return;
        }
        if (tab == TAB_SMB) {
            if (!focusContent) return;
            if (btnDiscoverSmb != null) {
                btnDiscoverSmb.requestFocus();
            } else if (etSmbHost != null) {
                etSmbHost.requestFocus();
            }
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        reloadDataFromStorage();
        refreshConnections();
        refreshPlaylists();
        refreshRecentPlaylists();
        showTab(selectedTab);
    }

    @Override
    public void onBackPressed() {
        View focused = getCurrentFocus();
        if (focused != btnTabRecent && focused != btnTabPlaylist && focused != btnTabSmb) {
            if (selectedTab == TAB_RECENT) {
                btnTabRecent.requestFocus();
            } else if (selectedTab == TAB_PLAYLIST) {
                btnTabPlaylist.requestFocus();
            } else {
                btnTabSmb.requestFocus();
            }
            return;
        }
        super.onBackPressed();
    }

    private void upsertPlaylistItem(String title, String connectionName, String sourceUrl, String username, String password,
                                    String dirPath, String lastEpisodeName, String lastEpisodePath, long lastEpisodePosition) {
        String normalizedDir = ensureTrailingSlash(dirPath);
        if (TextUtils.isEmpty(normalizedDir)) {
            return;
        }
        if (TextUtils.isEmpty(sourceUrl)) {
            sourceUrl = normalizedDir;
        }

        PlaylistItem previous = null;
        for (int i = playlists.size() - 1; i >= 0; i--) {
            PlaylistItem item = playlists.get(i);
            if (sameDirectory(item.dirPath, normalizedDir)) {
                previous = preferredPlaylist(previous, item);
                playlists.remove(i);
            }
        }

        if (previous != null && TextUtils.isEmpty(lastEpisodePath)) {
            lastEpisodeName = previous.lastEpisodeName;
            lastEpisodePath = previous.lastEpisodePath;
            lastEpisodePosition = previous.lastEpisodePositionMs;
        }

        if (TextUtils.isEmpty(lastEpisodeName) && !TextUtils.isEmpty(lastEpisodePath)) {
            lastEpisodeName = extractFileName(lastEpisodePath);
        }

        playlists.add(new PlaylistItem(
                title,
                connectionName,
                sourceUrl,
                username,
                password,
                normalizedDir,
                lastEpisodeName,
                lastEpisodePath,
                Math.max(0L, lastEpisodePosition),
                previous == null ? System.currentTimeMillis() : previous.createdAt
        ));
        sortPlaylists();
        savePlaylists();
        refreshPlaylists();
        refreshRecentPlaylists();
    }

    private void saveConnection() {
        String rawUrl = String.valueOf(etSmbHost.getText()).trim();
        String user = String.valueOf(etSmbUsername.getText()).trim();
        String pass = String.valueOf(etSmbPassword.getText());

        if (TextUtils.isEmpty(rawUrl)) {
            toast("请输入 SMB IP");
            return;
        }

        final String finalUrl;
        try {
            finalUrl = normalizeSmbUrl(rawUrl);
        } catch (IllegalArgumentException e) {
            tvStatus.setText("无法保存：" + e.getMessage());
            return;
        }

        final String finalName = deriveConnectionNameFromUrl(finalUrl);
        final String finalUser = user;
        final String finalPass = pass;
        setSavingConnectionState(true);
        tvStatus.setText("添加中：先保存...");
        ioExecutor.execute(() -> {
            String msg = validateLocalNetwork(finalUrl);
            boolean ok = TextUtils.isEmpty(msg);
            mainHandler.post(() -> {
                    if (!ok) {
                        tvStatus.setText("无法保存：" + msg);
                        toast("添加失败：" + msg);
                        setSavingConnectionState(false);
                        return;
                    }

                connections.add(0, new SmbConnection(finalName, finalUrl, finalUser, finalPass));
                saveConnections();
                refreshConnections();
                tvStatus.setText("已保存：" + finalUrl);

                ioExecutor.execute(() -> {
                    String reachMsg = checkSmbConnection(finalUrl, finalUser, finalPass);
                    mainHandler.post(() -> {
                        if (TextUtils.isEmpty(reachMsg)) {
                            setSavingConnectionState(false);
                            toast("已添加连接，开始后台扫描：" + finalName);
                            scanAndAddPlaylistFolders(
                                    finalName,
                                    finalUrl,
                                    finalUser,
                                    finalPass,
                                    finalUrl,
                                    "",
                                    "",
                                    0L
                            );
                        } else {
                            tvStatus.setText("已保存：" + finalUrl + "（未通过连通性：" + reachMsg + "）");
                            toast("已添加连接，但当前不可达：" + finalName);
                            setSavingConnectionState(false);
                        }
                    });
                });
            });
        });
    }

    private void testCurrentInput() {
        String rawUrl = String.valueOf(etSmbHost.getText()).trim();
        String user = String.valueOf(etSmbUsername.getText()).trim();
        String pass = String.valueOf(etSmbPassword.getText());

        if (TextUtils.isEmpty(rawUrl)) {
            toast("请输入 SMB IP");
            return;
        }

        String finalUrl;
        try {
            finalUrl = normalizeSmbUrl(rawUrl);
        } catch (IllegalArgumentException e) {
            tvStatus.setText("测试失败：" + e.getMessage());
            return;
        }

        setTestingConnectionState(true);
        tvStatus.setText("测试中...");
        ioExecutor.execute(() -> {
            String msg = checkSmbConnection(finalUrl, user, pass);
            boolean ok = TextUtils.isEmpty(msg);
            mainHandler.post(() -> {
                if (ok) {
                    tvStatus.setText("测试成功：SMB 端口可达（内网校验通过）");
                } else {
                    tvStatus.setText("测试失败：" + msg);
                }
                setTestingConnectionState(false);
            });
        });
    }

    private void setSavingConnectionState(boolean saving) {
        isSavingConnection = saving;
        if (btnAddConnection != null) {
            btnAddConnection.setEnabled(!saving);
            btnAddConnection.setText(saving ? getString(R.string.btn_label_saving) : getString(R.string.btn_add_connection));
        }
    }

    private void setTestingConnectionState(boolean testing) {
        isTestingConnection = testing;
        if (btnTestConnection != null) {
            btnTestConnection.setEnabled(!testing);
            btnTestConnection.setText(testing ? getString(R.string.btn_label_testing) : getString(R.string.btn_test_connection));
        }
    }

    private void startSmbDiscovery() {
        if (smbDiscovery != null) {
            toast("正在扫描局域网");
            return;
        }
        discoveredDevices.clear();
        btnDiscoverSmb.setEnabled(false);
        btnDiscoverSmb.setText(R.string.btn_discovering_smb);
        tvStatus.setText("正在通过 WS-Discovery 和 TCP 445 扫描局域网...");
        smbDiscovery = new SmbDiscovery(getApplicationContext(), new SmbDiscovery.Callback() {
            @Override
            public void onDeviceFound(SmbDiscovery.Device device) {
                mainHandler.post(() -> {
                    discoveredDevices.put(device.host, device);
                    tvStatus.setText("已发现 " + discoveredDevices.size() + " 台 SMB 设备，继续扫描中...");
                });
            }

            @Override
            public void onFinished(String errorMessage) {
                mainHandler.post(() -> finishSmbDiscovery(errorMessage));
            }
        });
        smbDiscovery.start();
    }

    private void finishSmbDiscovery(String errorMessage) {
        if (smbDiscovery != null) {
            smbDiscovery.close();
            smbDiscovery = null;
        }
        btnDiscoverSmb.setEnabled(true);
        btnDiscoverSmb.setText(R.string.btn_discover_smb);
        if (discoveredDevices.isEmpty()) {
            String message = TextUtils.isEmpty(errorMessage)
                    ? "未发现 SMB 设备，请确认电视和服务器在同一局域网"
                    : "扫描失败：" + errorMessage;
            tvStatus.setText(message);
            toast(message);
            return;
        }

        List<SmbDiscovery.Device> devices = new ArrayList<>(discoveredDevices.values());
        String[] labels = new String[devices.size()];
        for (int i = 0; i < devices.size(); i++) {
            SmbDiscovery.Device device = devices.get(i);
            labels[i] = device.host + "    " + device.source;
        }
        tvStatus.setText("发现 " + devices.size() + " 台 SMB 设备");
        new AlertDialog.Builder(this)
                .setTitle("选择 SMB 设备")
                .setItems(labels, (dialog, which) -> {
                    SmbDiscovery.Device selected = devices.get(which);
                    etSmbHost.setText(selected.host);
                    etSmbUsername.requestFocus();
                    tvStatus.setText("已选择：" + selected.host + "，请输入账号密码后测试连接");
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private String deriveConnectionNameFromUrl(String smbUrl) {
        if (TextUtils.isEmpty(smbUrl)) {
            return "SMB";
        }
        Uri uri = Uri.parse(smbUrl);
        if (uri == null || TextUtils.isEmpty(uri.getHost())) {
            return "SMB";
        }
        String host = uri.getHost();
        String path = uri.getPath();
        if (TextUtils.isEmpty(path) || "/".equals(path)) {
            return host;
        }
        String trimmed = removeTrailingSlash(path);
        int slash = trimmed.lastIndexOf('/');
        if (slash < 0 || slash + 1 >= trimmed.length()) {
            return host + trimmed;
        }
        return host + "/" + trimmed.substring(slash + 1);
    }

    private String checkSmbConnection(String smbUrl, String username, String password) {
        try {
            requireLocalNetwork(smbUrl);
            String host = extractHost(smbUrl);
            if (!canReachSmbPort(host, 445, 3000)) {
                return "SMB 端口不可达（445）";
            }
            SmbFile root = new SmbFile(ensureTrailingSlash(smbUrl), buildSmbContext(username, password));
            if (!root.exists() || !root.isDirectory()) {
                return "SMB 地址无效或账号无权访问";
            }
            root.listFiles();
            return "";
        } catch (Exception e) {
            String message = e.getMessage();
            return TextUtils.isEmpty(message) ? "认证失败或无权浏览共享目录" : message;
        }
    }

    private String validateLocalNetwork(String smbUrl) {
        try {
            requireLocalNetwork(smbUrl);
            return "";
        } catch (IllegalArgumentException e) {
            return e.getMessage();
        }
    }

    private String validateLocalHost(String smbUrl) {
        try {
            requireLocalNetwork(smbUrl);
            return "";
        } catch (IllegalArgumentException e) {
            return e.getMessage();
        }
    }

    private String normalizeSmbUrl(String rawUrl) {
        String normalized = rawUrl;
        if (!normalized.startsWith("smb://")) {
            normalized = "smb://" + normalized;
        }

        Uri uri = Uri.parse(normalized);
        if (uri == null) {
            throw new IllegalArgumentException("非法 URL");
        }
        String host = uri.getHost();
        if (TextUtils.isEmpty(host)) {
            throw new IllegalArgumentException("非法 SMB 地址");
        }

        String path = uri.getEncodedPath();
        String query = uri.getEncodedQuery();
        String fragment = uri.getEncodedFragment();
        StringBuilder result = new StringBuilder();
        result.append("smb://");
        result.append(host);
        if (uri.getPort() > 0) {
            result.append(":").append(uri.getPort());
        }
        if (!TextUtils.isEmpty(path)) {
            result.append(path);
        }
        if (!TextUtils.isEmpty(query)) {
            result.append("?").append(query);
        }
        if (!TextUtils.isEmpty(fragment)) {
            result.append("#").append(fragment);
        }
        return result.toString();
    }

    private CIFSContext buildSmbContext(String username, String password) {
        if (TextUtils.isEmpty(username) && TextUtils.isEmpty(password)) {
            return SingletonContext.getInstance();
        }
        return SingletonContext.getInstance().withCredentials(
                new NtlmPasswordAuthenticator("", username, password == null ? "" : password));
    }

    private void requireLocalNetwork(String smbUrl) {
        Uri uri = Uri.parse(smbUrl);
        String host = uri == null ? null : uri.getHost();
        if (TextUtils.isEmpty(host)) {
            throw new IllegalArgumentException("非法 SMB 地址");
        }
        if (!isLocalHost(host) && !isPrivateHost(host)) {
            throw new IllegalArgumentException("仅允许访问局域网 SMB（192.168.x/172.16-31.x/10.x/169.254.x）");
        }
    }

    private boolean isLocalHost(String host) {
        return "localhost".equalsIgnoreCase(host) || host.startsWith("127.");
    }

    private boolean isPrivateHost(String host) {
        if (isIpV4(host) && isPrivateIpv4(host)) {
            return true;
        }

        try {
            InetAddress[] addrs = InetAddress.getAllByName(host);
            for (InetAddress address : addrs) {
                String ip = address.getHostAddress();
                if (address.isLoopbackAddress()) {
                    return true;
                }
                if (isIpV4(ip) && isPrivateIpv4(ip)) {
                    return true;
                }
            }
        } catch (UnknownHostException e) {
            return false;
        }
        return false;
    }

    private boolean isIpV4(String ip) {
        return !TextUtils.isEmpty(ip) && ip.matches("\\d+\\.\\d+\\.\\d+\\.\\d+");
    }

    private boolean isPrivateIpv4(String ip) {
        if (TextUtils.isEmpty(ip)) {
            return false;
        }
        if (ip.startsWith("10.")) {
            return true;
        }
        if (ip.startsWith("192.168.")) {
            return true;
        }
        if (ip.startsWith("169.254.")) {
            return true;
        }
        String[] parts = ip.split("\\.");
        if (parts.length != 4) {
            return false;
        }
        if (!"172".equals(parts[0])) {
            return false;
        }
        try {
            int second = Integer.parseInt(parts[1]);
            return second >= 16 && second <= 31;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private String extractHost(String smbUrl) {
        Uri uri = Uri.parse(smbUrl);
        if (uri == null || TextUtils.isEmpty(uri.getHost())) {
            throw new IllegalArgumentException("无法解析 Host");
        }
        return uri.getHost();
    }

    private boolean canReachSmbPort(String host, int port, int timeoutMs) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), timeoutMs);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private void loadConnections() {
        String raw = SecurePreferences.get(this).getString(KEY_CONNECTIONS, "");
        if (TextUtils.isEmpty(raw)) {
            return;
        }
        try {
            JSONArray arr = new JSONArray(raw);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject obj = arr.getJSONObject(i);
                String name = obj.optString("name", "");
                String url = obj.optString("url", "");
                String username = obj.optString("username", DEFAULT_USERNAME);
                String password = obj.optString("password", DEFAULT_PASSWORD);
                if (!TextUtils.isEmpty(url)) {
                    try {
                        url = normalizeSmbUrl(url);
                        connections.add(new SmbConnection(name, url, username, password));
                    } catch (IllegalArgumentException ignored) {
                        // Skip malformed legacy connections.
                    }
                }
            }
        } catch (JSONException e) {
            // ignore corrupt data
        }
    }

    private void saveConnections() {
        JSONArray arr = new JSONArray();
        for (SmbConnection conn : connections) {
            try {
                JSONObject obj = new JSONObject();
                obj.put("name", conn.name);
                obj.put("url", conn.url);
                obj.put("username", conn.username);
                obj.put("password", conn.password);
                arr.put(obj);
            } catch (JSONException ignored) {
            }
        }
        SecurePreferences.get(this).edit()
                .putString(KEY_CONNECTIONS, arr.toString())
                .apply();
    }

    private void loadPlaylists() {
        String raw = SecurePreferences.get(this).getString(KEY_PLAYLISTS, "");
        if (TextUtils.isEmpty(raw)) {
            return;
        }
        try {
            JSONArray arr = new JSONArray(raw);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject obj = arr.getJSONObject(i);
                String title = obj.optString("title", "");
                String connectionName = obj.optString("connectionName", "");
                String sourceUrl = obj.optString("sourceUrl", "");
                String dirPath = obj.optString("dirPath", "");
                String username = obj.optString("username", "");
                String password = obj.optString("password", "");
                String lastEpisodeName = obj.optString("lastEpisodeName", "");
                String lastEpisodePath = obj.optString("lastEpisodePath", "");
                long lastEpisodePositionMs = obj.optLong("lastEpisodePositionMs", 0L);
                long createdAt = obj.optLong("createdAt", System.currentTimeMillis());

                if (TextUtils.isEmpty(dirPath)) {
                    String legacyPath = obj.optString("path", "");
                    if (!TextUtils.isEmpty(legacyPath)) {
                        dirPath = parentDirectoryOfFile(legacyPath);
                        if (TextUtils.isEmpty(lastEpisodeName)) {
                            lastEpisodeName = extractFileName(legacyPath);
                        }
                        if (TextUtils.isEmpty(lastEpisodePath)) {
                            lastEpisodePath = legacyPath;
                        }
                        if (TextUtils.isEmpty(title)) {
                            title = extractDirectoryName(dirPath);
                        }
                        if (TextUtils.isEmpty(connectionName)) {
                            connectionName = extractConnectionName(sourceUrl);
                        }
                    }
                }
                if (TextUtils.isEmpty(dirPath)) {
                    continue;
                }
                if (TextUtils.isEmpty(sourceUrl)) {
                    sourceUrl = dirPath;
                }
                try {
                    sourceUrl = normalizeSmbUrl(sourceUrl);
                    dirPath = normalizeSmbUrl(dirPath);
                } catch (IllegalArgumentException ignored) {
                    continue;
                }
                if (TextUtils.isEmpty(connectionName)) {
                    connectionName = extractConnectionName(sourceUrl);
                }
                playlists.add(new PlaylistItem(
                        TextUtils.isEmpty(title) ? extractDirectoryName(dirPath) : title,
                        connectionName,
                        sourceUrl,
                        username,
                        password,
                        ensureTrailingSlash(dirPath),
                        lastEpisodeName,
                        lastEpisodePath,
                        lastEpisodePositionMs,
                        createdAt
                ));
            }
            if (deduplicatePlaylists()) {
                savePlaylists();
            }
            sortPlaylists();
        } catch (JSONException e) {
            // ignore corrupt data
        }
    }

    private boolean deduplicatePlaylists() {
        List<PlaylistItem> unique = new ArrayList<>();
        boolean changed = false;
        for (PlaylistItem candidate : playlists) {
            int duplicateIndex = -1;
            for (int i = 0; i < unique.size(); i++) {
                if (sameDirectory(unique.get(i).dirPath, candidate.dirPath)) {
                    duplicateIndex = i;
                    break;
                }
            }
            if (duplicateIndex < 0) {
                unique.add(candidate);
            } else {
                unique.set(duplicateIndex, preferredPlaylist(unique.get(duplicateIndex), candidate));
                changed = true;
            }
        }
        if (changed) {
            playlists.clear();
            playlists.addAll(unique);
        }
        return changed;
    }

    private PlaylistItem preferredPlaylist(PlaylistItem left, PlaylistItem right) {
        if (left == null) return right;
        if (right == null) return left;
        boolean leftHasProgress = !TextUtils.isEmpty(left.lastEpisodePath) || left.lastEpisodePositionMs > 0L;
        boolean rightHasProgress = !TextUtils.isEmpty(right.lastEpisodePath) || right.lastEpisodePositionMs > 0L;
        if (leftHasProgress != rightHasProgress) return rightHasProgress ? right : left;
        return right.createdAt >= left.createdAt ? right : left;
    }

    private boolean sameDirectory(String left, String right) {
        String normalizedLeft = ensureTrailingSlash(left == null ? "" : left);
        String normalizedRight = ensureTrailingSlash(right == null ? "" : right);
        return !TextUtils.isEmpty(normalizedLeft) && normalizedLeft.equalsIgnoreCase(normalizedRight);
    }

    private void cleanInvalidPlaylists() {
        if (isCleaningPlaylists) {
            toast("正在检查播放列表");
            return;
        }
        if (playlists.isEmpty()) {
            toast("播放列表为空");
            return;
        }

        isCleaningPlaylists = true;
        btnCleanInvalidPlaylists.setEnabled(false);
        btnCleanInvalidPlaylists.setText("检查中...");
        List<PlaylistItem> snapshot = new ArrayList<>(playlists);
        ioExecutor.execute(() -> {
            List<String> invalidDirectories = new ArrayList<>();
            int unavailableCount = 0;
            for (PlaylistItem item : snapshot) {
                if (Thread.currentThread().isInterrupted()) return;
                try {
                    SmbFile directory = new SmbFile(
                            ensureTrailingSlash(item.dirPath),
                            buildSmbContext(item.username, item.password));
                    if (!directory.exists() || !directory.isDirectory()) {
                        invalidDirectories.add(item.dirPath);
                    }
                } catch (Exception ignored) {
                    unavailableCount++;
                }
            }

            int finalUnavailableCount = unavailableCount;
            mainHandler.post(() -> {
                int before = playlists.size();
                playlists.removeIf(item -> {
                    for (String invalidDirectory : invalidDirectories) {
                        if (sameDirectory(item.dirPath, invalidDirectory)) return true;
                    }
                    return false;
                });
                boolean deduplicated = deduplicatePlaylists();
                int removed = before - playlists.size();
                if (removed > 0 || deduplicated) savePlaylists();
                refreshPlaylists();
                refreshRecentPlaylists();
                isCleaningPlaylists = false;
                btnCleanInvalidPlaylists.setEnabled(true);
                btnCleanInvalidPlaylists.setText("清理失效列表");
                String result = removed > 0 ? "已清理 " + removed + " 个失效列表" : "没有失效列表";
                if (finalUnavailableCount > 0) result += "，" + finalUnavailableCount + " 个暂时无法确认";
                toast(result);
            });
        });
    }

    private void showRemoteControl() {
        RemoteControlManager remote = RemoteControlManager.get(this);
        try {
            remote.start();
            String address = remote.getPairingUrl();
            Bitmap qr = remote.createQrCode(420);
            LinearLayout content = new LinearLayout(this);
            content.setOrientation(LinearLayout.VERTICAL);
            content.setPadding(36, 24, 36, 12);
            TextView hint = new TextView(this);
            hint.setText("手机与电视连接同一局域网后扫码\n" + address.substring(0, address.indexOf('#')));
            hint.setTextColor(Color.WHITE);
            hint.setTextSize(18f);
            content.addView(hint);
            ImageView image = new ImageView(this);
            image.setImageBitmap(qr);
            content.addView(image, new LinearLayout.LayoutParams(420, 420));
            new AlertDialog.Builder(this)
                    .setTitle("手机遥控与 SMB 管理")
                    .setView(content)
                    .setNegativeButton("关闭", null)
                    .show();
        } catch (Exception e) {
            toast("启动手机遥控失败：" + e.getMessage());
        }
    }

    private void savePlaylists() {
        JSONArray arr = new JSONArray();
        for (PlaylistItem item : playlists) {
            try {
                JSONObject obj = new JSONObject();
                obj.put("title", item.title);
                obj.put("connectionName", item.connectionName);
                obj.put("sourceUrl", item.sourceUrl);
                obj.put("dirPath", item.dirPath);
                obj.put("username", item.username);
                obj.put("password", item.password);
                obj.put("lastEpisodeName", item.lastEpisodeName);
                obj.put("lastEpisodePath", item.lastEpisodePath);
                obj.put("lastEpisodePositionMs", item.lastEpisodePositionMs);
                obj.put("createdAt", item.createdAt);
                arr.put(obj);
            } catch (JSONException ignored) {
            }
        }
        SecurePreferences.get(this).edit()
                .putString(KEY_PLAYLISTS, arr.toString())
                .apply();
    }

    private void sortPlaylists() {
        playlists.sort((a, b) -> {
            int cmp = String.CASE_INSENSITIVE_ORDER.compare(a.title, b.title);
            if (cmp != 0) {
                return cmp;
            }
            return Long.compare(b.createdAt, a.createdAt);
        });
    }

    private void refreshRecentPlaylists() {
        recentPlaylists.clear();
        for (PlaylistItem item : playlists) {
            boolean hasHistory = !TextUtils.isEmpty(item.lastEpisodePath) || !TextUtils.isEmpty(item.lastEpisodeName);
            if (hasHistory) {
                recentPlaylists.add(item);
            }
        }
        recentPlaylists.sort((a, b) -> Long.compare(b.createdAt, a.createdAt));
        if (recentPlaylistAdapter != null) {
            recentPlaylistAdapter.notifyDataSetChanged();
        }
        if (tvStatus != null && selectedTab == TAB_RECENT && recentPlaylists.isEmpty()) {
            tvStatus.setText("最近播放空，先播放一个电视剧目录后会自动显示。");
        }
    }

    private void refreshConnections() {
        if (connectionAdapter != null) {
            connectionAdapter.notifyDataSetChanged();
        }
    }

    private void refreshPlaylists() {
        if (playlistAdapter != null) {
            playlistAdapter.notifyDataSetChanged();
        }
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }

    private String extractConnectionName(String smbUrl) {
        if (TextUtils.isEmpty(smbUrl)) {
            return "SMB";
        }
        Uri uri = Uri.parse(smbUrl);
        if (uri == null || TextUtils.isEmpty(uri.getHost())) {
            return "SMB";
        }
        return uri.getHost();
    }

    private String parentDirectoryOfFile(String filePath) {
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
        String trimmed = removeTrailingSlash(ensureTrailingSlash(fileUrlPath));
        int lastSlash = trimmed.lastIndexOf('/');
        if (lastSlash <= 0) {
            StringBuilder base = new StringBuilder("smb://");
            base.append(uri.getHost());
            if (uri.getPort() > 0) {
                base.append(":").append(uri.getPort());
            }
            base.append("/");
            return base.toString();
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

    private String extractDirectoryName(String smbPath) {
        if (TextUtils.isEmpty(smbPath)) {
            return "未命名目录";
        }
        Uri uri = Uri.parse(smbPath);
        if (uri == null) {
            return smbPath;
        }
        String path = uri.getPath();
        if (TextUtils.isEmpty(path)) {
            return smbPath;
        }
        String trimmed = removeTrailingSlash(path);
        int slash = trimmed.lastIndexOf('/');
        if (slash < 0 || slash + 1 >= trimmed.length()) {
            return trimmed;
        }
        return trimmed.substring(slash + 1);
    }

    private String extractFileName(String filePath) {
        if (TextUtils.isEmpty(filePath)) {
            return "";
        }
        int slash = filePath.lastIndexOf('/');
        if (slash < 0 || slash + 1 >= filePath.length()) {
            return filePath;
        }
        return filePath.substring(slash + 1);
    }

    private String formatPosition(long ms) {
        long totalSeconds = Math.max(0L, ms / 1000L);
        long minutes = totalSeconds / 60L;
        long seconds = totalSeconds % 60L;
        return minutes + ":" + String.format("%02d", seconds);
    }

    private String ensureTrailingSlash(String raw) {
        if (TextUtils.isEmpty(raw)) {
            return "";
        }
        if (raw.endsWith("/")) {
            return raw;
        }
        return raw + "/";
    }

    private String removeTrailingSlash(String raw) {
        if (TextUtils.isEmpty(raw)) {
            return "";
        }
        if (raw.endsWith("/")) {
            return raw.substring(0, raw.length() - 1);
        }
        return raw;
    }

    @Override
    protected void onDestroy() {
        if (smbDiscovery != null) {
            smbDiscovery.close();
            smbDiscovery = null;
        }
        super.onDestroy();
        ioExecutor.shutdownNow();
    }

    private class ConnectionAdapter extends ArrayAdapter<SmbConnection> {
        ConnectionAdapter() {
            super(MainActivity.this, R.layout.item_connection, connections);
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            View view = convertView;
            if (view == null) {
                view = LayoutInflater.from(getContext()).inflate(R.layout.item_connection, parent, false);
            }

            SmbConnection conn = getItem(position);
            if (conn == null) {
                return view;
            }

            TextView tvName = view.findViewById(R.id.tv_conn_name);
            TextView tvUrl = view.findViewById(R.id.tv_conn_url);
            TextView tvAccount = view.findViewById(R.id.tv_conn_account);

            tvName.setText(TextUtils.isEmpty(conn.name) ? conn.url : conn.name);
            tvUrl.setText(conn.url);
            tvAccount.setText("账号：" + (TextUtils.isEmpty(conn.username) ? "（匿名）" : conn.username));
            return view;
        }
    }

    private class PlaylistAdapter extends ArrayAdapter<PlaylistItem> {
        PlaylistAdapter() {
            super(MainActivity.this, R.layout.item_playlist, playlists);
        }

        PlaylistAdapter(List<PlaylistItem> source) {
            super(MainActivity.this, R.layout.item_playlist, source);
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            View view = convertView;
            if (view == null) {
                view = LayoutInflater.from(getContext()).inflate(R.layout.item_playlist, parent, false);
            }

            PlaylistItem item = getItem(position);
            if (item == null) {
                return view;
            }

            TextView tvTitle = view.findViewById(R.id.tv_playlist_title);
            TextView tvConnection = view.findViewById(R.id.tv_playlist_connection);
            TextView tvPath = view.findViewById(R.id.tv_playlist_path);
            TextView tvLastEpisode = view.findViewById(R.id.tv_playlist_last_episode);
            TextView tvProgress = view.findViewById(R.id.tv_playlist_progress);
            View btnPlay = view.findViewById(R.id.btn_play_playlist);

            tvTitle.setText(item.title);
            tvConnection.setText("来源：" + item.connectionName);
            tvPath.setText(item.dirPath);
            tvLastEpisode.setText("上次选集：" + (TextUtils.isEmpty(item.lastEpisodeName) ? "未播放" : item.lastEpisodeName));
            tvProgress.setText("上次进度：" + formatPosition(item.lastEpisodePositionMs));
            if (btnPlay != null) {
                btnPlay.setOnClickListener(v -> openPlaylist(item));
            }

            return view;
        }
    }

    private static class SmbConnection {
        final String name;
        final String url;
        final String username;
        final String password;

        SmbConnection(String name, String url, String username, String password) {
            this.name = name;
            this.url = url;
            this.username = username;
            this.password = password;
        }
    }

    private static class PlaylistItem {
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

        PlaylistItem(String title, String connectionName, String sourceUrl, String username, String password,
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
    }
}
