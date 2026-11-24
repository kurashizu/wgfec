#!/usr/bin/env python3
"""Compare four ways of carrying UDP to the test server, from this machine:

    direct   plain UDP
    fec      UDPspeeder (forward error correction)
    faketcp  udp2raw in fake-TCP mode
    both     UDPspeeder on top of udp2raw fake-TCP

Each way is measured with probe.py (loss per direction, latency). Everything is local and temporary:
the helper programs are started from ./bin and stopped again; nothing is installed and no setting is changed.
Pure standard library; Python 3.6+. Linux/macOS: run with sudo for the two faketcp ways; Windows: run in an
Administrator terminal (needs Npcap, https://npcap.com). Without those rights only direct and fec are run.

    python3 compare.py                 # about 8 minutes: 2 rounds, second round in reverse order
    python3 compare.py --quick         # about 2 minutes, one short round (just to see that it works)
    python3 compare.py --ip            # print this machine's public IP (to tell the server owner)
"""
import argparse
import atexit
import json
import os
import platform
import signal
import socket
import statistics
import subprocess
import sys
import time

HERE = os.path.dirname(os.path.abspath(__file__))
OS = platform.system()                       # Linux / Darwin / Windows
LOGS = os.path.join(HERE, "logs")
PROBE = os.path.join(HERE, "probe.py")
LOCAL = {"fec": 52001, "faketcp": 52002, "both_u2r": 52003, "both": 52004}
LABEL = {"direct": "direct UDP", "fec": "FEC", "faketcp": "faketcp", "both": "FEC + faketcp"}
ORDER = ["direct", "fec", "faketcp", "both"]
CHILDREN = []


def is_admin():
    if OS == "Windows":
        try:
            import ctypes
            return bool(ctypes.windll.shell32.IsUserAnAdmin())
        except Exception:
            return False
    return os.geteuid() == 0


def binaries(wepoll):
    """(udp2raw, speederv2) paths for this OS, or exit with a clear message."""
    mach = platform.machine().lower()
    if OS == "Linux":
        if mach not in ("x86_64", "amd64"):
            sys.exit("This package only has x86-64 Linux binaries (this machine: %s)." % mach)
        d = os.path.join(HERE, "bin", "linux")
        u, s = os.path.join(d, "udp2raw_amd64"), os.path.join(d, "speederv2_amd64")
    elif OS == "Darwin":
        d = os.path.join(HERE, "bin", "macos")
        m1 = mach in ("arm64", "aarch64")
        u = os.path.join(d, "udp2raw_mp_mac_m1" if m1 else "udp2raw_mp_mac")
        s = os.path.join(d, "speederv2_mac_m1" if m1 else "speederv2_mac")
    elif OS == "Windows":
        d = os.path.join(HERE, "bin", "windows")
        u = os.path.join(d, "udp2raw_mp_wepoll.exe" if wepoll else "udp2raw_mp.exe")
        s = os.path.join(d, "speederv2_wepoll.exe" if wepoll else "speederv2.exe")
    else:
        sys.exit("Unsupported OS: %s" % OS)
    for p in (u, s):
        if not os.path.exists(p):
            sys.exit("Missing file: %s" % p)
        if OS != "Windows":
            os.chmod(p, 0o755)
    return u, s


def start(cmd, logname):
    os.makedirs(LOGS, exist_ok=True)
    f = open(os.path.join(LOGS, logname), "w")
    kw = {}
    if OS == "Windows":
        kw["creationflags"] = subprocess.CREATE_NEW_PROCESS_GROUP
    p = subprocess.Popen(cmd, stdout=f, stderr=subprocess.STDOUT, cwd=HERE, **kw)
    CHILDREN.append(p)
    return p


def stop(p):
    if p.poll() is None:
        try:
            if OS == "Windows":
                p.terminate()
            else:
                p.send_signal(signal.SIGINT)   # udp2raw removes its own firewall rule on SIGINT
        except Exception:
            pass
        try:
            p.wait(timeout=6)
        except subprocess.TimeoutExpired:
            p.kill()
            p.wait(timeout=3)
    if p in CHILDREN:
        CHILDREN.remove(p)


def linux_rule_cleanup():
    """udp2raw -a adds iptables rules; remove any left behind (only ones named udp2raw*)."""
    if OS != "Linux" or not is_admin():
        return
    try:
        out = subprocess.run(["iptables", "-S"], capture_output=True, text=True, timeout=10).stdout
    except Exception:
        return
    for line in out.splitlines():
        if "udp2raw" in line and line.startswith("-A INPUT"):
            subprocess.run(["iptables"] + ["-D"] + line.split()[1:], capture_output=True, timeout=10)
    out = subprocess.run(["iptables", "-S"], capture_output=True, text=True, timeout=10).stdout
    for line in out.splitlines():
        if line.startswith("-N udp2raw"):
            subprocess.run(["iptables", "-F", line.split()[1]], capture_output=True, timeout=10)
            subprocess.run(["iptables", "-X", line.split()[1]], capture_output=True, timeout=10)


