#!/usr/bin/env python3
"""Discord mod-list Lua profiling service (2026-10-25, maintainer: "people send me their modlist and I run the lua
profiler on them").

Watches the forum channel #lua-mods-profiling. For every new post: reads the mod list from the post (Workshop links or
ids, a Steam collection link, a server ini's WorkshopItems= / Mods= lines, a mods/default.txt, or such a text file
attached), downloads the Workshop items, screens them, builds a throw-away user folder with exactly those mods enabled,
runs the built-in benchmark with its mod Lua profiler through the run queue, and answers in the post's thread with
the per-mod HTML report and the CSVs.

    scripts/modprof-watch.py                 # the service: poll every --interval s (systemd unit pzopt-modprof.service)
    scripts/modprof-watch.py --once          # one poll, then exit
    scripts/modprof-watch.py --thread <id>   # (re)process one post now, even if it was done before
    scripts/modprof-watch.py --parse-only --thread <id>   # print what would be downloaded and run, touch nothing

Safety (strangers' mods, the maintainer's machine):
  - the game runs on a separate user folder (-Ddeployment.user.cachedir), so a mod never sees ~/Zomboid, its saves or
    options; the folder is deleted after the post;
  - items with Java or native code (.jar .class .so .dll .dylib .exe, mod.info javaJarFile) are refused, only Lua /
    script / asset mods run; Workshop items of other games are refused;
  - steamcmd runs with its own HOME (the shared one dropped the /games library on 2026-10-05) and anonymous login;
  - limits per post (items, download size) and per Discord user (posts a day, one at a time).
Secrets stay outside the public repo: the bot token (~/.config/pzopt/discord-bot-token, reads the channel) and the
channel's webhook (~/.config/pzopt/discord-webhook-modprof, posts the answers).
State: ~/.local/state/pzopt-modprof/ (state.json, service.log); downloads and job folders: ~/.cache/pzopt-modprof/.
"""

import argparse, collections, datetime, io, json, os, re, shutil, subprocess, sys, time, traceback
import urllib.error, urllib.parse, urllib.request, uuid, zipfile
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent
CHANNEL = os.environ.get("MODPROF_CHANNEL", "1563949188897570909")  # #lua-mods-profiling (forum)
APP = "108600"
UA = "pzopt-modprof (github.com/xD3I/PZ_Optimization, 1)"
API = "https://discord.com/api/v10"
CFG = Path.home() / ".config/pzopt"
STATE_DIR = Path(os.environ.get("MODPROF_STATE", Path.home() / ".local/state/pzopt-modprof"))
CACHE = Path(os.environ.get("MODPROF_CACHE", Path.home() / ".cache/pzopt-modprof"))
WORKSHOP = CACHE / "workshop"  # steamcmd force_install_dir; items land in steamapps/workshop/content/108600/<id>
SPEC = os.environ.get("MODPROF_SPEC", "spin")  # one Rosewood spin pass (maintainer, 2026-10-26)
INSTALL = os.environ.get("MODPROF_INSTALL", "opt")  # queue --install: this checkout's build/classes
MAX_ITEMS = int(os.environ.get("MODPROF_MAX_ITEMS", "400"))
MAX_BYTES = int(float(os.environ.get("MODPROF_MAX_GB", "25")) * 1e9)
USER_DAILY = int(os.environ.get("MODPROF_USER_DAILY", "3"))
UPLOAD_LIMIT = 9_500_000  # Discord's attachment cap per message (10 MB without boosts), with room for the form
BLOCKED_EXT = {".jar", ".class", ".so", ".dll", ".dylib", ".exe"}

HELP = ("Post your mod list as one of: Steam Workshop links or ids (one per line), a Steam collection link, your server "
        "ini's `WorkshopItems=` line (add the `Mods=` line to pick which mods of each item are on), or attach that text "
        "file. Build 42 mods only.")


def log(msg):
    line = f"[{datetime.datetime.now():%Y-%m-%d %H:%M:%S}] {msg}"
    print(line, flush=True)
    try:
        STATE_DIR.mkdir(parents=True, exist_ok=True)
        with (STATE_DIR / "service.log").open("a") as fh:
            fh.write(line + "\n")
    except OSError:
        pass


# --- state ------------------------------------------------------------------------------------------------------------

def load_state():
    p = STATE_DIR / "state.json"
    if p.is_file():
        try:
            return json.loads(p.read_text())
        except ValueError:
            log("state.json unreadable; starting fresh (old file kept as state.json.bad)")
            p.rename(p.with_suffix(".json.bad"))
    return {"threads": {}}


