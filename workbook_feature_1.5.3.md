# workbook_feature_1.5.3

Формат: рабочий журнал итерации (агентский). После merge сжимается в WORKBOOK.md.

## Метаданные итерации

- Ветка: `feature/1.5.3`
- База: `main` @ `1cee57c` (свежайшая, feature/1.5.2 и feature/scan-session-rate-limit уже влиты, незавершённых веток нет)
- Версия: `1.5.3` (patch), versionCode 9
- Цель: автозапуск синхронизации при выходе из офлайна, исправление ошибки 400 при синхронизации,
  журнал (порядок «свежие сверху», персистентность, очистка, подтверждение удаления), чистка UI настроек
  и экрана ввода.

## Прочитано перед работой

- `ARCHITECTURE.md` (120 строк), `WORKBOOK.md` (329 строк), `docs/TZ.md`.
- Код по указателям из ARCHITECTURE: `ui/MainActivity.kt`, `ui/MainViewModel.kt`, `data/DocumentRepository.kt`,
  `data/OfflineStore.kt`, `data/OfflineModels.kt`, `data/SettingsStore.kt`, `ui/SettingsActivity.kt`,
  `ui/SettingsViewModel.kt`, `res/layout/activity_main.xml`, `activity_settings.xml`, `item_recent_scan.xml`,
  `res/values/strings.xml`, `app/build.gradle.kts`, `.github/workflows/android-ci.yml`.

## Диагностика ошибки 400 (подтверждена на тестовом аккаунте)

Запросы curl к `api.moysklad.ru` (basic auth `admin@manubad`, тестовый аккаунт):

| Запрос | Код |
|---|---|
| `entity/assortment?filter=id=<uuid>` | 200 |
| `entity/assortment?filter=id=<полный href>` | 400, «Неверное значение '…' параметра фильтрации 'id'» |
| `report/stock/all?filter=product=<полный href>` | 200 (href допустим) |

Причина в коде: `DocumentRepository.addItemByHref()` строит `assortment?filter=id=$productHref`, где
`productHref` — полный href из офлайн-снимка (`OfflineItem.href`). `pushPending()` вызывает именно
`addItemByHref`, поэтому синхронизация обрывается на первой же строке очереди. Та же ошибка молча
глотается в `assortmentInfo()` (`if (400) continue`) — из-за этого у части строк снимка нет наименований.

Решение: нормализовать значения фильтра `id=` до UUID (последний сегмент href) в `addItemByHref()` и
`assortmentInfo()`. Хранимый формат (href) не меняем — он нужен для сверки позиций документа
(`positions[].assortment.meta.href`).

## План задач

1. [x] Диагностика 400.
2. [ ] `DocumentRepository`: UUID в фильтрах `id=` (addItemByHref, assortmentInfo).
3. [ ] `MainViewModel`: автосинхронизация при выключении офлайна.
4. [ ] Журнал: свежие сверху, сквозная нумерация = seq, персистентность в `OfflineStore`, лимит 200,
       без автопрокрутки вниз, очистка журнала.
5. [ ] Удаление из журнала: диалог подтверждения (ДА красным / НЕТ зелёным, переменные жирным).
6. [ ] Убрать undo-«✕» из блока результата (удаление только из журнала).
7. [ ] Разметка result-зоны: 3 строки под наименование, 1 под код, 2 строки под результат.
8. [ ] Экран ввода: скрывать кнопку синхронизации (стрелки) при выключенном офлайне (GONE, не disabled).
9. [ ] Настройки: убрать ползунок офлайна, оставить кнопку «Офлайн остатки».
10. [ ] Версия 1.5.3 / versionCode 9, docs (ARCHITECTURE, WORKBOOK), CI, релиз `v1.5.3`.

## Журнал выполненного

(заполняется по ходу)
