# DataGrail Consent Android Demo App

This is a demo application showcasing the DataGrail Consent SDK for Android.

## Features Demonstrated

- Config URL Tester (launcher screen): preview a pre-signed test config URL from the Mobile tab
- SDK initialization with config URL
- Showing the consent banner dialog
- Checking consent status
- Accept all / Reject all quick actions
- Consent change notifications
- Preference retrieval

## Running the Demo

1. Build the project:

   ```bash
   ./gradlew :demo:assembleDebug
   ```

2. Install on device/emulator:

   ```bash
   ./gradlew :demo:installDebug
   ```

3. Or run directly:

   ```bash
   ./gradlew :demo:assembleDebug :demo:installDebug
   ```

## Usage

1. **Initialize SDK**: Click to initialize the SDK with a config URL
2. **Show Banner**: Displays the consent banner dialog
3. **Check Status**: Shows current consent status
4. **Accept All**: Programmatically accept all categories
5. **Reject All**: Programmatically reject non-essential categories

## Configuration

To use the full SDK demo with your own config, tap **Open full SDK demo** on the tester screen and enter your config URL in the **Config URL** field (the default is set by `defaultConfigUrl` in `MainActivity.kt`).

## Validating a test config from the Mobile tab

The app opens on the **Config URL Tester**. It previews a test config with the SDK's own banner before you publish it live.

1. Install the app. Internally, download `consent-demo-debug-apk` from the artifacts of a CI run's *Build Demo App* job, unzip it, and run:

   ```bash
   adb install demo-debug.apk
   ```

   Or build from source with `./launch_demo.sh`.
2. In the Consent **Mobile** tab, publish a test config and copy its **View config** link.
3. Get the link onto the device (for example, email or message it to yourself), then tap **Paste** and **Load**.
4. Check the version panel: `SDK library version`, `SDK schema version`, and after loading, `Config schema · Artifact · Expires`.
5. Tap **Show banner (modal)** or **Show banner (full screen)**. Saving or dismissing only updates the status text; nothing is stored or sent.

Test URLs are valid for **15 minutes**. If the tester says the URL expired, reload the test config panel in the Mobile tab to get a fresh link. Test configs are deleted after 7 days.

| Error | What to do |
|---|---|
| Test URL expired | Reload the Mobile tab's test config panel and copy the new link. |
| URL was changed or cut off | The link was truncated or edited; copy the whole link again. |
| Config not found | The test config was deleted or access was denied; publish a new test config. |
| Couldn't reach the config | Check the device's internet connection. |
| Unsupported schema version | This build renders only the schema shown as *SDK schema version*. Select that target in the Mobile tab. |
| Config couldn't be read | Report it to support with the schema version shown in the panel. |

The tester fetches the URL itself rather than calling `DataGrailConsent.initialize`, because the SDK falls back to a cached config when a fetch fails, which would hide an expired or altered link.

## Testing

The demo app demonstrates:

- ✅ Full banner UI with multiple layers
- ✅ Category toggles
- ✅ Button actions (accept, reject, save, navigate)
- ✅ Link handling
- ✅ Consent change callbacks
- ✅ Persistence across app restarts
