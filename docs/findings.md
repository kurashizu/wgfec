# Test findings

Measured with `tools/net-probe`. Loss is as seen by the probe, per direction. These are a handful of runs on one
residential line plus a lab host, not a study. Treat them as hints.

**Residential line with heavy downlink loss, short run:**

| Mode | Downlink loss |
|---|---|
| direct UDP | about 39% |
| FEC | about 1% |
| fake-TCP only | 10-15% |
| FEC + fake-TCP | about 1% |

**Same line, full two-round run (second round in reverse order):**

| Mode | Downlink loss |
|---|---|
| direct UDP | 14.5% |
| FEC | round 1: 0-3%, **round 2: 100% (not explained)** |
| fake-TCP only | 31-36% (worse than direct) |
| FEC + fake-TCP | 0.6-0.8% (best) |

**What we take from it**

- FEC is the big win: it turned 15-40% loss into about 1% in most runs.
- Fake-TCP alone did not help and sometimes hurt. FEC + fake-TCP was the most robust combination in the one full run.
- One FEC round failed completely. We could not tell whether it was the line, the server, or the test, so do not read
  "FEC always works" out of this.
- Fake-TCP needs a real layer-3 path. On a host behind **rootless podman with pasta** it failed (84-100% loss) because pasta
  terminates TCP and re-originates it; plain UDP and FEC still worked there.
