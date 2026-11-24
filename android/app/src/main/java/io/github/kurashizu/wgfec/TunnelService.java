package io.github.kurashizu.wgfec;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;

import com.wireguard.android.backend.GoBackend;
import com.wireguard.android.backend.Statistics;
import com.wireguard.android.backend.Tunnel;
import com.wireguard.config.Config;
import com.wireguard.crypto.Key;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

/** Foreground service: starts the speederv2 helper, then brings the WireGuard tunnel up through it. */
public class TunnelService extends Service {
    enum St { STOPPED, CONNECTING, CONNECTED, ERROR }

    static final String ACTION_START = "start", ACTION_STOP = "stop";
    static volatile St state = St.STOPPED;
    static volatile String error = "";
    static volatile String profileName = "";
    static volatile long since, rx, tx, handshake;
    static final CopyOnWriteArrayList<Runnable> LISTENERS = new CopyOnWriteArrayList<>();

    private static final ArrayDeque<String> LOG = new ArrayDeque<>();
    private static final SimpleDateFormat TS = new SimpleDateFormat("HH:mm:ss", Locale.US);
    private static GoBackend backend;

    private volatile boolean stopping;
    private volatile Process proc;
    private Thread worker, monitor;

    private final Tunnel tunnel = new Tunnel() {
        @Override public String getName() { return "wgfec"; }
        @Override public void onStateChange(State s) { log("WireGuard tunnel " + s); }
    };

    static boolean running() { return state == St.CONNECTING || state == St.CONNECTED; }

    static void log(String s) {
        synchronized (LOG) {
            LOG.add(TS.format(new Date()) + "  " + s);
            while (LOG.size() > 400) LOG.removeFirst();
        }
        poke();
    }

    static String logText() { synchronized (LOG) { return String.join("\n", LOG); } }
    static void clearLog() { synchronized (LOG) { LOG.clear(); } poke(); }

    static void poke() { for (Runnable r : LISTENERS) r.run(); }

    private void setState(St s, String err) {
        state = s;
        error = err == null ? "" : err;
        if (s == St.CONNECTED) since = System.currentTimeMillis();
        if (s == St.CONNECTING) { rx = tx = handshake = 0; }
        poke();
        if (running()) notifyOngoing();
    }

    @Override public IBinder onBind(Intent i) { return null; }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? ACTION_STOP : intent.getAction();
        if (ACTION_START.equals(action)) {
            Store.Item it = Store.active(this);
            if (running()) return START_NOT_STICKY;
            if (it == null) { state = St.ERROR; error = "No profile selected."; poke(); stopSelf(); return START_NOT_STICKY; }
            profileName = it.name;
            goForeground();
            stopping = false;
            setState(St.CONNECTING, null);
            worker = new Thread(() -> startAll(it), "wgfec-start");
            worker.start();
        } else {
            new Thread(() -> stopAll(null), "wgfec-stop").start();
        }
        return START_NOT_STICKY;
    }

    private Notification buildNotification() {
        String text = state == St.CONNECTED ? "Connected · " + profileName : "Connecting · " + profileName;
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stop = PendingIntent.getService(this, 1, new Intent(this, TunnelService.class).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, "tunnel")
                .setContentTitle("WG FEC")
                .setContentText(text)
                .setSmallIcon(R.drawable.ic_stat)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setContentIntent(open)
                .addAction(new Notification.Action.Builder(null, "Disconnect", stop).build())
                .build();
    }

    private void notifyOngoing() {
        getSystemService(NotificationManager.class).notify(1, buildNotification());
    }

    private void goForeground() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel("tunnel", "Tunnel status", NotificationManager.IMPORTANCE_LOW));
        Notification n = buildNotification();
        if (Build.VERSION.SDK_INT >= 34) startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        else startForeground(1, n);
    }

    private void startAll(Store.Item it) {
        try {
            log("Connecting with profile \"" + it.name + "\"");
            Profile p = Profile.parse(it.text, getPackageName());
            for (String n : p.notes) log(n);
            if (p.hasFec()) {
                startSpeeder(p.speederArgs);
                Thread.sleep(800);
                if (proc == null || !proc.isAlive()) throw new Exception("speederv2 stopped right away. See the log for why.");
            }
            Config cfg = Config.parse(new ByteArrayInputStream(p.wgConfig.getBytes(StandardCharsets.UTF_8)));
            if (backend == null) backend = new GoBackend(getApplicationContext());
            backend.setState(tunnel, Tunnel.State.UP, cfg);
            if (stopping) return;
            setState(St.CONNECTED, null);
            startMonitor();
        } catch (Throwable e) {
            String m = e.getMessage() == null ? e.toString() : e.getMessage();
            log("Error: " + m);
            stopAll(m);
        }
    }

    private void startMonitor() {
        monitor = new Thread(() -> {
            while (!stopping) {
                try { Thread.sleep(2000); } catch (InterruptedException e) { return; }
                try {
                    if (backend.getState(tunnel) != Tunnel.State.UP) {
                        log("The VPN was turned off from outside the app.");
                        stopAll("The VPN was turned off by Android or another VPN app.");
                        return;
                    }
                    Statistics st = backend.getStatistics(tunnel);
                    rx = st.totalRx(); tx = st.totalTx();
                    long hs = 0;
                    for (Key k : st.peers()) hs = Math.max(hs, st.peer(k).latestHandshakeEpochMillis());
                    handshake = hs;
                    poke();
                } catch (Throwable ignored) { }
            }
        }, "wgfec-monitor");
        monitor.start();
    }

    private void startSpeeder(List<String> args) {
        List<String> cmd = new ArrayList<>();
        cmd.add(new File(getApplicationInfo().nativeLibraryDir, "libspeederv2.so").getAbsolutePath());
        cmd.addAll(args);
        if (!args.contains("--disable-color")) cmd.add("--disable-color");
        new Thread(() -> {
            while (!stopping) {
                try {
                    log("Starting speederv2");
                    proc = new ProcessBuilder(cmd).redirectErrorStream(true).start();
                    try (BufferedReader r = new BufferedReader(new InputStreamReader(proc.getInputStream()))) {
                        String line;
                        while ((line = r.readLine()) != null) log("speeder: " + line);
                    }
                    log("speederv2 exited with code " + proc.waitFor());
                } catch (Exception e) {
                    log("speederv2 failed: " + e);
                }
                if (stopping) break;
                try { Thread.sleep(2000); } catch (InterruptedException e) { break; }
            }
        }, "wgfec-speeder").start();
    }

    private synchronized void stopAll(String err) {
        if (stopping && !running()) return;
        stopping = true;
        try { if (backend != null) backend.setState(tunnel, Tunnel.State.DOWN, null); }
        catch (Throwable e) { log("Stopping the tunnel failed: " + e.getMessage()); }
        Process p = proc;
        if (p != null) {
            p.destroy();
            try { if (!p.waitFor(2, TimeUnit.SECONDS)) p.destroyForcibly(); } catch (InterruptedException ignored) { }
        }
        if (monitor != null) monitor.interrupt();
        if (err != null) setState(St.ERROR, err); else setState(St.STOPPED, null);
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    @Override public void onDestroy() {
        stopping = true;
        Process p = proc;
        if (p != null) p.destroyForcibly();
        if (running()) { state = St.STOPPED; poke(); }
        super.onDestroy();
    }
}
