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
    private String pickFor = "";                 // the profile the two level pickers were filled for
    private int selUp = -1, selDown = -1;          // what the pickers show; -1 = as the profile has it
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
        b.tunnelQuicSwitch.setOnCheckedChangeListener((sw, on) -> {
            if (quicBinding) return;
            Store.Item it = selected();
            if (it == null) return;
            Tuning.setQuic(requireContext(), it.id, on);
            Store.Item act = Store.active(requireContext());
            boolean live = TunnelService.running() && act != null && act.id.equals(it.id);
            if (live) TunnelService.retune(requireContext());
            Toast.makeText(requireContext(), (on ? "QUIC disguise on" : "QUIC disguise off") + (live ? " (the tunnel stays up)" : ""), Toast.LENGTH_SHORT).show();
            renderQuic();
            renderTarget();
        });
        b.start.setOnClickListener(v -> { if (ProbeRunner.running) ProbeRunner.stop(); else start(); });
        b.copy.setOnClickListener(v -> {
            ((ClipboardManager) requireContext().getSystemService(Context.CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText("link test", exportText()));
            Toast.makeText(requireContext(), "Results copied", Toast.LENGTH_SHORT).show();
        });
        b.share.setOnClickListener(v -> startActivity(Intent.createChooser(
                new Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, exportText()), "Share results")));
        b.downPick.setOnItemClickListener((parent, v, pos, id) -> { selDown = pos - 1; renderTuneButtons(); });
        b.upPick.setOnItemClickListener((parent, v, pos, id) -> { selUp = pos - 1; renderTuneButtons(); });
        b.tuneApply.setOnClickListener(v -> applyLevels(selUp, selDown));
        b.tuneReset.setOnClickListener(v -> applyLevels(-1, -1));
        b.tuneSuggest.setOnClickListener(v -> applyLevels(ProbeRunner.suggestUp, ProbeRunner.suggestDown));
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

    /** True when the tunnel of this profile is set to go out as QUIC: the test then uses the same kind of packets. */
    private boolean quicMode(Store.Item it, Profile p) {
        return it != null && p != null && p.hasQuic() && Tuning.quic(requireContext(), it.id);
    }

    /** What the test will talk to, from the profile and the mode it was set up with. */
    private void renderTarget() {
        Store.Item it = selected();
        Profile p = selectedProfile();
        if (items.isEmpty()) { b.targetHint.setText("Add a profile first (Profiles tab)."); return; }
        if (p == null) { b.targetHint.setText("This profile cannot be read."); return; }
        if (quicMode(it, p)) {
            b.targetHint.setText("Tests UDP to " + p.serverHost() + ":" + p.quicPort + " with packets that look like QUIC, the way this profile's tunnel is sent "
                    + "(its QUIC disguise is on). Switch the disguise off below to test plain UDP.");
            return;
        }
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
        ProbeRunner.start(requireContext(), it.id, it.name, p, plan, quicMode(it, p), 0);
    }

    private String exportText() {
        return ProbeReport.export(ProbeRunner.profileName, ProbeRunner.host, ProbeRunner.port, ProbeRunner.results, ProbeRunner.quic);
    }

    private void keepAwake(boolean on) {
        if (getActivity() == null) return;
        if (on) getActivity().getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        else getActivity().getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }

    /** Fills the two pickers for the selected profile (once per profile, so a half-made choice is not overwritten). */
    private void loadPickers(Store.Item it, Profile p, boolean force) {
        if (!force && it.id.equals(pickFor)) return;
        pickFor = it.id;
        Tuning.Choice c = Tuning.get(requireContext(), it.id);
        selUp = c == null ? -1 : c.up;
        selDown = c == null ? -1 : c.down;
        List<String> names = new ArrayList<>();
        names.add("Profile's own settings");
        for (FecTuner.Tier t : p.tiers) names.add(t.name + " (up to about " + t.loss + " %)");
        b.downPick.setAdapter(new ArrayAdapter<>(requireContext(), android.R.layout.simple_dropdown_item_1line, names));
        b.upPick.setAdapter(new ArrayAdapter<>(requireContext(), android.R.layout.simple_dropdown_item_1line, names));
        b.downPick.setText(names.get(selDown + 1 < names.size() ? selDown + 1 : 0), false);
        b.upPick.setText(names.get(selUp + 1 < names.size() ? selUp + 1 : 0), false);
    }

    private void applyLevels(int up, int down) {
        Store.Item it = selected();
        if (it == null) return;
        Tuning.set(requireContext(), it.id, up, down);
        Store.Item act = Store.active(requireContext());
        boolean live = TunnelService.running() && act != null && act.id.equals(it.id);
        if (live) TunnelService.retune(requireContext());
        Profile p = it.parsed();
        String lab = p == null ? null : Tuning.label(p, Tuning.get(requireContext(), it.id));
        Toast.makeText(requireContext(), (lab == null ? "Using the profile's own settings" : "Set: " + lab) + (live ? " (the tunnel stays up)" : ""), Toast.LENGTH_SHORT).show();
        pickFor = "";
        render();
    }

    private void renderTuneButtons() {
        Store.Item it = selected();
        Tuning.Choice c = it == null ? null : Tuning.get(requireContext(), it.id);
        int storedUp = c == null ? -1 : c.up, storedDown = c == null ? -1 : c.down;
        b.tuneApply.setEnabled(selUp != storedUp || selDown != storedDown);
        b.tuneReset.setEnabled(c != null || selUp >= 0 || selDown >= 0);
    }

    /** The card where the user picks the FEC level of the selected profile, per direction. Only for profiles that offer levels. */
    private boolean quicBinding;

    /** The switch for sending the tunnel itself as QUIC: shown for profiles whose server offers it. */
    private void renderQuic() {
        Store.Item it = selected();
        Profile p = it == null ? null : it.parsed();
        if (p == null || !p.hasQuic()) { b.tunnelQuicCard.setVisibility(View.GONE); return; }
        b.tunnelQuicCard.setVisibility(View.VISIBLE);
        quicBinding = true;
        b.tunnelQuicSwitch.setChecked(Tuning.quic(requireContext(), it.id));
        quicBinding = false;
        b.tunnelQuicText.setText("Sends this profile's FEC traffic to UDP " + p.quicPort + " of the server wrapped in packets that look like QUIC (Hysteria 2 / HTTP/3), "
                + "to see whether your network lets them through better. It adds 13 bytes to each packet. "
                + (TunnelService.running() ? "If this profile is connected, only its FEC helper restarts and the tunnel stays up." : "It takes effect the next time you connect."));
    }

    private void renderTune() {
        renderQuic();
        Store.Item it = selected();
        Profile p = it == null ? null : it.parsed();
        if (p == null || !p.hasTiers()) { b.tuneCard.setVisibility(View.GONE); return; }
        loadPickers(it, p, false);
        Tuning.Choice c = Tuning.get(requireContext(), it.id);
        String lab = Tuning.label(p, c);
        b.tuneCard.setVisibility(View.VISIBLE);
        b.tuneText.setText(lab == null ? "Using the profile's own settings in both directions." : "Set to " + lab + ".");
        b.tuneHint.setText("Nothing changes by itself: this changes only when you tap Apply. A stronger level repairs more loss but sends more traffic. "
                + (TunnelService.running() ? "If this profile is connected, only its FEC helper restarts and the tunnel stays up." : "It takes effect the next time you connect."));
        boolean sug = !running() && ProbeRunner.suggestId.equals(it.id) && ProbeRunner.suggestMsg != null;
        b.suggestText.setVisibility(sug ? View.VISIBLE : View.GONE);
        b.suggestText.setText(sug ? ProbeRunner.suggestMsg : "");
        b.tuneSuggest.setVisibility(sug && ProbeRunner.suggestUp >= 0 && ProbeRunner.suggestDown >= 0 ? View.VISIBLE : View.GONE);
        renderTuneButtons();
    }

    private static boolean running() { return ProbeRunner.running; }

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
        b.start.setText(running ? "Stop" : "Start test");
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
        }
        b.actions.setVisibility(rs.isEmpty() ? View.GONE : View.VISIBLE);
    }
}
