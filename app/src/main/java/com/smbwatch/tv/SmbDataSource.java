package com.smbwatch.tv;

import android.net.Uri;

import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.datasource.BaseDataSource;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DataSpec;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;

import jcifs.CIFSContext;
import jcifs.smb.SmbFile;

final class SmbDataSource extends BaseDataSource {
    static final class Factory implements DataSource.Factory {
        private final CIFSContext context;
        Factory(CIFSContext context) { this.context = context; }
        @Override public DataSource createDataSource() { return new SmbDataSource(context); }
    }

    private final CIFSContext context;
    private InputStream input;
    private Uri uri;
    private long bytesRemaining;
    private boolean opened;

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
        input = file.getInputStream();
        skipFully(input, dataSpec.position);
        bytesRemaining = dataSpec.length == C.LENGTH_UNSET
                ? fileLength - dataSpec.position
                : Math.min(dataSpec.length, fileLength - dataSpec.position);
        opened = true;
        transferStarted(dataSpec);
        return bytesRemaining;
    }

    @Override
    public int read(byte[] buffer, int offset, int length) throws IOException {
        if (length == 0) return 0;
        if (bytesRemaining == 0L) return C.RESULT_END_OF_INPUT;
        int requested = (int) Math.min(length, bytesRemaining);
        int read = input.read(buffer, offset, requested);
        if (read < 0) return C.RESULT_END_OF_INPUT;
        bytesRemaining -= read;
        bytesTransferred(read);
        return read;
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
            if (opened) {
                opened = false;
                transferEnded();
            }
        }
    }

    private static void skipFully(InputStream input, long bytes) throws IOException {
        long remaining = bytes;
        while (remaining > 0L) {
            long skipped = input.skip(remaining);
            if (skipped > 0L) {
                remaining -= skipped;
            } else if (input.read() < 0) {
                throw new EOFException("无法定位到播放位置");
            } else {
                remaining--;
            }
        }
    }
}
