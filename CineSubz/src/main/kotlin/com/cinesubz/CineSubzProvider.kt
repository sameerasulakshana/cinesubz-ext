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
        return doc.select("div.flw-item, div.module-item, article.item").mapNotNull { it.toSearchResponse() }
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url).document

        val titleEl = doc.selectFirst("h1, h2.film-title, .title, .film-title")
        val title = titleEl?.text()?.trim()
            ?.replace("| සිංහල උපසිරැසි සමඟ", "")?.trim()
            ?: throw ErrorLoadingException("Title not found")

        val poster = doc.select("div.poster img, img.poster, .film-poster img, .poster img").attr("src").ifEmpty {
            doc.select("meta[property=og:image]").attr("content")
        }

        val year = Regex("\\d{4}").find(
            doc.select("span.year, .meta-year, div.row-line:contains(Year) a, div.row-line:contains(Released) a, a[href*=/release/]").text()
        )?.value?.toIntOrNull()

        val rating = Regex("[\\d.]+").find(
            doc.select("span.imdb, .imdb-rating, .rating, div.row-line:contains(IMDB)").text()
        )?.value?.toFloatOrNull()?.let { (it * 10).toInt() }

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

            val seasonContainers = doc.select("div.season-tabs div.tab-content > div, div.season-list > div, div#seasons > div")
            if (seasonContainers.isNotEmpty()) {
                seasonContainers.forEach { container ->
                    val seasonNum = Regex("""S(\d+)""", RegexOption.IGNORE_CASE).find(
                        container.id() + container.className() + container.select("h3, .season-title").text()
                    )?.groupValues?.get(1)?.toIntOrNull() ?: 1

                    container.select("a[href*=/episodes/], li a[href*=/episodes/]").forEach { epEl ->
                        val epLink = epEl.attr("href")
                        val epTitle = epEl.select("span.title, .ep-title, .name").text().ifEmpty { epEl.text() }
                        val epNum = Regex("""(?:EP|Episode|E|ep)[.\s]*(\d+)""", RegexOption.IGNORE_CASE).find(epTitle)?.groupValues?.get(1)?.toIntOrNull()
                        if (epLink.isNotBlank()) {
                            episodes.add(newEpisode(fixUrl(epLink)) {
                                this.name = epTitle.trim()
                                this.season = seasonNum
                                this.episode = epNum ?: (episodes.count { it.season == seasonNum } + 1)
                            })
                        }
                    }
                }
            } else {
                doc.select("a[href*=/episodes/]").forEach { epEl ->
                    val epLink = epEl.attr("href")
                    val epText = epEl.text()
                    val seasonMatch = Regex("""S(\d+)""", RegexOption.IGNORE_CASE).find(epLink)
                    val epNumMatch = Regex("""(?:EP|Episode|E|ep)[.\s]*(\d+)""", RegexOption.IGNORE_CASE).find(epText + " " + epLink)
                    val season = seasonMatch?.groupValues?.get(1)?.toIntOrNull() ?: 1
                    val epNum = epNumMatch?.groupValues?.get(1)?.toIntOrNull()
                    if (epLink.isNotBlank()) {
                        episodes.add(newEpisode(fixUrl(epLink)) {
                            this.name = epText.trim()
                            this.season = season
                            this.episode = epNum ?: (episodes.size + 1)
                        })
                    }
                }
            }

            if (episodes.isEmpty()) {
                val seasonLinks = doc.select("div.dropdown-menu a, ul.dropdown-menu a, div.season-tabs a")
                seasonLinks.forEach { seasonEl ->
                    val seasonNum = Regex("\\d+").find(seasonEl.text())?.value?.toIntOrNull() ?: 1
                    doc.select("a[href*=/episodes/]").forEach { epEl ->
                        val epLink = epEl.attr("href")
                        val epText = epEl.text()
                        if (epLink.isNotBlank()) {
                            episodes.add(newEpisode(fixUrl(epLink)) {
                                this.name = epText.trim()
                                this.season = seasonNum
                                this.episode = Regex("""(\d+)""").find(epText)?.value?.toIntOrNull() ?: (episodes.size + 1)
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
                this.rating = rating
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
                this.rating = rating
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
            val qual = when {
                data.contains("1080") || data.contains("4K") || resp.text().contains("1080") -> Qualities.FourK.value
                data.contains("720") || resp.text().contains("720") -> Qualities.720.value
                data.contains("480") || resp.text().contains("480") -> Qualities.480.value
                else -> Qualities.Unknown.value
            }
            callback.invoke(
                ExtractorLink(
                    source = name,
                    name = "Direct",
                    url = fixUrl(directLink),
                    referer = "$mainUrl/",
                    quality = qual
                )
            )
            return true
        }

        val iframes = doc.select("iframe[src]")
        for (iframe in iframes) {
            val src = iframe.attr("src")
            if (src.isNotBlank()) {
                loadExtractor(src, subtitleCallback, callback)
            }
        }

        val videoSources = doc.select("video source[src], source[src*=.mp4], source[src*=.m3u8]")
        for (source in videoSources) {
            val src = source.attr("src")
            if (src.isNotBlank()) {
                callback.invoke(
                    ExtractorLink(
                        source = name,
                        name = "Video",
                        url = fixUrl(src),
                        referer = "$mainUrl/",
                        quality = Qualities.Unknown.value
                    )
                )
            }
        }

        val downloadLinks = doc.select("a[href*=/zt-links/], a[href*=/api-]")
        for (link in downloadLinks) {
            val href = link.attr("href")
            if (href.isNotBlank()) {
                val qual = when {
                    link.text().contains("1080") || link.text().contains("4K") -> Qualities.FourK.value
                    link.text().contains("720") -> Qualities.720.value
                    link.text().contains("480") -> Qualities.480.value
                    else -> Qualities.Unknown.value
                }
                val dlResp = app.get(fixUrl(href))
                val dlDoc = dlResp.document
                val dlLink = dlDoc.select("a[href*=http]").attr("href")
                if (dlLink.isNotBlank() && dlLink != "#") {
                    callback.invoke(
                        ExtractorLink(
                            source = name,
                            name = "Download ${link.text().trim().take(40)}",
                            url = fixUrl(dlLink),
                            referer = "$mainUrl/",
                            quality = qual
                        )
                    )
                }
            }
        }

        return true
    }

    private fun Element.toSearchResponse(): SearchResponse? {
        val linkEl = when {
            tagName() == "a" -> this
            select("a").isNotEmpty() -> select("a").first()
            else -> return null
        }
        val href = linkEl.attr("href")
        if (href.isNullOrBlank()) return null

        val img = select("img").first()
        val poster = img?.attr("src")?.ifEmpty { img?.attr("data-src") } ?: ""
        val title = (img?.attr("alt") ?: select("h2, h3, .title, .name").text().ifEmpty { linkEl.text() }).trim()
        if (title.isNullOrBlank()) return null

        val badge = select(".badge, .quality, .badge-quality").text()
        val isTv = href.contains("/tvshows/") ||
            badge.contains("S0", ignoreCase = true) ||
            badge.contains("Season", ignoreCase = true) ||
            badge.contains("Complete", ignoreCase = true)

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
