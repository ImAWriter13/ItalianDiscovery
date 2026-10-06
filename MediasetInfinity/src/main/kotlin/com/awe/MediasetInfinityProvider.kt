package com.awe

import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.fixUrl
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.INFER_TYPE
import com.lagradost.cloudstream3.utils.newDrmExtractorLink
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.nio.charset.StandardCharsets
import java.util.UUID

class MediasetInfinity : MainAPI() {
    override var mainUrl = "https://mediasetinfinity.mediaset.it"
    private var apiUrl = "https://mediasetplay.api-graph.mediaset.it"
    override var name = "MediasetInfinity"
    override var lang = "it"
    override val hasMainPage = true
    override var supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    private val apiHeaders = mapOf("x-m-platform" to "WEB", "x-m-property" to "MPLAY")

    companion object {
        private const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
        private const val APP_NAME = "web//mediasetplay-web/1.0.26-154f52d"
        private const val CLIENT_ID = "93030b28-56d7-473c-83cb-a355b2900368"
        private const val ACCOUNT_ID = "2702976343"
        private const val IMG_BASE = "https://img-prod-api2.mediasetplay.mediaset.it/api/images"
        val WIDEVINE_UUID: UUID = UUID.fromString("edef8ba9-79d6-4ace-a3c8-27dcd51d21ed")
    }

    private fun posterUrl(type: String, guid: String) =
        "$IMG_BASE/$type/v5/ita/$guid/image_vertical/300/450"

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get("$mainUrl/programmitv").document
        val excluded = setOf("Le clip più viste", "TG", "Sport")

        val sections = document.select("div.padding-y-collections, section, .canali-item")
            .mapNotNull { row ->
                val sectionTitle = row.select("h2").text()
                if (sectionTitle in excluded) return@mapNotNull null

                val programs = row.select("ul.scroll li, .card-item, .item-video")
                    .dropLast(1)
                    .mapNotNull { el ->
                        val link = el.select("a").attr("href")
                        if (link.isEmpty()) return@mapNotNull null
                        val title = el.select("h3, .title, img").attr("alt")
                            .ifEmpty { el.select("h3, .title").text() }
                        newTvSeriesSearchResponse(title, link, TvType.TvSeries) {
                            posterUrl = el.select("img").attr("src")
                        }
                    }

                if (programs.isEmpty()) return@mapNotNull null
                HomePageList(sectionTitle, programs, isHorizontalImages = false)
            }

