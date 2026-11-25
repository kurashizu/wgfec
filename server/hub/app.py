#!/usr/bin/python3
"""wg-web: small password-protected web UI for managing WireGuard peers.

Pure standard library. Listens on 127.0.0.1 only (put a TLS-terminating tunnel
or proxy in front). All privileged work is delegated to
`sudo /usr/local/sbin/wg-peer {list|add|remove}`.

    app.py                      run the server
    app.py --set-password       read a new password from stdin, write the hash
"""
import base64
import hashlib
import hmac
import ipaddress
import http.cookies
import http.server
import json
import os
import re
import secrets
import ssl
import subprocess
import sys
import threading
import time
import urllib.parse

CONFIG_PATH = os.environ.get("WGWEB_CONFIG", "/etc/wg-web/config.json")
BASE_DIR = os.path.dirname(os.path.abspath(__file__))
BIND = (os.environ.get("WGWEB_BIND", "127.0.0.1"), int(os.environ.get("WGWEB_PORT", "8788")))
# Behind Cloudflare the real client IP is in CF-Connecting-IP. When this server is reachable
# directly that header is attacker-controlled, so it must not be trusted (login lockout).
TRUST_CF = os.environ.get("WGWEB_TRUST_CF", "1") == "1"
MIN_PW = int(os.environ.get("WGWEB_MIN_PW", "12"))
TLS_CERT = os.environ.get("WGWEB_TLS_CERT")
TLS_KEY = os.environ.get("WGWEB_TLS_KEY")
WG_PEER = ["sudo", "-n", "/usr/local/sbin/wg-peer"]
WG_ADMIN = ["sudo", "-n", "/usr/local/sbin/wg-admin"]
LOG_SOURCES = ("wg-service", "boringtun", "speeder", "web-ui")
SERVICE_ACTIONS = ("start", "stop", "restart")

# UDPspeeder FEC front for wg0 (wg-speeder.service). Settings live in an env file
# readable by this user's supplementary group; the key is only shown to a logged-in admin.
SPEEDER_ENV = "/etc/wg-speeder/speeder.env"
LEVELS_FILE = "/etc/wg-speeder/levels.json"
LEVEL_UNIT = "wg-speeder-level@%s.service"
PROBE_PORT = 62933              # UDP port of wg-probe.service (net-probe echo); keep in step with --port in that unit
PROBE_UNIT = "wg-probe.service"
QUIC_UNIT = "wg-quic.service"
QUIC_ENV = "/etc/wg-quic/relay.env"      # written by scripts/install-quic.sh: QUIC_PORT, QUIC_SNI
SPEEDER_HOST = os.environ.get("WGWEB_ENDPOINT_HOST", "wg.example.com")   # same host as the WG endpoint
SPEEDER_LOCAL_PORT = 51821      # where the client-side speederv2 listens on 127.0.0.1
SPEEDER_CLIENT_MTU = 1150       # keeps each WG packet inside one FEC shard of the default mode-0 size (mtu 1250)
VIA_MODES = ("direct", "speeder")

SESSION_TTL = 12 * 3600
COOKIE = "wgsid"
MAX_BODY = 4096
NAME_RE = re.compile(r"^[A-Za-z0-9_.-]{1,32}$")

# login throttling: per client IP and global
IP_MAX_FAILS, IP_WINDOW, IP_LOCK = 5, 15 * 60, 15 * 60
GLOBAL_MAX_FAILS, GLOBAL_WINDOW, GLOBAL_LOCK = 40, 10 * 60, 10 * 60

STATIC = {
    "/": ("index.html", "text/html; charset=utf-8"),
    "/static/app.js": ("static/app.js", "application/javascript; charset=utf-8"),
    "/static/qrcode.js": ("static/qrcode.js", "application/javascript; charset=utf-8"),
    "/static/style.css": ("static/style.css", "text/css; charset=utf-8"),
}

CSP = ("default-src 'none'; script-src 'self'; style-src 'self'; "
       "img-src 'self' data:; connect-src 'self'; base-uri 'none'; "
       "form-action 'self'; frame-ancestors 'none'")


