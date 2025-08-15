package eu.kanade.tachiyomi.animeextension.es.henaojara

import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.lib.doodextractor.DoodExtractor
import eu.kanade.tachiyomi.lib.filemoonextractor.FilemoonExtractor
import eu.kanade.tachiyomi.lib.mixdropextractor.MixDropExtractor
import eu.kanade.tachiyomi.lib.okruextractor.OkruExtractor
import eu.kanade.tachiyomi.lib.streamtapeextractor.StreamTapeExtractor
import eu.kanade.tachiyomi.lib.streamwishextractor.StreamWishExtractor
import eu.kanade.tachiyomi.lib.uqloadextractor.UqloadExtractor
import eu.kanade.tachiyomi.lib.vidguardextractor.VidGuardExtractor
import eu.kanade.tachiyomi.lib.voeextractor.VoeExtractor
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import extensions.utils.getPreferencesLazy
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.util.regex.Pattern

class Henaojara : ConfigurableAnimeSource, ParsedAnimeHttpSource() {

    override val name = "Henaojara"
    override val baseUrl = "https://vwv.henaojara.net"
    override val lang = "es"
    override val supportsLatest = true

    private val preferences by getPreferencesLazy()
    private val gson by lazy { Gson() }

    // =============================== Popular ==============================

    override fun popularAnimeRequest(page: Int): Request {
        val url = "$baseUrl/animes?tipo=anime&pag=$page".toHttpUrlOrNull() ?: throw Exception("URL de populares mal formada")
        return GET(url.toString(), headers)
    }

    override fun popularAnimeSelector() = "div.ul article.li"

    override fun popularAnimeFromElement(element: Element) = SAnime.create().apply {
        setUrlWithoutDomain(element.selectFirst("a")!!.attr("href"))
        title = element.selectFirst("h3 a")!!.text()
        thumbnail_url = element.selectFirst("img")?.attr("data-src")
    }

    override fun popularAnimeNextPageSelector() = "ul.pagination > li.active ~ li:has(a)"

    // =============================== Latest ===============================

    override fun latestUpdatesRequest(page: Int): Request = GET(baseUrl)

    override fun latestUpdatesSelector() = "div.ul.hm article.li"

    override fun latestUpdatesFromElement(element: Element) = SAnime.create().apply {
        val titleElement = element.selectFirst("h3 a")
        val episodeTitle = element.selectFirst("span.episode-num")?.text() ?: ""
        val animeTitle = titleElement?.text() ?: ""
        title = "$episodeTitle $animeTitle".trim()
        val episodeUrl = titleElement!!.attr("href")
        val seriesSlug = episodeUrl.substringAfter("/ver/").substringBeforeLast("-")
        setUrlWithoutDomain("/anime/$seriesSlug")
        thumbnail_url = element.selectFirst("img")?.attr("data-src")
    }

    override fun latestUpdatesNextPageSelector() = null

