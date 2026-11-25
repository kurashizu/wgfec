package io.github.kurashizu.wgfec;

import android.content.Context;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import io.github.kurashizu.wgfec.databinding.FragmentDebugBinding;

/** One tab for everything that helps when something is wrong: the link test and the tunnel log. */
public class DebugFragment extends Fragment {
    /** Set by other screens to open a particular pane the next time this tab is shown ("log" or "test"). */
    static String pendingPane;

    private FragmentDebugBinding b;

    @Nullable @Override
    public View onCreateView(@NonNull LayoutInflater inf, @Nullable ViewGroup c, @Nullable Bundle s) {
        b = FragmentDebugBinding.inflate(inf, c, false);
        android.content.SharedPreferences sp = requireContext().getSharedPreferences("p", Context.MODE_PRIVATE);
        String pane = pendingPane != null ? pendingPane : sp.getString("debug_pane", "test");
        pendingPane = null;
        b.pane.addOnButtonCheckedListener((g, id, checked) -> {
            if (!checked) return;
            boolean log = id == R.id.paneLog;
            sp.edit().putString("debug_pane", log ? "log" : "test").apply();
            show(log);
        });
        b.pane.check("log".equals(pane) ? R.id.paneLog : R.id.paneTest);
        return b.getRoot();
    }

    private void show(boolean log) {
        getChildFragmentManager().beginTransaction()
                .replace(R.id.debugContainer, log ? new LogFragment() : new TestFragment()).commit();
    }

    @Override public void onDestroyView() { b = null; super.onDestroyView(); }
}
