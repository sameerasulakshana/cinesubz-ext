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
        val items = doc.select("div.display-item, div.flw-item, div.module-item, article.item").mapNotNull { it.toSearchResponse() }
        return newHomePageResponse(request.name, items)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val doc = app.get("$mainUrl/?s=$query").document
        val results = doc.select("div.display-item, div.flw-item, div.module-item, article.item").mapNotNull { it.toSearchResponse() }
        return results
    }

    override suspend fun load(url: String): LoadResponse? {
        val doc = app.get(url).document

        val titleEl = doc.selectFirst("h1, h2.film-title, .title, .film-title, .details-title")
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

        val plot = doc.select("div.description, .plot, .film-description, div.details-desc, div.row-line:contains(Overview)").text().trim()

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
            return newMovieLoadResponse(title, url, TvType.Movie, url) {
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
        val doc = app.get(data).document

        val playerOptions = doc.select("li.zetaflix_player_option")
        if (playerOptions.isEmpty()) {
            val urls = if (data.startsWith("[") && data.endsWith("]")) {
                data.removePrefix("[").removeSuffix("]").split(",")
                    .map { it.trim().removeSurrounding("\"") }
                    .filter { it.isNotBlank() }
            } else {
                listOf(data)
            }
            for (url in urls) {
                if (!url.startsWith("http")) continue
                try {
                    val dlDoc = app.get(url).document
                    val dlBtn = dlDoc.select("a#link").first() ?: dlDoc.select("div.wait-done a").first()
                    if (dlBtn != null) {
                        val rawUrl = dlBtn.attr("href").trim()
                        val transformed = transformVideoUrl(rawUrl)
                        if (transformed.isNotBlank()) loadExtractor(transformed, subtitleCallback, callback)
                    }
                } catch (_: Exception) { }
            }
            return true
        }

        for (option in playerOptions) {
            val post = option.attr("data-post")
            val nume = option.attr("data-nume")
            val ptype = option.attr("data-type")
            if (post.isBlank() || nume.isBlank() || nume == "trailer") continue
            try {
                val resp = app.post(
                    "$mainUrl/wp-admin/admin-ajax.php",
                    data = mapOf("action" to "zeta_player_ajax", "post" to post, "nume" to nume, "type" to ptype),
                    headers = mapOf("X-Requested-With" to "XMLHttpRequest"),
                    referer = data
                )
                val json = resp.text
                if (json.isBlank() || json == "0") continue

                val embedUrl = Regex("\"embed_url\"\\s*:\\s*\"([^\"]+)\"").find(json)
                    ?.groupValues?.get(1)?.replace("\\/", "/") ?: continue
                val linkType = Regex("\"type\"\\s*:\\s*\"([^\"]+)\"").find(json)
                    ?.groupValues?.get(1) ?: ""

                if (linkType == "mp4") {
                    fetchMp4Qualities(embedUrl, nume, subtitleCallback, callback)
                } else if (linkType == "iframe") {
                    val iframeSrc = Regex("""src=["']([^"']+)["']""").find(embedUrl)?.groupValues?.get(1)
                    if (iframeSrc != null) loadExtractor(iframeSrc, subtitleCallback, callback)
                } else if (embedUrl.startsWith("http")) {
                    loadExtractor(embedUrl, subtitleCallback, callback)
                }
            } catch (_: Exception) { }
        }
        return true
    }

    private suspend fun fetchMp4Qualities(
        embedUrl: String, nume: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val playerHtml = app.get(embedUrl, referer = mainUrl).text
            val matches = Regex("""html":"([^"]*)","url":"([^"]+\.mp4\?play=true)""").findAll(playerHtml).toList()
            if (matches.isNotEmpty()) {
                for (m in matches) {
                    val qName = m.groupValues[1]
                    val qUrl = m.groupValues[2].replace("\\/", "/")
                    val quality = when {
                        qName.contains("1080") -> 1080
                        qName.contains("720") -> 720
                        qName.contains("480") -> 480
                        qName.contains("360") -> 360
                        else -> Qualities.Unknown.value
                    }
                    callback.invoke(
                        ExtractorLink(
                            source = name,
                            name = "$name $nume $qName",
                            url = qUrl,
                            referer = mainUrl,
                            quality = quality,
                            type = ExtractorLinkType.VIDEO
                        )
                    )
                }
            } else {
                callback.invoke(
                    ExtractorLink(
                        source = name,
                        name = "$name Player $nume",
                        url = "$embedUrl?play=true",
                        referer = mainUrl,
                        quality = Qualities.Unknown.value,
                        type = ExtractorLinkType.VIDEO
                    )
                )
            }
        } catch (_: Exception) { }
    }

    private fun transformVideoUrl(url: String): String {
        if (url.isBlank()) return ""
        var transformed = url
            .replace("https://google.com/server11/1:/", "https://bot3.sonic-cloud.online/server1/")
            .replace("https://google.com/server12/1:/", "https://bot3.sonic-cloud.online/server1/")
            .replace("https://google.com/server13/1:/", "https://bot3.sonic-cloud.online/server1/")
            .replace("https://google.com/server21/1:/", "https://bot3.sonic-cloud.online/server2/")
            .replace("https://google.com/server22/1:/", "https://bot3.sonic-cloud.online/server2/")
            .replace("https://google.com/server23/1:/", "https://bot3.sonic-cloud.online/server2/")
            .replace("https://google.com/server3/1:/", "https://bot3.sonic-cloud.online/server3/")
            .replace("https://google.com/server4/1:/", "https://bot3.sonic-cloud.online/server4/")
            .replace("https://google.com/server5/1:/", "https://bot3.sonic-cloud.online/server5/")
            .replace("https://google.com/server6/", "https://bot3.sonic-cloud.online/server6/")
        if (transformed == url) return ""
        if (transformed.contains(".mp4") && !transformed.contains("?bot=")) {
            transformed = transformed.replaceFirst(".mp4", "?ext=mp4")
        }
        if (transformed.contains(".mkv") && !transformed.contains("?bot=")) {
            transformed = transformed.replaceFirst(".mkv", "?ext=mkv")
        }
        return transformed
    }

    private fun Element.toSearchResponse(): SearchResponse? {
        val isDisplayItem = select("div.item-box").isNotEmpty()

        val href: String
        var title: String
        var poster: String
        var tvType: TvType = TvType.Movie

        if (isDisplayItem) {
            val linkEl = select("div.item-box > a").first() ?: return null
            href = linkEl.attr("href").trim()
            if (href.isBlank()) return null

            title = linkEl.attr("title").trim()
            if (title.isBlank()) title = select("div.item-desc-title h3").text().trim()
            if (title.isBlank()) title = select("img.thumb").attr("alt").trim()

            val imgEl = select("img.thumb.mli-thumb").first()
            poster = if (imgEl != null) {
                val s = imgEl.attr("src")
                if (s.isNotBlank()) s else imgEl.attr("data-original")
            } else ""

            val ptype = linkEl.attr("data-ptype")
            tvType = if (ptype == "tvshows" || href.contains("/tvshows/")) TvType.TvSeries else TvType.Movie
        } else {
            val linkEl = when {
                tagName() == "a" -> this
                select("a").isNotEmpty() -> select("a").first()
                else -> return null
            }
            href = linkEl?.attr("href")?.trim() ?: return null
            if (href.isBlank()) return null

            title = linkEl.attr("title").ifEmpty { linkEl.select("h3, .title, .film-name").text() }.trim()
            if (title.isBlank()) title = select("img").attr("alt").trim()
            if (title.isBlank()) title = linkEl.text().trim()

            val imgEl = select("img").first()
            poster = if (imgEl != null) {
                val s = imgEl.attr("src")
                if (s.isNotBlank()) s else imgEl.attr("data-src").ifEmpty { imgEl.attr("data-original") }
            } else ""

            val badge = select(".badge, .quality, .badge-quality, .badge-season, .badge-episode").text()
            val isTv = href.contains("/tvshows/") ||
                badge.contains("S0", ignoreCase = true) ||
                badge.contains("Season", ignoreCase = true) ||
                badge.contains("Complete", ignoreCase = true) ||
                badge.contains("EP", ignoreCase = true)
            if (isTv) tvType = TvType.TvSeries
        }

        if (title.isBlank()) return null
        title = title.replace("| සිංහල උපසිරැසි සමඟ", "").trim()
        if (title.isBlank()) return null

        return if (tvType == TvType.TvSeries) {
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