def save_state(st):
    STATE_DIR.mkdir(parents=True, exist_ok=True)
    tmp = STATE_DIR / "state.json.tmp"
    tmp.write_text(json.dumps(st, indent=1, sort_keys=True))
    tmp.replace(STATE_DIR / "state.json")


# --- Discord ----------------------------------------------------------------------------------------------------------

def secret(env, name):
    v = os.environ.get(env, "")
    if not v and (CFG / name).is_file():
        v = (CFG / name).read_text().strip()
    if not v:
        sys.exit(f"modprof-watch: missing ${env} / ~/.config/pzopt/{name}")
    return v


def http(req, timeout=30, tries=4):
    for i in range(tries):
        try:
            with urllib.request.urlopen(req, timeout=timeout) as r:
                body = r.read()
                return json.loads(body) if body else {}
        except urllib.error.HTTPError as e:
            if e.code == 429 and i + 1 < tries:  # rate limited: Discord says how long
                try:
                    wait = float(json.loads(e.read()).get("retry_after", 2))
                except ValueError:
                    wait = 2
                time.sleep(min(wait, 60) + 0.5)
                continue
            if e.code >= 500 and i + 1 < tries:
                time.sleep(3 * (i + 1))
                continue
            raise RuntimeError(f"{req.get_method()} {req.full_url.split('?')[0][:80]} -> HTTP {e.code}: "
                               f"{e.read().decode(errors='replace')[:300]}")
        except (urllib.error.URLError, TimeoutError) as e:
            if i + 1 < tries:
                time.sleep(5 * (i + 1))
                continue
            raise RuntimeError(f"{req.full_url.split('?')[0][:80]}: {e}")


def dget(path):
    return http(urllib.request.Request(API + path, headers={"Authorization": "Bot " + secret("PZOPT_DISCORD_BOT_TOKEN", "discord-bot-token"),
                                                           "User-Agent": UA}))


def forum_posts():
    guild = dget(f"/channels/{CHANNEL}")["guild_id"]
    out = {t["id"]: t for t in dget(f"/guilds/{guild}/threads/active").get("threads", []) if t.get("parent_id") == CHANNEL}
    for t in dget(f"/channels/{CHANNEL}/threads/archived/public?limit=25").get("threads", []):
        out.setdefault(t["id"], t)
    return sorted(out.values(), key=lambda t: int(t["id"]))  # oldest first: answered in the order they came


DRY_OUT = None  # --test-list: the replies go to files here instead of Discord


def post(thread, content, files=(), mention=None):
    """Webhook message into the forum post's thread; files = [(name, bytes)]."""
    if DRY_OUT:
        n = len(list(DRY_OUT.glob("reply-*.txt")))
        (DRY_OUT / f"reply-{n}.txt").write_text(content[:2000] + "\n")
        for name, data in files:
            (DRY_OUT / name).write_bytes(data)
        log(f"(test) reply {n}: {len(content)} chars, {len(files)} files, {sum(len(d) for _, d in files)} bytes -> {DRY_OUT}")
        return {}
    url = secret("PZOPT_DISCORD_WEBHOOK_MODPROF", "discord-webhook-modprof")
    url += ("&" if "?" in url else "?") + urllib.parse.urlencode({"thread_id": thread, "wait": "true"})
    payload = {"content": content[:2000], "username": "PZ Optimization mod profiler",
               "allowed_mentions": {"users": [mention] if mention else []}}
    if not files:
        req = urllib.request.Request(url, data=json.dumps(payload).encode(), method="POST",
                                     headers={"Content-Type": "application/json", "User-Agent": UA})
        return http(req)
    b = "pzopt" + uuid.uuid4().hex
    body = io.BytesIO()

    def part(headers, data):
        body.write(f"--{b}\r\n{headers}\r\n\r\n".encode())
        body.write(data)
        body.write(b"\r\n")
    part('Content-Disposition: form-data; name="payload_json"\r\nContent-Type: application/json', json.dumps(payload).encode())
    for i, (name, data) in enumerate(files):
        part(f'Content-Disposition: form-data; name="files[{i}]"; filename="{name}"\r\nContent-Type: application/octet-stream', data)
    body.write(f"--{b}--\r\n".encode())
    req = urllib.request.Request(url, data=body.getvalue(), method="POST",
                                 headers={"Content-Type": f"multipart/form-data; boundary={b}", "User-Agent": UA})
    return http(req, timeout=120)


