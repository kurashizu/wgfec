# Tests for the FEC level code in wg-admin, with systemctl mocked. Run: python3 server/tests/test_levels.py hub   (or exit)
import base64, importlib.machinery, importlib.util, json, os, sys, tempfile
which = sys.argv[1]
path = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", which, "wg-admin")
loader = importlib.machinery.SourceFileLoader("wgadmin_" + which, path)
spec = importlib.util.spec_from_loader(loader.name, loader); m = importlib.util.module_from_spec(spec); loader.exec_module(m)
tmp = tempfile.mkdtemp()
m.SPEEDER_ENV = tmp + "/speeder.env"; m.SPEEDER_PREV = tmp + "/speeder.env.prev"; m.LEVELS_FILE = tmp + "/levels.json"
m.LEVEL_ENV = tmp + "/level-%s.env"; m.LOCKFILE = tmp + "/lock"
os.chown = lambda *a, **k: None
m.time.sleep = lambda s: None
calls, bad = [], set()
def fake_run(cmd, timeout=20, check=True):
    calls.append(cmd[1:])
    if cmd[1] == "is-active": return ("failed" if cmd[2] in bad else "active") + "\n"
    if cmd[1] == "show": return "0\n"
    return ""
m.run = fake_run
open(m.SPEEDER_ENV, "w").write("SPEEDER_KEY=abcdefgh12\nSPEEDER_PORT=62932\nSPEEDER_FEC=1:3,2:4,5:6,10:10,20:20,40:40\nSPEEDER_TIMEOUT=20\nSPEEDER_INTERVAL=5\nSPEEDER_MTU=1250\nSPEEDER_DECODE_BUF=2000\nSPEEDER_SOCK_BUF=1024\nU2R_KEY=zzzzzzzz12\n")
def req(**r): return base64.b64encode(json.dumps(r).encode()).decode()
ok = lambda c, msg: print(("PASS " if c else "FAIL ") + msg) or (c or sys.exit(1))

r = m.cmd_levels_init(); d = json.load(open(m.LEVELS_FILE))
ok(r["default"] == "custom" and [l["id"] for l in d["levels"]] == list(m.LEVEL_IDS), "init: 5 presets + custom, default custom")
ok(sorted(l["port"] for l in d["levels"] if l["instance"]) == [62940, 62941, 62942, 62943, 62944], "init: ports 62940-62944")
cu = [l for l in d["levels"] if l["id"] == "custom"][0]
ok(cu["down"]["fec"] == "1:3,2:4,5:6,10:10,20:20,40:40" and cu["port"] == 62932 and not cu["instance"], "init: custom = the current main settings")
ok(open(m.LEVEL_ENV % "balanced").read().startswith("LEVEL_PORT=62942\nLEVEL_FEC=1:3,2:5"), "init: level env file")
ok(any(c[:2] == ["enable", "--now"] and len(c) == 7 for c in calls), "init: enabled 5 instances")
before = open(m.SPEEDER_ENV).read(); calls.clear()
m.cmd_levels_init(); ok(open(m.SPEEDER_ENV).read() == before, "init again: main env untouched")

calls.clear(); r = m.cmd_levels_set(req(levels={"balanced": {"down": {"fec": "1:4,2:6"}, "db": 3000}}))
ok(r["changed"] == ["balanced"], "set: only balanced changed")
ok(open(m.LEVEL_ENV % "balanced").read().count("LEVEL_FEC=1:4,2:6") == 1 and "LEVEL_DECODE_BUF=3000" in open(m.LEVEL_ENV % "balanced").read(), "set: env file rewritten")
restarts = [c[1] for c in calls if c[0] == "restart"]
ok(restarts == ["wg-speeder-level@balanced.service"], "set: restarted only that level, not the main service: %s" % restarts)
ok("1:4,2:6" in open(m.LEVELS_FILE).read(), "set: levels.json updated")

calls.clear(); r = m.cmd_levels_set(req(default="light"))
e = open(m.SPEEDER_ENV).read()
ok("SPEEDER_FEC=1:2,2:2,5:3,10:4,20:7,40:10" in e and "SPEEDER_FEC_UP=1:1,2:1,4:1,8:1,16:2,32:3" in e and "SPEEDER_TIMEOUT=15" in e, "default=light: main env mirrors the light level")
ok("U2R_KEY=zzzzzzzz12" in e and "SPEEDER_KEY=abcdefgh12" in e and "SPEEDER_MTU=1250" in e, "default=light: other keys untouched")
ok([c[1] for c in calls if c[0] == "restart"] == ["wg-speeder.service"], "default=light: only the main service restarted")
calls.clear(); r = m.cmd_levels_set(req(default="light")); ok(r["changed"] == [] and not calls, "no-op request does nothing")

calls.clear(); m.cmd_levels_set(req(mtu=1200))
ok("SPEEDER_MTU=1200" in open(m.SPEEDER_ENV).read(), "mtu: written to main env")
ok(len([c for c in calls if c[0] == "restart" and "level@" in c[1]]) == 5, "mtu: all 5 level instances restarted")

for bad_req, what in [(req(levels={"light": {"down": {"fec": "3:1,2:1"}}}), "decreasing data"), (req(levels={"nope": {}}), "unknown level"),
                      (req(levels={"light": {"db": 5}}), "buffer out of range"), (req(default="x"), "unknown default"), ("%%%", "garbage")]:
    try: m.cmd_levels_set(bad_req); ok(False, "reject " + what)
    except m.Err: ok(True, "reject " + what)

snap = (open(m.LEVELS_FILE).read(), open(m.LEVEL_ENV % "maximum").read(), open(m.SPEEDER_ENV).read())
bad.add("wg-speeder-level@maximum.service")
try: m.cmd_levels_set(req(levels={"maximum": {"down": {"timeout": 50}}}, default="aggressive")); ok(False, "failing level is reported")
except m.Err as ex: ok("put back" in str(ex), "failing level: error says it was put back")
ok(snap == (open(m.LEVELS_FILE).read(), open(m.LEVEL_ENV % "maximum").read(), open(m.SPEEDER_ENV).read()), "failing level: levels.json, level env and main env all restored")
print("ALL OK", which)
