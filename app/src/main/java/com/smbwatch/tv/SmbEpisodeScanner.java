package com.smbwatch.tv;

import android.text.TextUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

import jcifs.CIFSContext;
import jcifs.smb.SmbFile;

final class SmbEpisodeScanner {
    static final class SubtitleItem {
        final String path;
        final String displayName;

        SubtitleItem(String path, String displayName) {
            this.path = path;
            this.displayName = displayName;
        }
    }

    static final class Item {
        final String path;
        final String displayName;
        final List<SubtitleItem> subtitles;

        Item(String path, String displayName, List<SubtitleItem> subtitles) {
            this.path = path;
            this.displayName = displayName;
            this.subtitles = subtitles;
        }
    }

    private static final class FileItem {
        final String path;
        final String name;

        FileItem(String path, String name) {
            this.path = path;
            this.name = name;
        }
    }

    private SmbEpisodeScanner() { }

    static List<Item> scan(String directoryUrl, CIFSContext context) throws Exception {
        SmbFile directory = new SmbFile(ensureDirectory(directoryUrl), context);
        if (!directory.exists() || !directory.isDirectory()) {
            throw new IllegalStateException("目录不存在或无权访问");
        }
        SmbFile[] children = directory.listFiles();
        if (children == null) throw new IllegalStateException("目录为空或无权访问");

        List<FileItem> videoFiles = new ArrayList<>();
        List<FileItem> subtitleFiles = new ArrayList<>();
        for (SmbFile child : children) {
            if (Thread.currentThread().isInterrupted()) throw new InterruptedException("扫描已取消");
            if (child == null) continue;
            try {
                if (child.isDirectory()) continue;
                String name = trimSlash(child.getName());
                if (isVideo(name)) {
                    videoFiles.add(new FileItem(child.getPath(), name));
                } else if (isSubtitle(name)) {
                    subtitleFiles.add(new FileItem(child.getPath(), name));
                }
            } catch (Exception ignored) {
                // Skip entries that disappear while listing the directory.
            }
        }
        Collections.sort(videoFiles, (left, right) -> NaturalOrder.compare(left.name, right.name));
        Collections.sort(subtitleFiles, (left, right) -> NaturalOrder.compare(left.name, right.name));

        List<Item> videos = new ArrayList<>();
        for (FileItem video : videoFiles) {
            List<SubtitleItem> matched = new ArrayList<>();
            String videoBase = baseName(video.name).toLowerCase(Locale.ROOT);
            for (FileItem subtitle : subtitleFiles) {
                String subtitleBase = baseName(subtitle.name).toLowerCase(Locale.ROOT);
                boolean sameVideo = subtitleBase.equals(videoBase)
                        || subtitleBase.startsWith(videoBase + ".")
                        || subtitleBase.startsWith(videoBase + "-")
                        || subtitleBase.startsWith(videoBase + "_");
                if (sameVideo || videoFiles.size() == 1) {
                    matched.add(new SubtitleItem(subtitle.path, subtitle.name));
                }
            }
            videos.add(new Item(video.path, video.name, matched));
        }
        return videos;
    }

    static boolean isVideo(String name) {
        String lower = SmbUrls.isEmpty(name) ? "" : name.toLowerCase(Locale.ROOT);
        return lower.endsWith(".mp4") || lower.endsWith(".mkv") || lower.endsWith(".mov")
                || lower.endsWith(".flv") || lower.endsWith(".avi") || lower.endsWith(".ts")
                || lower.endsWith(".m4v") || lower.endsWith(".webm");
    }

    static boolean isSubtitle(String name) {
        String lower = SmbUrls.isEmpty(name) ? "" : name.toLowerCase(Locale.ROOT);
        return lower.endsWith(".srt") || lower.endsWith(".ass") || lower.endsWith(".ssa")
                || lower.endsWith(".vtt");
    }

    private static String baseName(String name) {
        if (SmbUrls.isEmpty(name)) return "";
        int dot = name.lastIndexOf('.');
        return dot <= 0 ? name : name.substring(0, dot);
    }

    private static String trimSlash(String value) {
        return value != null && value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }

    private static String ensureDirectory(String value) {
        return TextUtils.isEmpty(value) || value.endsWith("/") ? value : value + "/";
    }
}
