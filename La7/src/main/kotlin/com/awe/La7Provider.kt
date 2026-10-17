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
    private val nameOverrides = mapOf(
        "100minuti" to "100 Minuti",
        "bellitalia-in-viaggio" to "Bell'italia in viaggio",
        "atlantidefiles" to "Atlantide Files",
        "bellidentrobellifuori" to "Bellli dentro belli fuori",
        "charlie-s-angels" to "Charlie's Angels",
        "figworldcup" to "Coppa del mondo di ritmica",
        "crozza" to "Crozza nel Paese delle Meraviglie",
        "dimartedi" to "DiMartedì",
        "e-quella-giusta" to "È quella giusta",
        "speciali-ezio-mauro" to "Ezio Mauro",
        "facciaafaccia" to "Faccia a Faccia",
        "famiglie-ditalia" to "Famiglie d'Italia",
        "webserie" to "Food Maniac",
        "honestlygood" to "Honestly Good",
        "house-of-trump-la-serie" to "House of Trump",
        "i-menù-di-benedetta" to "I Menù di Benedetta",
        "ilpolliceverdesonoio" to "Il Pollice Verde Sono Io",
        "l-assaggiastorie" to "L'assaggiastorie",
        "laria-che-tira" to "L'aria che tira",
        "lariadestate" to "L'aria d'estate",
        "lera-delle-macchine" to "L'era delle Macchine",
        "lerba-del-vicino" to "L'erba del Vicino",
        "lingrediente-perfetto" to "L'ingrediente perfetto",
        "litaliapiubellachece" to "L'Italia più Bella che c'è",
        "lora-della-salute" to "L'Ora della Salute",
        "lunionefalaforza" to "L'unione fa la forza",
        "lafelicitanoneunatruffa" to "La Felicità non è una Truffa",
        "la7cult" to "La7 Cult",
        "la7magazine" to "La7 Magazine",
        "la7ricorda" to "La7 Ricorda",
        "la7risponde" to "La7 Original Risponde",
        "la7it-original" to "La7 Original",
        "la725anni-un-racconto-italiano" to "La7 25 anni - Un racconto Italiano",
        "la7-cinema-tutti-i-film" to "La7 Cinema",
        "la7racconta" to "La7 Racconta",
        "la7speciali" to "La7 Speciali",
        "la7venti" to "La7 Venti",
        "mercanti-dellanima" to "Mercanti dell'anima",
        "meteola7" to "Meteo La7",
        "donpasta" to "Natale con Donpasta",
        "nonelarena" to "Non È L'Arena",
        "oliver-stone-nuclear-now" to "Nuclear Now",
        "pinkisgood" to "Pink is Good",
        "trash-it" to "Post It & Trash It",
        "prova-dinchiesta" to "Prova d'inchiesta",
        "raffaella-carra" to "Raffaella Carrà",
        "startupeconomy" to "Startup Economy",
        "storie-dellaltro-social" to "Storie dell'altro social",
        "tacco12enonsolo" to "Tacco 12... e non solo!",
        "tagada" to "Tagadà",
        "taste-il-gusto-delleccellenza" to "Taste - Il gusto dell'eccellenza",
        "tgla7" to "Tg La7",
        "the-show-must-go-off" to "The Show Must Go Off"
    )
    private val hiddenSlugs = setOf("le-copertine-di-crozza", "eccezionale-veramente-ma-veramente", "welovecinema", "euro-dieci", "i-responsabili", "il-mondo-che-verra", "josephineangegardien", "lofficina-delle-erbe", "little-murders", "quattrodonneeunfunerale", "recital-di-corrado-guzzanti", "si-parla-di")
    private val notReversedSlugs = setOf("m-il-figlio-del-secolo", "hawthorne", "house-of-trump-la-serie")

    private val dateRegex = Regex("\\d{1,2}[/.]\\d{1,2}[/.]\\d{2,4}")
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
            val list = h3.nextElementSibling()
                ?.takeIf { it.hasClass("container-programmi-list") }
                ?: return@mapNotNull null

            val items = list.select("div.list-item a[href]")
                .filterNot { it.attr("href").trimEnd('/').substringAfterLast('/') in hiddenSlugs }
                .map { a ->
                val href = fixUrl(a.attr("href"))
                val title = nameOverrides[href.trimEnd('/').substringAfterLast('/')] ?: titleOf(a)
                newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
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
        collectEpisodes(doc, "a[href*=\"/$slug/rivedila7/\"]", eps)
        var next = 0
        var more = true
        while (more && next < 200) {
            val pages = (next until next + 5).toList().amap { p ->
                val pageUrl = if (p == 0) "$mainUrl/$slug/rivedila7" else "$mainUrl/$slug/rivedila7?page=$p"
                try {
                    app.get(pageUrl).document
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    null
                }
            }
            for (pageDoc in pages) {
                val before = eps.size
                if (pageDoc != null) collectEpisodes(pageDoc, "div.common-item a[href*=\"/rivedila7/\"]", eps)
                if (eps.size == before) {
                    more = false
                    break
                }
            }
            next += 5
        }

        if (eps.isEmpty()) {
            return if (html.contains("videoParams")) videoLoad(url, html, ogTitle, plot, ogImage) else null
        }

        val episodes = (if (slug in notReversedSlugs) eps.values.toList() else eps.values.reversed()).map { ep ->
            newEpisode(ep.href) {
                val n = ep.name
                this.name = when {
                    n == null -> ep.date ?: slugTitle(ep.href)
                    ep.date == null || dateRegex.containsMatchIn(n) -> n
                    else -> "${ep.date} - $n"
                }
                this.posterUrl = ep.poster
            }
        }
        return newTvSeriesLoadResponse(nameOverrides[slug] ?: ogTitle ?: doc.title(), url, TvType.TvSeries, episodes) {
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

    private fun collectEpisodes(doc: Document, selector: String, out: MutableMap<String, Ep>) {
        doc.select(selector).forEach { a ->
            val ep = out.getOrPut(fixUrl(a.attr("href"))) { Ep(fixUrl(a.attr("href")), null, null, null) }
            if (ep.name == null) {
                ep.name = (a.selectFirst(".title_puntata") ?: a.selectFirst(".occhiello") ?: a.selectFirst(".title"))
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

    private fun Element.bgImage(): String? =
        attr("data-background-image").trim().takeIf { it.isNotBlank() }?.let {
            if (it.startsWith("//")) "https:$it" else fixUrl(it)
        }
}