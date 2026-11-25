package io.github.kurashizu.wgfec;

import android.content.res.ColorStateList;
import android.widget.TextView;

import androidx.core.content.ContextCompat;

final class Ui {
    /** The badge for a stored profile: its mode, with QUIC when the profile sends its tunnel as QUIC. */
    static void modeBadge(TextView v, Profile p, String profileId) {
        modeBadge(v, p, p != null && p.hasQuic() && Tuning.quic(v.getContext(), profileId));
    }

    /** Fills a pill-shaped TextView with the profile's mode: QUIC in purple, FEC in green, Direct in blue, a bad profile in red. */
    static void modeBadge(TextView v, Profile p, boolean quic) {
        int bg, fg;
        String t;
        if (p == null) { t = "Invalid"; bg = R.color.status_err; fg = R.color.mode_fec_fg; }
        else if (quic) { t = "QUIC"; bg = R.color.mode_quic_bg; fg = R.color.mode_quic_fg; }
        else if (p.hasFec()) { t = "FEC"; bg = R.color.mode_fec_bg; fg = R.color.mode_fec_fg; }
        else { t = "Direct"; bg = R.color.mode_direct_bg; fg = R.color.mode_direct_fg; }
        v.setText(t);
        v.setTextColor(ContextCompat.getColor(v.getContext(), fg));
        v.setBackgroundTintList(ColorStateList.valueOf(ContextCompat.getColor(v.getContext(), bg)));
    }
}
