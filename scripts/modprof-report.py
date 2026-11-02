#!/usr/bin/env python3
"""Per-mod Lua report of a benchmark run: which mods of a player's list cost the most game-thread time.

    scripts/modprof-report.py <run>/results --out <dir> [--title "..."] [--requested ids.txt] [--notes notes.txt]

Reads the benchmark's step-NN-<scenario>-...-luaprof.properties / .csv (pzopt.ModLuaProfile, one pair per pass),
setup.txt (the mods the game loaded) and report.txt (SYS lines: the machine). Writes into --out:
  modlist-lua-report.html  self-contained page: per scenario all Lua / mods Lua per frame, each mod's cost (bars + table),
                           the mods' Lua per frame over the pass, their costliest functions and events, the slowest frames
  mods-summary.csv         one row per mod and scenario: ms per frame, % of the frame, calls per frame, p99, files
  summary.txt              a short plain-text summary (the Discord reply's body is built from it)
The benchmark's own report.html carries no per-mod data (only the in-game report's Mods row does), hence this page.
"""

import argparse, csv, html, json, re, sys
from pathlib import Path


def props(path):
    out = {}
    for line in path.read_text(encoding="utf-8", errors="replace").splitlines():
        if line.startswith("#") or "=" not in line:
            continue
        k, v = line.split("=", 1)
        out[k] = v
    return out


def rows(p, prefix):
    out, i = [], 0
    while f"{prefix}.{i}" in p:
        out.append(p[f"{prefix}.{i}"].split("\t"))
        i += 1
    return out


def num(s, d=0.0):
    try:
        return float(s)
    except (TypeError, ValueError):
        return d


SCEN_TITLES = {"spin": "Rosewood spin", "drive120": "120 km/h drive", "louisville": "Louisville horde",
               "drive120south": "120 km/h drive south", "horde": "Horde shooting", "sweep": "Visuals feature costs"}


def load(results):
    passes = []
    for pf in sorted(results.glob("step-*-luaprof.properties")):
        m = re.match(r"step-(\d+)-([a-z0-9]+)-", pf.name)
        scen = m.group(2) if m else pf.stem
        p = props(pf)
        frames = []
        cf = pf.with_name(pf.name.replace(".properties", ".csv"))
        if cf.is_file():
            with cf.open(newline="") as fh:
                r = csv.reader(fh)
                head = next(r, [])
                mod_cols = [i for i, h in enumerate(head) if h.startswith("mod:")]
                for row in r:
                    try:
                        frames.append((float(row[1]), float(row[2]), float(row[3]), sum(float(row[i] or 0) for i in mod_cols)))
                    except (ValueError, IndexError):
                        continue
        passes.append({"scenario": scen, "title": SCEN_TITLES.get(scen, scen), "p": p, "frames": frames,
                       "origins": rows(p, "origin"), "fns": rows(p, "fn"), "events": rows(p, "event"), "spikes": rows(p, "spike")})
    return passes


def sysinfo(results):
    out = {}
    rt = results / "report.txt"
    if rt.is_file():
        for line in rt.read_text(errors="replace").splitlines():
            f = line.split("\t")
            if len(f) >= 3 and f[0] == "SYS":
                out[f[1]] = f[2]
    return out


def loaded_mods(results):
    out = []
    st = results / "setup.txt"
    if st.is_file():
        for line in st.read_text(errors="replace").splitlines():
            f = line.split("\t")
            if f[0] == "M" and len(f) >= 3:
                out.append((f[1], f[2]))
    return out


def downsample(frames, window=0.25):
    """(t, mods ms) averaged over windows of `window` s of game time: the sampler gives each sample 0.5 ms, so single
    frames are quantised and a per-frame max would overstate a light mod; the slow-frame table keeps the peaks."""
    out, acc, start = [], [], None
    for t, _, _, mods in frames:
        if start is None:
            start = t
        if t - start >= window and acc:
            out.append((start, sum(acc) / len(acc)))
            acc, start = [], t
        acc.append(mods)
    if acc:
        out.append((start, sum(acc) / len(acc)))
    return out


