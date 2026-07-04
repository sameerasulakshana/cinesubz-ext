package com.cinesubz

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addDuration
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

class CineSubzProvider : MainAPI() {
    override var mainUrl = "https://cinesubz.net"
    override var name = "CineSubz"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)
    override var lang = "si"
    override val hasMainPage = true

    override val mainPage = mainPageOf(
        Pair("/movies/", "Latest Movies"),
        Pair("/tvshows/", "Latest TV Shows"),
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page == 1) "$mainUrl${request.data}" else "$mainUrl${request.data}page/$page/"
        val doc = app.get(url).document
        val items = doc.select("div.flw-item, div.module-item, article.item").mapNotNull { it.toSearchResponse() }
        return newHomePageResponse(request.name, items)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val doc = app.get("$mainUrl/?s=$query").document
        return doc.select("div.flw-item, div.module-item, article.item, div.film-item").mapNotNull { it.toSearchResponse() }
    }

    override suspend fun load(url: String): LoadResponse? {
        val doc = app.get(url).document

        val titleEl = doc.selectFirst("h1, h2.film-title, .title, .film-title")
        val title = titleEl?.text()?.trim()
            ?.replace("| සිංහල උපසිරැසි සමඟ", "")?.trim()
            ?: return null

        val poster = doc.select("div.poster img, img.poster, .film-poster img, .poster img").attr("src").ifEmpty {
            doc.select("meta[property=og:image]").attr("content")
        }

        val year = Regex("\\d{4}").find(
            doc.select("span.year, .meta-year, div.row-line:contains(Year) a, div.row-line:contains(Released) a, a[href*=/release/]").text()
        )?.value?.toIntOrNull()

        val ratingFloat = Regex("[\\d.]+").find(
            doc.select("span.imdb, .imdb-rating, .rating, div.row-line:contains(IMDB)").text()
        )?.value?.toFloatOrNull()

        val duration = doc.select("span.duration, .meta-duration, div.row-line:contains(Duration)").text().trim()

        val plot = doc.select("div.description, .plot, .film-description, div.row-line:contains(Overview)").text().trim()

        val tags = doc.select("div.genres a, .genre a, div.row-line:contains(Genre) a, div.row-line:contains(Genres) a").map { it.text() }

        val cast = doc.select("div.cast a, a[href*=/cast/]").map { it.text() }.filter { it.length < 50 }

        val youtubeTrailer = doc.select("a[href*=youtube]:contains(Trailer), a:contains(Trailer)[href*=youtube]").attr("href")

        val isTv = url.contains("/tvshows/") || doc.select("div.season-tabs, div#seasons, div.season-list").isNotEmpty()

        val recommendations = doc.select("div.film_list-wrap div.flw-item a, div.related-items a, .recommendations a").mapNotNull { el ->
            val recUrl = el.attr("href").ifEmpty { el.parent()?.attr("href") }
            val recTitle = el.select("h3, .title, .name").text().ifEmpty { el.attr("title") }
            if (recUrl.isNullOrBlank() || recTitle.isNullOrBlank()) return@mapNotNull null
            val recPoster = el.select("img").attr("src").ifEmpty { el.select("img").attr("data-src") }
            newMovieSearchResponse(recTitle, fixUrl(recUrl), if (recUrl.contains("/tvshows/")) TvType.TvSeries else TvType.Movie) {
                this.posterUrl = fixUrl(recPoster)
            }
        }

        val imdbId = Regex("tt\\d+").find(doc.text())?.value

        if (isTv) {
            val episodes = mutableListOf<Episode>()

            val episodeLinks = doc.select("a[href*=/episodes/]")
            for (epEl in episodeLinks) {
                val epLink = epEl.attr("href")
                val epText = epEl.text()
                if (epLink.isNotBlank()) {
                    val seasonMatch = Regex("""[S|s](\d+)""").find(epLink)
                    val epNumMatch = Regex("""(?:EP|Episode|E|ep)[.\s]*(\d+)""", RegexOption.IGNORE_CASE).find(epText)
                    episodes.add(newEpisode(fixUrl(epLink)) {
                        this.name = epText.trim()
                        this.season = seasonMatch?.groupValues?.get(1)?.toIntOrNull() ?: 1
                        this.episode = epNumMatch?.groupValues?.get(1)?.toIntOrNull() ?: (episodes.size + 1)
                    })
                }
            }

            if (episodes.isEmpty()) {
                doc.select("div.dropdown-menu a").forEach { seasonEl ->
                    val seasonNum = Regex("\\d+").find(seasonEl.text())?.value?.toIntOrNull() ?: 1
                    doc.select("a[href*=/episodes/]").forEach { epEl ->
                        val epLink = epEl.attr("href")
                        if (epLink.isNotBlank()) {
                            episodes.add(newEpisode(fixUrl(epLink)) {
                                this.name = epEl.text().trim()
                                this.season = seasonNum
                                this.episode = episodes.size + 1
                            })
                        }
                    }
                }
            }

            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = fixUrl(poster)
                this.year = year
                this.plot = plot
                this.tags = tags
                this.recommendations = recommendations
                addDuration(duration)
                addActors(cast)
                if (youtubeTrailer.isNotBlank()) addTrailer(youtubeTrailer)
            }
        } else {
            val playerLinks = doc.select("a[href*=/zt-links/]").map { fixUrl(it.attr("href")) }

            return newMovieLoadResponse(title, url, TvType.Movie, playerLinks) {
                this.posterUrl = fixUrl(poster)
                this.year = year
                this.plot = plot
                this.tags = tags
                this.recommendations = recommendations
                addDuration(duration)
                addActors(cast)
                if (youtubeTrailer.isNotBlank()) addTrailer(youtubeTrailer)
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val resp = app.get(data)
        val doc = resp.document

        val directLink = doc.select("a[href*=http]").attr("href")
        if (directLink.isNotBlank() && directLink != "#" && !directLink.startsWith(mainUrl)) {
            loadExtractor(directLink, subtitleCallback, callback)
            return true
        }

        val iframes = doc.select("iframe[src]")
        for (iframe in iframes) {
            val src = iframe.attr("src")
            if (src.isNotBlank()) {
                loadExtractor(src, subtitleCallback, callback)
            }
        }

        val downloadLinks = doc.select("a[href*=/zt-links/], a[href*=/api-]")
        for (link in downloadLinks) {
            val href = link.attr("href")
            if (href.isNotBlank()) {
                val dlResp = app.get(fixUrl(href))
                val dlDoc = dlResp.document
                val dlLink = dlDoc.select("a[href*=http]").attr("href")
                if (dlLink.isNotBlank() && dlLink != "#") {
                    loadExtractor(dlLink, subtitleCallback, callback)
                }
            }
        }

        return true
    }

    private fun Element.toSearchResponse(): SearchResponse? {
        var linkA: org.jsoup.nodes.Element? = null
        var href: String? = null
        if (tagName() == "a") {
            linkA = this
            href = attr("href")
        } else {
            linkA = select("a").first()
            href = linkA?.attr("href")
        }
        if (href.isNullOrBlank()) return null

        val img = select("img").first()
        val poster = if (img != null) {
            val src = img.attr("src")
            if (src.isNotBlank()) src else img.attr("data-src")
        } else ""

        var title = linkA?.attr("title") ?: img?.attr("alt") ?: ""
        if (title.isBlank()) title = select("h2, h3, .title, .film-name, .name").text().trim()
        if (title.isBlank()) title = linkA?.text()?.trim() ?: ""
        if (title.isBlank()) return null
        title = title.replace("| සිංහල උපසිරැසි සමඟ", "").trim()

        val badge = select(".badge, .quality, .badge-quality, .badge-season, .badge-episode").text()
        val isTv = href.contains("/tvshows/") ||
            badge.contains("S0", ignoreCase = true) ||
            badge.contains("Season", ignoreCase = true) ||
            badge.contains("Complete", ignoreCase = true) ||
            badge.contains("TV", ignoreCase = true) ||
            badge.contains("EP", ignoreCase = true)

        return if (isTv) {
            newTvSeriesSearchResponse(title, fixUrl(href), TvType.TvSeries) {
                this.posterUrl = fixUrl(poster)
            }
        } else {
            newMovieSearchResponse(title, fixUrl(href), TvType.Movie, false) {
                this.posterUrl = fixUrl(poster)
            }
        }
    }
}
