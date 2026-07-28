package com.smbwatch.tv;

import android.text.TextUtils;

import java.util.Properties;

import jcifs.CIFSContext;
import jcifs.config.PropertyConfiguration;
import jcifs.context.BaseContext;
import jcifs.smb.NtlmPasswordAuthenticator;

/**
 * 全 App 共享的 jcifs 上下文。相比 SingletonContext 默认值：
 * 启用 SMB 3.1.1 协商，并调大单次读写请求上限，减少大码率视频的往返次数。
 */
final class SmbContexts {
    private static volatile CIFSContext base;

    private SmbContexts() { }

    static CIFSContext get() {
        if (base == null) {
            synchronized (SmbContexts.class) {
                if (base == null) base = create();
            }
        }
        return base;
    }

    static CIFSContext withCredentials(String username, String password) {
        if (TextUtils.isEmpty(username) && TextUtils.isEmpty(password)) return get();
        return get().withCredentials(
                new NtlmPasswordAuthenticator("", username, password == null ? "" : password));
    }

    private static CIFSContext create() {
        Properties properties = new Properties();
        properties.setProperty("jcifs.smb.client.minVersion", "SMB202");
        properties.setProperty("jcifs.smb.client.maxVersion", "SMB311");
        properties.setProperty("jcifs.smb.client.rcv_buf_size", String.valueOf(1024 * 1024));
        properties.setProperty("jcifs.smb.client.snd_buf_size", String.valueOf(1024 * 1024));
        properties.setProperty("jcifs.smb.client.transactionBufferSize", String.valueOf(1024 * 1024));
        properties.setProperty("jcifs.smb.client.responseTimeout", "20000");
        properties.setProperty("jcifs.smb.client.soTimeout", "25000");
        // 匿名访问时优先猜测匿名而不是弹认证
        properties.setProperty("jcifs.smb.client.ipcSigningEnforced", "false");
        try {
            return new BaseContext(new PropertyConfiguration(properties));
        } catch (jcifs.CIFSException e) {
            throw new IllegalStateException("SMB 客户端初始化失败", e);
        }
    }
}
