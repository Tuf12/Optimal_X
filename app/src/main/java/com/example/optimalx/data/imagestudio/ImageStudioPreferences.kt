package com.example.optimalx.data.imagestudio

import android.content.Context
import com.example.optimalx.data.preferences.SettingsDefaults
import com.example.optimalx.data.preferences.SettingsKeys
import com.example.optimalx.data.preferences.settingsDataStore
import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

data class ImageStudioDefaults(
    val tier: ImageTier,
    val aspectRatio: ImageAspectRatio,
)

object ImageStudioPreferences {

    suspend fun readDefaults(context: Context): ImageStudioDefaults {
        val prefs = context.applicationContext.settingsDataStore.data.first()
        val tierWire = prefs[SettingsKeys.IMAGE_STUDIO_DEFAULT_TIER]
            ?: SettingsDefaults.IMAGE_STUDIO_DEFAULT_TIER
        val aspectWire = prefs[SettingsKeys.IMAGE_STUDIO_DEFAULT_ASPECT]
            ?: SettingsDefaults.IMAGE_STUDIO_DEFAULT_ASPECT
        return ImageStudioDefaults(
            tier = ImageTier.fromWire(tierWire) ?: ImageTier.DRAFT,
            aspectRatio = ImageAspectRatio.fromWire(aspectWire) ?: ImageAspectRatio.DEFAULT,
        )
    }

    fun observeDefaults(context: Context) =
        context.applicationContext.settingsDataStore.data.map { prefs ->
            val tierWire = prefs[SettingsKeys.IMAGE_STUDIO_DEFAULT_TIER]
                ?: SettingsDefaults.IMAGE_STUDIO_DEFAULT_TIER
            val aspectWire = prefs[SettingsKeys.IMAGE_STUDIO_DEFAULT_ASPECT]
                ?: SettingsDefaults.IMAGE_STUDIO_DEFAULT_ASPECT
            ImageStudioDefaults(
                tier = ImageTier.fromWire(tierWire) ?: ImageTier.DRAFT,
                aspectRatio = ImageAspectRatio.fromWire(aspectWire) ?: ImageAspectRatio.DEFAULT,
            )
        }

    suspend fun saveDefaultTier(context: Context, tier: ImageTier) {
        context.applicationContext.settingsDataStore.edit {
            it[SettingsKeys.IMAGE_STUDIO_DEFAULT_TIER] = tier.wireValue
        }
    }

    suspend fun saveDefaultAspectRatio(context: Context, aspectRatio: ImageAspectRatio) {
        context.applicationContext.settingsDataStore.edit {
            it[SettingsKeys.IMAGE_STUDIO_DEFAULT_ASPECT] = aspectRatio.wireValue
        }
    }
}
