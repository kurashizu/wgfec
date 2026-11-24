package io.github.kurashizu.wgfec;

import android.content.res.ColorStateList;
import android.widget.TextView;

import androidx.core.content.ContextCompat;

final class Ui {
    /** Fills a pill-shaped TextView with the profile's mode: FEC in green, Direct in blue, a bad profile in red. */
    static void modeBadge(TextView v, Profile p) {
        int bg, fg;
        String t;
        if (p == null) { t = "Invalid"; bg = R.color.status_err; fg = R.color.mode_fec_fg; }
        else if (p.hasFec()) { t = "FEC"; bg = R.color.mode_fec_bg; fg = R.color.mode_fec_fg; }
        else { t = "Direct"; bg = R.color.mode_direct_bg; fg = R.color.mode_direct_fg; }
        v.setText(t);
        v.setTextColor(ContextCompat.getColor(v.getContext(), fg));
        v.setBackgroundTintList(ColorStateList.valueOf(ContextCompat.getColor(v.getContext(), bg)));
    }
}
