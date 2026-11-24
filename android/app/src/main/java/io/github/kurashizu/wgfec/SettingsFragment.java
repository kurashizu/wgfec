package io.github.kurashizu.wgfec;

import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import io.github.kurashizu.wgfec.databinding.FragmentSettingsBinding;

public class SettingsFragment extends Fragment {
    private FragmentSettingsBinding b;

    @Nullable @Override
    public View onCreateView(@NonNull LayoutInflater inf, @Nullable ViewGroup c, @Nullable Bundle s) {
        b = FragmentSettingsBinding.inflate(inf, c, false);
        b.battBtn.setOnClickListener(v -> startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)));
        b.notifBtn.setOnClickListener(v -> startActivity(new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, requireContext().getPackageName())));
        b.vpnBtn.setOnClickListener(v -> startActivity(new Intent(Settings.ACTION_VPN_SETTINGS)));
        String ver = "";
        try { ver = requireContext().getPackageManager().getPackageInfo(requireContext().getPackageName(), 0).versionName; } catch (Exception ignored) { }
        b.about.setText("WG FEC " + ver + "\nWireGuard tunnel with UDPspeeder forward error correction.\n\n"
                + "Built on the WireGuard Android tunnel library (wireguard-go) and UDPspeeder (speederv2), both open source.\n"
                + "\"WireGuard\" is a registered trademark of Jason A. Donenfeld. This app is not made or endorsed by the WireGuard project.");
        return b.getRoot();
    }

    @Override public void onResume() {
        super.onResume();
        Context c = requireContext();
        boolean ign = c.getSystemService(PowerManager.class).isIgnoringBatteryOptimizations(c.getPackageName());
        b.battDesc.setText(ign ? "Unrestricted: Android will not stop the tunnel to save power." : "Restricted: Android may stop the tunnel in the background. Allow WG FEC to run without restrictions.");
        b.battBtn.setText(ign ? "Change" : "Allow");
        boolean n = c.getSystemService(NotificationManager.class).areNotificationsEnabled();
        b.notifDesc.setText(n ? "On. The tunnel shows its status in a notification." : "Off. The tunnel still works, but you will not see its notification.");
    }

    @Override public void onDestroyView() { b = null; super.onDestroyView(); }
}
