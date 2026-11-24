package io.github.kurashizu.wgfec;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.PopupMenu;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

import io.github.kurashizu.wgfec.databinding.FragmentProfilesBinding;
import io.github.kurashizu.wgfec.databinding.ItemProfileBinding;

import java.util.ArrayList;
import java.util.List;

public class ProfilesFragment extends Fragment {
    private FragmentProfilesBinding b;
    private final List<Store.Item> items = new ArrayList<>();
    private String activeId = "";
    private RecyclerView.Adapter<VH> adapter;

    static final class VH extends RecyclerView.ViewHolder {
        final ItemProfileBinding v;
        VH(ItemProfileBinding v) { super(v.getRoot()); this.v = v; }
    }

    @Nullable @Override
    public View onCreateView(@NonNull LayoutInflater inf, @Nullable ViewGroup c, @Nullable Bundle s) {
        b = FragmentProfilesBinding.inflate(inf, c, false);
        b.list.setLayoutManager(new LinearLayoutManager(requireContext()));
        adapter = new RecyclerView.Adapter<VH>() {
            @NonNull @Override public VH onCreateViewHolder(@NonNull ViewGroup p, int t) {
                return new VH(ItemProfileBinding.inflate(LayoutInflater.from(p.getContext()), p, false));
            }
            @Override public int getItemCount() { return items.size(); }
            @Override public void onBindViewHolder(@NonNull VH h, int pos) { bind(h, items.get(pos)); }
        };
        b.list.setAdapter(adapter);
        b.add.setOnClickListener(v -> new MaterialAlertDialogBuilder(requireContext())
                .setTitle("Add a profile")
                .setItems(new String[]{"Paste app string from the clipboard", "Enter manually"}, (d, which) -> {
                    if (which == 0) Importer.fromClipboard(requireActivity(), this::reload); else Importer.manual(requireActivity(), this::reload);
                }).show());
        return b.getRoot();
    }

    @Override public void onResume() { super.onResume(); reload(); }
    @Override public void onDestroyView() { b = null; super.onDestroyView(); }

    private void reload() {
        if (b == null) return;
        items.clear();
        items.addAll(Store.list(requireContext()));
        Store.Item a = Store.active(requireContext());
        activeId = a == null ? "" : a.id;
        adapter.notifyDataSetChanged();
        b.empty.setVisibility(items.isEmpty() ? View.VISIBLE : View.GONE);
    }

    private void bind(VH h, Store.Item it) {
        Profile p = it.parsed();
        h.v.name.setText(it.name);
        h.v.info.setText(p == null ? "This profile is not valid." : p.detail());
        Ui.modeBadge(h.v.mode, p);
        boolean active = it.id.equals(activeId);
        h.v.badge.setVisibility(active ? View.VISIBLE : View.GONE);
        h.v.card.setStrokeWidth(active ? (int) (2 * getResources().getDisplayMetrics().density) : 0);
        h.v.card.setOnClickListener(v -> {
            if (TunnelService.running() && !active) { Toast.makeText(requireContext(), "Disconnect first to switch profiles.", Toast.LENGTH_SHORT).show(); return; }
            Store.setActive(requireContext(), it.id);
            reload();
        });
        h.v.more.setOnClickListener(v -> {
            PopupMenu m = new PopupMenu(requireContext(), v);
            m.inflate(R.menu.profile_item);
            m.setOnMenuItemClickListener(mi -> {
                int id = mi.getItemId();
                if (id == R.id.act_details) details(it);
                else if (id == R.id.act_rename) rename(it);
                else delete(it, active);
                return true;
            });
            m.show();
        });
    }

    private void details(Store.Item it) {
        Profile p = it.parsed();
        StringBuilder t = new StringBuilder();
        if (p == null) t.append("This profile is not valid.");
        else {
            t.append("Type: ").append(p.hasFec() ? "WireGuard with FEC (speederv2)" : "Plain WireGuard").append('\n');
            t.append("Server: ").append(p.server).append('\n');
            if (p.hasFec()) {
                t.append("FEC: ").append(p.fec.isEmpty() ? "default" : p.fec);
                if (!p.timeout.isEmpty()) t.append(", timeout ").append(p.timeout).append(" ms");
                if (!p.interval.isEmpty()) t.append(", interval ").append(p.interval).append(" ms");
                t.append('\n');
            }
            if (!p.address.isEmpty()) t.append("Address: ").append(p.address).append('\n');
            if (!p.mtu.isEmpty()) t.append("MTU: ").append(p.mtu).append('\n');
            if (!p.dns.isEmpty()) t.append("DNS: ").append(p.dns).append('\n');
            t.append("\nThe private key is stored on this phone only and is never shown.");
        }
        new MaterialAlertDialogBuilder(requireContext()).setTitle(it.name).setMessage(t.toString()).setPositiveButton("OK", null).show();
    }

    private void rename(Store.Item it) {
        TextInputLayout box = new TextInputLayout(requireContext());
        TextInputEditText f = new TextInputEditText(box.getContext());
        f.setSingleLine(true); f.setText(it.name); box.setHint("Name"); box.addView(f);
        int pad = (int) (24 * getResources().getDisplayMetrics().density);
        FrameLayout w = new FrameLayout(requireContext());
        w.setPadding(pad, pad / 3, pad, 0);
        w.addView(box, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        new MaterialAlertDialogBuilder(requireContext()).setTitle("Rename").setView(w)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Save", (d, x) -> { Store.rename(requireContext(), it.id, String.valueOf(f.getText())); reload(); }).show();
    }

    private void delete(Store.Item it, boolean active) {
        if (active && TunnelService.running()) { Toast.makeText(requireContext(), "Disconnect first.", Toast.LENGTH_SHORT).show(); return; }
        new MaterialAlertDialogBuilder(requireContext()).setTitle("Delete \"" + it.name + "\"?")
                .setMessage("The profile and its private key are removed from this phone. The device stays on the server.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Delete", (d, x) -> { Store.delete(requireContext(), it.id); reload(); }).show();
    }
}
