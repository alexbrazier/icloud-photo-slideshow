package com.alexbrazier.icloudframe;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.InputType;
import android.util.TypedValue;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Wi-Fi without leaving the app. Frames often have no navigation bar, so Android's own Wi-Fi
 * screen can be a dead end. Uses the pre-Android-10 Wi-Fi API, which targetSdk 28 keeps.
 */
@SuppressWarnings("deprecation")
public final class WifiActivity extends Activity {
    private WifiManager wifi;
    private TextView current;
    private boolean askedLocation;
    private ArrayAdapter<String> adapter;
    private final List<ScanResult> networks = new ArrayList<>();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable refresh = new Runnable() {
        @Override
        public void run() {
            showCurrent();
            handler.postDelayed(this, 3000);
        }
    };
    private final BroadcastReceiver scanReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            showNetworks();
        }
    };

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        if (getActionBar() != null) getActionBar().setDisplayHomeAsUpEnabled(true);
        wifi = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);

        int pad = dp(16);
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(pad, pad, pad, pad);

        current = new TextView(this);
        current.setTextSize(18);
        current.setPadding(0, 0, 0, pad);
        layout.addView(current);

        LinearLayout buttons = new LinearLayout(this);
        buttons.addView(button("Scan", () -> scan()));
        buttons.addView(button("Add by name", () -> askPassword(null, "PSK")));
        buttons.addView(button("Android Wi-Fi settings", () -> startActivity(new Intent(Settings.ACTION_WIFI_SETTINGS))));
        layout.addView(buttons);

        ListView list = new ListView(this);
        adapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, new ArrayList<>());
        list.setAdapter(adapter);
        list.setOnItemClickListener((parent, view, position, id) -> {
            if (position >= networks.size()) return;
            ScanResult r = networks.get(position);
            String security = security(r);
            if (security.equals("EAP")) {
                Toast.makeText(this, "Work networks need Android Wi-Fi settings", Toast.LENGTH_LONG).show();
                return;
            }
            askPassword(r.SSID, security);
        });
        layout.addView(list, new LinearLayout.LayoutParams(-1, 0, 1f));
        setContentView(layout);
    }

    @Override
    protected void onResume() {
        super.onResume();
        registerReceiver(scanReceiver, new IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION));
        handler.post(refresh);
        scan();
    }

    @Override
    protected void onPause() {
        super.onPause();
        unregisterReceiver(scanReceiver);
        handler.removeCallbacks(refresh);
    }

    @Override
    public boolean onNavigateUp() {
        finish();
        return true;
    }

    private int dp(float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics()));
    }

    private Button button(String label, Runnable action) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setOnClickListener(v -> action.run());
        return b;
    }

    private void showCurrent() {
        if (!wifi.isWifiEnabled()) {
            current.setText("Wi-Fi is off. Scan turns it on.");
            return;
        }
        WifiInfo info = wifi.getConnectionInfo();
        String ssid = info == null ? null : info.getSSID();
        boolean connected = Net.online(this) && ssid != null && !ssid.contains("unknown ssid");
        current.setText(connected ? "Connected to " + ssid.replace("\"", "") : "Not connected");
    }

    private void scan() {
        if (!wifi.isWifiEnabled()) wifi.setWifiEnabled(true);
        if (Build.VERSION.SDK_INT >= 23
                && checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            // Ask once; asking from every resume loops when the answer is no
            if (!askedLocation) {
                askedLocation = true;
                Permissions.request(this, Manifest.permission.ACCESS_COARSE_LOCATION, 1);
                return;
            }
        }
        wifi.startScan();
        showNetworks();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) scan();
    }

    private void showNetworks() {
        List<ScanResult> results = wifi.getScanResults();
        Collections.sort(results, (a, b) -> Integer.compare(b.level, a.level));
        networks.clear();
        adapter.clear();
        Set<String> seen = new HashSet<>();
        for (ScanResult r : results) {
            if (r.SSID == null || r.SSID.isEmpty() || !seen.add(r.SSID)) continue;
            networks.add(r);
            int bars = WifiManager.calculateSignalLevel(r.level, 4) + 1;
            adapter.add(r.SSID + "    " + "▂▄▆█".substring(0, bars) + (security(r).equals("OPEN") ? "" : "  🔒"));
        }
        if (networks.isEmpty()) {
            adapter.add("No networks found. Some frames only list networks with Location turned on in Android settings; you can also add one by name.");
        }
    }

    private static String security(ScanResult r) {
        String caps = r.capabilities == null ? "" : r.capabilities;
        if (caps.contains("EAP")) return "EAP";
        if (caps.contains("PSK") || caps.contains("SAE")) return "PSK";
        if (caps.contains("WEP")) return "WEP";
        return "OPEN";
    }

    private void askPassword(String ssid, String security) {
        boolean secured = !security.equals("OPEN");
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(dp(20), dp(8), dp(20), 0);
        EditText name = new EditText(this);
        name.setHint("Network name");
        name.setSingleLine();
        if (ssid == null) form.addView(name);
        EditText password = new EditText(this);
        password.setHint("Password");
        password.setSingleLine();
        password.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
        if (secured) form.addView(password);

        new AlertDialog.Builder(this)
                .setTitle(ssid == null ? "Add network" : ssid)
                .setView(form)
                .setPositiveButton("Connect", (d, w) -> {
                    String n = ssid != null ? ssid : name.getText().toString().trim();
                    // Networks added by name may be hidden, which needs a directed scan
                    if (!n.isEmpty()) connect(n, secured ? password.getText().toString() : null, security, ssid == null);
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void connect(String ssid, String password, String security, boolean hidden) {
        WifiConfiguration config = new WifiConfiguration();
        config.SSID = "\"" + ssid + "\"";
        config.hiddenSSID = hidden;
        if (password == null || password.isEmpty()) {
            config.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.NONE);
        } else if (security.equals("WEP")) {
            config.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.NONE);
            config.allowedAuthAlgorithms.set(WifiConfiguration.AuthAlgorithm.OPEN);
            config.allowedAuthAlgorithms.set(WifiConfiguration.AuthAlgorithm.SHARED);
            // WEP keys are either hex digits or a quoted passphrase
            boolean hex = password.matches("[0-9A-Fa-f]{10}|[0-9A-Fa-f]{26}|[0-9A-Fa-f]{58}");
            config.wepKeys[0] = hex ? password : "\"" + password + "\"";
            config.wepTxKeyIndex = 0;
        } else {
            // A 64-hex-digit key is the raw key and goes in unquoted; anything else is a passphrase
            config.preSharedKey = password.matches("[0-9A-Fa-f]{64}") ? password : "\"" + password + "\"";
        }
        int id = wifi.addNetwork(config);
        if (id < 0) {
            Toast.makeText(this, "Couldn't add that network. Try Android Wi-Fi settings.", Toast.LENGTH_LONG).show();
            return;
        }
        wifi.disconnect();
        wifi.enableNetwork(id, true);
        wifi.reconnect();
        wifi.saveConfiguration();
        Toast.makeText(this, "Connecting to " + ssid, Toast.LENGTH_SHORT).show();
        FrameApp.get().sync().wake(true);
    }
}
