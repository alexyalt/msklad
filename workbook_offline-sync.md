# WORKBOOK BRANCH: feature/offline-sync (msklad 1.5)

branch_id: offline-sync
target_version: 1.5 (versionCode 6)
main_workbook: WORKBOOK.md (перенести сюда после мержа, этот файл удалить)

## SCOPE 1.5 — статус

- [x] S1 кнопка «Офлайн Синхронизация» на стартовом экране + 2 строки сводки
- [x] S2 ползунок офлайн-режима в шапке экрана сканирования
- [x] S3 кнопка «Синхронизировать» (иконка двух стрелок) → выгрузка опр→спис→пер, затем перезагрузка остатков
- [x] S4 единица измерения в строке «ИЗ → В» и в «было → изменение → стало»
- [x] S5 кнопки пер/опр/спис под полем ввода (toggle group, активна одна)
- [x] S6 поля документов списания и оприходования в настройках
- [x] S7 корректировка остатков через оприходование/списание, работает и в офлайне
- [x] S8 версия 1.5 / code 6
- [ ] S9 merge в main + релиз v1.5 с APK

ВНЕ 1.5 (следующая итерация): подсветка ошибочных позиций, построчная обработка ошибок синхронизации,
автоматическое разрешение конфликтов.

## ФАЙЛЫ

Новые:
- `data/Documents.kt` — DocumentType (move/supply/loss), DocInfo, AddResult (+uom, storeBefore, docType), ProductInfo (+uom)
- `data/OfflineModels.kt` — OfflineItem, PendingLine, OfflineSnapshot (+find по barcode/code/article, available, withPending, withoutPending*)
- `data/OfflineStore.kt` — SharedPreferences+Gson: snapshot и кэш DocInfo
- `data/DocumentRepository.kt` — заменил MoveRepository: документы любых 3 типов, сбор снимка, pushPending
- `res/drawable/ic_sync.xml`, `workbook_offline-sync.md`, `ARCHITECTURE.md`

Изменены:
- `api/MoySkladApi.kt` — generic entity/{type}, entity/{type}/{id} GET+PUT, у stock добавлен offset
- `data/SettingsStore.kt` — supplyInput, lossInput
- `data/Format.kt` — withUom(value, uom)
- `ui/MainViewModel.kt` — офлайн-ветка, очередь, sync(), docType, pendingCount, Synced state
- `ui/MainActivity.kt` — switch/docGroup/syncButton, resultLine с uom, DocInfo в Intent (+EXTRA_TYPE)
- `ui/SettingsActivity.kt` + `ui/SettingsViewModel.kt` — downloadOffline(), savedSummary(), поля опр/спис
- `ui/LabelViewModel.kt` — MoveRepository → DocumentRepository
- `res/layout/activity_main.xml`, `res/layout/activity_settings.xml`, `res/values/strings.xml`
- `app/build.gradle.kts` — 1.5 / 6

Удалено: `data/MoveRepository.kt`.

## РЕШЕНИЯ

- D1 стартовый экран = SettingsActivity. «Начать» не менялось по поведению.
- D2 офлайн-снимок = объединение остатков source+target и позиций документов опр/спис; справочные
  данные батчами `entity/assortment?filter=id=..` по 50, фолбэк на данные отчёта об остатках.
- D3 поиск в офлайне: barcode → code → article (точное совпадение).
- D4 очередь в SharedPreferences (OfflineSnapshot.pending), порядок выгрузки supply→loss→move,
  ошибка прерывает выгрузку, очередь сохраняется.
- D5 математика остатков повторяет онлайн-логику (notYetInStock для непроведённых документов).
- D6 AddResult: для MOVE «ИЗ → В», для SUPPLY/LOSS «было → ±qty → стало» (result_line_store).
- D7 кнопки пер/опр/спис активны всегда; если документ не задан — понятная ошибка, офлайн-выгрузка
  пропускает такие строки и сообщает об этом (sync_skipped).
- D8 DocInfo опр/спис кэшируются в OfflineStore при «Начать» и при офлайн-синхронизации, чтобы экран
  сканирования не делал запросов за документами.
- D9 сборка APK только на GitHub Actions (в песочнице Android SDK нет).

## ЛОГ

- [x] клонирование, чтение кода, ветка feature/offline-sync
- [x] ARCHITECTURE.md + журнал ветки (74e3a84)
- [x] слой данных (eca4e84)
- [x] песочница сброшена после eca4e84 → данные уцелели, потерян UI-слой, восстановлен из контекста
- [x] версия 1.5 / 6, ic_sync, строки (4586b08)
- [x] MainViewModel (5019005)
- [x] MainActivity (ff8f2a6)
- [x] SettingsActivity/ViewModel (2870d96)
- [x] разметка (3dba276)
- [ ] прогон CI на ветке
- [ ] merge → main, CI собирает и обновляет релиз latest
- [ ] релиз v1.5 с APK из артефакта сборки
