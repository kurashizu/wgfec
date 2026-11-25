package io.github.kurashizu.wgfec;

import java.util.ArrayList;
import java.util.List;

/**
 * Picks the FEC level that fits a measured loss rate, and rewrites the speederv2 client arguments for the picked levels.
 * Pure Java, no Android classes.
 *
 * A server offers several levels (weakest first). Each runs on its own UDP port. The downlink strength is decided by
 * which port the client connects to; the uplink strength by the client's own -f. So the two directions are chosen
 * independently, from their own measured loss.
 */
final class FecTuner {
    /** One FEC level as the server publishes it (see the "tiers" field of the app string). */
    static final class Tier {
        final String name, upFec;
        final int loss, port, upTimeout, upInterval, decodeBuf, sockBuf;

        Tier(String name, int loss, int port, String upFec, int upTimeout, int upInterval, int decodeBuf, int sockBuf) {
            this.name = name; this.loss = loss; this.port = port; this.upFec = upFec;
            this.upTimeout = upTimeout; this.upInterval = upInterval; this.decodeBuf = decodeBuf; this.sockBuf = sockBuf;
        }
    }

    /**
     * Index of the weakest level that still covers this loss with some margin. A level is meant for "up to about N %" of
     * loss, so asking for 1.6 times the measured loss (and at least 1 point more) keeps a bad minute from breaking it.
     * Losses that come in bursts are harder to repair, so they move the choice up one level.
     */
    static int pick(List<Tier> tiers, double lossPct, boolean bursty) {
        if (tiers.isEmpty()) return -1;
        int pick = tiers.size() - 1;
        if (lossPct < 0.2) pick = 0;
        else {
            double need = Math.max(lossPct * 1.6, lossPct + 1.0);
            for (int i = 0; i < tiers.size(); i++) if (tiers.get(i).loss >= need) { pick = i; break; }
        }
        if (bursty && pick < tiers.size() - 1) pick++;
        return pick;
    }

    /**
     * The client arguments with the uplink settings of {@code up} and the server port of {@code down}. A null level leaves
     * that direction as the arguments already have it.
     */
    static List<String> applyTiers(List<String> args, Tier up, Tier down) {
        List<String> out = new ArrayList<>(args);
        if (down != null) {
            String remote = valueOf(out, "-r");
            String host = remote == null ? null : remote.contains(":") ? remote.substring(0, remote.lastIndexOf(':')) : remote;
            if (host != null) setFlag(out, "-r", host + ":" + down.port);
            setFlag(out, "--decode-buf", String.valueOf(down.decodeBuf));
            setFlag(out, "--sock-buf", String.valueOf(down.sockBuf));
        }
        if (up != null) {
            setFlag(out, "-f", up.upFec);
            setFlag(out, "--timeout", String.valueOf(up.upTimeout));
            setFlag(out, "-i", String.valueOf(up.upInterval));
        }
        return out;
    }

    static String valueOf(List<String> a, String flag) {
        for (int i = 0; i < a.size(); i++) {
            String x = a.get(i);
            if (x.equals(flag)) return i + 1 < a.size() ? a.get(i + 1) : null;
            if (isShort(flag) && x.startsWith(flag) && !x.startsWith("--") && x.length() > 2) return x.substring(2);
        }
        return null;
    }

    private static boolean isShort(String flag) { return flag.length() == 2 && flag.charAt(0) == '-' && flag.charAt(1) != '-'; }

    private static void setFlag(List<String> a, String flag, String value) {
        for (int i = 0; i < a.size(); i++) {
            String x = a.get(i);
            if (x.equals(flag)) {
                if (i + 1 < a.size()) a.set(i + 1, value); else a.add(value);
                return;
            }
            if (isShort(flag) && x.startsWith(flag) && !x.startsWith("--") && x.length() > 2) { a.set(i, flag + value); return; }
        }
        a.add(flag);
        a.add(value);
    }
}
