package io.github.kurashizu.wgfec;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Makes link test packets look like a QUIC v1 connection (what Hysteria 2 and HTTP/3 look like on the wire).
 * A disguise, not QUIC: there is no real handshake. Same format as tools/net-probe/quicshape.py, which describes it:
 * a valid Initial packet with a TLS ClientHello that names the given SNI, a small Handshake packet, then short header
 * packets (flags, 8 byte connection id, 4 bytes standing for the packet number) that carry the probe packet encrypted
 * with AES-CTR. Pure Java, no Android classes.
 */
final class QuicShape {
    static final int OVERHEAD = 13, CID_LEN = 8, MIN_INITIAL = 1200;
    private static final byte[] SALT_V1 = hex("38762cf7f55934b34d179ae6a4c80cadccbb7f0a");
    private static final SecureRandom RNG = new SecureRandom();

    final byte[] cid = new byte[CID_LEN];
    private final SecretKeySpec ctrKey;
    private final Cipher enc, dec;     // enc is used by the sending thread, dec by the receiving one

    QuicShape() throws GeneralSecurityException { this(0); }

    /** With a port, the first two bytes of the connection id carry it (masked), so a relay in front of the servers knows where to send the connection. */
    QuicShape(int port) throws GeneralSecurityException {
        RNG.nextBytes(cid);
        if (port > 0) {
            byte[] m = Arrays.copyOf(MessageDigest.getInstance("SHA-256").digest("wgfec-quic-port".getBytes(StandardCharsets.US_ASCII)), 2);
            cid[0] = (byte) ((port >> 8) ^ m[0]);
            cid[1] = (byte) ((port & 0xFF) ^ m[1]);
        }
        byte[] k = Arrays.copyOf(MessageDigest.getInstance("SHA-256").digest(concat("npr-quic".getBytes(StandardCharsets.US_ASCII), cid)), 16);
        ctrKey = new SecretKeySpec(k, "AES");
        enc = Cipher.getInstance("AES/CTR/NoPadding");
        dec = Cipher.getInstance("AES/CTR/NoPadding");
    }

    /** The datagrams that open a connection: a client Initial naming {@code sni}, and a small Handshake packet. */
    byte[][] firstFlight(String sni) throws GeneralSecurityException {
        byte[] scid = new byte[8];
        RNG.nextBytes(scid);
        int size = MIN_INITIAL + RNG.nextInt(80);
        byte[] ini = initial(cid, scid, crypto(clientHello(sni, scid)), false, cid, size);
        return new byte[][]{ini, handshake(cid, scid, 60 + RNG.nextInt(60))};
    }

    /** A short header packet that carries {@code inner[0..len)}. */
    byte[] wrap(byte[] inner, int len) throws GeneralSecurityException {
        byte[] nonce = new byte[4];
        RNG.nextBytes(nonce);
        byte[] out = new byte[OVERHEAD + len];
        out[0] = (byte) (0x40 | (RNG.nextInt() & 0x3F));
        System.arraycopy(cid, 0, out, 1, CID_LEN);
        System.arraycopy(nonce, 0, out, 1 + CID_LEN, 4);
        byte[] iv = new byte[16];
        System.arraycopy(nonce, 0, iv, 0, 4);
        enc.init(Cipher.ENCRYPT_MODE, ctrKey, new IvParameterSpec(iv));
        enc.update(inner, 0, len, out, OVERHEAD);
        return out;
    }

    /** The probe packet inside a short header packet that belongs to this connection, else null. */
    byte[] unwrap(byte[] d, int len) throws GeneralSecurityException {
        if (len <= OVERHEAD || (d[0] & 0xC0) != 0x40) return null;
        for (int i = 0; i < CID_LEN; i++) if (d[1 + i] != cid[i]) return null;
        byte[] iv = new byte[16];
        System.arraycopy(d, 1 + CID_LEN, iv, 0, 4);
        dec.init(Cipher.DECRYPT_MODE, ctrKey, new IvParameterSpec(iv));
        return dec.update(d, OVERHEAD, len - OVERHEAD);
    }

    static boolean isLongHeader(byte[] d, int len) { return len > 0 && (d[0] & 0x80) != 0; }

    // ---- Initial packets (RFC 9001 section 5) ----------------------------------------------------------------

    static byte[][] initialKeys(byte[] dcid, boolean server) throws GeneralSecurityException {
        byte[] secret = hmac(SALT_V1, dcid);
        byte[] side = expandLabel(secret, server ? "server in" : "client in", 32);
        return new byte[][]{expandLabel(side, "quic key", 16), expandLabel(side, "quic iv", 12), expandLabel(side, "quic hp", 16)};
    }

    private static byte[] expandLabel(byte[] secret, String label, int len) throws GeneralSecurityException {
        byte[] full = ("tls13 " + label).getBytes(StandardCharsets.US_ASCII);
        ByteArrayOutputStream info = new ByteArrayOutputStream();
        info.write(len >> 8); info.write(len); info.write(full.length);
        info.write(full, 0, full.length);
        info.write(0); info.write(1);
        return Arrays.copyOf(hmac(secret, info.toByteArray()), len);
    }

    private static byte[] hmac(byte[] key, byte[] data) throws GeneralSecurityException {
        Mac m = Mac.getInstance("HmacSHA256");
        m.init(new SecretKeySpec(key, "HmacSHA256"));
        return m.doFinal(data);
    }