CSS = """
:root{--surface:#fcfcfb;--card:#ffffff;--ink:#0b0b0b;--ink2:#52514e;--muted:#8a8984;--line:#e4e3df;--series:#2a78d6;--track:#eef0f3}
@media (prefers-color-scheme:dark){:root:not([data-theme="light"]){--surface:#1a1a19;--card:#232322;--ink:#f0efec;--ink2:#c3c2b7;--muted:#8f8e86;--line:#3a3a37;--series:#3987e5;--track:#2d2f33}}
:root[data-theme="dark"]{--surface:#1a1a19;--card:#232322;--ink:#f0efec;--ink2:#c3c2b7;--muted:#8f8e86;--line:#3a3a37;--series:#3987e5;--track:#2d2f33}
*{box-sizing:border-box}body{margin:0;background:var(--surface);color:var(--ink);font:15px/1.5 system-ui,-apple-system,Segoe UI,sans-serif}
main{max-width:1100px;margin:0 auto;padding:24px 16px 48px}h1{font-size:24px;margin:0 0 4px}h2{font-size:19px;margin:32px 0 8px}
h3{font-size:15px;margin:20px 0 6px;color:var(--ink2)}p{margin:6px 0}.sub{color:var(--ink2)}.muted{color:var(--muted);font-size:13px}
.card{background:var(--card);border:1px solid var(--line);border-radius:10px;padding:16px;margin:12px 0}
.tiles{display:flex;flex-wrap:wrap;gap:12px}.tile{flex:1 1 180px;background:var(--card);border:1px solid var(--line);border-radius:10px;padding:12px 14px}
.tile b{display:block;font-size:24px;font-variant-numeric:tabular-nums}.tile span{color:var(--ink2);font-size:13px}
.bars{display:grid;grid-template-columns:minmax(120px,32%) 1fr auto;gap:6px 10px;align-items:center}
.bars .n{overflow:hidden;text-overflow:ellipsis;white-space:nowrap}.bars .t{background:var(--track);border-radius:4px;height:14px}
.bars .f{background:var(--series);height:14px;border-radius:0 4px 4px 0;min-width:2px}.bars .v{font-variant-numeric:tabular-nums;color:var(--ink2);font-size:13px;white-space:nowrap}
.scroll{overflow-x:auto}table{border-collapse:collapse;width:100%;font-size:13px}th,td{text-align:left;padding:5px 8px;border-bottom:1px solid var(--line);vertical-align:top}
th{color:var(--ink2);font-weight:600}td.r,th.r{text-align:right;font-variant-numeric:tabular-nums;white-space:nowrap}
td code{font-size:12px;word-break:break-all}svg{display:block;width:100%;height:auto}.chart{position:relative}
.tip{position:absolute;pointer-events:none;background:var(--card);border:1px solid var(--line);border-radius:6px;padding:4px 8px;font-size:12px;display:none;white-space:nowrap}
ul{margin:6px 0;padding-left:20px}
"""

JS = """
document.querySelectorAll('.chart').forEach(function(c){
  var d=JSON.parse(c.dataset.series),svg=c.querySelector('svg'),tip=c.querySelector('.tip'),cross=svg.querySelector('.cross');
  var W=+svg.dataset.w,X0=+svg.dataset.x0,X1=+svg.dataset.x1,T=+svg.dataset.t;
  function at(ev){var r=svg.getBoundingClientRect(),x=(ev.clientX-r.left)*W/r.width;if(x<X0||x>X1){hide();return}
    var t=(x-X0)/(X1-X0)*T,i=0,b=1e9;for(var k=0;k<d.length;k++){var e=Math.abs(d[k][0]-t);if(e<b){b=e;i=k}}
    var px=X0+d[i][0]/T*(X1-X0);cross.setAttribute('x1',px);cross.setAttribute('x2',px);cross.style.display='';
    tip.style.display='block';tip.textContent=d[i][0].toFixed(1)+' s: mods '+d[i][1].toFixed(3)+' ms';
    var l=(px/W)*r.width;tip.style.left=Math.min(l+8,r.width-tip.offsetWidth)+'px';tip.style.top='4px'}
  function hide(){tip.style.display='none';cross.style.display='none'}
  svg.addEventListener('pointermove',at);svg.addEventListener('pointerleave',hide);
});
"""


