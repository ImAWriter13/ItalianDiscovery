package com.awe

import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SeasonData
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.fixUrl
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.json.JSONObject
import org.jsoup.nodes.Element

class RaiPlay : MainAPI() {
    override var mainUrl = "https://www.raiplay.it"
    override var name = "RaiPlay"
    override var lang = "it"
    override val hasMainPage = true
    override var supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    private val seasonRegex = Regex("""^Stagione\s+(\d{1,2})$""", RegexOption.IGNORE_CASE)

    private val clipTextKeywords = listOf("sanremo")
    private val clipCategories = listOf("calcio")

    private fun JSONObject.categoryNames(): List<String> {
        val out = mutableListOf<String>()
        listOf("typology", "label").forEach { key ->
            optString(key).takeIf { it.isNotBlank() }?.let { out += it }
        }
        listOf("typologies", "genres", "subgenres").forEach { key ->
            val arr = optJSONArray(key) ?: return@forEach
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                listOf("name", "nome").forEach { f ->
                    o.optString(f).takeIf { it.isNotBlank() }?.let { out += it }
                }
            }
        }
        return out
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse =
        coroutineScope {
            val doc = app.get("$mainUrl/").document

            val rows = doc.select("section.card-list")
                .filter { it.attr("data-functional-type") != "UserService" }
                .map { section ->
                    async {
                        val strip = section
                            .selectFirst("rai-dynamic-strip[type=lazy-strip]")
                            ?.attr("strip")
                        if (!strip.isNullOrBlank()) loadStrip(strip) else parseInlineSection(section)
                    }
                }
                .awaitAll()
                .filterNotNull()

            newHomePageResponse(rows, hasNext = false)
        }
    private fun parseInlineSection(section: Element): HomePageList? {
        val name = section.selectFirst("h2.card-list__title")?.text()?.trim().orEmpty()
        if (name.isBlank()) return null
        val items = section.select("div[data-type=program]").mapNotNull { it.toSearchResult() }
        return if (items.isEmpty()) null else HomePageList(name, items)
    }

    private suspend fun loadStrip(path: String): HomePageList? {
        return try {
            val json = JSONObject(app.get(fixUrl(path)).text)
            val name = json.optJSONObject("header")?.optString("label").orEmpty()
                .ifBlank { json.optString("name") }
            val contents = json.optJSONArray("contents") ?: return null
            val items = (0 until contents.length())
                .mapNotNull { contents.optJSONObject(it)?.toSearchResultFromJson() }
            if (name.isBlank() || items.isEmpty()) null else HomePageList(name, items)
        } catch (e: Exception) {
            null
        }
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val href = selectFirst("a.card-item__link")?.attr("href").orEmpty()
        if (href.isBlank()) return null
        val img = selectFirst("img")
        val title = img?.attr("alt")?.removeSuffix(" - RaiPlay")?.trim().orEmpty()
        if (title.isBlank()) return null
        return newTvSeriesSearchResponse(title, fixUrl(href), TvType.TvSeries) {
            this.posterUrl = img?.attr("src")?.let { fixUrl(it) }
        }
    }

