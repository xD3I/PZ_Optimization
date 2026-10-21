#!/usr/bin/env python3
"""gcphase.py <run>...: per young GC in the route window (jvm.log with gc+phases=debug[,gc+heap=debug]): object copy /
scan heap roots (avg ms per worker), survivor regions after, old regions added, age-1 bytes."""
import re,sys,datetime,statistics as st
from pathlib import Path
for r in sys.argv[1:]:
    r=Path(r); L=(r/"jvm.log").read_text().splitlines()
    kv=dict(l.split("=",1) for l in (r/"pzopt-bench.out").read_text().splitlines() if "=" in l)
    A=int(kv["route_start_epoch_ms"])/1000; B=int(kv["route_end_epoch_ms"])/1000
    gc={}
    for l in L:
        m=re.match(r"\[(\S+?)\]\[[\d.]+s\] GC\((\d+)\)(.*)",l)
        if not m: continue
        t=datetime.datetime.strptime(m.group(1),"%Y-%m-%dT%H:%M:%S.%f%z").timestamp(); d=gc.setdefault(int(m.group(2)),{"t":t}); x=m.group(3)
        for k,p in (("copy",r"Object Copy \(ms\):.*Avg: ([\d.]+)"),("scan",r"Scan Heap Roots \(ms\):.*Avg: ([\d.]+)"),("merge",r"Merge Heap Roots \(ms\):.*Avg: ([\d.]+)"),("age1",r"- age +1: +(\d+) bytes")):
            mm=re.search(p,x)
            if mm: d[k]=float(mm.group(1))
        mm=re.search(r"Survivor regions: (\d+)->(\d+)",x)
        if mm: d["surv"]=int(mm.group(2))
        mm=re.search(r"Old regions: (\d+)->(\d+)",x)
        if mm: d["old"]=int(mm.group(2))-int(mm.group(1))
    rows=[d for g,d in sorted(gc.items()) if A<=d["t"]<=B and "copy" in d]
    def m(k,s=1): v=[d[k]/s for d in rows if k in d]; return f"{st.mean(v):.2f}" if v else "-"
    print(f"{r.name[:34]:34} young GCs {len(rows)}: object copy {m('copy')} ms, scan roots {m('scan')}, merge {m('merge')}, survivor regions {m('surv')}, old regions added {m('old')}, age-1 MB {m('age1',1e6)}")
