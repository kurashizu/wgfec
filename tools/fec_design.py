#!/usr/bin/env python3
"""fec_design.py: compute a speederv2 -f tier table for a link that drops packets at random.

    python3 tools/fec_design.py --loss 0.68                 # the table used by the "maximum" level
    python3 tools/fec_design.py --loss 0.40 --target 1e-3

Model: every packet of a group (x data + y parity) is lost independently with probability --loss. The group is rebuilt
when at least x of the x+y packets arrive. For each tier x it finds the smallest y whose expected share of data packets
that stay lost (the "residual") is at most --target. Standard library only.

Limits: independent losses only (bursts need a longer wait/interleave, see docs/loss-test.md); x + y must stay <= 255.
"""
import argparse
import math

DEFAULT_TIERS = (1, 2, 3, 5, 8, 12, 16, 24, 32, 40, 48)


def residual(x, y, p):
    """Expected fraction of the x data packets that are lost and cannot be rebuilt."""
    n = x + y
    q = 1.0 - p
    total = 0.0
    for j in range(x):                      # j packets arrive, fewer than x: the group fails
        total += math.comb(n, j) * q ** j * p ** (n - j) * (1.0 - j / n)
    return total


def min_parity(x, p, target, max_total=255):
    for y in range(0, max_total - x + 1):
        if residual(x, y, p) <= target:
            return y
    return None


def design(loss, target=1e-4, tiers=DEFAULT_TIERS, max_total=255):
    """List of (x, y) for the tiers that can reach the target; tiers that cannot are left out."""
    out = []
    for x in tiers:
        y = min_parity(x, loss, target, max_total)
        if y is not None:
            out.append((x, y))
    return out


def table_string(pairs):
    return ",".join("%d:%d" % xy for xy in pairs)


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--loss", type=float, required=True, help="random packet loss to survive, 0-1 (0.68 = 68%%)")
    ap.add_argument("--target", type=float, default=1e-4, help="residual loss wanted (default 1e-4 = 0.01%%)")
    ap.add_argument("--tiers", default=",".join(map(str, DEFAULT_TIERS)), help="data counts x, comma separated")
    a = ap.parse_args()
    pairs = design(a.loss, a.target, [int(v) for v in a.tiers.split(",")])
    print("-f%s" % table_string(pairs))
    for x, y in pairs:
        print("  %3d:%-3d  traffic x%.1f  residual %.5f%%" % (x, y, (x + y) / x, 100 * residual(x, y, a.loss)))
    skipped = [int(v) for v in a.tiers.split(",") if int(v) not in dict(pairs)]
    if skipped:
        print("  not reachable within 255 packets per group: %s" % skipped)


if __name__ == "__main__":
    main()
