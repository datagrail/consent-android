# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

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
