# Loss test and the "Maximum" FEC level

`tools/fectest.py` measures a line end to end with UDP only, in one run, and suggests FEC settings. It was written to
find out how bad a real home line is and what it takes to repair it. Its screen output is in Chinese.

## Running it

One side runs the server on a host that can receive UDP (a throwaway VM is fine, not your production hub):

    python3 tools/fectest.py server --key SOMETHING-RANDOM --port 5201

The line under test runs the client (Windows, Linux or macOS, Python 3.8 or newer, no packages):

    python fectest.py client SERVER_IP --key SOMETHING-RANDOM            # about 20 minutes
    python fectest.py client SERVER_IP --key SOMETHING-RANDOM --quick    # about 8 minutes

Run it where the loss is worst, in the evening, with nothing else using the line. All control packets are signed with the
key, so only someone who knows it can start a test. The server limits rate, duration and bytes per address and never
sends to an address that did not ask. Stop it when the test is done: `fectest.py selftest` checks the whole thing on one
machine with simulated loss.

Options worth knowing: `--rates` (traffic levels in packets per second, default 100,300,800), `--dmax` (extra delay you
accept, ms, default 100), `--target-loss` (residual loss wanted, %, default 0.1), `--max-mbps`, `--size`, `--out`.

## What it does

1. Environment: ICMP ping and a route trace, what the server sees as your address.
2. UDP round trip time and jitter, loss split into uplink and downlink.
3. A rate ladder in each direction (is there a limit on UDP, and is loss tied to the rate?) and a burst test.
4. Long runs at each traffic level, in each direction: loss, run lengths of lost packets, worst second.
5. FEC search: candidate group sizes and parity counts are first replayed on the measured loss trace, then the best ones
   are tried for real on the line, each direction separately. It prints the best setting inside the delay budget and the
   smallest delay that reaches the target.

It writes a text report, a JSON report, the loss traces (so everything can be recomputed offline) and the route trace.

## What we measured (one residential line)

| | Uplink | Downlink |
|---|---|---|
| Loss | 0% from 50 to 1250 packets/s (10 Mbit/s) | **50-67%**, from 50 packets/s up, not tied to rate or packet size |
| Shape | none | random: after a loss the next packet is lost with about the average probability; it drifted between 37% and 67% over a few minutes |
| Round trip | 160 ms minimum, 212 ms median, 287 ms p99 | |

The route left China on the Unicom backbone and crossed to the server through NTT. A multi-site ICMP check from about 330
Chinese probes to four servers showed the same pattern: Unicom lossy, Telecom around 12%, Mobile best. Treat these as
hints: one line, one session, ICMP for the multi-site numbers.

## How "Maximum" was derived

The downlink loss above is random and does not depend on how much we send. That is the case where FEC works by the law
of large numbers: with groups of x data and y parity packets, a group is rebuilt when at least x of the x+y arrive, so a
bigger group needs less relative parity for the same safety, and the floor is 1/(1-p) times the traffic.

`tools/fec_design.py` computes, for each group size x, the smallest y whose expected residual loss is at most 0.01% at a
given loss rate. For 68%:

    python3 tools/fec_design.py --loss 0.68
    -f1:23,2:29,3:34,5:43,8:55,12:70,16:83,24:109,32:133,40:156,48:178

That table, with a wait of 70 ms and an interleave of 20 ms, is the **Maximum** level (`PRESETS` in `wg-admin`, the same
values in the admin page, checked by `server/tests/test_maximum_design.py`).

What to expect, from the model (not measured on a real line at this traffic yet):

| Real downlink loss | Residual loss with the largest group (48:178) |
|---|---|
| up to 60% | below 0.001% |
| 65% | about 0.01% |
| 68% | about 0.01% (the design point) |
| 70% | about 0.1% |
| 75% | tens of percent: the group no longer fills |

- **Traffic:** 4.7 times on the downlink with big groups, up to 24 times when only a packet or two arrive per wait period.
  The uplink table of the level is unchanged.
- **Delay:** up to about 90 ms (70 ms wait + 20 ms interleave) on top of the line, in `--mode 0` where packets wait in the queue.
  Busy tunnels fill a group sooner and wait less.
- **A single TCP flow** needs the residual loss very low to be fast at 200+ ms round trip (roughly 5 Mbit/s at 0.01%,
  1.7 Mbit/s at 0.1%, by the usual loss-throughput estimate); a congestion control that ignores loss does better.
- **What it does not do:** repair long bursts. The simulated runs with loss in 50-200 ms bursts left 5-12% at a 60-100 ms
  window. If your line loses packets in bursts, a longer wait and interleave are needed, and the delay grows with them.

## Things not measured yet

- The server CPU for groups of 48 data + 178 parity packets, which is the main risk on a small VM. Watch `top` on the first run.
- Whether the line keeps its loss rate when sending 5 times the traffic (it did not change from 0.8 to 6.4 Mbit/s).
- The real behaviour of `speederv2`'s queue and `--timeout` against the model above; the model assumes the group is flushed when
  it is full or when the wait expires.
- Other lines. The level is derived from one Unicom line.
