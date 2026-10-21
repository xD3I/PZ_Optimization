#!/usr/bin/env python3
"""probe.py <run>: pzopt-swapprobe.out summary over the route window: per segment p50/p99/max and counts > 0.5 / 1 ms,
then the samples of long segments grouped by (segment, syscall, wchan, fd target, xwayland state/wchan)."""
import sys, collections
from pathlib import Path

SYS = {0: 'read', 1: 'write', 7: 'poll', 16: 'ioctl', 202: 'futex', 47: 'recvmsg', 46: 'sendmsg', 271: 'ppoll', 232: 'epoll_wait',
       230: 'nanosleep', 20: 'writev', 19: 'readv', 45: 'recvfrom', 44: 'sendto', 24: 'sched_yield', 'running': 'running'}


def pct(v, q):
    v = sorted(v)
    return v[min(len(v) - 1, int(q / 100 * len(v)))] if v else float('nan')


run = Path(sys.argv[1])
kv = dict(l.split('=', 1) for l in (run / 'pzopt-bench.out').read_text().splitlines() if '=' in l)
A, B = int(kv['route_start_epoch_ms']), int(kv['route_end_epoch_ms'])
# map call_ns -> epoch via pacing.out (epoch_ms, swap_call_ns)
off = None
for l in (run / 'pzopt-pacing.out').read_text().splitlines():
    if l[:1].isdigit():
        p = l.split(); off = int(p[0]) - int(p[4]) / 1e6; break
segs = None
F = {}
S = collections.defaultdict(list)
for l in (run / 'pzopt-swapprobe.out').read_text().splitlines():
    if l.startswith('# F'):
        segs = l.split(':')[1].split('(')[0].split()
    if l.startswith('F '):
        p = l.split(); F[int(p[1])] = (int(p[2]), list(map(int, p[3:])))
    elif l.startswith('S '):
        a, b = l[2:].split(' | ', 1) if ' | ' in l else (l[2:], '')
        p = a.split()
        S[int(p[0])].append((p[1], int(p[2]), p[3], p[4], p[5], p[6], ' '.join(p[7:]), b))
route = {f: v for f, v in F.items() if A <= v[0] / 1e6 + off <= B}
print(f'{run.name}: {len(route)} route frames of {len(F)}')
for i, s in enumerate(segs):
    v = [x[1][i] / 1000 for x in route.values()]
    print(f'  {s:7} p50 {pct(v,50):6.3f} p90 {pct(v,90):6.3f} p99 {pct(v,99):6.3f} max {max(v):6.2f}  >0.5 {sum(x>0.5 for x in v):4} >1 {sum(x>1 for x in v):4}')
tot = [sum(x[1]) / 1000 for x in route.values()]
print(f'  total   p50 {pct(tot,50):6.3f} p99 {pct(tot,99):6.3f} >0.5 {sum(x>0.5 for x in tot)} >1 {sum(x>1 for x in tot)}')
# which segment dominates the long frames
dom = collections.Counter()
for f, (c, d) in route.items():
    if sum(d) > 500:
        dom[segs[max(range(len(d)), key=lambda i: d[i])]] += 1
print('  dominant segment of frames with total > 0.5 ms:', dict(dom))
g = collections.Counter()
for f in route:
    for (seg, t, nr, a0, a1, wc, fdt, other) in S.get(f, []):
        try:
            n = SYS.get(int(nr), nr)
        except ValueError:
            n = nr
        xo = other.split(' | ')
        g[(seg, n, wc, fdt.split(' ')[0] if fdt else '', xo[0] if xo else '')] += 1
print('  samples (seg, syscall, rt wchan, fd, xwayland state wchan):')
for k, c in g.most_common(25):
    print(f'   {c:5} {k}')
kw = collections.Counter()
for f in route:
    for s in S.get(f, []):
        xo = s[7].split(' | ')
        if len(xo) > 1:
            kw[xo[1]] += 1
print('  kwin states:', kw.most_common(6))
