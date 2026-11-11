package app.signal.tracker;

import android.app.AppOpsManager;
import android.app.usage.NetworkStats;
import android.app.usage.NetworkStatsManager;
import android.content.Context;
import android.net.ConnectivityManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;

/** Traffic tab data on Android: this phone's data counters and its website log. */
final class Traffic {
    private static final Pattern TWO_LEVEL = Pattern.compile("^(co|com|net|org|gov|ac|edu)\\.[a-z]{2}$");
    private final Context ctx;
    private final NetworkStatsManager stats;
    private final Map<String, JSONObject> geo = new ConcurrentHashMap<>();
    private final Set<String> geoPending = ConcurrentHashMap.newKeySet();
    private final ExecutorService geoPool = Executors.newSingleThreadExecutor();
    private JSONObject usageCache;
    private long usageTime;

    Traffic(Context context) {
        ctx = context.getApplicationContext();
        stats = (NetworkStatsManager) ctx.getSystemService(Context.NETWORK_STATS_SERVICE);
    }

    JSONObject report() throws Exception {
        return new JSONObject().put("usage", usage()).put("websites", websites());
    }

    /* ---------- Data counters ---------- */

    boolean hasUsageAccess() {
        AppOpsManager ops = (AppOpsManager) ctx.getSystemService(Context.APP_OPS_SERVICE);
        int mode = ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, android.os.Process.myUid(), ctx.getPackageName());
        return mode == AppOpsManager.MODE_ALLOWED;
    }

    private synchronized JSONObject usage() throws Exception {
        if (!hasUsageAccess()) return new JSONObject().put("permission", false).put("scope", "This phone");
        long now = System.currentTimeMillis();
        if (usageCache != null && now - usageTime < 30000) return usageCache;

        long today = startOfDay(now, 0);
        long[] t = total(today, now);
        JSONArray days = new JSONArray();
        SimpleDateFormat fmt = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
        for (int i = 13; i >= 0; i--) {
            long s = startOfDay(now, -i), e = i == 0 ? now : startOfDay(now, -i + 1);
            long[] d = total(s, e);
            days.put(new JSONObject().put("date", fmt.format(s)).put("down", d[0]).put("up", d[1]));
        }
        JSONArray hours = new JSONArray();
        long hourNow = now - now % 3600000;
        for (int i = 23; i >= 0; i--) {
            long s = hourNow - i * 3600000L;
            long[] h = total(s, i == 0 ? now : s + 3600000L);
            hours.put(new JSONObject().put("t", s).put("down", h[0]).put("up", h[1]));
        }
        // Android keeps a limited history, so lifetime starts at the oldest month it still has.
        long since = startOfMonth(now, 0);
        for (int m = 1; m <= 24; m++) {
            long s = startOfMonth(now, -m);
            long[] d = total(s, startOfMonth(now, -m + 1));
            if (d[0] + d[1] == 0) break;
            since = s;
        }
        long[] life = total(since, now);

        usageCache = new JSONObject()
            .put("scope", "This phone, Wi-Fi and mobile data")
            .put("note", "")
            .put("today", new JSONObject().put("down", t[0]).put("up", t[1]))
            .put("lifetime", new JSONObject().put("down", life[0]).put("up", life[1]).put("since", since))
            .put("days", days)
            .put("hours", hours)
            .put("apps", apps(today, now));
        usageTime = now;
        return usageCache;
    }

    @SuppressWarnings("deprecation")
    private long[] total(long start, long end) {
        long rx = 0, tx = 0;
        for (int type : new int[] {ConnectivityManager.TYPE_WIFI, ConnectivityManager.TYPE_MOBILE}) {
            try {
                NetworkStats.Bucket b = stats.querySummaryForDevice(type, null, start, end);
                if (b != null) { rx += b.getRxBytes(); tx += b.getTxBytes(); }
            } catch (Exception ignored) {}
        }
        return new long[] {rx, tx};
    }

    @SuppressWarnings("deprecation")
    private JSONArray apps(long start, long end) throws Exception {
        Map<Integer, long[]> byUid = new HashMap<>();
        for (int type : new int[] {ConnectivityManager.TYPE_WIFI, ConnectivityManager.TYPE_MOBILE}) {
            try (NetworkStats ns = stats.querySummary(type, null, start, end)) {
                NetworkStats.Bucket b = new NetworkStats.Bucket();
                while (ns.hasNextBucket()) {
                    ns.getNextBucket(b);
                    long[] v = byUid.computeIfAbsent(b.getUid(), k -> new long[2]);
                    v[0] += b.getRxBytes();
                    v[1] += b.getTxBytes();
                }
            } catch (Exception ignored) {}
        }
        List<Map.Entry<Integer, long[]>> list = new ArrayList<>(byUid.entrySet());
        Collections.sort(list, (a, b) -> Long.compare(b.getValue()[0] + b.getValue()[1], a.getValue()[0] + a.getValue()[1]));
        JSONArray out = new JSONArray();
        Map<String, long[]> merged = new HashMap<>();
        List<String> order = new ArrayList<>();
        for (Map.Entry<Integer, long[]> e : list) {
            String name = DnsVpnService.appName(ctx, e.getKey());
            if (!merged.containsKey(name)) { merged.put(name, new long[2]); order.add(name); }
            merged.get(name)[0] += e.getValue()[0];
            merged.get(name)[1] += e.getValue()[1];
        }
        for (String name : order.subList(0, Math.min(10, order.size()))) {
            long[] v = merged.get(name);
            if (v[0] + v[1] == 0) continue;
            out.put(new JSONObject().put("name", name).put("down", v[0]).put("up", v[1]));
        }
        return out;
    }

    private static long startOfDay(long now, int dayOffset) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(now);
        c.set(Calendar.HOUR_OF_DAY, 0); c.set(Calendar.MINUTE, 0); c.set(Calendar.SECOND, 0); c.set(Calendar.MILLISECOND, 0);
        c.add(Calendar.DAY_OF_MONTH, dayOffset);
        return c.getTimeInMillis();
    }

    private static long startOfMonth(long now, int monthOffset) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(startOfDay(now, 0));
        c.set(Calendar.DAY_OF_MONTH, 1);
        c.add(Calendar.MONTH, monthOffset);
        return c.getTimeInMillis();
    }

    /* ---------- Website log ---------- */

    private JSONObject websites() throws Exception {
        File f = new File(ctx.getFilesDir(), DnsVpnService.LOG_FILE);
        long since = System.currentTimeMillis() - 7L * 86400000, today = startOfDay(System.currentTimeMillis(), 0);
        Map<String, Map<String, JSONObject>> byApp = new HashMap<>();
        Map<String, Integer> totals = new HashMap<>();
        int todayCount = 0;
        if (f.exists()) {
            try (BufferedReader r = new BufferedReader(new FileReader(f))) {
                String line;
                while ((line = r.readLine()) != null) {
                    JSONObject e;
                    try { e = new JSONObject(line); } catch (Exception ex) { continue; }
                    long t = e.optLong("t");
                    if (t < since) continue;
                    if (t >= today) todayCount++;
                    String app = e.optString("app", "Unknown app"), domain = e.optString("domain");
                    String site = siteOf(domain);
                    Map<String, JSONObject> sites = byApp.computeIfAbsent(app, k -> new HashMap<>());
                    JSONObject s = sites.get(site);
                    if (s == null) {
                        s = new JSONObject().put("domain", site).put("count", 0).put("lastSeen", 0L).put("examples", new JSONArray());
                        sites.put(site, s);
                    }
                    s.put("count", s.getInt("count") + 1);
                    s.put("lastSeen", Math.max(s.getLong("lastSeen"), t));
                    JSONArray ex = s.getJSONArray("examples");
                    if (ex.length() < 3 && !contains(ex, domain)) ex.put(domain);
                    JSONArray ips = e.optJSONArray("ips");
                    if (ips != null && ips.length() > 0) s.put("ip", ips.getString(0));
                    totals.merge(app, 1, Integer::sum);
                }
            }
        }
        List<String> apps = new ArrayList<>(byApp.keySet());
        Collections.sort(apps, (a, b) -> totals.get(b) - totals.get(a));
        JSONArray groups = new JSONArray();
        for (String app : apps) {
            List<JSONObject> sites = new ArrayList<>(byApp.get(app).values());
            Collections.sort(sites, (a, b) -> b.optInt("count") - a.optInt("count"));
            JSONArray domains = new JSONArray();
            for (int i = 0; i < Math.min(60, sites.size()); i++) {
                JSONObject s = sites.get(i);
                String ip = s.optString("ip", "");
                if (i < 25) locate(ip);
                JSONObject g = geo.get(ip);
                if (g != null) for (String k : new String[] {"country", "code", "city", "org"}) s.put(k, g.optString(k));
                s.remove("ip");
                domains.put(s);
            }
            groups.put(new JSONObject().put("key", app).put("title", app).put("sub", "").put("total", totals.get(app)).put("domains", domains));
        }
        return new JSONObject().put("scope", "This phone").put("enabled", f.exists()).put("active", DnsVpnService.running)
            .put("today", todayCount).put("groups", groups);
    }

    private void locate(String ip) {
        if (ip.isEmpty() || geo.containsKey(ip) || !geoPending.add(ip)) return;
        geoPool.submit(() -> {
            try {
                HttpURLConnection c = (HttpURLConnection) new URL("https://ipwho.is/" + ip + "?fields=success,country,country_code,city,connection").openConnection();
                c.setConnectTimeout(5000); c.setReadTimeout(5000);
                try (InputStream in = c.getInputStream()) {
                    JSONObject g = new JSONObject(new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
                    if (g.optBoolean("success")) {
                        JSONObject conn = g.optJSONObject("connection");
                        geo.put(ip, new JSONObject().put("country", g.optString("country")).put("code", g.optString("country_code"))
                            .put("city", g.optString("city")).put("org", conn == null ? "" : conn.optString("org")));
                    }
                }
                Thread.sleep(1200);
            } catch (Exception ignored) {
            } finally {
                geoPending.remove(ip);
            }
        });
    }

    private static boolean contains(JSONArray a, String v) {
        for (int i = 0; i < a.length(); i++) if (v.equals(a.optString(i))) return true;
        return false;
    }

    static String siteOf(String domain) {
        String[] parts = domain.split("\\.");
        if (parts.length <= 2) return domain;
        String two = parts[parts.length - 2] + "." + parts[parts.length - 1];
        int keep = TWO_LEVEL.matcher(two).matches() ? 3 : 2;
        StringBuilder sb = new StringBuilder();
        for (int i = Math.max(0, parts.length - keep); i < parts.length; i++) {
            if (sb.length() > 0) sb.append('.');
            sb.append(parts[i]);
        }
        return sb.toString();
    }
}
