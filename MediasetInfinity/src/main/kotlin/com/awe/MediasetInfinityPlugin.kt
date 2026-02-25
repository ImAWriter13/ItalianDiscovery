package com.awe

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class MediasetInfinityPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(MediasetInfinity())
    }
}
