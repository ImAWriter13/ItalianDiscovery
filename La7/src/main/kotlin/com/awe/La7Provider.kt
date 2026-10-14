package com.awe

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.text.Normalizer
import kotlin.coroutines.cancellation.CancellationException

class LA7Provider : MainAPI() {
    override var mainUrl = "https://www.la7.it"
    override var name = "LA7"
    override var lang = "it"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.TvSeries, TvType.Movie)

    private var homeCache: List<HomePageList>? = null

    private suspend fun loadHome(): List<HomePageList> {
        homeCache?.let { return it }

        val lists = mutableListOf<HomePageList>()
        val films = getRivediLa7()
        if (films.isNotEmpty()) lists.add(HomePageList("RivediLa7", films))
        lists.addAll(getProgrammiPerLettera())

        if (lists.isNotEmpty()) homeCache = lists
        return lists
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        return newHomePageResponse(loadHome(), hasNext = false)
    }

    override suspend fun search(query: String, page: Int): SearchResponseList? {
        if (page > 1) return null
        return filterHome(query).toNewSearchResponseList()
    }

    override suspend fun search(query: String): List<SearchResponse> = filterHome(query)

    private suspend fun filterHome(query: String): List<SearchResponse> {
        val q = query.normalized()
        if (q.isBlank()) return emptyList()
        return loadHome()
            .flatMap { it.list }
            .filter { it.name.normalized().contains(q) || it.url.normalized().contains(q) }
            .distinctBy { it.url }
    }

    private fun String.normalized(): String =
        Normalizer.normalize(this, Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "")
            .replace('-', ' ')
            .lowercase()
            .trim()

    private suspend fun getRivediLa7(): List<SearchResponse> {
        val doc = app.get("$mainUrl/la7-cinema-tutti-i-film/rivedila7").document
        val out = mutableListOf<SearchResponse>()

        doc.select("div.ultima_puntata.scritte-sotto").forEach { box ->
            val a = box.selectFirst("a[href]") ?: return@forEach
            val title = box.selectFirst(".title_puntata")?.text()?.trim().orEmpty()
            val poster = box.selectFirst("div.holder-bg")?.bgImage()
            out.add(movieResult(a, title, poster) ?: return@forEach)
        }

        doc.select("div.view-content div.common-item").forEach { row ->
            val a = row.selectFirst("a[href]") ?: return@forEach
            val poster = row.selectFirst("div.bg-img")?.bgImage()
            out.add(movieResult(a, titleOf(a), poster) ?: return@forEach)
        }

        return out.distinctBy { it.url }
    }

    private suspend fun getProgrammiPerLettera(): List<HomePageList> {
        val doc = app.get("$mainUrl/tutti-i-programmi").document
        return doc.select("div.container-programmi h3").mapNotNull { h3 ->
            // la lista e' il fratello successivo dell'h3, non un suo figlio
            val list = h3.nextElementSibling()
                ?.takeIf { it.hasClass("container-programmi-list") }
                ?: return@mapNotNull null

            val items = list.select("div.list-item a[href]").map { a ->
                newTvSeriesSearchResponse(titleOf(a), fixUrl(a.attr("href")), TvType.TvSeries) {
                    this.posterUrl = a.selectFirst("div.image-bg")?.bgImage()
                }
            }.distinctBy { it.url }

            if (items.isEmpty()) null else HomePageList(h3.text().trim(), items)
        }
    }

    private class Ep(val href: String, var name: String?, var poster: String?, var date: String?)

    override suspend fun load(url: String): LoadResponse? {
        val res = app.get(url)
        val html = res.text
        val doc = res.document

        val ogTitle = doc.selectFirst("meta[property=og:title]")?.attr("content")?.trim()
            ?.takeIf { it.isNotBlank() }
        val plot = doc.selectFirst("meta[property=og:description]")?.attr("content")?.trim()
            ?.takeIf { it.isNotBlank() }
        val ogImage = doc.selectFirst("meta[property=og:image]")?.attr("content")?.trim()
            ?.takeIf { it.isNotBlank() }?.let { fixUrl(it) }

        if (url.contains("/rivedila7/") || url.contains("/video/")) {
            return videoLoad(url, html, ogTitle, plot, ogImage)
        }

        val slug = url.trimEnd('/').substringAfterLast('/')
        val eps = LinkedHashMap<String, Ep>()
        collectEpisodes(doc, slug, eps)
        try {
            collectEpisodes(app.get("$mainUrl/$slug/rivedila7").document, slug, eps)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
        }

        if (eps.isEmpty()) {
            return if (html.contains("videoParams")) videoLoad(url, html, ogTitle, plot, ogImage) else null
        }

        val episodes = eps.values.map { ep ->
            newEpisode(ep.href) {
                this.name = listOfNotNull(ep.date, ep.name).joinToString(" - ")
                    .ifBlank { slugTitle(ep.href) }
                this.posterUrl = ep.poster
            }
        }
        return newTvSeriesLoadResponse(ogTitle ?: doc.title(), url, TvType.TvSeries, episodes) {
            this.posterUrl = ogImage
            this.plot = plot
        }
    }

    private suspend fun videoLoad(
        url: String, html: String, ogTitle: String?, plot: String?, ogImage: String?
    ): LoadResponse {
        val params = html.substringAfter("var videoParams", html)
        val title = params.jsField("title") ?: ogTitle ?: slugTitle(url)
        val poster = params.jsField("poster")?.let { if (it.startsWith("//")) "https:$it" else it } ?: ogImage
        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            this.posterUrl = poster
            this.plot = plot
        }
    }

    private fun collectEpisodes(doc: Document, slug: String, out: MutableMap<String, Ep>) {
        doc.select("a[href*=\"/$slug/rivedila7/\"]").forEach { a ->
            val ep = out.getOrPut(fixUrl(a.attr("href"))) { Ep(fixUrl(a.attr("href")), null, null, null) }
            if (ep.name == null) {
                ep.name = (a.selectFirst(".title_puntata") ?: a.selectFirst(".occhiello"))
                    ?.text()?.trim()?.takeIf { it.isNotBlank() }
            }
            if (ep.poster == null) ep.poster = a.selectFirst("[data-background-image]")?.bgImage()
            if (ep.date == null) {
                ep.date = a.selectFirst("div.data")?.text()?.trim()?.takeIf { it.isNotBlank() }
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val html = app.get(data).text
        val src = html.substringAfter("var videoParams", html).substringBefore("chn:")

        // il player elenca piu' file dello stesso video: l'ultimo e' quello con l'audio buono;
        // se il video ha un solo file, e' quello
        val mp4 = Regex("content/entry/data/(\\d+/\\d+)/(\\w+)").findAll(src)
            .map { "https://awsvodpkg.iltrovatore.it/content/entry/data/${it.groupValues[1]}/${it.groupValues[2]}.mp4" }
            .distinct().lastOrNull()
            ?: src.jsonField("mp4")?.cleanUrl()
            ?: return false

        callback(newExtractorLink(name, "$name MP4", mp4, ExtractorLinkType.VIDEO) {
            this.referer = mainUrl
            this.quality = Qualities.Unknown.value
        })
        return true
    }

    private fun String.jsonField(key: String): String? =
        Regex("\"$key\"\\s*:\\s*\"([^\"]+)\"").find(this)?.groupValues?.get(1)

    private fun String.jsField(key: String): String? =
        Regex("\\b$key\\s*:\\s*\"([^\"]*)\"").find(this)?.groupValues?.get(1)?.takeIf { it.isNotBlank() }

    private fun String.cleanUrl(): String {
        val u = replace("\\/", "/")
        return if (u.startsWith("//")) "https:$u" else u
    }

    private fun slugTitle(href: String): String =
        href.trimEnd('/').substringAfterLast('/').replace('-', ' ').replaceFirstChar { it.uppercase() }

    private fun movieResult(a: Element, title: String, poster: String?): SearchResponse? {
        val href = a.attr("href").ifBlank { return null }
        return newMovieSearchResponse(title.ifBlank { titleOf(a) }, fixUrl(href), TvType.Movie) {
            this.posterUrl = poster
        }
    }

    private fun titleOf(a: Element): String {
        a.attr("title").trim().takeIf { it.isNotBlank() }?.let { return it }
        a.selectFirst("img")?.attr("alt")?.trim()?.takeIf { it.isNotBlank() }?.let { return it }
        a.text().trim().takeIf { it.isNotBlank() }?.let { return it }
        return slugTitle(a.attr("href"))
    }

    // lozad scrive lo style via JS: nell'HTML scaricato c'e' solo data-background-image
    private fun Element.bgImage(): String? =
        attr("data-background-image").trim().takeIf { it.isNotBlank() }?.let {
            if (it.startsWith("//")) "https:$it" else fixUrl(it)
        }
}