package io.github.kurashizu.wgfec;

import java.util.List;
import java.util.Locale;

/** Turns link test results into text: one block per phase, a verdict, and a plain text export. */
final class ProbeReport {
    static final int[][] FEC_TIERS = {{1, 3}, {2, 4}, {5, 6}, {10, 10}, {20, 20}};

    static String pct(double v) { return Double.isNaN(v) ? "n/a" : String.format(Locale.US, "%.2f %%", v); }

    static String runs(int[] h) {
        if (h == null) return "n/a";
        return String.format(Locale.US, "1:%d 2:%d 3:%d 4:%d 5-9:%d 10+:%d (longest %d)", h[0], h[1], h[2], h[3], h[4], h[5], h[6]);
    }

    static String title(LinkProbe.Result r) {
        return String.format(Locale.US, "%d pps × %d B for %d s", r.pps, r.size, r.seconds);
    }

    static String body(LinkProbe.Result r) {
        StringBuilder s = new StringBuilder();
        if (Double.isNaN(r.upLoss)) {
            s.append(r.note).append('\n');
            s.append("Round trip loss  ").append(pct(r.roundTripLoss)).append(String.format(Locale.US, "  (%d of %d echoes)", r.echoes, r.sent));
            return s.toString();
        }
        s.append("Uplink loss      ").append(pct(r.upLoss)).append("   (you → server)\n");
        s.append("Downlink loss    ").append(pct(r.downLoss)).append("   (server → you)\n");
        s.append("Round trip loss  ").append(pct(r.roundTripLoss)).append(String.format(Locale.US, "   %d of %d echoes\n", r.echoes, r.sent));
        if (!Double.isNaN(r.rttMin))
            s.append(String.format(Locale.US, "RTT ms  min %.1f  p50 %.1f  p95 %.1f  p99 %.1f  max %.1f\nJitter %.2f ms   spikes (> p50 + 50 ms): %d\n",
                    r.rttMin, r.rttP50, r.rttP95, r.rttP99, r.rttMax, r.jitter, r.spikes));
        if (r.upRuns != null) {
            s.append("Lost in a row (uplink)   ").append(runs(r.upRuns)).append('\n');
            s.append("Lost in a row (downlink) ").append(runs(r.downRuns)).append('\n');
        } else {
            s.append("The server's full record did not arrive, so there is no per-direction burst information.\n");
        }
        if (r.reordered > 0) s.append("Reordered echoes: ").append(r.reordered).append('\n');
        if (r.windowRoundTrip != null && r.windowRoundTrip.length > 1) {
            int best = 0, worst = 0;
            for (int i = 1; i < r.windowRoundTrip.length; i++) {
                if (r.windowRoundTrip[i] < r.windowRoundTrip[best]) best = i;
                if (r.windowRoundTrip[i] > r.windowRoundTrip[worst]) worst = i;
            }
            s.append(String.format(Locale.US, "Over time (10 s windows): best %.1f %% at %d s, worst %.1f %% at %d s\n",
                    r.windowRoundTrip[best], best * 10, r.windowRoundTrip[worst], worst * 10));
        }
        return s.toString().trim();
    }

    /** Worst loss in either direction over all phases, in percent (0 when nothing could be measured). */
    static double worst(List<LinkProbe.Result> rs) {
        double w = 0;
        for (LinkProbe.Result r : rs) w = Math.max(w, r.worstOneWay());
        return w;
    }

    static String grade(double worstPct) {
        if (worstPct < 0.5) return "Excellent";
        if (worstPct < 1.5) return "Good";
        if (worstPct < 4) return "Fair";
        if (worstPct < 10) return "Poor";
        return "Bad";
    }

    /** Losses that come in bursts beyond what chance explains (same rule as tools/net-probe/probe.py). */
    static String bursts(List<LinkProbe.Result> rs) {
        StringBuilder out = new StringBuilder();
        for (LinkProbe.Result r : rs)
            for (int i = 0; i < 2; i++) {
                String line = burstLine(r, i);
                if (line != null) out.append(line).append('\n');
            }
        return out.toString().trim();
    }

    /** dir 0 = uplink, 1 = downlink. Null when that direction shows no bursts beyond chance in this phase. */
    private static String burstLine(LinkProbe.Result r, int dir) {
        if (r.upRuns == null) return null;
        int[] runs = dir == 0 ? r.upRuns : r.downRuns;
        double q = (dir == 0 ? r.upLoss : r.downLoss) / 100.0;
        int base = dir == 0 ? r.sent : Math.max(0, r.serverRx);
        if (Double.isNaN(q) || q < 0.005 || base == 0) return null;
        int seen = runs[3] + runs[4] + runs[5];
        double expect = base * (1 - q) * Math.pow(q, 4);
        if (seen <= Math.max(3, 3 * expect)) return null;
        return String.format(Locale.US, "%s: %d runs of 4+ lost packets in a row (about %.1f expected by chance) in the %d pps phase",
                dir == 0 ? "uplink" : "downlink", seen, expect, r.pps);
    }

    static boolean burstyDir(List<LinkProbe.Result> rs, boolean uplink) {
        for (LinkProbe.Result r : rs) if (burstLine(r, uplink ? 0 : 1) != null) return true;
        return false;
    }

    /** True when at least one phase got the server's record, so the loss of each direction is known. */
    static boolean measured(List<LinkProbe.Result> rs) {
        for (LinkProbe.Result r : rs) if (!Double.isNaN(r.upLoss)) return true;
        return false;
    }

    /** Worst loss of one direction over all phases, in percent. */
    static double lossOf(List<LinkProbe.Result> rs, boolean uplink) {
        double w = 0;
        for (LinkProbe.Result r : rs) {
            double v = uplink ? r.upLoss : r.downLoss;
            if (!Double.isNaN(v)) w = Math.max(w, v);
        }
        return w;
    }

    static String verdict(List<LinkProbe.Result> rs) {
        boolean any = false;
        for (LinkProbe.Result r : rs) if (!Double.isNaN(r.upLoss)) any = true;
        if (!any) return "No usable answer from the server, so there is nothing to grade. Check the host and the port, and that the probe service is running.";
        double w = worst(rs);
        StringBuilder s = new StringBuilder();
        s.append(grade(w)).append(String.format(Locale.US, " - worst one-way loss %.2f %%.", w));
        if (w < 0.1) {
            s.append("\nNo loss seen: FEC is not needed on this path right now.");
            return s.toString();
        }
        double p = w / 100.0;
        s.append("\nChance that one FEC group (data:parity) cannot be repaired at this loss rate, if losses are independent:");
        for (int[] t : FEC_TIERS)
            s.append(String.format(Locale.US, "\n  %d:%-2d  (%.2f× bytes)  %.1e", t[0], t[1], (t[0] + t[1]) / (double) t[0], LinkProbe.groupFail(t[0], t[1], p)));
        String b = bursts(rs);
        if (!b.isEmpty()) s.append("\nLosses come in BURSTS beyond chance:\n").append(b).append("\nUse bigger groups spread out in time, and treat the table above as optimistic.");
        return s.toString();
    }

    static String export(String host, int port, List<LinkProbe.Result> rs) {
        StringBuilder s = new StringBuilder();
        s.append("wgfec UDP link test to ").append(host).append(':').append(port).append('\n');
        for (int i = 0; i < rs.size(); i++) {
            s.append("\nPhase ").append(i + 1).append(": ").append(title(rs.get(i))).append('\n').append(body(rs.get(i))).append('\n');
        }
        s.append('\n').append(verdict(rs)).append('\n');
        return s.toString();
    }
}
