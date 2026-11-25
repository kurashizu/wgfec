package io.github.kurashizu.wgfec;

import android.content.Context;
import android.content.SharedPreferences;

/** Which FEC level (uplink and downlink) a profile is tuned to. Chosen by the link test, or none (the profile's own settings). */
final class Tuning {
    static final class Choice {
        final int up, down;
        final long at;
        Choice(int up, int down, long at) { this.up = up; this.down = down; this.at = at; }
    }

    private static SharedPreferences sp(Context c) { return c.getSharedPreferences("p", Context.MODE_PRIVATE); }

    static Choice get(Context c, String profileId) {
        String v = sp(c).getString("tier_" + profileId, "");
        String[] f = v.split(",");
        try { return f.length == 3 ? new Choice(Integer.parseInt(f[0]), Integer.parseInt(f[1]), Long.parseLong(f[2])) : null; }
        catch (NumberFormatException e) { return null; }
    }

    static void set(Context c, String profileId, int up, int down) {
        sp(c).edit().putString("tier_" + profileId, up + "," + down + "," + System.currentTimeMillis()).apply();
    }

    static void clear(Context c, String profileId) { sp(c).edit().remove("tier_" + profileId).apply(); }

    /** "Downlink Light, uplink Aggressive", or null when the profile is not tuned. */
    static String label(Profile p, Choice ch) {
        if (p == null || ch == null || !p.hasTiers() || ch.up >= p.tiers.size() || ch.down >= p.tiers.size()) return null;
        return "down " + p.tiers.get(ch.down).name + ", up " + p.tiers.get(ch.up).name;
    }
}
