#!/usr/bin/env python3
"""Jev orders the run queue (harness/queue.sh, 2026-09-22): the only sorter of a machine's pending jobs.

  harness/queue-jev.py rank --q <PZQ_DIR> --machine <m> [--why]
  harness/queue-jev.py suggest --intent "<what the session wants to learn>" [--resource r1,r2] [--args "<run.sh args>"]
  harness/queue-jev.py resources

suggest: Jev picks the standard bench run (harness/queue/benches.json; every entry names the resources it tests)
for an intent and the resources the run must stress, and with --args judges whether the session's own arguments
test them (fits=, and matches_suggestion= computed in code). key=value lines; exit 3 = Jev unavailable.

Code gathers the facts each session provides or the queue measures, per pending job on that machine:
the session's name, the job's intent and the session's task progress (both typed by the session at
submit / `queue.sh session`), how long the job has waited, how long the session has been running and
how long it has waited in the queue altogether, the job's size estimate (and where it came from), the
session's other pending jobs, what is running now. Jev reads that JSON and answers one choice question
("which job runs next"); its probabilities over every candidate are the whole order, so one request
gives both the pick and the plan.

Output: one line per pending job, pick first: "<job dir>\t<probability>\t<eta seconds>". The plan is
also written to <PZQ_DIR>/machines/<m>/plan (human-readable, what `queue.sh next` prints).
Exit 3 = Jev could not be asked (no key, network): the caller decides (queue.sh blocks, or FIFO with
PZQ_JEV_FALLBACK=fifo). A single candidate is returned without a request: there is nothing to order.
"""
import argparse
import datetime as dt
import glob
import json
import os
import sys
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))
from typesafe_client import choice, noul  # noqa: E402  (pure helpers; ask() is imported where a request is made)

CLAUDE_PROJECTS = Path.home() / ".claude" / "projects"

POLICY = (
    "You are the scheduler of a shared benchmark machine. Several AI coding sessions submit jobs (game benchmark runs, "
    "media encodes, commands) that must run one at a time on this machine. Pick the job that should run next so that "
    "the sessions as a whole make the most progress and nobody is starved. Weigh: (1) a session that has waited long "
    "in total, or whose job has waited long, deserves to go soon (a job waiting well past 30 minutes should almost "
    "always go next); (2) short jobs (small estimate_s) cost the others little, prefer them when other things are "
    "equal, but never let a long job be skipped over and over; (3) a session close to finishing its task (progress "
    "says last step, wrap-up, final encode / verification before a commit or release) should be unblocked quickly; "
    "media jobs (encodes / stitches) usually mean a session is wrapping up; (4) a session's first pending job should "
    "normally go before another session's second or third job of a batch (spread the machine between sessions); "
    "(5) keep a session's own jobs in the order it submitted them unless its intent says otherwise (an A/B pair "
    "belongs together); (6) jobs whose intent blocks other work (a release, a build other runs depend on, a "
    "regression check) are urgent. Facts are measured by the queue; intent and progress are the sessions' own words."
)


def read_kv(path):
    out = {}
    try:
        for line in Path(path).read_text(errors="replace").splitlines():
            if "=" in line:
                k, v = line.split("=", 1)
                out.setdefault(k, v)
    except OSError:
        pass
    return out


def read(path, default=""):
    try:
        return Path(path).read_text(errors="replace").strip()
    except OSError:
        return default


def epoch(stamp):
    try:
        return dt.datetime.strptime(stamp, "%Y-%m-%d %H:%M:%S").timestamp()
    except (TypeError, ValueError):
        return None


def session_start(q, sid):
    """First timestamp of the Claude Code transcript <sid>.jsonl, else the queue's first sight of the session."""
    for f in glob.glob(str(CLAUDE_PROJECTS / "*" / f"{sid}.jsonl")):
        try:
            with open(f, errors="replace") as fh:
                for _, line in zip(range(60), fh):
                    i = line.find('"timestamp":"')
                    if i >= 0:
                        s = line[i + 13:line.index('"', i + 13)]
                        return dt.datetime.fromisoformat(s.replace("Z", "+00:00")).timestamp()
        except (OSError, ValueError):
            pass
    return epoch(read(Path(q) / "sessions" / sid / "first_seen")) or None


