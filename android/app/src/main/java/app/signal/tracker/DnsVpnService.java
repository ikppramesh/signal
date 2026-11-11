package app.signal.tracker;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.VpnService;
import android.os.ParcelFileDescriptor;
import android.system.OsConstants;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.Arrays;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Website log for this phone. A VPN that routes only DNS lookups to itself: each lookup is
 * forwarded unchanged to the real DNS server and the website name and requesting app are
 * recorded on the phone. All other traffic goes directly to the network as usual.
 */
public class DnsVpnService extends VpnService {
    static final String ACTION_START = "start", ACTION_STOP = "stop";
    static final String LOG_FILE = "websites.jsonl";
    private static final String VPN_ADDRESS = "10.111.0.1", DNS_ADDRESS = "10.111.0.2";

    static volatile boolean running;
    private ParcelFileDescriptor tun;
    private Thread reader;
    private ExecutorService forwarders;

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            shutdown();
            stopSelf();
            return START_NOT_STICKY;
        }
        if (!running) startTunnel();
        return START_STICKY;
    }

    private void startTunnel() {
        try {
            Builder b = new Builder()
                .setSession("Signal website log")
                .addAddress(VPN_ADDRESS, 32)
                .addDnsServer(DNS_ADDRESS)
                .addRoute(DNS_ADDRESS, 32)
                .setBlocking(true);
            b.addDisallowedApplication(getPackageName());
            tun = b.establish();
            if (tun == null) return;
            running = true;
            forwarders = Executors.newFixedThreadPool(8);
            reader = new Thread(this::readLoop, "dns-vpn");
            reader.start();
        } catch (Exception e) {
            shutdown();
        }
    }

    private void readLoop() {
        byte[] buf = new byte[32767];
        try (FileInputStream in = new FileInputStream(tun.getFileDescriptor());
             FileOutputStream out = new FileOutputStream(tun.getFileDescriptor())) {
            while (running) {
                int n = in.read(buf);
                if (n <= 0) continue;
                byte[] packet = Arrays.copyOf(buf, n);
                forwarders.submit(() -> handle(packet, out));
            }
        } catch (Exception ignored) {
        } finally {
            running = false;
        }
    }

    /** Handles one IPv4/UDP packet sent to the fake DNS address. */
    private void handle(byte[] p, FileOutputStream out) {
        try {
            if ((p[0] >> 4) != 4 || p[9] != 17) return;
            int ihl = (p[0] & 0x0F) * 4;
            int srcPort = ((p[ihl] & 0xFF) << 8) | (p[ihl + 1] & 0xFF);
            int dstPort = ((p[ihl + 2] & 0xFF) << 8) | (p[ihl + 3] & 0xFF);
            if (dstPort != 53) return;
            byte[] src = Arrays.copyOfRange(p, 12, 16), dst = Arrays.copyOfRange(p, 16, 20);
            byte[] query = Arrays.copyOfRange(p, ihl + 8, p.length);

            String app = appFor(src, srcPort, dst);
            byte[] answer = forward(query);
            if (answer == null) return;

            byte[] reply = udpPacket(dst, src, 53, srcPort, answer);
            synchronized (out) { out.write(reply); }
            Dns.Question q = Dns.question(query);
            if (q != null && !q.name.isEmpty() && !q.name.endsWith(".arpa") && !q.name.endsWith(".local")) {
                log(app, q.name, Dns.ipv4Answers(answer));
            }
        } catch (Exception ignored) {}
    }

    private byte[] forward(byte[] query) {
        try (DatagramSocket s = new DatagramSocket()) {
            protect(s);
            s.setSoTimeout(4000);
            s.send(new DatagramPacket(query, query.length, upstream(), 53));
            byte[] buf = new byte[4096];
            DatagramPacket resp = new DatagramPacket(buf, buf.length);
            s.receive(resp);
            return Arrays.copyOf(buf, resp.getLength());
        } catch (Exception e) {
            return null;
        }
    }

    /** The real network's DNS server, so lookups behave as they would without the log. */
    private InetAddress upstream() throws Exception {
        ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        for (Network n : cm.getAllNetworks()) {
            NetworkCapabilities c = cm.getNetworkCapabilities(n);
            if (c == null || c.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) continue;
            LinkProperties lp = cm.getLinkProperties(n);
            if (lp == null) continue;
            for (InetAddress a : lp.getDnsServers()) if (a instanceof Inet4Address) return a;
        }
        return InetAddress.getByName("1.1.1.1");
    }

    private String appFor(byte[] src, int srcPort, byte[] dst) {
        try {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            int uid = cm.getConnectionOwnerUid(OsConstants.IPPROTO_UDP,
                new InetSocketAddress(InetAddress.getByAddress(src), srcPort),
                new InetSocketAddress(InetAddress.getByAddress(dst), 53));
            return appName(this, uid);
        } catch (Exception e) {
            return "Unknown app";
        }
    }

    static String appName(Context ctx, int uid) {
        if (uid == 1000) return "Android system";
        if (uid < 10000) return "System service";
        PackageManager pm = ctx.getPackageManager();
        String[] pkgs = pm.getPackagesForUid(uid);
        if (pkgs == null || pkgs.length == 0) return "App " + uid;
        try {
            ApplicationInfo info = pm.getApplicationInfo(pkgs[0], 0);
            return pm.getApplicationLabel(info).toString();
        } catch (Exception e) {
            return pkgs[0];
        }
    }

    private synchronized void log(String app, String domain, JSONArray ips) {
        try {
            File f = new File(getFilesDir(), LOG_FILE);
            if (f.length() > 20 * 1024 * 1024) f.renameTo(new File(getFilesDir(), LOG_FILE + ".old"));
            JSONObject e = new JSONObject().put("t", System.currentTimeMillis()).put("app", app).put("domain", domain).put("ips", ips);
            try (FileWriter w = new FileWriter(f, true)) { w.write(e.toString()); w.write('\n'); }
        } catch (Exception ignored) {}
    }

    private static byte[] udpPacket(byte[] src, byte[] dst, int srcPort, int dstPort, byte[] data) {
        int len = 28 + data.length;
        byte[] p = new byte[len];
        p[0] = 0x45;
        p[2] = (byte) (len >> 8); p[3] = (byte) len;
        p[8] = 64; p[9] = 17;
        System.arraycopy(src, 0, p, 12, 4);
        System.arraycopy(dst, 0, p, 16, 4);
        int sum = 0;
        for (int i = 0; i < 20; i += 2) sum += ((p[i] & 0xFF) << 8) | (p[i + 1] & 0xFF);
        while ((sum >> 16) != 0) sum = (sum & 0xFFFF) + (sum >> 16);
        sum = ~sum & 0xFFFF;
        p[10] = (byte) (sum >> 8); p[11] = (byte) sum;
        p[20] = (byte) (srcPort >> 8); p[21] = (byte) srcPort;
        p[22] = (byte) (dstPort >> 8); p[23] = (byte) dstPort;
        int udpLen = 8 + data.length;
        p[24] = (byte) (udpLen >> 8); p[25] = (byte) udpLen;
        // UDP checksum 0 means "not computed", which IPv4 allows.
        System.arraycopy(data, 0, p, 28, data.length);
        return p;
    }

    private void shutdown() {
        running = false;
        try { if (tun != null) tun.close(); } catch (Exception ignored) {}
        tun = null;
        if (forwarders != null) forwarders.shutdownNow();
    }

    @Override
    public void onRevoke() { shutdown(); stopSelf(); }

    @Override
    public void onDestroy() { shutdown(); super.onDestroy(); }
}