# --- the mod list -----------------------------------------------------------------------------------------------------

def fetch_text(url, cap=2_000_000):
    req = urllib.request.Request(url, headers={"User-Agent": UA})
    with urllib.request.urlopen(req, timeout=30) as r:
        return r.read(cap).decode("utf-8", errors="replace")


def parse_list(text):
    """-> (workshop ids in order, mod ids in order or [] = every mod of each item)."""
    ws, mods = [], []

    def add(lst, x):
        if x and x not in lst:
            lst.append(x)
    for line in text.splitlines():
        s = line.strip().strip("`")
        m = re.match(r"(?i)^WorkshopItems\s*=\s*(.*)$", s)
        if m:
            for x in re.split(r"[;,\s]+", m.group(1)):
                if x.isdigit():
                    add(ws, x)
            continue
        m = re.match(r"(?i)^Mods\s*=\s*(.*)$", s)
        if m:
            for x in m.group(1).split(";"):
                add(mods, x.strip().lstrip("\\"))
            continue
        m = re.match(r"^mod\s*=\s*\\?([^,]+),?$", s)  # mods/default.txt
        if m:
            add(mods, m.group(1).strip())
            continue
        for m in re.finditer(r"(?i)mod\s*id\s*[:=]\s*\\?([A-Za-z0-9_.\- ]+?)(?=$|[;,|]|\s{2}|\s+workshop)", s):
            add(mods, m.group(1).strip())
        for m in re.finditer(r"(?:[?&]id=|workshop\s*id\s*[:=]\s*)(\d{6,12})", s, re.I):
            add(ws, m.group(1))
        for tok in re.findall(r"(?<![\w/=.:-])(\d{8,11})(?![\w.-])", s):  # bare ids, one or more per line
            add(ws, tok)
    return ws, mods


def steam_details(ids):
    out = {}
    for i in range(0, len(ids), 100):
        chunk = ids[i:i + 100]
        data = {"itemcount": len(chunk)}
        for k, x in enumerate(chunk):
            data[f"publishedfileids[{k}]"] = x
        req = urllib.request.Request("https://api.steampowered.com/ISteamRemoteStorage/GetPublishedFileDetails/v1/",
                                     data=urllib.parse.urlencode(data).encode(), headers={"User-Agent": UA})
        for d in http(req)["response"].get("publishedfiledetails", []):
            out[d["publishedfileid"]] = d
    return out


def collection_children(cid):
    req = urllib.request.Request("https://api.steampowered.com/ISteamRemoteStorage/GetCollectionDetails/v1/",
                                 data=urllib.parse.urlencode({"collectioncount": 1, "publishedfileids[0]": cid}).encode(),
                                 headers={"User-Agent": UA})
    det = http(req)["response"].get("collectiondetails", [{}])[0]
    return [c["publishedfileid"] for c in sorted(det.get("children", []), key=lambda c: c.get("sortorder", 0))]


def required_items(wid):
    """The 'Required items' block of the item's Workshop page (the Web API has no keyless call for it). Steam answers
    429 to bursts, so one page at a time, with backoff."""
    for i in range(3):
        try:
            page = fetch_text(f"https://steamcommunity.com/sharedfiles/filedetails/?id={wid}")
            break
        except urllib.error.HTTPError as e:
            if e.code != 429:
                return []
            time.sleep(15 * (i + 1))
        except Exception:
            return []
    else:
        return []
    time.sleep(1)
    m = re.search(r'id="RequiredItems(.*?)</div>\s*</div>', page, re.S)
    return list(dict.fromkeys(re.findall(r"filedetails/\?id=(\d+)", m.group(1)))) if m else []


def resolve(ws_ids, notes):
    """Expand collections, keep the game's public items. -> ordered [(id, details)]."""
    det = steam_details(ws_ids)
    order = []
    for x in ws_ids:
        d = det.get(x)
        if not d or d.get("result") != 1:
            notes.append(f"{x}: not a public Workshop item, skipped")
            continue
        if str(d.get("consumer_app_id")) != APP:
            notes.append(f"{x} ({d.get('title', '?')}): not a Project Zomboid item, skipped")
            continue
        kids = collection_children(x) if not int(d.get("file_size") or 0) else []  # a collection has no file of its own
        if kids:
            notes.append(f"collection {d.get('title', x)}: {len(kids)} items")
            order += [k for k in kids if k not in order]
        elif x not in order:
            order.append(x)
    det.update(steam_details([x for x in order if x not in det]))
    order = [x for x in order if det.get(x, {}).get("result") == 1 and str(det[x].get("consumer_app_id")) == APP]
    return [(x, det[x]) for x in order]