# ---------------------------------------------------------------- udpspeeder
def quic_info():
    """wg-quic (devices may send the FEC tunnel dressed up as QUIC): None when it is not installed, else port, name and state."""
    try:
        env = {}
        with open(QUIC_ENV) as f:
            for line in f:
                k, _, val = line.strip().partition("=")
                env[k] = val
        port = int(env["QUIC_PORT"])
        sni = env.get("QUIC_SNI", "")
        if not 1 <= port <= 65535 or not re.fullmatch(r"[A-Za-z0-9.-]{1,100}", sni):
            return None
    except Exception:
        return None
    try:
        state = subprocess.run(["systemctl", "is-active", QUIC_UNIT], capture_output=True, text=True, timeout=5).stdout.strip()
    except Exception:
        state = "unknown"
    return {"port": port, "sni": sni, "state": state}


def probe_info():
    """The link-test probe service: the port devices should test against, and whether it is running."""
    try:
        state = subprocess.run(["systemctl", "is-active", PROBE_UNIT], capture_output=True, text=True, timeout=5).stdout.strip()
    except Exception:
        state = "unknown"
    return {"port": PROBE_PORT, "state": state}


def levels_info():
    """The FEC levels (see wg-admin) with the state of each level's service, or None when levels are not set up."""
    try:
        with open(LEVELS_FILE) as f:
            d = json.load(f)
        levels = d["levels"]
        for l in levels:
            if not (re.fullmatch(r"[a-z]{3,16}", l["id"]) and isinstance(l["port"], int) and isinstance(l["loss"], int)):
                return None
        default = d["default"]
        if not any(l["id"] == default for l in levels):
            return None
    except (OSError, ValueError, KeyError, TypeError):
        return None
    units = [LEVEL_UNIT % l["id"] for l in levels if l.get("instance")]
    states = {}
    if units:
        try:
            out = subprocess.run(["systemctl", "is-active"] + units, capture_output=True, text=True, timeout=5).stdout.split()
            states = dict(zip(units, out))
        except Exception:
            pass
    for l in levels:
        l["state"] = states.get(LEVEL_UNIT % l["id"], "unknown") if l.get("instance") else "n/a"
    return {"default": default, "levels": levels}


def tiers_for_app(info):
    """The levels the Android app may switch between, weakest first: where to connect and the uplink settings to use."""
    out = []
    for l in (info.get("levels") or []):
        if l.get("auto") and l.get("instance") and l.get("state") == "active":
            out.append({"n": l["name"], "l": l["loss"], "p": l["port"], "u": l["up"]["fec"], "t": l["up"]["timeout"],
                        "i": l["up"]["interval"], "d": l["db"], "s": l["sb"]})
    return sorted(out, key=lambda t: t["l"])


def speeder_info():
    """Settings of the server-side speederv2, or None when it is not configured."""
    try:
        with open(SPEEDER_ENV) as f:
            raw = f.read()
    except OSError:
        return None
    env = {}
    for line in raw.splitlines():
        if "=" in line and not line.lstrip().startswith("#"):
            k, v = line.split("=", 1)
            env[k.strip()] = v.strip()
    try:
        key, port = env["SPEEDER_KEY"], int(env["SPEEDER_PORT"])
        fec = env["SPEEDER_FEC"]
        timeout, interval = int(env["SPEEDER_TIMEOUT"]), int(env["SPEEDER_INTERVAL"])
    except (KeyError, ValueError):
        return None
    if not (re.match(r"^[A-Za-z0-9]{8,64}$", key) and re.match(r"^[0-9:,]{1,200}$", fec)):
        return None
    try:
        active = subprocess.run(["systemctl", "is-active", "wg-speeder"], capture_output=True,
                                text=True, timeout=5).stdout.strip()
    except Exception:
        active = "unknown"
    def num(k, default=None):
        try:
            return int(env[k])
        except (KeyError, ValueError):
            return default
    fec_up = env.get("SPEEDER_FEC_UP", fec)
    if not re.match(r"^[0-9:,]{1,300}$", fec_up):
        fec_up = fec
    li = levels_info()
    return {"key": key, "port": port, "fec": fec, "timeout": timeout, "interval": interval,
            "levels": li["levels"] if li else None, "default_level": li["default"] if li else None,
            "fec_up": fec_up, "timeout_up": num("SPEEDER_TIMEOUT_UP", timeout),
            "interval_up": num("SPEEDER_INTERVAL_UP", interval),
            "fec_mtu": num("SPEEDER_MTU"), "decode_buf": num("SPEEDER_DECODE_BUF"), "sock_buf": num("SPEEDER_SOCK_BUF"),
            "active": active, "required": env.get("SPEEDER_REQUIRED") == "1",
            "default_via": "speeder" if env.get("SPEEDER_DEFAULT") == "speeder" else "direct"}


