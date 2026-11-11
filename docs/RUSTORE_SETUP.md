# Настройка автопубликации в RuStore

Workflow `.github/workflows/release-rustore.yml` публикует новую версию
MtzForge (`com.bluequark.mtzforge`) в RuStore по тегу `v*` или вручную.

## Что нужно добавить (один раз)

1. **RuStore Console** → приложение MtzForge → раздел «API RuStore» →
   «Создать ключ» → сохранить Key ID и скачать приватный ключ.
2. **GitHub** → Settings → Environments → `production`
   (Deployment branches: только теги `v*`) → добавить секреты:
   - `RUSTORE_KEY_ID` — Key ID из консоли;
   - `RUSTORE_PRIVATE_KEY` — приватный ключ целиком (Base64-строка из файла).
3. Секреты подписи (`FORGE_KEYSTORE_BASE64`, `FORGE_STORE_PASSWORD`,
   `FORGE_KEY_ALIAS`, `FORGE_KEY_PASSWORD`) уже используются в `build.yml` —
   добавьте их в environment `production` тоже (секреты environment не видят
   repository secrets и наоборот, если job использует environment).

## Как пользоваться

- **Тест:** Actions → Release to RuStore → Run workflow → `submit = false`.
  Создастся черновик с загруженным APK, но на модерацию ничего не уйдёт.
  Проверьте лог шага «Опубликовать в RuStore».
- **Релиз:** поднимите `versionCode` в `app/build.gradle.kts` (сейчас `1`),
  заполните текст «Что нового» при запуске или добавьте `CHANGELOG.md`, затем:
  `git tag v1.0.1 && git push origin v1.0.1`.

## Важно

- `versionCode` новой сборки обязан быть больше предыдущего — сейчас он
  захардкожен в `app/build.gradle.kts`, не забывайте поднимать.
- Загруженный через API файл не виден в веб-консоли до отправки на модерацию —
  это нормально; скрипт сам проверяет прикрепление файла через API.
- Одновременно возможен только один черновик: скрипт удаляет старый автоматически.
- Подробности и таблица ошибок — в полном туториале проекта.
