package app.signal.tracker;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.net.VpnService;
import android.provider.Settings;
import android.os.Build;
import android.os.Bundle;
import android.view.WindowInsets;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.widget.FrameLayout;

import java.util.ArrayList;
import java.util.List;

/** Shows the Signal page and gives it live readings from this phone through {@link NativeBridge}. */
public class MainActivity extends Activity {
    private WebView web;
    private NativeBridge bridge;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        web = new WebView(this);
        web.setBackgroundColor(0xFF07070C);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(true);

        // Keep the page clear of the status and navigation bars. Padding goes on a container,
        // because a WebView ignores its own padding.
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0xFF07070C);
        root.addView(web);
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars());
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return WindowInsets.CONSUMED;
        });

        bridge = new NativeBridge(this);
        web.addJavascriptInterface(bridge, "SignalNative");
        setContentView(root);
        web.loadUrl("file:///android_asset/index.html");
        askPermissions();
    }

    private void askPermissions() {
        List<String> needed = new ArrayList<>();
        needed.add(Manifest.permission.ACCESS_FINE_LOCATION);
        needed.add(Manifest.permission.ACCESS_COARSE_LOCATION);
        if (Build.VERSION.SDK_INT >= 33) needed.add(Manifest.permission.NEARBY_WIFI_DEVICES);
        List<String> missing = new ArrayList<>();
        for (String p : needed) {
            if (checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) missing.add(p);
        }
        if (!missing.isEmpty()) requestPermissions(missing.toArray(new String[0]), 1);
    }

    private static final int VPN_REQUEST = 2;

    void openUsageAccess() {
        Intent i = new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS);
        i.setData(Uri.parse("package:" + getPackageName()));
        try { startActivity(i); } catch (Exception e) { startActivity(new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)); }
    }

    /** Android asks the user once to allow the VPN, then the log starts. */
    void startWebsiteLog() {
        Intent consent = VpnService.prepare(this);
        if (consent != null) startActivityForResult(consent, VPN_REQUEST);
        else onActivityResult(VPN_REQUEST, RESULT_OK, null);
    }

    void stopWebsiteLog() {
        startService(new Intent(this, DnsVpnService.class).setAction(DnsVpnService.ACTION_STOP));
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == VPN_REQUEST && resultCode == RESULT_OK) {
            startService(new Intent(this, DnsVpnService.class).setAction(DnsVpnService.ACTION_START));
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        web.onResume();
        web.resumeTimers();
        bridge.start();
    }

    @Override
    protected void onPause() {
        super.onPause();
        // Stop page timers too, so the app does not read Wi-Fi while it is in the background.
        web.onPause();
        web.pauseTimers();
        bridge.stop();
    }

    @Override
    public void onBackPressed() {
        if (web.canGoBack()) web.goBack();
        else super.onBackPressed();
    }
}
