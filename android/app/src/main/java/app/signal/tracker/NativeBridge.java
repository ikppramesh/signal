package app.signal.tracker;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.RouteInfo;
import android.net.TrafficStats;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.telephony.CellSignalStrength;
import android.telephony.SignalStrength;
import android.telephony.TelephonyManager;
import android.webkit.JavascriptInterface;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads this phone's Wi-Fi and mobile radios and scans the local network, returning JSON
 * shaped like the Mac server's /api/status and /api/devices so the same page renders it.
 */
public class NativeBridge {
    private static final String[] BONJOUR = {
        "_airplay._tcp", "_raop._tcp", "_companion-link._tcp", "_googlecast._tcp", "_androidtvremote2._tcp",
        "_amzn-wplay._tcp", "_ipp._tcp", "_printer._tcp", "_pdl-datastream._tcp", "_scanner._tcp",
        "_hap._tcp", "_spotify-connect._tcp", "_sonos._tcp", "_smb._tcp", "_matter._tcp",
    };
    private static final Pattern PING_TIME = Pattern.compile("time=([\\d.]+)");

    private final Context ctx;
    private final WifiManager wifi;
    private final ConnectivityManager cm;
    private final TelephonyManager tel;
    private ScheduledExecutorService loop;

    private volatile Double routerMs;
    private final ArrayDeque<Boolean> routerResults = new ArrayDeque<>();
    private long prevRx = -1, prevTx = -1, prevT;
    private volatile double downMbps, upMbps;

    private volatile JSONObject lastDevices;
    private volatile boolean scanning;
    private volatile long lastScan;
    private String knownNetwork;
    private final Map<String, JSONObject> known = new ConcurrentHashMap<>();

    NativeBridge(Context context) {
        ctx = context.getApplicationContext();
        wifi = (WifiManager) ctx.getSystemService(Context.WIFI_SERVICE);
        cm = (ConnectivityManager) ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
        tel = (TelephonyManager) ctx.getSystemService(Context.TELEPHONY_SERVICE);
    }

    synchronized void start() {
        if (loop != null) return;
        loop = Executors.newScheduledThreadPool(2);
        loop.scheduleWithFixedDelay(this::tick, 0, 2, TimeUnit.SECONDS);
        // Android limits apps to a few Wi-Fi scans every two minutes, so this asks rarely.
        loop.scheduleWithFixedDelay(this::requestWifiScan, 0, 30, TimeUnit.SECONDS);
    }

    synchronized void stop() {
        if (loop != null) loop.shutdownNow();
        loop = null;
    }

    @SuppressWarnings("deprecation")
    private void requestWifiScan() {
        try { wifi.startScan(); } catch (Exception ignored) {}
    }

    private void tick() {
        String gw = gateway();
        Double ms = gw == null ? null : ping(gw, 1);
        routerMs = ms;
        synchronized (routerResults) {
            if (gw == null) routerResults.clear();
            else {
                routerResults.add(ms != null);
                if (routerResults.size() > 20) routerResults.poll();
            }
        }
        long rx = TrafficStats.getTotalRxBytes(), tx = TrafficStats.getTotalTxBytes(), t = System.currentTimeMillis();
        if (prevRx >= 0 && rx >= prevRx && tx >= prevTx && t > prevT) {
            double secs = (t - prevT) / 1000.0;
            downMbps = (rx - prevRx) * 8 / secs / 1e6;
            upMbps = (tx - prevTx) * 8 / secs / 1e6;
        }
        prevRx = rx; prevTx = tx; prevT = t;
    }

    /* ---------------- Status ---------------- */