        return newHomePageResponse(sections, hasNext = false)
    }

    private fun buildSearchResponses(items: List<Item>): List<SearchResponse> =
        items.mapNotNull { item ->
            val url = item.card?.url ?: return@mapNotNull null
            val title = item.name ?: "Titolo sconosciuto"
            val guid = item.guid ?: return@mapNotNull null
            if (item.type == "SeriesItem") {
                newTvSeriesSearchResponse(title, url, TvType.TvSeries) {
                    posterUrl = posterUrl("mse", guid)
                }
            } else {
                newMovieSearchResponse(title, url, TvType.Movie) {
                    posterUrl = posterUrl("mp", guid)
                }
            }
        }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$apiUrl/?extensions={\"persistedQuery\":{\"version\":1,\"sha256Hash\":\"819f5ee79c4b589ce25bacbf2390d181311495e647bfff092a487b4e00552072\"}}&variables={\"first\":12,\"property\":\"search\",\"query\":\"$query\",\"uxReference\":\"filteredSearch\"}"
        val result = parseJson<InertiaResponse>(app.get(url, headers = apiHeaders).text)
        val items = result.data.getSearchPage.areaContainersConnection.areaContainers
            .firstOrNull()?.areas?.firstOrNull()?.sections?.firstOrNull()
            ?.collections?.firstOrNull()?.itemsConnection?.items ?: return emptyList()
        return buildSearchResponses(items)
    }

    override suspend fun load(url: String): LoadResponse {
        val pageUrl = fixUrl(url)
        val response = app.get(pageUrl)
        val document = response.document
        val responseText = response.text

        val stId = Regex("""(ST\d{10,})""").find(responseText)?.groupValues?.get(1)
        val fId = Regex("""(F\d{10,})""").find(responseText)?.groupValues?.get(1)
        val finalGuid = stId ?: fId

        val verticalPoster = if (finalGuid != null) {
            posterUrl(if (finalGuid.startsWith("ST")) "mst" else "mp", finalGuid)
        } else {
            document.select("meta[property=og:image]").attr("content")
                .replace("image_landscape", "image_vertical")
        }
        val horizontalBackground = document.select("meta[property=og:image]").attr("content")
            .replace("image_vertical", "image_landscape")

        val title = document.select("img.absolute").attr("title").removeSuffix(" logo")
            .ifBlank { document.select("h1").text() }
        val details = document.select("span.text-body-1-m").text()
        val year = document.select("span.text-caption").text().filter { it.isDigit() }.take(4).toIntOrNull()
        val age = when (document.select("circle").attr("fill")) {
            "#FFAF00" -> "R"
            "#B20707" -> "18+"
            "#1DCC24" -> "E"
            else -> ""
        }
        val isMovie = url.contains("/movie/") || (fId != null && stId == null)

        if (isMovie) {
            val movieDetails = document.select("p.text-body-1-m").text()
            val movieYear = movieDetails.filter { it.isDigit() }.take(4).toIntOrNull()
            val duration = document.select("span.opacity-40").text().filter { it.isDigit() }.take(3).toIntOrNull()
            val actors = document.select("h2.text-body-2-m").firstOrNull()?.text()
                ?.substringAfter("Cast: ")?.removeSuffix(".")
                ?.split(",")?.map { it.trim() } ?: emptyList()

            return newMovieLoadResponse(title, url, TvType.Movie, fId ?: url) {
                this.posterUrl = verticalPoster
                this.backgroundPosterUrl = horizontalBackground
                this.year = movieYear
                this.plot = movieDetails
                this.duration = duration
                this.contentRating = age
                this.addActors(actors)
            }
        }

        val orderedSeasonData = extractSeasons(responseText, url)

        val episodes = kotlinx.coroutines.coroutineScope {
            orderedSeasonData.mapIndexed { seasonIndex, (seasonTitle, seasonPath) ->
                async {
                    loadSeasonEpisodes(url, seasonTitle, seasonPath, seasonIndex)
                }
            }.awaitAll().flatten()
        }

        val actorsFull = document.select("span.text-body-2-m").firstOrNull()?.text()
        val actors = actorsFull?.takeIf { it.contains("Cast: ") }
            ?.substringAfter("Cast: ")?.removeSuffix(".")
            ?.split(",")?.map { it.trim() }

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
            this.posterUrl = verticalPoster
            this.backgroundPosterUrl = horizontalBackground
            this.year = year
            this.plot = details
            this.contentRating = age
            this.addActors(actors)
        }
    }

    private fun extractSeasons(responseText: String, url: String): List<Pair<String, String>> {
        val seasonData = mutableListOf<Pair<String, String>>()

        val seasonsBlock = listOf("\"seasons\":[", "\\\"seasons\\\":[").firstNotNullOfOrNull { pattern ->
            val startIndex = responseText.indexOf(pattern)
            if (startIndex == -1) return@firstNotNullOfOrNull null
            val arrayStart = startIndex + pattern.length - 1
            var bracketCount = 0
            var arrayEnd = -1
            for (i in arrayStart until responseText.length) {
                when (responseText[i]) {
                    '[' -> bracketCount++
                    ']' -> if (--bracketCount == 0) { arrayEnd = i; break }
                }
            }
            if (arrayEnd != -1) responseText.substring(arrayStart, arrayEnd + 1) else null
        }

        if (seasonsBlock != null) {
            val normalized = seasonsBlock.replace("\\\\\"", "\"").replace("\\\"", "\"")
            var i = 0
            while (i < normalized.length) {
                if (normalized[i] == '{') {
                    var braceCount = 0
                    var objEnd = -1
                    for (j in i until normalized.length) {
                        when (normalized[j]) {
                            '{' -> braceCount++
                            '}' -> if (--braceCount == 0) { objEnd = j; break }
                        }
                    }
                    if (objEnd != -1) {
                        val obj = normalized.substring(i, objEnd + 1)
                        val sTitle = Regex(""""seasonTitle"\s*:\s*"([^"]+)"""").find(obj)?.groupValues?.get(1)
                        val sPath = Regex(""""value"\s*:\s*"(https?://mediasetinfinity\.mediaset\.it[^"]+)"""").find(obj)?.groupValues?.get(1)
                        if (sTitle != null && sPath != null &&
                            (sPath.contains("_SE") || sPath.contains("ST")) &&
                            seasonData.none { it.second == sPath }
                        ) {
                            seasonData.add(sTitle to sPath)
                        }
                        i = objEnd + 1
                    } else i++
                } else i++
            }
        }

        val finalSeasons = seasonData.asReversed().toMutableList()

        if (finalSeasons.isEmpty()) {
            val absoluteUrl = if (url.startsWith("http")) url else "$mainUrl${url.removePrefix("/")}"
            finalSeasons.add("Stagione Corrente" to absoluteUrl)
        } else {
            val currentPath = url.substringAfter("mediasetinfinity.mediaset.it").substringBefore("?").trimEnd('/')
            val isPresent = finalSeasons.any { (_, path) ->
                path.substringAfter("mediasetinfinity.mediaset.it").substringBefore("?").trimEnd('/') == currentPath
            }
            if (!isPresent) {
                val absoluteUrl = if (url.startsWith("http")) url else "$mainUrl${url.removePrefix("/")}"
                finalSeasons.add(0, "Stagione Corrente" to absoluteUrl)
            }
        }

        return finalSeasons
    }

    private suspend fun loadSeasonEpisodes(
        seriesUrl: String,
        seasonTitle: String,
        seasonPath: String,
        seasonIndex: Int
    ): List<Episode> {
        val seasonEpisodes = mutableListOf<Episode>()
        try {
            val rawSeasonNum = Regex("""stagione\s*(\d+)""", RegexOption.IGNORE_CASE)
                .find(seasonPath)?.groupValues?.get(1)?.toIntOrNull()
                ?: Regex("""\d+""").find(seasonTitle)?.value?.toIntOrNull()
            val finalSeasonId = rawSeasonNum?.takeIf { it < 1900 } ?: (seasonIndex + 1)

            val apiEps = if (!seasonPath.startsWith("http") && !seasonPath.startsWith("/") && seasonPath.length > 15) {
                fetchEpisodesFromGraphQL(apiUrl, seasonPath)
            } else {
                resolveAndFetchEpisodes(seasonPath)
            }

            apiEps.forEachIndexed { idx, ep ->
                seasonEpisodes.add(
                    newEpisode(Pair(seriesUrl, ep.id)) {
                        this.posterUrl = ep.posterUrl
                        this.name = ep.title
                        this.season = finalSeasonId
                        this.episode = ep.episodeNumber ?: (apiEps.size - idx)
                        this.description = ep.description
                    }
                )
            }
        } catch (_: Exception) {}
        return seasonEpisodes
    }

    private suspend fun resolveAndFetchEpisodes(seasonPath: String): List<ApiEpisode> {
        var subId = Regex(",sb(\\d+)").find(seasonPath)?.groupValues?.get(1)
        var fallbackId: String? = null

        if (subId == null) {
            val fullPath = if (seasonPath.startsWith("http")) seasonPath
            else "$mainUrl${if (seasonPath.startsWith("/")) "" else "/"}$seasonPath"
            val sText = app.get(fullPath).text

            subId = Regex(",sb(\\d+)").find(sText)?.groupValues?.get(1)
                ?: Regex("""subBrandId[^"\d]*(\d+)""").find(sText)?.groupValues?.get(1)
                ?: Regex(""""subBrandId"\s*:\s*"(\d+)"""").find(sText)?.groupValues?.get(1)

            if (subId == null) {
                fallbackId = Regex("""/browse/puntate[-_]intere_e([^/?#"]+)""").find(sText)?.groupValues?.get(1)
                    ?: Regex("""_e([A-Za-z0-9]{15,30})""").find(sText)?.groupValues?.get(1)
            }
        }

        return when {
            fallbackId != null -> fetchEpisodesFromGraphQL(apiUrl, fallbackId)
            subId != null -> fetchEpisodesFromPlatformFeed(apiUrl, subId)
            else -> emptyList()
        }
    }

    private suspend fun fetchEpisodesFromGraphQL(apiBaseUrl: String, listingId: String): List<ApiEpisode> {
        val allEpisodes = mutableListOf<ApiEpisode>()
        var afterCursor = "-1"
        var hasNextPage = true

        while (hasNextPage) {
            val variables = org.json.JSONObject().apply {
                put("after", afterCursor)
                put("context", """{"a":{"template":"KEYFRAME","layout":"GRID","flags":["SHOW_TITLE"]},"pt":"listing"}""")
                put("first", 24)
                put("id", listingId)
                put("pageType", "listing")
            }.toString()

            val extensions = org.json.JSONObject().apply {
                put("persistedQuery", org.json.JSONObject().apply {
                    put("version", 1)
                    put("sha256Hash", "fd2844ffc4b9ddc328c40926ae4a5ebddbdd3f9a616a80192bd937e94aa6566f")
                })
            }.toString()

            val apiResponse = app.get(apiBaseUrl, params = mapOf("extensions" to extensions, "variables" to variables), headers = apiHeaders)

            try {
                val itemsConnection = apiResponse.text.let { org.json.JSONObject(it) }
                    .optJSONObject("data")?.let { data ->
                        data.optJSONObject(data.keys().asSequence().firstOrNull() ?: return@let null)
                    }?.optJSONObject("itemsConnection") ?: break

                val items = itemsConnection.optJSONArray("items") ?: break
                if (items.length() == 0) break

                for (i in 0 until items.length()) {
                    val item = items.optJSONObject(i) ?: continue
                    val guid = item.optString("guid", "").ifBlank {
                        item.optJSONObject("cardLink")?.optString("referenceId", "") ?: ""
                    }
                    if (guid.isBlank()) continue

                    val cardTitle = item.optString("cardTitle", "").trim()
                    val epNum = Regex("""(?:Ep\.?\s*|Episodio\s*)(\d+)""", RegexOption.IGNORE_CASE)
                        .find(cardTitle)?.groupValues?.get(1)?.toIntOrNull()
                    val cleanTitle = cardTitle
                        .replace(Regex("""^Ep\.?\s*\d+\s*[-–]\s*"""), "")
                        .replace(Regex("""^Episodio\s*\d+\s*[-–]\s*"""), "")
                        .trim()

                    allEpisodes.add(ApiEpisode(
                        id = guid,
                        title = cleanTitle.ifBlank { cardTitle },
                        posterUrl = "$IMG_BASE/mp/v5/ita/$guid/image_keyframe_poster/224/126",
                        episodeNumber = epNum,
                        description = item.optString("cardText", "").ifBlank { item.optString("description", null) }
                    ))
                }

                val pageInfo = itemsConnection.optJSONObject("pageInfo")
                hasNextPage = pageInfo?.optBoolean("hasNextPage", false) ?: false
                if (hasNextPage) afterCursor = pageInfo?.optString("endCursor", "-1") ?: "-1"
            } catch (_: Exception) { break }
        }
        return allEpisodes
    }

    private suspend fun fetchEpisodesFromPlatformFeed(apiBaseUrl: String, subBrandId: String): List<ApiEpisode> {
        val allEpisodes = mutableListOf<ApiEpisode>()
        var afterCursor = "-1"
        var hasNextPage = true

        while (hasNextPage) {
            val feedUrl = "http://feed.entertainment.tv.theplatform.eu/f/PR1GhC/mediaset-prod-all-programs-v2?byCustomValue={subBrandId}{$subBrandId}&sort=:publishInfo_lastPublished|desc,tvSeasonEpisodeNumber|desc"
            val graphId = "m:${android.util.Base64.encodeToString(feedUrl.toByteArray(StandardCharsets.UTF_8), android.util.Base64.NO_WRAP)}"

            val variables = org.json.JSONObject().apply {
                put("after", afterCursor)
                put("context", """{"a":{"template":"KEYFRAME","layout":"GRID","flags":["SHOW_TITLE"]},"pt":"listing"}""")
                put("first", 1000)
                put("id", graphId)
                put("pageType", "listing")
            }.toString()

            val extensions = org.json.JSONObject().apply {
                put("persistedQuery", org.json.JSONObject().apply {
                    put("version", 1)
                    put("sha256Hash", "744a87fb36dd66f089b2eb301bf12240fed77ba4d400fba3065fb8d6ff8535da")
                })
            }.toString()

            val apiResponse = app.get(apiBaseUrl, params = mapOf("extensions" to extensions, "variables" to variables), headers = apiHeaders)

            try {
                val itemsConnection = apiResponse.text.let { org.json.JSONObject(it) }
                    .optJSONObject("data")?.let { data ->
                        data.optJSONObject(data.keys().asSequence().firstOrNull() ?: return@let null)
                    }?.optJSONObject("itemsConnection") ?: break

                val items = itemsConnection.optJSONArray("items") ?: break
                for (i in 0 until items.length()) {
                    val item = items.optJSONObject(i) ?: continue
                    val guid = item.optString("guid", "").ifBlank {
                        item.optJSONObject("cardLink")?.optString("referenceId", "") ?: ""
                    }
                    if (guid.isBlank()) continue
                    allEpisodes.add(ApiEpisode(
                        id = guid,
                        title = item.optString("cardTitle", "").trim(),
                        posterUrl = "$IMG_BASE/mp/v5/ita/$guid/image_keyframe_poster/224/126",
                        episodeNumber = null,
                        description = item.optString("cardText", null)
                    ))
                }

                val pageInfo = itemsConnection.optJSONObject("pageInfo")
                hasNextPage = pageInfo?.optBoolean("hasNextPage", false) ?: false
                if (hasNextPage) afterCursor = pageInfo?.optString("endCursor", "-1") ?: "-1"
            } catch (_: Exception) { break }
        }
        return allEpisodes
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val videoPath = try { parseJson<Pair<String, String>>(data).second } catch (_: Exception) { data }
        val guid = Regex("""([A-Z]\d{14,})""").find(videoPath)?.groupValues?.get(1) ?: videoPath

        val token = getAnonymousToken().takeIf { it.isNotBlank() } ?: return false

        val smilUrl = "https://link.api.eu.theplatform.com/s/PR1GhC/media/guid/$ACCOUNT_ID/$guid" +
                "?format=SMIL&auth=$token&formats=MPEG-DASH" +
                "&assetTypes=HR,browser,widevine,geoIT%7CgeoNo:SD,browser,widevine,geoIT%7CgeoNo" +
                "&balance=true&auto=true&tracking=true&delivery=Streaming"

        val smilResponse = app.get(smilUrl, headers = mapOf("User-Agent" to USER_AGENT, "Origin" to mainUrl, "Referer" to "$mainUrl/"))
        if (smilResponse.code != 200 || smilResponse.text.contains("isException")) return false

        val smilText = smilResponse.text
        val mpdUrl = Regex("""<video[^>]+src="([^"]+\.mpd)"""").find(smilText)?.groupValues?.get(1)
            ?: return false
        val releasePid = Regex("""\bpid=([^|"&]+)""").find(smilText)?.groupValues?.get(1) ?: ""

        val kid = app.get(mpdUrl, headers = mapOf("Origin" to mainUrl, "Referer" to "$mainUrl/")).text
            .let { Regex("""cenc:default_KID="([^"]+)"""").find(it)?.groupValues?.get(1) }
            ?.replace("-", "")?.lowercase()
            ?.takeIf { it.isNotBlank() } ?: return false

        val licenseUrl = "https://widevine.entitlement.theplatform.eu/wv/web/ModularDrm/getRawWidevineLicense" +
                "?releasePid=$releasePid" +
                "&account=${java.net.URLEncoder.encode("http://access.auth.theplatform.com/data/Account/$ACCOUNT_ID", "UTF-8")}" +
                "&schema=1.0&token=$token"

        callback.invoke(
            newDrmExtractorLink(this.name, this.name, mpdUrl, INFER_TYPE, WIDEVINE_UUID) {
                this.licenseUrl = licenseUrl
                this.kid = kid
                this.referer = "$mainUrl/"
                this.headers = mapOf("Origin" to mainUrl, "Referer" to "$mainUrl/", "User-Agent" to USER_AGENT)
            }
        )
        return true
    }

    private suspend fun getAnonymousToken(): String {
        return try {
            val body = """{"appName":"$APP_NAME","client_id":"$CLIENT_ID"}"""
                .toRequestBody("application/json".toMediaType())
            val response = app.post(
                "https://api-ott-prod-fe.mediaset.net/PROD/play/idm/anonymous/login/v2.0",
                requestBody = body,
                headers = mapOf("User-Agent" to USER_AGENT, "Origin" to mainUrl, "Referer" to "$mainUrl/", "Accept" to "application/json")
            )
            Regex(""""beToken"\s*:\s*"([^"]+)"""").find(response.text)?.groupValues?.get(1) ?: ""
        } catch (_: Exception) { "" }
    }
}
