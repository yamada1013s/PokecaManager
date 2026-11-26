# Official Japanese card detail extraction pilot

This is a bounded validation run, not the production card-master rebuild.

## What it does

- Downloads the already completed `official-jp-card-discovery` artifact.
- Selects 20-50 IDs spread across the full discovered ID list (recommended: 20).
- Fetches detail pages sequentially, with at least 3.5 seconds between requests plus jitter.
- Saves raw HTML and three reviewable CSVs:
  - `cards_pilot.csv`: one row per official card ID
  - `card_products_pilot.csv`: zero-to-many product/page links per card ID
  - `needs_review_pilot.csv`: missing or ambiguous fields requiring human review
- Saves `pilot_summary.json`.

## Safety limits

- No concurrent requests.
- Minimum 3 seconds between requests (workflow uses 3.5 seconds plus jitter).
- Stops on HTTP 403 or 429.
- No production CSV, existing master, Android assets, or app data is modified.
- The image folder code is explicitly a candidate only; it is not treated as a validated set ID.
- Product links are kept as a separate one-to-many relationship. They are not flattened into one set/card row.

## Run

1. Open the repository's Actions tab.
2. Select **Pilot official Japanese card detail extraction**.
3. Enter the run ID of the successful **Discover official Japanese card IDs** run.
4. Keep `max_cards=20` for the first pilot.
5. Run the workflow and download the `official-jp-detail-pilot` artifact.

Review the three CSVs and summary before considering any larger crawl. A successful pilot does not establish completeness of the official site for all historical or promotional cards.
