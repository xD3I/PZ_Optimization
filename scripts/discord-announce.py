#!/usr/bin/env python3
"""Announce a published release in the project's Discord server (https://discord.gg/WNeQqYZ4T).

    scripts/discord-announce.py --tag b42.21-20261003-2207-8d97af98            # post it
    scripts/discord-announce.py --tag <tag> --dry-run                           # print the payload only
    scripts/discord-announce.py --tag <tag> --headline "Zombies attack cars again"
    scripts/discord-announce.py --seed-cards                                    # mark the page's current New! cards as posted

Every release is announced (maintainer, 2026-10-04). scripts/workshop.sh --tag <tag> --upload runs this after
a successful Workshop upload, so the post links both the GitHub release and the Workshop page.

The post goes through a channel webhook (Discord: channel > Edit Channel > Integrations > Webhooks > New
Webhook > Copy Webhook URL). The URL is a write credential and the repo is public, so it is never committed:
$PZOPT_DISCORD_WEBHOOK, else ~/.config/pzopt/discord-webhook (one line). Tags already posted are listed in
~/.config/pzopt/discord-announced.txt; a second post of the same tag needs --force.

Headline: --headline (the user-facing change in a few words), else the release title. Body: the release notes' middle part (what
release.sh --notes added), without the boilerplate install / sha256 sentences.

New! cards (maintainer, 2026-10-15): the Workshop page's "[h1]New! <title>[/h1]" sections (docs/workshop/description.txt of
this checkout, the page workshop.sh just uploaded; --description for another file) are attached to the post when they were
not posted before: one embed per card with its title and its first still image; an animated AVIF card (Discord plays no
animated AVIF) goes as an AV1 10-bit MP4 in the project's published-video format (PQ / BT.2020, SDR white at 203 nits, the
card's size and frame rate; quality lowered until the post fits the upload limit), attached under the post; PNG / JPG / GIF
images go as they are. Image files are read from docs/workshop/images/
when the URL is this repo's raw GitHub path, else downloaded. Posted card images are listed in
~/.config/pzopt/discord-announced-cards.txt; a card whose every image is listed is not posted again (--force-cards posts the
page's cards anyway, --no-cards leaves them out). Without that file the first run would post the page's current cards, so
--seed-cards records them without posting.
"""
import argparse, io, json, os, re, subprocess, sys, tempfile, urllib.error, urllib.request, uuid
from pathlib import Path

CONF = Path.home() / ".config/pzopt"
WORKSHOP = "https://steamcommunity.com/sharedfiles/filedetails/?id=3805285544"
REPO = Path(__file__).resolve().parent.parent
RAW = "https://raw.githubusercontent.com/xD3I/PZ_Optimization/"
UA = "PZ_Optimization-release (https://github.com/xD3I/PZ_Optimization, 1.0)"
CARDS_SENT = CONF / "discord-announced-cards.txt"
LIMIT = 9_500_000  # bytes for all attachments of one post (Discord's 10 MB webhook upload limit, with margin)


def webhook():
    url = os.environ.get("PZOPT_DISCORD_WEBHOOK", "").strip()
    if not url and (CONF / "discord-webhook").exists():
        url = (CONF / "discord-webhook").read_text().strip()
    return url


def page_cards(path):
    """The page's New! sections: [(title, [image URL, ...]), ...] in page order."""
    cards, cur = [], None
    for line in Path(path).read_text(errors="replace").splitlines():
        m = re.match(r"\s*\[h1\]\s*New!\s*(.*?)\s*\[/h1\]", line)
        if m:
            cur = (m.group(1), [])
            cards.append(cur)
            continue
        if cur is None:
            continue
        if re.match(r"\s*\[h1\]", line):
            cur = None
            continue
        cur[1].extend(re.findall(r"\[img\]\s*(\S+?)\s*\[/img\]", line))
    return [c for c in cards if c[1]]


def sent_cards():
    return set(CARDS_SENT.read_text().split()) if CARDS_SENT.exists() else set()


def image_bytes(url):
    """Local docs/workshop/images/ copy of this repo's raw URL, else a download."""
    m = re.match(re.escape(RAW) + r"[^/]+/(.+)$", url)
    if m and (REPO / m.group(1)).is_file():
        return (REPO / m.group(1)).read_bytes()
    req = urllib.request.Request(url, headers={"User-Agent": UA})
    with urllib.request.urlopen(req, timeout=30) as r:
        return r.read()


def avif_frames(data):
    from PIL import Image
    im = Image.open(io.BytesIO(data))
    n = getattr(im, "n_frames", 1)
    frames, durs = [], []
    for k in range(n):
        im.seek(k)
        frames.append(im.convert("RGBA"))
        durs.append(im.info.get("duration", 1000 / 60) or 1000 / 60)
    return frames, durs