    @JavascriptInterface
    public String status() {
        JSONObject o = new JSONObject();
        try {
            o.put("viewer", "host");
            o.put("helper", true);
            o.put("native", true);
            Network wn = wifiNetwork();
            boolean connected = wn != null;
            o.put("powerOn", wifi.isWifiEnabled());
            o.put("connected", connected);
            o.put("namesAvailable", hasLocation());
            if (!connected && onCellular()) o.put("cellular", cellular());

            @SuppressWarnings("deprecation") WifiInfo info = wifi.getConnectionInfo();
            String ssid = connected ? cleanSsid(info.getSSID()) : null;
            o.put("ssid", ssid == null ? JSONObject.NULL : ssid);
            List<ScanResult> results = scanResults();

            if (connected) {
                int rssi = info.getRssi();
                int freq = info.getFrequency();
                o.put("rssi", rssi);
                o.put("noise", JSONObject.NULL);
                o.put("percent", percentFor(rssi));
                o.put("txRate", info.getLinkSpeed());
                o.put("channel", channelFor(freq));
                o.put("band", bandFor(freq));
                ScanResult mine = null;
                String bssid = info.getBSSID();
                for (ScanResult r : results) if (r.BSSID != null && r.BSSID.equalsIgnoreCase(bssid)) mine = r;
                o.put("width", mine == null ? 0 : widthFor(mine.channelWidth));
                o.put("security", mine == null ? currentSecurity(info) : securityFor(mine.capabilities));

                JSONObject router = new JSONObject();
                String gw = gateway();
                router.put("ip", gw == null ? JSONObject.NULL : gw);
                router.put("ms", routerMs == null ? JSONObject.NULL : routerMs);
                o.put("router", router);

                double loss;
                synchronized (routerResults) {
                    int n = routerResults.size(), fails = 0;
                    for (Boolean ok : routerResults) if (!ok) fails++;
                    loss = n == 0 ? 0 : (double) fails / n;
                }
                double pingScore = routerMs == null ? 0 : Math.max(0, Math.min(1, 1 - (routerMs - 2) / 80));
                JSONObject health = new JSONObject();
                health.put("score", Math.round(100 * (percentFor(rssi) / 100.0 + pingScore) / 2 * (1 - loss)));
                health.put("snr", JSONObject.NULL);
                health.put("loss", Math.round(loss * 100));
                o.put("health", health);
            } else {
                JSONObject router = new JSONObject();
                router.put("ip", JSONObject.NULL);
                router.put("ms", JSONObject.NULL);
                o.put("router", router);
            }
            o.put("nearby", nearby(results, ssid));
        } catch (JSONException ignored) {}
        return o.toString();
    }

    private JSONArray nearby(List<ScanResult> results, String current) throws JSONException {
        Map<String, ScanResult> best = new LinkedHashMap<>();
        for (ScanResult r : results) {
            @SuppressWarnings("deprecation") String name = r.SSID;
            if (name == null || name.isEmpty()) continue;
            if (name.equals(current)) continue;
            ScanResult prev = best.get(name);
            if (prev == null || prev.level < r.level) best.put(name, r);
        }
        List<ScanResult> list = new ArrayList<>(best.values());
        Collections.sort(list, (a, b) -> b.level - a.level);
        JSONArray out = new JSONArray();
        for (ScanResult r : list) {
            @SuppressWarnings("deprecation") String name = r.SSID;
            JSONObject n = new JSONObject();
            n.put("id", "s:" + name);
            n.put("ssid", name);
            n.put("rssi", r.level);
            n.put("percent", percentFor(r.level));
            n.put("channel", channelFor(r.frequency));
            n.put("band", bandFor(r.frequency));
            n.put("security", securityFor(r.capabilities));
            out.put(n);
        }
        return out;
    }

    private JSONObject cellular() throws JSONException {
        JSONObject c = new JSONObject();
        int dbm = Integer.MAX_VALUE, level = 0;
        try {
            SignalStrength ss = tel.getSignalStrength();
            if (ss != null) {
                level = ss.getLevel();
                for (CellSignalStrength cs : ss.getCellSignalStrengths()) {
                    if (cs.getDbm() != Integer.MAX_VALUE) { dbm = cs.getDbm(); break; }
                }
            }
        } catch (Exception ignored) {}
        int percent = dbm == Integer.MAX_VALUE ? level * 25 : clamp((dbm + 125) * 100 / 55);
        c.put("percent", percent);
        c.put("dbm", dbm == Integer.MAX_VALUE ? JSONObject.NULL : dbm);
        c.put("bars", level);
        String carrier = tel.getNetworkOperatorName();
        c.put("carrier", carrier == null || carrier.isEmpty() ? JSONObject.NULL : carrier);
        return c;
    }

    /* ---------------- Devices ---------------- */

    @JavascriptInterface
    public String devices(boolean fresh) {
        long now = System.currentTimeMillis();
        if (!scanning && (lastDevices == null || (fresh && now - lastScan > 10000) || now - lastScan > 120000)) {
            scanning = true;
            new Thread(this::scanNetwork, "device-scan").start();
        }
        try {
            JSONObject out = lastDevices == null ? new JSONObject().put("devices", new JSONArray()).put("time", now) : new JSONObject(lastDevices.toString());
            JSONObject self = new JSONObject();
            self.put("downMbps", Math.round(downMbps * 100) / 100.0);
            self.put("upMbps", Math.round(upMbps * 100) / 100.0);
            out.put("self", self);
            out.put("scanning", scanning);
            return out.toString();
        } catch (JSONException e) {
            return "{\"devices\":[]}";
        }
    }

