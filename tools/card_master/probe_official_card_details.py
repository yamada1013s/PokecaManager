#!/usr/bin/env python3
"""Probe official Japanese card detail pages before building a bulk scraper."""
from __future__ import annotations

import argparse
import csv
import json
import random
import re
import time
from pathlib import Path
from urllib.parse import urljoin

import requests
from bs4 import BeautifulSoup

BASE_URL = "https://www.pokemon-card.com"
DETAIL_URL = BASE_URL + "/card-search/details.php/card/{card_id}/regu/all"
USER_AGENT = "PokecaManager-card-master-research/1.0 (manual research run)"


def clean(value: str) -> str:
    return re.sub(r"\s+", " ", value or "").strip()


def inspect_page(card_id: str, html: str) -> dict:
    soup = BeautifulSoup(html, "html.parser")
    selectors = [".TopInfo", ".LeftBox", ".RightBox", ".cardName", ".CardName",
                 "[class*='Name']", "[class*='Number']", "[class*='Info']",
                 "[class*='Detail']", "[id*='card']", "[data-card-id]"]
    blocks = {}
    for selector in selectors:
        found = soup.select(selector)
        if found:
            blocks[selector] = [
                {
                    "text": clean(node.get_text(" ", strip=True))[:1600],
                    "id": node.get("id"),
                    "class": node.get("class"),
                    "attrs": {k: v for k, v in node.attrs.items()
                              if k.startswith("data-") or k in ("title", "alt", "href")}
                }
                for node in found[:12]
            ]

    text_lines = [clean(line) for line in soup.get_text("\n").splitlines()]
    text_lines = [line for line in text_lines if line]
    scripts = []
    for script in soup.find_all("script"):
        script_text = script.string or script.get_text()
        if script_text and any(token in script_text.lower()
                               for token in ("card", "name", "number", "regulation", "pack")):
            scripts.append(clean(script_text)[:2500])

    card_name = clean(soup.find("h1").get_text(" ", strip=True)) if soup.find("h1") else ""
    left_box = soup.select_one(".LeftBox")
    left_box_text = clean(left_box.get_text(" ", strip=True)) if left_box else ""
    number_match = re.search(r"(?<!\d)(\d{1,3}\s*/\s*\d{1,3})(?!\d)", left_box_text)
    card_number = clean(number_match.group(1)) if number_match else None

    card_image = None
    for img in soup.find_all("img"):
        alt = clean(img.get("alt", ""))
        src = img.get("src") or img.get("data-src") or ""
        if src and ((card_name and alt == card_name) or "card_images/large/" in src):
            card_image = {
                "alt": alt,
                "src": src,
                "url": urljoin(BASE_URL, src),
                "class": img.get("class"),
            }
            break

    image_set_code = None
    if card_image:
        match = re.search(r"/card_images/large/([^/]+)/", card_image["src"])
        if match:
            image_set_code = match.group(1)

    rarity_images = []
    for img in soup.select("img[src*='/rarity/']"):
        src = img.get("src", "")
        rarity_images.append({
            "src": src,
            "url": urljoin(BASE_URL, src),
            "class": img.get("class"),
            "alt": img.get("alt", ""),
        })

    # Keep visible anchor labels and their destinations so product/set relationships
    # can be reviewed before deciding how to model reprints in the master.
    anchors = []
    for anchor in soup.find_all("a", href=True):
        label = clean(anchor.get_text(" ", strip=True))
        href = anchor.get("href", "")
        if label or href:
            parent = anchor.parent
            anchors.append({
                "text": label[:300],
                "href": href,
                "url": urljoin(BASE_URL, href),
                "class": anchor.get("class"),
                "parent_class": parent.get("class") if parent else None,
            })
        if len(anchors) >= 200:
            break

    return {
        "official_card_id": card_id,
        "url": DETAIL_URL.format(card_id=card_id),
        "card_name": card_name or None,
        "card_number": card_number,
        "left_box_text": left_box_text,
        "card_image": card_image,
        "image_set_code_candidate": image_set_code,
        "rarity_images": rarity_images,
        "product_and_page_links": anchors,
        "http_title": clean(soup.title.get_text(" ", strip=True)) if soup.title else "",
        "h1": [clean(x.get_text(" ", strip=True)) for x in soup.find_all("h1")[:10]],
        "h2": [clean(x.get_text(" ", strip=True)) for x in soup.find_all("h2")[:20]],
        "blocks": blocks,
        "visible_text": text_lines[:250],
        "scripts_with_card_terms": scripts[:20],
        "images": [
            {"alt": img.get("alt", ""), "src": img.get("src", ""),
             "data_src": img.get("data-src"), "class": img.get("class")}
            for img in soup.find_all("img")[:40]
        ],
        "meta": {
            x.get("property") or x.get("name"): x.get("content", "")
            for x in soup.find_all("meta")
            if (x.get("property") or x.get("name")) and x.get("content")
        },
        "html_bytes": len(html.encode("utf-8")),
    }


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--ids-csv", required=True)
    parser.add_argument("--output-dir", default="build/official-jp-detail-probe")
    parser.add_argument("--max-cards", type=int, default=1)
    parser.add_argument("--delay", type=float, default=3.0)
    args = parser.parse_args()
    if args.max_cards < 1 or args.max_cards > 5:
        parser.error("--max-cards must be between 1 and 5 for the schema probe")
    if args.delay < 3.0:
        parser.error("--delay must be at least 3 seconds for the detail-page probe")

    out = Path(args.output_dir)
    out.mkdir(parents=True, exist_ok=True)
    with Path(args.ids_csv).open(encoding="utf-8-sig", newline="") as handle:
        ids = [row["official_card_id"].strip() for row in csv.DictReader(handle)
               if row.get("official_card_id", "").strip().isdigit()]
    if not ids:
        raise SystemExit("No numeric official_card_id values found in the discovery CSV")

    if args.max_cards == 1:
        sample_ids = [ids[len(ids) // 2]]
    else:
        sample_indexes = sorted({round(i * (len(ids) - 1) / (args.max_cards - 1))
                                 for i in range(args.max_cards)})
        sample_ids = [ids[i] for i in sample_indexes]

    session = requests.Session()
    session.headers.update({"User-Agent": USER_AGENT, "Accept": "text/html,application/xhtml+xml"})
    results = []
    for index, card_id in enumerate(sample_ids):
        if index:
            time.sleep(args.delay + random.uniform(0, 0.5))
        url = DETAIL_URL.format(card_id=card_id)
        response = session.get(url, timeout=(15, 45))
        if response.status_code in (403, 429):
            raise SystemExit(f"HTTP {response.status_code}; stopped safely at card ID {card_id}")
        response.raise_for_status()
        response.encoding = response.apparent_encoding or response.encoding
        html = response.text
        (out / f"sample-{card_id}.html").write_text(html, encoding="utf-8")
        results.append(inspect_page(card_id, html))

    (out / "detail_probe.json").write_text(
        json.dumps(results, ensure_ascii=False, indent=2), encoding="utf-8"
    )
    print(json.dumps({
        "sample_count": len(results),
        "sampled_ids": sample_ids,
        "samples": results,
        "note": "Schema probe only. No bulk detail crawl and no production CSV were generated."
    }, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
