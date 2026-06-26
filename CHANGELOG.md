# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

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