    private void scanNetwork() {
        try {
            Network wn = wifiNetwork();
            if (wn == null) {
                lastDevices = new JSONObject().put("time", System.currentTimeMillis()).put("devices", new JSONArray()).put("network", JSONObject.NULL);
                return;
            }
            LinkProperties lp = cm.getLinkProperties(wn);
            Inet4Address self = null;
            int prefix = 24;
            for (LinkAddress la : lp.getLinkAddresses()) {
                if (la.getAddress() instanceof Inet4Address) { self = (Inet4Address) la.getAddress(); prefix = la.getPrefixLength(); }
            }
            if (self == null) return;
            String selfIp = self.getHostAddress();
            String gw = gateway();
            @SuppressWarnings("deprecation") String ssid = cleanSsid(wifi.getConnectionInfo().getSSID());
            String networkKey = gw + "|" + ssid;
            if (!networkKey.equals(knownNetwork)) { known.clear(); knownNetwork = networkKey; }

            List<String> hosts = hostsFor(self, prefix);
            Set<String> alive = ConcurrentHashMap.newKeySet();
            Map<String, JSONObject> bonjour = new ConcurrentHashMap<>();
            Thread mdns = new Thread(() -> discoverBonjour(bonjour), "bonjour");
            mdns.start();

            ExecutorService pool = Executors.newFixedThreadPool(48);
            for (String ip : hosts) pool.submit(() -> { if (ping(ip, 1) != null) alive.add(ip); });
            pool.shutdown();
            pool.awaitTermination(40, TimeUnit.SECONDS);
            mdns.join(20000);

            Set<String> ips = ConcurrentHashMap.newKeySet();
            ips.addAll(alive);
            ips.addAll(bonjour.keySet());
            ips.add(selfIp);
            if (gw != null) ips.add(gw);

            Map<String, String> reverse = new ConcurrentHashMap<>();
            ExecutorService dnsPool = Executors.newFixedThreadPool(16);
            for (String ip : ips) {
                if (bonjour.containsKey(ip) || ip.equals(selfIp)) continue;
                dnsPool.submit(() -> {
                    try {
                        String name = InetAddress.getByName(ip).getCanonicalHostName();
                        if (name != null && !name.equals(ip)) reverse.put(ip, name.replaceAll("\\.(local|lan|home)$", ""));
                    } catch (Exception ignored) {}
                });
            }
            dnsPool.shutdown();
            dnsPool.awaitTermination(8, TimeUnit.SECONDS);

            long now = System.currentTimeMillis();
            for (JSONObject d : known.values()) d.put("status", "offline");
            for (String ip : ips) {
                JSONObject b = bonjour.get(ip);
                JSONObject txt = b == null ? new JSONObject() : b.getJSONObject("txt");
                boolean isSelf = ip.equals(selfIp), isRouter = ip.equals(gw);
                JSONObject d = known.containsKey(ip) ? known.get(ip) : new JSONObject().put("firstSeen", now);
                String name = isSelf ? Build.MANUFACTURER + " " + Build.MODEL
                    : b != null ? txt.optString("fn", b.getJSONArray("names").optString(0, ""))
                    : reverse.getOrDefault(ip, d.optString("name", ""));
                if (isRouter && name.isEmpty()) name = "Router";
                String model = firstOf(txt, "model", "rpMd", "md", "am", "ty", "usb_MDL");
                d.put("id", ip).put("ip", ip).put("mac", JSONObject.NULL).put("vendor", "")
                    .put("name", name).put("model", model.isEmpty() ? d.optString("model", "") : model)
                    .put("isSelf", isSelf).put("isRouter", isRouter)
                    .put("services", b == null ? new JSONArray() : b.getJSONArray("services"))
                    .put("status", alive.contains(ip) || b != null || isSelf ? "online" : "idle")
                    .put("lastSeen", now);
                if (isSelf) d.put("type", "Phone");
                known.put(ip, d);
            }

            List<JSONObject> list = new ArrayList<>(known.values());
            Collections.sort(list, (a, b) -> {
                int off = Boolean.compare("offline".equals(a.optString("status")), "offline".equals(b.optString("status")));
                if (off != 0) return off;
                return Integer.compare(lastOctet(a.optString("ip")), lastOctet(b.optString("ip")));
            });
            JSONArray arr = new JSONArray();
            for (JSONObject d : list) arr.put(d);
            JSONObject network = new JSONObject().put("gateway", gw == null ? JSONObject.NULL : gw)
                .put("subnet", selfIp + " / " + prefix).put("macsVisible", false);
            lastDevices = new JSONObject().put("time", now).put("network", network).put("devices", arr);
        } catch (Exception ignored) {
        } finally {
            lastScan = System.currentTimeMillis();
            scanning = false;
        }
    }

