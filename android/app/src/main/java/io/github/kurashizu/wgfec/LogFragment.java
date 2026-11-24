package io.github.kurashizu.wgfec;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import io.github.kurashizu.wgfec.databinding.FragmentLogBinding;

public class LogFragment extends Fragment {
    private FragmentLogBinding b;
    private final Handler h = new Handler(Looper.getMainLooper());
    private final Runnable listener = () -> h.post(this::render);

    @Nullable @Override
    public View onCreateView(@NonNull LayoutInflater inf, @Nullable ViewGroup c, @Nullable Bundle s) {
        b = FragmentLogBinding.inflate(inf, c, false);
        b.copy.setOnClickListener(v -> {
            ((ClipboardManager) requireContext().getSystemService(Context.CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText("log", TunnelService.logText()));
            Toast.makeText(requireContext(), "Log copied", Toast.LENGTH_SHORT).show();
        });
        b.share.setOnClickListener(v -> startActivity(Intent.createChooser(new Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, TunnelService.logText()), "Share log")));
        b.clear.setOnClickListener(v -> TunnelService.clearLog());
        return b.getRoot();
    }

    @Override public void onStart() { super.onStart(); TunnelService.LISTENERS.add(listener); render(); }
    @Override public void onStop() { TunnelService.LISTENERS.remove(listener); super.onStop(); }
    @Override public void onDestroyView() { b = null; super.onDestroyView(); }

    private void render() {
        if (b == null) return;
        String t = TunnelService.logText();
        b.text.setText(t.isEmpty() ? "Nothing yet. The log fills in when you connect." : t);
        b.scroll.post(() -> { if (b != null) b.scroll.fullScroll(View.FOCUS_DOWN); });
    }
}
