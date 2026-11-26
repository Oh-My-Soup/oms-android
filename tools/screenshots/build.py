#!/usr/bin/env python3
"""Frames the raw captures into 1080x1920 store-style promo images.

    tools/screenshots/build.py               # every shot in SHOTS -> docs/screenshots/
    tools/screenshots/build.py 03-commands   # only the named shots
    tools/screenshots/build.py --keep-html   # also keep the filled frame pages (tools/screenshots/.html/) for preview

Needs ImageMagick (`magick`) and Chromium/Chrome on PATH (or CHROME=/path/to/browser).
Input: raw/<id>.png from capture.sh. Output: docs/screenshots/<id>.png (extras go to docs/screenshots/extras/).

Before framing, two device status icons the SystemUI demo mode does not hide (a Digital
Wellbeing glyph next to the clock and the silent-ringer bell) are painted over with the status
bar color next to them. The raw captures are left untouched.
"""

import html
import os
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent
RAW = ROOT / "raw"
FINAL = ROOT.parent.parent / "docs" / "screenshots"
TEMPLATE = ROOT / "frame.html"

# id, headline, subline, extra (extras go to docs/screenshots/extras/)
SHOTS = [
    (
        "01-transcript",
        "Your agent, in your pocket",
        "Follow the live transcript and every tool call while oms works on your computer.",
        False,
    ),
    (
        "02-steer",
        "Steer it mid-turn",
        "On a full-control link, a message sent while oms works steers the current turn.",
        False,
    ),
    (
        "03-commands",
        "Run slash commands",
        "Run host commands like /todo and /compact, with completions and output.",
        False,
    ),
    (
        "04-models",
        "Switch model and thinking",
        "Pick from your host's models and thinking levels for this session.",
        False,
    ),
    (
        "05-agents",
        "Keep an eye on subagents",
        "See what each one is doing, message it, kill it or revive it.",
        False,
    ),
    (
        "06-ask",
        "Answer when it asks",
        "Questions and tool approvals reach your phone, with a notification when you're in another app.",
        False,
    ),
    (
        "extra-approve",
        "Approve tool calls",
        "Read the exact command before it runs on your computer.",
        True,
    ),
    (
        "extra-home",
        "Pair with a QR code",
        "Run /collab in oms and scan the code. Saved hosts are one tap away.",
        True,
    ),
]

# Status icons to paint over, as (x1, y1, x2, y2) in the 1080x2400 capture, each with the point
# whose color fills it (the bare status bar just right of the icon).
STATUS_PATCHES = [((146, 26, 194, 106), (230, 66)), ((772, 26, 818, 106), (760, 66))]


def find_chrome() -> str:
    for name in (os.environ.get("CHROME"), "chromium", "chromium-browser", "google-chrome", "google-chrome-stable"):
        if name and shutil.which(name):
            return shutil.which(name)
    sys.exit("Chromium/Chrome not found; set CHROME=/path/to/browser")


def clean_status_bar(src: Path, dst: Path) -> None:
    args = ["magick", str(src)]
    for (x1, y1, x2, y2), (sx, sy) in STATUS_PATCHES:
        color = subprocess.run(
            ["magick", str(src), "-format", f"%[pixel:p{{{sx},{sy}}}]", "info:"],
            check=True,
            capture_output=True,
            text=True,
        ).stdout.strip()
        args += ["-fill", color, "-draw", f"rectangle {x1},{y1} {x2},{y2}"]
    subprocess.run(args + [str(dst)], check=True)


def render(chrome: str, page: Path, out: Path) -> None:
    out.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory() as profile:
        subprocess.run(
            [
                chrome,
                "--headless=new",
                "--disable-gpu",
                "--hide-scrollbars",
                "--no-first-run",
                "--no-default-browser-check",
                f"--user-data-dir={profile}",
                "--force-device-scale-factor=1",
                "--window-size=1080,1920",
                "--virtual-time-budget=2000",
                f"--screenshot={out}",
                page.as_uri(),
            ],
            check=True,
            capture_output=True,
        )
    size = subprocess.run(
        ["magick", "identify", "-format", "%wx%h", str(out)], check=True, capture_output=True, text=True
    ).stdout
    if size != "1080x1920":
        sys.exit(f"{out}: rendered {size}, expected 1080x1920")


def main() -> None:
    args = sys.argv[1:]
    keep_html = "--keep-html" in args
    wanted = {a for a in args if not a.startswith("--")}
    shots = [s for s in SHOTS if not wanted or s[0] in wanted]
    if wanted - {s[0] for s in shots}:
        sys.exit(f"unknown shots: {sorted(wanted - {s[0] for s in shots})}")

    chrome = find_chrome()
    template = TEMPLATE.read_text()
    work = Path(tempfile.mkdtemp(prefix="oms-promo-"))
    html_dir = ROOT / ".html"
    try:
        for shot_id, headline, subline, extra in shots:
            raw = RAW / f"{shot_id}.png"
            if not raw.exists():
                sys.exit(f"missing {raw}; run capture.sh first")
            clean = work / f"{shot_id}.png"
            clean_status_bar(raw, clean)
            page = work / f"{shot_id}.html"
            page.write_text(
                template.replace("{{headline}}", html.escape(headline))
                .replace("{{subline}}", html.escape(subline))
                .replace("{{screenshot}}", clean.as_uri())
            )
            out = (FINAL / "extras" if extra else FINAL) / f"{shot_id}.png"
            render(chrome, page, out)
            print(f"wrote {out.relative_to(ROOT)}")
            if keep_html:
                html_dir.mkdir(exist_ok=True)
                shutil.copy(clean, html_dir / clean.name)
                (html_dir / page.name).write_text(page.read_text().replace(clean.as_uri(), clean.name))
    finally:
        shutil.rmtree(work, ignore_errors=True)


if __name__ == "__main__":
    main()
