# Native mobile public catalog

The app reads facility markers, regional counts, names, addresses, opening hours and facilities from SQLite on the device. Reviews, ratings, crowding and tissue availability remain live detail-card requests. Kakao basemap tiles and place search still use the network.

## Contents and languages

`scripts/mobile-data/export.sh` selects explicit public columns for `visibility_status='VISIBLE'` in one MySQL repeatable-read, read-only transaction. It includes current verified region assignments, normalized schedules, coordinate-matched display groups and source-current translations. It never selects users, reviews, credentials or moderation records. All public facilities are retained; only valid Korean coordinates enter the marker index.

Korean plus **five foreign locales** are included: `en`, `ja`, `zh-cn`, **`zh-tw`**, **`zh-hk`**. Taiwan and Hong Kong remain separate records. Missing translations fall back to Korean; the manifest reports actual translation counts.

## Publication

- Dedicated R2 Standard bucket: `geupddong-mobile-data`.
- Public origin: `https://mobile-data.geupddong.com`.
- `latest.json` has a 60-second cache lifetime. Immutable files are compressed with HTTP gzip and cached for 30 days.
- The GitHub workflow reads the production DB through the existing pinned Cloudflare Tunnel SSH transport, builds SQLite on the runner and publishes once daily at approximately **04:30 KST**. GitHub schedules can be delayed.
- `MOBILE_CATALOG_ENABLED=true`, `MOBILE_CATALOG_ORIGIN`, and secret `MOBILE_CATALOG_PUBLISH_TOKEN` configure the job. The same token is stored as Worker secret `PUBLISH_TOKEN`, never in the app.
- The implementation branch push trigger bootstraps publication before merging. Scheduled execution requires this workflow on the default branch.
- Identical source content produces no new version. A decrease larger than 20% fails publication for investigation.
- Artifacts are immutable, SHA-256 checked on upload, and verified before the manifest is conditionally committed. A failed or overlapping job cannot publish a manifest pointing to missing files.

## Retention and recovery

Keep the latest and previous full snapshots plus 30 days of deltas. Deltas include full replacement records and deleted IDs, so translation removals, visibility removals and facility changes all propagate. Pruning removes expired delta references even on unchanged days. Unreferenced files get a 48-hour grace period for in-progress downloads and cached manifests. The latest snapshot has no age-based expiry.

At cold launch the app immediately restores the last validated SQLite file and checks the manifest once. It uses a complete delta chain only when cheaper than a full download; expired/missing chains fall back to the latest full snapshot. Updates run on a staging copy, check file checksum, database integrity, version and count, then atomically change the saved pointer. Previously downloaded data remains usable on failure. Returning from the background does not re-sync.

Disable `MOBILE_CATALOG_ENABLED` to pause future exports. Existing public files and installed offline copies continue working. This pipeline does not deploy or modify the API service or existing web cache.

## Validation

```text
python -B -m unittest discover -s scripts/mobile-data -p 'test_*.py'
node --test mobile-data-worker/worker.test.mjs
```

App checks live in `toilet-mobile/tests/catalog.test.mjs`, alongside native app type checks and emulator validation.
