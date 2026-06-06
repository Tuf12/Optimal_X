# THEME.md

## Purpose

This file defines the visual theme system for OptimalX v2.

It covers both the dark and light themes, every color token with exact values, how tokens are used across the UI, and how to implement the theme system in Kotlin with Jetpack Compose.

Coding agents should use this file as the source of truth for all color decisions.
No color should be hardcoded anywhere in the app — everything goes through the token system defined here.

---

## Theme Options

OptimalX supports three theme settings:

| Setting | Behavior |
|---|---|
| Dark | Always uses the dark theme regardless of system setting |
| Light | Always uses the light theme regardless of system setting |
| System default | Follows the device system theme — switches automatically |

The default is **System default**.

### Where the setting lives
- Accessed via the three dot menu in the top bar of the Parent Folder Page
- Stored in `DataStore<Preferences>` so it persists across app restarts
- No app restart required — theme switches immediately when changed
- Key: `theme_preference` — values: `"dark"`, `"light"`, `"system"`

---

## Color Token System

All colors are defined as a custom `OptimalXColors` data class in Kotlin.
Two instances are created — one for dark, one for light.
The active instance is provided via `CompositionLocalProvider` and consumed anywhere in the UI.

No component should reference a raw hex color.
Every component should consume a token (e.g. `LocalOptimalXColors.current.surface`).

---

## Dark Theme Tokens

Background and surface:

| Token | Hex | Usage |
|---|---|---|
| background | #0e0e0f | Root screen background |
| surface | #161618 | Folder cards, bottom bar, editor background |
| surface2 | #1e1e21 | Search bar, toolbar buttons, input fields |
| pageBackground | #080809 | Outside the phone shell (not used in app directly) |

Borders:

| Token | Hex | Usage |
|---|---|---|
| border | #2a2a2e | All dividers, card borders, input borders |
| borderSoft | #2a2a2e | Same as border in dark theme |

Accent (electric lime):

| Token | Hex | Usage |
|---|---|---|
| accent | #c8fb5e | Primary action buttons, active states, cursor, pill borders, active dot |
| accentDim | rgba(200,251,94,0.10) | Eidos button background, active card background, pill background |
| accentBorder | rgba(200,251,94,0.25) | Eidos button border, active card border, accent pill border |

Text:

| Token | Hex | Usage |
|---|---|---|
| textPrimary | #f0f0ee | All primary text — folder names, note content, headings |
| textMid | #9a9a96 | Toolbar icons, secondary labels |
| textDim | #52524e | Placeholder text, metadata, timestamps, bar labels |

File type badges:

| Token | Hex | Usage |
|---|---|---|
| badgePdfText | #ff503c | PDF badge text |
| badgePdfBg | rgba(255,80,60,0.12) | PDF badge background |
| badgeDocText | #3c78ff | DOC badge text |
| badgeDocBg | rgba(60,120,255,0.12) | DOC badge background |
| badgeImgText | #c8fb5e | IMG badge text (same as accent) |
| badgeImgBg | rgba(200,251,94,0.12) | IMG badge background |

Eidos sheet:

| Token | Hex | Usage |
|---|---|---|
| sheetBackground | #121214 | Eidos bottom sheet background |
| sheetBorder | #2e2e32 | Eidos bottom sheet top border |
| messageBubbleUser | #1e1e21 | User message bubble background |
| messageBubbleEidos | gradient #181d11 → #141614 | Eidos message bubble background |
| messageBubbleEidosBorder | rgba(200,251,94,0.18) | Eidos message bubble border |

---

## Light Theme Tokens

Background and surface:

| Token | Hex | Usage |
|---|---|---|
| background | #f5f2ed | Root screen background |
| surface | #ede9e2 | Folder cards, bottom bar, editor background |
| surface2 | #e4dfd6 | Search bar, toolbar buttons, input fields |
| pageBackground | #dedad4 | Outside the phone shell (not used in app directly) |

Borders:

| Token | Hex | Usage |
|---|---|---|
| border | #d4cfc7 | All dividers, card borders, input borders |
| borderSoft | #e0dbd3 | Softer card borders |

Accent (forest green):

| Token | Hex | Usage |
|---|---|---|
| accent | #4a6741 | Primary action buttons, active states, cursor, pill borders, active dot |
| accentDim | rgba(74,103,65,0.10) | Eidos button background, active card background, pill background |
| accentBorder | rgba(74,103,65,0.25) | Eidos button border, active card border, accent pill border |

