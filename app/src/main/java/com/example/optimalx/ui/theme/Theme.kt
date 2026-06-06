package com.example.optimalx.ui.theme

import android.content.Context
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

val Context.themeDataStore: DataStore<Preferences> by preferencesDataStore(name = "theme_preferences")

private val THEME_KEY = stringPreferencesKey("theme_preference")

suspend fun saveThemePreference(context: Context, value: String) {
    context.themeDataStore.edit { it[THEME_KEY] = value }
}

fun getThemePreference(context: Context): Flow<String> {
    return context.themeDataStore.data.map { it[THEME_KEY] ?: "system" }
}

@Composable
fun OptimalXTheme(
    themePreference: String = "system",
    content: @Composable () -> Unit
) {
    val systemInDarkTheme = isSystemInDarkTheme()

    val colors = when (themePreference) {
        "dark" -> DarkColors
        "light" -> LightColors
        else -> if (systemInDarkTheme) DarkColors else LightColors
    }

    val materialColorScheme = if (colors === DarkColors) {
        darkColorScheme(
            primary = colors.accent,
            onPrimary = colors.background,
            background = colors.background,
            onBackground = colors.textPrimary,
            surface = colors.surface,
            onSurface = colors.textPrimary,
            surfaceVariant = colors.surface2,
            onSurfaceVariant = colors.textMid,
            outline = colors.border,
        )
    } else {
        lightColorScheme(
            primary = colors.accent,
            onPrimary = colors.background,
            background = colors.background,
            onBackground = colors.textPrimary,
            surface = colors.surface,
            onSurface = colors.textPrimary,
            surfaceVariant = colors.surface2,
            onSurfaceVariant = colors.textMid,
            outline = colors.border,
        )
    }

    MaterialTheme(colorScheme = materialColorScheme) {
        CompositionLocalProvider(LocalOptimalXColors provides colors) {
            content()
        }
    }
}