    private fun JSONObject.toSearchResultFromJson(): SearchResponse? {
        if (!optString("type").contains("Programma")) return null
        val link = optString("weblink").ifBlank { optString("path_id").removeSuffix(".json") }
        val title = optString("name").trim()
        if (link.isBlank() || title.isBlank()) return null

        return newTvSeriesSearchResponse(title, fixUrl(link), TvType.TvSeries) {
            this.posterUrl = optJSONObject("images")
                .firstImage("portrait43", "portrait", "square", "landscape43", "landscape")
        }
    }
    override suspend fun search(query: String): List<SearchResponse>? {
        val q = query.trim().replace(Regex("\\s+"), " ")
        if (q.length < 2) return emptyList() //site won't search under 2 chars

        return try {
            val res = app.post(
                "$mainUrl/atomatic/raiplay-search-service/api/v1/msearch",
                headers = mapOf("X-Caller-Version" to ""),
                json = mapOf(
                    "templateIn" to "6470a982e4e0301afe1f81f1",
                    "templateOut" to "6516ac5d40da6c377b151642",
                    "params" to mapOf("param" to q)
                )
            )

            val cards = JSONObject(res.text)
                .optJSONObject("agg")
                ?.optJSONObject("titoli")
                ?.optJSONArray("cards")
                ?: return emptyList()

            (0 until cards.length())
                .mapNotNull { cards.optJSONObject(it)?.toSearchResultFromSearch() }
                .distinctBy { it.url }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun JSONObject.toSearchResultFromSearch(): SearchResponse? {
        if (optString("tipo") != "Programma") return null
        val link = optString("url").ifBlank { optString("path_id").removeSuffix(".json") }
        val title = optString("titolo").trim()
        if (link.isBlank() || title.isBlank()) return null

        return newTvSeriesSearchResponse(title, fixUrl(link), TvType.TvSeries) {
            this.posterUrl = optString("immagine").takeIf { it.isNotBlank() }?.let { fixUrl(it) }
        }
    }

    private data class EpisodeSet(val season: Int?, val path: String)
    private data class ClipGroup(val name: String, val items: List<JSONObject>)

    override suspend fun load(url: String): LoadResponse? = coroutineScope {
        val base = url.substringBefore("?")
            .removeSuffix("/").removeSuffix(".html").removeSuffix(".json")
        val root = JSONObject(app.get("$base.json").text)
        val info = root.optJSONObject("program_info") ?: return@coroutineScope null

        val title = info.optString("name").ifBlank { root.optString("name") }
        val allClips = clipTextKeywords.any {
            title.contains(it, ignoreCase = true) || base.contains(it, ignoreCase = true)
        } || info.categoryNames().any { cat ->
            clipCategories.any { cat.equals(it, ignoreCase = true) }
        }

        var seasonNames: List<SeasonData>? = null
        val episodes: List<Episode>

        if (allClips) {
            val groups = collectClipGroups(root)
            episodes = groups.flatMapIndexed { gi, g ->
                g.items.mapIndexedNotNull { ei, item -> item.toEpisode(gi + 1, ei + 1) }
            }
            seasonNames = groups.mapIndexed { gi, g -> SeasonData(gi + 1, g.name) }
        } else {
            episodes = root.episodeSets()
                .map { s ->
                    async {
                        try {
                            loadSet(s)
                        } catch (e: Exception) {
                            emptyList()
                        }
                    }
                }
                .awaitAll()
                .flatten()
        }

        if (episodes.isEmpty()) return@coroutineScope null

        val images = info.optJSONObject("images")
        val actors = info.optString("actors").split(",").map { it.trim() }.filter { it.isNotBlank() }
        val tags = info.optJSONArray("genres")?.let { g ->
            (0 until g.length()).mapNotNull { g.optJSONObject(it)?.optString("name") }
                .filter { it.isNotBlank() }
        }.orEmpty()

        newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
            this.posterUrl = images.firstImage("portrait43", "portrait", "square", "landscape43")
            this.backgroundPosterUrl = images.firstImage("landscape", "landscape43")
            this.year = info.optString("year").toIntOrNull()
            this.plot = info.optString("description").ifBlank { info.optString("vanity") }
            this.tags = tags
            this.seasonNames = seasonNames
            addActors(actors)
        }
    }
    private fun JSONObject.episodeSets(): List<EpisodeSet> {
        val blocks = optJSONArray("blocks") ?: return emptyList()
        val skip = listOf("extra", "highlight", "riassunt")
        val out = mutableListOf<EpisodeSet>()
        for (i in 0 until blocks.length()) {
            val block = blocks.optJSONObject(i) ?: continue
            if (skip.any { block.optString("name").contains(it, ignoreCase = true) }) continue
            val sets = block.optJSONArray("sets") ?: continue
            for (j in 0 until sets.length()) {
                val s = sets.optJSONObject(j) ?: continue
                if (s.optString("type") != "RaiPlay Multimedia Set") continue
                val path = s.optString("path_id")
                if (path.isBlank()) continue
                val season = seasonRegex.find(s.optString("name").trim())
                    ?.groupValues?.get(1)?.toIntOrNull()
                out += EpisodeSet(season, path)
            }
        }
        return out.sortedBy { it.season ?: Int.MAX_VALUE }
    }

    private suspend fun loadSet(set: EpisodeSet): List<Episode> {
        val items = JSONObject(app.get(fixUrl(set.path)).text).optJSONArray("items")
            ?: return emptyList()
        return (0 until items.length()).mapNotNull { i ->
            val item = items.optJSONObject(i) ?: return@mapNotNull null
            if (item.optString("type") != "RaiPlay Video Item") return@mapNotNull null
            item.toEpisode(
                season = item.optString("season").toIntOrNull() ?: set.season,
                number = item.optString("episode").toIntOrNull()
            )
        }
    }

    private fun JSONObject.toEpisode(season: Int?, number: Int?): Episode? {
        val videoUrl = optString("video_url").ifBlank { return null }
        val title = optString("episode_title").ifBlank { optString("toptitle") }
            .ifBlank { optString("name") }
        val parts = optString("duration").split(":").mapNotNull { it.toIntOrNull() }
        val minutes = if (parts.size == 3) parts[0] * 60 + parts[1] else null

        // path_id for subtitles
        val data = JSONObject()
            .put("v", videoUrl)
            .put("j", optString("path_id"))
            .toString()

        return newEpisode(data) {
            this.name = title
            this.season = season
            this.episode = number
            this.description = optString("description").takeIf { it.isNotBlank() }
            this.runTime = minutes
            this.posterUrl = optJSONObject("images").firstImage("landscape", "landscape43")
        }
    }

    private fun joinLabel(vararg parts: String): String =
        parts.map { it.trim() }.filter { it.isNotBlank() }.distinct().joinToString(" - ")

    private suspend fun collectClipGroups(root: JSONObject): List<ClipGroup> = coroutineScope {
        val blocks = root.optJSONArray("blocks") ?: return@coroutineScope emptyList()
        val jobs = mutableListOf<Deferred<List<ClipGroup>>>()
        for (i in 0 until blocks.length()) {
            val block = blocks.optJSONObject(i) ?: continue
            val sets = block.optJSONArray("sets") ?: continue
            for (j in 0 until sets.length()) {
                val set = sets.optJSONObject(j) ?: continue
                val path = set.optString("path_id")
                if (path.isBlank()) continue
                val label = joinLabel(block.optString("name"), set.optString("name"))
                jobs += async { collectFromPath(path, label, 0) }
            }
        }
        jobs.awaitAll().flatten()
    }

    private suspend fun collectFromPath(path: String, label: String, depth: Int): List<ClipGroup> {
        val json = try {
            JSONObject(app.get(fixUrl(path)).text)
        } catch (e: Exception) {
            return emptyList()
        }
        val items = json.optJSONArray("items") ?: return emptyList()

        val videos = mutableListOf<JSONObject>()
        val nested = mutableListOf<ClipGroup>()
        for (k in 0 until items.length()) {
            val item = items.optJSONObject(k) ?: continue
            if (item.optString("type") == "RaiPlay Video Item") {
                videos += item
                continue
            }
            val sub = item.optString("path_id")
            if (depth < 2 && sub.endsWith(".json")) {
                val subName = item.optString("name").ifBlank { item.optString("toptitle") }
                nested += collectFromPath(sub, joinLabel(label, subName), depth + 1)
            }
        }

        return (if (videos.isNotEmpty()) listOf(ClipGroup(label, videos)) else emptyList()) + nested
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val obj = runCatching { JSONObject(data) }.getOrNull()
        val videoUrl = obj?.optString("v")?.takeIf { it.isNotBlank() } ?: data
        val jsonPath = obj?.optString("j").orEmpty()

        if (jsonPath.isNotBlank()) {
            runCatching { loadSubtitles(jsonPath, subtitleCallback) }
        }

        val location = app.get(videoUrl, allowRedirects = false).headers["location"]
        if (location.isNullOrBlank()) return false

        val type = if (location.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
        callback(
            newExtractorLink(source = name, name = name, url = location, type = type) {
                this.referer = "$mainUrl/"
                this.quality = Qualities.Unknown.value
            }
        )
        return true
    }

    private suspend fun loadSubtitles(jsonPath: String, subtitleCallback: (SubtitleFile) -> Unit) {
        val video = JSONObject(app.get(fixUrl(jsonPath)).text).optJSONObject("video") ?: return
        val subs = video.optJSONArray("subtitlesArray") ?: video.optJSONArray("subtitleList") ?: return

        val seen = mutableSetOf<String>()
        for (i in 0 until subs.length()) {
            val s = subs.optJSONObject(i) ?: continue
            val url = s.optString("url").takeIf { it.isNotBlank() }?.let { fixUrl(it) } ?: continue
            if (!seen.add(url)) continue
            val label = s.optString("label").ifBlank { "Italiano" }
            subtitleCallback(newSubtitleFile(label, url))
        }
    }

    private fun JSONObject?.firstImage(vararg keys: String): String? {
        if (this == null) return null
        return keys.map { optString(it) }.firstOrNull { it.isNotBlank() }?.let { fixUrl(it) }
    }
}