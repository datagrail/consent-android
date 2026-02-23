# DataGrail Consent Android Demo App

This is a demo application showcasing the DataGrail Consent SDK for Android.

## Features Demonstrated

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

To use with your own config:

- Update the `configUrl` in `MainActivity.kt` line 56
- Replace with your actual DataGrail consent config URL

## Testing

The demo app demonstrates:

- ✅ Full banner UI with multiple layers
- ✅ Category toggles
- ✅ Button actions (accept, reject, save, navigate)
- ✅ Link handling
- ✅ Consent change callbacks
- ✅ Persistence across app restarts
