package com.eroticmv

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

class EroticMvProvider : MainAPI() {
    override var mainUrl = "https://eroticmv.com"
    override var name = "EroticMV"
    override val supportedTypes = setOf(TvType.Movie)
    override var lang = "en"
    override val hasMainPage = true

    override val mainPage = mainPageOf(
        Pair("/", "Latest Movies"),
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page == 1) mainUrl else "$mainUrl/page/$page/"
        val doc = app.get(url).document
        val items = doc.select("article.post-item.site__col").mapNotNull { it.toSearchResponse() }
        return newHomePageResponse(request.name, items)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val doc = app.get("$mainUrl/?s=$query").document
        return doc.select("article.post-item.site__col").mapNotNull { it.toSearchResponse() }
    }

    override suspend fun load(url: String): LoadResponse? {
        val doc = app.get(url).document

        val titleEl = doc.selectFirst("h1.entry-title, .entry-title")
        val title = titleEl?.text()?.trim() ?: doc.title().trim()
        if (title.isBlank()) return null

        val poster = doc.select("meta[property=og:image]").attr("content").ifEmpty {
            doc.select("link[rel=image_src]").attr("href")
        }

        val year = Regex("\\((\\d{4})\\)").find(title)?.groupValues?.get(1)?.toIntOrNull()

        val desc = doc.select("meta[name=description]").attr("content")

        val jsonLd = doc.select("script[type=application/ld+json]").firstOrNull()?.data()
        val tags = mutableListOf<String>()
        if (jsonLd != null) {
            val articleSection = Regex("""articleSection["\x27]*\s*:\s*\[([^\]]+)\]""").find(jsonLd)
            if (articleSection != null) {
                for (cat in articleSection.groupValues[1].split(",")) {
                    val tag = cat.trim().removeSurrounding("\"").trim()
                    if (tag.isNotBlank()) tags.add(tag)
                }
            }
        }

        val recommendations = doc.select("div.crp_related a, div.related-posts a, .recommendations a, .related a").mapNotNull { el ->
            val recUrl = el.attr("href").ifEmpty { return@mapNotNull null }
            val recTitle = el.attr("title").ifEmpty { el.text().trim() }
            if (recTitle.isBlank()) return@mapNotNull null
            val recPoster = el.select("img").attr("src").ifEmpty { el.select("img").attr("data-src") }
            newMovieSearchResponse(recTitle, fixUrl(recUrl), TvType.Movie) {
                this.posterUrl = fixUrl(recPoster)
            }
        }

        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            this.posterUrl = fixUrl(poster)
            this.year = year
            this.plot = desc
            this.tags = tags
            this.recommendations = recommendations
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val doc = app.get(data).document
        val fullHtml = doc.html()

        var videoUrl = doc.select("meta[property^=og:video]").attr("content")
        if (videoUrl.isBlank()) {
            val jsMatch = Regex("""single_video_url["\x27]\s*:\s*["\x27]([^"\x27]+)["\x27]""").find(fullHtml)
            videoUrl = jsMatch?.groupValues?.get(1) ?: ""
        }

        if (videoUrl.isNotBlank()) {
            if (videoUrl.endsWith(".m3u8")) {
                callback.invoke(
                    ExtractorLink(
                        source = name,
                        name = name,
                        url = videoUrl,
                        referer = mainUrl,
                        quality = Qualities.Unknown.value,
                        type = ExtractorLinkType.M3U8
                    )
                )
            } else {
                loadExtractor(videoUrl, subtitleCallback, callback)
            }
        }

        val jsUrl = Regex("""single_video_url["\x27]\s*:\s*["\x27]([^"\x27]+)["\x27]""").find(fullHtml)
        if (jsUrl != null) {
            val url = jsUrl.groupValues[1]
            if (url != videoUrl && url.isNotBlank()) {
                if (url.endsWith(".m3u8")) {
                    callback.invoke(
                        ExtractorLink(
                            source = name,
                            name = name,
                            url = url,
                            referer = mainUrl,
                            quality = Qualities.Unknown.value,
                            type = ExtractorLinkType.M3U8
                        )
                    )
                } else {
                    loadExtractor(url, subtitleCallback, callback)
                }
            }
        }

        return true
    }

    private fun org.jsoup.nodes.Element.toSearchResponse(): SearchResponse? {
        val linkEl = select("a.blog-img").first() ?: return null
        val href = linkEl.attr("href")
        if (href.isBlank()) return null

        val title = linkEl.attr("title").ifEmpty {
            select("h3.entry-title a").text().trim()
        }.ifEmpty { return null }.trim()

        val posterEl = select("img.blog-picture").first()
        val poster = posterEl?.attr("data-src")?.ifBlank { posterEl.attr("src") } ?: ""

        return newMovieSearchResponse(title, fixUrl(href), TvType.Movie) {
            this.posterUrl = fixUrl(poster)
        }
    }
}
