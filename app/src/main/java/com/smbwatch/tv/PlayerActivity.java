package com.smbwatch.tv;

import android.app.Activity;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.KeyEvent;
import android.widget.Button;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.ProgressiveMediaSource;
import androidx.media3.ui.PlayerView;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import jcifs.CIFSContext;
import jcifs.context.SingletonContext;
import jcifs.smb.NtlmPasswordAuthenticator;
import jcifs.smb.SmbFile;

public class PlayerActivity extends Activity {
    public static final String EXTRA_CONN_NAME = "player_conn_name";
    public static final String EXTRA_SOURCE_URL = "player_source_url";
    public static final String EXTRA_SMB_DIRECTORY = "player_smb_directory";
    public static final String EXTRA_USERNAME = "player_username";
    public static final String EXTRA_PASSWORD = "player_password";
    public static final String EXTRA_RESUME_FILE_PATH = "player_resume_file_path";
    public static final String EXTRA_RESUME_POSITION_MS = "player_resume_position_ms";

    private static final String KEY_PLAYLISTS = "playlists";
    private static final int PROGRESS_INTERVAL_MS = 700;
    private static final long AUTO_NEXT_DELAY_MS = 900L;
    private static final long AUTO_SAVE_INTERVAL_MS = 5000L;
    private static final long SEEK_STEP_MS = 10000L;

    private TextView tvTitle;
    private TextView tvStatus;
    private TextView tvProgress;
    private TextView tvDuration;
    private SeekBar sbProgress;
    private Button btnPrev;
    private Button btnPlay;
    private Button btnNext;
    private PlayerView playerView;
    private ExoPlayer player;

    private final ExecutorService ioExecutor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Handler uiHandler = new Handler(Looper.getMainLooper());
    private final List<EpisodeItem> episodes = new ArrayList<>();
    private final Runnable progressRunnable = new Runnable() {
        @Override
        public void run() {
            refreshProgress();
            uiHandler.postDelayed(this, PROGRESS_INTERVAL_MS);
        }
    };

