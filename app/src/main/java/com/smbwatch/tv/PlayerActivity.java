package com.smbwatch.tv;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.KeyEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.TrackSelectionOverride;
import androidx.media3.common.Tracks;
import androidx.media3.exoplayer.DefaultLoadControl;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy;
import androidx.media3.ui.AspectRatioFrameLayout;
import androidx.media3.ui.PlayerView;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import jcifs.CIFSContext;
import jcifs.context.SingletonContext;
import jcifs.smb.NtlmPasswordAuthenticator;

public class PlayerActivity extends Activity {
    public static final String EXTRA_CONN_NAME = "player_conn_name";
    public static final String EXTRA_SOURCE_URL = "player_source_url";
    public static final String EXTRA_SMB_DIRECTORY = "player_smb_directory";
    public static final String EXTRA_USERNAME = "player_username";
    public static final String EXTRA_PASSWORD = "player_password";
    public static final String EXTRA_RESUME_FILE_PATH = "player_resume_file_path";
    public static final String EXTRA_RESUME_POSITION_MS = "player_resume_position_ms";

    private static final String KEY_PLAYLISTS = "playlists";
    private static final String KEY_SPEED = "player_speed";
    private static final String KEY_SEEK_STEP = "player_seek_step";
    private static final String KEY_AUDIO_INDEX = "player_audio_index";
    private static final String KEY_TEXT_INDEX = "player_text_index";
    private static final int PROGRESS_INTERVAL_MS = 700;
    private static final long AUTO_NEXT_DELAY_MS = 5000L;
    private static final long AUTO_SAVE_INTERVAL_MS = 5000L;
    private static final long CONTROLS_TIMEOUT_MS = 6000L;
    private static final int MAX_PLAYBACK_RETRIES = 3;
    private static final float[] SPEEDS = {0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f};
    private static final long[] SEEK_STEPS = {10000L, 30000L, 60000L};

    private TextView tvTitle;
    private TextView tvStatus;
    private TextView tvProgress;
    private TextView tvDuration;
    private SeekBar sbProgress;
    private Button btnPrev;
    private Button btnPlay;
    private Button btnNext;
    private Button btnSpeed;
    private Button btnAudioTrack;
    private Button btnSubtitleTrack;
    private Button btnSeekStep;
    private PlayerView playerView;
    private View playerControls;
    private boolean controlsVisible;
    private ExoPlayer player;

    private final ExecutorService ioExecutor = Executors.newSingleThreadExecutor();
    private final ExecutorService progressExecutor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Handler uiHandler = new Handler(Looper.getMainLooper());
    private final List<EpisodeItem> episodes = new ArrayList<>();
    private final Runnable progressRunnable = new Runnable() {
        @Override public void run() {
            refreshProgress();
            uiHandler.postDelayed(this, PROGRESS_INTERVAL_MS);
        }
    };
    private final Runnable hideControlsRunnable = () -> {
        if (player != null && player.isPlaying()) hideControls();
    };
    private Runnable pendingAutoNext;
    private Runnable pendingRetry;

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
    private long seekStepMs = SEEK_STEPS[0];
    private long accumulatedSeekPositionMs = -1L;
    private long lastSeekCommandAtMs;
    private int currentIndex = -1;
    private int playbackRetryCount;
    private int preferredAudioIndex = -1;
    private int preferredTextIndex = -1;
    private boolean isSeeking;
    private boolean isDestroying;
    private boolean applyingRememberedTracks;
    private float playbackSpeed = 1f;
    private RemoteControlManager.PlaybackBridge remotePlaybackBridge;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        setContentView(R.layout.activity_player);
        enterImmersiveMode();
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        tvTitle = findViewById(R.id.tv_player_title);
        tvStatus = findViewById(R.id.tv_player_status);
        tvProgress = findViewById(R.id.tv_progress_current);
        tvDuration = findViewById(R.id.tv_progress_total);
        sbProgress = findViewById(R.id.sb_progress);
        btnPrev = findViewById(R.id.btn_prev_episode);
        btnPlay = findViewById(R.id.btn_play_toggle);
        btnNext = findViewById(R.id.btn_next_episode);
        btnSpeed = findViewById(R.id.btn_speed);
        btnAudioTrack = findViewById(R.id.btn_audio_track);
        btnSubtitleTrack = findViewById(R.id.btn_subtitle_track);
        btnSeekStep = findViewById(R.id.btn_seek_step);
        playerView = findViewById(R.id.player_view);
        playerControls = findViewById(R.id.player_controls);
        playerControls.setVisibility(View.GONE);

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

