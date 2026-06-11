#!/usr/bin/env python3
"""
Banner style analyzer.

Reads bible/refs/banner.png, extracts dominant color palette via PIL, then
uses Gemini Vision (gemini-2.5-flash) to do a structured style analysis.
Output is a JSON report + a human-readable markdown with concrete
bible.yml edit suggestions.

Usage:
  pip install google-genai Pillow numpy
  export GEMINI_API_KEY=...
  python analyze-banner.py
  python analyze-banner.py --banner /path/to/banner.png   # custom path
  python analyze-banner.py --skip-vision                  # palette only
"""
from __future__ import annotations

import argparse
import collections
import json
import os
import sys
from pathlib import Path

try:
    from PIL import Image
except ImportError:
    sys.exit("Missing dep — run:  pip install Pillow")

ROOT = Path(__file__).resolve().parents[2]
DEFAULT_BANNER = ROOT / "bible" / "refs" / "banner.png"
REPORT_DIR = ROOT / "bible" / "refs" / "analysis"


# ─────────────────────────────────────────────────────────────────────
# 1) Color palette extraction (no API call, fully local)
# ─────────────────────────────────────────────────────────────────────

def extract_palette(img_path: Path, n: int = 8) -> list[dict]:
    """Returns top N colors by frequency, quantized into 8-bit-per-channel
       buckets, normalised to hex + RGB + share."""
    img = Image.open(img_path).convert("RGB")
    # Downsample for speed.
    img.thumbnail((400, 400))
    # Quantize to ~256 colors with median-cut.
    quantized = img.quantize(colors=64, method=Image.Quantize.MEDIANCUT)
    palette = quantized.getpalette()
    counts = collections.Counter(quantized.getdata())
    total = sum(counts.values())
    top = counts.most_common(n)
    out = []
    for idx, c in top:
        r, g, b = palette[idx * 3], palette[idx * 3 + 1], palette[idx * 3 + 2]
        out.append({
            "hex": "#{:02X}{:02X}{:02X}".format(r, g, b),
            "rgb": [r, g, b],
            "share": round(c / total * 100, 2),
        })
    return out


# ─────────────────────────────────────────────────────────────────────
# 2) Gemini Vision structured analysis
# ─────────────────────────────────────────────────────────────────────

VISION_PROMPT = """
Analyze this YouTube channel banner for "Tiny Chicken World", a kids
animation channel. Return ONLY JSON matching this schema (no prose):

{
  "art_style": "string — overall art style in 1 sentence",
  "rendering": "string — 2D flat, watercolor, Pixar 3D, painterly, etc",
  "color_mood": "string — overall color mood (warm/cool, saturated/muted)",
  "lighting": "string — lighting style (golden hour, soft diffuse, etc)",
  "linework": "string — line weight, hard outlines or soft",
  "characters_visible": "string — describe the chickens shown",
  "character_design": "string — eye style, body proportions, accessories",
  "backgrounds": "string — environment style, complexity",
  "typography": "string — if any text/logo visible, font style",
  "composition": "string — layout, focal points",
  "mood_keywords": ["string", "string", "..."],
  "differences_to_watch": "string — what to specifically match in scene art",
  "concrete_visualStyle_suggestion": "string — a 4-5 sentence description that could be pasted into bible.yml visualStyle.description"
}

Be specific. The downstream pipeline will use this to align scene
generation with the banner.
"""


def gemini_analyze(img_path: Path) -> dict | None:
    try:
        from google import genai
        from google.genai import types
    except ImportError:
        print("google-genai not installed — skipping vision analysis")
        return None
    key = os.environ.get("GEMINI_API_KEY") or os.environ.get("GOOGLE_API_KEY")
    if not key:
        print("GEMINI_API_KEY not set — skipping vision analysis")
        return None

    client = genai.Client(api_key=key)
    image_bytes = img_path.read_bytes()
    parts = [
        types.Part.from_bytes(data=image_bytes, mime_type="image/png"),
        VISION_PROMPT,
    ]
    resp = client.models.generate_content(
        model="gemini-2.5-flash",
        contents=parts,
        config=types.GenerateContentConfig(
            response_modalities=["TEXT"],
            response_mime_type="application/json",
        ),
    )
    text = resp.text
    try:
        return json.loads(text)
    except json.JSONDecodeError:
        # Strip Markdown code-block fencing if present.
        stripped = text.strip()
        if stripped.startswith("```"):
            stripped = stripped.split("\n", 1)[1]
            stripped = stripped.rsplit("```", 1)[0]
        try:
            return json.loads(stripped)
        except Exception:
            print("Gemini response was not JSON:\n", text)
            return None