def gather(q, machine):
    q = Path(q)
    now = time.time()
    jobs = []
    for d in sorted(glob.glob(str(q / "jobs" / "*"))):
        j = read_kv(Path(d) / "job")
        if not j:
            continue
        j["_dir"] = d
        j["_status"] = read(Path(d) / "status", "unknown")
        jobs.append(j)
    pending = [j for j in jobs if j["_status"] == "pending" and j.get("machine") == machine]
    running = [j for j in jobs if j["_status"] == "running" and j.get("machine") == machine]

    def is_prio(j):  # the maintainer's priority: sessions/<sid>/priority exists, or the job's intent starts with it
        return ((q / "sessions" / j.get("session", "?") / "priority").exists()
                or (j.get("intent") or "").startswith("MAINTAINER PRIORITY"))

    # whether the job started last on this machine (running or finished) was a priority one: the turns alternate
    last = max(((epoch(read(Path(j["_dir"]) / "started")) or 0, j) for j in jobs
                if j.get("machine") == machine and j["_status"] != "pending"), key=lambda t: t[0], default=None)
    last_prio = bool(last and is_prio(last[1]))

    sessions = {}

    def sess(sid):
        if sid not in sessions:
            sd = q / "sessions" / sid
            start = session_start(q, sid)
            mine = [j for j in jobs if j.get("session") == sid]
            waited = 0.0
            for j in mine:  # queue wait of every job of the session: submitted -> started (or now while pending)
                sub = epoch(j.get("submitted"))
                if sub is None:
                    continue
                st = epoch(read(Path(j["_dir"]) / "started"))
                if j["_status"] == "pending":
                    waited += now - sub
                elif st is not None and st >= sub:
                    waited += st - sub
            prog_at = epoch(read(sd / "progress_at"))
            sessions[sid] = {
                "name": read(sd / "name") or sid[:8],
                "intent": read(sd / "intent") or "(not given)",
                "progress": read(sd / "progress") or "(not given)",
                "progress_updated_min_ago": round((now - prog_at) / 60, 1) if prog_at else None,
                "session_age_min": round((now - start) / 60, 1) if start else None,
                "total_queue_wait_min": round(waited / 60, 1),
                "jobs_done_or_failed": sum(1 for j in mine if j["_status"] in ("done", "failed")),
                "jobs_pending_everywhere": sum(1 for j in mine if j["_status"] == "pending"),
            }
        return sessions[sid]

    cands = []
    for j in pending:
        sid = j.get("session", "?")
        s = sess(sid)
        sub = epoch(j.get("submitted"))
        own = [p for p in pending if p.get("session") == sid]
        cands.append({
            "id": j.get("id"),
            "label": j.get("label"),
            "kind": j.get("kind"),
            "session": s["name"],
            "intent": j.get("intent") or "(not given: submitted before the Jev queue)",
            "session_progress": j.get("progress") or s["progress"],
            "waited_min": round((now - sub) / 60, 1) if sub else None,
            "estimate_s": int(j.get("size") or 0) or None,
            "estimate_from": j.get("size_from") or "?",
            "position_in_own_session_batch": 1 + own.index(j),
            "session_pending_on_this_machine": len(own),
            "blocked": read(Path(j["_dir"]) / "blocked") or None,
            "resources_tested": j.get("resource") or None,
            "bench": j.get("bench") or None,
            "args": (j.get("args") or "")[:200],
            "goal": j.get("goal") or None,
            "_dir": j["_dir"],
            "_prio": is_prio(j),
        })
    run_now = None
    if running:
        r = running[0]
        st = epoch(read(Path(r["_dir"]) / "started"))
        el = (now - st) if st else 0
        run_now = {"label": r.get("label"), "session": sess(r.get("session", "?"))["name"],
                   "estimate_s": int(r.get("size") or 0) or None, "elapsed_s": int(el),
                   "remaining_s_estimate": max(0, int(r.get("size") or 0) - int(el))}
    return cands, run_now, sessions, last_prio


def eta(order, run_now):
    t = run_now["remaining_s_estimate"] if run_now else 0
    out = []
    for c in order:
        out.append(t)
        t += c["estimate_s"] or 60
    return out


