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
    /** What the last test suggests for one profile (indexes into its levels, -1 for none), and the text to show. Never applied by itself. */
    static volatile String suggestId = "", suggestMsg;
    static volatile int suggestUp = -1, suggestDown = -1;
    static volatile boolean cancelled, quic;
    /** The name written into the fake QUIC Initial. It does not exist: nothing resolves or checks it. */
    static final String QUIC_SNI = "www.kestrelmoor.com.au";
    static volatile List<LinkProbe.Result> results = new ArrayList<>();
    private static LinkProbe probe;

    /** Runs a test. With a profile (and its id), the FEC level of that profile is chosen from the result and applied. */
    static synchronized boolean start(Context ctx, String profileId, String name, Profile profile, List<LinkProbe.Phase> plan) {
        return start(ctx, profileId, name, profile, plan, false, 0);
    }

    /** {@code quicShape}: dress the packets up as QUIC. {@code portOverride} > 0 tests that port instead of the profile's. */
    static synchronized boolean start(Context ctx, String profileId, String name, Profile profile, List<LinkProbe.Phase> plan,
                                      boolean quicShape, int portOverride) {
        if (running) return false;
        running = true; cancelled = false; error = null; suggestId = ""; suggestMsg = null; suggestUp = suggestDown = -1; results = new ArrayList<>();
        String h = profile.serverHost();
        int p = portOverride > 0 ? portOverride : profile.linkTestPort();
        quic = quicShape;
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
                }, quicShape ? QUIC_SNI : null);
            } catch (java.net.UnknownHostException e) {
                error = "Cannot resolve \"" + h + "\".";
            } catch (Exception e) {
                error = e.getMessage() != null ? e.getMessage() : e.toString();
            } finally {
                if (profile.hasTiers() && !quicShape && !cancelled && error == null && !results.isEmpty()) suggest(profileId, profile);
                running = false;
                changed();
            }
        }, "linkprobe");
        t.setDaemon(true);
        t.start();
        changed();
        return true;
    }

    /** A level for each direction that fits what the test measured. It is only offered to the user, never applied. */
    private static void suggest(String id, Profile p) {
        List<LinkProbe.Result> rs = results;
        suggestId = id; suggestUp = suggestDown = -1; suggestMsg = null;
        if (!ProbeReport.measured(rs)) { suggestMsg = "No suggestion: the server's record did not arrive, so the two directions could not be told apart."; return; }
        double lu = ProbeReport.lossOf(rs, true), ld = ProbeReport.lossOf(rs, false);
        suggestUp = FecTuner.pick(p.tiers, lu, ProbeReport.burstyDir(rs, true));
        suggestDown = FecTuner.pick(p.tiers, ld, ProbeReport.burstyDir(rs, false));
        if (suggestUp < 0 || suggestDown < 0) { suggestUp = suggestDown = -1; return; }
        suggestMsg = String.format(java.util.Locale.US, "Suggested by this test: downlink %s (measured %.2f %%), uplink %s (measured %.2f %%).",
                p.tiers.get(suggestDown).name, ld, p.tiers.get(suggestUp).name, lu);
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
