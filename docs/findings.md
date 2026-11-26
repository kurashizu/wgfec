# Test findings

Measured with `tools/net-probe`. Loss is as seen by the probe, per direction. These are a handful of runs on one
residential line plus a lab host, not a study. Treat them as hints.

**Residential line with heavy downlink loss, short run:**

| Mode | Downlink loss |
|---|---|
| direct UDP | about 39% |
| FEC | about 1% |

**Same line, full two-round run (second round in reverse order):**

| Mode | Downlink loss |
|---|---|
| direct UDP | 14.5% |
| FEC | round 1: 0-3%, **round 2: 100% (not explained)** |

**What we take from it**

- FEC is the big win: it turned 15-40% loss into about 1% in most runs.
- One FEC round failed completely. We could not tell whether it was the line, the server, or the test, so do not read
  "FEC always works" out of this.

## Home line with random downlink loss (2026-11-26)

Measured with `tools/fectest.py` (see [loss-test.md](loss-test.md)); one residential line, one session, a friend's computer
(route: Unicom backbone, then NTT, to a server in Sydney). Treat as a hint.

| | Result |
|---|---|
| Uplink | 0% loss from 50 to 1250 packets/s |
| Downlink | 50-67% loss at every rate from 50 packets/s, 100 and 1000 byte packets alike |
| Shape | random (the chance of a loss after a loss is about the average loss); slow drift between 37% and 67% over minutes |
| Round trip | 212 ms median |

**What we take from it**

- This is loss that does not depend on rate or size, and it comes in no bursts, so the FEC wait and group size are the
  only things to tune: big groups repair it cheaply. The **Maximum** level is built from it.
- A fixed-rate group designed for the average (55%) fails when the loss drifts up (about 60-65%). We design for the worst
  period instead, which is why Maximum is sized for 68%.
- The same test on a server whose cloud network drops unsolicited inbound UDP (the first lab host) showed the opposite
  case, loss from policing at about 3 Mbit/s of UDP, where more parity makes it worse. Check the rate ladder first.