    static byte[] initial(byte[] dcid, byte[] scid, byte[] frames, boolean server, byte[] keyCid, int size) throws GeneralSecurityException {
        byte[][] k = initialKeys(keyCid, server);
        int pnLen = 2, pn = 0;
        ByteArrayOutputStream head = new ByteArrayOutputStream();
        head.write(0xC0 | (pnLen - 1));
        head.write(0); head.write(0); head.write(0); head.write(1);
        head.write(dcid.length); head.write(dcid, 0, dcid.length);
        head.write(scid.length); head.write(scid, 0, scid.length);
        head.write(0);
        int fixed = head.size() + 2 + pnLen + 16;
        byte[] payload = Arrays.copyOf(frames, Math.max(frames.length, Math.max(4, size - fixed)));
        int length = pnLen + payload.length + 16;
        head.write(0x40 | (length >> 8)); head.write(length);
        head.write(pn >> 8); head.write(pn);
        byte[] hdr = head.toByteArray();
        byte[] nonce = k[1].clone();
        nonce[10] ^= (byte) (pn >> 8); nonce[11] ^= (byte) pn;
        Cipher gcm = Cipher.getInstance("AES/GCM/NoPadding");
        gcm.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(k[0], "AES"), new GCMParameterSpec(128, nonce));
        gcm.updateAAD(hdr);
        byte[] ct = gcm.doFinal(payload);
        byte[] pkt = concat(hdr, ct);
        int pnOff = hdr.length - pnLen;
        Cipher ecb = Cipher.getInstance("AES/ECB/NoPadding");
        ecb.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(k[2], "AES"));
        byte[] mask = ecb.doFinal(pkt, pnOff + 4, 16);
        pkt[0] ^= mask[0] & 0x0F;
        for (int i = 0; i < pnLen; i++) pkt[pnOff + i] ^= mask[1 + i];
        return pkt;
    }

    /** A Handshake packet of random bytes. */
    static byte[] handshake(byte[] dcid, byte[] scid, int size) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        o.write(0xE0 | 1);
        o.write(0); o.write(0); o.write(0); o.write(1);
        o.write(dcid.length); o.write(dcid, 0, dcid.length);
        o.write(scid.length); o.write(scid, 0, scid.length);
        int body = Math.max(20, size - o.size() - 2);
        o.write(0x40 | (body >> 8)); o.write(body);
        byte[] r = new byte[body];
        RNG.nextBytes(r);
        o.write(r, 0, r.length);
        return o.toByteArray();
    }

    // ---- TLS ClientHello ------------------------------------------------------------------------------------

    private static byte[] crypto(byte[] data) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        o.write(0x06); o.write(0);
        byte[] l = varint(data.length);
        o.write(l, 0, l.length);
        o.write(data, 0, data.length);
        return o.toByteArray();
    }

    static byte[] clientHello(String sni, byte[] scid) {
        byte[] name = sni.getBytes(StandardCharsets.US_ASCII);
        ByteArrayOutputStream tp = new ByteArrayOutputStream();
        long[][] ints = {{0x01, 30000}, {0x03, 1452}, {0x04, 15728640}, {0x05, 6291456}, {0x06, 6291456}, {0x07, 6291456},
                {0x08, 100}, {0x09, 103}, {0x0e, 4}};
        for (long[] p : ints) {
            byte[] v = varint(p[1]);
            tp.write((int) p[0]); tp.write(v.length); tp.write(v, 0, v.length);
        }
        tp.write(0x0f); tp.write(scid.length); tp.write(scid, 0, scid.length);
        byte[] share = new byte[32];
        RNG.nextBytes(share);
        ByteArrayOutputStream ext = new ByteArrayOutputStream();
        ext(ext, 0, concat(u16(name.length + 3), new byte[]{0}, u16(name.length), name));
        ext(ext, 10, concat(u16(6), hex("001d00170018")));
        ext(ext, 13, concat(u16(16), hex("04030804040105030805050108060601")));
        ext(ext, 16, new byte[]{0, 3, 2, 'h', '3'});
        ext(ext, 43, new byte[]{2, 3, 4});
        ext(ext, 45, new byte[]{1, 1});
        ext(ext, 51, concat(u16(36), u16(0x001d), u16(32), share));
        ext(ext, 57, tp.toByteArray());
        byte[] rnd = new byte[32];
        RNG.nextBytes(rnd);
        byte[] e = ext.toByteArray();
        byte[] body = concat(new byte[]{3, 3}, rnd, new byte[]{0}, u16(6), hex("130113021303"), new byte[]{1, 0}, u16(e.length), e);
        return concat(new byte[]{1, (byte) (body.length >> 16), (byte) (body.length >> 8), (byte) body.length}, body);
    }

    private static void ext(ByteArrayOutputStream o, int type, byte[] body) {
        byte[] h = concat(u16(type), u16(body.length), body);
        o.write(h, 0, h.length);
    }

    static byte[] varint(long n) {
        if (n < 0x40) return new byte[]{(byte) n};
        if (n < 0x4000) return new byte[]{(byte) (0x40 | (n >> 8)), (byte) n};
        return new byte[]{(byte) (0x80 | (n >> 24)), (byte) (n >> 16), (byte) (n >> 8), (byte) n};
    }

    private static byte[] u16(int v) { return new byte[]{(byte) (v >> 8), (byte) v}; }

    private static byte[] concat(byte[]... parts) {
        int n = 0;
        for (byte[] p : parts) n += p.length;
        byte[] out = new byte[n];
        int o = 0;
        for (byte[] p : parts) { System.arraycopy(p, 0, out, o, p.length); o += p.length; }
        return out;
    }

    static byte[] hex(String s) {
        byte[] b = new byte[s.length() / 2];
        for (int i = 0; i < b.length; i++) b[i] = (byte) Integer.parseInt(s.substring(2 * i, 2 * i + 2), 16);
        return b;
    }
}