def esc(s):
    return html.escape(str(s), quote=True)


def line_chart(frames):
    pts = downsample(frames)
    if len(pts) < 2:
        return "<p class='muted'>No per-frame data.</p>"
    W, H, X0, X1, Y0, Y1 = 1000, 220, 52, 990, 12, 190
    T = max(pts[-1][0], 1e-6)
    top = max(max(v for _, v in pts), 0.01)
    # a round axis top
    for s in (0.01, 0.02, 0.05, 0.1, 0.2, 0.5, 1, 2, 5, 10, 20, 50, 100, 200, 500):
        if top <= s:
            top = s
            break
    xy = " ".join(f"{X0 + t / T * (X1 - X0):.1f},{Y1 - v / top * (Y1 - Y0):.1f}" for t, v in pts)
    grid = ""
    for k in range(5):
        v = top * k / 4
        y = Y1 - v / top * (Y1 - Y0)
        grid += f"<line x1='{X0}' x2='{X1}' y1='{y:.1f}' y2='{y:.1f}' stroke='var(--line)' stroke-width='1'/>"
        grid += f"<text x='{X0 - 6}' y='{y + 4:.1f}' text-anchor='end' font-size='11' fill='var(--muted)'>{v:g}</text>"
    for k in range(6):
        t = T * k / 5
        x = X0 + t / T * (X1 - X0)
        grid += f"<text x='{x:.1f}' y='{H - 8}' text-anchor='middle' font-size='11' fill='var(--muted)'>{t:.0f} s</text>"
    data = json.dumps([[round(t, 2), round(v, 4)] for t, v in pts])
    return (f"<div class='chart' data-series='{esc(data)}'><svg viewBox='0 0 {W} {H}' data-w='{W}' data-x0='{X0}' data-x1='{X1}' "
            f"data-t='{T}' role='img' aria-label='Mods Lua per frame over the pass, ms'>{grid}"
            f"<polyline points='{xy}' fill='none' stroke='var(--series)' stroke-width='2' stroke-linejoin='round'/>"
            f"<line class='cross' y1='{Y0}' y2='{Y1}' stroke='var(--ink2)' stroke-width='1' style='display:none'/></svg>"
            f"<div class='tip'></div></div><p class='muted'>ms per frame spent in the mods' Lua, averaged over 0.25 s; "
            f"the slow frames are listed below. Hover for values.</p>")


