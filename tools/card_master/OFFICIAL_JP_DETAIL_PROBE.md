# Official Japanese card detail schema probe

This is a deliberately limited schema-inspection stage. It downloads the
previously validated `official-jp-card-discovery` artifact and fetches only
1-5 official detail pages. It does not perform a bulk crawl or write the
production card master.

## Why a probe comes first

The official search API provides numeric card IDs, but detail-page HTML must
be inspected before relying on selectors for Japanese card name, collector
number, set name, and release metadata. The probe stores one raw HTML sample
and prints a compact structural preview in the Actions log.

## Safe request policy

- 1-5 sample cards only.
- Sequential requests, at least 3 seconds between requests.
- No concurrent detail-page requests.
- HTTP 403/429 stops immediately.
- No production CSV, app asset, or existing master is modified.

## Run

1. Open Actions and select **Probe official Japanese card detail schema**.
2. Run it with the run ID of the successful full discovery workflow.
3. Keep `max_cards=1` for the first probe.
4. Inspect the compact JSON preview in the workflow log. The artifact contains
   `detail_probe.json` and the raw `sample-<id>.html`.

The detail parser and any bulk collector should be implemented only after the
actual official HTML structure has been reviewed.
