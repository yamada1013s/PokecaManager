#!/usr/bin/env python3
"""Discover card IDs exposed by the official Japanese Pokémon Card search API.

This is a discovery stage only. It deliberately does not download individual
card pages or generate the production card master until the listing response
has been inspected and its fields have been verified.
"""
from __future__ import annotations

import argparse
import csv
import json
import random
import sys
import time
from pathlib import Path

import requests

API_URL = "https://www.pokemon-card.com/card-search/resultAPI.php"
USER_AGENT = "PokecaManager-card-master-research/1.0 (manual research run)"


def request_page(session: requests.Session, page: int, delay: float, retries: int) -> dict:
    if delay:
        time.sleep(delay + random.uniform(0, min(0.5, delay * 0.1)))
    params = {
        "keyword": "",
        "regulation_sidebar_form": "all",
        "page": page,
    }
    last_error = None
    for attempt in range(retries + 1):
        try:
            response = session.get(API_URL, params=params, timeout=(15, 45))
            if response.status_code in (403, 429):
                retry_after = response.headers.get("Retry-After")
                wait = max(60, int(retry_after)) if retry_after and retry_after.isdigit() else min(900, 60 * (attempt + 1))
                raise RuntimeError(f"HTTP {response.status_code}; stopping safely (Retry-After={retry_after!r}, suggested_wait={wait}s)")
            response.raise_for_status()
            data = response.json()
            if not isinstance(data, dict) or data.get("result") != 1:
                raise ValueError(f"Unexpected API response for page {page}: {str(data)[:500]}")
            return data
        except RuntimeError:
            raise
        except (requests.RequestException, ValueError) as exc:
            last_error = exc
            if attempt >= retries:
                break
            time.sleep(min(60, 5 * (2 ** attempt)))
    raise RuntimeError(f"Page {page} failed after retries: {last_error}")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output-dir", default="build/official-jp-discovery")
    parser.add_argument("--delay", type=float, default=2.0, help="minimum seconds between requests")
    parser.add_argument("--retries", type=int, default=3)
    parser.add_argument("--max-pages", type=int, default=0, help="0 means all pages; use 1 for a safe schema test")
    args = parser.parse_args()

    if args.delay < 2.0:
        parser.error("--delay must be at least 2 seconds")
    if args.retries < 0:
        parser.error("--retries cannot be negative")

    out = Path(args.output_dir)
    raw_dir = out / "raw_pages"
    raw_dir.mkdir(parents=True, exist_ok=True)
    session = requests.Session()
    session.headers.update({"User-Agent": USER_AGENT, "Accept": "application/json"})

    first = request_page(session, 1, args.delay, args.retries)
    max_page = int(first.get("maxPage", 1))
    hit_count = first.get("hitCnt")
    target_pages = min(max_page, args.max_pages) if args.max_pages else max_page
    pages = [first]
    (raw_dir / "page-0001.json").write_text(json.dumps(first, ensure_ascii=False, indent=2), encoding="utf-8")

    for page in range(2, target_pages + 1):
        data = request_page(session, page, args.delay, args.retries)
        pages.append(data)
        (raw_dir / f"page-{page:04d}.json").write_text(json.dumps(data, ensure_ascii=False, indent=2), encoding="utf-8")

    ids: list[str] = []
    malformed: list[dict] = []
    for page_number, data in enumerate(pages, start=1):
        entries = data.get("cardList")
        if not isinstance(entries, list):
            malformed.append({"page": page_number, "reason": "cardList is not a list"})
            continue
        for row_index, entry in enumerate(entries):
            if not isinstance(entry, dict):
                malformed.append({"page": page_number, "row": row_index, "reason": "entry is not an object"})
                continue
            card_id = str(entry.get("cardID", "")).strip()
            if card_id.isdigit():
                ids.append(card_id)
            else:
                malformed.append({"page": page_number, "row": row_index, "reason": "missing/non-numeric cardID", "entry": entry})

    unique_ids = sorted(set(ids), key=int)
    with (out / "official_card_ids.csv").open("w", newline="", encoding="utf-8-sig") as handle:
        writer = csv.writer(handle)
        writer.writerow(["official_card_id"])
        writer.writerows([[value] for value in unique_ids])
    summary = {
        "source": API_URL,
        "scope": "IDs listed by the official Japanese card-search API; not a complete historical master",
        "hitCnt_reported": hit_count,
        "maxPage_reported": max_page,
        "pages_fetched": len(pages),
        "pages_targeted": target_pages,
        "card_ids_rows": len(ids),
        "unique_card_ids": len(unique_ids),
        "duplicate_id_rows": len(ids) - len(unique_ids),
        "malformed_entries": len(malformed),
        "complete_listing_fetched": target_pages == max_page,
        "note": "Raw API pages are retained for schema review. No card detail pages were fetched.",
    }
    (out / "discovery_summary.json").write_text(json.dumps(summary, ensure_ascii=False, indent=2), encoding="utf-8")
    (out / "malformed_entries.json").write_text(json.dumps(malformed, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps(summary, ensure_ascii=False, indent=2))
    if malformed:
        print("WARNING: malformed entries were found; inspect malformed_entries.json", file=sys.stderr)
    return 0 if pages else 1


if __name__ == "__main__":
    raise SystemExit(main())
