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
import android.widget.ArrayAdapter;
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

/**
 * Link test of one profile: measures UDP loss, latency and jitter between the phone and that profile's server, outside the
 * tunnel. The target (the server's address and its probe port) comes from the profile, and for a profile with several FEC
 * levels the test also picks the level to use.
 */
public class TestFragment extends Fragment {
    /** Set by other screens to open the test for one profile the next time this pane is shown. */
    static String pendingProfileId;

    private static final LinkProbe.Phase[][] PRESETS = {
            {new LinkProbe.Phase(30, 200, 8), new LinkProbe.Phase(300, 1200, 8)},
            {new LinkProbe.Phase(30, 200, 30), new LinkProbe.Phase(300, 1200, 20)},
            {new LinkProbe.Phase(30, 200, 90), new LinkProbe.Phase(300, 1200, 45)},
    };

    private FragmentTestBinding b;
    private String selectedId = "";
    private final List<Store.Item> items = new ArrayList<>();
    private final Runnable listener = this::render;

    private SharedPreferences prefs() { return requireContext().getSharedPreferences("p", Context.MODE_PRIVATE); }

    @Nullable @Override
    public View onCreateView(@NonNull LayoutInflater inf, @Nullable ViewGroup c, @Nullable Bundle s) {
        b = FragmentTestBinding.inflate(inf, c, false);
        int preset = prefs().getInt("probe_preset", 0);
        loadProfiles();
        b.profilePick.setOnItemClickListener((parent, v, pos, id) -> { selectedId = items.get(pos).id; prefs().edit().putString("probe_profile", selectedId).apply(); render(); });
        b.presets.check(preset == 1 ? R.id.pStandard : preset == 2 ? R.id.pThorough : R.id.pQuick);
        b.presets.setOnCheckedStateChangeListener((g, ids) -> { prefs().edit().putInt("probe_preset", preset()).apply(); renderPlan(); });
        b.start.setOnClickListener(v -> { if (ProbeRunner.running) ProbeRunner.stop(); else start(); });
        b.copy.setOnClickListener(v -> {
            ((ClipboardManager) requireContext().getSystemService(Context.CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText("link test", exportText()));
            Toast.makeText(requireContext(), "Results copied", Toast.LENGTH_SHORT).show();
        });
        b.share.setOnClickListener(v -> startActivity(Intent.createChooser(
                new Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, exportText()), "Share results")));
        b.tuneReset.setOnClickListener(v -> {
            Store.Item it = selected();
            if (it != null) {
                Tuning.clear(requireContext(), it.id);
                Store.Item act = Store.active(requireContext());
                if (act != null && act.id.equals(it.id)) TunnelService.retune(requireContext());
            }
            render();
        });
        renderPlan();
        return b.getRoot();
    }

    @Override public void onStart() { super.onStart(); ProbeRunner.LISTENERS.add(listener); loadProfiles(); render(); }

    @Override public void onStop() {
        ProbeRunner.LISTENERS.remove(listener);
        keepAwake(false);
        super.onStop();
    }

    @Override public void onDestroyView() { b = null; super.onDestroyView(); }

    /** The profile list for the picker; keeps the selection (asked for by another screen, else the last one, else the active one). */
    private void loadProfiles() {
        items.clear();
        items.addAll(Store.list(requireContext()));
        String want = pendingProfileId != null ? pendingProfileId : !selectedId.isEmpty() ? selectedId : prefs().getString("probe_profile", "");
        pendingProfileId = null;
        Store.Item act = Store.active(requireContext());
        selectedId = "";
        for (Store.Item it : items) if (it.id.equals(want)) selectedId = it.id;
        if (selectedId.isEmpty() && act != null) selectedId = act.id;
        List<String> names = new ArrayList<>();
        for (Store.Item it : items) names.add(it.name);
        if (b != null) {
            b.profilePick.setAdapter(new ArrayAdapter<>(requireContext(), android.R.layout.simple_dropdown_item_1line, names));
            Store.Item s = selected();
            b.profilePick.setText(s == null ? "" : s.name, false);
        }
    }

    private Store.Item selected() {
        for (Store.Item it : items) if (it.id.equals(selectedId)) return it;
        return null;
    }

