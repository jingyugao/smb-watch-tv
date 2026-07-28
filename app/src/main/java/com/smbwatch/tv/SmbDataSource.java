package com.smbwatch.tv;

import android.net.Uri;

import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.datasource.BaseDataSource;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DataSpec;

import java.io.EOFException;
import java.io.IOException;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

import jcifs.CIFSContext;
import jcifs.smb.SmbFile;
import jcifs.smb.SmbRandomAccessFile;

/**
 * 带后台预读的 SMB 数据源：独立线程持续从 SMB 顺序读取数据块，
 * 播放器消费当前块时下一块已经在网络上传输，避免读取阻塞在网络往返上。
 */
final class SmbDataSource extends BaseDataSource {
    private static final int CHUNK_SIZE = 2 * 1024 * 1024;
    private static final int QUEUE_CHUNKS = 2;
    private static final int MAX_READ_ATTEMPTS = 3;

    static final class Factory implements DataSource.Factory {
        private final CIFSContext context;
        Factory(CIFSContext context) { this.context = context; }
        @Override public DataSource createDataSource() { return new SmbDataSource(context); }
    }

    private final CIFSContext context;
    private Uri uri;
    private long bytesRemaining;
    private boolean opened;
    private Prefetcher prefetcher;
    private Chunk current;

    private SmbDataSource(CIFSContext context) {
        super(false);
        this.context = context;
    }

    @Override
    public long open(DataSpec dataSpec) throws IOException {
        transferInitializing(dataSpec);
        uri = dataSpec.uri;
        SmbFile file = new SmbFile(uri.toString(), context);
        long fileLength = file.length();
        if (dataSpec.position > fileLength) throw new EOFException("播放位置超过文件长度");
        bytesRemaining = dataSpec.length == C.LENGTH_UNSET
                ? fileLength - dataSpec.position
                : Math.min(dataSpec.length, fileLength - dataSpec.position);
        prefetcher = new Prefetcher(file, dataSpec.position, bytesRemaining);
        prefetcher.start();
        opened = true;
        transferStarted(dataSpec);
        return bytesRemaining;
    }

    @Override
    public int read(byte[] buffer, int offset, int length) throws IOException {
        if (length == 0) return 0;
        if (bytesRemaining == 0L) return C.RESULT_END_OF_INPUT;
        if (current == null || current.remaining() == 0) {
            if (current != null) prefetcher.recycle(current);
            current = prefetcher.take();
            if (current == null) return C.RESULT_END_OF_INPUT;
        }
        int copied = (int) Math.min(Math.min(length, bytesRemaining), current.remaining());
        System.arraycopy(current.data, current.offset, buffer, offset, copied);
        current.offset += copied;
        bytesRemaining -= copied;
        bytesTransferred(copied);
        return copied;
    }

    @Nullable
    @Override
    public Uri getUri() { return uri; }

    @Override
    public void close() {
        uri = null;
        current = null;
        if (prefetcher != null) {
            prefetcher.shutdown();
            prefetcher = null;
        }
        if (opened) {
            opened = false;
            transferEnded();
        }
    }

    private static final class Chunk {
        byte[] data;
        int offset;
        int limit;

        int remaining() { return limit - offset; }
    }

    private static final class Prefetcher implements Runnable {
        /** 队列结束标记：EOF 或失败（失败原因见 failure 字段）。 */
        private static final Chunk END = new Chunk();

        private final SmbFile file;
        private final BlockingQueue<Chunk> filled = new ArrayBlockingQueue<>(QUEUE_CHUNKS + 1);
        private final BlockingQueue<byte[]> free = new ArrayBlockingQueue<>(QUEUE_CHUNKS + 1);
        private final Thread thread;
        private volatile boolean closed;
        private volatile IOException failure;
        private long position;
        private long remaining;
        private SmbRandomAccessFile input;

        Prefetcher(SmbFile file, long position, long remaining) {
            this.file = file;
            this.position = position;
            this.remaining = remaining;
            for (int i = 0; i <= QUEUE_CHUNKS; i++) free.add(new byte[CHUNK_SIZE]);
            thread = new Thread(this, "SmbPrefetch");
            thread.setDaemon(true);
        }

        void start() { thread.start(); }

        /** 取出下一块数据；EOF 返回 null，读取失败抛出原始 IOException。 */
        @Nullable
        Chunk take() throws IOException {
            try {
                while (true) {
                    if (closed) throw new IOException("SMB 读取已取消");
                    Chunk chunk = filled.poll(500, TimeUnit.MILLISECONDS);
                    if (chunk == END) {
                        IOException error = failure;
                        if (error != null) throw error;
                        return null;
                    }
                    if (chunk != null) return chunk;
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("SMB 读取已取消", e);
            }
        }

        void recycle(Chunk chunk) {
            if (chunk.data != null) free.offer(chunk.data);
            chunk.data = null;
        }

        void shutdown() {
            closed = true;
            thread.interrupt();
            filled.clear();
        }

        @Override
        public void run() {
            try {
                input = openAt(position);
                while (remaining > 0 && !closed) {
                    byte[] buffer = free.take();
                    int requested = (int) Math.min(CHUNK_SIZE, remaining);
                    int read = readWithRetry(buffer, requested);
                    if (read <= 0) throw new EOFException("SMB 文件提前结束");
                    position += read;
                    remaining -= read;
                    Chunk chunk = new Chunk();
                    chunk.data = buffer;
                    chunk.limit = read;
                    filled.put(chunk);
                }
            } catch (IOException e) {
                if (!closed) failure = e;
            } catch (InterruptedException ignored) {
                // 收到关闭信号，直接退出
            } catch (RuntimeException e) {
                if (!closed) failure = new IOException("SMB 读取异常", e);
            } finally {
                closeQuietly(input);
                input = null;
                try {
                    filled.put(END);
                } catch (InterruptedException ignored) {
                    // 消费方已经关闭，不再需要结束标记
                }
            }
        }

        private int readWithRetry(byte[] buffer, int requested) throws IOException {
            IOException failure = null;
            for (int attempt = 1; attempt <= MAX_READ_ATTEMPTS; attempt++) {
                if (closed) throw new IOException("SMB 读取已取消");
                try {
                    return input.read(buffer, 0, requested);
                } catch (IOException error) {
                    if (failure == null) failure = error; else failure.addSuppressed(error);
                    if (attempt == MAX_READ_ATTEMPTS) break;
                    try {
                        Thread.sleep(200L * attempt);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new IOException("SMB 读取已取消", interrupted);
                    }
                    closeQuietly(input);
                    input = openAt(position);
                }
            }
            throw failure == null ? new IOException("SMB 读取失败") : failure;
        }

        private SmbRandomAccessFile openAt(long target) throws IOException {
            SmbRandomAccessFile input = new SmbRandomAccessFile(file, "r");
            input.seek(target);
            return input;
        }

        private static void closeQuietly(@Nullable SmbRandomAccessFile input) {
            if (input == null) return;
            try { input.close(); } catch (IOException ignored) { }
        }
    }
}
