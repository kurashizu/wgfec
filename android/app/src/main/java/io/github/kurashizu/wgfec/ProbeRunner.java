package io.github.kurashizu.wgfec;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Runs one link test at a time on a background thread. The state lives here (not in the fragment) so the test keeps
 * going, and the screen shows it again, when you switch tabs. The app is excluded from its own VPN, so the packets go
 * straight to the server whether or not the tunnel is up.
 */
final class ProbeRunner {
    static final List<Runnable> LISTENERS = new CopyOnWriteArrayList<>();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    static volatile boolean running;
    static volatile String host = "", stage = "", profileName = "";
    static volatile int port, phase, phases, sent, total, echoes;
    static volatile String error;
    /** What the last test switched the FEC level to, or why it did not. Null when the test was not meant to tune. */
    static volatile String tuneMsg;
    static volatile boolean cancelled;
    static volatile List<LinkProbe.Result> results = new ArrayList<>();
    private static LinkProbe probe;

    /** Runs a test. With a profile (and its id), the FEC level of that profile is chosen from the result and applied. */
    static synchronized boolean start(Context ctx, String profileId, String name, Profile profile, List<LinkProbe.Phase> plan) {
        if (running) return false;
        Context app = ctx.getApplicationContext();
        running = true; cancelled = false; error = null; tuneMsg = null; results = new ArrayList<>();
        String h = profile.serverHost();
        int p = profile.linkTestPort();
        host = h; port = p; profileName = name; phase = 0; phases = plan.size(); sent = total = echoes = 0; stage = "starting";
        LinkProbe lp = probe = new LinkProbe();
        Thread t = new Thread(() -> {
            try {
                lp.run(h, p, plan, new LinkProbe.Listener() {
                    @Override public void onProgress(int ph, int phs, String st, int s, int tot, int e) {
                        phase = ph; phases = phs; stage = st; sent = s; total = tot; echoes = e;
                        changed();
                    }
                    @Override public void onPhase(int ph, LinkProbe.Result r) {
                        List<LinkProbe.Result> l = new ArrayList<>(results);
                        l.add(r);
                        results = l;
                        changed();
                    }
                });
            } catch (java.net.UnknownHostException e) {
                error = "Cannot resolve \"" + h + "\".";
            } catch (Exception e) {
                error = e.getMessage() != null ? e.getMessage() : e.toString();
            } finally {
                if (profile.hasTiers() && !cancelled && error == null && !results.isEmpty()) tune(app, profileId, profile);
                running = false;
                changed();
            }
        }, "linkprobe");
        t.setDaemon(true);
        t.start();
        changed();
        return true;
    }

    private static void tune(Context ctx, String id, Profile p) {
        List<LinkProbe.Result> rs = results;
        if (!ProbeReport.measured(rs)) { tuneMsg = "The FEC level was not changed: the server's record did not arrive, so the two directions could not be told apart."; return; }
        double lu = ProbeReport.lossOf(rs, true), ld = ProbeReport.lossOf(rs, false);
        int up = FecTuner.pick(p.tiers, lu, ProbeReport.burstyDir(rs, true)), down = FecTuner.pick(p.tiers, ld, ProbeReport.burstyDir(rs, false));
        if (up < 0 || down < 0) return;
        Tuning.set(ctx, id, up, down);
        Store.Item act = Store.active(ctx);
        boolean live = TunnelService.running() && act != null && act.id.equals(id);      // only the profile that is connected restarts its helper
        if (live) TunnelService.retune(ctx);
        tuneMsg = String.format(java.util.Locale.US, "Switched to downlink %s (measured %.2f %%) and uplink %s (measured %.2f %%).%s",
                p.tiers.get(down).name, ld, p.tiers.get(up).name, lu, live ? " The FEC helper was restarted with the new settings; the tunnel stayed up." : " It applies the next time you connect.");
    }

    static void stop() {
        cancelled = true;
        LinkProbe lp = probe;
        if (lp != null) lp.cancel();
    }

    private static void changed() {
        MAIN.post(() -> { for (Runnable r : LISTENERS) r.run(); });
    }
}
