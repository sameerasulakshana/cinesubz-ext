package com.eroticmv

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class EroticMvPlugin : Plugin() {
    override fun load() {
        registerMainAPI(EroticMvProvider())
    }
}
