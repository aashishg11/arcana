# CLAUDE.md — Arcana

Privacy-first Android app (Kotlin/Compose/Hilt/Room) for a real 504-item Funko Pop collection; portfolio piece showcasing **on-device AI** (Gemini Nano, hybrid inference). Single `:app` module, package-by-feature, base `com.aashishgodambe.arcana`.

**Read these before planning/large work:** `ARCANA_CONTEXT.md` (roadmap, hardware, gotchas) · `DESIGN.md` (interface seams, error handling) · `WEEK_02_SUMMARY.md` (current state, stubs, benchmarks) · `arcana-wireframes.html` (**design source of truth**). Weekly plans: `WEEK_0N_PLAN.md`.

## Build / deploy / test (CLI)

Bare `gradlew` fails after the Android Studio update — **always prefix `JAVA_HOME`**:

```bash
JAVA_HOME="C:/Program Files/Android/Android Studio1/jbr" ./gradlew :app:installDebug
JAVA_HOME="C:/Program Files/Android/Android Studio1/jbr" ./gradlew :app:testDebugUnitTest :app:connectedDebugAndroidTest
```

Relaunch on device after install: `adb shell monkey -p com.aashishgodambe.arcana -c android.intent.category.LAUNCHER 1`. Device: `57130DLCQ000ZJ` (Pixel 10 Pro XL, Nano v3 on-device).

**Git Bash mangles `/sdcard`//`/data` paths** — prefix such adb commands with `MSYS_NO_PATHCONV=1` (e.g. `uiautomator dump`, `adb push` to Downloads, `run-as … cat databases/…`).

## Toolchain (bleeding-edge — do NOT downgrade to appease an older IDE; update the IDE)

AGP 9.2.0 (built-in Kotlin 2.2.10) · Gradle 9.4.1 · compileSdk 37 · minSdk 29 · JDK 21 · KSP 2.2.10-2.0.2 · Room 2.8.4 · Hilt 2.59.2 · Compose BOM 2026.06.00 · firebase-ai 17.13.0 + firebase-ai-ondevice 16.0.0-beta03 (separate beta, **not** in BoM). Existing workarounds in `app/build.gradle.kts` / `gradle.properties` (Coil-3.5 Kotlin-2.4 metadata; KSP + AGP-9 source sets) are intentional — keep them.

## Conventions

- **Prefer the wireframes over any shorthand** in plans — dark-first, iris/gold, value-first Portfolio home.
- **Domain is a sealed `Collectible`** (only `FunkoPop` in v1); dispatch with exhaustive `when` so new categories break at compile time. Features depend on interface seams (`GeminiService`, `CollectibleRepository`, …), never Firebase/ML Kit/HTTP directly.
- **Valuation counts duplicate copies**: totals use `SUM(value * quantity)`; counts stay at unique entries (HobbyDB convention).
- **Tests**: `FakeGeminiService` + fake repositories make AI/ViewModel code device-free (coroutines-test + Turbine). Add coverage with new features; keep the suite green.

## Commits (personal repo — public-headed)

- Git identity is **personal** and repo-local: `Aashish Godambe` / `12780079+aashishg11@users.noreply.github.com` — NOT the work account.
- **No "Day N" prefix** — describe the change. Concise subject + bullet body.
- **No `Co-Authored-By` trailer** — do not add the Claude co-author line to commit messages.
- Commit/push only when asked.

## Never commit (gitignored — keep it that way)

- `app/google-services.json` (Firebase ids + client key)
- `seed-data/*.raw.csv` (personal data; only the sanitized `collectibles_2026-07-03.csv` is committed — it's the test fixture)
- `notes/INTERVIEW_PREP.md` (cumulative, updated each week)
- `.claude/settings.local.json`
