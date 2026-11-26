#!/usr/bin/env python3
"""Probe the official Japanese card detail page before building a bulk scraper.

This deliberately fetches only a tiny sample and records HTML structure. It does
not generate or modify the production card master.
"""
from __future__ import annotations

import argparse
import csv
import json
import random
import re
import time
from pathlib import Path

import requests
from bs4 import BeautifulSoup

BASE_URL = "https://www.pokemon-card.com"
DETAIL_URL = BASE_URL + "/card-search/details.php/card/{card_id}/regu/all"
USER_AGENT = "PokecaManager-card-master-research/1.0 (manual research run)"


def clean(value: str) -> str:
    return re.sub(r"\\s+", " ", value or "").strip()


def inspect_page(card_id: str, html: str) -> dict:
    soup = BeautifulSoup(html, "html.parser")
    selectors = [".TopInfo", ".LeftBox", ".RightBox", ".cardName", ".CardName",
                 "[class*='Name']", "[class*='Number']", "[id*='card']"]
    blocks = {}
    for selector in selectors:
        found = soup.select(selector)
        if found:
            blocks[selector] = [clean(node.get_text(" ", strip=True))[:1200] for node in found[:8]]
    return {
        "official_card_id": card_id,
        "url": DETAIL_URL.format(card_id=card_id),
        "http_title": clean(soup.title.get_text(" ", strip=True)) if soup.title else "",
        "h1": [clean(x.get_text(" ", strip=True)) for x in soup.find_all("h1")[:10]],
        "h2": [clean(x.get_text(" ", strip=True)) for x in soup.find_all("h2")[:20]],
        "blocks": blocks,
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

    session = requests.Session()
    session.headers.update({"User-Agent": USER_AGENT, "Accept": "text/html,application/xhtml+xml"})
    results = []
    for index, card_id in enumerate(ids[:args.max_cards]):
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
        "samples": results,
        "note": "Schema probe only. No bulk detail crawl and no production CSV were generated."
    }, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
