package com.smbwatch.tv;

import android.app.Activity;
import android.content.Intent;
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

import jcifs.CIFSContext;

public class PlaylistDetailActivity extends Activity {
    public static final String EXTRA_TITLE = "detail_title";
    public static final String EXTRA_CONN_NAME = "detail_conn_name";
    public static final String EXTRA_SOURCE_URL = "detail_source_url";
    public static final String EXTRA_DIRECTORY = "detail_directory";
    public static final String EXTRA_USERNAME = "detail_username";
    public static final String EXTRA_PASSWORD = "detail_password";
    public static final String EXTRA_RESUME_PATH = "detail_resume_path";
    public static final String EXTRA_RESUME_POSITION = "detail_resume_position";

    private final List<Episode> episodes = new ArrayList<>();
    private final ExecutorService ioExecutor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private EpisodeAdapter adapter;
    private TextView status;
    private Button continueButton;
    private String title;
    private String connectionName;
    private String sourceUrl;
    private String directory;
    private String username;
    private String password;
    private String resumePath;
    private long resumePosition;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_playlist_detail);
        title = value(EXTRA_TITLE);
        connectionName = value(EXTRA_CONN_NAME);
        sourceUrl = value(EXTRA_SOURCE_URL);
        directory = value(EXTRA_DIRECTORY);
        username = value(EXTRA_USERNAME);
        password = value(EXTRA_PASSWORD);
        resumePath = normalizeFilePath(value(EXTRA_RESUME_PATH));
        resumePosition = Math.max(0L, getIntent().getLongExtra(EXTRA_RESUME_POSITION, 0L));

        ((TextView) findViewById(R.id.tv_detail_title)).setText(title);
        status = findViewById(R.id.tv_detail_status);
        continueButton = findViewById(R.id.btn_continue_playlist);
        ListView list = findViewById(R.id.lv_episodes);
        adapter = new EpisodeAdapter();
        list.setAdapter(adapter);
        list.setOnItemClickListener((parent, view, position, id) -> playEpisode(position, 0L));
        continueButton.setOnClickListener(v -> playResume());
        continueButton.requestFocus();
        updateContinueButton();
        loadEpisodes();
    }

    private void loadEpisodes() {
        status.setText("正在读取选集...");
        ioExecutor.execute(() -> {
            try {
                List<Episode> loaded = new ArrayList<>();
                List<SmbEpisodeScanner.Item> found = SmbEpisodeScanner.scan(
                        ensureDirectory(removeUserInfo(directory)), buildContext());
                for (SmbEpisodeScanner.Item item : found) {
                    loaded.add(new Episode(item.displayName, item.path));
                }
                mainHandler.post(() -> {
                    episodes.clear();
                    episodes.addAll(loaded);
                    adapter.notifyDataSetChanged();
                    status.setText(loaded.isEmpty() ? "当前目录没有可播放视频" : "共 " + loaded.size() + " 集");
                });
            } catch (Exception e) {
                mainHandler.post(() -> status.setText("读取失败：" + e.getMessage()));
            }
        });
    }

    private void playResume() {
        int index = 0;
        for (int i = 0; i < episodes.size(); i++) {
            if (TextUtils.equals(normalizeFilePath(episodes.get(i).path), resumePath)) {
                index = i;
                break;
            }
        }
        if (episodes.isEmpty() && TextUtils.isEmpty(resumePath)) {
            Toast.makeText(this, "选集仍在加载", Toast.LENGTH_SHORT).show();
            return;
        }
        launchPlayer(TextUtils.isEmpty(resumePath) && !episodes.isEmpty() ? episodes.get(index).path : resumePath, resumePosition);
    }

    private void playEpisode(int index, long position) {
        if (index < 0 || index >= episodes.size()) return;
        launchPlayer(episodes.get(index).path, position);
    }

    private void launchPlayer(String path, long position) {
        Intent intent = new Intent(this, PlayerActivity.class);
        intent.putExtra(PlayerActivity.EXTRA_CONN_NAME, connectionName);
        intent.putExtra(PlayerActivity.EXTRA_SOURCE_URL, sourceUrl);
        intent.putExtra(PlayerActivity.EXTRA_SMB_DIRECTORY, directory);
        intent.putExtra(PlayerActivity.EXTRA_USERNAME, username);
        intent.putExtra(PlayerActivity.EXTRA_PASSWORD, password);
        intent.putExtra(PlayerActivity.EXTRA_RESUME_FILE_PATH, path);
        intent.putExtra(PlayerActivity.EXTRA_RESUME_POSITION_MS, position);
        startActivity(intent);
    }

    @Override
    protected void onResume() {
        super.onResume();
        PlaylistStore.Playlist item = PlaylistStore.findByDirectory(
                this, ensureDirectory(removeUserInfo(directory)));
        if (item != null) {
            resumePath = normalizeFilePath(item.lastEpisodePath);
            resumePosition = item.lastEpisodePositionMs;
            updateContinueButton();
            if (adapter != null) adapter.notifyDataSetChanged();
        }
    }

    private void updateContinueButton() {
        if (continueButton == null) return;
        String episode = fileName(resumePath);
        continueButton.setText(TextUtils.isEmpty(episode)
                ? "从第一集开始播放"
                : "继续播放  " + episode + "  ·  " + formatMs(resumePosition));
    }

    private CIFSContext buildContext() {
        return SmbContexts.withCredentials(username, password);
    }

    private String value(String key) { String v = getIntent().getStringExtra(key); return v == null ? "" : v; }
    private String ensureDirectory(String value) { return TextUtils.isEmpty(value) || value.endsWith("/") ? value : value + "/"; }
    private String trimSlash(String value) { return value != null && value.endsWith("/") ? value.substring(0, value.length() - 1) : value; }
    private String normalizeFilePath(String value) { return TextUtils.isEmpty(value) ? "" : trimSlash(removeUserInfo(value)); }
    private String removeUserInfo(String value) {
        Uri uri = Uri.parse(value);
        if (uri == null || TextUtils.isEmpty(uri.getHost())) return value;
        return "smb://" + uri.getHost() + (uri.getPort() > 0 ? ":" + uri.getPort() : "")
                + (TextUtils.isEmpty(uri.getPath()) ? "/" : uri.getPath());
    }
    private String fileName(String value) { int i = value == null ? -1 : value.lastIndexOf('/'); return i < 0 ? value : value.substring(i + 1); }
    private String formatMs(long ms) { long s = Math.max(0L, ms / 1000L); return String.format(Locale.ROOT, "%02d:%02d", s / 60L, s % 60L); }

    @Override
    protected void onDestroy() {
        ioExecutor.shutdownNow();
        super.onDestroy();
    }

    private static class Episode {
        final String name;
        final String path;
        Episode(String name, String path) { this.name = name; this.path = path; }
    }

    private class EpisodeAdapter extends ArrayAdapter<Episode> {
        EpisodeAdapter() { super(PlaylistDetailActivity.this, R.layout.item_episode, episodes); }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            View view = convertView == null
                    ? LayoutInflater.from(getContext()).inflate(R.layout.item_episode, parent, false)
                    : convertView;
            Episode episode = getItem(position);
            TextView name = view.findViewById(R.id.tv_episode_name);
            TextView meta = view.findViewById(R.id.tv_episode_meta);
            name.setText(episode == null ? "" : episode.name);
            boolean current = episode != null && TextUtils.equals(normalizeFilePath(episode.path), resumePath);
            meta.setText(current ? "上次播放  ·  " + formatMs(resumePosition) : "按确认播放");
            view.setSelected(current);
            return view;
        }
    }
}
