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
        opened = true;
        transferStarted(dataSpec);
        return bytesRemaining;
    }

    @Override
    public int read(byte[] buffer, int offset, int length) throws IOException {
        if (length == 0) return 0;
        if (bytesRemaining == 0L) return C.RESULT_END_OF_INPUT;
        int requested = (int) Math.min(length, bytesRemaining);
        int read;
        try {
            read = input.read(buffer, offset, requested);
        } catch (IOException firstError) {
            try {
                reopenAtCurrentPosition();
                read = input.read(buffer, offset, requested);
            } catch (IOException retryError) {
                retryError.addSuppressed(firstError);
                throw retryError;
            }
        }
        if (read < 0) return C.RESULT_END_OF_INPUT;
        bytesRemaining -= read;
        readPosition += read;
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
            file = null;
            readPosition = 0L;
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
}
