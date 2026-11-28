# WORKBOOK BRANCH: feature/offline-sync (msklad 1.5)

branch_id: offline-sync
target_version: 1.5
started: 2026-11-28
main_workbook: WORKBOOK.md (merge after release)

## SCOPE 1.5

- [ ] S1 offline snapshot download (start screen button "Офлайн Синхронизация" + summary lines)
- [ ] S2 offline switch in scan screen header
- [ ] S3 sync button (two circular arrows) -> push pending supply->loss->move, reload snapshot
- [ ] S4 UoM in "ИЗ -> В" line
- [ ] S5 doc selector пер / опр / спис under input row
- [ ] S6 settings fields for loss + supply documents
- [ ] S7 correction flows (supply/loss docs work in both online and offline)
- [ ] S8 version 1.5 (versionCode 6), release v1.5 with APK asset

OUT OF SCOPE (next iter): per-position error highlighting, partial sync error recovery, conflict resolution.

## CODE MAP (read these first)

- `app/src/main/kotlin/ru/msklad/scanner/api/MoySkladApi.kt` - Retrofit endpoints
- `data/DocumentRepository.kt` (new, replaces MoveRepository.kt) - all МойСклад document logic
- `data/OfflineStore.kt` (new) - SharedPreferences + Gson persistence of snapshot/pending/docs
- `data/OfflineModels.kt` (new) - DocumentType, DocInfo, OfflineItem, PendingLine, OfflineSnapshot
- `ui/MainViewModel.kt` - scan session, online/offline branches
- `ui/MainActivity.kt` - scan screen bindings
- `ui/SettingsViewModel.kt` / `ui/SettingsActivity.kt` - start screen, offline download

## DECISIONS

- D1: start screen == SettingsActivity (LAUNCHER). "Начать" untouched. Offline download is a separate
  button that saves settings + resolves docs + downloads snapshot + shows 2 summary lines.
- D2: offline snapshot = union of {stock source, stock target, supply positions, loss positions};
  names/code/article/barcode/uom fetched via `entity/assortment?filter=id=a,b,c` batches (50 per call),
  fallback to stock-report name if batch fails (barcode then empty, lookup by code/article still works).
- D3: offline product lookup by exact match on barcode -> code -> article.
- D4: pending lines stored locally per DocumentType; push order SUPPLY -> LOSS -> MOVE; abort on first
  ApiException, keep pending intact, show error.
- D5: stock math mirrors existing online logic (notYetInStock subtraction for non-applied docs) so that
  online and offline numbers agree.
- D6: AddResult extended with uom, docType, storeBefore, storeAfter. MOVE renders `result_line`,
  SUPPLY/LOSS render `result_line_store` (before -> +/-qty uom -> after).
- D7: doc selector buttons stay enabled even if doc not configured; submit returns a clear error and
  offline sync skips unconfigured docs (documented, not silent).
- D8: resolved DocInfo for supply/loss cached in OfflineStore at "Начать"/offline download so the scan
  screen never needs network to know document ids/stores.

## PROGRESS LOG

- [x] repo cloned, code read, branch feature/offline-sync created
- [ ] ARCHITECTURE.md
