package com.awe

import com.lagradost.api.Log
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
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.INFER_TYPE
import com.lagradost.cloudstream3.utils.newDrmExtractorLink
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.UUID

class MediasetInfinity : MainAPI() {
    override var mainUrl = "https://mediasetinfinity.mediaset.it"
    var apiUrl = "https://mediasetplay.api-graph.mediaset.it"
    override var name = "MediasetInfinity"
    override var lang = "it"
    override val hasMainPage = true
    override var supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    var headers = mapOf(
        "x-m-platform" to "WEB",
        "x-m-property" to "MPLAY"
    ).toMutableMap()

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val response = app.get("$mainUrl/programmitv").document
        val homePageSections = mutableListOf<HomePageList>()
        val rows = response.select("div.padding-y-collections, section, .canali-item")

        rows.forEach { row ->
            val sectionTitle = row.select("h2").text()
            val programs = row.select("ul.scroll li, .card-item, .item-video").map { element ->
                val title = element.select("h3, .title, img").attr("alt").ifEmpty { element.select("h3, .title").text() }
                val link = element.select("a").attr("href")
                val poster = element.select("img").attr("src")

                newTvSeriesSearchResponse(title, link, TvType.TvSeries) {
                    this.posterUrl = poster
                }
            }.filter { it.url.isNotEmpty() }
            if (programs.isNotEmpty() && sectionTitle != "Le clip più viste" && sectionTitle != "TG" && sectionTitle != "Sport") {
                homePageSections.add(
                    HomePageList(
                        name = sectionTitle,
                        list = programs,
                        isHorizontalImages = false
                    )
                )
            }
        }

