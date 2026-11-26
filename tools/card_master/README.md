# Japanese card master rebuild

This folder contains the reproducible builder for the Japanese card master used by PokecaManager.

## Source

- CardCorpus dataset: https://github.com/cardcorpus/pokemon-card-dataset
- Live Japanese set index: https://pokemon.cardcorpus.com/v1/sets/japanese.json
- Dataset snapshot date documented by the upstream project: 2026-11-21
- License: CC BY 4.0. Credit CardCorpus and link to the source repository when redistributing the derived data.

CardCorpus is a community-maintained dataset, not an official Pokémon Company database. Treat it as a broad primary candidate source, not as unquestionable ground truth. Names, numbers, set boundaries, promos, and historical releases still need verification against Japanese printed cards and official product information.

## Build

Run from the repository root with Python 3.10 or later:

```bash
python tools/card_master/build_japanese_master.py
```

The first run downloads the Japanese set index and each Japanese set detail into `.cache/cardcorpus-ja/`. Later runs reuse the cache. Delete that cache directory to force a fresh download.

The script writes:

- `app/src/main/assets/cards_ja_final.csv`
- `app/src/main/assets/sets_info.csv`
- `app/src/main/assets/data_check.csv`

It does not change collection records, app code, or the current master automatically. It writes generated CSVs only when at least one card has been retrieved.

## Review policy

`data_check.csv` is a review queue, not a list of confirmed errors. In particular:

- Names without Japanese characters may be legitimate exceptions or upstream fallback names.
- Duplicate numbers may be valid parallel products, promos, or source modeling details.
- Missing numbers may be valid for some historical cards.

Do not delete, merge, or silently rename rows solely because they appear in the review CSV. Resolve each case with a reliable Japanese-language reference.

## Compatibility note

The generated `set_id` and `card_id` values are stable CardCorpus-derived composite identifiers (for example, `japanese/expansion-pack-base-set::no-001`). Existing collection records are intentionally not migrated because this rebuild is intended to replace the whole master. Before shipping, confirm the app's CSV import and sorting behavior with these identifiers and the historical `No.` number formats.
