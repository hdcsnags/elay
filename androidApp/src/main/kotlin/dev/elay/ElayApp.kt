package dev.elay

import android.app.Application
import dev.elay.di.AppGraph
import dev.elay.di.createAndroidAppGraph
import dev.elay.di.createAppScope
import dev.elay.ui.together.proposal.RsvpLinkConfig
import dev.elay.util.ElayLogConfig

class ElayApp : Application() {
    lateinit var appGraph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        // Stage5 §A MASVS checklist: the one production call site that flips ElayLog on — only
        // for debug builds, so release builds emit nothing (dev.elay.util.ElayLog).
        ElayLogConfig.enabled = BuildConfig.DEBUG
        // Stage 5 F11 (re-verify REOPENED it: the first attempt at this line silently failed
        // to apply — the wiring, not just the gate, is what the release assertion protects):
        // the RSVP share-link base ships from BuildConfig/local.properties, never the
        // commonMain default. The exact-line grep in assertReleaseEndpoints pins this.
        RsvpLinkConfig.base = BuildConfig.RSVP_LINK_BASE
        appGraph =
            createAndroidAppGraph(
                context = applicationContext,
                supabaseUrl = BuildConfig.SUPABASE_URL,
                supabaseAnonKey = BuildConfig.SUPABASE_ANON_KEY,
                appScope = createAppScope(),
            )
    }
}
