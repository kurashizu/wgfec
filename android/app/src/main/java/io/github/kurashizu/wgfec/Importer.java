package io.github.kurashizu.wgfec;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.Toast;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

/** Adding a profile: from the clipboard, from a wgfec:// link, or typed in. */
final class Importer {
    static void fromClipboard(Activity a, Runnable done) {
        ClipData d = ((ClipboardManager) a.getSystemService(Context.CLIPBOARD_SERVICE)).getPrimaryClip();
        String t = d == null || d.getItemCount() == 0 ? "" : d.getItemAt(0).coerceToText(a).toString();
        if (t.trim().isEmpty()) { Toast.makeText(a, "The clipboard is empty. Copy the app string on the admin page first.", Toast.LENGTH_LONG).show(); return; }
        offer(a, t, done);
    }

    /** text is an app string (wgfec://...) or a plain WireGuard config, optionally followed by the speederv2 command. */
    static void offer(Activity a, String text, Runnable done) {
        String name, profileText;
        Profile p;
        try {
            if (AppString.looksLike(text)) {
                AppString q = AppString.parse(text);
                name = q.name; profileText = q.profileText;
            } else {
                name = "Profile"; profileText = text.trim() + "\n";
            }
            p = Profile.parse(profileText, a.getPackageName());
        } catch (Exception e) {
            new MaterialAlertDialogBuilder(a).setTitle("Cannot import").setMessage(e.getMessage()).setPositiveButton("OK", null).show();
            return;
        }
        TextInputLayout box = new TextInputLayout(a);
        TextInputEditText field = new TextInputEditText(box.getContext());
        field.setSingleLine(true);
        field.setText(name);
        box.setHint("Name");
        box.addView(field);
        int pad = (int) (24 * a.getResources().getDisplayMetrics().density);
        FrameLayout wrap = new FrameLayout(a);
        wrap.setPadding(pad, pad / 3, pad, 0);
        wrap.addView(box, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        final String finalText = profileText;
        new MaterialAlertDialogBuilder(a)
                .setTitle("Add profile")
                .setMessage(p.describe())
                .setView(wrap)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Add", (d, w) -> {
                    Store.Item it = Store.add(a, String.valueOf(field.getText()), finalText);
                    Toast.makeText(a, "Added \"" + it.name + "\"", Toast.LENGTH_SHORT).show();
                    if (done != null) done.run();
                }).show();
    }

    static void manual(Activity a, Runnable done) {
        TextInputLayout box = new TextInputLayout(a);
        TextInputEditText field = new TextInputEditText(box.getContext());
        field.setMinLines(6);
        field.setMaxLines(10);
        field.setGravity(android.view.Gravity.TOP);
        field.setTypeface(android.graphics.Typeface.MONOSPACE);
        field.setTextSize(12);
        box.setHint("App string, or WireGuard config + speederv2 command");
        box.addView(field);
        int pad = (int) (24 * a.getResources().getDisplayMetrics().density);
        FrameLayout wrap = new FrameLayout(a);
        wrap.setPadding(pad, pad / 3, pad, 0);
        wrap.addView(box, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        new MaterialAlertDialogBuilder(a)
                .setTitle("Enter manually")
                .setView(wrap)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Next", (d, w) -> offer(a, String.valueOf(field.getText()), done))
                .show();
    }
}
