package dev.elay

import android.app.Application
import dev.elay.di.AppGraph
import dev.elay.di.createAndroidAppGraph
import dev.elay.di.createAppScope

class ElayApp : Application() {
    lateinit var appGraph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        appGraph =
            createAndroidAppGraph(
                context = applicationContext,
                supabaseUrl = BuildConfig.SUPABASE_URL,
                supabaseAnonKey = BuildConfig.SUPABASE_ANON_KEY,
                appScope = createAppScope(),
            )
    }
}