def section(ps):
    p = ps["p"]
    frames = int(num(p.get("frames")))
    secs = num(p.get("seconds"))
    fps = frames / secs if secs else 0
    frame_ms = 1000 / fps if fps else 0
    mods = sorted([o for o in ps["origins"] if o[1] == "mod" or o[0].startswith("mod:")], key=lambda o: -num(o[3]))
    mods = [o for o in mods if o[0] != "mod:pzopt-harness"]
    out = [f"<h2>{esc(ps['title'])}</h2>",
           f"<p class='sub'>{frames:,} frames in {secs:.0f} s ({fps:.0f} fps on this PC, {frame_ms:.2f} ms a frame).</p>",
           "<div class='tiles'>",
           f"<div class='tile'><b>{num(p.get('mods_ms')):.3f} ms</b><span>all mods' Lua per frame ({num(p.get('mods_pct')):.2f} % of the frame)</span></div>",
           f"<div class='tile'><b>{num(p.get('lua_ms')):.3f} ms</b><span>all Lua per frame, game + mods ({num(p.get('lua_pct')):.2f} %)</span></div>",
           f"<div class='tile'><b>{esc(p.get('worst_mod_name') or 'none')}</b><span>costliest mod: {num(p.get('worst_mod_ms')):.3f} ms per frame</span></div>",
           "</div>"]
    out.append("<h3>Each mod's Lua per frame</h3>")
    if mods:
        top = max(num(o[3]) for o in mods) or 1
        out.append("<div class='card'><div class='bars'>")
        for o in mods[:40]:
            w = 100 * num(o[3]) / top
            out.append(f"<div class='n' title='{esc(o[2])}'>{esc(o[2])}</div><div class='t' title='{esc(o[2])}: {num(o[3]):.4f} ms per frame'>"
                       f"<div class='f' style='width:{w:.1f}%'></div></div><div class='v'>{num(o[3]):.3f} ms</div>")
        out.append("</div>")
        if len(mods) > 40:
            out.append(f"<p class='muted'>{len(mods) - 40} more mods in the table below.</p>")
        out.append("</div><div class='scroll'><table><tr><th>Mod</th><th class='r'>ms / frame</th><th class='r'>% of frame</th>"
                   "<th class='r'>calls / frame</th><th class='r'>p99 ms</th><th class='r'>Lua files</th></tr>")
        for o in mods:
            out.append(f"<tr><td>{esc(o[2])}</td><td class='r'>{num(o[3]):.4f}</td><td class='r'>{num(o[4]):.3f}</td>"
                       f"<td class='r'>{esc(o[5]) or '-'}</td><td class='r'>{num(o[6]):.3f}</td><td class='r'>{esc(o[7])}</td></tr>")
        out.append("</table></div>")
    else:
        out.append("<p>No mod ran any Lua the profiler could see in this pass.</p>")
    out.append("<h3>The mods' Lua over the pass</h3>")
    out.append(line_chart(ps["frames"]))
    fns = [f for f in ps["fns"] if f[0].startswith("mod:") and f[0] != "mod:pzopt-harness" and (num(f[5]) > 0 or num(f[6]) > 0)]
    names = {o[0]: o[2] for o in ps["origins"]}
    if fns:
        out.append("<h3>The mods' costliest Lua functions</h3><div class='scroll'><table><tr><th>Mod</th><th>Function</th><th>Event</th>"
                   "<th class='r'>self ms / frame</th><th class='r'>total ms / frame</th><th class='r'>calls / frame</th></tr>")
        for f in sorted(fns, key=lambda f: (-num(f[5]), -num(f[6])))[:25]:
            out.append(f"<tr><td>{esc(names.get(f[0], f[0]))}</td><td><code>{esc(f[2])} ({esc(f[1])}:{esc(f[3])})</code></td>"
                       f"<td>{esc(f[4])}</td><td class='r'>{num(f[5]):.4f}</td><td class='r'>{num(f[6]):.4f}</td>"
                       f"<td class='r'>{esc(f[7] if len(f) > 7 else '') or '-'}</td></tr>")
        out.append("</table></div>")
    evs = [e for e in ps["events"] if num(e[2]) > 0]
    if evs:
        out.append("<h3>Events the mods hook</h3><div class='scroll'><table><tr><th>Event</th><th class='r'>all Lua ms / frame</th>"
                   "<th class='r'>mods ms / frame</th><th class='r'>calls / frame</th><th>Costliest</th></tr>")
        for e in sorted(evs, key=lambda e: -num(e[2]))[:20]:
            out.append(f"<tr><td>{esc(e[0])}</td><td class='r'>{num(e[1]):.4f}</td><td class='r'>{num(e[2]):.4f}</td>"
                       f"<td class='r'>{esc(e[3]) or '-'}</td><td>{esc(names.get(e[4], e[4]))}</td></tr>")
        out.append("</table></div>")
    sp = [s for s in ps["spikes"] if num(s[3]) > 0]
    if sp:
        out.append("<h3>Slow frames with mod Lua in them</h3><div class='scroll'><table><tr><th class='r'>at</th><th class='r'>frame ms</th>"
                   "<th class='r'>Lua ms</th><th class='r'>mods ms</th><th>Costliest in that frame</th></tr>")
        for s in sorted(sp, key=lambda s: -num(s[3]))[:12]:
            out.append(f"<tr><td class='r'>{num(s[0]):.1f} s</td><td class='r'>{num(s[1]):.1f}</td><td class='r'>{num(s[2]):.2f}</td>"
                       f"<td class='r'>{num(s[3]):.2f}</td><td>{esc(names.get(s[4], s[4]))} ({num(s[5]):.2f} ms)</td></tr>")
        out.append("</table></div>")
    return "\n".join(out), mods


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("results")
    ap.add_argument("--out", required=True)
    ap.add_argument("--title", default="Lua cost of a mod list")
    ap.add_argument("--notes", help="text file: one line per note shown under the title (skipped items, ...)")
    a = ap.parse_args()
    results, out = Path(a.results), Path(a.out)
    out.mkdir(parents=True, exist_ok=True)
    passes = load(results)
    if not passes:
        sys.exit(f"modprof-report: no *-luaprof.properties in {results}")
    sysi = sysinfo(results)
    loaded = loaded_mods(results)
    notes = [l for l in Path(a.notes).read_text().splitlines() if l.strip()] if a.notes and Path(a.notes).is_file() else []

    body, summary, csv_rows = [], [], []
    for ps in passes:
        sec, mods = section(ps)
        body.append(sec)
        p = ps["p"]
        summary.append(f"{ps['title']}: all mods {num(p.get('mods_ms')):.3f} ms/frame ({num(p.get('mods_pct')):.2f} % of the frame), "
                       f"all Lua {num(p.get('lua_ms')):.3f} ms")
        for i, o in enumerate(mods[:5]):
            summary.append(f"  {i + 1}. {o[2]}: {num(o[3]):.3f} ms/frame ({num(o[4]):.2f} %)")
        for o in mods:
            csv_rows.append([ps["scenario"], o[0][4:] if o[0].startswith("mod:") else o[0], o[2], o[3], o[4], o[5], o[6], o[7]])

    with (out / "mods-summary.csv").open("w", newline="") as fh:
        w = csv.writer(fh)
        w.writerow(["scenario", "mod_id", "mod_name", "ms_per_frame", "pct_of_frame", "calls_per_frame", "p99_ms", "lua_files"])
        w.writerows(csv_rows)
    (out / "summary.txt").write_text("\n".join(summary) + "\n")

    machine = ", ".join(x for x in (sysi.get("cpu", ""), sysi.get("gpu", "").split("/")[0], sysi.get("screen", ""),
                                    (sysi.get("ram_gb", "") + " GB RAM") if sysi.get("ram_gb") else "") if x)
    head = [f"<h1>{esc(a.title)}</h1>",
            f"<p class='sub'>{len(loaded)} mods loaded. Measured with PZ Optimization's built-in benchmark and its mod Lua profiler "
            f"(a 2 kHz sampler of the game thread's Lua: each mod's own functions and the Java they call), build "
            f"{esc(sysi.get('pzopt_build', '?'))}.</p>"]
    if machine:
        head.append(f"<p class='muted'>Machine: {esc(machine)}. Absolute times scale with the CPU; the order of the mods "
                    f"carries over to other PCs.</p>")
    head.append("<div class='card'><p><b>How to read it.</b> A frame at 60 fps is 16.7 ms; the game's own Lua is usually "
                "0.1-0.5 ms of it. A mod at 0.1 ms per frame or more is worth a look; at 1 ms or more it costs frames on most PCs. "
                "Mods that only act on rare events (a click, a menu) barely register here: this is the steady per-frame cost "
                "while playing.</p></div>")
    if notes:
        head.append("<div class='card'><b>Notes</b><ul>" + "".join(f"<li>{esc(n)}</li>" for n in notes) + "</ul></div>")
    if loaded:
        head.append("<details class='card'><summary>Mods loaded in the run</summary><ul>"
                    + "".join(f"<li>{esc(n)} <span class='muted'>({esc(i)})</span></li>" for i, n in loaded) + "</ul></details>")
    page = (f"<!doctype html><html lang='en'><head><meta charset='utf-8'><meta name='viewport' content='width=device-width,initial-scale=1'>"
            f"<title>Mod Lua report</title><style>{CSS}</style></head><body><main>{''.join(head)}{''.join(body)}"
            f"<p class='muted'>Columns: ms / frame = the mod's Lua averaged over every frame of the pass; p99 = the 99th percentile "
            f"of its per-frame time; calls / frame = how often its functions ran. Made by github.com/xD3I/PZ_Optimization.</p>"
            f"</main><script>{JS}</script></body></html>")
    (out / "modlist-lua-report.html").write_text(page, encoding="utf-8")
    print("\n".join(summary))


if __name__ == "__main__":
    main()
