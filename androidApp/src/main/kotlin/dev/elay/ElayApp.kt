package dev.elay

import android.app.Application
import dev.elay.di.AppGraph
import dev.elay.di.createAndroidAppGraph
import dev.elay.di.createAppScope
import dev.elay.util.ElayLogConfig

class ElayApp : Application() {
    lateinit var appGraph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        // Stage5 §A MASVS checklist: the one production call site that flips ElayLog on — only
        // for debug builds, so release builds emit nothing (dev.elay.util.ElayLog).
        ElayLogConfig.enabled = BuildConfig.DEBUG
        appGraph =
            createAndroidAppGraph(
                context = applicationContext,
                supabaseUrl = BuildConfig.SUPABASE_URL,
                supabaseAnonKey = BuildConfig.SUPABASE_ANON_KEY,
                appScope = createAppScope(),
            )
    }
}
