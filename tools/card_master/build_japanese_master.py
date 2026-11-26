#!/usr/bin/env python3
"""Build the Japanese Pokémon card master from CardCorpus.

Usage:
  python tools/card_master/build_japanese_master.py
  python tools/card_master/build_japanese_master.py --output-dir ./build/card-master

The script does not modify collection data or run automatically in the app.
"""
from __future__ import annotations

import argparse
import csv
import json
import re
import sys
import time
import urllib.error
import urllib.request
from pathlib import Path
from typing import Any

BASE_URL = "https://pokemon.cardcorpus.com/v1"
USER_AGENT = "PokecaManager-card-master-builder/1.0 (data attribution: CardCorpus)"
CARD_FIELDS = ["set_id", "set_name", "number", "card_name", "card_id"]
SET_FIELDS = ["set_id", "set_name", "official", "total", "release_date"]
CHECK_FIELDS = ["issue_type", "set_id", "set_name", "number", "card_id", "card_name", "details"]
JAPANESE_RE = re.compile(r"[ぁ-んァ-ヶ一-龯々ー]")


def fetch_json(url: str, cache_file: Path, retries: int = 4) -> dict[str, Any]:
    if cache_file.exists():
        return json.loads(cache_file.read_text(encoding="utf-8"))
    cache_file.parent.mkdir(parents=True, exist_ok=True)
    last_error: Exception | None = None
    for attempt in range(retries):
        try:
            req = urllib.request.Request(url, headers={"User-Agent": USER_AGENT})
            with urllib.request.urlopen(req, timeout=45) as response:
                data = response.read()
            parsed = json.loads(data.decode("utf-8-sig"))
            cache_file.write_text(json.dumps(parsed, ensure_ascii=False), encoding="utf-8")
            return parsed
        except urllib.error.HTTPError as exc:
            last_error = exc
            # Respect Retry-After when the server provides it. For rate limiting,
            # wait at least 30 seconds before retrying rather than hammering the API.
            retry_after = exc.headers.get("Retry-After", "")
            try:
                server_wait = max(0, int(retry_after))
            except (TypeError, ValueError):
                server_wait = 0
            backoff = min(2 ** (attempt + 1), 30)
            if exc.code == 429:
                backoff = max(backoff, 30)
            time.sleep(max(server_wait, backoff))
        except (OSError, urllib.error.URLError, json.JSONDecodeError) as exc:
            last_error = exc
            time.sleep(min(2 ** (attempt + 1), 30))
    raise RuntimeError(f"Failed to fetch {url}: {last_error}")


