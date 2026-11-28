# workbook feature/1.6.1 (агентский, сжимается в WORKBOOK.md после merge)

version: 1.6.1 (versionCode 11, patch)
base: main 6265b6a

tasks:
  - id: offline-stock
    status: done (коммит 167c3da, предыдущая сессия)
    what: OfflineSnapshot — живой остаток = items + вклад всех pending (SUPPLY +, LOSS −, MOVE − источник / + цель);
      available(href) не зависит от вкладки; отправленная строка «запекается» в items (baked); сводки по effective().
    files: data/OfflineModels.kt, ui/MainViewModel.kt
    check: тест.аккаунт — move П1 src=Основной склад, enter 001 / loss С1 store=Основной склад → один склад, сценарий
      «00050: оприходовать 10 → списать 5» в офлайне проходит по логике снимка.
  - id: export-separator
    status: done (167c3da) — OfflineExport ';' / ';;'; заголовок диалога и KDoc обновлены в этой сессии.
  - id: export-copy
    status: done
    what: диалог выгрузки — EditText без keyListener=null (раньше выделение/копирование не работало),
      showSoftInputOnFocus=false, longClickable, + кнопка «Копировать» (ClipboardManager, без разрешений), Toast.
    files: ui/MainActivity.kt, res/values/strings.xml (export_copy, export_copied, export_title)
  - id: sync-time-no-date
    status: done — syncTimeLabel формат "HH:mm".
  - id: error-in-result-block
    status: done — ResultState.Error выводится в resultName (красным), resultMessage больше не получает ошибку;
      дублирующий Snackbar убран.
  - id: version
    status: done — app/build.gradle.kts 1.6.1 / 11.

known_limits / next:
  - Офлайн-снимок считает остатки по складу-источнику перемещения; если у оприходования/списания другой склад,
    их остатки в офлайне всё равно берутся/меняются по складу перемещения (на тест.аккаунте склады совпадают).
  - Товары с нулевым остатком на обоих складах и не входящие в позиции документов не попадают в снимок
    (report/stock/all их не отдаёт) → офлайн-оприходование такого товара даст «не найден в локальной копии».
    Кандидат на следующую итерацию: догружать весь entity/assortment (на тест.аккаунте 100 позиций).

log:
  - 2026-11-28 UTC: коммит 2b4c6a7 (UI+версия), PR #3, run 46168240812 (pull_request) — success.
  - ARCHITECTURE.md: поток «живые остатки», 2 ограничения, журнал 1.6.1.