        loadPlayerPreferences();
        DefaultLoadControl loadControl = new DefaultLoadControl.Builder()
                .setBufferDurationsMs(45000, 180000, 3000, 5000)
                .setPrioritizeTimeOverSizeThresholds(true)
                .build();
        player = new ExoPlayer.Builder(this).setLoadControl(loadControl).build();
        player.setPlaybackSpeed(playbackSpeed);
        playerView.setPlayer(player);
        playerView.setUseController(false);
        playerView.setResizeMode(AspectRatioFrameLayout.RESIZE_MODE_ZOOM);
        setupControls();
        setupPlayerListener();
        updatePreferenceButtons();
        playerView.requestFocus();
        registerRemotePlayback();
        loadEpisodesAndPlay();
    }

    private void registerRemotePlayback() {
        remotePlaybackBridge = new RemoteControlManager.PlaybackBridge() {
            @Override public JSONObject state() {
                JSONObject result = new JSONObject();
                try {
                    result.put("active", true);
                    result.put("title", tvTitle == null ? "" : tvTitle.getText());
                    result.put("status", tvStatus == null ? "" : tvStatus.getText());
                    result.put("playing", player != null && player.isPlaying());
                    result.put("position", currentPosition());
                    result.put("duration", player == null ? 0L : Math.max(0L, player.getDuration()));
                    result.put("speed", playbackSpeed);
                } catch (JSONException ignored) { }
                return result;
            }

            @Override public void action(String action, JSONObject data) {
                switch (action) {
                    case "toggle": togglePlayPause(); break;
                    case "next": playNextEpisode(); break;
                    case "previous": playPrevEpisode(); break;
                    case "seek": seekBy(data.optLong("offset", 0L)); break;
                    case "speed":
                        float speed = (float) data.optDouble("speed", 1d);
                        if (speed >= 0.5f && speed <= 2f) {
                            playbackSpeed = speed;
                            if (player != null) player.setPlaybackSpeed(speed);
                            SecurePreferences.get(PlayerActivity.this).edit().putFloat(KEY_SPEED, speed).apply();
                            updatePreferenceButtons();
                        }
                        break;
                }
            }
        };
        RemoteControlManager.get(this).setPlaybackBridge(remotePlaybackBridge);
    }

    private void setupControls() {
        btnPlay.setOnClickListener(v -> togglePlayPause());
        btnNext.setOnClickListener(v -> playNextEpisode());
        btnPrev.setOnClickListener(v -> playPrevEpisode());
        btnSpeed.setOnClickListener(v -> showSpeedPicker());
        btnAudioTrack.setOnClickListener(v -> showTrackPicker(C.TRACK_TYPE_AUDIO));
        btnSubtitleTrack.setOnClickListener(v -> showTrackPicker(C.TRACK_TYPE_TEXT));
        btnSeekStep.setOnClickListener(v -> cycleSeekStep());
        findViewById(R.id.btn_replay).setOnClickListener(v -> {
            cancelPendingTransitions();
            if (player != null) {
                player.seekTo(0L);
                player.play();
                scheduleHideControls();
            }
        });
        findViewById(R.id.btn_episode_list).setOnClickListener(v -> showEpisodePicker());
        sbProgress.setKeyProgressIncrement((int) seekStepMs);
        sbProgress.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser) tvProgress.setText(formatMs(progress));
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {
                isSeeking = true;
                uiHandler.removeCallbacks(hideControlsRunnable);
            }
            @Override public void onStopTrackingTouch(SeekBar seekBar) {
                isSeeking = false;
                if (player != null) player.seekTo(seekBar.getProgress());
                scheduleHideControls();
            }
        });
    }

    private void setupPlayerListener() {
        player.addListener(new Player.Listener() {
            @Override public void onPlaybackStateChanged(int state) {
                if (state == Player.STATE_READY) {
                    playbackRetryCount = 0;
                    btnPlay.setText(player.isPlaying() ? "暂停" : "播放");
                    uiSetStatus("播放中 · " + formatSpeed(playbackSpeed));
                    startProgressRefresh();
                    scheduleHideControls();
                } else if (state == Player.STATE_BUFFERING) {
                    uiSetStatus(playbackRetryCount > 0
                            ? "正在重新连接 SMB（" + playbackRetryCount + "/" + MAX_PLAYBACK_RETRIES + "）..."
                            : "缓冲中...");
                } else if (state == Player.STATE_ENDED) {
                    onEpisodeComplete();
                }
            }

            @Override public void onIsPlayingChanged(boolean playing) {
                btnPlay.setText(playing ? "暂停" : "播放");
                if (playing) scheduleHideControls(); else uiHandler.removeCallbacks(hideControlsRunnable);
            }

            @Override public void onTracksChanged(Tracks tracks) {
                applyRememberedTrackSelections(tracks);
                updateTrackButtons(tracks);
            }

            @Override public void onPlayerError(PlaybackException error) {
                lastPositionMs = currentPosition();
                saveCurrentProgress(lastPositionMs);
                handlePlaybackError(error);
            }
        });
    }

    private void loadEpisodesAndPlay() {
        uiSetStatus("加载目录中...");
        ioExecutor.execute(() -> {
            try {
                List<EpisodeItem> list = new ArrayList<>();
                List<SmbEpisodeScanner.Item> found = SmbEpisodeScanner.scan(
                        ensureSmbDir(removeSmbUserInfo(directoryPath)), buildContext());
                for (SmbEpisodeScanner.Item item : found) {
                    List<SubtitleItem> subtitles = new ArrayList<>();
                    for (SmbEpisodeScanner.SubtitleItem subtitle : item.subtitles) {
                        subtitles.add(new SubtitleItem(subtitle.path, subtitle.displayName));
                    }
                    list.add(new EpisodeItem(item.path, item.displayName, subtitles));
                }
                if (list.isEmpty()) throw new IllegalStateException("当前目录没有可播放文件");
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
                    toast("无法播放：" + safeMessage(e));
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
        cancelPendingTransitions();
        EpisodeItem item = episodes.get(currentIndex);
        lastAutoSaveAtMs = 0L;
        lastAutoSavePositionMs = -1L;
        playbackRetryCount = 0;
        tvTitle.setText(item.name + "  ·  " + (currentIndex + 1) + "/" + episodes.size());
        uiSetStatus("正在打开：" + item.name);

        MediaItem.Builder mediaItem = new MediaItem.Builder().setUri(Uri.parse(item.path));
        List<MediaItem.SubtitleConfiguration> subtitleConfigurations = new ArrayList<>();
        for (SubtitleItem subtitle : item.subtitles) {
            subtitleConfigurations.add(new MediaItem.SubtitleConfiguration.Builder(Uri.parse(subtitle.path))
                    .setMimeType(subtitleMimeType(subtitle.name))
                    .setLabel(subtitle.name)
                    .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
                    .build());
        }
        mediaItem.setSubtitleConfigurations(subtitleConfigurations);
        DefaultMediaSourceFactory sourceFactory = new DefaultMediaSourceFactory(new SmbDataSource.Factory(buildContext()))
                .setLoadErrorHandlingPolicy(new DefaultLoadErrorHandlingPolicy(MAX_PLAYBACK_RETRIES));
        player.stop();
        player.setMediaSource(sourceFactory.createMediaSource(mediaItem.build()));
        player.prepare();
        player.setPlaybackSpeed(playbackSpeed);
        if (lastPositionMs > 0L) player.seekTo(lastPositionMs);
        player.play();
        saveCurrentProgress(lastPositionMs);
    }

    private void onEpisodeComplete() {
        saveCurrentProgress(player == null ? lastPositionMs : Math.max(0L, player.getDuration()));
        int nextIndex = currentIndex + 1;
        if (nextIndex >= episodes.size()) {
            uiSetStatus("播放结束");
            showControls(false);
            toast("已播放完该目录全部视频");
            return;
        }
        uiSetStatus("5 秒后自动播放下一个");
        pendingAutoNext = () -> {
            if (isDestroying) return;
            currentIndex = nextIndex;
            lastPositionMs = 0L;
            playCurrentEpisode();
        };
        uiHandler.postDelayed(pendingAutoNext, AUTO_NEXT_DELAY_MS);
    }

    private void playNextEpisode() {
        if (currentIndex + 1 >= episodes.size()) { toast("已经是最后一个"); return; }
        cancelPendingTransitions();
        saveCurrentProgress(currentPosition());
        currentIndex++;
        lastPositionMs = 0L;
        playCurrentEpisode();
    }

    private void playPrevEpisode() {
        if (currentIndex <= 0) { toast("已经是第一个"); return; }
        cancelPendingTransitions();
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
            showControls(false);
        } else {
            player.play();
            uiSetStatus("播放中 · " + formatSpeed(playbackSpeed));
            scheduleHideControls();
        }
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        int keyCode = event.getKeyCode();
        boolean down = event.getAction() == KeyEvent.ACTION_DOWN;
        if (down && controlsVisible) scheduleHideControls();

        if (down && (keyCode == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE || keyCode == KeyEvent.KEYCODE_SPACE)) {
            togglePlayPause();
            return true;
        }
        if (down && keyCode == KeyEvent.KEYCODE_MEDIA_NEXT) {
            playNextEpisode();
            return true;
        }
        if (down && keyCode == KeyEvent.KEYCODE_MEDIA_PREVIOUS) {
            playPrevEpisode();
            return true;
        }
        if (down && controlsVisible && keyCode == KeyEvent.KEYCODE_DPAD_UP) {
            hideControls();
            return true;
        }

        boolean videoFocused = getCurrentFocus() == playerView;
        if (down && videoFocused && (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER)) {
            if (!controlsVisible) showControls(false); else togglePlayPause();
            return true;
        }
        if (down && videoFocused && keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
            showControls(true);
            return true;
        }
        if (down && videoFocused && keyCode == KeyEvent.KEYCODE_DPAD_UP) {
            showEpisodePicker();
            return true;
        }
        if (down && videoFocused && (keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT)) {
            int repeat = event.getRepeatCount();
            if (repeat == 0 || repeat % 4 == 0) {
                long acceleratedStep = repeat >= 12 ? 60000L : repeat >= 4 ? 30000L : seekStepMs;
                seekBy(keyCode == KeyEvent.KEYCODE_DPAD_RIGHT ? acceleratedStep : -acceleratedStep);
            }
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    private void showControls(boolean focusButtons) {
        controlsVisible = true;
        playerControls.setVisibility(View.VISIBLE);
        if (focusButtons) btnPlay.requestFocus();
        scheduleHideControls();
    }

    private void hideControls() {
        uiHandler.removeCallbacks(hideControlsRunnable);
        controlsVisible = false;
        playerControls.setVisibility(View.GONE);
        playerView.requestFocus();
    }

    private void scheduleHideControls() {
        uiHandler.removeCallbacks(hideControlsRunnable);
        if (player != null && player.isPlaying()) {
            uiHandler.postDelayed(hideControlsRunnable, CONTROLS_TIMEOUT_MS);
        }
    }

    private void showEpisodePicker() {
        if (episodes.isEmpty()) return;
        uiHandler.removeCallbacks(hideControlsRunnable);
        String[] labels = new String[episodes.size()];
        for (int i = 0; i < episodes.size(); i++) {
            labels[i] = (i == currentIndex ? "正在播放  " : "") + episodes.get(i).name;
        }
        new AlertDialog.Builder(this)
                .setTitle("选择视频")
                .setSingleChoiceItems(labels, currentIndex, (dialog, which) -> {
                    saveCurrentProgress(currentPosition());
                    currentIndex = which;
                    lastPositionMs = 0L;
                    dialog.dismiss();
                    playCurrentEpisode();
                    playerView.requestFocus();
                })
                .setNegativeButton("取消", null)
                .setOnDismissListener(dialog -> scheduleHideControls())
                .show();
    }

    private void showSpeedPicker() {
        String[] labels = new String[SPEEDS.length];
        int selected = 0;
        for (int i = 0; i < SPEEDS.length; i++) {
            labels[i] = formatSpeed(SPEEDS[i]);
            if (Math.abs(SPEEDS[i] - playbackSpeed) < 0.01f) selected = i;
        }
        new AlertDialog.Builder(this)
                .setTitle("播放速度")
                .setSingleChoiceItems(labels, selected, (dialog, which) -> {
                    playbackSpeed = SPEEDS[which];
                    if (player != null) player.setPlaybackSpeed(playbackSpeed);
                    SecurePreferences.get(this).edit().putFloat(KEY_SPEED, playbackSpeed).apply();
                    updatePreferenceButtons();
                    uiSetStatus("播放速度 " + formatSpeed(playbackSpeed));
                    dialog.dismiss();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void showTrackPicker(int trackType) {
        if (player == null) return;
        Tracks tracks = player.getCurrentTracks();
        List<TrackOption> options = collectTrackOptions(tracks, trackType);
        boolean text = trackType == C.TRACK_TYPE_TEXT;
        int offset = text ? 1 : 0;
        if (options.isEmpty()) {
            toast(text ? "当前视频没有可选字幕" : "当前视频没有可选音轨");
            return;
        }
        String[] labels = new String[options.size() + offset];
        int selected = text ? 0 : -1;
        if (text) labels[0] = "关闭字幕";
        for (int i = 0; i < options.size(); i++) {
            TrackOption option = options.get(i);
            labels[i + offset] = option.label;
            if (option.selected) selected = i + offset;
        }
        new AlertDialog.Builder(this)
                .setTitle(text ? "选择字幕" : "选择音轨")
                .setSingleChoiceItems(labels, selected, (dialog, which) -> {
                    if (text && which == 0) {
                        disableTextTracks();
                    } else {
                        applyTrackOption(options.get(which - offset), trackType);
                    }
                    dialog.dismiss();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private List<TrackOption> collectTrackOptions(Tracks tracks, int type) {
        List<TrackOption> options = new ArrayList<>();
        int typeIndex = 0;
        for (Tracks.Group group : tracks.getGroups()) {
            if (group.getType() != type) continue;
            for (int trackIndex = 0; trackIndex < group.length; trackIndex++) {
                if (!group.isTrackSupported(trackIndex)) continue;
                Format format = group.getTrackFormat(trackIndex);
                options.add(new TrackOption(group, trackIndex, typeIndex,
                        trackLabel(format, typeIndex + 1), group.isTrackSelected(trackIndex)));
                typeIndex++;
            }
        }
        return options;
    }

    private void applyTrackOption(TrackOption option, int type) {
        if (player == null) return;
        player.setTrackSelectionParameters(player.getTrackSelectionParameters().buildUpon()
                .setTrackTypeDisabled(type, false)
                .clearOverridesOfType(type)
                .setOverrideForType(new TrackSelectionOverride(option.group.getMediaTrackGroup(), option.trackIndex))
                .build());
        if (type == C.TRACK_TYPE_AUDIO) {
            preferredAudioIndex = option.typeIndex;
            SecurePreferences.get(this).edit().putInt(KEY_AUDIO_INDEX, preferredAudioIndex).apply();
        } else {
            preferredTextIndex = option.typeIndex;
            SecurePreferences.get(this).edit().putInt(KEY_TEXT_INDEX, preferredTextIndex).apply();
        }
    }

    private void disableTextTracks() {
        if (player == null) return;
        preferredTextIndex = -1;
        SecurePreferences.get(this).edit().putInt(KEY_TEXT_INDEX, -1).apply();
        player.setTrackSelectionParameters(player.getTrackSelectionParameters().buildUpon()
                .clearOverridesOfType(C.TRACK_TYPE_TEXT)
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                .build());
        btnSubtitleTrack.setText("字幕 关闭");
    }

    private void applyRememberedTrackSelections(Tracks tracks) {
        if (player == null || applyingRememberedTracks) return;
        applyingRememberedTracks = true;
        try {
            if (preferredAudioIndex >= 0) applyRememberedTrack(tracks, C.TRACK_TYPE_AUDIO, preferredAudioIndex);
            if (preferredTextIndex >= 0) {
                applyRememberedTrack(tracks, C.TRACK_TYPE_TEXT, preferredTextIndex);
            } else {
                player.setTrackSelectionParameters(player.getTrackSelectionParameters().buildUpon()
                        .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).build());
            }
        } finally {
            applyingRememberedTracks = false;
        }
    }

    private void applyRememberedTrack(Tracks tracks, int type, int wantedIndex) {
        int typeIndex = 0;
        for (Tracks.Group group : tracks.getGroups()) {
            if (group.getType() != type) continue;
            for (int trackIndex = 0; trackIndex < group.length; trackIndex++) {
                if (!group.isTrackSupported(trackIndex)) continue;
                if (typeIndex++ == wantedIndex) {
                    player.setTrackSelectionParameters(player.getTrackSelectionParameters().buildUpon()
                            .setTrackTypeDisabled(type, false)
                            .clearOverridesOfType(type)
                            .setOverrideForType(new TrackSelectionOverride(group.getMediaTrackGroup(), trackIndex))
                            .build());
                    return;
                }
            }
        }
    }

    private void updateTrackButtons(Tracks tracks) {
        int audioCount = collectTrackOptions(tracks, C.TRACK_TYPE_AUDIO).size();
        int textCount = collectTrackOptions(tracks, C.TRACK_TYPE_TEXT).size();
        btnAudioTrack.setText("音轨 " + audioCount);
        btnSubtitleTrack.setText(preferredTextIndex < 0 ? "字幕 关闭" : "字幕 " + textCount);
    }

    @Override
    public void onBackPressed() {
        if (controlsVisible) {
            hideControls();
            return;
        }
        super.onBackPressed();
    }

    private void seekBy(long offsetMs) {
        cancelPendingTransitions();
        if (player == null || player.getPlaybackState() == Player.STATE_IDLE) return;
        long duration = player.getDuration();
        long now = System.currentTimeMillis();
        long basePosition = now - lastSeekCommandAtMs <= 1500L && accumulatedSeekPositionMs >= 0L
                ? accumulatedSeekPositionMs
                : currentPosition();
        long target = Math.max(0L, basePosition + offsetMs);
        if (duration > 0L && duration != C.TIME_UNSET) target = Math.min(target, duration);
        accumulatedSeekPositionMs = target;
        lastSeekCommandAtMs = now;
        player.seekTo(target);
        lastPositionMs = target;
        showControls(false);
        if (duration > 0L && duration != C.TIME_UNSET) {
            sbProgress.setMax((int) Math.min(Integer.MAX_VALUE, duration));
        }
        sbProgress.setProgress((int) Math.min(Integer.MAX_VALUE, target));
        tvProgress.setText(formatMs(target));
        uiSetStatus((offsetMs > 0 ? "快进 " : "快退 ") + Math.abs(offsetMs / 1000L) + " 秒");
    }

    private void cycleSeekStep() {
        int next = 0;
        for (int i = 0; i < SEEK_STEPS.length; i++) {
            if (SEEK_STEPS[i] == seekStepMs) next = (i + 1) % SEEK_STEPS.length;
        }
        seekStepMs = SEEK_STEPS[next];
        sbProgress.setKeyProgressIncrement((int) seekStepMs);
        SecurePreferences.get(this).edit().putLong(KEY_SEEK_STEP, seekStepMs).apply();
        updatePreferenceButtons();
    }

    private void handlePlaybackError(PlaybackException error) {
        cancelPendingRetry();
        if (playbackRetryCount < MAX_PLAYBACK_RETRIES) {
            playbackRetryCount++;
            long delay = playbackRetryCount * 1500L;
            uiSetStatus(errorCategory(error) + "，" + (delay / 1000f) + " 秒后重试");
            pendingRetry = () -> {
                if (isDestroying || player == null) return;
                player.prepare();
                player.seekTo(lastPositionMs);
                player.play();
            };
            uiHandler.postDelayed(pendingRetry, delay);
            return;
        }

        uiSetStatus(errorCategory(error) + "，已停止重试");
        showControls(false);
        if (currentIndex + 1 < episodes.size()) {
            toast("当前视频无法播放，3 秒后跳到下一个");
            pendingAutoNext = () -> {
                if (isDestroying) return;
                currentIndex++;
                lastPositionMs = 0L;
                playCurrentEpisode();
            };
            uiHandler.postDelayed(pendingAutoNext, 3000L);
        } else {
            toast("播放失败：" + error.getErrorCodeName());
        }
    }

    private String errorCategory(PlaybackException error) {
        String code = error.getErrorCodeName();
        if (code.contains("NETWORK") || code.contains("TIMEOUT") || code.contains("IO")) return "SMB 网络读取失败";
        if (code.contains("DECOD") || code.contains("FORMAT")) return "设备不支持该音视频格式";
        if (code.contains("PARSING")) return "视频文件损坏或封装不兼容";
        return "播放失败：" + code;
    }

    private void cancelPendingTransitions() {
        if (pendingAutoNext != null) {
            uiHandler.removeCallbacks(pendingAutoNext);
            pendingAutoNext = null;
        }
        cancelPendingRetry();
    }

    private void cancelPendingRetry() {
        if (pendingRetry != null) {
            uiHandler.removeCallbacks(pendingRetry);
            pendingRetry = null;
        }
    }

    private long currentPosition() {
        return player == null ? lastPositionMs : Math.max(0L, player.getCurrentPosition());
    }

    private void refreshProgress() {
        if (player == null || isSeeking || currentIndex < 0 || currentIndex >= episodes.size()) return;
        long rawDuration = player.getDuration();
        long duration = rawDuration == C.TIME_UNSET ? 0L : Math.max(0L, rawDuration);
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

        String finalSafeSource = safeSource;
        String finalSafeDir = safeDir;
        progressExecutor.execute(() -> persistProgress(
                title, finalSafeSource, finalSafeDir, item, positionMs));
    }

    private void persistProgress(String title, String safeSource, String safeDir,
                                 EpisodeItem item, long positionMs) {
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

    private void loadPlayerPreferences() {
        SharedPreferences preferences = SecurePreferences.get(this);
        playbackSpeed = preferences.getFloat(KEY_SPEED, 1f);
        seekStepMs = preferences.getLong(KEY_SEEK_STEP, SEEK_STEPS[0]);
        preferredAudioIndex = preferences.getInt(KEY_AUDIO_INDEX, -1);
        preferredTextIndex = preferences.getInt(KEY_TEXT_INDEX, -1);
    }

    private void updatePreferenceButtons() {
        btnSpeed.setText("倍速 " + formatSpeed(playbackSpeed));
        btnSeekStep.setText("跳转 " + (seekStepMs / 1000L) + "秒");
    }

    private String trackLabel(Format format, int fallbackIndex) {
        StringBuilder label = new StringBuilder();
        if (!TextUtils.isEmpty(format.label)) label.append(format.label);
        if (!TextUtils.isEmpty(format.language)) {
            if (label.length() > 0) label.append(" · ");
            label.append(format.language);
        }
        if (format.channelCount > 0) {
            if (label.length() > 0) label.append(" · ");
            label.append(format.channelCount).append("声道");
        }
        if (!TextUtils.isEmpty(format.sampleMimeType)) {
            if (label.length() > 0) label.append(" · ");
            label.append(format.sampleMimeType.substring(format.sampleMimeType.lastIndexOf('/') + 1));
        }
        return label.length() == 0 ? "轨道 " + fallbackIndex : label.toString();
    }

    private String subtitleMimeType(String name) {
        String lower = valueOrEmpty(name).toLowerCase(Locale.ROOT);
        if (lower.endsWith(".srt")) return MimeTypes.APPLICATION_SUBRIP;
        if (lower.endsWith(".ass") || lower.endsWith(".ssa")) return MimeTypes.TEXT_SSA;
        return MimeTypes.TEXT_VTT;
    }

    private CIFSContext buildContext() {
        if (TextUtils.isEmpty(username) && TextUtils.isEmpty(password)) return SingletonContext.getInstance();
        return SingletonContext.getInstance().withCredentials(new NtlmPasswordAuthenticator("", username, password));
    }

    private String normalizeFilePath(String raw) { return TextUtils.isEmpty(raw) ? "" : removeTrailingSlash(removeSmbUserInfo(raw)); }
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
        long hours = seconds / 3600L;
        long minutes = (seconds % 3600L) / 60L;
        long remainingSeconds = seconds % 60L;
        return hours > 0
                ? String.format(Locale.ROOT, "%02d:%02d:%02d", hours, minutes, remainingSeconds)
                : String.format(Locale.ROOT, "%02d:%02d", minutes, remainingSeconds);
    }
    private String formatSpeed(float speed) {
        return speed == (long) speed
                ? String.format(Locale.ROOT, "%.0fx", speed)
                : String.format(Locale.ROOT, "%.2gx", speed);
    }
    private String safeMessage(Exception error) {
        return TextUtils.isEmpty(error.getMessage()) ? error.getClass().getSimpleName() : error.getMessage();
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
        uiHandler.removeCallbacks(hideControlsRunnable);
    }

    @Override
    protected void onResume() {
        super.onResume();
        enterImmersiveMode();
        startProgressRefresh();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) enterImmersiveMode();
    }

    private void enterImmersiveMode() {
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
    }

    @Override
    protected void onDestroy() {
        isDestroying = true;
        RemoteControlManager.get(this).clearPlaybackBridge(remotePlaybackBridge);
        cancelPendingTransitions();
        stopProgressRefresh();
        uiHandler.removeCallbacks(hideControlsRunnable);
        ioExecutor.shutdownNow();
        progressExecutor.shutdown();
        if (playerView != null) playerView.setPlayer(null);
        if (player != null) player.release();
        super.onDestroy();
    }

    private static final class TrackOption {
        final Tracks.Group group;
        final int trackIndex;
        final int typeIndex;
        final String label;
        final boolean selected;

        TrackOption(Tracks.Group group, int trackIndex, int typeIndex, String label, boolean selected) {
            this.group = group;
            this.trackIndex = trackIndex;
            this.typeIndex = typeIndex;
            this.label = label;
            this.selected = selected;
        }
    }

    private static final class SubtitleItem {
        final String path;
        final String name;
        SubtitleItem(String path, String name) { this.path = path; this.name = name; }
    }

    private static final class EpisodeItem {
        final String path;
        final String name;
        final List<SubtitleItem> subtitles;
        EpisodeItem(String path, String name, List<SubtitleItem> subtitles) {
            this.path = path;
            this.name = name;
            this.subtitles = subtitles;
        }
    }
}
