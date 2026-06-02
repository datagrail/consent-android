# CLAUDE.local.md — TRUST-1843 (consent-android surface)

You are running JAILED inside a shuru microVM (Linux, aarch64). Your workspace is
`/workspace` (a standalone clone of consent-android on branch
`TRUST-1843-android-tv`). The host knowledgebase is mounted read-write at
`/knowledgebase` — read specs there, and update ONLY the harness state files
under `/knowledgebase/projects/consent/.harness/TRUST-1843/` (progress.md,
feature_list.json). Write all code in `/workspace`.

## Kotlin/Android DOES compile here — self-verify, don't defer
Unlike the tvOS surface (Swift can't build on Linux), this VM has JDK 17 + the
Android SDK (compileSdk 34, build-tools, platform-tools) and the Gradle 8.9
wrapper. **You are expected to actually build and test your work**:

```bash
cd /workspace
./gradlew :library:ktlintCheck      # lint (autofix: :library:ktlintFormat)
./gradlew :library:test             # unit tests (Robolectric runs headless on the JVM — no emulator)
./gradlew :library:assembleRelease  # library compiles
./gradlew :demo:assembleDebug       # demo compiles (feat-014)
```

A feature is `passes:true` ONLY if its `verification` Gradle commands actually
succeed in this VM. Do not mark a feature passing on inspection alone — run it.
On-device / emulator D-pad QA is the human's job on the host; everything else is
yours to verify here.

The toolchain is pre-warmed: JDK 17, Android SDK (compileSdk 34), the Gradle 8.9
wrapper, and a populated `~/.gradle` cache are baked into the VM image, so builds
are fast and need no network for existing deps. `assembleRelease`/`test`/
`ktlintCheck` on the existing code already pass here — a green baseline.

**aapt2 note (already handled — do not change):** this is an aarch64 VM and
Google's aapt2 is an x86_64 binary, so AGP runs it through a qemu shim.
`~/.gradle/gradle.properties` already sets
`android.aapt2FromMavenOverride=$ANDROID_HOME/build-tools/34.0.0/aapt2` (the qemu
wrapper). Leave it alone. If you ever see "AAPT2 ... Daemon startup failed" or
"Exec format error", that property got lost — restore it; do NOT add a different
aapt2 or downgrade AGP.

> If Gradle genuinely can't find the Android SDK (not the aapt2 case above),
> STOP and report it — don't hack around it. For the new ZXing dependency
> (feat-012), `./gradlew` fetches it from Maven Central on first use — that
> network access is expected and allowed.

## Harness: TRUST-1843
State lives in the knowledgebase (cross-repo harness):
`/knowledgebase/projects/consent/.harness/TRUST-1843/`
This run works ONLY on features tagged `"repo": "consent-android"` (feat-010..014).

### Session Start
1. Read `/knowledgebase/projects/consent/.harness/TRUST-1843/sources.json`,
   `progress.md`, `feature_list.json`.
2. Read the plan: `/knowledgebase/projects/consent/wiki/decisions/mobile/ctv-implementation-plan.md`
   (**Platform 2: Android TV** is your primary spec) and
   `ctv-platform-expansion-feasibility.md` for context.
3. Study the EXISTING SDK you are extending — your code must match its patterns
   and not change mobile behavior:
   - `library/src/main/kotlin/com/datagrail/consent/DataGrailConsent.kt` (public singleton; dual Kotlin-lambda/Java-callback overloads)
   - `library/src/main/kotlin/com/datagrail/consent/ui/BannerDialog.kt` (the mobile banner whose element model + handleAcceptAll/handleRejectAll/handleSavePreferences logic you mirror)
   - `ConsentManager.kt`, `models/`, `network/NetworkClient.kt`, `storage/ConsentStorage.kt`
4. Pick the lowest-numbered consent-android feature where `passes:false` and all
   dependencies pass.

### Work Rules
- **WIP=1.** One feature at a time, in dependency order (feat-010 first — TV
  detection + routing must compile before the banner/QR build on it).
- **Guardrails:**
  - ZERO changes to existing MOBILE public API signatures or behavior. TV
    routing is INTERNAL (`DeviceCapabilities.isTv()` inside `showBanner`).
    Existing mobile tests stay green.
  - Reuse the existing layers untouched: `EncryptedSharedPreferences` storage,
    `NetworkClient`, `ConsentService`, models. No model/manager rewrites — add
    `ConsentManager.adoptRemotePreferences(...)` additively.
  - Back key NEVER trapped (navigate-a-layer then dismiss).
  - **Dependency exception:** you MAY add `com.google.zxing:core` (pure-Java QR
    encoder) in feat-012 — Android has no built-in QR generator (tvOS used
    CoreImage). Do NOT add any other new external dependency.
- **Two lessons carried from the tvOS build — get these right:**
  1. **cookieOptions map→array adapter.** The Universal Consent API returns
     `consent_preferences.cookieOptions` as a MAP `{ "dg-category-x": bool }`
     with camelCase `isCustomised`. The SDK's `ConsentPreferences` models
     cookieOptions as a LIST of `CategoryConsent`. `PairingService` MUST decode
     via a boundary type and adapt map→list, or it can never parse a real
     "found" response. (On tvOS the agent decoded straight into the model — a
     real bug caught only on the host. Don't repeat it.)
  2. **Baseline `updated_at`.** `PairingCoordinator` captures the record's
     `updated_at` on its FIRST poll without completing; it only completes on a
     NEW write (changed/appeared `updated_at`). Otherwise a pre-existing record
     instantly dismisses the banner.
- **user_hash parity:** `SHA256("{customerId}:{consentProjectId}:{deviceId}")`
  hex must byte-match web/iOS for the shared test vector (deviceId default =
  `Settings.Secure.ANDROID_ID`). Include a parity test against the known vector.
- **Commit after each feature** with a descriptive message.

### Session End
1. Commit all changes in `/workspace`.
2. Update `/knowledgebase/.../TRUST-1843/progress.md` and `feature_list.json`
   with status + exactly which Gradle commands you ran and their result, and the
   list of HOST-only steps remaining (on-device/Fire TV D-pad QA, store/cert).