    private Profile selectedProfile() {
        Store.Item it = selected();
        return it == null ? null : it.parsed();
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
    }

    /** What the test will talk to, from the profile. */
    private void renderTarget() {
        Profile p = selectedProfile();
        if (items.isEmpty()) { b.targetHint.setText("Add a profile first (Profiles tab)."); return; }
        if (p == null) { b.targetHint.setText("This profile cannot be read."); return; }
        String where = p.serverHost() + ":" + p.linkTestPort();
        b.targetHint.setText(p.probePort > 0
                ? "Tests UDP to " + where + ", the server and probe port this profile came with."
                : "Tests UDP to " + where + ". This profile does not say which port the probe uses, so the default is tried; "
                + "import its app string again from the admin page to store the right one.");
    }

    private void start() {
        Store.Item it = selected();
        Profile p = it == null ? null : it.parsed();
        if (it == null || p == null) { Toast.makeText(requireContext(), "Pick a profile first.", Toast.LENGTH_SHORT).show(); return; }
        prefs().edit().putInt("probe_preset", preset()).apply();
        List<LinkProbe.Phase> plan = new ArrayList<>(Arrays.asList(PRESETS[preset()]));
        ProbeRunner.start(requireContext(), it.id, it.name, p, plan);
    }

    private String exportText() {
        return ProbeReport.export(ProbeRunner.profileName, ProbeRunner.host, ProbeRunner.port, ProbeRunner.results);
    }

    private void keepAwake(boolean on) {
        if (getActivity() == null) return;
        if (on) getActivity().getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        else getActivity().getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }

    /** The card that says which FEC level the selected profile uses. Only for profiles that have several levels. */
    private void renderTune() {
        Store.Item it = selected();
        Profile p = it == null ? null : it.parsed();
        if (p == null || !p.hasTiers()) { b.tuneCard.setVisibility(View.GONE); return; }
        Tuning.Choice c = Tuning.get(requireContext(), it.id);
        String lab = Tuning.label(p, c);
        b.tuneCard.setVisibility(View.VISIBLE);
        StringBuilder levels = new StringBuilder();
        for (FecTuner.Tier t : p.tiers) levels.append(levels.length() == 0 ? "" : ", ").append(t.name);
        b.tuneText.setText(lab == null ? "Not tuned: using the profile's own settings." : "Using " + lab + ".");
        b.tuneHint.setText((lab == null ? "" : "Set by a test " + Fmt.ago(c.at) + ". ") + "Levels: " + levels + ". \"Test and tune\" measures this profile's link and picks the level for each direction itself.");
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
        Profile p = selectedProfile();
        b.start.setText(running ? "Stop" : p != null && p.hasTiers() ? "Test and tune" : "Start test");
        b.start.setEnabled(running || p != null);
        renderTarget();
        renderTune();
        b.profilePick.setEnabled(!running);
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
            b.status.setText(String.format(Locale.US, "%s, phase %d of %d: %s  (%d / %d sent, %d echoed)", ProbeRunner.profileName,
                    Math.max(1, ProbeRunner.phase), ProbeRunner.phases, what, ProbeRunner.sent, ProbeRunner.total, ProbeRunner.echoes));
        } else if (ProbeRunner.error != null) {
            b.status.setTextColor(b.status.getContext().getColor(R.color.status_err));
            b.status.setText(ProbeRunner.error);
        }

        b.results.removeAllViews();
        List<LinkProbe.Result> rs = ProbeRunner.results;
        String who = ProbeRunner.profileName.isEmpty() ? "" : ProbeRunner.profileName + " · ";
        for (int i = 0; i < rs.size(); i++) card(who + "Phase " + (i + 1) + ":  " + ProbeReport.title(rs.get(i)), ProbeReport.body(rs.get(i)));
        if (!rs.isEmpty() && !running) {
            card(who + "Verdict", ProbeReport.verdict(rs));
            if (ProbeRunner.tuneMsg != null) card("FEC level", ProbeRunner.tuneMsg);
        }
        b.actions.setVisibility(rs.isEmpty() ? View.GONE : View.VISIBLE);
    }
}