def cleanup():
    for p in list(CHILDREN):
        stop(p)
    linux_rule_cleanup()


def u2r_cmd(exe, cfg, local, port):
    """udp2raw client. Linux: real faketcp + -a (adds the iptables rule). Windows/macOS: easy-faketcp, no rule."""
    base = [exe, "-c", "-l127.0.0.1:%d" % local, "-r%s:%d" % (cfg["server"], port), "-k", cfg["u2r_key"],
            "--cipher-mode", "aes128cbc", "--auth-mode", "hmac_sha1", "--log-level", "3", "--disable-color"]
    if OS == "Linux":
        return base + ["--raw-mode", "faketcp", "-a"]
    return base + ["--raw-mode", "easy-faketcp"]


def spd_cmd(exe, cfg, local, remote):
    return [exe, "-c", "-l127.0.0.1:%d" % local, "-r%s" % remote, "-k", cfg["fec_key"], "-f" + cfg["fec_up"],
            "--timeout", "10", "-i", "0", "--mode", "0", "--mtu", "1350", "--decode-buf", "12000",
            "--sock-buf", "4096", "--disable-color", "--log-level", "3"]


def port_free(port):
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        s.bind(("127.0.0.1", port))
        return True
    except OSError:
        return False
    finally:
        s.close()


def run_probe(host, port, tag, phases):
    os.makedirs(LOGS, exist_ok=True)
    out = os.path.join(LOGS, "res-%s.json" % tag)
    if os.path.exists(out):
        os.remove(out)
    cmd = [sys.executable, "-I", PROBE, host, "--port", str(port)]
    for ph in phases:
        cmd += ["--phase", ph]
    cmd += ["--json", out]
    secs = sum(int(p.split(":")[2]) for p in phases)
    subprocess.run(cmd, capture_output=True, text=True, timeout=secs + 120)
    try:
        return json.load(open(out))["phases"]
    except Exception:
        return None


def public_ip():
    import urllib.request
    for u in ("http://ip.3322.net", "http://members.3322.org/dyndns/getip"):
        try:
            return urllib.request.urlopen(u, timeout=5).read().decode().strip()
        except Exception:
            continue
    return None


def measure(mode, cfg, exes, phases, tag):
    u2r, spd = exes
    procs = []
    try:
        if mode == "direct":
            host, port = cfg["server"], cfg["probe_port"]
        elif mode == "fec":
            procs.append(start(spd_cmd(spd, cfg, LOCAL["fec"], "%s:%d" % (cfg["server"], cfg["fec_port"])), tag + "-spd.log"))
            host, port = "127.0.0.1", LOCAL["fec"]
        elif mode == "faketcp":
            procs.append(start(u2r_cmd(u2r, cfg, LOCAL["faketcp"], cfg["u2r_port"]), tag + "-u2r.log"))
            host, port = "127.0.0.1", LOCAL["faketcp"]
        else:
            procs.append(start(u2r_cmd(u2r, cfg, LOCAL["both_u2r"], cfg["u2r_fec_port"]), tag + "-u2r.log"))
            time.sleep(2)
            procs.append(start(spd_cmd(spd, cfg, LOCAL["both"], "127.0.0.1:%d" % LOCAL["both_u2r"]), tag + "-spd.log"))
            host, port = "127.0.0.1", LOCAL["both"]
        time.sleep(3)
        dead = [p for p in procs if p.poll() is not None]
        if dead:
            return None, "helper program exited at start (see logs/%s-*.log)" % tag
        return run_probe(host, port, tag, phases), None
    finally:
        for p in procs:
            stop(p)
        linux_rule_cleanup()
        time.sleep(2)