        return newHomePageResponse(homePageSections, hasNext = false)
    }


    private fun searchResponseBuilder(listJson: List<Item>): List<SearchResponse> {
        return listJson.mapNotNull { item ->
            val url = item.card?.url ?: return@mapNotNull null
            val title = item.name ?: "Titolo sconosciuto"
            if (item.type == "SeriesItem") {
                newTvSeriesSearchResponse(title, url, TvType.TvSeries){this.posterUrl = "https://img-prod-api2.mediasetplay.mediaset.it/api/images/mse/v5/ita/${item.guid}/image_vertical/300/450"}
            } else {
                newMovieSearchResponse(title, url, TvType.Movie){this.posterUrl = "https://img-prod-api2.mediasetplay.mediaset.it/api/images/mp/v5/ita/${item.guid}/image_vertical/300/450"}
            }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$apiUrl/?extensions={\"persistedQuery\":{\"version\":1,\"sha256Hash\":\"819f5ee79c4b589ce25bacbf2390d181311495e647bfff092a487b4e00552072\"}}&variables={\"first\":12,\"property\":\"search\",\"query\":\"$query\",\"uxReference\":\"filteredSearch\"}"
        val response = app.get(url, headers = headers).text
        val result = parseJson<InertiaResponse>(response)
        val items = result.data.getSearchPage.areaContainersConnection.areaContainers
            .firstOrNull()?.areas?.firstOrNull()?.sections?.firstOrNull()
            ?.collections?.firstOrNull()?.itemsConnection?.items ?: return emptyList()
        return searchResponseBuilder(items)
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
            val type = if (finalGuid.startsWith("ST")) "mst" else "mp"
            "https://img-prod-api2.mediasetplay.mediaset.it/api/images/$type/v5/ita/$finalGuid/image_vertical/300/450"
        } else {
            document.select("meta[property=og:image]").attr("content")
                .replace("image_landscape", "image_vertical")
        }

        val horizontalBackground = document.select("meta[property=og:image]").attr("content")
            .replace("image_vertical", "image_landscape")
        val titleImg = document.select("img.absolute")
        val title =
            titleImg.attr("title").removeSuffix(" logo").ifBlank { document.select("h1").text() }
        val details = document.select("span.text-body-1-m").text()
        val year = document.select("span.text-caption").text().filter { it.isDigit() }.take(4)
            .toIntOrNull()
        val ageCircle = document.select("circle").attr("fill")
        var age = ""
        if (ageCircle == "#FFAF00") {
            age = "R"
        } else if (ageCircle == "#B20707") {
            age = "18+"
        } else if (ageCircle == "#1DCC24") {
            age = "E"
        }
        val isMovie = url.contains("/movie/") || (fId != null && stId == null)

        if (isMovie) {
            val details = document.select("p.text-body-1-m").text()
            val year = document.select("p.text-body-1-m").text().filter { it.isDigit() }.take(4)
                .toIntOrNull()
            val duration = document.select("span.opacity-40").text().filter { it.isDigit() }.take(3)
                .toIntOrNull()
            val actors =
                document.select("h2.text-body-2-m").first()!!.text().substringAfter("Cast: ")
                    .removeSuffix(".").split(",").map { it.trim() }
            return newMovieLoadResponse(title, url, TvType.Movie, fId ?: url) {
                this.posterUrl = verticalPoster
                this.backgroundPosterUrl = horizontalBackground
                this.year = year
                this.plot = details
                this.duration = duration
                this.contentRating = age
                this.addActors(actors)
            }
        } else {
            val seasonData = mutableListOf<Pair<String, String>>()
            val seasonsPatterns = listOf("\"seasons\":[", "\\\"seasons\\\":[")
            var seasonsBlock: String? = null

            for (pattern in seasonsPatterns) {
                val startIndex = responseText.indexOf(pattern)
                if (startIndex == -1) continue
                val arrayStart = startIndex + pattern.length - 1
                var bracketCount = 0
                var arrayEnd = -1
                for (i in arrayStart until responseText.length) {
                    when (responseText[i]) {
                        '[' -> bracketCount++
                        ']' -> {
                            bracketCount--
                            if (bracketCount == 0) {
                                arrayEnd = i
                                break
                            }
                        }
                    }
                }
                if (arrayEnd != -1) {
                    seasonsBlock = responseText.substring(arrayStart, arrayEnd + 1)
                    break
                }
            }

            if (seasonsBlock != null) {
                val normalized = seasonsBlock.replace("\\\\\"", "\"").replace("\\\"", "\"")
                val seasonObjects = mutableListOf<String>()
                var i = 0
                while (i < normalized.length) {
                    if (normalized[i] == '{') {
                        var braceCount = 0
                        var objEnd = -1
                        for (j in i until normalized.length) {
                            when (normalized[j]) {
                                '{' -> braceCount++
                                '}' -> {
                                    braceCount--
                                    if (braceCount == 0) {
                                        objEnd = j
                                        break
                                    }
                                }
                            }
                        }
                        if (objEnd != -1) {
                            seasonObjects.add(normalized.substring(i, objEnd + 1))
                            i = objEnd + 1
                        } else i++
                    } else i++
                }

                for (seasonObj in seasonObjects) {
                    val sTitle =
                        Regex(""""seasonTitle"\s*:\s*"([^"]+)"""").find(seasonObj)?.groupValues?.get(
                            1
                        ) ?: continue
                    val sPath =
                        Regex(""""value"\s*:\s*"(https?://mediasetinfinity\.mediaset\.it(/[^"]+))"""").find(
                            seasonObj
                        )?.groupValues?.get(2) ?: continue
                    if (sPath.contains("_SE") && seasonData.none { it.second == sPath }) {
                        seasonData.add(sTitle to sPath)
                    }
                }
            }

            val orderedSeasonData = seasonData.asReversed()

            if (orderedSeasonData.isEmpty()) orderedSeasonData.add(
                "Stagione 1" to url.substringAfter(
                    mainUrl
                )
            )

            val episodes = kotlinx.coroutines.coroutineScope {
                orderedSeasonData.mapIndexed { seasonIndex, (seasonTitle, seasonPath) ->
                    async {
                        val seasonEpisodesList = mutableListOf<Episode>()
                        try {
                            var subId = Regex(",sb(\\d+)").find(seasonPath)?.groupValues?.get(1)
                            if (subId == null) {
                                val sText = app.get("$mainUrl$seasonPath").text
                                subId = Regex(",sb(\\d+)").find(sText)?.groupValues?.get(1)
                                    ?: Regex("subBrandId[^\\d]*(\\d{5,})").find(sText)?.groupValues?.get(
                                        1
                                    )
                            }
                            if (subId != null) {
                                val apiEps = fetchEpisodesFromApi(
                                    apiUrl,
                                    "744a87fb36dd66f089b2eb301bf12240fed77ba4d400fba3065fb8d6ff8535da",
                                    subId
                                )
                                apiEps.forEachIndexed { idx, ep ->
                                    seasonEpisodesList.add(
                                        newEpisode(Pair(url, ep.id)) {
                                            this.posterUrl = ep.posterUrl
                                            this.name = ep.title
                                            this.season = seasonIndex + 1
                                            this.episode = ep.episodeNumber ?: (apiEps.size - idx)
                                            this.description = ep.description
                                        }
                                    )
                                }
                            }
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                        seasonEpisodesList
                    }
                }.awaitAll().flatten()
            }
            val actorsFull = document.select("span.text-body-2-m").first()!!.text()
            val actors = if (actorsFull != null && actorsFull.contains("Cast: ")) {
                actorsFull.substringAfter("Cast: ").removeSuffix(".").split(",").map { it.trim() }
            } else {
                null
            }
            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = verticalPoster
                this.backgroundPosterUrl = horizontalBackground
                this.year = year
                this.plot = details
                this.contentRating = age
                this.addActors(actors)
            }
        }
    }

    private suspend fun fetchEpisodesFromApi(
        apiBaseUrl: String,
        sha256Hash: String,
        subBrandId: String
    ): List<ApiEpisode> {
        val allEpisodes = mutableListOf<ApiEpisode>()
        var afterCursor = "-1"
        var hasNextPage = true

        while (hasNextPage) {
            val feedUrl = "http://feed.entertainment.tv.theplatform.eu/f/PR1GhC/mediaset-prod-all-programs-v2?byCustomValue={subBrandId}{$subBrandId}&sort=:publishInfo_lastPublished|desc,tvSeasonEpisodeNumber|desc"
            val feedUrlBase64 = android.util.Base64.encodeToString(
                feedUrl.toByteArray(Charsets.UTF_8),
                android.util.Base64.NO_WRAP
            )
            val graphId = "m:$feedUrlBase64"

            val context = """{"a":{"template":"KEYFRAME","layout":"GRID","flags":["SHOW_TITLE"]},"pt":"listing"}"""
            val variables = org.json.JSONObject().apply {
                put("after", afterCursor)
                put("context", context)
                put("first", 1000)
                put("id", graphId)
                put("pageType", "listing")
            }.toString()

            val extensions = org.json.JSONObject().apply {
                put("persistedQuery", org.json.JSONObject().apply {
                    put("version", 1)
                    put("sha256Hash", sha256Hash)
                })
            }.toString()

            val apiResponse = app.get(
                apiBaseUrl,
                params = mapOf(
                    "extensions" to extensions,
                    "variables" to variables
                ),
                headers = mapOf(
                    "x-m-platform" to "WEB",
                    "x-m-property" to "MPLAY"
                )
            )

            try {
                val jsonObj = org.json.JSONObject(apiResponse.text)
                val dataObj = jsonObj.optJSONObject("data") ?: break

                val resultKey = dataObj.keys().asSequence().firstOrNull() ?: break
                val resultObj = dataObj.optJSONObject(resultKey) ?: break

                val itemsConnection = resultObj.optJSONObject("itemsConnection") ?: break
                val items = itemsConnection.optJSONArray("items") ?: break

                for (i in 0 until items.length()) {
                    val item = items.optJSONObject(i) ?: continue
                    val guid = item.optString("guid", "").ifBlank {
                        val cardLink = item.optJSONObject("cardLink")
                        cardLink?.optString("referenceId", "") ?: ""
                    }
                    if (guid.isBlank()) continue
                    val cardTitle = item.optString("cardTitle", "").trim()
                    val description = item.optString("cardText", "").ifBlank {
                        item.optString("description", "")
                    }
                    val epPosterUrl = "https://img-prod-api2.mediasetplay.mediaset.it/api/images/mp/v5/ita/$guid/image_keyframe_poster/224/126"
                    val epNumFromTitle = Regex("""(?:Ep\.?\s*|Episodio\s*)(\d+)""", RegexOption.IGNORE_CASE)
                        .find(cardTitle)?.groupValues?.get(1)?.toIntOrNull()
                    val cleanTitle = cardTitle
                        .replace(Regex("""^Ep\.?\s*\d+\s*[-–]\s*"""), "")
                        .replace(Regex("""^Episodio\s*\d+\s*[-–]\s*"""), "")
                        .replace(Regex("""^\w+\s+puntata\s*\|""", RegexOption.IGNORE_CASE), "")
                        .trim()

                    allEpisodes.add(
                        ApiEpisode(
                            id = guid,
                            title = cleanTitle.ifBlank { cardTitle },
                            posterUrl = epPosterUrl,
                            episodeNumber = epNumFromTitle,
                            description = description.ifBlank { null }
                        )
                    )
                }

                val pageInfo = itemsConnection.optJSONObject("pageInfo")
                hasNextPage = pageInfo?.optBoolean("hasNextPage", false) ?: false
                if (hasNextPage) {
                    afterCursor = pageInfo?.optString("endCursor", "-1") ?: "-1"
                }

            } catch (e: Exception) {
                e.printStackTrace()
                break
            }
        }

        return allEpisodes
    }

    companion object {
        const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
        const val APP_NAME = "web//mediasetplay-web/1.0.26-154f52d"
        const val CLIENT_ID = "93030b28-56d7-473c-83cb-a355b2900368"
        const val ACCOUNT_ID = "2702976343"
        val WIDEVINE_UUID: UUID = UUID.fromString("edef8ba9-79d6-4ace-a3c8-27dcd51d21ed")
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val TAG = "MediasetInfinity"
        val videoPath = try {
            val pair = parseJson<Pair<String, String>>(data)
            pair.second
        } catch (e: Exception) {
            data
        }
        val guid = Regex("""([A-Z]\d{14,})""").find(videoPath)?.groupValues?.get(1) ?: videoPath
        val token = getAnonymousToken()
        if (token.isBlank()) {return false}
        val smilUrl = "https://link.api.eu.theplatform.com/s/PR1GhC/media/guid/$ACCOUNT_ID/$guid" +
                "?format=SMIL&auth=$token&formats=MPEG-DASH" +
                "&assetTypes=HR,browser,widevine,geoIT%7CgeoNo:SD,browser,widevine,geoIT%7CgeoNo" +
                "&balance=true&auto=true&tracking=true&delivery=Streaming"
        val smilResponse = app.get(smilUrl, headers = mapOf(
            "User-Agent" to USER_AGENT, "Origin" to mainUrl, "Referer" to "$mainUrl/"
        ))
        if (smilResponse.code != 200 || smilResponse.text.contains("isException")) {
            Log.d(TAG, "SMIL errore: ${smilResponse.code}")
            return false
        }
        val smilText = smilResponse.text
        val mpdUrl = Regex("""<video[^>]+src="([^"]+\.mpd)"""").find(smilText)?.groupValues?.get(1)
        if (mpdUrl.isNullOrBlank()) {
            return false
        }
        val releasePid = Regex("""\bpid=([^|"&]+)""").find(smilText)?.groupValues?.get(1) ?: ""
        val mpdResponse = app.get(mpdUrl, headers = mapOf("Origin" to mainUrl, "Referer" to "$mainUrl/"))
        val mpdText = mpdResponse.text
        val kid = Regex("""cenc:default_KID="([^"]+)"""").find(mpdText)?.groupValues?.get(1)
            ?.replace("-", "")?.lowercase() ?: ""
        if (kid.isBlank()) {
            return false
        }
        val licenseUrl = "https://widevine.entitlement.theplatform.eu/wv/web/ModularDrm/getRawWidevineLicense" +
                "?releasePid=$releasePid" +
                "&account=${java.net.URLEncoder.encode("http://access.auth.theplatform.com/data/Account/$ACCOUNT_ID", "UTF-8")}" +
                "&schema=1.0&token=$token"
        callback.invoke(
            newDrmExtractorLink(
                this.name,
                this.name,
                mpdUrl,
                INFER_TYPE,
                WIDEVINE_UUID
            ) {
                this.licenseUrl = licenseUrl
                this.kid = kid
                this.referer = "$mainUrl/"
                this.headers = mapOf(
                    "Origin" to mainUrl,
                    "Referer" to "$mainUrl/",
                    "User-Agent" to USER_AGENT
                )
            }
        )
        return true
    }

    private suspend fun getAnonymousToken(): String {
        val TAG = "MediasetInfinity"
        try {
            val loginUrl = "https://api-ott-prod-fe.mediaset.net/PROD/play/idm/anonymous/login/v2.0"
            val jsonBody = """{"appName":"$APP_NAME","client_id":"$CLIENT_ID"}"""
            val requestBody = jsonBody.toRequestBody("application/json".toMediaType())
            val response = app.post(
                loginUrl,
                requestBody = requestBody,
                headers = mapOf(
                    "User-Agent" to USER_AGENT,
                    "Origin" to mainUrl,
                    "Referer" to "$mainUrl/",
                    "Accept" to "application/json"
                )
            )
            val tokenRegex = Regex(""""beToken"\s*:\s*"([^"]+)"""")
            val match = tokenRegex.find(response.text)
            if (match != null) {
                return match.groupValues[1]
            }
        } catch (e: Exception) {
            Log.d(TAG, "Errore token: ${e.message}")
        }

        return ""
    }
}