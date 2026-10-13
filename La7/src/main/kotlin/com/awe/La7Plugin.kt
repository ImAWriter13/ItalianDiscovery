package com.awe

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class La7 : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(LA7Provider())
    }
}
