package com.smbwatch.tv;

/**
 * smb:// URL 的纯字符串工具，不依赖 android.net.Uri，便于 JVM 单元测试。
 */
final class SmbUrls {
    private SmbUrls() { }

    static boolean isEmpty(String value) {
        return value == null || value.isEmpty();
    }

    static String ensureTrailingSlash(String raw) {
        if (isEmpty(raw)) return "";
        return raw.endsWith("/") ? raw : raw + "/";
    }

    static String removeTrailingSlash(String raw) {
        if (isEmpty(raw)) return "";
        return raw.endsWith("/") ? raw.substring(0, raw.length() - 1) : raw;
    }

    static String fileName(String path) {
        if (isEmpty(path)) return "";
        int slash = path.lastIndexOf('/');
        return slash < 0 || slash + 1 >= path.length() ? path : path.substring(slash + 1);
    }

    /** smb://host[:port]/a/b/ 或 smb://host/a/b → b；无路径时返回空串。 */
    static String directoryName(String url) {
        String path = pathOf(url);
        return fileName(removeTrailingSlash(path));
    }

    /** 去掉 smb://user:pass@host/... 中的用户信息，保留 host[:port]/path。 */
    static String stripUserInfo(String url) {
        if (isEmpty(url)) return "";
        String authorityAndPath = afterScheme(url);
        if (authorityAndPath == null) return url;
        int slash = authorityAndPath.indexOf('/');
        String authority = slash < 0 ? authorityAndPath : authorityAndPath.substring(0, slash);
        String path = slash < 0 ? "/" : authorityAndPath.substring(slash);
        int at = authority.lastIndexOf('@');
        if (at >= 0) authority = authority.substring(at + 1);
        if (isEmpty(authority)) return url;
        return "smb://" + authority + path;
    }

    /** 提取 host（不含端口和用户信息）；解析失败返回空串。 */
    static String hostOf(String url) {
        String authorityAndPath = afterScheme(url);
        if (authorityAndPath == null) return "";
        int slash = authorityAndPath.indexOf('/');
        String authority = slash < 0 ? authorityAndPath : authorityAndPath.substring(0, slash);
        int at = authority.lastIndexOf('@');
        if (at >= 0) authority = authority.substring(at + 1);
        int colon = authority.indexOf(':');
        if (colon >= 0) authority = authority.substring(0, colon);
        return authority;
    }

    static String pathOf(String url) {
        String authorityAndPath = afterScheme(url);
        if (authorityAndPath == null) return "";
        int slash = authorityAndPath.indexOf('/');
        return slash < 0 ? "" : authorityAndPath.substring(slash);
    }

    /** 目录路径大小写不敏感比较（都补全末尾斜杠）。 */
    static boolean sameDirectory(String left, String right) {
        String normalizedLeft = ensureTrailingSlash(left == null ? "" : left);
        String normalizedRight = ensureTrailingSlash(right == null ? "" : right);
        return !isEmpty(normalizedLeft) && normalizedLeft.equalsIgnoreCase(normalizedRight);
    }

    /**
     * 规范化 smb URL：补全 scheme、去掉用户信息，保留 host[:port] 和路径。
     * host 为空时抛出 IllegalArgumentException。
     */
    static String normalize(String raw) {
        if (isEmpty(raw)) throw new IllegalArgumentException("非法 SMB 地址");
        String url = raw.startsWith("smb://") ? raw : "smb://" + raw;
        String stripped = stripUserInfo(url);
        if (isEmpty(hostOf(stripped))) throw new IllegalArgumentException("非法 SMB 地址");
        return stripped;
    }

    /** 文件 URL 的父目录（带末尾斜杠）；已经是共享根时返回 smb://host/。 */
    static String parentDirectory(String fileUrl) {
        if (isEmpty(fileUrl)) return "";
        String stripped = stripUserInfo(fileUrl);
        String host = hostOf(stripped);
        if (isEmpty(host)) return "";
        String authority = authorityOf(stripped);
        String path = removeTrailingSlash(pathOf(stripped));
        int lastSlash = path.lastIndexOf('/');
        if (lastSlash <= 0) return "smb://" + authority + "/";
        return "smb://" + authority + path.substring(0, lastSlash + 1);
    }

    private static String authorityOf(String url) {
        String authorityAndPath = afterScheme(url);
        if (authorityAndPath == null) return "";
        int slash = authorityAndPath.indexOf('/');
        return slash < 0 ? authorityAndPath : authorityAndPath.substring(0, slash);
    }

    private static String afterScheme(String url) {
        if (isEmpty(url) || !url.startsWith("smb://")) return null;
        return url.substring("smb://".length());
    }
}
