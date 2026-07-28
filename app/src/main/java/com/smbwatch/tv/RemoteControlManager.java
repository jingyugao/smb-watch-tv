package com.smbwatch.tv;

import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.MultiFormatWriter;
import com.google.zxing.common.BitMatrix;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import jcifs.CIFSContext;
import jcifs.smb.SmbFile;

final class RemoteControlManager {
    interface PlaybackBridge {
        JSONObject state();
        void action(String action, JSONObject data);
    }
    interface LibraryChangeListener { void onLibraryChanged(); }

    private static volatile RemoteControlManager instance;
    private static final String CONNECTIONS = "connections";
    private static final String PLAYLISTS = "playlists";
    private final Context context;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService clients = Executors.newCachedThreadPool();
    private final String token;
    private final String pairingId;
    private final byte[] encryptionKey;
    private volatile PlaybackBridge playback;
    private volatile LibraryChangeListener libraryListener;
    private volatile ServerSocket server;
    private volatile int port;

    private RemoteControlManager(Context context) {
        this.context = context.getApplicationContext();
        byte[] secret = new byte[24];
        new SecureRandom().nextBytes(secret);
        token = base64Url(secret);
        encryptionKey = sha256(token.getBytes(StandardCharsets.UTF_8));
        pairingId = hex(encryptionKey);
    }

    static RemoteControlManager get(Context context) {
        if (instance == null) synchronized (RemoteControlManager.class) {
            if (instance == null) instance = new RemoteControlManager(context);
        }
        return instance;
    }

    synchronized void start() throws Exception {
        if (server != null && !server.isClosed()) return;
        for (int candidate = 8787; candidate <= 8797; candidate++) {
            try { server = new ServerSocket(candidate); port = candidate; break; }
            catch (Exception ignored) { }
        }
        if (server == null) throw new IllegalStateException("端口 8787-8797 均被占用");
        clients.execute(() -> {
            while (!server.isClosed()) {
                try {
                    Socket socket = server.accept();
                    if (socket.getInetAddress().isSiteLocalAddress() || socket.getInetAddress().isLoopbackAddress())
                        clients.execute(() -> handle(socket));
                    else socket.close();
                } catch (Exception ignored) { }
            }
        });
    }

    String getPairingUrl() throws Exception { return "http://" + localIp() + ":" + port + "/#" + token; }

