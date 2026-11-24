package io.github.kurashizu.wgfec;

import android.app.PendingIntent;
import android.content.Intent;
import android.net.VpnService;
import android.os.Build;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;

/** Quick Settings tile: connect or disconnect without opening the app. */
public class ToggleTile extends TileService {
    private final Runnable listener = this::refresh;

    @Override public void onStartListening() { TunnelService.LISTENERS.add(listener); refresh(); }
    @Override public void onStopListening() { TunnelService.LISTENERS.remove(listener); }

    @Override public void onClick() {
        if (TunnelService.running()) {
            startService(new Intent(this, TunnelService.class).setAction(TunnelService.ACTION_STOP));
            return;
        }
        if (Store.active(this) == null || VpnService.prepare(this) != null) {   // needs a profile or the VPN permission: open the app
            Intent i = new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            if (Build.VERSION.SDK_INT >= 34) startActivityAndCollapse(PendingIntent.getActivity(this, 2, i, PendingIntent.FLAG_IMMUTABLE));
            else startActivityAndCollapse(i);
            return;
        }
        startForegroundService(new Intent(this, TunnelService.class).setAction(TunnelService.ACTION_START));
    }

    private void refresh() {
        Tile t = getQsTile();
        if (t == null) return;
        TunnelService.St s = TunnelService.state;
        t.setState(s == TunnelService.St.CONNECTED || s == TunnelService.St.CONNECTING ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE);
        t.setLabel("WG FEC");
        if (Build.VERSION.SDK_INT >= 29)
            t.setSubtitle(s == TunnelService.St.CONNECTED ? "Connected" : s == TunnelService.St.CONNECTING ? "Connecting…" : "Off");
        t.updateTile();
    }
}
