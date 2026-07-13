package com.smbwatch.tv;

import android.net.Uri;

import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.datasource.BaseDataSource;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DataSpec;

import java.io.EOFException;
import java.io.IOException;

import jcifs.CIFSContext;
import jcifs.smb.SmbFile;
import jcifs.smb.SmbRandomAccessFile;

final class SmbDataSource extends BaseDataSource {
    private static final int READ_AHEAD_SIZE = 1024 * 1024;
    static final class Factory implements DataSource.Factory {
        private final CIFSContext context;
        Factory(CIFSContext context) { this.context = context; }
        @Override public DataSource createDataSource() { return new SmbDataSource(context); }
    }

    private final CIFSContext context;
    private SmbFile file;
    private SmbRandomAccessFile input;
    private Uri uri;
    private long bytesRemaining;
    private long readPosition;
    private boolean opened;
    private final byte[] readAheadBuffer = new byte[READ_AHEAD_SIZE];
    private int readAheadOffset;
    private int readAheadLimit;

    private SmbDataSource(CIFSContext context) {
        super(false);
        this.context = context;
    }

    @Override
    public long open(DataSpec dataSpec) throws IOException {
        transferInitializing(dataSpec);
        uri = dataSpec.uri;
        file = new SmbFile(uri.toString(), context);
        long fileLength = file.length();
        if (dataSpec.position > fileLength) throw new EOFException("播放位置超过文件长度");
        openAt(dataSpec.position);
        bytesRemaining = dataSpec.length == C.LENGTH_UNSET
                ? fileLength - dataSpec.position
                : Math.min(dataSpec.length, fileLength - dataSpec.position);
        readPosition = dataSpec.position;
        readAheadOffset = 0;
        readAheadLimit = 0;
        opened = true;
        transferStarted(dataSpec);
        return bytesRemaining;
    }

    @Override
    public int read(byte[] buffer, int offset, int length) throws IOException {
        if (length == 0) return 0;
        if (bytesRemaining == 0L) return C.RESULT_END_OF_INPUT;
        if (readAheadOffset >= readAheadLimit && !fillReadAheadBuffer()) return C.RESULT_END_OF_INPUT;
        int copied = (int) Math.min(Math.min(length, bytesRemaining), readAheadLimit - readAheadOffset);
        System.arraycopy(readAheadBuffer, readAheadOffset, buffer, offset, copied);
        readAheadOffset += copied;
        bytesRemaining -= copied;
        bytesTransferred(copied);
        return copied;
    }

    @Nullable
    @Override
    public Uri getUri() { return uri; }

    @Override
    public void close() throws IOException {
        uri = null;
        try {
            if (input != null) input.close();
        } finally {
            input = null;
            file = null;
            readPosition = 0L;
            readAheadOffset = 0;
            readAheadLimit = 0;
            if (opened) {
                opened = false;
                transferEnded();
            }
        }
    }

    private void openAt(long position) throws IOException {
        input = new SmbRandomAccessFile(file, "r");
        input.seek(position);
    }

    private void reopenAtCurrentPosition() throws IOException {
        if (input != null) {
            try { input.close(); } catch (IOException ignored) { }
        }
        if (file == null) throw new IOException("SMB 文件连接已关闭");
        openAt(readPosition);
    }

    private boolean fillReadAheadBuffer() throws IOException {
        int requested = (int) Math.min(readAheadBuffer.length, bytesRemaining);
        if (requested <= 0) return false;
        int read;
        try {
            read = input.read(readAheadBuffer, 0, requested);
        } catch (IOException firstError) {
            try {
                reopenAtCurrentPosition();
                read = input.read(readAheadBuffer, 0, requested);
            } catch (IOException retryError) {
                retryError.addSuppressed(firstError);
                throw retryError;
            }
        }
        if (read <= 0) return false;
        readPosition += read;
        readAheadOffset = 0;
        readAheadLimit = read;
        return true;
    }
}
