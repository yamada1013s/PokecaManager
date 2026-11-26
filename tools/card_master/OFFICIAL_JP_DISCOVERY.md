# Official Japanese card-source discovery

This workflow is a **discovery and schema-inspection stage**, not a production card-master builder.

## Why this exists

The existing CardCorpus export is useful as a cross-reference, but the previous run showed that its Japanese card-name and collector-number fields are not reliable enough to become the sole authority for the app's Japanese master.

The official Japanese Pokémon Card website exposes a search endpoint used by its own card-search pages:

- Endpoint: https://www.pokemon-card.com/card-search/resultAPI.php
- The current discovery script requests the endpoint's paginated card list and records the returned raw JSON pages.
- The script does not guess sequential card IDs.
- It does not fetch individual detail pages.
- It does not modify app assets or the existing CSV master.

## Conservative request policy

- Sequential requests only; no concurrency.
- Minimum 2-second delay between requests, with small jitter.
- HTTP 403/429 stops the run instead of attempting to push through the restriction.
- Transient network errors receive a small bounded retry count and exponential wait.
- Every fetched page is saved as raw JSON for inspection and restart/debugging.
- The default workflow input fetches **one page only**. Full listing discovery requires explicitly setting `max_pages=0` after the first page's schema and behavior have been reviewed.

## Run

1. Open GitHub Actions.
2. Select **Discover official Japanese card IDs**.
3. Run the workflow with `max_pages=1` (default).
4. Download the `official-jp-card-discovery` artifact.
5. Inspect `raw_pages/page-0001.json`, `discovery_summary.json`, and `official_card_ids.csv`.

Only after confirming the response schema, request behavior, and coverage should a full listing pass be considered.

## Important coverage limitation

The official site's searchable catalogue is not assumed to include every historical Japanese printing, promo distribution, or product-exclusive card. It is an official-source anchor for the records it exposes, not proof of a complete all-era card master. Historical gaps must be identified and filled from independently reviewable sources, with provenance retained per record.

## Output

- `raw_pages/page-NNNN.json`: unmodified API responses.
- `official_card_ids.csv`: unique numeric IDs exposed in the fetched pages.
- `discovery_summary.json`: reported totals, fetched page count, duplicate and malformed row counts.
- `malformed_entries.json`: entries that did not contain a numeric `cardID`.
