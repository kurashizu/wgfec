package io.github.kurashizu.wgfec;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** Splits what the admin page gives you into a WireGuard config and the UDPspeeder client command. */
final class Profile {
    String wgConfig;
    String server = "", fec = "", address = "", mtu = "", dns = "", mode = "", timeout = "", interval = "";
    final List<String> speederArgs = new ArrayList<>();
    final List<String> notes = new ArrayList<>();
    /** The server's FEC levels, weakest first (empty when the server has none or this is not an FEC profile). */
    final List<FecTuner.Tier> tiers = new ArrayList<>();
    static final String TIERS_PREFIX = "# wgfec-tiers:";
    static final String PROBE_PREFIX = "# wgfec-probe:";
    /** UDP port of the server's link-test probe, as the admin page gave it; 0 when the profile does not say. */
    int probePort;
    static final int DEFAULT_PROBE_PORT = 62933;

    static Profile parse(String text, String ownPackage) throws Exception {
        Profile p = new Profile();
        StringBuilder wg = new StringBuilder();
        for (String raw : text.split("\r?\n")) {
            String line = raw.trim();
            if (line.startsWith(TIERS_PREFIX)) { parseTiers(p, line.substring(TIERS_PREFIX.length())); continue; }
            if (line.startsWith(PROBE_PREFIX)) {
                try { int v = Integer.parseInt(line.substring(PROBE_PREFIX.length()).trim()); if (v >= 1 && v <= 65535) p.probePort = v; } catch (NumberFormatException ignored) { }
                continue;
            }
            List<String> t = line.isEmpty() || line.startsWith("[") || line.startsWith("#") || line.contains("=") && !line.contains(" -")
                    ? null : tokens(line);
            String prog = null;
            if (t != null && !t.isEmpty()) {
                int i = t.get(0).equals("sudo") ? 1 : 0;
                if (i < t.size()) prog = t.get(i).substring(t.get(i).lastIndexOf('/') + 1);
                if (prog != null && prog.startsWith("speederv2")) {
                    if (!p.speederArgs.isEmpty()) throw new Exception("More than one speederv2 command was pasted.");
                    p.speederArgs.addAll(t.subList(i + 1, t.size()));
                    continue;
                }
            }
            wg.append(raw).append('\n');
        }
        String cfg = wg.toString();
        if (!cfg.contains("[Interface]") || !cfg.contains("[Peer]"))
            throw new Exception("No WireGuard config found: it needs an [Interface] and a [Peer] section.");
        // The helper process must talk to the server directly, never through the tunnel it feeds.
        if (!cfg.contains("ExcludedApplications"))
            cfg = cfg.replaceFirst("\\[Interface\\]", "[Interface]\nExcludedApplications = " + ownPackage);
        p.wgConfig = cfg;
        if (!p.speederArgs.isEmpty() && !cfg.matches("(?s).*Endpoint\\s*=\\s*127\\.0\\.0\\.1:.*"))
            p.notes.add("Warning: the WireGuard Endpoint is not 127.0.0.1, so it will bypass speederv2.");
        if (p.speederArgs.isEmpty())
            p.notes.add("No speederv2 command found: the tunnel will run without FEC.");
        p.address = cfgValue(cfg, "Address"); p.mtu = cfgValue(cfg, "MTU"); p.dns = cfgValue(cfg, "DNS");
        p.server = arg(p.speederArgs, "-r");
        if (p.server.isEmpty()) p.server = cfgValue(cfg, "Endpoint");
        p.fec = arg(p.speederArgs, "-f");
        p.timeout = arg(p.speederArgs, "--timeout"); p.interval = arg(p.speederArgs, "-i"); p.mode = arg(p.speederArgs, "--mode");
        return p;
    }

    boolean hasFec() { return !speederArgs.isEmpty(); }

    /** The server's address without its port. */
    String serverHost() {
        if (server.startsWith("[")) { int e = server.indexOf(']'); return e > 0 ? server.substring(1, e) : server; }
        int i = server.lastIndexOf(':');
        return i > 0 ? server.substring(0, i) : server;
    }