Text:

| Token | Hex | Usage |
|---|---|---|
| textPrimary | #1c1c1a | All primary text — folder names, note content, headings |
| textMid | #6b6860 | Toolbar icons, secondary labels |
| textDim | #a8a49e | Placeholder text, metadata, timestamps, bar labels |

File type badges:

| Token | Hex | Usage |
|---|---|---|
| badgePdfText | #b84030 | PDF badge text |
| badgePdfBg | rgba(200,70,50,0.10) | PDF badge background |
| badgeDocText | #3a5ab8 | DOC badge text |
| badgeDocBg | rgba(50,90,200,0.10) | DOC badge background |
| badgeImgText | #4a6741 | IMG badge text (same as accent) |
| badgeImgBg | rgba(74,103,65,0.10) | IMG badge background |

Eidos sheet:

| Token | Hex | Usage |
|---|---|---|
| sheetBackground | #f0ece5 | Eidos bottom sheet background |
| sheetBorder | #d4cfc7 | Eidos bottom sheet top border |
| messageBubbleUser | #e4dfd6 | User message bubble background |
| messageBubbleEidos | gradient #edf0e9 → #e8ede3 | Eidos message bubble background |
| messageBubbleEidosBorder | rgba(74,103,65,0.15) | Eidos message bubble border |

---

## Typography

Fonts are the same in both themes. Only colors change.

| Role | Font | Weight | Usage |
|---|---|---|---|
| Display | Syne | 800 | Screen titles, folder names in header, Eidos name |
| Body | DM Sans | 300 / 400 / 500 | Note content, card names, general UI text |
| Mono | DM Mono | 400 / 500 | Timestamps, metadata, labels, toolbar size selector, status pills |

All three fonts are available via Google Fonts and can be loaded in Android using the `downloadable fonts` API or bundled directly in the app assets.

---

## Shadows and Elevation

### Dark theme
- Cards and surfaces use no drop shadow
- Depth is created with border colors and surface color stepping
- The Eidos bottom sheet uses: `box-shadow: 0 -16px 48px rgba(0,0,0,0.6)`

### Light theme
- Cards use a subtle drop shadow: `0 1px 3px rgba(0,0,0,0.05)`
- Cards have an inset highlight on the top edge: `0 1px 0 rgba(255,255,255,0.7) inset`
- Input fields use an inset shadow: `inset 0 1px 2px rgba(0,0,0,0.04)`
- The Eidos bottom sheet uses: `box-shadow: 0 -8px 32px rgba(0,0,0,0.10)`

In Compose, use `Modifier.shadow()` with the appropriate elevation per theme.

---

## Kotlin Implementation

### Step 1 — Define the color data class

```kotlin
data class OptimalXColors(
    val background: Color,
    val surface: Color,
    val surface2: Color,
    val border: Color,
    val borderSoft: Color,
    val accent: Color,
    val accentDim: Color,
    val accentBorder: Color,
    val textPrimary: Color,
    val textMid: Color,
    val textDim: Color,
    val badgePdfText: Color,
    val badgePdfBg: Color,
    val badgeDocText: Color,
    val badgeDocBg: Color,
    val badgeImgText: Color,
    val badgeImgBg: Color,
    val sheetBackground: Color,
    val sheetBorder: Color,
    val messageBubbleUser: Color,
    val messageBubbleEidosBorder: Color,
)
```

### Step 2 — Define both theme instances

