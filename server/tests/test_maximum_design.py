# The "maximum" FEC level must match what tools/fec_design.py computes for 68% random loss (residual 0.01%).
# Run: python3 server/tests/test_maximum_design.py
import importlib.machinery, importlib.util, os, sys
here = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(here, "..", "..", "tools"))
import fec_design

want = fec_design.table_string(fec_design.design(0.68, 1e-4))
for which in ("hub", "exit"):
    loader = importlib.machinery.SourceFileLoader("wgadmin_" + which, os.path.join(here, "..", which, "wg-admin"))
    spec = importlib.util.spec_from_loader(loader.name, loader); m = importlib.util.module_from_spec(spec); loader.exec_module(m)
    lv = [p for p in m.PRESETS if p[0] == "maximum"][0]
    assert lv[2] == 68, "%s: loss label %r" % (which, lv[2])
    assert lv[3][0] == want, "%s: maximum table %s != designed %s" % (which, lv[3][0], want)
    assert lv[3][1:] == (70, 20), "%s: wait/interleave %r" % (which, lv[3][1:])
    assert len(lv[3][0]) <= 200 and len(lv[3][0].split(",")) <= 24
    print("PASS %s: maximum = %s, wait 70 ms, interleave 20 ms" % (which, want))
print("ALL OK")