    // =============================== Search ===============================

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val url = "$baseUrl/animes?buscar=$query&pag=$page".toHttpUrlOrNull() ?: throw Exception("URL de busqueda mal formada")
        return GET(url.toString(), headers)
    }

    override fun searchAnimeSelector() = "div.ul article.li"

    override fun searchAnimeFromElement(element: Element) = SAnime.create().apply {
        setUrlWithoutDomain(element.selectFirst("a")!!.attr("href"))
        title = element.selectFirst("h3 a")!!.text()
        thumbnail_url = element.selectFirst("img")?.attr("data-src")
    }

    override fun searchAnimeNextPageSelector() = "ul.pagination > li.active ~ li:has(a)"

    override fun getFilterList() = AnimeFilterList(AnimeFilter.Header("La búsqueda por texto ignora el filtro"))

    // =========================== Anime Details ============================

    override fun animeDetailsParse(document: Document) = SAnime.create().apply {
        title = document.selectFirst("h1")?.text() ?: ""
        genre = document.select("a[href*=/genero/]").eachText().joinToString(", ")
        description = document.selectFirst("div.tx p")?.text()
        status = when (document.selectFirst("span.e")?.text()) {
            "En emision" -> SAnime.ONGOING
            "Terminado" -> SAnime.COMPLETED
            else -> SAnime.UNKNOWN
        }
    }

    // ============================== Episodes ==============================

    override fun episodeListParse(response: Response): List<SEpisode> {
        val document = response.asJsoup()
        val script = document.select("script").html()
        val pattern = Pattern.compile("var eps = \\[.*\\];")
        val matcher = pattern.matcher(script)
        if (!matcher.find()) return emptyList()

        val jsonString = matcher.group(0).substringAfter("var eps = ").substringBefore(";")
        val type = object : TypeToken<List<List<String>>>() {}.type
        val eps = gson.fromJson<List<List<String>>>(jsonString, type)

        val animeSlug = response.request.url.pathSegments.lastOrNull()?.substringBefore("-") ?: ""
        return eps.map {
            SEpisode.create().apply {
                episode_number = it.getOrNull(0)?.toFloatOrNull() ?: 0F
                name = "Episodio ${it.getOrNull(0) ?: "0"}"
                setUrlWithoutDomain("/ver/$animeSlug-${it.getOrNull(0)}")
            }
        }.reversed()
    }

    override fun episodeListSelector() = throw UnsupportedOperationException()
    override fun episodeFromElement(element: Element) = throw UnsupportedOperationException()

    // ============================ Video Links =============================

    private val doodExtractor by lazy { DoodExtractor(client) }
    private val filemoonExtractor by lazy { FilemoonExtractor(client) }
    private val mixDropExtractor by lazy { MixDropExtractor(client) }
    private val okruExtractor by lazy { OkruExtractor(client) }
    private val streamtapeExtractor by lazy { StreamTapeExtractor(client) }
    private val streamWishExtractor by lazy { StreamWishExtractor(client, headers) }
    private val uqloadExtractor by lazy { UqloadExtractor(client) }
    private val vidguardExtractor by lazy { VidGuardExtractor(client) }
    private val voeExtractor by lazy { VoeExtractor(client) }

    override fun videoListParse(response: Response): List<Video> {
        val document = response.asJsoup()
        val videoList = mutableListOf<Video>()
        val script = document.select("script:containsData(player.src)").lastOrNull()?.data() ?: return emptyList()

        val embedUrlRegex = """player\.src\s*=\s*['"](.*?)['"]""".toRegex()
        val playerUrl = embedUrlRegex.find(script)?.groupValues?.get(1) ?: return emptyList()

        when {
            "dood" in playerUrl -> doodExtractor.videosFromUrl(playerUrl)?.let { videoList.addAll(it) }
            "filemoon" in playerUrl -> filemoonExtractor.videosFromUrl(playerUrl)?.let { videoList.addAll(it) }
            "streamwish" in playerUrl -> streamWishExtractor.videosFromUrl(playerUrl)?.let { videoList.addAll(it) }
            "mixdrop" in playerUrl -> mixDropExtractor.videosFromUrl(playerUrl)?.let { videoList.addAll(it) }
            "ok.ru" in playerUrl -> okruExtractor.videosFromUrl(playerUrl)?.let { videoList.addAll(it) }
            "streamtape" in playerUrl -> streamtapeExtractor.videosFromUrl(playerUrl)?.let { videoList.addAll(it) }
            "uqload" in playerUrl -> uqloadExtractor.videosFromUrl(playerUrl)?.let { videoList.addAll(it) }
            "vidguard" in playerUrl -> vidguardExtractor.videosFromUrl(playerUrl)?.let { videoList.addAll(it) }
            "voe" in playerUrl -> voeExtractor.videosFromUrl(playerUrl)?.let { videoList.addAll(it) }
        }

        return videoList.distinctBy { it.url }
    }

    override fun videoListSelector() = throw UnsupportedOperationException()
    override fun videoFromElement(element: Element) = throw UnsupportedOperationException()
    override fun videoUrlParse(document: Document) = throw UnsupportedOperationException()

    // ============================= Utilities ==============================

    override fun List<Video>.sort(): List<Video> {
        val quality = preferences.getString("preferred_quality", "1080")!!
        val server = preferences.getString("preferred_server", "filemoon")!!
        return this.sortedWith(
            compareByDescending<Video> { it.quality.contains(server, true) }
                .thenByDescending { it.quality.contains(quality, true) },
        )
    }

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        val servers = listOf(
            "filemoon", "doodstream", "streamwish", "mixdrop", "voe", "uqload", "okru", "streamtape", "vidguard",
        )
        val serverPref = ListPreference(screen.context).apply {
            key = "preferred_server"
            title = "Servidor preferido"
            entries = servers.toTypedArray()
            entryValues = servers.toTypedArray()
            setDefaultValue("filemoon")
            summary = "%s"
            setOnPreferenceChangeListener { _, newValue ->
                preferences.edit().putString(key, newValue as String).commit()
            }
        }
        screen.addPreference(serverPref)

        val videoQualityPref = ListPreference(screen.context).apply {
            key = "preferred_quality"
            title = "Calidad preferida"
            entries = arrayOf("1080p", "720p", "480p", "360p", "240p")
            entryValues = arrayOf("1080", "720", "480", "360", "240")
            setDefaultValue("1080")
            summary = "%s"
            setOnPreferenceChangeListener { _, newValue ->
                preferences.edit().putString(key, newValue as String).commit()
            }
        }
        screen.addPreference(videoQualityPref)
    }
}
