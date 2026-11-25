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
    static volatile String host = "", stage = "";
    static volatile int port, phase, phases, sent, total, echoes;
    static volatile String error;
    /** What the last test switched the FEC level to, or why it did not. Null when the test was not meant to tune. */
    static volatile String tuneMsg;
    static volatile boolean cancelled;
    static volatile List<LinkProbe.Result> results = new ArrayList<>();
    private static LinkProbe probe;

    /** Runs a test. With a profile (and its id), the FEC level of that profile is chosen from the result and applied. */
    static synchronized boolean start(Context ctx, String h, int p, List<LinkProbe.Phase> plan, String tuneId, Profile tuneProfile) {
        if (running) return false;
        Context app = ctx.getApplicationContext();
        running = true; cancelled = false; error = null; tuneMsg = null; results = new ArrayList<>();
        host = h; port = p; phase = 0; phases = plan.size(); sent = total = echoes = 0; stage = "starting";
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
                if (tuneId != null && !cancelled && error == null && !results.isEmpty()) tune(app, tuneId, tuneProfile, h);
                running = false;
                changed();
            }
        }, "linkprobe");
        t.setDaemon(true);
        t.start();
        changed();
        return true;
    }

    private static String hostOf(String hostPort) {
        if (hostPort.startsWith("[")) { int e = hostPort.indexOf(']'); return e > 0 ? hostPort.substring(1, e) : hostPort; }
        int i = hostPort.lastIndexOf(':');
        return i > 0 ? hostPort.substring(0, i) : hostPort;
    }

    private static void tune(Context ctx, String id, Profile p, String testedHost) {
        List<LinkProbe.Result> rs = results;
        try {   // the result only describes this profile's path if both names are the same server
            if (!LinkProbe.resolve(testedHost).equals(LinkProbe.resolve(hostOf(p.server)))) {
                tuneMsg = "The FEC level was not changed: the tested server is not the profile's server (" + hostOf(p.server) + ").";
                return;
            }
        } catch (java.io.IOException e) { tuneMsg = "The FEC level was not changed: could not resolve the profile's server."; return; }
        if (!ProbeReport.measured(rs)) { tuneMsg = "The FEC level was not changed: the server's record did not arrive, so the two directions could not be told apart."; return; }
        double lu = ProbeReport.lossOf(rs, true), ld = ProbeReport.lossOf(rs, false);
        int up = FecTuner.pick(p.tiers, lu, ProbeReport.burstyDir(rs, true)), down = FecTuner.pick(p.tiers, ld, ProbeReport.burstyDir(rs, false));
        if (up < 0 || down < 0) return;
        Tuning.set(ctx, id, up, down);
        boolean live = TunnelService.running();
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
