#!/usr/bin/env python3
"""Read the project's Discord server (https://discord.gg/WNeQqYZ4T) through the bot: player reports, release feedback.

    scripts/discord-read.py channels                       # the text channels the bot sees (id, name)
    scripts/discord-read.py read general [-n 100]          # the last N messages of a channel (name or id), oldest first
    scripts/discord-read.py read general --since 2026-10-10T15:00   # only messages after a UTC time
    scripts/discord-read.py threads bug-reports            # a forum channel's posts; read one with: read <thread id>

Read-only: GET requests only, it never posts (releases are announced by discord-announce.py through a webhook).
The bot token is a credential and the repo is public, so it is never committed: $PZOPT_DISCORD_BOT_TOKEN, else
~/.config/pzopt/discord-bot-token (one line). The bot needs View Channel + Read Message History on the channel, and
the Message Content intent for the text of other users' messages. Times are printed in UTC.
"""

import argparse, json, os, sys, urllib.error, urllib.request
from pathlib import Path

API = "https://discord.com/api/v10"


def token():
    t = os.environ.get("PZOPT_DISCORD_BOT_TOKEN") or ""
    if not t:
        p = Path.home() / ".config/pzopt/discord-bot-token"
        if p.is_file():
            t = p.read_text().strip()
    if not t:
        sys.exit("discord-read: no bot token ($PZOPT_DISCORD_BOT_TOKEN or ~/.config/pzopt/discord-bot-token)")
    return t


def get(path):
    req = urllib.request.Request(API + path, headers={"Authorization": "Bot " + token(),
                                                      "User-Agent": "pzopt-discord-read (github.com/xD3I/PZ_Optimization, 1)"})
    try:
        with urllib.request.urlopen(req, timeout=20) as r:
            return json.load(r)
    except urllib.error.HTTPError as e:
        sys.exit(f"discord-read: GET {path} -> HTTP {e.code}: {e.read().decode(errors='replace')[:300]}")


def text_channels():
    out = []
    for g in get("/users/@me/guilds"):
        for c in get(f"/guilds/{g['id']}/channels"):
            if c["type"] in (0, 5, 15):  # text, announcement, forum
                out.append((g["name"], c["id"], c["name"]))
    return out


def resolve(channel):
    if channel.isdigit():
        return channel
    for _, cid, name in text_channels():
        if name == channel.lstrip("#"):
            return cid
    sys.exit(f"discord-read: no channel named {channel!r} (see: discord-read.py channels)")


def threads(channel):
    """A forum channel's posts (#bug-reports): its active threads plus the archived public ones, newest first."""
    cid = resolve(channel)
    guild = get(f"/channels/{cid}")["guild_id"]
    out = [t for t in get(f"/guilds/{guild}/threads/active").get("threads", []) if t.get("parent_id") == cid]
    out += get(f"/channels/{cid}/threads/archived/public?limit=50").get("threads", [])
    seen, rows = set(), []
    for t in out:
        if t["id"] not in seen:
            seen.add(t["id"])
            rows.append(t)
    rows.sort(key=lambda t: int(t["id"]), reverse=True)  # snowflake order = creation order
    return rows


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = ap.add_subparsers(dest="cmd", required=True)
    sub.add_parser("channels")
    th = sub.add_parser("threads", help="a forum channel's posts (bug-reports): id, created, messages, title")
    th.add_argument("channel")
    th.add_argument("-n", type=int, default=15)
    rd = sub.add_parser("read")
    rd.add_argument("channel", help="channel name (general) or id")
    rd.add_argument("-n", type=int, default=100, help="messages to fetch (default 100)")
    rd.add_argument("--since", help="UTC ISO time; only messages after it")
    a = ap.parse_args()

    if a.cmd == "channels":
        for guild, cid, name in text_channels():
            print(f"{cid}  #{name}  ({guild})")
        return
    if a.cmd == "threads":
        for t in threads(a.channel)[:a.n]:
            meta = t.get("thread_metadata", {})
            print(f"{t['id']}  {meta.get('create_timestamp', '')[:16]}  {t.get('message_count', '?'):>3} msgs  {t['name']}")
        return

    cid = resolve(a.channel)
    msgs, before = [], None
    while len(msgs) < a.n:
        page = get(f"/channels/{cid}/messages?limit={min(100, a.n - len(msgs))}" + (f"&before={before}" if before else ""))
        if not page:
            break
        msgs += page
        before = page[-1]["id"]
        if a.since and page[-1]["timestamp"][:len(a.since)] < a.since:
            break
    for m in reversed(msgs):
        ts = m["timestamp"][:19]
        if a.since and ts[:len(a.since)] < a.since:
            continue
        ref = m.get("referenced_message") or {}
        to = f" -> {ref['author']['username']}" if ref.get("author") else ""
        att = "".join(" " + x["url"] for x in m.get("attachments", []))
        body = (m.get("content") or "").replace("\n", " / ")
        if m.get("type") not in (0, 19) and not body:
            continue  # joins and other system messages
        print(f"[{ts}Z] {m['author']['username']}{to}: {body}{att}")


if __name__ == "__main__":
    main()