def to_video(data, crf):
    """An animated AVIF card as an AV1 10-bit MP4 in the project's published-video format (PQ / BT.2020, the SDR card mapped
    with reference white at 203 nits as harness/encode-av1-hdr.sh does), the card's own size and frame rate. NVENC on the
    desktop, SVT-AV1 elsewhere."""
    frames, durs = avif_frames(data)
    w, h = frames[0].size
    w2, h2 = w - w % 2, h - h % 2
    fps = 1000.0 / (sum(durs) / len(durs))
    # Pillow gives AVIF frame durations in whole ms (17 for a 60 fps card): snap to the standard rate it came from
    fps = min((24, 25, 30, 48, 50, 60, 90, 120), key=lambda r: abs(r - fps)) if abs(fps - round(fps)) > 0.01 else fps
    vf = (f"crop={w2}:{h2}:0:0,format=yuv420p,setparams=color_primaries=bt709:color_trc=bt709:colorspace=bt709:range=tv,"
          "zscale=t=linear:npl=203,format=gbrpf32le,zscale=pin=bt709:tin=linear:p=bt2020:t=smpte2084:m=bt2020nc:r=tv:npl=203,"
          "format=yuv420p10le,setparams=color_primaries=bt2020:color_trc=smpte2084:colorspace=bt2020nc:range=tv")
    nvenc = "av1_nvenc" in subprocess.run(["ffmpeg", "-hide_banner", "-encoders"], capture_output=True, text=True).stdout
    enc = (["-c:v", "av1_nvenc", "-preset", "p7", "-tune", "hq", "-rc", "vbr", "-cq", str(crf), "-b:v", "0", "-pix_fmt", "p010le"]
           if nvenc else ["-c:v", "libsvtav1", "-preset", "6", "-crf", str(crf), "-pix_fmt", "yuv420p10le"])
    with tempfile.TemporaryDirectory(prefix="pzopt-card-") as d:
        out = f"{d}/card.mp4"
        cmd = (["ffmpeg", "-hide_banner", "-v", "error", "-y", "-f", "rawvideo", "-pix_fmt", "rgb24", "-s", f"{w}x{h}",
                "-r", f"{fps:.3f}", "-i", "-", "-vf", vf] + enc
               + ["-color_primaries", "bt2020", "-color_trc", "smpte2084", "-colorspace", "bt2020nc", "-color_range", "tv",
                  "-movflags", "+faststart+write_colr", out])
        proc = subprocess.Popen(cmd, stdin=subprocess.PIPE)
        for f in frames:
            proc.stdin.write(f.convert("RGB").tobytes())
        proc.stdin.close()
        if proc.wait() != 0:
            raise RuntimeError("ffmpeg failed to encode the card video")
        return Path(out).read_bytes()


def card_files(cards, budget):
    """[(title, [(filename, bytes)])] for the cards, animated AVIFs as AV1 videos, all files together under `budget` bytes."""
    raw = []
    for title, urls in cards:
        files = []
        for url in urls:
            name = url.rsplit("/", 1)[-1].split("?")[0] or "card"
            files.append([name, image_bytes(url), name.lower().endswith(".avif")])
        raw.append((title, files))
    for crf in (40, 46, 52):  # card-sized AV1: 1.6 MB for the 16 s 630 px puddle card at 40 (the AVIF is 1.9 MB)
        out, total = [], 0
        for title, files in raw:
            done = []
            for name, data, avif in files:
                if avif:
                    data, name = to_video(data, crf), name[:-5] + ".mp4"
                done.append((name, data))
                total += len(data)
            out.append((title, done))
        if total <= budget:
            return out
    raise RuntimeError(f"New! card attachments are {total} bytes even at quality {crf} (limit {budget})")


