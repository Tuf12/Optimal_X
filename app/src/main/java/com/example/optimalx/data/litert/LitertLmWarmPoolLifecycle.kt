package com.example.optimalx.data.litert

import com.example.optimalx.OptimalXApplication
import android.content.ComponentCallbacks2
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner

internal object LitertLmWarmPoolLifecycle {

    fun register(app: OptimalXApplication) {
        ProcessLifecycleOwner.get().lifecycle.addObserver(
            object : DefaultLifecycleObserver {
                override fun onStart(owner: LifecycleOwner) {
                    // Recover after a critical-memory unload. Minimize / widget
                    // hops do not unload, so this is a cheap no-op when already warm.
                    LitertLmWarmPoolCoordinator.onAppForegrounded(app)
                }
            },
        )
        app.registerComponentCallbacks(
            object : ComponentCallbacks2 {
                override fun onTrimMemory(level: Int) {
                    LitertLmWarmPoolCoordinator.onTrimMemory(app, level)
                }

                override fun onConfigurationChanged(newConfig: android.content.res.Configuration) = Unit

                // Do not unload Gemma here. onLowMemory() fires when the *system* is tight on
                // RAM — common after minimizing with a ~3.7 GB model resident — and is not the
                // same as TRIM_MEMORY_RUNNING_CRITICAL. Mapping it to critical caused unload on
                // every minimize, then a full reload on the next onStart foreground hop.
                override fun onLowMemory() = Unit
            },
        )
    }
}
