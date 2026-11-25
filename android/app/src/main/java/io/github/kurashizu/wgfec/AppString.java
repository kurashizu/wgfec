package io.github.kurashizu.wgfec;

import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.zip.InflaterInputStream;

/**
 * The app string protocol of the admin pages (the wgfec:// link).
 *   wgfec://1/<base64url(JSON)>          plain
 *   wgfec://1z/<base64url(zlib(JSON))>   compressed
 * JSON: {"v":1, "name":"...", "wg":"<WireGuard config>", "sp":"<speederv2 client command>" (absent = direct tunnel),
 *        "tiers":[{n,l,p,u,t,i,d,s}, ...] (optional: the server's FEC levels, weakest first),
 *        "probe":62933 (optional: UDP port of the server's link-test probe),
 *        "quic":{"p":443,"s":"name"} (optional: the server accepts the FEC tunnel dressed up as QUIC on UDP port p; s is the made-up server name),
 *        "qd":1 (optional: with "quic", start with the QUIC disguise switched on)}
 */
final class AppString {
    static final String SCHEME = "wgfec://";
    private static final int MAX_BYTES = 64 * 1024;

    final String name;
    final String profileText;   // what the profile box holds: the WireGuard config followed by the speederv2 command
    final boolean fec;
    /** True when the admin page asked for the QUIC disguise to start switched on ("qd":1, only meaningful together with "quic"). */
    final boolean quicDefault;

    private AppString(String name, String profileText, boolean fec, boolean quicDefault) {
        this.name = name; this.profileText = profileText; this.fec = fec; this.quicDefault = quicDefault;
    }

    static boolean looksLike(String s) { return s != null && s.trim().startsWith(SCHEME); }

    static AppString parse(String link) throws Exception {
        String s = link.trim();
        boolean z;
        String body;
        if (s.startsWith(SCHEME + "1z/")) { z = true; body = s.substring((SCHEME + "1z/").length()); }
        else if (s.startsWith(SCHEME + "1/")) { z = false; body = s.substring((SCHEME + "1/").length()); }
        else if (s.startsWith(SCHEME)) throw new Exception("This app string is from a newer version of the admin page. Update the app.");
        else throw new Exception("Not a WG FEC app string.");
        byte[] raw;
        try {
            raw = Base64.getUrlDecoder().decode(body);
            if (z) raw = inflate(raw);
        } catch (IllegalArgumentException | java.io.IOException e) {
            throw new Exception("The app string is damaged or incomplete. Copy it again.");
        }
        JSONObject o = new JSONObject(new String(raw, StandardCharsets.UTF_8));
        if (o.optInt("v", 0) != 1) throw new Exception("Unsupported app string version " + o.optInt("v", 0) + ". Update the app.");
        String wg = o.optString("wg", "");
        if (!wg.contains("[Interface]") || !wg.contains("[Peer]")) throw new Exception("The app string has no WireGuard config.");
        String sp = o.optString("sp", "");
        StringBuilder t = new StringBuilder(wg.trim()).append('\n');
        if (!sp.isEmpty()) t.append('\n').append(sp.trim()).append('\n');
        int probe = o.optInt("probe", 0);
        if (probe >= 1 && probe <= 65535) t.append(Profile.PROBE_PREFIX).append(' ').append(probe).append('\n');
        JSONObject quic = o.optJSONObject("quic");
        boolean quicOk = !sp.isEmpty() && quic != null && quic.optInt("p", 0) >= 1 && quic.optInt("p", 0) <= 65535 && quic.optString("s", "").matches("[A-Za-z0-9.-]{1,100}");
        if (!sp.isEmpty() && quic != null && quic.optInt("p", 0) >= 1 && quic.optInt("p", 0) <= 65535 && quic.optString("s", "").matches("[A-Za-z0-9.-]{1,100}"))
            t.append(Profile.QUIC_PREFIX).append(' ').append(quic.optInt("p")).append(' ').append(quic.optString("s")).append('\n');
        org.json.JSONArray tiers = o.optJSONArray("tiers");
        if (!sp.isEmpty() && tiers != null && tiers.length() > 0) t.append(Profile.TIERS_PREFIX).append(' ').append(tiers.toString()).append('\n');
        String name = o.optString("name", "").replaceAll("[^\\w .@-]", "");
        return new AppString(name.isEmpty() ? "device" : name, t.toString(), !sp.isEmpty(), quicOk && o.optInt("qd", 0) == 1);
    }

    private static byte[] inflate(byte[] z) throws java.io.IOException {
        try (InflaterInputStream in = new InflaterInputStream(new ByteArrayInputStream(z))) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
                if (out.size() > MAX_BYTES) throw new java.io.IOException("too large");
            }
            return out.toByteArray();
        }
    }
}
