package io.github.kurashizu.wgfec;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Saved profiles. They live in the app's private storage (the private key is in there, so backups are off). */
final class Store {
    static final class Item {
        String id, name, text;
        long created;
        Profile parsed() { try { return Profile.parse(text, "x"); } catch (Exception e) { return null; } }
    }

    private static SharedPreferences sp(Context c) { return c.getSharedPreferences("p", Context.MODE_PRIVATE); }

    static synchronized List<Item> list(Context c) {
        List<Item> out = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(sp(c).getString("profiles", "[]"));
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                Item it = new Item();
                it.id = o.getString("id"); it.name = o.getString("name"); it.text = o.getString("text"); it.created = o.optLong("created");
                out.add(it);
            }
        } catch (Exception ignored) { }
        return out;
    }

    private static void save(Context c, List<Item> items) {
        JSONArray a = new JSONArray();
        try {
            for (Item it : items) a.put(new JSONObject().put("id", it.id).put("name", it.name).put("text", it.text).put("created", it.created));
        } catch (Exception ignored) { }
        sp(c).edit().putString("profiles", a.toString()).apply();
    }

    static synchronized Item active(Context c) {
        String id = sp(c).getString("active", "");
        List<Item> l = list(c);
        for (Item it : l) if (it.id.equals(id)) return it;
        return l.isEmpty() ? null : l.get(0);
    }

    static void setActive(Context c, String id) { sp(c).edit().putString("active", id).apply(); }

    static synchronized Item add(Context c, String name, String text) {
        List<Item> l = list(c);
        Item it = new Item();
        it.id = UUID.randomUUID().toString(); it.name = uniqueName(l, name, null); it.text = text; it.created = System.currentTimeMillis();
        l.add(it);
        save(c, l);
        if (l.size() == 1 || sp(c).getString("active", "").isEmpty()) setActive(c, it.id);
        return it;
    }

    static synchronized void rename(Context c, String id, String name) {
        List<Item> l = list(c);
        for (Item it : l) if (it.id.equals(id)) it.name = uniqueName(l, name, id);
        save(c, l);
    }

    static synchronized void delete(Context c, String id) {
        List<Item> l = list(c);
        l.removeIf(it -> it.id.equals(id));
        save(c, l);
        Tuning.clear(c, id);
        if (sp(c).getString("active", "").equals(id)) setActive(c, l.isEmpty() ? "" : l.get(0).id);
    }

    static String uniqueName(List<Item> l, String want, String selfId) {
        String base = want == null || want.trim().isEmpty() ? "Profile" : want.trim();
        String n = base;
        for (int k = 2; taken(l, n, selfId); k++) n = base + " " + k;
        return n;
    }

    private static boolean taken(List<Item> l, String n, String selfId) {
        for (Item it : l) if (it.name.equalsIgnoreCase(n) && !it.id.equals(selfId)) return true;
        return false;
    }
}
