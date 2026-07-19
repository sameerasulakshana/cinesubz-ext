package com.eroticmv

import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

@CloudstreamPlugin
class EroticMvPlugin : BasePlugin() {
    override fun load() {
        registerMainAPI(EroticMvProvider())
    }
}