def add_requirements(items, notes):
    """Downloaded items whose mods require a mod no item has: their Workshop pages' required items, added in front
    (two rounds). -> the new item list (downloaded)."""
    for _ in range(2):
        have = {m for w, _ in items for m, *_ in mods_of(w)[0]}
        need = [w for w, _ in items if any(r not in have for _, _, _, rs in mods_of(w)[0] for r in rs)]
        new = []
        for w in need[:60]:
            for r in required_items(w):
                if r not in dict(items) and r not in new:
                    new.append(r)
                    notes.append(f"added {r}: required by {dict(items)[w].get('title', w)}")
        if not new:
            return items
        extra = resolve(new, notes)
        present = download(extra)
        items = [(w, d) for w, d in extra if w in present] + items
    return items


# --- download, screen, stage ------------------------------------------------------------------------------------------

def item_dir(wid):
    return WORKSHOP / "steamapps/workshop/content" / APP / wid


def download(items):
    """steamcmd, anonymous, its own HOME. -> set of ids present afterwards."""
    home = CACHE / "steamcmd-home"
    home.mkdir(parents=True, exist_ok=True)
    WORKSHOP.mkdir(parents=True, exist_ok=True)
    stamp = CACHE / "updated.json"
    have = json.loads(stamp.read_text()) if stamp.is_file() else {}
    todo = [w for w, d in items if not item_dir(w).is_dir() or str(have.get(w)) != str(d.get("time_updated"))]
    env = dict(os.environ, HOME=str(home))
    for i in range(0, len(todo), 40):
        chunk = todo[i:i + 40]
        cmd = ["steamcmd", "+force_install_dir", str(WORKSHOP), "+login", "anonymous"]
        for w in chunk:
            cmd += ["+workshop_download_item", APP, w]
        cmd += ["+quit"]
        log(f"steamcmd: {len(chunk)} items ({i + len(chunk)}/{len(todo)})")
        try:
            r = subprocess.run(cmd, env=env, stdin=subprocess.DEVNULL, capture_output=True, text=True, timeout=3600)
            (CACHE / "steamcmd-last.log").write_text(r.stdout[-20000:] + r.stderr[-5000:])
        except subprocess.TimeoutExpired:
            log("steamcmd: timed out")
        upd = {w: dict(items)[w].get("time_updated") for w in chunk if item_dir(w).is_dir()}
        have.update(upd)
        stamp.write_text(json.dumps(have))
    return {w for w, _ in items if item_dir(w).is_dir()}


def read_info(p):
    out = {}
    for line in p.read_text(encoding="utf-8", errors="replace").splitlines():
        if "=" in line:
            k, v = line.split("=", 1)
            out.setdefault(k.strip().lower(), v.strip())
    return out


def mods_of(wid):
    """The Build 42 mods of a downloaded item: [(mod id, name, folder, [required mod ids])], plus a refusal reason."""
    root = item_dir(wid) / "mods"
    if not root.is_dir():
        return [], "no mods folder"
    for f in item_dir(wid).rglob("*"):
        if f.suffix.lower() in BLOCKED_EXT and f.is_file():
            return [], f"contains Java / native code ({f.name}); only Lua mods are run automatically"
    out = []
    for folder in sorted(p for p in root.iterdir() if p.is_dir()):
        infos = sorted(folder.glob("42*/mod.info"), key=lambda p: [int(x) if x.isdigit() else 0 for x in p.parent.name.split(".")])
        if not infos and (folder / "common/mod.info").is_file():
            infos = [folder / "common/mod.info"]  # Build 42 loads a mod whose mod.info sits only in common/ (Pack Mule, TchernoLib)
        if not infos:
            continue  # a Build 41 folder
        info = read_info(infos[-1])
        if info.get("javajarfile") or info.get("javajar"):
            return [], "a Java mod (javaJarFile); only Lua mods are run automatically"
        mid = info.get("id", "").lstrip("\\")
        if not mid:
            continue
        req = [x.strip().lstrip("\\") for x in re.split(r"[,;]", info.get("require", "")) if x.strip()]
        out.append((mid, info.get("name", mid), folder, req))
    return out, ("" if out else "no Build 42 mod in it")