    /** Browses Bonjour for a few seconds, then resolves each service one at a time, as older Android requires. */
    @SuppressWarnings("deprecation")
    private void discoverBonjour(Map<String, JSONObject> out) {
        NsdManager nsd = (NsdManager) ctx.getSystemService(Context.NSD_SERVICE);
        WifiManager.MulticastLock lock = wifi.createMulticastLock("signal-scan");
        lock.acquire();
        List<NsdServiceInfo> found = Collections.synchronizedList(new ArrayList<>());
        List<NsdManager.DiscoveryListener> listeners = new ArrayList<>();
        try {
            for (String type : BONJOUR) {
                NsdManager.DiscoveryListener l = new NsdManager.DiscoveryListener() {
                    public void onStartDiscoveryFailed(String t, int e) {}
                    public void onStopDiscoveryFailed(String t, int e) {}
                    public void onDiscoveryStarted(String t) {}
                    public void onDiscoveryStopped(String t) {}
                    public void onServiceFound(NsdServiceInfo info) { found.add(info); }
                    public void onServiceLost(NsdServiceInfo info) {}
                };
                try { nsd.discoverServices(type, NsdManager.PROTOCOL_DNS_SD, l); listeners.add(l); } catch (Exception ignored) {}
            }
            Thread.sleep(4000);
        } catch (InterruptedException ignored) {
        } finally {
            for (NsdManager.DiscoveryListener l : listeners) {
                try { nsd.stopServiceDiscovery(l); } catch (Exception ignored) {}
            }
        }

        List<NsdServiceInfo> snapshot;
        synchronized (found) { snapshot = new ArrayList<>(found); }
        for (NsdServiceInfo info : snapshot) {
            CountDownLatch done = new CountDownLatch(1);
            try {
                nsd.resolveService(info, new NsdManager.ResolveListener() {
                    public void onResolveFailed(NsdServiceInfo s, int e) { done.countDown(); }
                    public void onServiceResolved(NsdServiceInfo s) {
                        try {
                            InetAddress host = s.getHost();
                            if (host instanceof Inet4Address) {
                                String ip = host.getHostAddress();
                                JSONObject d = out.containsKey(ip) ? out.get(ip)
                                    : new JSONObject().put("names", new JSONArray()).put("services", new JSONArray()).put("txt", new JSONObject());
                                d.getJSONArray("names").put(s.getServiceName());
                                d.getJSONArray("services").put(info.getServiceType().replaceAll("\\.$", ""));
                                JSONObject txt = d.getJSONObject("txt");
                                for (Map.Entry<String, byte[]> e : s.getAttributes().entrySet()) {
                                    if (e.getValue() != null) txt.put(e.getKey(), new String(e.getValue(), StandardCharsets.UTF_8));
                                }
                                out.put(ip, d);
                            }
                        } catch (JSONException ignored) {}
                        done.countDown();
                    }
                });
                done.await(3, TimeUnit.SECONDS);
            } catch (Exception ignored) {}
        }
        lock.release();
    }

    /* ---------------- Helpers ---------------- */

    private Network wifiNetwork() {
        for (Network n : cm.getAllNetworks()) {
            NetworkCapabilities c = cm.getNetworkCapabilities(n);
            if (c != null && c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return n;
        }
        return null;
    }

    private boolean onCellular() {
        Network n = cm.getActiveNetwork();
        NetworkCapabilities c = n == null ? null : cm.getNetworkCapabilities(n);
        return c != null && c.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR);
    }

    private String gateway() {
        Network wn = wifiNetwork();
        if (wn == null) return null;
        LinkProperties lp = cm.getLinkProperties(wn);
        if (lp == null) return null;
        for (RouteInfo r : lp.getRoutes()) {
            if (r.isDefaultRoute() && r.getGateway() instanceof Inet4Address) return r.getGateway().getHostAddress();
        }
        return null;
    }

    private boolean hasLocation() {
        return ctx.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    private List<ScanResult> scanResults() {
        try {
            List<ScanResult> r = wifi.getScanResults();
            return r == null ? new ArrayList<>() : r;
        } catch (SecurityException e) {
            return new ArrayList<>();
        }
    }

    private static Double ping(String ip, int waitSeconds) {
        Process p = null;
        try {
            p = new ProcessBuilder("ping", "-c", "1", "-W", String.valueOf(waitSeconds), ip).redirectErrorStream(true).start();
            StringBuilder sb = new StringBuilder();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                String line;
                while ((line = r.readLine()) != null) sb.append(line).append('\n');
            }
            p.waitFor(waitSeconds + 2, TimeUnit.SECONDS);
            Matcher m = PING_TIME.matcher(sb);
            return m.find() ? Double.valueOf(m.group(1)) : null;
        } catch (Exception e) {
            return null;
        } finally {
            if (p != null) p.destroy();
        }
    }

