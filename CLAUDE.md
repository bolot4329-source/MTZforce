# Project: MTZ Forge — on-device HyperOS icon theme (.mtz) builder

Owner is a beginner: explain steps simply, in Russian. User guide: `docs/TUTORIAL.md` (Russian).

## Target & CI/CD
- **Devices:** the app runs on any Android 8.0+ (`minSdk = 26`), but the built themes apply only on Xiaomi/POCO/Redmi with **HyperOS/MIUI** (system «Темы» app, `.mtz` format).
- **Build:** GitHub Actions only (`.github/workflows/build.yml`): `assembleDebug assembleRelease`, artifacts `MTZ-Forge-Debug` / `MTZ-Forge-Release`. No `gradlew`/wrapper jar in git; CI installs Gradle 8.11.1 via `gradle/actions/setup-gradle`.
- **Publish:** tag push `v*` or manual Run workflow with `publish = true` → GitHub Release `MTZ-Forge-v<versionName>.apk`, body from `docs/RELEASE_NOTES.md`. Bump `versionCode` + `versionName` in `app/build.gradle.kts` before every publish.
- **Toolchain (STRICT for CI stability, change only as a dedicated step):** AGP `8.7.3`, Kotlin `2.0.21` (+ compose plugin), Gradle `8.11.1`, JVM target `17`, `compileSdk = 35`, `targetSdk = 35`. All dependency versions live in the catalog `gradle/libs.versions.toml` — add/change libraries there.
- **UI:** single-activity Jetpack Compose Material 3, no navigation library (screens switched by state in `MainScreen`).

## Signing (MUST FOLLOW)
- The release key exists ONLY in GitHub secrets (`FORGE_KEYSTORE_BASE64` / `FORGE_STORE_PASSWORD` / `FORGE_KEY_ALIAS` = `forge` / `FORGE_KEY_PASSWORD`) and in the owner's local `MtzForge-release.keystore`. **Never commit `*.keystore` / `*.jks`** — `.gitignore` blocks them.
- `app/build.gradle.kts` reads signing from env vars or `~/.gradle/gradle.properties` (`FORGE_KEYSTORE_FILE`, `FORGE_STORE_PASSWORD`, `FORGE_KEY_ALIAS`, `FORGE_KEY_PASSWORD`). Without them, release falls back to the debug key so CI never breaks; such APKs are **not compatible** with official releases.
- Release key SHA-256: `09:6A:50:9A:1D:EC:E7:10:83:0F:29:84:15:85:28:A6:5B:70:65:20:1E:52:87:E6:04:1A:69:20:04:64:90:9A`. The old key (committed in the past inside `MtzForge.zip`, password `mtzforge`) is compromised and dead — never reuse it.

## Project layout (`com.bluequark.mtzforge`)
- `core/PsdReader.kt` — PSD/PSB parser (RGB 8-bit), composite («Как в Photoshop») vs layer modes, grid-unpacking of folder layouts.
- `core/IconRecolor.kt` — palette extraction (background/glyph/extras), antialiasing-safe recolor, theme palette, color pairs (`lighterAccent` / `darkerShade`), `themeIconName(pkg, cls)` for share-sheet icons (`пакет#экран`).
- `core/MtzTheme.kt` — MTZ (zip) read/write: nested `icons` zip, per-app module rebuild (`res/drawable-xxhdpi`, `night-*` copies — dark theme wins otherwise), `description.xml` patch, base-theme resource carry-over.
- `core/IconPainter.kt`, `core/Raster.kt` — brush/fill editor with undo history.
- `SystemIcons.kt` / `ShareIcons.kt` — pull icons from installed/system apps and share-sheet targets.
- `ThemeApply.kt` — export to `Download/MTZ Forge`, open in the Themes app, keep-theme service, icon check.
- `ui/` — `MainScreen` (editor), `SystemScreen` (in-app icons + share menu), `PainterScreen`, `Pickers.kt` (color picker), `ApplyUi.kt`, `ColorsUi.kt`.

## Rules
1. Never commit signing keys, passwords or keystores.
2. Icons are saved in their exact source size; dark variants are duplicated as `night-xxhdpi` in `MtzTheme.rebuildModule` — do not break this.
3. Kotlin string escapes in regexes: `\.` in source = literal dot. When editing files via GitHub MCP tools, double-check that backslashes survive the round trip (a real bug once: `\.` arrived as `.` in `MtzTheme.iconPath`).
4. Stateful system resources (switches, level indicators, animations) stay hidden behind the «Только иконки» filter — replacing them with a static PNG breaks their logic.
5. CI publishes a GitHub Release only from a `v*` tag or manual `publish = true`, and only when all four `FORGE_*` secrets are set.

## Status
- v1.0 (versionCode 1) — first public release: PSD import, PNG batch, base theme, recolor + palette + pairs, painter, system/share icons, theme apply & keep service. Review log: `docs/gemini-log.md`.
