#!/usr/bin/env python3
"""blkgpu.py <run>...: swap blocks (swap_ret - swap_call > 0.5 ms) in the route split by where the GPU was at the swap call
(gpu_done - swap_call), and the block's end relative to the GPU completion."""
import sys
from pathlib import Path
def pct(v,q):
    v=sorted(v); return v[min(len(v)-1,int(q/100*len(v)))] if v else float('nan')
for a in sys.argv[1:]:
    run=Path(a)
    kv=dict(l.split('=',1) for l in (run/'pzopt-bench.out').read_text().splitlines() if '=' in l)
    A,B=int(kv['route_start_epoch_ms']),int(kv['route_end_epoch_ms'])
    rows=[list(map(int,l.split()[:7])) for l in (run/'pzopt-pacing.out').read_text().splitlines() if l[:1].isdigit()]
    rows=[r for r in rows if A<=r[0]<=B and r[6]]
    busy=[r for r in rows if r[6]>r[3]]; idle=[r for r in rows if r[6]<=r[3]]
    def blk(v): return [ (r[4]-r[3])/1e6 for r in v]
    bb=[r for r in busy if r[4]-r[3]>5e5]; bi=[r for r in idle if r[4]-r[3]>5e5]
    print(f'{run.name}: frames {len(rows)}; GPU busy at swap call {len(busy)} -> blocked {len(bb)} ({100*len(bb)/max(1,len(busy)):.1f} %); GPU done {len(idle)} -> blocked {len(bi)} ({100*len(bi)/max(1,len(idle)):.2f} %)')
    if bb: print('   busy+blocked: block p50 %.2f; swap ret - gpu done p10 %.2f p50 %.2f p90 %.2f' % (pct(blk(bb),50), *[pct([(r[4]-r[6])/1e6 for r in bb],q) for q in (10,50,90)]))
    if bi: print('   done+blocked: block p50 %.2f p90 %.2f; gpu done before call p50 %.2f' % (pct(blk(bi),50), pct(blk(bi),90), pct([(r[3]-r[6])/1e6 for r in bi],50)))
    print('   all busy frames: gpu past call p50 %.2f p90 %.2f' % (pct([(r[6]-r[3])/1e6 for r in busy],50), pct([(r[6]-r[3])/1e6 for r in busy],90)))