    Bitmap createQrCode(int size) throws Exception {
        BitMatrix matrix = new MultiFormatWriter().encode(getPairingUrl(), BarcodeFormat.QR_CODE, size, size);
        Bitmap bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.RGB_565);
        for (int y = 0; y < size; y++) for (int x = 0; x < size; x++)
            bitmap.setPixel(x, y, matrix.get(x, y) ? Color.BLACK : Color.WHITE);
        return bitmap;
    }

    void setPlaybackBridge(PlaybackBridge bridge) { playback = bridge; }
    void clearPlaybackBridge(PlaybackBridge bridge) { if (playback == bridge) playback = null; }
    void setLibraryChangeListener(LibraryChangeListener listener) { libraryListener = listener; }

    private void notifyLibraryChanged() {
        LibraryChangeListener listener = libraryListener;
        if (listener != null) main.post(listener::onLibraryChanged);
    }

    private void handle(Socket socket) {
        try (Socket closeable = socket) {
            Request request = Request.read(closeable.getInputStream());
            if (request == null) return;
            if ("/".equals(request.path)) { respond(closeable, 200, "text/html; charset=utf-8", asset("remote/index.html")); return; }
            if (!request.path.startsWith("/api/") || !pairingId.equals(request.headers.get("x-pairing-id"))) {
                respond(closeable, 403, "application/json", jsonError("未配对")); return;
            }
            JSONObject body = request.body.length == 0 ? new JSONObject() : decryptBody(request.body);
            JSONObject result = route(request.method, request.path, body);
            respond(closeable, 200, "application/json; charset=utf-8", result.toString().getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            try { respond(socket, 500, "application/json; charset=utf-8", jsonError(safe(e))); } catch (Exception ignored) { }
        }
    }

    private JSONObject route(String method, String path, JSONObject body) throws Exception {
        if ("GET".equals(method) && "/api/state".equals(path)) {
            JSONObject result = libraryJson();
            PlaybackBridge bridge = playback;
            result.put("player", bridge == null ? new JSONObject().put("active", false) : onMain(bridge::state));
            return result;
        }
        if ("POST".equals(method) && "/api/action".equals(path)) {
            PlaybackBridge bridge = playback;
            if (bridge != null) main.post(() -> bridge.action(body.optString("action"), body));
            return ok();
        }
        if ("POST".equals(method) && "/api/play".equals(path)) return play(body.optString("directory"));
        if ("POST".equals(method) && "/api/smb/test".equals(path)) { connectionFrom(body, true); return ok(); }
        if ("POST".equals(method) && "/api/smb/add".equals(path)) return addConnection(body);
        if ("POST".equals(method) && "/api/smb/delete".equals(path)) return deleteConnection(body.optString("url"));
        if ("POST".equals(method) && "/api/smb/scan".equals(path)) return scanSavedConnection(body.optString("url"));
        if ("POST".equals(method) && "/api/clean".equals(path)) return cleanInvalid();
        throw new IllegalArgumentException("未知接口");
    }

    private synchronized JSONObject libraryJson() throws Exception {
        JSONArray connections = loadArray(CONNECTIONS);
        JSONArray safeConnections = new JSONArray();
        for (int i = 0; i < connections.length(); i++) {
            JSONObject source = connections.optJSONObject(i); if (source == null) continue;
            safeConnections.put(new JSONObject().put("name", source.optString("name"))
                    .put("url", source.optString("url")).put("username", source.optString("username")));
        }
        JSONArray playlists = loadArray(PLAYLISTS);
        JSONArray safePlaylists = new JSONArray();
        for (int i = 0; i < playlists.length(); i++) {
            JSONObject source = playlists.optJSONObject(i); if (source == null) continue;
            safePlaylists.put(new JSONObject().put("title", source.optString("title"))
                    .put("directory", source.optString("dirPath"))
                    .put("last", source.optString("lastEpisodeName"))
                    .put("position", source.optLong("lastEpisodePositionMs")));
        }
        return new JSONObject().put("connections", safeConnections).put("playlists", safePlaylists);
    }

    private JSONObject addConnection(JSONObject body) throws Exception {
        JSONObject connection = connectionFrom(body, true);
        JSONArray values = loadArray(CONNECTIONS); JSONArray merged = new JSONArray();
        for (int i = 0; i < values.length(); i++) {
            JSONObject item = values.optJSONObject(i);
            if (item != null && !item.optString("url").equalsIgnoreCase(connection.optString("url"))) merged.put(item);
        }
        merged.put(connection); saveArray(CONNECTIONS, merged);
        int count = scanConnection(connection);
        notifyLibraryChanged();
        return ok().put("count", count);
    }

    private JSONObject connectionFrom(JSONObject body, boolean test) throws Exception {
        String host = body.optString("host").trim();
        String share = body.optString("share").trim();
        if (host.startsWith("smb://")) host = host.substring(6);
        host = host.replaceAll("/+$", ""); share = share.replaceAll("^/+|/+$", "");
        if (TextUtils.isEmpty(host) || TextUtils.isEmpty(share)) throw new IllegalArgumentException("IP和共享名不能为空");
        String url = "smb://" + host + "/" + share + "/";
        String user = body.optString("username"); String password = body.optString("password");
        JSONObject result = new JSONObject().put("name", host + "/" + share).put("url", url)
                .put("username", user).put("password", password);
        if (test) {
            SmbFile file = new SmbFile(url, smbContext(user, password));
            if (!file.exists() || !file.isDirectory()) throw new IllegalStateException("共享目录不存在或无权访问");
        }
        return result;
    }

    private JSONObject scanSavedConnection(String url) throws Exception {
        JSONObject connection = findConnection(url); if (connection == null) throw new IllegalArgumentException("连接不存在");
        int count = scanConnection(connection); notifyLibraryChanged(); return ok().put("count", count);
    }

    private int scanConnection(JSONObject connection) throws Exception {
        String url = connection.getString("url"); String user = connection.optString("username");
        String pass = connection.optString("password");
        List<SmbPlaylistScanner.Candidate> found = SmbPlaylistScanner.scan(url, smbContext(user, pass));
        JSONArray old = loadArray(PLAYLISTS); List<JSONObject> values = new ArrayList<>();
        for (int i = 0; i < old.length(); i++) if (old.optJSONObject(i) != null) values.add(old.optJSONObject(i));
        for (SmbPlaylistScanner.Candidate candidate : found) {
            JSONObject previous = null;
            for (int i = values.size() - 1; i >= 0; i--) if (sameDir(values.get(i).optString("dirPath"), candidate.directoryPath))
                previous = values.remove(i);
            JSONObject item = previous == null ? new JSONObject() : previous;
            item.put("title", candidate.title).put("connectionName", connection.optString("name"))
                    .put("sourceUrl", url).put("dirPath", slash(candidate.directoryPath))
                    .put("username", user).put("password", pass);
            if (!item.has("createdAt")) item.put("createdAt", System.currentTimeMillis());
            values.add(item);
        }
        JSONArray result = new JSONArray(); for (JSONObject item : values) result.put(item); saveArray(PLAYLISTS, result);
        return found.size();
    }

    private JSONObject deleteConnection(String url) throws Exception {
        JSONArray old = loadArray(CONNECTIONS), result = new JSONArray();
        for (int i = 0; i < old.length(); i++) { JSONObject item = old.optJSONObject(i);
            if (item != null && !item.optString("url").equalsIgnoreCase(url)) result.put(item); }
        saveArray(CONNECTIONS, result); notifyLibraryChanged(); return ok();
    }

    private JSONObject cleanInvalid() throws Exception {
        JSONArray old = loadArray(PLAYLISTS), result = new JSONArray(); int removed = 0, unknown = 0;
        for (int i = 0; i < old.length(); i++) {
            JSONObject item = old.optJSONObject(i); if (item == null) continue;
            try {
                SmbFile dir = new SmbFile(slash(item.optString("dirPath")), smbContext(item.optString("username"), item.optString("password")));
                if (dir.exists() && dir.isDirectory()) result.put(item); else removed++;
            } catch (Exception e) { result.put(item); unknown++; }
        }
        saveArray(PLAYLISTS, result); notifyLibraryChanged(); return ok().put("removed", removed).put("unknown", unknown);
    }

    private JSONObject play(String directory) throws Exception {
        JSONArray values = loadArray(PLAYLISTS); JSONObject selected = null;
        for (int i = 0; i < values.length(); i++) { JSONObject item = values.optJSONObject(i);
            if (item != null && sameDir(item.optString("dirPath"), directory)) { selected = item; break; } }
        if (selected == null) throw new IllegalArgumentException("播放列表不存在");
        Intent intent = new Intent(context, PlayerActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra(PlayerActivity.EXTRA_CONN_NAME, selected.optString("connectionName"))
                .putExtra(PlayerActivity.EXTRA_SOURCE_URL, selected.optString("sourceUrl"))
                .putExtra(PlayerActivity.EXTRA_SMB_DIRECTORY, selected.optString("dirPath"))
                .putExtra(PlayerActivity.EXTRA_USERNAME, selected.optString("username"))
                .putExtra(PlayerActivity.EXTRA_PASSWORD, selected.optString("password"))
                .putExtra(PlayerActivity.EXTRA_RESUME_FILE_PATH, selected.optString("lastEpisodePath"))
                .putExtra(PlayerActivity.EXTRA_RESUME_POSITION_MS, selected.optLong("lastEpisodePositionMs"));
        main.post(() -> context.startActivity(intent)); return ok();
    }

    private JSONObject findConnection(String url) throws Exception { JSONArray a = loadArray(CONNECTIONS);
        for (int i=0;i<a.length();i++){JSONObject o=a.optJSONObject(i);if(o!=null&&o.optString("url").equalsIgnoreCase(url))return o;} return null; }
    private JSONArray loadArray(String key) { try { return new JSONArray(SecurePreferences.get(context).getString(key, "")); } catch(Exception e){return new JSONArray();} }
    private void saveArray(String key, JSONArray value) { SecurePreferences.get(context).edit().putString(key, value.toString()).commit(); }
    private CIFSContext smbContext(String user,String pass){return SmbContexts.withCredentials(user,pass);}
    private static boolean sameDir(String a,String b){return slash(a).equalsIgnoreCase(slash(b));}
    private static String slash(String s){return TextUtils.isEmpty(s)?"":s.endsWith("/")?s:s+"/";}
    private static JSONObject ok() throws Exception{return new JSONObject().put("ok",true);}

    private JSONObject decryptBody(byte[] raw) throws Exception {
        JSONObject wrapper = new JSONObject(new String(raw, StandardCharsets.UTF_8));
        byte[] iv = android.util.Base64.decode(wrapper.getString("iv"), android.util.Base64.NO_WRAP);
        byte[] encrypted = android.util.Base64.decode(wrapper.getString("data"), android.util.Base64.NO_WRAP);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(encryptionKey, "AES"), new GCMParameterSpec(128, iv));
        return new JSONObject(new String(cipher.doFinal(encrypted), StandardCharsets.UTF_8));
    }

    private <T> T onMain(Callable<T> callable) throws Exception {
        if (Looper.myLooper() == Looper.getMainLooper()) return callable.call();
        Object[] value = new Object[1]; Exception[] error = new Exception[1]; CountDownLatch latch = new CountDownLatch(1);
        main.post(() -> { try { value[0]=callable.call(); } catch(Exception e){error[0]=e;} finally{latch.countDown();} });
        if (!latch.await(3, TimeUnit.SECONDS)) throw new IllegalStateException("主线程响应超时");
        if (error[0]!=null) throw error[0]; return (T)value[0];
    }

    private byte[] asset(String name) throws Exception { try(InputStream in=context.getAssets().open(name);ByteArrayOutputStream out=new ByteArrayOutputStream()){byte[]b=new byte[8192];int n;while((n=in.read(b))>0)out.write(b,0,n);return out.toByteArray();} }
    private String localIp() throws Exception { Enumeration<NetworkInterface> all=NetworkInterface.getNetworkInterfaces();
        for(NetworkInterface n:Collections.list(all))for(InetAddress a:Collections.list(n.getInetAddresses()))if(a instanceof Inet4Address&&!a.isLoopbackAddress()&&a.isSiteLocalAddress())return a.getHostAddress(); throw new IllegalStateException("没有可用的局域网 IPv4 地址"); }
    private static byte[] jsonError(String text){return ("{\"ok\":false,\"error\":"+JSONObject.quote(text)+"}").getBytes(StandardCharsets.UTF_8);}
    private static String safe(Exception e){return TextUtils.isEmpty(e.getMessage())?e.getClass().getSimpleName():e.getMessage();}
    private static byte[] sha256(byte[] v){try{return MessageDigest.getInstance("SHA-256").digest(v);}catch(Exception e){throw new IllegalStateException(e);}}
    private static String hex(byte[]v){StringBuilder s=new StringBuilder();for(byte b:v)s.append(String.format(Locale.ROOT,"%02x",b));return s.toString();}
    private static String base64Url(byte[]v){return android.util.Base64.encodeToString(v,android.util.Base64.URL_SAFE|android.util.Base64.NO_WRAP|android.util.Base64.NO_PADDING);}
    private static void respond(Socket s,int code,String type,byte[]body)throws Exception{OutputStream o=s.getOutputStream();String h="HTTP/1.1 "+code+" "+(code==200?"OK":"Error")+"\r\nContent-Type: "+type+"\r\nContent-Length: "+body.length+"\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n";o.write(h.getBytes(StandardCharsets.US_ASCII));o.write(body);o.flush();}

    private static final class Request {
        final String method,path; final java.util.Map<String,String> headers; final byte[]body;
        Request(String m,String p,java.util.Map<String,String>h,byte[]b){method=m;path=p;headers=h;body=b;}
        static Request read(InputStream raw)throws Exception{BufferedInputStream in=new BufferedInputStream(raw);ByteArrayOutputStream hb=new ByteArrayOutputStream();int state=0,c;
            while((c=in.read())>=0){hb.write(c);state=(state==0&&c=='\r')?1:(state==1&&c=='\n')?2:(state==2&&c=='\r')?3:(state==3&&c=='\n')?4:0;if(state==4)break;if(hb.size()>32768)throw new IllegalArgumentException("请求头过大");}if(hb.size()==0)return null;
            String[]lines=hb.toString(StandardCharsets.US_ASCII.name()).split("\r\n");String[]first=lines[0].split(" ");java.util.Map<String,String>headers=new java.util.HashMap<>();for(int i=1;i<lines.length;i++){int x=lines[i].indexOf(':');if(x>0)headers.put(lines[i].substring(0,x).trim().toLowerCase(Locale.ROOT),lines[i].substring(x+1).trim());}
            int length=Integer.parseInt(headers.getOrDefault("content-length","0"));byte[]body=new byte[length];int offset=0;while(offset<length){int n=in.read(body,offset,length-offset);if(n<0)throw new IllegalArgumentException("请求体不完整");offset+=n;}String path=first[1].split("\\?",2)[0];return new Request(first[0],URLDecoder.decode(path,"UTF-8"),headers,body);}
    }
}
