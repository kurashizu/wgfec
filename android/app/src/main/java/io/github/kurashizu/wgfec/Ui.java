package io.github.kurashizu.wgfec;

import android.content.res.ColorStateList;
import android.widget.TextView;

import com.google.android.material.color.MaterialColors;

final class Ui {
    /** Fills a pill-shaped TextView with the profile's mode: FEC in the accent colour, Direct in a neutral one. */
    static void modeBadge(TextView v, Profile p) {
        int bgAttr, fgAttr;
        String t;
        if (p == null) {
            t = "Invalid"; bgAttr = com.google.android.material.R.attr.colorErrorContainer; fgAttr = com.google.android.material.R.attr.colorOnErrorContainer;
        } else if (p.hasFec()) {
            t = "FEC"; bgAttr = com.google.android.material.R.attr.colorPrimaryContainer; fgAttr = com.google.android.material.R.attr.colorOnPrimaryContainer;
        } else {
            t = "Direct"; bgAttr = com.google.android.material.R.attr.colorSecondaryContainer; fgAttr = com.google.android.material.R.attr.colorOnSecondaryContainer;
        }
        v.setText(t);
        v.setTextColor(MaterialColors.getColor(v, fgAttr));
        v.setBackgroundTintList(ColorStateList.valueOf(MaterialColors.getColor(v, bgAttr)));
    }
}
