package com.smbwatch.tv;

import android.net.Uri;
import android.text.TextUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import jcifs.CIFSContext;
import jcifs.smb.SmbFile;

final class SmbPlaylistScanner {
    private static final int MAX_DEPTH = 12;
    private static final int MAX_DIRECTORIES = 2000;
    // SMB 目录枚举以网络往返为主，多线程并发能成倍缩短整体扫描时间
    private static final int SCAN_THREADS = 4;

    static final class Candidate {
        final String title;
        final String directoryPath;
        final int videoCount;

        Candidate(String title, String directoryPath, int videoCount) {
            this.title = title;
            this.directoryPath = directoryPath;
            this.videoCount = videoCount;
        }
    }

    private static final class Pending {
        final SmbFile directory;
        final int depth;

        Pending(SmbFile directory, int depth) {
            this.directory = directory;
            this.depth = depth;
        }
    }

    private SmbPlaylistScanner() { }

    static List<Candidate> scan(String rootUrl, CIFSContext context) throws Exception {
        SmbFile root = new SmbFile(ensureDirectory(rootUrl), context);
        if (!root.exists() || !root.isDirectory()) {
            throw new IllegalStateException("目录不存在或无权访问");
        }

        List<Candidate> candidates = Collections.synchronizedList(new ArrayList<>());
        Set<String> visited = ConcurrentHashMap.newKeySet();
        List<Pending> level = new ArrayList<>();
        level.add(new Pending(root, 0));

        ExecutorService pool = Executors.newFixedThreadPool(SCAN_THREADS);
        try {
            while (!level.isEmpty() && visited.size() < MAX_DIRECTORIES) {
                if (Thread.currentThread().isInterrupted()) throw new InterruptedException("扫描已取消");
                List<Callable<List<Pending>>> tasks = new ArrayList<>(level.size());
                for (Pending pending : level) {
                    tasks.add(() -> scanDirectory(pending, candidates, visited));
                }
                List<Pending> next = new ArrayList<>();
                for (Future<List<Pending>> future : pool.invokeAll(tasks)) {
                    next.addAll(future.get());
                }
                level = next;
            }
        } finally {
            pool.shutdownNow();
        }

        List<Candidate> result = new ArrayList<>(candidates);
        Collections.sort(result, (left, right) -> {
            int title = NaturalOrder.compare(left.title, right.title);
            return title != 0 ? title : left.directoryPath.compareToIgnoreCase(right.directoryPath);
        });
        return result;
    }

    private static List<Pending> scanDirectory(Pending current, List<Candidate> candidates, Set<String> visited) {
        if (visited.size() >= MAX_DIRECTORIES) return Collections.emptyList();
        String path = ensureDirectory(current.directory.getPath());
        if (!visited.add(path)) return Collections.emptyList();

        SmbFile[] children;
        try {
            children = current.directory.listFiles();
        } catch (Exception ignored) {
            return Collections.emptyList();
        }
        if (children == null) return Collections.emptyList();

        int directVideoCount = 0;
        List<SmbFile> subdirectories = new ArrayList<>();
        for (SmbFile child : children) {
            if (child == null) continue;
            String name = trimSlash(child.getName());
            if (TextUtils.isEmpty(name)) continue;
            try {
                if (child.isDirectory()) {
                    if (current.depth < MAX_DEPTH && !shouldSkip(name)) subdirectories.add(child);
                } else if (SmbEpisodeScanner.isVideo(name)) {
                    directVideoCount++;
                }
            } catch (Exception ignored) {
            }
        }

        if (directVideoCount > 0) {
            candidates.add(new Candidate(directoryName(path), path, directVideoCount));
        }
        Collections.sort(subdirectories, (left, right) -> NaturalOrder.compare(left.getName(), right.getName()));
        List<Pending> next = new ArrayList<>(subdirectories.size());
        for (SmbFile subdirectory : subdirectories) next.add(new Pending(subdirectory, current.depth + 1));
        return next;
    }

    private static boolean shouldSkip(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        return lower.startsWith(".") || "@eadir".equals(lower) || "$recycle.bin".equals(lower)
                || "system volume information".equals(lower);
    }

    private static String directoryName(String path) {
        Uri uri = Uri.parse(path);
        String value = uri == null ? path : uri.getPath();
        value = trimSlash(value);
        int slash = value == null ? -1 : value.lastIndexOf('/');
        return slash < 0 ? value : value.substring(slash + 1);
    }

    private static String trimSlash(String value) {
        return value != null && value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }

    private static String ensureDirectory(String value) {
        return TextUtils.isEmpty(value) || value.endsWith("/") ? value : value + "/";
    }
}
