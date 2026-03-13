# Dark Mode Support & Color Customization

The DataGrail Consent SDK automatically supports both light and dark modes. The banner will adapt its colors based on the user's system theme preference.

## Default Colors

The SDK provides sensible defaults for both light and dark modes:

### Light Mode (Default)
- **Background**: White (`#FFFFFF`)
- **Primary Text**: Black (`#000000`)
- **Secondary Text**: Gray (`#666666`)
- **Button Background**: Blue (`#2196F3`)
- **Button Text**: White (`#FFFFFF`)
- **Links**: Blue (`#2196F3`)
- **Surface/Cards**: Light Gray (`#F5F5F5`)

### Dark Mode (Default)
- **Background**: Dark Gray (`#121212`)
- **Primary Text**: White (`#FFFFFF`)
- **Secondary Text**: Light Gray (`#B0B0B0`)
- **Button Background**: Blue (`#2196F3`)
- **Button Text**: White (`#FFFFFF`)
- **Links**: Light Blue (`#64B5F6`)
- **Surface/Cards**: Dark Surface (`#1E1E1E`)

## Customizing Colors

You can override any of the SDK's colors to match your app's branding by defining colors with the same name in your app's resource files.

### Available Color Resources

The following colors can be customized:

| Color Resource | Usage |
|---|---|
| `consent_background` | Dialog/banner background |
| `consent_text_primary` | Main text, headings, labels |
| `consent_text_secondary` | Descriptions, secondary text |
| `consent_button_background` | Button background color |
| `consent_button_text` | Button text color |
| `consent_link` | Link text color |
| `consent_surface` | Category cards, surface elements |

### How to Override

Create color resource files in your app's `res` directory:

#### Step 1: Create `app/src/main/res/values/colors.xml`

Override light mode colors:

```xml
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <!-- Customize light mode consent colors -->
    <color name="consent_background">#FFFFFF</color>
    <color name="consent_text_primary">#1A1A1A</color>
    <color name="consent_button_background">#FF6B35</color>
    <!-- Add other overrides as needed -->
</resources>
```

#### Step 2: Create `app/src/main/res/values-night/colors.xml`

Override dark mode colors:

```xml
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <!-- Customize dark mode consent colors -->
    <color name="consent_background">#1E1E1E</color>
    <color name="consent_text_primary">#FFFFFF</color>
    <color name="consent_button_background">#FF8C61</color>
    <!-- Add other overrides as needed -->
</resources>
```

### Best Practices

1. **Override Both Modes**: If you customize light mode colors, make sure to also customize dark mode colors to ensure good contrast and readability.

2. **Maintain Contrast**: Ensure sufficient contrast between text and backgrounds for accessibility:
   - Primary text should have at least 4.5:1 contrast ratio
   - Secondary text should have at least 3:1 contrast ratio
   - Use tools like [WebAIM Contrast Checker](https://webaim.org/resources/contrastchecker/)

3. **Test Both Modes**: Always test your app in both light and dark modes to ensure the consent banner looks good in both.

4. **Match Your Brand**: Customize the button and link colors to match your app's primary brand colors.

5. **Consider Context**: The consent banner appears as a modal overlay, so ensure colors work well against various app backgrounds.

## Examples

### Example 1: Brand Color Customization

To match a brand with orange as the primary color:

**`values/colors.xml`** (Light Mode):
```xml
<color name="consent_button_background">#FF6B35</color>
<color name="consent_link">#FF6B35</color>
```

**`values-night/colors.xml`** (Dark Mode):
```xml
<color name="consent_button_background">#FF8C61</color>
<color name="consent_link">#FF8C61</color>
```

### Example 2: High Contrast Mode

For better accessibility:

**`values/colors.xml`** (Light Mode):
```xml
<color name="consent_background">#FFFFFF</color>
<color name="consent_text_primary">#000000</color>
<color name="consent_text_secondary">#333333</color>
<color name="consent_surface">#F0F0F0</color>
```

**`values-night/colors.xml`** (Dark Mode):
```xml
<color name="consent_background">#000000</color>
<color name="consent_text_primary">#FFFFFF</color>
<color name="consent_text_secondary">#CCCCCC</color>
<color name="consent_surface">#1A1A1A</color>
```

### Example 3: Minimal Customization

If you only want to change button colors to match your brand:

**`values/colors.xml`**:
```xml
<color name="consent_button_background">#6200EE</color>
```

**`values-night/colors.xml`**:
```xml
<color name="consent_button_background">#BB86FC</color>
```

All other colors will use the SDK defaults.

## Testing Your Customization

### Using Android Studio

1. Open your app in Android Studio
2. Enable dark mode: Device Manager → Three dots → Settings → Dark theme
3. Run your app and trigger the consent banner
4. Toggle between light and dark mode to verify both themes

### Programmatically Toggle (for Testing)

You can temporarily override the system theme for testing:

```kotlin
import android.content.res.Configuration

// In your Activity/Fragment
fun testDarkMode() {
    val nightMode = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
    when (nightMode) {
        Configuration.UI_MODE_NIGHT_YES -> {
            // Currently in dark mode
        }
        Configuration.UI_MODE_NIGHT_NO -> {
            // Currently in light mode
        }
    }
}
```

### Using Command Line

Toggle dark mode via ADB:

```bash
# Enable dark mode
adb shell "cmd uimode night yes"

# Disable dark mode (light mode)
adb shell "cmd uimode night no"
```

## Troubleshooting

### Colors Not Changing

**Problem**: Custom colors aren't being applied.

**Solutions**:
- Ensure your color names exactly match the SDK's color names (e.g., `consent_background`, not `consentBackground`)
- Clean and rebuild your project: `./gradlew clean && ./gradlew build`
- Check that color files are in the correct location: `app/src/main/res/values/colors.xml`

### Dark Mode Not Working

**Problem**: Banner always shows light colors, even in dark mode.

**Solutions**:
- Ensure you have a `values-night/colors.xml` file with dark mode colors
- Check that your app's theme allows dark mode (not forcing light mode)
- Verify the device/emulator has dark mode enabled in system settings

### Poor Contrast

**Problem**: Text is hard to read in dark/light mode.

**Solutions**:
- Use a contrast checker tool to verify 4.5:1 ratio for text
- Test on actual devices (emulator displays may differ)
- Consider using Material Design color guidelines for accessible color palettes

## Need Help?

If you have questions about customizing the consent banner colors:
- Check the [main README](README.md) for general SDK documentation
- Review the SDK's default colors in `library/src/main/res/values/colors.xml`
- Contact DataGrail support for assistance