def multipart(payload, files):
    boundary = uuid.uuid4().hex
    parts = [f'--{boundary}\r\nContent-Disposition: form-data; name="payload_json"\r\nContent-Type: application/json\r\n\r\n'
             .encode() + json.dumps(payload).encode() + b"\r\n"]
    for k, (name, data) in enumerate(files):
        ctype = {"mp4": "video/mp4", "gif": "image/gif", "png": "image/png", "jpg": "image/jpeg", "jpeg": "image/jpeg"}.get(
            name.rsplit(".", 1)[-1].lower(), "application/octet-stream")
        parts.append(f'--{boundary}\r\nContent-Disposition: form-data; name="files[{k}]"; filename="{name}"\r\n'
                     f"Content-Type: {ctype}\r\n\r\n".encode() + data + b"\r\n")
    parts.append(f"--{boundary}--\r\n".encode())
    return b"".join(parts), f"multipart/form-data; boundary={boundary}"


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--tag")
    ap.add_argument("--headline")
    ap.add_argument("--dry-run", action="store_true")
    ap.add_argument("--force", action="store_true")
    ap.add_argument("--description", default=str(REPO / "docs/workshop/description.txt"))
    ap.add_argument("--no-cards", action="store_true")
    ap.add_argument("--force-cards", action="store_true")
    ap.add_argument("--seed-cards", action="store_true")
    a = ap.parse_args()

    cards = page_cards(a.description) if Path(a.description).is_file() else []
    if a.seed_cards:
        urls = [u for _, us in cards for u in us]
        CONF.mkdir(parents=True, exist_ok=True)
        with CARDS_SENT.open("a") as f:
            f.writelines(u + "\n" for u in urls if u not in sent_cards())
        print(f"discord: recorded {len(cards)} New! card(s) as posted: {', '.join(t for t, _ in cards) or '-'}")
        return 0
    if not a.tag:
        ap.error("--tag is required")

    rel = json.loads(subprocess.check_output(
        ["gh", "release", "view", a.tag, "--json", "name,url,body,publishedAt"], text=True))
    headline = a.headline or rel["name"]
    body = rel["body"]
    # release.sh writes "Prebuilt ... (Build 42.x). <extra notes> Install with ... sha256 <hex>"
    m = re.search(r"^Prebuilt .*?\)\.\s*(.*?)\s*Install with ", body, re.S)
    notes = m.group(1).strip() if m else ""
    if len(notes) > 3800:
        notes = notes[:3800].rsplit(" ", 1)[0] + " …"

    desc = (notes + "\n\n" if notes else "") + (
        f"**Install:** Steam Workshop ([subscribe]({WORKSHOP})) or `install.ps1` / `install.sh` "
        f"from the [GitHub release]({rel['url']}).")
    embeds = [{
        "title": rel["name"],
        "url": rel["url"],
        "description": desc,
        "color": 0x4C8C2B,
        "footer": {"text": a.tag},
        "timestamp": rel["publishedAt"],
    }]

    seen = sent_cards()
    new = [] if a.no_cards else [c for c in cards if a.force_cards or not all(u in seen for u in c[1])]
    files = []
    if new:
        for title, done in card_files(new[:9], LIMIT):
            files.extend(done)
            # one embed per card with its first still image as the picture; a card video (Discord embeds cannot hold
            # one) and further images show as attachments under the post
            card = {"title": f"New! {title}", "url": WORKSHOP, "color": 0x4C8C2B}
            still = next((n for n, _ in done if not n.endswith(".mp4")), None)
            if still:
                card["image"] = {"url": f"attachment://{still}"}
            embeds.append(card)
    payload = {
        "username": "PZ_Optimization",
        "content": f"**New release: {headline}**",
        "embeds": embeds,
        "attachments": [{"id": k, "filename": n} for k, (n, _) in enumerate(files)],
        "allowed_mentions": {"parse": []},
    }
    if a.dry_run:
        print(json.dumps(payload, indent=2, ensure_ascii=False))
        if files:
            d = Path(tempfile.mkdtemp(prefix="pzopt-discord-cards-"))
            for n, data in files:
                (d / n).write_bytes(data)
            print(f"New! card attachments ({sum(len(x) for _, x in files)} bytes) written to {d}: "
                  + ", ".join(f"{n} {len(x)}" for n, x in files))
        else:
            print("no New! card to attach" + (" (--no-cards)" if a.no_cards else ""))
        return 0

    sent = CONF / "discord-announced.txt"
    if not a.force and sent.exists() and a.tag in sent.read_text().split():
        print(f"discord: {a.tag} was already announced (--force to post again)")
        return 0
    url = webhook()
    if not re.match(r"^https://(?:\w+\.)?discord(?:app)?\.com/api/webhooks/", url):
        print("discord: no webhook URL (set PZOPT_DISCORD_WEBHOOK or ~/.config/pzopt/discord-webhook); "
              "an invite link (discord.gg/...) cannot post", file=sys.stderr)
        return 2
    if files:
        data, ctype = multipart(payload, files)
    else:
        data, ctype = json.dumps(payload).encode(), "application/json"
    req = urllib.request.Request(url + "?wait=true", data=data, method="POST", headers={
        "Content-Type": ctype,
        # Discord's edge refuses the default Python-urllib agent
        "User-Agent": UA})
    try:
        with urllib.request.urlopen(req, timeout=60) as r:
            msg = json.load(r)
    except urllib.error.HTTPError as e:
        print(f"discord: post FAILED: HTTP {e.code} {e.read().decode(errors='replace')[:300]}", file=sys.stderr)
        return 3
    CONF.mkdir(parents=True, exist_ok=True)
    with sent.open("a") as f:
        f.write(a.tag + "\n")
    if new:
        with CARDS_SENT.open("a") as f:
            f.writelines(u + "\n" for _, us in new for u in us if u not in seen)
    print(f"discord: announced {a.tag} (message {msg.get('id')} in channel {msg.get('channel_id')})"
          + (f" with New! card(s): {', '.join(t for t, _ in new)}" if new else ""))
    return 0


if __name__ == "__main__":
    sys.exit(main())