def stage(job, items, want_mods, notes):
    """The job's user folder: Zomboid/mods/<id> hard-linked copies, mods/default.txt, the player's options.ini.
    -> (Zomboid dir, enabled [(id, name)])."""
    zd = job / "home/Zomboid"
    if zd.exists():
        shutil.rmtree(zd)
    (zd / "mods").mkdir(parents=True)
    avail = collections.OrderedDict()
    for wid, d in items:
        ms, why = mods_of(wid)
        if why:
            notes.append(f"{d.get('title', wid)} ({wid}): skipped, {why}")
            continue
        for mid, name, folder, req in ms:
            avail.setdefault(mid, (name, folder, req, wid))
    if want_mods:
        chosen = [m for m in want_mods if m in avail]
        for m in want_mods:
            if m not in avail:
                notes.append(f"mod id {m}: in your Mods= list but in none of the items, left out")
        named = {avail[m][3] for m in chosen}
        for m, v in avail.items():  # an item none of whose mods the list names runs with all of them
            if v[3] not in named and m not in chosen:
                chosen.append(m)
                notes.append(f"mod {v[0]}: on (the list names no mod of Workshop item {v[3]}, so all of its mods are on)")
        # requirements of the chosen mods that the item set has
        for m in list(chosen):
            for r in avail[m][2]:
                if r in avail and r not in chosen:
                    chosen.append(r)
                    notes.append(f"mod {r}: enabled, {avail[m][0]} requires it")
    else:
        chosen = list(avail)
        multi = collections.Counter(v[3] for v in avail.values())
        if any(n > 1 for n in multi.values()):
            notes.append("no Mods= list given: every Build 42 mod of each item is on (items with several variants run them all)")
    # requirements before the mods that need them, else the given order
    done, ordered = set(), []

    def visit(m, depth=0):
        if m in done or depth > 20:
            return
        done.add(m)
        for r in avail[m][2]:
            if r in avail and r in chosen:
                visit(r, depth + 1)
        ordered.append(m)
    for m in chosen:
        visit(m)
    for m in ordered:
        missing = [r for r in avail[m][2] if r not in chosen]
        if missing:
            notes.append(f"{avail[m][0]} requires {', '.join(missing)}, which is not in the list (the game may not load it)")
        dst = zd / "mods" / m.replace("/", "_")
        try:
            subprocess.run(["cp", "-al", str(avail[m][1]), str(dst)], check=True, capture_output=True)
        except subprocess.CalledProcessError:
            shutil.copytree(avail[m][1], dst)
    (zd / "mods/default.txt").write_text("VERSION = 1,\n\nmods\n{\n" + "".join(f"    mod = {m},\n" for m in ordered)
                                         + "}\n\nmaps\n{\n}\n")
    # without its reset-mods-<version>.txt marker a fresh user folder's first boot empties default.txt
    # (ZomboidFileSystem.resetDefaultModsForNewRelease): the first test run loaded no mod
    for f in (Path.home() / "Zomboid/mods").glob("reset-mods-*.txt"):
        shutil.copy(f, zd / "mods" / f.name)
    if not list((zd / "mods").glob("reset-mods-*.txt")):
        (zd / "mods/reset-mods-42_00.txt").write_text("If this file does not exist, default.txt will be reset to empty (no mods active).")
    src = Path.home() / "Zomboid/options.ini"  # the desktop's display settings, so the window and the cap match
    if src.is_file():
        shutil.copy(src, zd / "options.ini")
    return zd, [(m, avail[m][0]) for m in ordered]


# --- the run ----------------------------------------------------------------------------------------------------------

def queue(*args, timeout=None):
    env = dict(os.environ, PZQ_SESSION="modprof-service", PZQ_SESSION_NAME="modprof-service")
    return subprocess.run([str(REPO / "harness/queue.sh"), *args], cwd=REPO, env=env, capture_output=True, text=True,
                          timeout=timeout)


def run_bench(tid, zd, n_mods, poster):
    label = f"bench-modprof-{tid[-8:]}"
    heap = ["-Xmx12g"] if n_mods >= 100 else ["-Xmx8g"] if n_mods >= 30 else []
    vm = " ".join([f"-Ddeployment.user.cachedir={zd.parent}"] + heap)
    r = queue("submit", "cmd", "--install", INSTALL, "--label", label, "--name", "modprof-service",
              "--intent", f"Discord #lua-mods-profiling: per-mod Lua cost of {poster}'s {n_mods}-mod list (automatic service, "
                          f"no decision depends on it; any time the desktop is free)",
              "--progress", "one benchmark per Discord post",
              "--", "env", f"BENCH_VMARGS={vm}", f"ZOMBOID={zd}", "BENCH_TIMEOUT=1200",
              "harness/benchmark-run.sh", SPEC, label)
    m = re.search(r"^job (\S+) queued", r.stdout, re.M)
    if r.returncode or not m:
        raise RuntimeError(f"queue submit failed: {(r.stdout + r.stderr)[-600:]}")
    return m.group(1), label


