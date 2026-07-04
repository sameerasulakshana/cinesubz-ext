package com.cinesubz

import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

@CloudstreamPlugin
class CineSubzPlugin : BasePlugin() {
    override fun load() {
        registerMainAPI(CineSubzProvider())
    }
}
