#!/usr/bin/env python3
"""Fetch a small, rate-limited pilot of official Japanese card detail pages."""
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
USER_AGENT = "PokecaManager-card-master-pilot/1.0 (careful sequential research)"
PRODUCT_PATH = re.compile(r"/(?:products?/|ex/|special/|event/)", re.I)
PRODUCT_LABEL = re.compile(r"(拡張パック|強化拡張パック|コンセプトパック|ハイクラスパック|スターター|デッキ|プロモ|キャンペーン|商品|セット|BOX|ボックス|パック)", re.I)


def clean(value: str) -> str:
    return re.sub(r"\\s+", " ", value or "").strip()


def parse_card(card_id: str, html: str) -> tuple[dict, list[dict], list[str]]:
    soup = BeautifulSoup(html, "html.parser")
    h1 = soup.find("h1")
    name = clean(h1.get_text(" ", strip=True)) if h1 else ""
    left = soup.select_one(".LeftBox")
    left_text = clean(left.get_text(" ", strip=True)) if left else ""
    number_match = re.search(r"(?<!\\d)(\\d{1,3}\\s*/\\s*\\d{1,3})(?!\\d)", left_text)
    number = clean(number_match.group(1)) if number_match else ""

    image = None
    for img in soup.find_all("img"):
        src = img.get("src") or img.get("data-src") or ""
        alt = clean(img.get("alt", ""))
        if src and ((name and alt == name) or "/card_images/large/" in src):
            image = urljoin(BASE_URL, src)
            break
    image_path = image or ""
    code_match = re.search(r"/card_images/large/([^/]+)/", image_path)
    set_code = code_match.group(1) if code_match else ""

    rarity = ""
    for img in soup.select("img[src*='/rarity/']"):
        src = img.get("src", "")
        match = re.search(r"/rarity/([^/?#]+)", src)
        if match:
            rarity = match.group(1).rsplit(".", 1)[0]
            break

    products = []
    seen = set()
    for a in soup.find_all("a", href=True):
        label = clean(a.get_text(" ", strip=True))
        href = a.get("href", "")
        url = urljoin(BASE_URL, href)
        if not label or not (PRODUCT_PATH.search(href) or PRODUCT_LABEL.search(label)):
            continue
        key = (label, url)
        if key in seen:
            continue
        seen.add(key)
        products.append({
            "official_card_id": card_id,
            "product_name": label,
            "product_url": url,
            "link_path": href,
        })

    card = {
        "official_card_id": card_id,
        "card_name_ja": name,
        "card_number": number,
        "image_url": image or "",
        "image_set_code_candidate": set_code,
        "rarity_code_candidate": rarity,
        "official_detail_url": DETAIL_URL.format(card_id=card_id),
        "left_box_text": left_text,
    }
    review = []
    if not name:
        review.append("card_name_missing")
    if not number:
        review.append("card_number_missing_or_exception")
    if not image:
        review.append("card_image_missing")
    if not set_code:
        review.append("image_set_code_missing")
    if len(products) == 0:
        review.append("product_link_not_found")
    elif len(products) > 1:
        review.append("multiple_product_links_review")
    return card, products, review


def write_csv(path: Path, rows: list[dict], fields: list[str]) -> None:
    with path.open("w", encoding="utf-8-sig", newline="") as f:
        writer = csv.DictWriter(f, fieldnames=fields, extrasaction="ignore")
        writer.writeheader()
        writer.writerows(rows)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--ids-csv", required=True)
    parser.add_argument("--output-dir", default="build/official-jp-detail-pilot")
    parser.add_argument("--max-cards", type=int, default=20)
    parser.add_argument("--delay", type=float, default=3.5)
    args = parser.parse_args()
    if not 20 <= args.max_cards <= 50:
        parser.error("--max-cards must be between 20 and 50")
    if args.delay < 3.0:
        parser.error("--delay must be at least 3 seconds")

    out = Path(args.output_dir)
    raw_dir = out / "raw_html"
    raw_dir.mkdir(parents=True, exist_ok=True)
    with Path(args.ids_csv).open(encoding="utf-8-sig", newline="") as f:
        ids = [row["official_card_id"].strip() for row in csv.DictReader(f)
               if row.get("official_card_id", "").strip().isdigit()]
    if len(ids) < args.max_cards:
        raise SystemExit(f"Need at least {args.max_cards} IDs; found {len(ids)}")

    indexes = sorted({round(i * (len(ids) - 1) / (args.max_cards - 1))
                      for i in range(args.max_cards)})
    sample_ids = [ids[i] for i in indexes]
    session = requests.Session()
    session.headers.update({"User-Agent": USER_AGENT,
                            "Accept": "text/html,application/xhtml+xml"})
    cards, products, reviews = [], [], []
    errors = []
    for index, card_id in enumerate(sample_ids):
        if index:
            time.sleep(args.delay + random.uniform(0, 0.75))
        url = DETAIL_URL.format(card_id=card_id)
        try:
            response = session.get(url, timeout=(15, 45))
        except requests.RequestException as exc:
            errors.append({"official_card_id": card_id, "error": str(exc)})
            break
        if response.status_code in (403, 429):
            errors.append({"official_card_id": card_id,
                           "error": f"HTTP {response.status_code}; stopped safely"})
            break
        response.raise_for_status()
        response.encoding = response.apparent_encoding or response.encoding
        html = response.text
        (raw_dir / f"{card_id}.html").write_text(html, encoding="utf-8")
        card, card_products, flags = parse_card(card_id, html)
        cards.append(card)
        products.extend(card_products)
        if flags:
            reviews.append({
                "official_card_id": card_id,
                "card_name_ja": card["card_name_ja"],
                "review_flags": ";".join(flags),
                "product_link_count": len(card_products),
                "official_detail_url": card["official_detail_url"],
            })

    write_csv(out / "cards_pilot.csv", cards, [
        "official_card_id", "card_name_ja", "card_number", "image_url",
        "image_set_code_candidate", "rarity_code_candidate",
        "official_detail_url", "left_box_text"])
    write_csv(out / "card_products_pilot.csv", products, [
        "official_card_id", "product_name", "product_url", "link_path"])
    write_csv(out / "needs_review_pilot.csv", reviews, [
        "official_card_id", "card_name_ja", "review_flags",
        "product_link_count", "official_detail_url"])
    summary = {
        "requested_count": len(sample_ids),
        "fetched_count": len(cards),
        "sampled_ids": sample_ids,
        "product_relationship_rows": len(products),
        "needs_review_rows": len(reviews),
        "errors": errors,
        "request_policy": "Sequential; >=3 seconds between requests; no concurrency; stop on HTTP 403/429",
        "scope_note": "Pilot only. No production master CSV or Android assets are created or modified.",
    }
    (out / "pilot_summary.json").write_text(
        json.dumps(summary, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps(summary, ensure_ascii=False, indent=2))
    return 1 if errors else 0


if __name__ == "__main__":
    raise SystemExit(main())