def wait_job(job_id):
    queue("wait", job_id)
    res = queue("result", job_id).stdout
    m = re.search(r"^run_dir=(\S+)", res, re.M)
    st = re.search(r"^status=(\S+)", res, re.M)
    run_dir = Path(m.group(1)) if m and m.group(1).startswith("/") else None
    return (st.group(1) if st else "?"), run_dir, res


def answer(tid, poster, run_dir, enabled, notes, job):
    results = run_dir / "results"
    out = job / "out"
    if out.exists():
        shutil.rmtree(out)
    out.mkdir(parents=True)
    nf = job / "notes.txt"
    nf.write_text("\n".join(notes))
    r = subprocess.run([sys.executable, str(REPO / "scripts/modprof-report.py"), str(results), "--out", str(out),
                        "--title", f"Lua cost of {poster}'s mod list", "--notes", str(nf)], capture_output=True, text=True)
    if r.returncode:
        raise RuntimeError(f"report: {r.stderr[-400:]}")
    summary = (out / "summary.txt").read_text().strip()
    files = [("modlist-lua-report.html", (out / "modlist-lua-report.html").read_bytes()),
             ("mods-summary.csv", (out / "mods-summary.csv").read_bytes())]
    frames = []
    for c in sorted(results.glob("step-*-luaprof.csv")):
        mm = re.match(r"step-\d+-([a-z0-9]+)-", c.name)
        frames.append((f"{mm.group(1) if mm else c.stem}-per-frame-luaprof.csv", c.read_bytes()))
    if sum(len(d) for _, d in files + frames) > UPLOAD_LIMIT:
        z = io.BytesIO()
        with zipfile.ZipFile(z, "w", zipfile.ZIP_DEFLATED, compresslevel=9) as zf:
            for n, d in frames:
                zf.writestr(n, d)
        frames = [("per-frame-luaprof-csv.zip", z.getvalue())]
    if sum(len(d) for _, d in files + frames) > UPLOAD_LIMIT:
        frames = []
        summary += "\n(the per-frame CSVs were too big for Discord and are left out)"
    skipped = [n for n in notes if "skipped" in n or "left out" in n or "did not load" in n]
    msg = (f"<@{poster_id(tid)}> here is the Lua profile of your {len(enabled)} mods (built-in benchmark, "
           f"{', '.join(dict.fromkeys(re.findall(r'^([^ :][^:]*):', summary, re.M)))}):\n```\n{summary}\n```\n"
           + (f"Skipped: {len(skipped)} (see the report's Notes)\n" if skipped else "")
           + "Open **modlist-lua-report.html** in a browser for every mod, function and event. "
             "`mods-summary.csv` = one row per mod; the per-frame CSVs = ms of Lua per mod in every frame.")
    post(tid, msg, files + frames, mention=poster_id(tid))


_POSTERS = {}


def poster_id(tid):
    return _POSTERS.get(tid)


# --- one post ---------------------------------------------------------------------------------------------------------

TEST_TEXT = None  # --test-list: the post's text


def gather(tid):
    """The post's text and its text attachments. -> (author dict, text)."""
    if TEST_TEXT is not None:
        return {"id": "0", "username": "test"}, TEST_TEXT
    m = dget(f"/channels/{tid}/messages/{tid}")  # a forum post's starter message has the thread's id
    text = (m.get("content") or "")
    for a in m.get("attachments", []):
        if a.get("size", 0) <= 2_000_000 and re.search(r"\.(txt|ini|cfg|json|csv|md|log)$", a.get("filename", ""), re.I) \
                or (a.get("content_type") or "").startswith("text/"):
            try:
                text += "\n" + fetch_text(a["url"])
            except Exception as e:
                log(f"{tid}: attachment {a.get('filename')}: {e}")
    return m["author"], text