def resolve_v4(host):
    """speederv2 only accepts an IPv4 address after -r, so resolve the endpoint host."""
    try:
        import socket
        return socket.getaddrinfo(host, None, socket.AF_INET, socket.SOCK_DGRAM)[0][4][0]
    except Exception:
        return host


def speeder_client_cmd(info, host):
    rhost, rport = resolve_v4(host), info["port"]
    # The client only encodes the UPLINK, so it gets the uplink settings; the server's -f covers the downlink.
    cmd = ("speederv2 -c -l127.0.0.1:%d -r%s:%d -k %s -f%s --timeout %d -i %d --mode 0"
           % (SPEEDER_LOCAL_PORT, rhost, rport, info["key"], info.get("fec_up", info["fec"]),
              info.get("timeout_up", info["timeout"]), info.get("interval_up", info["interval"])))
    if info.get("fec_mtu"):
        cmd += " --mtu %d" % info["fec_mtu"]
    if info.get("decode_buf"):
        cmd += " --decode-buf %d" % info["decode_buf"]        # the decoder has to hold the whole (large) downlink groups
    if info.get("sock_buf"):
        cmd += " --sock-buf %d" % info["sock_buf"]
    return cmd + " --report 60"


def exclude_server_route(config, server_ip):
    """Full-tunnel configs route everything into wg0, including speederv2's own packets to this
    server (a loop: wg0 -> speederv2 -> wg0). Carve the server's /32 out of 0.0.0.0/0 so those
    packets keep using the normal route. Done in AllowedIPs, so no PostUp scripts are needed
    (the Windows client does not run them)."""
    m = re.search(r"^AllowedIPs = (.+)$", config, re.M)
    if not m:
        return config
    items = [x.strip() for x in m.group(1).split(",")]
    if "0.0.0.0/0" not in items:
        return config
    try:
        ip = ipaddress.ip_address(server_ip)
    except ValueError:
        return config
    if ip.version != 4:
        return config
    nets = sorted(ipaddress.ip_network("0.0.0.0/0").address_exclude(ipaddress.ip_network("%s/32" % ip)),
                  key=lambda n: (int(n.network_address), n.prefixlen))
    # ::/0 cannot stay as is: wg-quick (Linux) then runs a plain "ip -6 route add ::/0 dev wg0",
    # which collides with the physical default route (File exists) or loses the tie, leaking IPv6.
    # Two more-specific halves always win.
    rest = []
    for x in items:
        if x == "0.0.0.0/0":
            continue
        rest.extend(["::/1", "8000::/1"] if x == "::/0" else [x])
    new = [str(n) for n in nets] + rest
    return config[:m.start(1)] + ", ".join(new) + config[m.end(1):]


def client_cmds(info, host, via="speeder"):
    """The client-side program a connection type needs."""
    return {"speeder": speeder_client_cmd(info, host), "port": SPEEDER_LOCAL_PORT}


def via_ready(info, via):
    """None when the connection type can be offered, otherwise the reason it cannot."""
    if via == "direct":
        return None
    if info["active"] != "active":
        return "The UDPspeeder service is not running"
    return None


def speeder_cmd_of(cmds):
    """Legacy single-command field: the command a plain speederv2 setup needs."""
    return cmds.get("speeder")


def speederize(config, info, via="speeder"):
    """Turn a direct client config into one that goes through the local client programs."""
    m = re.search(r"^Endpoint = (.+):[0-9]+$", config, re.M)
    if not m:
        raise ValueError("no endpoint in config")
    host = m.group(1)
    cmds = client_cmds(info, host, via)
    config = re.sub(r"^Endpoint = .*$", "Endpoint = 127.0.0.1:%d" % cmds["port"], config, flags=re.M)
    config = re.sub(r"^MTU = .*$", "MTU = %d" % SPEEDER_CLIENT_MTU, config, flags=re.M)
    config = exclude_server_route(config, resolve_v4(host))
    return config, cmds, host


# ---------------------------------------------------------------- passwords
def hash_password(password):
    salt = secrets.token_bytes(16)
    n, r, p = 2 ** 15, 8, 1
    dk = hashlib.scrypt(password.encode(), salt=salt, n=n, r=r, p=p,
                        dklen=32, maxmem=128 * 1024 * 1024)
    return "scrypt$%d$%d$%d$%s$%s" % (n, r, p, base64.b64encode(salt).decode(),
                                      base64.b64encode(dk).decode())


