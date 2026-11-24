package io.github.kurashizu.wgfec;

import java.util.Locale;

final class Fmt {
    static String bytes(long b) {
        if (b < 1024) return b + " B";
        double v = b; String[] u = {"KB", "MB", "GB", "TB"}; int i = -1;
        do { v /= 1024; i++; } while (v >= 1024 && i < u.length - 1);
        return String.format(Locale.US, v >= 100 ? "%.0f %s" : "%.1f %s", v, u[i]);
    }

    static String duration(long ms) {
        long s = Math.max(0, ms / 1000), h = s / 3600, m = (s % 3600) / 60;
        return h > 0 ? String.format(Locale.US, "%d:%02d:%02d", h, m, s % 60) : String.format(Locale.US, "%02d:%02d", m, s % 60);
    }

    static String ago(long epochMs) {
        if (epochMs <= 0) return "none yet";
        long s = Math.max(0, (System.currentTimeMillis() - epochMs) / 1000);
        if (s < 60) return s + " s ago";
        if (s < 3600) return (s / 60) + " min ago";
        return (s / 3600) + " h ago";
    }
}