def process(tid, st, title="", parse_only=False):
    rec = st["threads"].setdefault(tid, {})
    author, text = gather(tid)
    _POSTERS[tid] = author["id"]
    name = author.get("global_name") or author.get("username", "?")
    rec.update(author=author["id"], name=name, title=title)
    ws, mods = parse_list(text + "\n" + title)
    log(f"{tid} '{title}' by {name}: {len(ws)} Workshop ids, {len(mods)} mod ids")
    if not ws:
        if parse_only:
            print("nothing to run:", HELP)
            return
        rec["status"] = "no-list"
        save_state(st)
        post(tid, f"<@{author['id']}> I found no Workshop items in this post. {HELP}\nMake a new post with the list and "
                  f"it will be picked up within a minute.", mention=author["id"])
        return
    notes = []
    items = resolve(ws, notes)
    size = sum(int(d.get("file_size") or 0) for _, d in items)
    if parse_only:
        print(f"{len(items)} items, {size / 1e9:.2f} GB; mod ids: {mods or 'all'}")
        for w, d in items:
            print(f"  {w}  {d.get('title', '')[:70]}  {int(d.get('file_size') or 0) / 1e6:.1f} MB")
        print("notes:", *notes, sep="\n  ")
        return
    if len(items) > MAX_ITEMS or size > MAX_BYTES:
        rec["status"] = "too-big"
        save_state(st)
        post(tid, f"<@{author['id']}> that list is {len(items)} Workshop items / {size / 1e9:.1f} GB; the automatic runs take "
                  f"up to {MAX_ITEMS} items and {MAX_BYTES / 1e9:.0f} GB. Split it or ask in #general.", mention=author["id"])
        return
    if not items:
        rec["status"] = "no-items"
        save_state(st)
        post(tid, f"<@{author['id']}> none of those ids is a public Project Zomboid Workshop item.\n" + "\n".join(notes[:15]),
             mention=author["id"])
        return
    post(tid, f"Got it: {len(items)} Workshop items ({size / 1e9:.2f} GB). Downloading, then a benchmark run on the "
              f"maintainer's PC (one Rosewood spin pass, a few minutes once its turn comes). The report will be "
              f"posted here.")
    rec["status"] = "downloading"
    save_state(st)
    present = download(items)
    for w, d in items:
        if w not in present:
            notes.append(f"{d.get('title', w)} ({w}): skipped, the download failed")
    items = [(w, d) for w, d in items if w in present]
    items = add_requirements(items, notes)
    job = CACHE / "jobs" / tid
    zd, enabled = stage(job, items, mods, notes)
    if not enabled:
        rec["status"] = "nothing-to-run"
        save_state(st)
        post(tid, f"<@{author['id']}> no mod of that list can run here:\n" + "\n".join(f"- {n}" for n in notes[:20]),
             mention=author["id"])
        shutil.rmtree(job, ignore_errors=True)
        return
    job_id, label = run_bench(tid, zd, len(enabled), name)
    rec.update(status="queued", job=job_id, label=label, mods=len(enabled), enabled=[m for m, _ in enabled], notes=notes)
    save_state(st)
    log(f"{tid}: queue job {job_id} ({label}), {len(enabled)} mods")
    finish(tid, st)


def finish(tid, st):
    rec = st["threads"][tid]
    _POSTERS.setdefault(tid, rec.get("author"))
    status, run_dir, res = wait_job(rec["job"])
    job = CACHE / "jobs" / tid
    enabled = rec.get("enabled", [])
    ok = run_dir is not None and any((run_dir / "results").glob("step-*-luaprof.properties"))
    if ok:
        st_txt = run_dir / "results/setup.txt"  # the mods the game had active when the benchmark started
        loaded = {l.split("\t")[1] for l in st_txt.read_text(errors="replace").splitlines()
                  if l.startswith("M\t")} if st_txt.is_file() else set(enabled)
        notes = list(rec.get("notes", []))
        for m in enabled:
            if m not in loaded:
                notes.append(f"mod {m}: did not load in the game (a missing requirement or a broken mod.info), not measured")
        if enabled and not loaded:
            ok = False
        for sp in sorted((run_dir / "results").glob("step-*.properties")):
            m = re.search(r"(?m)^mod_pauses_resumed=(\d+)", sp.read_text(errors="replace"))
            if m and int(m.group(1)) > 0:
                notes.append(f"{sp.stem}: a mod paused the game {m.group(1)} time(s) (a popup calling setGameSpeed(0)); "
                             f"the benchmark resumed it, the window stayed open")
    if ok:
        answer(tid, rec.get("name", "?"), run_dir, sorted(loaded), notes, job)
        rec.update(status="done", run_dir=str(run_dir), finished=time.time())
        log(f"{tid}: answered ({run_dir.name})")
    else:
        rec.update(status="failed", finished=time.time(), result=res[-1500:])
        log(f"{tid}: job {rec['job']} {status}, no profile:\n{res[-800:]}")
        post(tid, f"<@{rec.get('author')}> the benchmark with your mods did not finish, or the game loaded none of them (one "
                  f"may stop the game from starting or the world from loading). The maintainer has been told and will look at it.",
             mention=rec.get("author"))
        subprocess.run(["notify-send", "-a", "pzopt-modprof", "Mod profile failed", f"post {tid}, queue job {rec['job']}"],
                       capture_output=True)
    save_state(st)
    if ok:
        shutil.rmtree(job / "home", ignore_errors=True)  # the user folder with the mods and the bench world


