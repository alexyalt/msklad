# WORKBOOK

Журнал работ по проекту msklad. Записи добавляются в конец, старые не переписываются.

## 2026-11-20

- Создан локальный репозиторий, добавлены `README.md` и `.gitignore`. Коммит `dbc30c8`, ветка `master`.
- Добавлен раздел «Создание проекта» в `README.md`. Коммит `021f448` в `master`.
- Добавлен `halloworld.txt` в ветку `main`. Коммит `17159ab`, без перезаписи истории.
- Добавлены `docs/TZ.md` (техзадание), `AGENTS.md` (правила для агента) и `WORKBOOK.md` (журнал). Коммит и пуш в `main`: см. следующую запись.

Осталось: отозвать токен, который был в чате; проверить сборку APK на GitHub Actions после появления кода приложения.

## 2026-11-20 (пуш)

- Коммит `dec2ced` в `main` с файлами `docs/TZ.md`, `AGENTS.md`, `WORKBOOK.md`. Пуш fast-forward от `17159ab`, проверен по выводу `git push`.

## 2026-11-20 (CI)

- Добавлен `.github/workflows/android-ci.yml`: сборка `assembleDebug` и загрузка APK. Пока Gradle-проекта нет (`gradlew` отсутствует), шаги сборки пропускаются, чтобы workflow не падал.
- Осталось: добавить Gradle-проект Android (`gradlew`, `app/`), затем проверить первый запуск в Actions.
- Run `Android CI` на `b614320`: статус success (https://github.com/alexyalt/msklad/actions/runs/44786193453). Шаги сборки пропускаются по условию, пока нет `gradlew`; пошагово это не проверял.
- Создан GitHub Environment `development` через API (PUT /environments/development). Правил ветки пока нет.

## 2026-11-20 (скелет проекта)

- Добавлен Gradle-проект Android: `settings.gradle.kts`, `build.gradle.kts`, `app/build.gradle.kts`, `gradle.properties`, wrapper (`gradlew`, `gradle/wrapper/*`), `.gitignore`. Пустое приложение с темой Material3 и `AndroidManifest.xml` (INTERNET, CAMERA, `uses-feature camera required=false`).
- Стек по ТЗ: Kotlin, minSdk 24, compileSdk/targetSdk 34, ViewBinding, Retrofit + Gson + OkHttp, CameraX 1.3.4, ML Kit barcode-scanning.
- Локальная сборка проверена: `./gradlew assembleDebug` → `app/build/outputs/apk/debug/app-debug.apk` (JDK 17, Android SDK 34).
- Изменённые файлы: см. коммит.
- Осталось: API-слой и репозиторий, экран настроек, главный экран, звук/индикация, README, прогон CI.
- Блокеры: нет.
