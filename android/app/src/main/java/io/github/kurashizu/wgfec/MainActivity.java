package io.github.kurashizu.wgfec;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.VpnService;
import android.os.Build;
import android.os.Bundle;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.Fragment;

import io.github.kurashizu.wgfec.databinding.ActivityMainBinding;

public class MainActivity extends AppCompatActivity {
    private static final int REQ_VPN = 1;
    private ActivityMainBinding b;

    @Override protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        b = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(b.getRoot());
        b.nav.setOnItemSelectedListener(item -> {
            int id = item.getItemId();
            Fragment f = id == R.id.nav_profiles ? new ProfilesFragment()
                    : id == R.id.nav_log ? new LogFragment()
                    : id == R.id.nav_settings ? new SettingsFragment() : new HomeFragment();
            getSupportFragmentManager().beginTransaction().replace(R.id.container, f).commit();
            return true;
        });
        if (saved == null) b.nav.setSelectedItemId(R.id.nav_home);
        handleLink(getIntent());
    }

    void select(int navId) { b.nav.setSelectedItemId(navId); }

    /** Connect or disconnect, asking for the permissions the first time. */
    void toggle() {
        if (TunnelService.running()) {
            startService(new Intent(this, TunnelService.class).setAction(TunnelService.ACTION_STOP));
            return;
        }
        if (Store.active(this) == null) {
            Toast.makeText(this, "Add a profile first.", Toast.LENGTH_SHORT).show();
            select(R.id.nav_profiles);
            return;
        }
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                && !getSharedPreferences("p", Context.MODE_PRIVATE).getBoolean("asked_notif", false)) {
            getSharedPreferences("p", Context.MODE_PRIVATE).edit().putBoolean("asked_notif", true).apply();
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 2);
        }
        Intent i = VpnService.prepare(this);
        if (i != null) startActivityForResult(i, REQ_VPN);
        else startTunnel();
    }

    private void startTunnel() {
        startForegroundService(new Intent(this, TunnelService.class).setAction(TunnelService.ACTION_START));
    }

    @Override protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == REQ_VPN && res == RESULT_OK) startTunnel();
        else if (req == REQ_VPN) Toast.makeText(this, "The VPN permission is needed to connect.", Toast.LENGTH_LONG).show();
    }

    @Override protected void onNewIntent(Intent i) {
        super.onNewIntent(i);
        setIntent(i);
        handleLink(i);
    }

    /** A wgfec:// link opened from another app. */
    private void handleLink(Intent i) {
        String link = i == null ? null : i.getDataString();
        if (AppString.looksLike(link)) {
            i.setData(null);
            select(R.id.nav_profiles);
            Importer.offer(this, link, null);
        }
    }
}