def allowed(st, author):
    day = time.time() - 86400
    mine = [r for r in st["threads"].values() if r.get("author") == author]
    if any(r.get("status") in ("downloading", "queued") for r in mine):
        return "one of your posts is still being measured; this one starts after it"
    if sum(1 for r in mine if r.get("started", 0) > day) >= USER_DAILY:
        return f"{USER_DAILY} mod lists a day per person"
    return ""


def poll(st, only=None, parse_only=False):
    for t in forum_posts():
        tid = t["id"]
        if only and tid != only:
            continue
        rec = st["threads"].get(tid)
        if rec and not only:
            if rec.get("status") == "queued":  # the service restarted while its job ran: pick it up again
                try:
                    finish(tid, st)
                except Exception:
                    log(f"{tid}: resume failed\n{traceback.format_exc()}")
            continue
        if not only:
            starter = dget(f"/channels/{tid}/messages/{tid}")
            if starter["author"].get("bot"):
                st["threads"][tid] = {"status": "ignored-bot"}
                save_state(st)
                continue
            why = allowed(st, starter["author"]["id"])
            if why.startswith("one of"):
                continue  # retried on a later poll
            if why:
                st["threads"][tid] = {"status": "rate-limited", "author": starter["author"]["id"], "started": time.time()}
                save_state(st)
                post(tid, f"<@{starter['author']['id']}> the automatic runs are limited to {why}; post again tomorrow.",
                     mention=starter["author"]["id"])
                continue
        if not parse_only:
            st["threads"][tid] = {"status": "picked", "started": time.time()}
            save_state(st)
        try:
            process(tid, st, t.get("name", ""), parse_only)
        except Exception as e:
            log(f"{tid}: failed\n{traceback.format_exc()}")
            if parse_only:
                raise
            st["threads"][tid].update(status="error", error=str(e)[:500])
            save_state(st)
            try:
                post(tid, "Something went wrong on my side while preparing this run; the maintainer has been told.")
            except Exception:
                pass
            subprocess.run(["notify-send", "-a", "pzopt-modprof", "Mod profile error", f"post {tid}: {str(e)[:200]}"],
                           capture_output=True)


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--once", action="store_true")
    ap.add_argument("--thread", help="process this post now (again)")
    ap.add_argument("--parse-only", action="store_true", help="with --thread: print the plan, change nothing")
    ap.add_argument("--interval", type=int, default=60)
    ap.add_argument("--test-list", metavar="FILE", help="the whole pipeline on a local mod list (a real queue run); the "
                    "replies and attachments go to ~/.cache/pzopt-modprof/test-<time>/ instead of Discord")
    a = ap.parse_args()
    if not shutil.which("steamcmd"):
        sys.exit("modprof-watch: steamcmd not installed")
    st = load_state()
    if a.test_list:
        global DRY_OUT, TEST_TEXT
        tid = f"test{int(time.time())}"
        DRY_OUT = CACHE / tid
        DRY_OUT.mkdir(parents=True)
        TEST_TEXT = Path(a.test_list).read_text()
        st = {"threads": {tid: {"started": time.time()}}}  # never mixed into the service's state
        process(tid, st, "test", a.parse_only)
        print(json.dumps(st["threads"][tid], indent=1)[:1500])
        return
    if a.thread:
        poll(st, only=a.thread, parse_only=a.parse_only)
        return
    log(f"watching channel {CHANNEL} every {a.interval} s (spec {SPEC}, install {INSTALL})")
    while True:
        try:
            poll(st)
        except Exception:
            log(f"poll failed\n{traceback.format_exc()}")
        if a.once:
            return
        time.sleep(a.interval)


if __name__ == "__main__":
    main()