def verify_password(password, stored):
    try:
        scheme, n, r, p, salt, h = stored.split("$")
        if scheme != "scrypt":
            return False
        want = base64.b64decode(h)
        dk = hashlib.scrypt(password.encode(), salt=base64.b64decode(salt),
                            n=int(n), r=int(r), p=int(p), dklen=len(want),
                            maxmem=128 * 1024 * 1024)
        return hmac.compare_digest(dk, want)
    except Exception:
        return False


def set_password_cli():
    pw = sys.stdin.readline().rstrip("\n")
    if len(pw) < MIN_PW:
        sys.exit("password must be at least %d characters" % MIN_PW)
    os.makedirs(os.path.dirname(CONFIG_PATH), mode=0o750, exist_ok=True)
    fd = os.open(CONFIG_PATH, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
    with os.fdopen(fd, "w") as f:
        json.dump({"password_hash": hash_password(pw)}, f)
    print("password hash written to", CONFIG_PATH)


# ---------------------------------------------------------------- state
class State:
    def __init__(self):
        self.lock = threading.Lock()
        self.sessions = {}          # token -> {"exp": ts, "csrf": str}
        self.ip_fails = {}          # ip -> [timestamps]
        self.ip_locked = {}         # ip -> until
        self.global_fails = []
        self.global_locked = 0.0

    def locked_for(self, ip):
        now = time.time()
        with self.lock:
            return max(self.ip_locked.get(ip, 0), self.global_locked, now) - now

    def record_fail(self, ip):
        now = time.time()
        with self.lock:
            fl = [t for t in self.ip_fails.get(ip, []) if now - t < IP_WINDOW] + [now]
            self.ip_fails[ip] = fl
            if len(fl) >= IP_MAX_FAILS:
                self.ip_locked[ip] = now + IP_LOCK
                self.ip_fails[ip] = []
            self.global_fails = [t for t in self.global_fails if now - t < GLOBAL_WINDOW] + [now]
            if len(self.global_fails) >= GLOBAL_MAX_FAILS:
                self.global_locked = now + GLOBAL_LOCK
                self.global_fails = []

    def record_ok(self, ip):
        with self.lock:
            self.ip_fails.pop(ip, None)

    def new_session(self):
        token, csrf = secrets.token_urlsafe(32), secrets.token_urlsafe(32)
        now = time.time()
        with self.lock:
            self.sessions = {k: v for k, v in self.sessions.items() if v["exp"] > now}
            self.sessions[token] = {"exp": now + SESSION_TTL, "csrf": csrf}
        return token, csrf

    def get_session(self, token):
        if not token:
            return None
        with self.lock:
            s = self.sessions.get(token)
            if s and s["exp"] > time.time():
                return s
            self.sessions.pop(token, None)
            return None

    def drop_session(self, token):
        with self.lock:
            self.sessions.pop(token, None)


STATE = State()
CONFIG = {}
FILES = {}


# ---------------------------------------------------------------- wg-peer
def call_backend(cmd, *args, timeout=30):
    try:
        p = subprocess.run(cmd + list(args), capture_output=True, text=True, timeout=timeout)
    except subprocess.TimeoutExpired:
        return 504, {"error": "Operation timed out"}
    try:
        data = json.loads(p.stdout)
    except Exception:
        return 500, {"error": "Backend error"}
    if p.returncode == 0:
        return 200, data
    msg = data.get("error", "Backend error")
    if msg.startswith("levels: "):
        return 400, {"error": msg[len("levels: "):][:200]}          # sanitised by wg-admin
    if msg.startswith("forward: "):
        return 400, {"error": msg[len("forward: "):][:200]}        # sanitised by wg-admin
    if "already exists" in msg:
        return 409, {"error": "A device with this name already exists"}
    if "invalid name" in msg:
        return 400, {"error": "Name: letters, digits, '_', '-', '.' only, max 32 characters"}
    if "no such managed device" in msg:
        return 404, {"error": "Device not found"}
    if "no saved config" in msg:
        return 404, {"error": "No saved config for this device (it was created before configs were kept). Delete it and add it again to get a new one."}
    if "saved key is damaged" in msg:
        return 500, {"error": "The saved key is damaged. Delete the device and add it again."}
    if "address already in use" in msg:
        return 409, {"error": "That address is already in use"}
    if "out of range" in msg or "invalid address" in msg:
        return 400, {"error": "Address must be 10.99.0.2 - 10.99.0.254"}
    if "pool exhausted" in msg:
        return 409, {"error": "Address pool exhausted"}
    if "in progress" in msg:
        return 409, {"error": "Another service operation is in progress"}
    if "systemctl" in msg:
        return 500, {"error": msg}          # already sanitised by wg-admin
    return 500, {"error": "Operation failed; check the server logs"}


def call_wg_peer(*args):
    return call_backend(WG_PEER, *args)


def call_wg_admin(*args, timeout=30):
    return call_backend(WG_ADMIN, *args, timeout=timeout)


# ---------------------------------------------------------------- handler
class Handler(http.server.BaseHTTPRequestHandler):
    server_version = "wg-web"
    sys_version = ""
    protocol_version = "HTTP/1.1"

    def log_message(self, fmt, *args):
        sys.stderr.write("%s %s\n" % (self.client_ip(), fmt % args))

    # -- helpers
    def client_ip(self):
        if TRUST_CF:
            return (self.headers.get("CF-Connecting-IP") or self.client_address[0])[:64]
        return self.client_address[0][:64]

    def send_bytes(self, status, body, ctype, extra=None, cache=False):
        self.send_response(status)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Content-Security-Policy", CSP)
        self.send_header("X-Content-Type-Options", "nosniff")
        self.send_header("X-Frame-Options", "DENY")
        self.send_header("Referrer-Policy", "no-referrer")
        self.send_header("Cache-Control", "no-cache" if cache else "no-store")
        for k, v in (extra or []):
            self.send_header(k, v)
        self.end_headers()
        if self.command != "HEAD":
            self.wfile.write(body)

    def send_json(self, status, obj, extra=None):
        self.send_bytes(status, json.dumps(obj, ensure_ascii=False).encode(),
                        "application/json; charset=utf-8", extra)

    def session(self):
        raw = self.headers.get("Cookie", "")
        try:
            c = http.cookies.SimpleCookie(raw)
        except http.cookies.CookieError:
            return None, None
        tok = c[COOKIE].value if COOKIE in c else None
        return tok, STATE.get_session(tok)

    def read_json(self):
        try:
            n = int(self.headers.get("Content-Length", "0"))
        except ValueError:
            return None
        if n < 0 or n > MAX_BODY:
            return None
        try:
            obj = json.loads(self.rfile.read(n) or b"{}")
        except Exception:
            return None
        return obj if isinstance(obj, dict) else None

    def mutation_allowed(self, sess):
        """CSRF: session token header + same-origin check."""
        if not hmac.compare_digest(self.headers.get("X-CSRF-Token", ""), sess["csrf"]):
            return False
        origin = self.headers.get("Origin")
        if origin:
            host = self.headers.get("Host", "")
            if urllib.parse.urlparse(origin).netloc != host:
                return False
        return True

    # -- routes
    def do_HEAD(self):
        self.do_GET()

    def do_GET(self):
        path = urllib.parse.urlparse(self.path).path
        if path in STATIC:
            body, ctype = FILES[path]
            return self.send_bytes(200, body, ctype, cache=True)
        if path == "/api/me":
            _, sess = self.session()
            if not sess:
                return self.send_json(401, {"error": "unauthorized"})
            return self.send_json(200, {"csrf": sess["csrf"]})
        if path == "/api/speeder":
            _, sess = self.session()
            if not sess:
                return self.send_json(401, {"error": "unauthorized"})
            info = speeder_info()
            if not info:
                return self.send_json(200, {"enabled": False})
            avail = ["speeder"]
            return self.send_json(200, dict(
                info, enabled=True, local_port=SPEEDER_LOCAL_PORT, mtu=SPEEDER_CLIENT_MTU,
                cmd=speeder_client_cmd(info, SPEEDER_HOST),
                modes=dict((v, client_cmds(info, SPEEDER_HOST, v)) for v in avail),
                running=[v for v in avail if via_ready(info, v) is None], probe=probe_info(), quic=quic_info()))
        m = re.match(r"^/api/peers/([A-Za-z0-9_.-]{1,32})/config$", path)
        if m:
            _, sess = self.session()
            if not sess:
                return self.send_json(401, {"error": "unauthorized"})
            q = urllib.parse.parse_qs(urllib.parse.urlparse(self.path).query)
            asked = (q.get("via") or [None])[0]
            if asked is not None and asked not in VIA_MODES:
                return self.send_json(400, {"error": "Invalid connection type"})
            status, data = call_wg_peer("show", m.group(1))
            if status != 200:
                return self.send_json(status, data)
            info1 = speeder_info()
            via = asked
            if via is None:
                saved = data.get("via")
                if saved in VIA_MODES and (saved == "direct" or (info1 and via_ready(info1, saved) is None)):
                    via = saved
                elif info1 and (info1.get("required") or info1.get("default_via") == "speeder") and via_ready(info1, "speeder") is None:
                    via = "speeder"
                else:
                    via = "direct"
            data["via"] = via
            sinfo = None
            if via != "direct":
                sinfo = info1
                why = "UDPspeeder is not configured" if not sinfo else via_ready(sinfo, via)
                if why:
                    return self.send_json(400, {"error": why})
            if status == 200:
                if sinfo:
                    try:
                        data["config"], cmds, _ = speederize(data["config"], sinfo, via)
                        data["cmds"], data["speeder_cmd"], data["via"], data["speeder"] = cmds, speeder_cmd_of(cmds), via, True
                        if via == "speeder" and tiers_for_app(sinfo):
                            data["tiers"] = tiers_for_app(sinfo)
                    except ValueError:
                        return self.send_json(500, {"error": "Could not build the client config"})
                pr = probe_info()
                if pr["state"] == "active":
                    data["probe"] = pr["port"]            # the app tests the link to this UDP port of the server
                qi = quic_info()
                if qi and qi["state"] == "active" and data.get("speeder"):
                    data["quic"] = {"p": qi["port"], "s": qi["sni"]}      # the app may send the FEC tunnel to this port as QUIC
                sys.stderr.write("%s viewed config of %s (%s)\n" % (self.client_ip(), m.group(1), via))
            return self.send_json(status, data)
        if path in ("/api/peers", "/api/status", "/api/config", "/api/logs", "/api/forwards"):
            _, sess = self.session()
            if not sess:
                return self.send_json(401, {"error": "unauthorized"})
            if path == "/api/peers":
                status, data = call_wg_peer("list")
            elif path == "/api/status":
                status, data = call_wg_admin("status")
            elif path == "/api/config":
                status, data = call_wg_admin("config")
            elif path == "/api/forwards":
                status, data = call_wg_admin("forward", "list")
            else:
                q = urllib.parse.parse_qs(urllib.parse.urlparse(self.path).query)
                source = (q.get("source") or ["wg-service"])[0]
                lines = (q.get("lines") or ["100"])[0]
                if source not in LOG_SOURCES or not re.match(r"^[0-9]{1,3}$", lines):
                    return self.send_json(400, {"error": "Invalid log request"})
                status, data = call_wg_admin("logs", source, lines)
            return self.send_json(status, data)
        return self.send_json(404, {"error": "not found"})

    def do_POST(self):
        path = urllib.parse.urlparse(self.path).path
        if path == "/api/login":
            return self.login()
        _, sess = self.session()
        if not sess:
            return self.send_json(401, {"error": "unauthorized"})
        if not self.mutation_allowed(sess):
            return self.send_json(403, {"error": "forbidden"})
        if path == "/api/logout":
            tok, _ = self.session()
            STATE.drop_session(tok)
            return self.send_json(200, {"ok": True}, [("Set-Cookie", self.cookie("", 0))])
        if path == "/api/service":
            body = self.read_json()
            action = (body or {}).get("action", "")
            if action not in SERVICE_ACTIONS:
                return self.send_json(400, {"error": "Invalid action"})
            sys.stderr.write("%s service %s requested\n" % (self.client_ip(), action))
            status, data = call_wg_admin("service", action, timeout=100)
            return self.send_json(status, data)
        if path == "/api/speeder/levels":
            body = self.read_json()
            if not isinstance(body, dict) or set(body) - {"default", "mtu", "levels"}:
                return self.send_json(400, {"error": "Invalid request"})
            raw = json.dumps(body, separators=(",", ":")).encode()
            if len(raw) > 20000:
                return self.send_json(400, {"error": "Request too large"})
            sys.stderr.write("%s changes FEC levels (%s)\n" % (self.client_ip(), ", ".join(sorted(body))))
            return self.send_json(*call_wg_admin("levels-set", base64.b64encode(raw).decode(), timeout=120))
        if path == "/api/forwards":
            body = self.read_json() or {}
            name, proto, device, lan_ip = body.get("name"), body.get("proto"), body.get("device"), body.get("lan_ip")
            port, target, src = body.get("port"), body.get("target_port"), body.get("src", [])
            if not (isinstance(name, str) and NAME_RE.match(name)):
                return self.send_json(400, {"error": "Invalid name"})
            if (device in (None, "")) == (lan_ip in (None, "")):
                return self.send_json(400, {"error": "Choose either a device or an address on the home network"})
            if lan_ip not in (None, ""):
                if not (isinstance(lan_ip, str) and re.match(r"^[0-9]{1,3}(\.[0-9]{1,3}){3}$", lan_ip)):
                    return self.send_json(400, {"error": "Enter an IPv4 address"})
                device = lan_ip
            elif not (isinstance(device, str) and NAME_RE.match(device)) or re.match(r"^[0-9]{1,3}(\.[0-9]{1,3}){3}$", device):
                return self.send_json(400, {"error": "Invalid device"})
            if proto not in ("tcp", "udp", "both"):
                return self.send_json(400, {"error": "Protocol must be TCP, UDP or both"})
            for v in (port, target):
                if isinstance(v, bool) or not isinstance(v, int):
                    return self.send_json(400, {"error": "Ports must be numbers"})
            if not isinstance(src, list) or len(src) > 8 or not all(isinstance(x, str) and re.match(r"^[0-9./]{7,18}$", x) for x in src):
                return self.send_json(400, {"error": "Allowed sources must be IPv4 addresses or CIDR ranges"})
            sys.stderr.write("%s adds port forward %s (%s %s -> %s:%s)\n" % (self.client_ip(), name, proto, port, device, target))
            return self.send_json(*call_wg_admin("forward", "add", name, proto, str(port), device, str(target), ",".join(src) or "-", timeout=60))
        m = re.match(r"^/api/forwards/([A-Za-z0-9_.-]{1,32})/enabled$", path)
        if m:
            en = (self.read_json() or {}).get("enabled")
            if not isinstance(en, bool):
                return self.send_json(400, {"error": "Invalid request"})
            sys.stderr.write("%s %s port forward %s\n" % (self.client_ip(), "enables" if en else "disables", m.group(1)))
            return self.send_json(*call_wg_admin("forward", "enable" if en else "disable", m.group(1), timeout=60))
        m = re.match(r"^/api/peers/([A-Za-z0-9_.-]{1,32})/mode$", path)
        if m:
            via = (self.read_json() or {}).get("via")
            if via not in VIA_MODES:
                return self.send_json(400, {"error": "Invalid connection type"})
            status, data = call_wg_peer("setmode", m.group(1), via)
            return self.send_json(status, data)
        if path == "/api/peers":
            body = self.read_json()
            name = (body or {}).get("name", "")
            if not isinstance(name, str) or not NAME_RE.match(name):
                return self.send_json(400, {"error": "Name: letters, digits, '_', '-', '.' only, max 32 characters"})
            sinfo = None
            info0 = speeder_info()
            # a device is not tied to a connection type (its config can be opened as either); the form does not ask, so the
            # server's own default is used, and a client of the API may still name one
            via = (body or {}).get("via")
            if via is None:
                via = "speeder" if info0 and (info0.get("required") or info0.get("default_via") == "speeder") and via_ready(info0, "speeder") is None else "direct"
            if via not in VIA_MODES:
                return self.send_json(400, {"error": "Invalid connection type"})
            if info0 and info0.get("required") and via == "direct":
                via = "speeder"          # this server only accepts FEC connections
            if via != "direct":
                sinfo = info0
                why = "UDPspeeder is not configured" if not sinfo else via_ready(sinfo, via)
                if why:
                    return self.send_json(400, {"error": why})
            ip = (body or {}).get("ip")
            if ip in (None, ""):
                status, data = call_wg_peer("add", name)
            elif isinstance(ip, str) and re.match(r"^10\.99\.0\.[0-9]{1,3}$", ip):
                status, data = call_wg_peer("add", name, ip)
            else:
                return self.send_json(400, {"error": "Address must be 10.99.0.2 - 10.99.0.254"})
            if status == 200:
                call_wg_peer("setmode", name, via)          # remember how this device connects
                data["via"] = via
                sys.stderr.write("%s added device %s (%s)%s\n" % (
                    self.client_ip(), name, data.get("ip"), " via %s" % via if sinfo else ""))
                if sinfo:
                    try:
                        data["config"], cmds, _ = speederize(data["config"], sinfo, via)
                        data["cmds"], data["speeder_cmd"], data["via"], data["speeder"] = cmds, speeder_cmd_of(cmds), via, True
                    except ValueError:
                        return self.send_json(500, {"error": "Device was created, but its client config could not be built. Delete it and retry."})
            return self.send_json(status, data)
        return self.send_json(404, {"error": "not found"})

    def do_DELETE(self):
        path = urllib.parse.urlparse(self.path).path
        _, sess = self.session()
        if not sess:
            return self.send_json(401, {"error": "unauthorized"})
        if not self.mutation_allowed(sess):
            return self.send_json(403, {"error": "forbidden"})
        m = re.match(r"^/api/forwards/([A-Za-z0-9_.-]{1,32})$", path)
        if m:
            sys.stderr.write("%s removes port forward %s\n" % (self.client_ip(), m.group(1)))
            return self.send_json(*call_wg_admin("forward", "remove", m.group(1), timeout=60))
        m = re.match(r"^/api/peers/([A-Za-z0-9_.-]{1,32})$", path)
        if not m:
            return self.send_json(404, {"error": "not found"})
        status, data = call_wg_peer("remove", m.group(1))
        if status == 200:
            sys.stderr.write("%s removed device %s\n" % (self.client_ip(), m.group(1)))
            call_wg_admin("forward", "reconcile", timeout=60)       # stop forwarding to a device that no longer exists
        return self.send_json(status, data)

    def cookie(self, value, max_age):
        return "%s=%s; Path=/; HttpOnly; Secure; SameSite=Strict; Max-Age=%d" % (COOKIE, value, max_age)

    def login(self):
        ip = self.client_ip()
        wait = STATE.locked_for(ip)
        if wait > 0:
            return self.send_json(429, {"error": "Too many attempts. Try again later."},
                                  [("Retry-After", str(int(wait) + 1))])
        body = self.read_json()
        pw = (body or {}).get("password", "")
        ok = isinstance(pw, str) and 0 < len(pw) <= 256 and \
            verify_password(pw, CONFIG.get("password_hash", ""))
        if not ok:
            STATE.record_fail(ip)
            sys.stderr.write("%s login failed\n" % ip)
            time.sleep(0.5)
            return self.send_json(401, {"error": "Wrong password"})
        STATE.record_ok(ip)
        token, csrf = STATE.new_session()
        sys.stderr.write("%s login ok\n" % ip)
        return self.send_json(200, {"csrf": csrf},
                              [("Set-Cookie", self.cookie(token, SESSION_TTL))])


def main():
    global CONFIG
    if "--set-password" in sys.argv:
        return set_password_cli()
    with open(CONFIG_PATH) as f:
        CONFIG = json.load(f)
    if not CONFIG.get("password_hash"):
        sys.exit("no password configured; run: app.py --set-password")
    for url, (rel, ctype) in STATIC.items():
        with open(os.path.join(BASE_DIR, rel), "rb") as f:
            FILES[url] = (f.read(), ctype)
    if TLS_CERT and TLS_KEY:
        ctx = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
        ctx.minimum_version = ssl.TLSVersion.TLSv1_2
        ctx.load_cert_chain(TLS_CERT, TLS_KEY)
        class TLSServer(http.server.ThreadingHTTPServer):
            def get_request(self):
                sock, addr = self.socket.accept()
                return ctx.wrap_socket(sock, server_side=True, do_handshake_on_connect=False), addr
            def handle_error(self, request, client_address):
                pass            # scanners and aborted handshakes are not worth a traceback
        class TLSHandler(Handler):
            def setup(self):
                self.request.settimeout(10)
                self.request.do_handshake()     # in the per-connection thread, never the accept loop
                super().setup()
        srv = TLSServer(BIND, TLSHandler)
    else:
        srv = http.server.ThreadingHTTPServer(BIND, Handler)
    srv.daemon_threads = True
    sys.stderr.write("wg-web listening on %s:%d\n" % BIND)
    try:
        srv.serve_forever()
    except KeyboardInterrupt:
        pass


if __name__ == "__main__":
    main()
