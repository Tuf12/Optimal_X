package com.example.optimalx.data.litert

import android.content.ComponentCallbacks2
import android.util.Log
import com.example.optimalx.OptimalXApplication
import kotlinx.coroutines.launch

/**
 * Keeps the LiteRT-LM engine warm for the process lifetime while local chat or
 * local scribe is enabled. Minimize, recents, and hops to the home-screen widget
 * must not unload — [ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN] is numerically
 * higher than [ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL] but is only
 * "the UI is no longer visible," not an OOM warning.
 *
 * Unload happens when both local features are off, the process dies, or the OS
 * reports [ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL] while the app is
 * still in the running/foreground state. [ComponentCallbacks2.onLowMemory] is
 * intentionally ignored — it fires on minimize when Gemma holds ~3.7 GB.
 */
object LitertLmWarmPoolCoordinator {

    private const val TAG = "OptimalX.LitertLmWarm"

    fun onAppForegrounded(app: OptimalXApplication) {
        app.eidosSendScope.launch {
            applyLitertEngineWarmState(app)
        }
    }

    fun onTrimMemory(app: OptimalXApplication, level: Int) {
        if (!shouldReleaseLitertEngineOnTrimMemory(level)) return
        Log.w(TAG, "Releasing LiteRT-LM engine under critical memory (level=$level)")
        app.eidosSendScope.launch {
            if (!shouldKeepLitertEngineWarm(app)) return@launch
            app.litertLmEngineHolder.releaseIfNotInUse()
        }
    }
}

/**
 * Only [ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL] means the process is
 * still in the foreground/running and the OS is about to kill it.
 *
 * Do **not** treat later LRU levels as more severe: [ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN]
 * (20) fires on every minimize / widget hop; BACKGROUND / MODERATE / COMPLETE
 * fire while the app is still in recents.
 */
internal fun shouldReleaseLitertEngineOnTrimMemory(level: Int): Boolean {
    return level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL
}
