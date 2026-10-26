# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Fixed

- Config fetch no longer retries definite 4xx responses; 408 and 429 still retry (TRUST-2744)
- A device holding an old copy of a user's consent no longer undoes a revocation the user made later on another device when it re-syncs (`setUserIdentifier`; sign-off scenario P0-06). The SDK now records when each consent choice was made (unix seconds, stored next to it): a `savePreferences` choice is stamped with the device clock (monotonically, never at or before the previous stamp), and an adopted universal consent record is stamped with its own `updated_at`. Universal consent writes send `is_explicit: true` and that `decision_ts` in the body, and sign `provDigest = sha256("true\n<decision_ts>\n")` to match. When the time is unknown the request timestamp is used, which is byte-identical to before; the `getSignature` contract is unchanged. On a re-sync over a found record, a local change is written through only when it was made strictly after the record's `updated_at`; otherwise the record is adopted. **Behavior change:** a same-second tie, a choice with no recorded time (every choice stored before this version, so the first re-sync after upgrading adopts the record) and a record without a usable `updated_at` all adopt. `updated_at` is the latest decision across all of the record's registers (categories and record-level fields such as `ccpa_optout`), so a newer local change in one category can still be dropped when anything else on the record is newer, a CCPA-only write included (TRUST-3226 exposes per-category times). A `setCcpaOptout` write-through, and the re-sync that flushes a pending CCPA choice, send a **CCPA-only** write (TRUST-2591 decision, 2026-10-26, same body as consent-banner #756): `ccpa_optout` with its provenance and an empty `cookieOptions` map, with no `isCustomised`, `consent_mode`, `platform`, `policy_name` or `config_version`. It is stamped with when `setCcpaOptout` was called (stored next to the flag), so a stale device toggling CCPA cannot undo a newer revocation, and a CCPA choice that waited offline is ranked by when the user made it. A local category change is never resent with it; the next re-sync writes it with its own decision time (TRUST-3311, TRUST-2902). Re-sending the categories with their older stamp would tie the record's `ccpa_optout` register, and the edge keeps an opt-out on a tie. Integrators whose backend rebuilds the string to sign from payload fields, assuming `decision_ts` equals the timestamp, will get `invalid_signature`; backends that HMAC the `stringToSign` the SDK hands them are unaffected (TRUST-3224)

### Added

- `initialize(context, configUrl, onConfigError, callback)` (and a Java overload taking a `ConfigErrorListener`): `onConfigError` reports a config fetch failure that the cached config masked. `initialize()` always fetches the config. When that fetch fails and a config cached by an earlier launch exists, init succeeds on the cache, and until now the failure was dropped silently. The callback receives the same exception `initialize()` reports when there is no cache: `ConfigNotPublished` for a definite 4xx, `NetworkError` for transport, timeout or 5xx, `ParseError` for a response that is not a config, and `ValidationError` for one that fails validation. The handler belongs to that `initialize()` call only, the same shape as iOS `initialize(configUrl:onConfigError:completion:)` and consent-react's `onConfigError` option. It fires at most once, on the main thread, before the init callback. It does not fire on success, without a cache, or once a newer `initialize()` or a `reset()` has superseded the call. A throwing handler is logged and ignored (TRUST-3254)
- Demo app: a Config URL Tester screen (now the launcher) that loads a pre-signed test config URL from the Mobile tab and previews it with `BannerDialog`, with specific errors for expired, altered, deleted, unreachable, unparseable, and unsupported-schema configs. CI now runs the demo's unit tests and uploads the debug APK as an artifact. No SDK API change (TRUST-2374)
- `ConsentException.HttpError` (with `statusCode` and `isClientError`), `ConsentException.ConfigNotPublished`, and `BuildConfig.SCHEMA_VERSION`. Both exceptions subclass `NetworkError`, so existing `NetworkError` handling still matches them (TRUST-2744)
- `clearUserIdentifier()`: logout / return-to-neutral for universal consent. Clears the device's identity binding and the stored consent choice so reads return the config defaults and the banner shows again; fires the consent-changed listener with the defaults. Non-destructive, unlike `reset()`: no network call, the server-side record is untouched, and the unique id, config cache/version, locale and pending queue are kept. Hosts must call it on logout; the SDK cannot detect a logout it is not told about (TRUST-2902)
- `setCcpaOptout(optedOut[, callback])` and `getCcpaOptout()`: a first-class CCPA/CPRA "Do Not Sell or Share" choice. Android has no native DNSMPI signal, so the host app is the source of truth. The flag is stored on the device and never changes a category. It is written through to the user's universal consent record when universal consent is enabled, `universalConsent.sync_optout` is on, `setUserIdentifier` has succeeded in this process, and an explicit consent choice is stored. Otherwise it rides the next universal consent write. A choice made while signed in, or during a login, that has not reached the record yet (nothing to write it with, a failed write) is kept by a re-sync or rehydrate instead of the record's older value. The write carries the user's raw category choice, never the signal-suppressed view: until the user saves a choice of their own, a write-through sends the choice the record held when `setUserIdentifier` last synced, so GPC or device ad-tracking suppression is never written to the record (TRUST-2591, TRUST-3074)

### Changed

