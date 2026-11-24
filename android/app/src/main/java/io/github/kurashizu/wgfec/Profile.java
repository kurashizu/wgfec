package io.github.kurashizu.wgfec;

import java.util.ArrayList;
import java.util.List;

/** Splits what the admin page gives you into a WireGuard config and the UDPspeeder client command. */
final class Profile {
    String wgConfig;
    String server = "", fec = "", address = "", mtu = "", dns = "", mode = "", timeout = "", interval = "";
    final List<String> speederArgs = new ArrayList<>();
    final List<String> notes = new ArrayList<>();

    static Profile parse(String text, String ownPackage) throws Exception {
        Profile p = new Profile();
        StringBuilder wg = new StringBuilder();
        for (String raw : text.split("\r?\n")) {
            String line = raw.trim();
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
                if (prog != null && prog.startsWith("udp2raw")) {
                    p.notes.add("Ignored a udp2raw line: fake-TCP needs root and is not supported in this app.");
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

    /** One line for lists: what the tunnel is and where it goes. */
    String summary() { return (hasFec() ? "FEC " + (fec.isEmpty() ? "" : fec + " · ") : "Direct · ") + server; }

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
