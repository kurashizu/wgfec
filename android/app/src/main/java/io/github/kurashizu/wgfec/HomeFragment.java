package io.github.kurashizu.wgfec;

import android.animation.ObjectAnimator;
import android.content.res.ColorStateList;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.LinearInterpolator;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;

import io.github.kurashizu.wgfec.databinding.FragmentHomeBinding;

public class HomeFragment extends Fragment {
    private FragmentHomeBinding b;
    private final Handler h = new Handler(Looper.getMainLooper());
    private final Runnable listener = () -> h.post(this::render);
    private final Runnable ticker = new Runnable() { @Override public void run() { render(); h.postDelayed(this, 1000); } };
    private ObjectAnimator pulse;

    @Nullable @Override
    public View onCreateView(@NonNull LayoutInflater inf, @Nullable ViewGroup c, @Nullable Bundle s) {
        b = FragmentHomeBinding.inflate(inf, c, false);
        b.powerBtn.setOnClickListener(v -> ((MainActivity) requireActivity()).toggle());
        b.change.setOnClickListener(v -> ((MainActivity) requireActivity()).select(R.id.nav_profiles));
        b.pasteFirst.setOnClickListener(v -> Importer.fromClipboard(requireActivity(), this::render));
        b.errLog.setOnClickListener(v -> ((MainActivity) requireActivity()).openLog());
        return b.getRoot();
    }

    @Override public void onStart() {
        super.onStart();
        TunnelService.LISTENERS.add(listener);
        h.post(ticker);
    }

    @Override public void onStop() {
        TunnelService.LISTENERS.remove(listener);
        h.removeCallbacks(ticker);
        if (pulse != null) { pulse.cancel(); pulse = null; }
        super.onStop();
    }

    @Override public void onDestroyView() { b = null; super.onDestroyView(); }

    private void render() {
        if (b == null || !isAdded()) return;
        TunnelService.St s = TunnelService.state;
        Store.Item it = Store.active(requireContext());
        int col, colRes;
        String title, sub;
        switch (s) {
            case CONNECTED:
                colRes = R.color.status_on; title = "Connected";
                sub = "Up for " + Fmt.duration(System.currentTimeMillis() - TunnelService.since) + (TunnelService.profileMode.isEmpty() ? "" : " · " + TunnelService.profileMode);
                if (TunnelService.handshake == 0 && System.currentTimeMillis() - TunnelService.since > 12000)
                    sub = "No reply from the server yet. Check the log.";
                break;
            case CONNECTING:
                colRes = R.color.status_busy; title = "Connecting…"; sub = "Tap to cancel";
                break;
            case ERROR:
                colRes = R.color.status_err; title = "Could not connect"; sub = "Tap to try again";
                break;
            default:
                colRes = R.color.status_off; title = "Disconnected";
                sub = it == null ? "Add a profile to get started" : "Tap to connect";
        }
        col = ContextCompat.getColor(requireContext(), colRes);
        b.powerBtn.setBackgroundTintList(ColorStateList.valueOf(col));
        b.halo.setBackgroundTintList(ColorStateList.valueOf(col));
        b.title.setText(title);
        b.sub.setText(sub);

        if (s == TunnelService.St.CONNECTING) {
            if (pulse == null) {
                pulse = ObjectAnimator.ofFloat(b.halo, "alpha", 0.08f, 0.38f);
                pulse.setDuration(800); pulse.setRepeatCount(ObjectAnimator.INFINITE); pulse.setRepeatMode(ObjectAnimator.REVERSE);
                pulse.setInterpolator(new LinearInterpolator());
                pulse.start();
            }
        } else {
            if (pulse != null) { pulse.cancel(); pulse = null; }
            b.halo.setAlpha(s == TunnelService.St.CONNECTED ? 0.22f : 0.12f);
        }

        boolean err = s == TunnelService.St.ERROR && !TunnelService.error.isEmpty();
        b.errCard.setVisibility(err ? View.VISIBLE : View.GONE);
        if (err) b.errText.setText(TunnelService.error);

        b.profileCard.setVisibility(it == null ? View.GONE : View.VISIBLE);
        b.emptyCard.setVisibility(it == null ? View.VISIBLE : View.GONE);
        if (it != null) {
            b.profName.setText(it.name);
            Profile p = it.parsed();
            b.profInfo.setText(p == null ? "This profile is not valid." : p.detail());
            Ui.modeBadge(b.profMode, p, it.id);
            // FEC level of a profile that offers several: what it is set to, and a tap opens its link test to change it
            if (p != null && (p.hasTiers() || p.hasQuic())) {
                String lab = Tuning.label(p, Tuning.get(requireContext(), it.id));
                String line = p.hasTiers() ? "FEC level: " + (lab == null ? "profile's own settings" : lab) : "";
                if (p.hasQuic()) line += (line.isEmpty() ? "" : " · ") + "QUIC disguise: " + (Tuning.quic(requireContext(), it.id) ? "on" : "off");
                b.profLevel.setText(line);
                b.profLevel.setVisibility(View.VISIBLE);
                b.profLevel.setOnClickListener(v -> ((MainActivity) requireActivity()).openLinkTest(it.id));
            } else b.profLevel.setVisibility(View.GONE);
        }
        b.change.setEnabled(!TunnelService.running());

        b.statsCard.setVisibility(s == TunnelService.St.CONNECTED ? View.VISIBLE : View.GONE);
        if (s == TunnelService.St.CONNECTED) {
            b.rx.setText(Fmt.bytes(TunnelService.rx));
            b.tx.setText(Fmt.bytes(TunnelService.tx));
            b.hs.setText(Fmt.ago(TunnelService.handshake));
        }
    }
}