def write_plan(q, machine, order, probs, etas, run_now, how):
    p = Path(q) / "machines" / machine
    p.mkdir(parents=True, exist_ok=True)
    lines = [f"plan for {machine} at {time.strftime('%Y-%m-%d %H:%M:%S')} ({how})"]
    if run_now:
        lines.append(f" running: {run_now['label']} ({run_now['session']}), ~{run_now['remaining_s_estimate']} s left of ~{run_now['estimate_s']} s")
    for n, (c, pr, e) in enumerate(zip(order, probs, etas), 1):
        lines.append(f"{n:2d}. {c['id']} {c['label'][:30]:30s} {c['session'][:22]:22s} ~{c['estimate_s'] or '?'} s, "
                     f"waited {c['waited_min']} min, starts in ~{round(e / 60, 1)} min  p={pr:.2f}  intent: {c['intent'][:80]}")
    tmp = p / "plan.tmp"
    tmp.write_text("\n".join(lines) + "\n")
    os.replace(tmp, p / "plan")


def rank(args):
    cands, run_now, sessions, last_prio = gather(args.q, args.machine)
    if not cands:
        return 1
    # jobs the maintainer gave priority take turns with the others (one each, starting with whoever did not run
    # last), in submission order; Jev orders the others
    prio = sorted((c for c in cands if c["_prio"]), key=lambda c: c["id"])
    cands = [c for c in cands if not c["_prio"]]
    how = "single candidate"
    if not cands:
        order, probs = [], []
    elif len(cands) == 1:
        order, probs = cands, [1.0]
    else:
        try:
            from typesafe_client import ask, choice
        except SystemExit:
            return 3
        state = {
            "machine": args.machine,
            "now": time.strftime("%Y-%m-%d %H:%M"),
            "running_now": run_now,
            "sessions": {v["name"]: {k: x for k, x in v.items() if k != "name"} for v in sessions.values()},
            "pending": [{k: v for k, v in c.items() if not k.startswith("_")} for c in cands],
        }
        crit = {f"job_{c['id']}": (f"{c['label']} ({c['kind']}, session {c['session']}, ~{c['estimate_s']} s, "
                                   f"waited {c['waited_min']} min): {c['intent'][:160]}") for c in cands}
        try:
            ans = ask(state, {"next": choice(POLICY + " Which job in `pending` should run next on `machine`?", crit)},
                      timeout=45, retries=2)["next"]
        except (SystemExit, Exception) as e:  # noqa: BLE001 - any failure means "Jev could not be asked"
            print(f"queue-jev: Jev unavailable: {e}", file=sys.stderr)
            return 3
        pr = ans.get("probabilities") or {}
        pick = ans.get("choice")
        key = {f"job_{c['id']}": c for c in cands}
        # the pick first, then the rest by probability, submission order on a tie
        order = sorted(cands, key=lambda c: (f"job_{c['id']}" != pick, -pr.get(f"job_{c['id']}", 0.0), c["id"]))
        probs = [pr.get(f"job_{c['id']}", 0.0) for c in order]
        how = f"Jev, confidence {ans.get('confidence', 0):.2f}"
        if pick not in key:
            how += " (pick not a candidate; ordered by probability)"
    if prio:
        mine, theirs, merged, ps = list(prio), list(zip(order, probs)), [], []
        turn_mine = not last_prio
        while mine or theirs:
            if (turn_mine and mine) or not theirs:
                merged.append(mine.pop(0)); ps.append(1.0)
            else:
                c, pr_ = theirs.pop(0); merged.append(c); ps.append(pr_)
            turn_mine = not turn_mine
        order, probs = merged, ps
        how = f"{len(prio)} maintainer-priority job(s) take turns with the others; " + how
    etas = eta(order, run_now)
    write_plan(args.q, args.machine, order, probs, etas, run_now, how)
    for c, p, e in zip(order, probs, etas):
        print(f"{c['_dir']}\t{p:.3f}\t{e}")
    if args.why:
        print(how, file=sys.stderr)
    return 0


CATALOG = Path(__file__).parent / "queue" / "benches.json"


def load_catalog():
    return json.loads(CATALOG.read_text())


