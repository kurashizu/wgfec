package io.github.kurashizu.wgfec;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * The FEC level a user picked for a profile: one for the downlink and one for the uplink, or -1 for "as the profile has it".
 * Nothing here changes by itself: the level only changes when the user applies a choice.
 */
final class Tuning {
    static final class Choice {
        final int up, down;
        final long at;
        Choice(int up, int down, long at) { this.up = up; this.down = down; this.at = at; }
    }

    private static SharedPreferences sp(Context c) { return c.getSharedPreferences("p", Context.MODE_PRIVATE); }

    /** The stored choice, or null when the profile uses its own settings in both directions. */
    static Choice get(Context c, String profileId) {
        String v = sp(c).getString("tier_" + profileId, "");
        String[] f = v.split(",");
        try {
            if (f.length != 3) return null;
            Choice ch = new Choice(Integer.parseInt(f[0]), Integer.parseInt(f[1]), Long.parseLong(f[2]));
            return ch.up < 0 && ch.down < 0 ? null : ch;
        } catch (NumberFormatException e) { return null; }
    }

    /** Stores the choice; -1 in a direction means the profile's own settings. Both -1 stores nothing. */
    static void set(Context c, String profileId, int up, int down) {
        if (up < 0 && down < 0) { clear(c, profileId); return; }
        sp(c).edit().putString("tier_" + profileId, up + "," + down + "," + System.currentTimeMillis()).apply();
    }

    static void clear(Context c, String profileId) { sp(c).edit().remove("tier_" + profileId).apply(); }

    /** "down Light, up profile's own", or null when the profile is not set to a level. */
    static String label(Profile p, Choice ch) {
        if (p == null || ch == null || !p.hasTiers()) return null;
        String d = ch.down >= 0 && ch.down < p.tiers.size() ? p.tiers.get(ch.down).name : "profile's own";
        String u = ch.up >= 0 && ch.up < p.tiers.size() ? p.tiers.get(ch.up).name : "profile's own";
        return "down " + d + ", up " + u;
    }
}
