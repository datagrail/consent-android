# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added

- `clearUserIdentifier()`: logout / return-to-neutral for universal consent. Clears the device's identity binding and the stored consent choice so reads return the config defaults and the banner shows again; fires the consent-changed listener with the defaults. Non-destructive, unlike `reset()`: no network call, the server-side record is untouched, and the unique id, config cache/version, locale and pending queue are kept. Hosts must call it on logout; the SDK cannot detect a logout it is not told about (TRUST-2902)

### Changed

- `setUserIdentifier` now tells a login apart from a re-sync. The SDK persists the user hash (never the raw identifier) of the identity the device is bound to; it is set only when `setUserIdentifier` succeeds and cleared by `clearUserIdentifier()` and `reset()`. On a login (device unbound, or bound to a different identity), a found universal consent record wins: it is adopted locally and nothing is written, even if the device holds a pre-login choice. With no record, only an explicit local choice (one saved on this device while it was not bound to a different identity) is written to the new identity; otherwise nothing is written, and if a different identity was bound its local state returns to the defaults. On a re-sync (already bound to this identity), found records and write-through are unchanged, but a miss writes only an explicit local choice. In every case, config defaults are no longer seeded into a new record. The SDK cannot tell whether a pre-login choice was made by the person logging in or a previous user of a shared device (an explicit choice on an unbound device is attached on a no-record login by design), does no shared-device or shared-account detection, and cannot detect two people sharing one account (TRUST-2902)
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
