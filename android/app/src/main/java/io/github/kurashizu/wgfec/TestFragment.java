package io.github.kurashizu.wgfec;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import io.github.kurashizu.wgfec.databinding.FragmentTestBinding;
import io.github.kurashizu.wgfec.databinding.ItemProbeCardBinding;

/** Measures UDP loss, latency and jitter between the phone and the server, outside the tunnel. */
public class TestFragment extends Fragment {
    static final int DEFAULT_PORT = 62933;
    private static final String[] PRESET_NAMES = {"Quick", "Standard", "Thorough"};
    private static final LinkProbe.Phase[][] PRESETS = {
            {new LinkProbe.Phase(30, 200, 8), new LinkProbe.Phase(300, 1200, 8)},
            {new LinkProbe.Phase(30, 200, 30), new LinkProbe.Phase(300, 1200, 20)},
            {new LinkProbe.Phase(30, 200, 90), new LinkProbe.Phase(300, 1200, 45)},
    };

    private FragmentTestBinding b;
    private final Runnable listener = this::render;

    private SharedPreferences prefs() { return requireContext().getSharedPreferences("p", Context.MODE_PRIVATE); }

    @Nullable @Override
    public View onCreateView(@NonNull LayoutInflater inf, @Nullable ViewGroup c, @Nullable Bundle s) {
        b = FragmentTestBinding.inflate(inf, c, false);
        String host = prefs().getString("probe_host", "");
        int port = prefs().getInt("probe_port", DEFAULT_PORT), preset = prefs().getInt("probe_preset", 0);
        if (host.isEmpty()) host = defaultHost();
        b.host.setText(host);
        b.port.setText(String.valueOf(port));
        b.presets.check(preset == 1 ? R.id.pStandard : preset == 2 ? R.id.pThorough : R.id.pQuick);
        b.presets.setOnCheckedStateChangeListener((g, ids) -> { prefs().edit().putInt("probe_preset", preset()).apply(); renderPlan(); });
        b.start.setOnClickListener(v -> { if (ProbeRunner.running) ProbeRunner.stop(); else start(); });
        b.copy.setOnClickListener(v -> {
            ((ClipboardManager) requireContext().getSystemService(Context.CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText("link test", exportText()));
            Toast.makeText(requireContext(), "Results copied", Toast.LENGTH_SHORT).show();
        });
        b.share.setOnClickListener(v -> startActivity(Intent.createChooser(
                new Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, exportText()), "Share results")));
        b.host.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence c, int a, int n, int d) { }
            @Override public void onTextChanged(CharSequence c, int a, int n, int d) { }
            @Override public void afterTextChanged(android.text.Editable e) { if (b != null && !ProbeRunner.running) b.start.setText(tunable() != null ? "Test and tune" : "Start test"); }
        });
        b.tuneReset.setOnClickListener(v -> {
            Store.Item it = Store.active(requireContext());
            if (it != null) { Tuning.clear(requireContext(), it.id); TunnelService.retune(requireContext()); }
            render();
        });
        renderPlan();
        return b.getRoot();
    }

    @Override public void onStart() { super.onStart(); ProbeRunner.LISTENERS.add(listener); render(); }

    @Override public void onStop() {
        ProbeRunner.LISTENERS.remove(listener);
        keepAwake(false);
        super.onStop();
    }

    @Override public void onDestroyView() { b = null; super.onDestroyView(); }

    /** The server of the active profile, without its port: that is where the probe service normally runs. */
    private String defaultHost() {
        Store.Item it = Store.active(requireContext());
        Profile p = it == null ? null : it.parsed();
        String s = p == null ? "" : p.server;
        if (s.startsWith("[")) { int e = s.indexOf(']'); return e > 0 ? s.substring(1, e) : s; }
        int i = s.lastIndexOf(':');
        return i > 0 ? s.substring(0, i) : s;
    }

    /** The active profile when it offers several FEC levels (the test then also picks the level). Whether the tested server is
     *  really the profile's server is checked after the test, on the resolved addresses. */
    private Store.Item tunable() {
        if (b == null) return null;
        Store.Item it = Store.active(requireContext());
        Profile p = it == null ? null : it.parsed();
        return p != null && p.hasTiers() ? it : null;
    }

    private int preset() {
        int id = b.presets.getCheckedChipId();
        return id == R.id.pStandard ? 1 : id == R.id.pThorough ? 2 : 0;
    }

    private void renderPlan() {
        LinkProbe.Phase[] plan = PRESETS[preset()];
        long bytes = 0; int secs = 0;
        StringBuilder sb = new StringBuilder();
        for (LinkProbe.Phase p : plan) {
            bytes += p.wireBytes(); secs += p.seconds + 4;
            sb.append(String.format(Locale.US, "%d pps × %d B for %d s;  ", p.pps, p.size, p.seconds));
        }
        b.planText.setText(String.format(Locale.US, "%suses about %s of data in total and takes about %d s. Keep the app open.",
                sb, Fmt.bytes(bytes), secs));
        String t = defaultHost();
        b.targetHint.setText(t.isEmpty()
                ? "Enter the address of a server that runs the probe service (port " + DEFAULT_PORT + " by default)."
                : "Starts from the active profile's server. The server must run the probe service, which listens on its own UDP port (" + DEFAULT_PORT + " by default).");
    }

    private void start() {
        String host = String.valueOf(b.host.getText()).trim();
        int port;
        try { port = Integer.parseInt(String.valueOf(b.port.getText()).trim()); } catch (NumberFormatException e) { port = -1; }
        if (host.isEmpty()) { b.host.setError("Enter a server"); return; }
        if (port < 1 || port > 65535) { b.port.setError("1 to 65535"); return; }
        prefs().edit().putString("probe_host", host).putInt("probe_port", port).putInt("probe_preset", preset()).apply();
        List<LinkProbe.Phase> plan = new ArrayList<>(Arrays.asList(PRESETS[preset()]));
        Store.Item t = tunable();
        ProbeRunner.start(requireContext(), host, port, plan, t == null ? null : t.id, t == null ? null : t.parsed());
    }

    private String exportText() {
        return ProbeReport.export(ProbeRunner.host, ProbeRunner.port, ProbeRunner.results);
    }

    private void keepAwake(boolean on) {
        if (getActivity() == null) return;
        if (on) getActivity().getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        else getActivity().getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }

    /** The card that says which FEC level the active profile uses. Only for profiles that have several levels. */
    private void renderTune() {
        Store.Item it = Store.active(requireContext());
        Profile p = it == null ? null : it.parsed();
        if (p == null || !p.hasTiers()) { b.tuneCard.setVisibility(View.GONE); return; }
        Tuning.Choice c = Tuning.get(requireContext(), it.id);
        String lab = Tuning.label(p, c);
        b.tuneCard.setVisibility(View.VISIBLE);
        StringBuilder levels = new StringBuilder();
        for (FecTuner.Tier t : p.tiers) levels.append(levels.length() == 0 ? "" : ", ").append(t.name);
        b.tuneText.setText(lab == null ? "Not tuned: using the profile's own settings." : "Using " + lab + ".");
        b.tuneHint.setText((lab == null ? "" : "Set by a test " + Fmt.ago(c.at) + ". ") + "Levels: " + levels + ". \"Test and tune\" measures the link to " + defaultHost()
                + " and picks the level for each direction itself.");
        b.tuneReset.setVisibility(lab == null ? View.GONE : View.VISIBLE);
    }

    private void card(String title, String body) {
        ItemProbeCardBinding c = ItemProbeCardBinding.inflate(getLayoutInflater(), b.results, false);
        c.cardTitle.setText(title);
        c.cardBody.setText(body);
        b.results.addView(c.getRoot());
    }

    private void render() {
        if (b == null) return;
        boolean running = ProbeRunner.running;
        keepAwake(running);
        b.start.setText(running ? "Stop" : tunable() != null ? "Test and tune" : "Start test");
        renderTune();
        b.host.setEnabled(!running); b.port.setEnabled(!running);
        for (int i = 0; i < b.presets.getChildCount(); i++) b.presets.getChildAt(i).setEnabled(!running);
        b.progress.setVisibility(running ? View.VISIBLE : View.GONE);
        b.status.setVisibility(running || ProbeRunner.error != null ? View.VISIBLE : View.GONE);
        if (running) {
            int total = Math.max(1, ProbeRunner.total);
            double frac = "sending".equals(ProbeRunner.stage) ? ProbeRunner.sent / (double) total : 1.0;
            b.progress.setProgress((int) (1000 * ((ProbeRunner.phase - 1) + frac) / Math.max(1, ProbeRunner.phases)));
            String what = "sending".equals(ProbeRunner.stage) ? "Sending" : "waiting".equals(ProbeRunner.stage) ? "Waiting for late packets"
                    : "collecting".equals(ProbeRunner.stage) ? "Asking the server what it received" : "Starting";
            b.status.setTextColor(com.google.android.material.color.MaterialColors.getColor(b.status, com.google.android.material.R.attr.colorOnSurface));
            b.status.setText(String.format(Locale.US, "Phase %d of %d: %s  (%d / %d sent, %d echoed)",
                    Math.max(1, ProbeRunner.phase), ProbeRunner.phases, what, ProbeRunner.sent, ProbeRunner.total, ProbeRunner.echoes));
        } else if (ProbeRunner.error != null) {
            b.status.setTextColor(b.status.getContext().getColor(R.color.status_err));
            b.status.setText(ProbeRunner.error);
        }

        b.results.removeAllViews();
        List<LinkProbe.Result> rs = ProbeRunner.results;
        for (int i = 0; i < rs.size(); i++) card("Phase " + (i + 1) + ":  " + ProbeReport.title(rs.get(i)), ProbeReport.body(rs.get(i)));
        if (!rs.isEmpty() && !running) {
            card("Verdict", ProbeReport.verdict(rs));
            if (ProbeRunner.tuneMsg != null) card("FEC level", ProbeRunner.tuneMsg);
        }
        b.actions.setVisibility(rs.isEmpty() ? View.GONE : View.VISIBLE);
    }
}