def pct(x):
    return "-" if x is None else ("%.1f%%" % x)


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--quick", action="store_true", help="one short round (about 2 minutes)")
    ap.add_argument("--rounds", type=int, default=2)
    ap.add_argument("--ip", action="store_true", help="print this machine's public IP and exit")
    ap.add_argument("--wepoll", action="store_true", help="Windows: use the *_wepoll.exe variants")
    a = ap.parse_args()
    if a.ip:
        print(public_ip() or "could not determine the public IP")
        return
    cfg = json.load(open(os.path.join(HERE, "config.json")))
    atexit.register(cleanup)
    signal.signal(signal.SIGTERM, lambda *_: sys.exit(1))
    exes = binaries(a.wepoll)
    admin = is_admin()
    phases = ["30:200:6", "100:600:5"] if a.quick else ["30:200:20", "100:600:15"]
    rounds = 1 if a.quick else max(1, a.rounds)
    busy = [p for p in LOCAL.values() if not port_free(p)]
    if busy:
        sys.exit("Local UDP ports %s are in use (a leftover udp2raw/speederv2 from an earlier run?). Stop it and retry." % busy)
    print("OS: %s %s | python %s | admin rights: %s | rounds: %d" % (OS, platform.release(), platform.python_version(), "yes" if admin else "NO", rounds))
    if not admin:
        print("NOTE: no administrator rights, so the two faketcp ways will be skipped (run again with sudo / as Administrator).")

    # 0. is the server reachable at all?
    pre = run_probe(cfg["server"], cfg["probe_port"], "pre", ["10:100:3"])
    if not pre or not pre[0].get("echoes"):
        print("\nThe test server did not answer. Either its test window is closed, or it has not been opened for your IP.")
        print("Your public IP is: %s   (send this to whoever runs the server)" % (public_ip() or "unknown"))
        sys.exit(2)

    results = {m: [] for m in ORDER}
    skipped = {}
    for r in range(rounds):
        order = ORDER if r % 2 == 0 else list(reversed(ORDER))
        for m in order:
            if m in ("faketcp", "both") and not admin:
                skipped[m] = "needs administrator rights"
                continue
            print("[round %d/%d] %s ..." % (r + 1, rounds, LABEL[m]), flush=True)
            ph, err = measure(m, cfg, exes, phases, "r%d-%s" % (r + 1, m))
            if err:
                skipped[m] = err
                print("   skipped: %s" % err)
                continue
            if not ph:
                ph = [{"roundtrip_loss_pct": 100.0, "echoes": 0}] * len(phases)
            results[m].append(ph)
            print("   " + " | ".join("%s: round-trip loss %s" % (p.get("pps", i), pct(p.get("roundtrip_loss_pct"))) for i, p in enumerate(ph)))
            time.sleep(3)

    # summary
    lines = []
    add = lines.append
    add("")
    add("%-14s %-14s %9s %9s %9s %9s" % ("way", "phase", "up loss", "down loss", "round-trip", "rtt p50"))
    summary = {}
    for m in ORDER:
        rs = results[m]
        if not rs:
            add("%-14s %s" % (LABEL[m], "not run: %s" % skipped.get(m, "?")))
            continue
        for i, ph in enumerate(phases):
            col = [r[i] for r in rs if i < len(r)]

            def avg(key):
                v = [c.get(key) for c in col if c.get(key) is not None]
                return statistics.mean(v) if v else None
            rtt = [c["rtt_ms"]["p50"] for c in col if c.get("rtt_ms") and c["rtt_ms"].get("p50") is not None]
            row = {"up": avg("uplink_loss_pct"), "down": avg("downlink_loss_pct"), "rt": avg("roundtrip_loss_pct"),
                   "rtt": statistics.median(rtt) if rtt else None}
            summary.setdefault(m, []).append(row)
            pps, size, _ = ph.split(":")
            add("%-14s %-14s %9s %9s %9s %9s" % (LABEL[m] if i == 0 else "", "%s pps/%s B" % (pps, size), pct(row["up"]), pct(row["down"]),
                                                  pct(row["rt"]), "-" if row["rtt"] is None else "%.0f ms" % row["rtt"]))
    add("")
    notes = []
    d = summary.get("direct")
    if d:
        drt = max((x["rt"] or 0) for x in d)
        if drt < 3:
            notes.append("Direct UDP is already clean here (loss under 3%), so nothing can improve much on this line.")
        for m in ("fec", "faketcp", "both"):
            s = summary.get(m)
            if not s:
                continue
            srt = max((x["rt"] or 0) for x in s)
            if m in ("faketcp", "both") and srt > 50 and drt < 20:
                notes.append("%s lost most packets while direct UDP did not: fake-TCP does not get through on this connection "
                             "(typical when the network, a router or a container layer proxies TCP). FEC alone is the option here." % LABEL[m])
            elif srt + 1 < drt:
                notes.append("%s cut round-trip loss from %.1f%% to %.1f%%." % (LABEL[m], drt, srt))
    for n in notes:
        add("- " + n)
    add("")
    text = "\n".join(lines)
    print(text)
    info = {"os": "%s %s" % (OS, platform.release()), "python": platform.python_version(), "admin": admin,
            "time_local": time.strftime("%Y-%m-%d %H:%M:%S %z"), "phases": phases, "rounds": rounds,
            "skipped": skipped, "summary": summary, "raw": results}
    json.dump(info, open(os.path.join(HERE, "results.json"), "w"), indent=1)
    open(os.path.join(HERE, "report.txt"), "w").write(text)
    if OS != "Windows" and os.environ.get("SUDO_UID"):       # started with sudo: hand the files back to the real user
        uid, gid = int(os.environ["SUDO_UID"]), int(os.environ.get("SUDO_GID", os.environ["SUDO_UID"]))
        for root, dirs, files in os.walk(HERE):
            for n in dirs + files:
                try:
                    os.chown(os.path.join(root, n), uid, gid)
                except OSError:
                    pass
    print("Saved: results.json and report.txt in %s" % HERE)


if __name__ == "__main__":
    main()
