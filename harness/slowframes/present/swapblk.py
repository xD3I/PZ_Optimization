#!/usr/bin/env python3
"""swapblk.py <run>... [--list]: the swap blocks of a run's route window joined with the X Present flip times.
Per frame: block = swap_ret - swap_call; gpu = gpu_done (ns); flip = present.txt ust of the frame (assigned in order: the
first unassigned completion at or after the swap call). Prints the distribution of the block, its relation to the GPU
completion and to the previous frame's flip, the flip-phase on the 240 Hz grid, and the swap-return interval misses."""
import sys, statistics, collections
from pathlib import Path


def pct(v, q):
    v = sorted(v)
    return v[min(len(v) - 1, int(q / 100 * len(v)))] if v else float('nan')


def load(run):
    kv = dict(l.split('=', 1) for l in (run / 'pzopt-bench.out').read_text().splitlines() if '=' in l)
    A, B = int(kv['route_start_epoch_ms']), int(kv['route_end_epoch_ms'])
    rows = [list(map(int, l.split()[:7])) for l in (run / 'pzopt-pacing.out').read_text().splitlines() if l[:1].isdigit()]
    pres = []
    pf = run / 'present.txt'
    if pf.exists():
        for l in pf.read_text().splitlines():
            if l[:1].isdigit():
                p = l.split()
                pres.append((int(p[0]) * 1000, int(p[1]), int(p[3])))
    # assign flips in order
    j = 0
    flips = []
    for r in rows:
        while j < len(pres) and pres[j][0] < r[3]:
            j += 1
        if j < len(pres):
            flips.append(pres[j]); j += 1
        else:
            flips.append(None)
    return A, B, rows, flips, pres


def main():
    lst = '--list' in sys.argv
    for a in [x for x in sys.argv[1:] if not x.startswith('--')]:
        run = Path(a)
        A, B, rows, flips, pres = load(run)
        idx = [i for i, r in enumerate(rows) if A <= r[0] <= B and i > 0]
        blocks = [(rows[i][4] - rows[i][3]) / 1e6 for i in idx]
        # refresh period from flip ust deltas
        d = [(pres[k + 1][0] - pres[k][0]) / 1e6 for k in range(len(pres) - 1)]
        msc = [pres[k + 1][1] - pres[k][1] for k in range(len(pres) - 1)]
        per = statistics.median([x / m for x, m in zip(d, msc) if m > 0 and 0 < x / m < 20]) if pres else 0
        print(f'== {run.name}: {len(idx)} route frames, refresh period {per:.4f} ms, msc steps {collections.Counter(msc).most_common(5)}')
        print(f'  swap block ms: p50 {pct(blocks,50):.3f} p90 {pct(blocks,90):.3f} p99 {pct(blocks,99):.3f} max {max(blocks):.2f}; '
              f'>0.5: {sum(b>0.5 for b in blocks)} >1: {sum(b>1 for b in blocks)}')
        miss = collections.Counter()
        out = []
        for i in idx:
            r, p = rows[i], rows[i - 1]
            iv = (r[4] - p[4]) / 1e6
            blk = (r[4] - r[3]) / 1e6
            gpu = r[6]
            gafter = (r[4] - gpu) / 1e6 if gpu else float('nan')  # swap return after gpu done
            gcall = (gpu - r[3]) / 1e6 if gpu else float('nan')  # gpu done after swap call
            f, fp = flips[i], flips[i - 1]
            flip_after_ret = (f[0] - r[4]) / 1e6 if f else float('nan')
            prevflip_vs_ret = (r[4] - fp[0]) / 1e6 if fp else float('nan')  # swap return - previous frame's flip
            prevflip_vs_call = (r[3] - fp[0]) / 1e6 if fp else float('nan')
            ph = ((r[3] - pres[0][0]) / 1e6) % per if per else 0
            o = dict(sec=(r[0] - A) / 1000, iv=iv, blk=blk, gcall=gcall, gafter=gafter, far=flip_after_ret, pfr=prevflip_vs_ret,
                     pfc=prevflip_vs_call, ph=ph, mode=f[2] if f else -1, msc=(f[1] - fp[1]) if f and fp else -1)
            out.append(o)
            if iv > 9:
                k = 'block' if blk > 0.5 else 'other'
                miss[k] += 1
        print(f'  misses (swap-ret interval > 9): {dict(miss)}')
        bl = [o for o in out if o['blk'] > 0.5]
        nb = [o for o in out if o['blk'] <= 0.5]
        def s(v, key):
            x = [o[key] for o in v if o[key] == o[key]]
            return f'{key} p10 {pct(x,10):6.2f} p50 {pct(x,50):6.2f} p90 {pct(x,90):6.2f}'
        for name, v in (('blocked > 0.5', bl), ('not blocked', nb)):
            print(f'  {name} ({len(v)}):')
            for key in ('gcall', 'gafter', 'pfc', 'pfr', 'far', 'ph'):
                print('     ', s(v, key))
            print('      flip msc steps', collections.Counter(o['msc'] for o in v).most_common(4))
        # phase histogram of swap call on the refresh grid, block rate per bin
        if per:
            bins = collections.defaultdict(lambda: [0, 0])
            for o in out:
                b = int(o['ph'] / per * 8)
                bins[b][0] += 1; bins[b][1] += o['blk'] > 0.5
            print('  swap-call phase bins (of refresh): ' + ' '.join(f'{b}:{bins[b][1]}/{bins[b][0]}' for b in sorted(bins)))
        if lst:
            print('     sec    iv   blk  gcall gafter   pfc    pfr    far   ph msc')
            for o in out:
                if o['blk'] > 0.5 or o['iv'] > 9:
                    print('  %6.2f %5.2f %5.2f %6.2f %6.2f %6.2f %6.2f %6.2f %4.2f %d' % (
                        o['sec'], o['iv'], o['blk'], o['gcall'], o['gafter'], o['pfc'], o['pfr'], o['far'], o['ph'], o['msc']))


main()