def tokens_cover(catalog_args, submitted):
    """The submitted run.sh arguments contain every option (with its value) of the catalog entry."""
    sub = submitted.split()
    pairs = set(zip(sub, sub[1:] + [""]))
    toks = catalog_args.split()
    i = 0
    while i < len(toks):
        t = toks[i]
        nxt = toks[i + 1] if i + 1 < len(toks) and not toks[i + 1].startswith("--") else None
        if nxt is not None:
            if (t, nxt) not in pairs:
                return False
            i += 2
        else:
            if t not in sub:
                return False
            i += 1
    return True


def suggest(args):
    """Jev picks the standard bench run for an intent and the resources it wants tested; with --args it also
    judges whether the session's own run.sh arguments test those resources. key=value lines on stdout."""
    cat = load_catalog()
    vocab = cat["resources"]
    res = [r.strip() for r in (args.resource or "").split(",") if r.strip()]
    bad = [r for r in res if r not in vocab]
    if bad:
        print(f"error=unknown resource {', '.join(bad)} (one of: {', '.join(vocab)})")
        return 2
    benches = {b["name"]: b for b in cat["benches"]}
    crit = {b["name"]: f"tests {', '.join(b['resources'])}: {b['what']}" for b in cat["benches"]}
    crit["none"] = "no standard bench fits the intent and resources; a one-off rig (the session's own arguments) is right"
    state = {
        "intent": args.intent,
        "resources_to_test": {r: vocab[r] for r in res} or "(not given: infer them from the intent)",
        "catalog": [{"name": b["name"], "tests": b["resources"], "what": b["what"], "args": b["args"]} for b in cat["benches"]],
    }
    qs = {"bench": choice("A benchmarking session describes in `intent` what it wants to learn and in `resources_to_test` "
                          "which hardware / engine resources the run must stress. Which bench run from `catalog` answers "
                          "that best? Prefer the run whose `tests` cover `resources_to_test` and whose `what` matches the "
                          "scene the intent is about (weather, zombies, driving, zoom, lights, loading, visual check).", crit)}
    if args.args:
        state["submitted_args"] = args.args
        qs["fits"] = noul("The session will run the harness/run.sh arguments in `submitted_args`. Do they exercise the "
                          "resources in `resources_to_test` well enough to answer `intent` (right scene, mode, route, "
                          "flags, recording when a visual check is needed)?")
    if not res:
        qs["resource"] = choice("Which single resource from `resources` does `intent` most need tested?",
                                {k: v for k, v in vocab.items()})
        state["resources"] = vocab
    try:
        from typesafe_client import ask
        ans = ask(state, qs, timeout=45, retries=2)
    except (SystemExit, Exception) as e:  # noqa: BLE001
        print(f"error=Jev unavailable: {e}")
        return 3
    b = ans["bench"]
    pick = b.get("choice")
    probs = sorted((b.get("probabilities") or {}).items(), key=lambda x: -x[1])
    print(f"bench={pick}")
    print(f"confidence={b.get('confidence', 0):.2f}")
    if pick in benches:
        print(f"args={benches[pick]['args']}")
        print(f"tests={','.join(benches[pick]['resources'])}")
        print(f"what={benches[pick]['what']}")
    print("alternatives=" + ", ".join(f"{n} {p:.2f}" for n, p in probs[1:4] if p >= 0.02))
    if "resource" in ans:
        print(f"resource_inferred={ans['resource'].get('choice')}")
    if args.args:
        print(f"fits={ans['fits']['noul']:.2f}")
        if pick in benches:
            print(f"matches_suggestion={'yes' if tokens_cover(benches[pick]['args'], args.args) else 'no'}")
    return 0


def main():
    ap = argparse.ArgumentParser()
    sub = ap.add_subparsers(dest="cmd", required=True)
    r = sub.add_parser("rank")
    r.add_argument("--q", required=True)
    r.add_argument("--machine", required=True)
    r.add_argument("--why", action="store_true")
    s = sub.add_parser("suggest")
    s.add_argument("--intent", required=True)
    s.add_argument("--resource", default="", help="comma-separated, from benches.json `resources`")
    s.add_argument("--args", default="", help="the session's own run.sh arguments, to judge the fit")
    sub.add_parser("resources")
    a = ap.parse_args()
    if a.cmd == "resources":
        for k, v in load_catalog()["resources"].items():
            print(f"{k:14s} {v}")
        sys.exit(0)
    sys.exit(rank(a) if a.cmd == "rank" else suggest(a))


if __name__ == "__main__":
    main()