- `reset()` during an `initialize()` still in flight now supersedes that init. Its callback fails with `NotInitialized` and its config is not restored. If it had already committed, it is uncommitted, so the SDK reads as uninitialized. If it had not, the storage wipe that `reset()` had nothing to run through is done by the next init to commit, or by the superseded init itself, exactly once. Before, the init committed afterwards and the reset was lost. An init that a newer `initialize()` superseded reports its own result and keeps (and caches) its config (TRUST-3254)
- **Behavior change:** universal consent writes now send `ccpa_optout` from the user's stored choice (`setCcpaOptout`), gated by `universalConsent.sync_optout`. With the gate off the field is left out of the write entirely, never sent as `false`, and a choice made meanwhile stays pending until the gate turns on (TRUST-3381, as consent-banner #756). Before, Android always sent `false`. The value is never derived from the ad-tracking signal or a category. When a login finds a record, the record's `ccpa_optout` replaces the local flag whatever `sync_optout` says, as on web and iOS. On a re-sync it is adopted only when `sync_optout` is on, and not while a choice made while signed in has yet to reach the record. A login away from a different identity with no record clears the flag. `clearUserIdentifier()` resets the flag to `false` and `reset()` wipes it. Records written earlier are adopted as they are (TRUST-2591)

- `setUserIdentifier` now tells a login apart from a re-sync. The SDK persists the user hash (never the raw identifier) of the identity the device is bound to; it is set only when `setUserIdentifier` succeeds and cleared by `clearUserIdentifier()` and `reset()`. On a login (device unbound, or bound to a different identity), a found universal consent record wins and nothing is written, even if the device holds a pre-login choice. A record carrying a choice replaces local state rather than merging with it: each category in the record takes the record's value, every other category takes its config default (never the prior local value), and essential stays on. A found record with no choice (signal-only, or empty preferences) returns local state to the defaults if anything is stored. With no record, only an explicit local choice (one saved on this device while it was not bound to a different identity) is written to the new identity; otherwise nothing is written, and if a different identity was bound its local state returns to the defaults. On a re-sync (already bound to this identity) with no local change since the last sync, the record is adopted and nothing is written, so it never re-sends a choice the user didn't change over a newer one from another device. Android used to write the local choice on every re-sync. A local change since then (any `savePreferences`, including one made while a sync's read or write was in flight) is written as the user's raw choice, and a miss writes only an explicit local choice. The device persists what the last sync left (the stored and raw consent maps and the bound user hash, never the raw identifier), so this also holds after a process restart and a view that GPC, the ad-tracking signal or the record's `ccpa_optout` suppressed is never written over the user's cross-device opt-ins. That state is cleared by `clearUserIdentifier()`, `reset()` and an identity switch. `rehydrateFromUniversalConsent` for the bound identity records what it stored the same way, so a later re-sync or `setCcpaOptout` never writes the view it suppressed; for any other identity it clears that state. In every case, config defaults are no longer seeded into a new record. The SDK cannot tell whether a pre-login choice was made by the person logging in or a previous user of a shared device (an explicit choice on an unbound device is attached on a no-record login by design), does no shared-device or shared-account detection, and cannot detect two people sharing one account (TRUST-2902)
- Align `rejectAll()` with the banner's definition of "essential": a category is now kept enabled after reject-all when `alwaysOn` is true or its `gtm_key` contains "essential", via the shared `ConsentConfig.essentialCategoryKeys()`. A category with `alwaysOn = false` whose `gtm_key` contains "essential" now stays enabled instead of being disabled (no change where essential categories are marked `alwaysOn`) (TRUST-1843)

## [1.7.0] - 2026-07-17

### Fixed

- Move encrypted storage initialization off the main thread in `initialize()` to prevent ANRs on cold start, and harden the async path against re-entrancy and cancellation (TRUST-2318)
- Fix close button clipping in `MODAL` display style by positioning it against the visible card, and density-scale content container side padding (TRUST-2267)

### Changed

- Show an "Always On" label for essential (always-on) categories instead of a disabled toggle switch (TRUST-2267)

## [1.6.0] - 2026-06-26

### Fixed

- Fix categories parse failure for button (TRUST-2138)
- Recover from corrupted Tink keyset in EncryptedSharedPreferences to prevent crash (TRUST-2075)
- Fix blank banner when switching between light and dark mode after SDK initialization (TRUST-1963)

### Changed

- Reflect button text casing defined in the layout (TRUST-1916)
- Respect the `style` parameter defined in the config JSON (TRUST-1900)

## [1.5.0] - 2026-05-22

### Added

- Add `consent_container_version_id` to save_preferences and save_open payloads
- Add `policy_uuid` to consent payloads
- Rich text rendering support for banner text and categories

### Fixed

- Fix save_open parameter alignment and action type handling

## [1.4.0] - 2026-04-01

### Changed

- Hide language picker element in mobile banner display

### Added

- CPRA and GDPR config parser test fixtures for improved testing coverage

## [1.0.0] - 2026-02-23

### Added

- Initial public release
- Consent banner display and management via `BannerDialog`
- Category-based consent preferences (`ConsentPreferences`, `CategoryConsent`)
- Network sync with DataGrail backend (`NetworkClient`, `ConfigService`, `ConsentService`)
- SharedPreferences-based local storage (`ConsentStorage`)
- Kotlin coroutines-based async operations with callback public API
- `DataGrailConsent` singleton for easy integration
- ProGuard/R8 consumer rules
- Demo app showcasing SDK integration