    private String connectionName = "SMB";
    private String sourceUrl = "";
    private String directoryPath = "";
    private String username = "";
    private String password = "";
    private String resumeEpisodePath = "";
    private long initialResumePositionMs;
    private long lastPositionMs;
    private long lastAutoSavePositionMs = -1L;
    private long lastAutoSaveAtMs;
    private int currentIndex = -1;
    private boolean isSeeking;
    private boolean isDestroying;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_player);

        tvTitle = findViewById(R.id.tv_player_title);
        tvStatus = findViewById(R.id.tv_player_status);
        tvProgress = findViewById(R.id.tv_progress_current);
        tvDuration = findViewById(R.id.tv_progress_total);
        sbProgress = findViewById(R.id.sb_progress);
        btnPrev = findViewById(R.id.btn_prev_episode);
        btnPlay = findViewById(R.id.btn_play_toggle);
        btnNext = findViewById(R.id.btn_next_episode);
        playerView = findViewById(R.id.player_view);

        connectionName = valueOrEmpty(getIntent().getStringExtra(EXTRA_CONN_NAME));
        sourceUrl = valueOrEmpty(getIntent().getStringExtra(EXTRA_SOURCE_URL));
        directoryPath = valueOrEmpty(getIntent().getStringExtra(EXTRA_SMB_DIRECTORY));
        username = valueOrEmpty(getIntent().getStringExtra(EXTRA_USERNAME));
        password = valueOrEmpty(getIntent().getStringExtra(EXTRA_PASSWORD));
        resumeEpisodePath = normalizeFilePath(getIntent().getStringExtra(EXTRA_RESUME_FILE_PATH));
        initialResumePositionMs = Math.max(0L, getIntent().getLongExtra(EXTRA_RESUME_POSITION_MS, 0L));

        if (TextUtils.isEmpty(connectionName)) connectionName = "SMB";
        if (TextUtils.isEmpty(sourceUrl)) {
            toast("播放源无效");
            finish();
            return;
        }
        if (TextUtils.isEmpty(directoryPath)) directoryPath = sourceUrl;

        player = new ExoPlayer.Builder(this).build();
        playerView.setPlayer(player);
        playerView.setUseController(false);
        setupControls();
        setupPlayerListener();
        playerView.requestFocus();
        loadEpisodesAndPlay();
    }

    private void setupControls() {
        btnPlay.setOnClickListener(v -> togglePlayPause());
        btnNext.setOnClickListener(v -> playNextEpisode());
        btnPrev.setOnClickListener(v -> playPrevEpisode());
        findViewById(R.id.btn_replay).setOnClickListener(v -> {
            if (player != null) {
                player.seekTo(0L);
                player.play();
            }
        });
        sbProgress.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser) tvProgress.setText(formatMs(progress));
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) { isSeeking = true; }
            @Override public void onStopTrackingTouch(SeekBar seekBar) {
                isSeeking = false;
                if (player != null) player.seekTo(seekBar.getProgress());
            }
        });
    }

    private void setupPlayerListener() {
        player.addListener(new Player.Listener() {
            @Override
            public void onPlaybackStateChanged(int state) {
                if (state == Player.STATE_READY) {
                    btnPlay.setText(player.isPlaying() ? "暂停" : "播放");
                    uiSetStatus("播放中");
                    startProgressRefresh();
                } else if (state == Player.STATE_BUFFERING) {
                    uiSetStatus("缓冲中...");
                } else if (state == Player.STATE_ENDED) {
                    onEpisodeComplete();
                }
            }

            @Override
            public void onIsPlayingChanged(boolean playing) {
                btnPlay.setText(playing ? "暂停" : "播放");
            }

            @Override
            public void onPlayerError(PlaybackException error) {
                lastPositionMs = player == null ? lastPositionMs : Math.max(0L, player.getCurrentPosition());
                saveCurrentProgress(lastPositionMs);
                uiSetStatus("播放失败：" + error.getErrorCodeName());
                toast("当前格式或网络流无法播放");
            }
        });
    }

    private void loadEpisodesAndPlay() {
        uiSetStatus("加载目录中...");
        ioExecutor.execute(() -> {
            try {
                SmbFile dir = new SmbFile(ensureSmbDir(removeSmbUserInfo(directoryPath)), buildContext());
                SmbFile[] entries = dir.listFiles();
                if (entries == null) throw new IllegalStateException("目录列表为空或无权限");
                List<EpisodeItem> list = new ArrayList<>();
                for (SmbFile item : entries) {
                    if (item == null || item.isDirectory()) continue;
                    String name = normalizeFileName(item.getName());
                    if (isVideoFile(name)) list.add(new EpisodeItem(item.getPath(), name));
                }
                if (list.isEmpty()) throw new IllegalStateException("当前目录没有可播放文件");
                Collections.sort(list, Comparator.comparing(a -> a.name.toLowerCase(Locale.ROOT)));
                int startIndex = resolveStartIndex(list);
                mainHandler.post(() -> {
                    if (isDestroying) return;
                    episodes.clear();
                    episodes.addAll(list);
                    currentIndex = startIndex;
                    lastPositionMs = initialResumePositionMs;
                    playCurrentEpisode();
                });
            } catch (Exception e) {
                mainHandler.post(() -> {
                    if (isDestroying) return;
                    toast("无法播放：" + e.getMessage());
                    finish();
                });
            }
        });
    }

    private int resolveStartIndex(List<EpisodeItem> list) {
        if (!TextUtils.isEmpty(resumeEpisodePath)) {
            for (int i = 0; i < list.size(); i++) {
                if (TextUtils.equals(normalizeFilePath(list.get(i).path), resumeEpisodePath)) return i;
            }
            String resumeName = extractFileName(resumeEpisodePath);
            for (int i = 0; i < list.size(); i++) {
                if (TextUtils.equals(list.get(i).name, resumeName)) return i;
            }
        }
        return 0;
    }

    private void playCurrentEpisode() {
        if (player == null || currentIndex < 0 || currentIndex >= episodes.size()) return;
        EpisodeItem item = episodes.get(currentIndex);
        lastAutoSaveAtMs = 0L;
        lastAutoSavePositionMs = -1L;
        tvTitle.setText("播放：" + item.name + "（" + (currentIndex + 1) + "/" + episodes.size() + "）");
        uiSetStatus("正在打开：" + item.name);
        ProgressiveMediaSource source = new ProgressiveMediaSource.Factory(
                new SmbDataSource.Factory(buildContext())).createMediaSource(MediaItem.fromUri(Uri.parse(item.path)));
        player.stop();
        player.setMediaSource(source);
        player.prepare();
        if (lastPositionMs > 0L) player.seekTo(lastPositionMs);
        player.play();
        saveCurrentProgress(lastPositionMs);
    }

    private void onEpisodeComplete() {
        saveCurrentProgress(player == null ? lastPositionMs : player.getDuration());
        int nextIndex = currentIndex + 1;
        if (nextIndex >= episodes.size()) {
            uiSetStatus("播放结束");
            toast("已播放完该目录全部视频");
            return;
        }
        uiSetStatus("该集播放结束，自动播放下一集");
        uiHandler.postDelayed(() -> {
            if (isDestroying) return;
            currentIndex = nextIndex;
            lastPositionMs = 0L;
            playCurrentEpisode();
        }, AUTO_NEXT_DELAY_MS);
    }

    private void playNextEpisode() {
        if (currentIndex + 1 >= episodes.size()) { toast("已经是最后一集"); return; }
        saveCurrentProgress(currentPosition());
        currentIndex++;
        lastPositionMs = 0L;
        playCurrentEpisode();
    }

    private void playPrevEpisode() {
        if (currentIndex <= 0) { toast("已经是第一集"); return; }
        saveCurrentProgress(currentPosition());
        currentIndex--;
        lastPositionMs = 0L;
        playCurrentEpisode();
    }

    private void togglePlayPause() {
        if (player == null) return;
        if (player.isPlaying()) {
            player.pause();
            saveCurrentProgress(currentPosition());
            uiSetStatus("已暂停");
        } else {
            player.play();
            uiSetStatus("播放中");
        }
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
            boolean videoFocused = getCurrentFocus() == playerView;
            if (videoFocused && event.getKeyCode() == KeyEvent.KEYCODE_DPAD_LEFT) {
                seekBy(-SEEK_STEP_MS);
                return true;
            }
            if (videoFocused && event.getKeyCode() == KeyEvent.KEYCODE_DPAD_RIGHT) {
                seekBy(SEEK_STEP_MS);
                return true;
            }
        }
        return super.dispatchKeyEvent(event);
    }

    private void seekBy(long offsetMs) {
        if (player == null || player.getPlaybackState() == Player.STATE_IDLE) return;
        long duration = player.getDuration();
        long target = Math.max(0L, currentPosition() + offsetMs);
        if (duration > 0L) target = Math.min(target, duration);
        player.seekTo(target);
        lastPositionMs = target;
        tvProgress.setText(formatMs(target));
        uiSetStatus(offsetMs > 0 ? "快进 10 秒" : "快退 10 秒");
    }

    private long currentPosition() {
        return player == null ? lastPositionMs : Math.max(0L, player.getCurrentPosition());
    }

    private void refreshProgress() {
        if (player == null || isSeeking || currentIndex < 0 || currentIndex >= episodes.size()) return;
        long duration = Math.max(0L, player.getDuration());
        long position = currentPosition();
        sbProgress.setMax((int) Math.min(Integer.MAX_VALUE, Math.max(1L, duration)));
        sbProgress.setProgress((int) Math.min(Integer.MAX_VALUE, position));
        tvProgress.setText(formatMs(position));
        tvDuration.setText(formatMs(duration));
        lastPositionMs = position;
        saveProgressIfNeeded(position);
    }

    private void saveProgressIfNeeded(long positionMs) {
        if (player == null || !player.isPlaying() || positionMs <= 0L) return;
        long now = System.currentTimeMillis();
        if (now - lastAutoSaveAtMs < AUTO_SAVE_INTERVAL_MS) return;
        if (Math.abs(positionMs - lastAutoSavePositionMs) < 1000L) return;
        saveCurrentProgress(positionMs);
        lastAutoSaveAtMs = now;
        lastAutoSavePositionMs = positionMs;
    }

    private void startProgressRefresh() {
        uiHandler.removeCallbacks(progressRunnable);
        uiHandler.post(progressRunnable);
    }

    private void stopProgressRefresh() { uiHandler.removeCallbacks(progressRunnable); }

    private void saveCurrentProgress(long positionMs) {
        if (episodes.isEmpty() || currentIndex < 0 || currentIndex >= episodes.size()) return;
        EpisodeItem item = episodes.get(currentIndex);
        String title = extractDirectoryName(sourceUrl, directoryPath);
        String safeSource = ensureSmbDir(removeSmbUserInfo(sourceUrl));
        String safeDir = ensureSmbDir(removeSmbUserInfo(directoryPath));
        if (TextUtils.isEmpty(safeDir)) return;
        if (TextUtils.isEmpty(safeSource)) safeSource = safeDir;

        SharedPreferences sp = SecurePreferences.get(this);
        JSONArray arr;
        try { arr = new JSONArray(sp.getString(KEY_PLAYLISTS, "")); }
        catch (Exception ignored) { arr = new JSONArray(); }
        JSONArray merged = new JSONArray();
        boolean found = false;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject obj = arr.optJSONObject(i);
            if (obj == null) continue;
            if (TextUtils.equals(obj.optString("dirPath"), safeDir)
                    && TextUtils.equals(obj.optString("sourceUrl"), safeSource)) {
                merged.put(buildPlaylistJson(title, safeSource, safeDir, item, positionMs));
                found = true;
            } else {
                merged.put(obj);
            }
        }
        if (!found) merged.put(buildPlaylistJson(title, safeSource, safeDir, item, positionMs));
        sp.edit().putString(KEY_PLAYLISTS, merged.toString()).apply();
    }

    private JSONObject buildPlaylistJson(String title, String safeSource, String safeDir,
                                         EpisodeItem item, long positionMs) {
        JSONObject obj = new JSONObject();
        try {
            obj.put("title", title);
            obj.put("connectionName", connectionName);
            obj.put("sourceUrl", safeSource);
            obj.put("dirPath", safeDir);
            obj.put("username", username);
            obj.put("password", password);
            obj.put("lastEpisodeName", item.name);
            obj.put("lastEpisodePath", normalizeFilePath(item.path));
            obj.put("lastEpisodePositionMs", Math.max(0L, positionMs));
            obj.put("createdAt", System.currentTimeMillis());
        } catch (JSONException ignored) { }
        return obj;
    }

    private boolean isVideoFile(String name) {
        String n = valueOrEmpty(name).toLowerCase(Locale.ROOT);
        return n.endsWith(".mp4") || n.endsWith(".mkv") || n.endsWith(".mov") || n.endsWith(".flv")
                || n.endsWith(".avi") || n.endsWith(".ts") || n.endsWith(".m4v") || n.endsWith(".webm");
    }

    private CIFSContext buildContext() {
        if (TextUtils.isEmpty(username) && TextUtils.isEmpty(password)) return SingletonContext.getInstance();
        return SingletonContext.getInstance().withCredentials(new NtlmPasswordAuthenticator("", username, password));
    }

    private String normalizeFilePath(String raw) { return TextUtils.isEmpty(raw) ? "" : removeTrailingSlash(removeSmbUserInfo(raw)); }
    private String normalizeFileName(String raw) { return !TextUtils.isEmpty(raw) && raw.endsWith("/") ? raw.substring(0, raw.length() - 1) : raw; }
    private String valueOrEmpty(@Nullable String value) { return value == null ? "" : value; }
    private String extractFileName(String path) {
        if (TextUtils.isEmpty(path)) return "";
        int slash = path.lastIndexOf('/');
        return slash < 0 || slash + 1 >= path.length() ? path : path.substring(slash + 1);
    }
    private String extractDirectoryName(String src, String dir) {
        Uri uri = Uri.parse(dir);
        if (uri == null || TextUtils.isEmpty(uri.getPath())) return TextUtils.isEmpty(src) ? "未命名目录" : src;
        String path = removeTrailingSlash(uri.getPath());
        int slash = path.lastIndexOf('/');
        return slash < 0 || slash + 1 >= path.length() ? path : path.substring(slash + 1);
    }
    private String removeTrailingSlash(String raw) { return !TextUtils.isEmpty(raw) && raw.endsWith("/") ? raw.substring(0, raw.length() - 1) : valueOrEmpty(raw); }
    private String ensureSmbDir(String raw) { return TextUtils.isEmpty(raw) || raw.endsWith("/") ? valueOrEmpty(raw) : raw + "/"; }
    private String removeSmbUserInfo(String url) {
        Uri uri = Uri.parse(url);
        if (uri == null || TextUtils.isEmpty(uri.getHost())) return url;
        String port = uri.getPort() > 0 ? ":" + uri.getPort() : "";
        return "smb://" + uri.getHost() + port + (TextUtils.isEmpty(uri.getPath()) ? "/" : uri.getPath());
    }
    private String formatMs(long ms) {
        long seconds = Math.max(0L, ms / 1000L);
        return String.format(Locale.ROOT, "%02d:%02d", seconds / 60L, seconds % 60L);
    }
    private void uiSetStatus(String message) { if (tvStatus != null) tvStatus.setText(valueOrEmpty(message)); }
    private void toast(String message) { Toast.makeText(this, message, Toast.LENGTH_SHORT).show(); }

    @Override
    protected void onPause() {
        super.onPause();
        lastPositionMs = currentPosition();
        if (player != null) player.pause();
        saveCurrentProgress(lastPositionMs);
        stopProgressRefresh();
    }

    @Override
    protected void onResume() {
        super.onResume();
        startProgressRefresh();
    }

    @Override
    protected void onDestroy() {
        isDestroying = true;
        stopProgressRefresh();
        ioExecutor.shutdownNow();
        if (playerView != null) playerView.setPlayer(null);
        if (player != null) player.release();
        super.onDestroy();
    }

    private static class EpisodeItem {
        final String path;
        final String name;
        EpisodeItem(String path, String name) { this.path = path; this.name = name; }
    }
}