def write_csv(path: Path, fields: list[str], rows: list[dict[str, Any]]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", encoding="utf-8-sig", newline="") as stream:
        writer = csv.DictWriter(stream, fieldnames=fields, extrasaction="ignore")
        writer.writeheader()
        writer.writerows(rows)


def main() -> int:
    parser = argparse.ArgumentParser(description="Generate Japanese Pokémon card master CSV files.")
    parser.add_argument("--output-dir", default="build/card-master", help="Directory for generated CSV files (kept separate from app assets until reviewed)")
    parser.add_argument("--cache-dir", default=".cache/cardcorpus-ja", help="Downloaded JSON cache")
    parser.add_argument("--delay", type=float, default=2.0, help="Minimum delay in seconds between uncached set downloads (default: 2)")
    args = parser.parse_args()

    output_dir = Path(args.output_dir)
    cache_dir = Path(args.cache_dir)
    index = fetch_json(f"{BASE_URL}/sets/japanese.json", cache_dir / "sets-japanese.json")
    sets = index.get("sets", [])
    if not sets:
        raise RuntimeError("CardCorpus returned no Japanese sets; refusing to write empty master files.")

    card_rows: list[dict[str, Any]] = []
    set_rows: list[dict[str, Any]] = []
    checks: list[dict[str, Any]] = []
    seen_card_ids: set[str] = set()
    set_file_errors = 0

    # Keep a pause after the catalog-index request too, before requesting set details.
    if args.delay > 0:
        time.sleep(args.delay)

    for position, set_info in enumerate(sets, start=1):
        set_id = str(set_info.get("id") or "")
        if not set_id.startswith("japanese/"):
            checks.append({"issue_type": "unexpected_set_id", "set_id": set_id,
                           "set_name": set_info.get("name", ""), "details": "Set ID is not in the Japanese catalog."})
            continue

        safe_name = set_id.replace("/", "__")
        detail_path = cache_dir / "sets" / f"{safe_name}.json"
        detail_url = f"{BASE_URL}/sets/{set_id}.json"
        was_cached = detail_path.exists()
        try:
            detail = fetch_json(detail_url, detail_path)
        except Exception as exc:
            set_file_errors += 1
            checks.append({"issue_type": "set_download_error", "set_id": set_id,
                           "set_name": set_info.get("name", ""), "details": str(exc)})
            continue

        set_name = str(detail.get("name") or set_info.get("name") or "")
        release_date = (detail.get("released") or {}).get("date", "")
        actual_total = detail.get("actualTotal")
        printed_total = detail.get("printedTotal")
        card_count = detail.get("cardCount", len(detail.get("cards", [])))
        official = printed_total if printed_total is not None else (
            actual_total if actual_total is not None else card_count
        )
        total = actual_total if actual_total is not None else card_count
        set_rows.append({
            "set_id": set_id,
            "set_name": set_name,
            "official": official if official is not None else "",
            "total": total if total is not None else "",
            "release_date": release_date,
        })

        cards = detail.get("cards", [])
        if len(cards) != card_count:
            checks.append({"issue_type": "set_card_count_mismatch", "set_id": set_id,
                           "set_name": set_name, "details": f"index cardCount={card_count}, downloaded={len(cards)}"})

        for card in cards:
            card_key = f"{set_id}::{card.get('id', '')}"
            number = str((card.get("number") or {}).get("short") or "")
            card_name = str(card.get("name") or "")
            if card_key in seen_card_ids:
                checks.append({"issue_type": "duplicate_card_id", "set_id": set_id,
                               "set_name": set_name, "number": number, "card_id": card_key,
                               "card_name": card_name, "details": "Duplicate composite card ID."})
            seen_card_ids.add(card_key)
            if not card_name:
                checks.append({"issue_type": "missing_card_name", "set_id": set_id,
                               "set_name": set_name, "number": number, "card_id": card_key,
                               "details": "Card name is empty."})
            elif not JAPANESE_RE.search(card_name):
                checks.append({"issue_type": "name_needs_review", "set_id": set_id,
                               "set_name": set_name, "number": number, "card_id": card_key,
                               "card_name": card_name,
                               "details": "Name has no Japanese characters; verify printed Japanese name."})
            if not number:
                checks.append({"issue_type": "missing_card_number", "set_id": set_id,
                               "set_name": set_name, "card_id": card_key, "card_name": card_name,
                               "details": "No short card number supplied by source."})
            card_rows.append({
                "set_id": set_id,
                "set_name": set_name,
                "number": number,
                "card_name": card_name,
                "card_id": card_key,
            })

        if position % 25 == 0:
            print(f"Processed {position}/{len(sets)} sets; {len(card_rows)} cards so far.", flush=True)
        if not was_cached and args.delay > 0:
            time.sleep(args.delay)

    # Detect duplicate printed numbers inside one set; these are review candidates, not auto-deleted.
    number_groups: dict[tuple[str, str], list[dict[str, Any]]] = {}
    for row in card_rows:
        if row["number"]:
            number_groups.setdefault((row["set_id"], row["number"]), []).append(row)
    for (set_id, number), rows in number_groups.items():
        if len(rows) > 1:
            for row in rows:
                checks.append({"issue_type": "duplicate_set_number", **row,
                               "details": f"{len(rows)} cards share this number in the same set; inspect manually."})

    if set_file_errors:
        print(f"WARNING: {set_file_errors} set files failed; output is incomplete.", file=sys.stderr)
    if set_file_errors:
        raise RuntimeError(f"{set_file_errors} set files failed. No CSV files were written; fix the network/source issue and rerun.")
    if not card_rows:
        raise RuntimeError("No cards were generated; refusing to write empty master files.")

    write_csv(output_dir / "cards_ja_final.csv", CARD_FIELDS, card_rows)
    write_csv(output_dir / "sets_info.csv", SET_FIELDS, set_rows)
    write_csv(output_dir / "data_check.csv", CHECK_FIELDS, checks)

    print(f"Generated {len(card_rows)} cards across {len(set_rows)} sets.")
    print(f"Review items: {len(checks)}. Set download errors: {set_file_errors}.")
    print(f"Output: {output_dir.resolve()}")
    return 2 if set_file_errors else 0


if __name__ == "__main__":
    raise SystemExit(main())
