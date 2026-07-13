package com.smbwatch.tv;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.text.TextUtils;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.MulticastSocket;
import java.net.NetworkInterface;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class SmbDiscovery implements AutoCloseable {
    interface Callback {
        void onDeviceFound(Device device);
        void onFinished(String errorMessage);
    }

    static final class Device {
        final String host;
        final String source;

        Device(String host, String source) {
            this.host = host;
            this.source = source;
        }
    }

    private static final int SMB_PORT = 445;
    private static final int CONNECT_TIMEOUT_MS = 350;
    private static final int WSD_TIMEOUT_MS = 2500;
    private static final int MAX_SUBNETS = 3;
    private static final Pattern XADDR_HOST = Pattern.compile(
            "https?://(?:\\[([^]]+)\\]|([^/:\\s<]+))", Pattern.CASE_INSENSITIVE);

    private final Context context;
    private final Callback callback;
    private final ExecutorService coordinator = Executors.newSingleThreadExecutor();
    private final ExecutorService workers = Executors.newFixedThreadPool(32);
    private final Set<String> emittedHosts = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private WifiManager.MulticastLock multicastLock;

    SmbDiscovery(Context context, Callback callback) {
        this.context = context.getApplicationContext();
        this.callback = callback;
    }

    void start() {
        coordinator.execute(() -> {
            String error = "";
            acquireMulticastLock();
            try {
                List<String> subnetPrefixes = findPrivateIpv4Prefixes();
                Future<?> wsDiscovery = workers.submit(this::runWsDiscovery);
                List<Future<?>> probes = new ArrayList<>();
                for (String prefix : subnetPrefixes) {
                    for (int host = 1; host <= 254; host++) {
                        String address = prefix + host;
                        probes.add(workers.submit(() -> probeSmb(address, "TCP 445")));
                    }
                }
                for (Future<?> probe : probes) {
                    if (closed.get()) break;
                    try {
                        probe.get(2, TimeUnit.SECONDS);
                    } catch (Exception ignored) {
                        probe.cancel(true);
                    }
                }
                try {
                    wsDiscovery.get(4, TimeUnit.SECONDS);
                } catch (Exception ignored) {
                    wsDiscovery.cancel(true);
                }
                if (subnetPrefixes.isEmpty() && emittedHosts.isEmpty()) {
                    error = "没有可扫描的私有 IPv4 网络";
                }
            } catch (Exception e) {
                error = TextUtils.isEmpty(e.getMessage()) ? "局域网扫描异常" : e.getMessage();
            } finally {
                releaseMulticastLock();
                if (!closed.get()) callback.onFinished(error);
            }
        });
    }

    private void runWsDiscovery() {
        if (closed.get()) return;
        String messageId = "uuid:" + UUID.randomUUID();
        String envelope = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<s:Envelope xmlns:s=\"http://www.w3.org/2003/05/soap-envelope\" "
                + "xmlns:a=\"http://schemas.xmlsoap.org/ws/2004/08/addressing\" "
                + "xmlns:d=\"http://schemas.xmlsoap.org/ws/2005/04/discovery\">"
                + "<s:Header><a:Action>http://schemas.xmlsoap.org/ws/2005/04/discovery/Probe</a:Action>"
                + "<a:MessageID>" + messageId + "</a:MessageID>"
                + "<a:To>urn:schemas-xmlsoap-org:ws:2005:04:discovery</a:To></s:Header>"
                + "<s:Body><d:Probe/></s:Body></s:Envelope>";
        byte[] payload = envelope.getBytes(StandardCharsets.UTF_8);
        long deadline = System.currentTimeMillis() + WSD_TIMEOUT_MS;
        try (MulticastSocket socket = new MulticastSocket()) {
            socket.setTimeToLive(1);
            socket.setSoTimeout(350);
            InetAddress group = InetAddress.getByName("239.255.255.250");
            socket.send(new DatagramPacket(payload, payload.length, group, 3702));
            byte[] buffer = new byte[65507];
            while (!closed.get() && System.currentTimeMillis() < deadline) {
                try {
                    DatagramPacket response = new DatagramPacket(buffer, buffer.length);
                    socket.receive(response);
                    String xml = new String(response.getData(), response.getOffset(), response.getLength(), StandardCharsets.UTF_8);
                    Set<String> hosts = extractHosts(xml);
                    hosts.add(response.getAddress().getHostAddress());
                    for (String host : hosts) probeSmb(host, "WS-Discovery");
                } catch (IOException ignored) {
                    // Receive timeout keeps the discovery window bounded.
                }
            }
        } catch (IOException ignored) {
            // TCP subnet scanning remains available as the fallback.
        }
    }

    private Set<String> extractHosts(String xml) {
        Set<String> hosts = new LinkedHashSet<>();
        Matcher matcher = XADDR_HOST.matcher(xml);
        while (matcher.find()) {
            String host = matcher.group(1) != null ? matcher.group(1) : matcher.group(2);
            if (isPrivateIpv4(host)) hosts.add(host);
        }
        return hosts;
    }

    private void probeSmb(String host, String source) {
        if (closed.get() || emittedHosts.contains(host) || !isPrivateIpv4(host)) return;
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, SMB_PORT), CONNECT_TIMEOUT_MS);
            if (emittedHosts.add(host) && !closed.get()) callback.onDeviceFound(new Device(host, source));
        } catch (IOException ignored) {
        }
    }

    private List<String> findPrivateIpv4Prefixes() {
        Set<String> prefixes = new LinkedHashSet<>();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            ConnectivityManager manager = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
            Network active = manager == null ? null : manager.getActiveNetwork();
            LinkProperties properties = manager == null || active == null ? null : manager.getLinkProperties(active);
            if (properties != null) {
                for (LinkAddress link : properties.getLinkAddresses()) {
                    addPrefix(prefixes, link.getAddress());
                }
            }
        }
        if (prefixes.isEmpty()) {
            try {
                Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
                for (NetworkInterface network : Collections.list(interfaces)) {
                    if (!network.isUp() || network.isLoopback()) continue;
                    for (InetAddress address : Collections.list(network.getInetAddresses())) addPrefix(prefixes, address);
                }
            } catch (Exception ignored) {
            }
        }
        List<String> result = new ArrayList<>(prefixes);
        return result.size() > MAX_SUBNETS ? result.subList(0, MAX_SUBNETS) : result;
    }

    private void addPrefix(Set<String> prefixes, InetAddress address) {
        if (!(address instanceof Inet4Address) || !address.isSiteLocalAddress()) return;
        String host = address.getHostAddress();
        int split = host.lastIndexOf('.');
        if (split > 0) prefixes.add(host.substring(0, split + 1));
    }

    private boolean isPrivateIpv4(String host) {
        if (TextUtils.isEmpty(host)) return false;
        String[] parts = host.split("\\.");
        if (parts.length != 4) return false;
        try {
            int a = Integer.parseInt(parts[0]);
            int b = Integer.parseInt(parts[1]);
            int c = Integer.parseInt(parts[2]);
            int d = Integer.parseInt(parts[3]);
            if (a < 0 || b < 0 || c < 0 || d < 0 || a > 255 || b > 255 || c > 255 || d > 255) return false;
            return a == 10 || (a == 172 && b >= 16 && b <= 31) || (a == 192 && b == 168) || (a == 169 && b == 254);
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private void acquireMulticastLock() {
        WifiManager wifi = (WifiManager) context.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        if (wifi == null) return;
        multicastLock = wifi.createMulticastLock("smb-watch-discovery");
        multicastLock.setReferenceCounted(false);
        multicastLock.acquire();
    }

    private void releaseMulticastLock() {
        if (multicastLock != null && multicastLock.isHeld()) multicastLock.release();
        multicastLock = null;
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) return;
        releaseMulticastLock();
        coordinator.shutdownNow();
        workers.shutdownNow();
    }
}