# ─────────────────────────────────────────────────────────────────────
# 3) Markdown report
# ─────────────────────────────────────────────────────────────────────

def render_report(palette: list[dict], vision: dict | None) -> str:
    md = ["# Banner Style Analysis\n",
          f"_Banner file: `bible/refs/banner.png`_\n"]

    md.append("## Dominant color palette\n")
    md.append("| Hex | RGB | Share | Sample |")
    md.append("|---|---|---|---|")
    for c in palette:
        sample = f"![](https://placehold.co/40x20/{c['hex'].lstrip('#')}/{c['hex'].lstrip('#')}.png)"
        md.append(f"| `{c['hex']}` | {c['rgb']} | {c['share']}%% | {sample} |")
    md.append("")

    if vision:
        md.append("## Visual style (Gemini analysis)\n")
        for k, v in vision.items():
            if isinstance(v, list):
                v = ", ".join(v)
            md.append(f"**{k.replace('_', ' ').title()}** — {v}\n")

        md.append("\n## Suggested bible.yml edits\n")
        md.append("Replace the `visualStyle.description` and `palette` blocks with:\n")
        md.append("```yaml")
        md.append("visualStyle:")
        md.append("  description: >")
        suggestion = vision.get("concrete_visualStyle_suggestion", "")
        for line in suggestion.split(". "):
            line = line.strip()
            if line and not line.endswith("."):
                line += "."
            if line:
                md.append("    " + line)
        md.append("  palette:")
        for c in palette[:6]:
            md.append(f'    - "{c["hex"]}"   # {c["share"]}%%')
        md.append("```")

    md.append("\n## Next steps\n")
    md.append("1. Review the suggested edits above.")
    md.append("2. Copy/paste into `bible/channel.yml`.")
    md.append("3. Rebuild image-service: `docker compose build image-service && docker compose up -d image-service`.")
    md.append("4. Generate a new test video to verify the visual alignment.\n")
    return "\n".join(md)


# ─────────────────────────────────────────────────────────────────────
# Main
# ─────────────────────────────────────────────────────────────────────

def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--banner", type=Path, default=DEFAULT_BANNER)
    ap.add_argument("--skip-vision", action="store_true",
                    help="Only extract the palette, skip the Gemini API call")
    ap.add_argument("--out-dir", type=Path, default=REPORT_DIR)
    args = ap.parse_args()

    if not args.banner.exists():
        sys.exit(f"Banner not found at {args.banner}\n"
                 "Drop a PNG/JPG screenshot of your YouTube channel banner there.")

    args.out_dir.mkdir(parents=True, exist_ok=True)

    print(f"Reading banner: {args.banner}")
    palette = extract_palette(args.banner)
    print(f"Top {len(palette)} colors:")
    for c in palette:
        print(f"  {c['hex']}  {c['share']}%%")

    vision = None
    if not args.skip_vision:
        print("\nCalling Gemini Vision for style analysis...")
        vision = gemini_analyze(args.banner)
        if vision:
            print("  OK")

    report_md = render_report(palette, vision)
    md_path = args.out_dir / "banner-report.md"
    md_path.write_text(report_md, encoding="utf-8")

    json_path = args.out_dir / "banner-report.json"
    json_path.write_text(json.dumps({
        "palette": palette,
        "vision": vision,
    }, indent=2), encoding="utf-8")

    print(f"\nReport written to:")
    print(f"  {md_path}")
    print(f"  {json_path}")
    if vision:
        print("\nNext: open banner-report.md, copy the suggested edits into bible/channel.yml")
    else:
        print("\nPalette extracted. Set GEMINI_API_KEY and rerun for full style analysis.")


if __name__ == "__main__":
    main()