    private static List<String> hostsFor(Inet4Address self, int prefix) {
        byte[] b = self.getAddress();
        long ip = ((b[0] & 0xFFL) << 24) | ((b[1] & 0xFFL) << 16) | ((b[2] & 0xFFL) << 8) | (b[3] & 0xFFL);
        int bits = Math.max(prefix, 24); // Bigger networks are trimmed to the /24 around this phone.
        long size = 1L << (32 - bits);
        long base = ip & ~(size - 1);
        List<String> out = new ArrayList<>();
        for (long i = 1; i < size - 1; i++) {
            long a = base + i;
            out.add(((a >> 24) & 255) + "." + ((a >> 16) & 255) + "." + ((a >> 8) & 255) + "." + (a & 255));
        }
        return out;
    }

    private static int lastOctet(String ip) {
        try { return Integer.parseInt(ip.substring(ip.lastIndexOf('.') + 1)); } catch (Exception e) { return 999; }
    }

    private static String firstOf(JSONObject txt, String... keys) {
        for (String k : keys) if (!txt.optString(k, "").isEmpty()) return txt.optString(k);
        return "";
    }

    private static String cleanSsid(String s) {
        if (s == null || s.equals("<unknown ssid>") || s.isEmpty()) return null;
        return s.startsWith("\"") && s.endsWith("\"") ? s.substring(1, s.length() - 1) : s;
    }

    private static int clamp(int v) { return Math.max(0, Math.min(100, v)); }
    private static int percentFor(int rssi) { return clamp(2 * (rssi + 100)); }

    private static int channelFor(int f) {
        if (f == 2484) return 14;
        if (f < 3000) return (f - 2407) / 5;
        if (f < 5925) return (f - 5000) / 5;
        return (f - 5950) / 5;
    }

    private static String bandFor(int f) {
        return f < 3000 ? "2.4 GHz" : f < 5925 ? "5 GHz" : "6 GHz";
    }

    private static int widthFor(int w) {
        switch (w) {
            case ScanResult.CHANNEL_WIDTH_40MHZ: return 40;
            case ScanResult.CHANNEL_WIDTH_80MHZ: return 80;
            case ScanResult.CHANNEL_WIDTH_160MHZ: return 160;
            case ScanResult.CHANNEL_WIDTH_80MHZ_PLUS_MHZ: return 80;
            case 5: return 320;
            default: return 20;
        }
    }

    static String securityFor(String caps) {
        if (caps == null) return "";
        boolean sae = caps.contains("SAE"), psk = caps.contains("PSK"), eap = caps.contains("EAP");
        boolean wpa3Eap = caps.contains("EAP_SUITE_B") || caps.contains("EAP-SHA256");
        if (eap) return wpa3Eap ? "WPA3 Enterprise" : caps.contains("RSN") || caps.contains("WPA2") ? "WPA2 Enterprise" : "WPA Enterprise";
        if (sae && psk) return "WPA2/WPA3";
        if (sae) return "WPA3";
        if (psk) return caps.contains("RSN") || caps.contains("WPA2") ? (caps.contains("[WPA-") ? "WPA/WPA2" : "WPA2") : "WPA";
        if (caps.contains("OWE")) return "Enhanced Open";
        if (caps.contains("WEP")) return "WEP";
        return "Open";
    }

    private static String currentSecurity(WifiInfo info) {
        if (Build.VERSION.SDK_INT < 31) return "";
        switch (info.getCurrentSecurityType()) {
            case WifiInfo.SECURITY_TYPE_OPEN: return "Open";
            case WifiInfo.SECURITY_TYPE_WEP: return "WEP";
            case WifiInfo.SECURITY_TYPE_PSK: return "WPA2";
            case WifiInfo.SECURITY_TYPE_SAE: return "WPA3";
            case WifiInfo.SECURITY_TYPE_OWE: return "Enhanced Open";
            case WifiInfo.SECURITY_TYPE_EAP: return "WPA2 Enterprise";
            case WifiInfo.SECURITY_TYPE_EAP_WPA3_ENTERPRISE:
            case WifiInfo.SECURITY_TYPE_EAP_WPA3_ENTERPRISE_192_BIT: return "WPA3 Enterprise";
            default: return "";
        }
    }
}
