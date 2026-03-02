#!/bin/bash
set -e

# Parse arguments
DEMO_TYPE="kotlin"
CLEAN_BUILD=false

while [[ "$#" -gt 0 ]]; do
    case $1 in
        --clean) CLEAN_BUILD=true ;;
        kotlin|java)
            DEMO_TYPE="$1"
            ;;
        --help|-h)
            echo "Usage: $0 [kotlin|java] [--clean]"
            echo ""
            echo "Arguments:"
            echo "  kotlin    Launch Kotlin demo (default)"
            echo "  java      Launch Java demo"
            echo "  --clean   Clean build artifacts before building"
            echo ""
            echo "Examples:"
            echo "  $0              # Launch Kotlin demo"
            echo "  $0 java         # Launch Java demo"
            echo "  $0 java --clean # Clean build and launch Java demo"
            exit 0
            ;;
        *)
            echo "Unknown option: $1"
            echo "Use --help for usage information"
            exit 1
            ;;
    esac
    shift
done

echo "Building and launching Android Demo app ($DEMO_TYPE version)..."

# Navigate to Android directory
cd "$(dirname "$0")"

# Check if JAVA_HOME is set, if not try to find it
if [ -z "$JAVA_HOME" ]; then
    # Try Android Studio's embedded JDK
    if [ -d "/Applications/Android Studio.app/Contents/jbr/Contents/Home" ]; then
        export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
        echo "Using Android Studio's embedded JDK: $JAVA_HOME"
    elif [ -x "/usr/libexec/java_home" ]; then
        export JAVA_HOME=$(/usr/libexec/java_home 2>/dev/null)
    fi
fi

if [ -z "$JAVA_HOME" ]; then
    echo "Java not found. Please install Java or set JAVA_HOME."
    exit 1
fi

export PATH="$JAVA_HOME/bin:$PATH"

# Check if ANDROID_HOME or ANDROID_SDK_ROOT is set
if [ -z "$ANDROID_HOME" ] && [ -z "$ANDROID_SDK_ROOT" ]; then
    # Try common locations
    if [ -d "$HOME/Library/Android/sdk" ]; then
        export ANDROID_HOME="$HOME/Library/Android/sdk"
    elif [ -d "$HOME/Android/Sdk" ]; then
        export ANDROID_HOME="$HOME/Android/Sdk"
    else
        echo "Android SDK not found. Please set ANDROID_HOME environment variable."
        exit 1
    fi
fi

SDK_ROOT="${ANDROID_HOME:-$ANDROID_SDK_ROOT}"
ADB="$SDK_ROOT/platform-tools/adb"
EMULATOR="$SDK_ROOT/emulator/emulator"

# Check for adb
if [ ! -f "$ADB" ]; then
    echo "adb not found at $ADB"
    exit 1
fi

# Find running emulator or start one
DEVICE_ID=$("$ADB" devices | grep -E "emulator-[0-9]+" | head -1 | awk '{print $1}')

if [ -z "$DEVICE_ID" ]; then
    echo "No running emulator found, starting one..."

    # Get list of available AVDs
    AVD_NAME=$("$EMULATOR" -list-avds 2>/dev/null | head -1)

    if [ -z "$AVD_NAME" ]; then
        echo "No Android Virtual Devices found."
        echo "Please create one using Android Studio's AVD Manager."
        exit 1
    fi

    echo "Starting emulator: $AVD_NAME"
    "$EMULATOR" -avd "$AVD_NAME" &

    echo "Waiting for emulator to boot..."
    "$ADB" wait-for-device

    # Wait for boot to complete
    while [ "$("$ADB" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" != "1" ]; do
        sleep 2
        echo "Still waiting for boot..."
    done

    DEVICE_ID=$("$ADB" devices | grep -E "emulator-[0-9]+" | head -1 | awk '{print $1}')
fi

echo "Using device: $DEVICE_ID"

if [ "$CLEAN_BUILD" = true ]; then
    echo "Cleaning build artifacts..."
    ./gradlew clean
fi

echo "Building Demo app..."
./gradlew :demo:assembleDebug

# Find the APK
APK_PATH="./demo/build/outputs/apk/debug/demo-debug.apk"
if [ ! -f "$APK_PATH" ]; then
    echo "APK not found at $APK_PATH"
    echo "Build may have failed."
    exit 1
fi

echo "Installing app to emulator..."
"$ADB" -s "$DEVICE_ID" install -r "$APK_PATH"

# Determine which activity to launch
if [ "$DEMO_TYPE" = "java" ]; then
    ACTIVITY=".JavaMainActivity"
    DEMO_LABEL="Java Demo"
else
    ACTIVITY=".MainActivity"
    DEMO_LABEL="Kotlin Demo"
fi

echo "Launching $DEMO_LABEL app..."
"$ADB" -s "$DEVICE_ID" shell am start -n com.datagrail.consent.demo/$ACTIVITY

echo "$DEMO_LABEL app launched successfully!"