```kotlin
val DarkColors = OptimalXColors(
    background = Color(0xFF0E0E0F),
    surface = Color(0xFF161618),
    surface2 = Color(0xFF1E1E21),
    border = Color(0xFF2A2A2E),
    borderSoft = Color(0xFF2A2A2E),
    accent = Color(0xFFC8FB5E),
    accentDim = Color(0x1AC8FB5E),
    accentBorder = Color(0x40C8FB5E),
    textPrimary = Color(0xFFF0F0EE),
    textMid = Color(0xFF9A9A96),
    textDim = Color(0xFF52524E),
    badgePdfText = Color(0xFFFF503C),
    badgePdfBg = Color(0x1FFF503C),
    badgeDocText = Color(0xFF3C78FF),
    badgeDocBg = Color(0x1F3C78FF),
    badgeImgText = Color(0xFFC8FB5E),
    badgeImgBg = Color(0x1AC8FB5E),
    sheetBackground = Color(0xFF121214),
    sheetBorder = Color(0xFF2E2E32),
    messageBubbleUser = Color(0xFF1E1E21),
    messageBubbleEidosBorder = Color(0x2EC8FB5E),
)

val LightColors = OptimalXColors(
    background = Color(0xFFF5F2ED),
    surface = Color(0xFFEDE9E2),
    surface2 = Color(0xFFE4DFD6),
    border = Color(0xFFD4CFC7),
    borderSoft = Color(0xFFE0DBD3),
    accent = Color(0xFF4A6741),
    accentDim = Color(0x1A4A6741),
    accentBorder = Color(0x404A6741),
    textPrimary = Color(0xFF1C1C1A),
    textMid = Color(0xFF6B6860),
    textDim = Color(0xFFA8A49E),
    badgePdfText = Color(0xFFB84030),
    badgePdfBg = Color(0x1AB84030),
    badgeDocText = Color(0xFF3A5AB8),
    badgeDocBg = Color(0x1A3A5AB8),
    badgeImgText = Color(0xFF4A6741),
    badgeImgBg = Color(0x1A4A6741),
    sheetBackground = Color(0xFFF0ECE5),
    sheetBorder = Color(0xFFD4CFC7),
    messageBubbleUser = Color(0xFFE4DFD6),
    messageBubbleEidosBorder = Color(0x264A6741),
)
```

### Step 3 — Create a CompositionLocal

```kotlin
val LocalOptimalXColors = staticCompositionLocalOf { DarkColors }
```

### Step 4 — Read the saved preference and provide the theme

```kotlin
@Composable
fun OptimalXTheme(
    themePreference: String, // "dark", "light", or "system"
    content: @Composable () -> Unit
) {
    val systemInDarkTheme = isSystemInDarkTheme()

    val colors = when (themePreference) {
        "dark" -> DarkColors
        "light" -> LightColors
        else -> if (systemInDarkTheme) DarkColors else LightColors
    }

    CompositionLocalProvider(LocalOptimalXColors provides colors) {
        content()
    }
}
```

### Step 5 — Consume in any composable

```kotlin
val colors = LocalOptimalXColors.current

Box(
    modifier = Modifier
        .background(colors.surface)
        .border(1.dp, colors.border, RoundedCornerShape(14.dp))
)
```

### Step 6 — Save and load the preference

```kotlin
// Save
suspend fun saveThemePreference(context: Context, value: String) {
    val dataStore = context.themeDataStore
    dataStore.edit { it[THEME_KEY] = value }
}

// Load (as a Flow)
fun getThemePreference(context: Context): Flow<String> {
    return context.themeDataStore.data.map { it[THEME_KEY] ?: "system" }
}
```

Collect this flow in the root composable and pass the value into `OptimalXTheme`.

---

## Settings Screen — Theme Section

The theme setting lives in the settings screen accessed via the three dot menu in the top bar.

### UI
- Section label: "Appearance"
- Three selectable options displayed as a segmented control or radio group:
    - Light
    - Dark
    - System default
- The currently active option is highlighted with the accent color
- Selection takes effect immediately — no save button needed

### Behavior
- Selecting an option writes to DataStore immediately
- The theme updates across the entire app in real time
- The setting persists across app restarts and device reboots

---

## Rules

- Never hardcode a color value anywhere in the UI layer
- Always consume from `LocalOptimalXColors.current`
- Both themes must be updated together if a new color token is added
- The accent color is intentionally different between themes — do not use the dark accent on the light theme or vice versa
- If a new screen or component is added, check both themes before shipping

---

## Summary

| Token group | Dark | Light |
|---|---|---|
| Background | Near black #0e0e0f | Warm parchment #f5f2ed |
| Surface | Dark grey #161618 | Warm off-white #ede9e2 |
| Accent | Electric lime #c8fb5e | Forest green #4a6741 |
| Text primary | Off white #f0f0ee | Near black #1c1c1a |
| Text dim | Warm grey #52524e | Warm grey #a8a49e |
| Sheet background | Deep black #121214 | Warm cream #f0ece5 |
| Theme storage | DataStore<Preferences> | DataStore<Preferences> |
| Default | System default | System default |