    /** The UDP port the link test uses on the server: the one in the profile, or the default when it does not say. */
    int linkTestPort() { return probePort > 0 ? probePort : DEFAULT_PROBE_PORT; }

    /** True when the app can switch this profile between several FEC levels. */
    boolean hasTiers() { return hasFec() && tiers.size() > 1; }

    /** The speederv2 arguments for a chosen uplink and downlink level (null choice: as pasted). */
    List<String> speederArgsFor(Tuning.Choice c) {
        if (c == null || !hasTiers() || c.up < 0 || c.up >= tiers.size() || c.down < 0 || c.down >= tiers.size()) return speederArgs;
        return FecTuner.applyTiers(speederArgs, tiers.get(c.up), tiers.get(c.down));
    }

    private static void parseTiers(Profile p, String json) {
        try {
            JSONArray a = new JSONArray(json.trim());
            List<FecTuner.Tier> out = new ArrayList<>();
            for (int i = 0; i < a.length() && i < 12; i++) {
                JSONObject o = a.getJSONObject(i);
                String name = o.getString("n").replaceAll("[^\\w .-]", "");
                String up = o.getString("u");
                int loss = o.getInt("l"), port = o.getInt("p"), t = o.getInt("t"), iv = o.getInt("i"), d = o.getInt("d"), s = o.getInt("s");
                if (name.isEmpty() || name.length() > 24 || !up.matches("[0-9]+:[0-9]+(,[0-9]+:[0-9]+)*") || up.length() > 300
                        || loss < 0 || loss > 100 || port < 1 || port > 65535 || t < 0 || t > 1000 || iv < 0 || iv > 1000
                        || d < 300 || d > 20000 || s < 10 || s > 10240) throw new Exception("bad level " + (i + 1));
                out.add(new FecTuner.Tier(name, loss, port, up, t, iv, d, s));
            }
            for (int i = 1; i < out.size(); i++) if (out.get(i).loss < out.get(i - 1).loss) throw new Exception("levels are not in order");
            p.tiers.addAll(out);
        } catch (Exception e) {
            p.notes.add("Ignored the list of FEC levels in this profile (" + e.getMessage() + "); it still works with its fixed settings.");
        }
    }

    /** "FEC" or "Direct": how this profile reaches the server. */
    String modeLabel() { return hasFec() ? "FEC" : "Direct"; }

    /** One line for lists, next to the mode badge: the FEC setting (if any) and where the tunnel goes. */
    String detail() { return (hasFec() && !fec.isEmpty() ? "FEC " + fec + " · " : "") + server; }

    /** Longer text for the import confirmation. */
    String describe() {
        return hasFec()
                ? "Mode: FEC (WireGuard through speederv2)\nFEC setting: " + (fec.isEmpty() ? "default" : fec) + "\nServer: " + server
                + (hasTiers() ? "\nFEC levels: " + tiers.size() + " (" + tiers.get(0).name + " to " + tiers.get(tiers.size() - 1).name + "), tuned from the Debug tab" : "")
                : "Mode: Direct (plain WireGuard, no FEC)\nServer: " + server;
    }

    private static String cfgValue(String cfg, String key) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(?mi)^\\s*" + key + "\\s*=\\s*(.+?)\\s*$").matcher(cfg);
        return m.find() ? m.group(1) : "";
    }

    private static String arg(List<String> a, String flag) {
        for (int i = 0; i < a.size(); i++) {
            String x = a.get(i);
            if (x.equals(flag)) return i + 1 < a.size() ? a.get(i + 1) : "";
            if (flag.length() == 2 && x.startsWith(flag) && x.length() > 2) return x.substring(2);
        }
        return "";
    }

    static List<String> tokens(String s) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        char q = 0;
        boolean any = false;
        for (char c : s.toCharArray()) {
            if (q != 0) { if (c == q) q = 0; else cur.append(c); }
            else if (c == '"' || c == '\'') { q = c; any = true; }
            else if (Character.isWhitespace(c)) { if (cur.length() > 0 || any) out.add(cur.toString()); cur.setLength(0); any = false; }
            else cur.append(c);
        }
        if (cur.length() > 0 || any) out.add(cur.toString());
        return out;
    }
}
