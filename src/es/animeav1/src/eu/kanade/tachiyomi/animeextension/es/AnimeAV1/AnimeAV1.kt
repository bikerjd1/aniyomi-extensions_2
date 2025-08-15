package eu.kanade.tachiyomi.animeextension.es.animeav1

import android.util.Log
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.lib.doodextractor.DoodExtractor
import eu.kanade.tachiyomi.lib.filemoonextractor.FilemoonExtractor
import eu.kanade.tachiyomi.lib.mp4uploadextractor.Mp4uploadExtractor
import eu.kanade.tachiyomi.lib.streamwishextractor.StreamWishExtractor
import eu.kanade.tachiyomi.lib.uqloadextractor.UqloadExtractor
import eu.kanade.tachiyomi.lib.voeextractor.VoeExtractor
import eu.kanade.tachiyomi.multisrc.dooplay.DooPlay
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.text.SimpleDateFormat
import java.util.Locale

class AnimeAV1 : DooPlay(
    "es",
    "AnimeAV1",
    "https://animeav1.com",
) {
    private val json = Json { ignoreUnknownKeys = true }

    // ============================== Popular ===============================
    override fun popularAnimeRequest(page: Int) = GET("$baseUrl/populares/page/$page")

    override fun popularAnimeSelector() = "article.anime-card"

    override fun popularAnimeFromElement(element: Element): SAnime {
        return SAnime.create().apply {
            val link = element.selectFirst("a.absolute")!!
            setUrlWithoutDomain(link.attr("href"))
            title = link.attr("title")
            thumbnail_url = element.selectFirst("img")?.attr("src")
        }
    }

    override fun popularAnimeNextPageSelector(): String = "a[rel=next]"

    // ============================== Latest ================================
    override fun latestUpdatesRequest(page: Int) = GET("$baseUrl/estrenos/page/$page")

    override fun latestUpdatesSelector() = popularAnimeSelector()

    override fun latestUpdatesFromElement(element: Element) = popularAnimeFromElement(element)

    override fun latestUpdatesNextPageSelector() = popularAnimeNextPageSelector()

    // =========================== Anime Details ============================
    override fun animeDetailsParse(document: Document): SAnime {
        return SAnime.create().apply {
            setUrlWithoutDomain(document.location())
            title = document.selectFirst("h1.text-2xl")?.text() ?: ""
            thumbnail_url = document.selectFirst("div.anime-poster img")?.attr("src")

            document.selectFirst("div.entry")?.let {
                description = it.select("p").text()
            }

            genre = document.select("div.flex.flex-wrap a.btn-xs").eachText().joinToString(", ")

            status = when (document.select("div.flex.flex-wrap span").getOrNull(3)?.text()) {
                "Finalizado" -> SAnime.COMPLETED
                "En emision" -> SAnime.ONGOING
                else -> SAnime.UNKNOWN
            }
        }
    }

    // ============================== Episodes ==============================
    override fun episodeListParse(response: Response): List<SEpisode> {
        val document = response.asJsoup()
        return document.select("div#episodes-list a").mapNotNull { element ->
            SEpisode.create().apply {
                setUrlWithoutDomain(element.attr("href"))
                name = "Episodio ${element.select("span.text-nowrap").text()}"
                episode_number = element.select("span.text-nowrap").text().toFloatOrNull() ?: 0f
                date_upload = element.selectFirst("span.text-xs")?.text()?.toDate() ?: 0L
            }
        }.reversed()
    }

    // ============================ Video Links =============================
    override fun videoListParse(response: Response): List<Video> {
        val document = response.asJsoup()
        val videos = mutableListOf<Video>()

        // Opción 1: Reproductor principal (iframe)
        document.select("iframe.aspect-video").firstOrNull()?.attr("src")?.let { iframeUrl ->
            videos.addAll(extractFromIframe(iframeUrl))
        }

        // Opción 2: Servidores alternativos (JSON)
        document.select("script").find { script ->
            script.data().contains("__sveltekit_")
        }?.data()?.let { scriptData ->
            videos.addAll(extractFromJson(scriptData))
        }

        return videos
    }

    private fun extractFromIframe(url: String): List<Video> {
        return when {
            "dood" in url -> DoodExtractor(client).videosFromUrl(url, "Doodstream")
            "filemoon" in url -> FilemoonExtractor(client).videosFromUrl(url, "Filemoon")
            "mp4upload" in url -> Mp4uploadExtractor(client).videosFromUrl(url, "Mp4upload")
            "uqload" in url -> UqloadExtractor(client).videosFromUrl(url, "Uqload")
            "voe" in url -> VoeExtractor(client).videosFromUrl(url, "Voe")
            "streamwish" in url -> StreamWishExtractor(client, headers).videosFromUrl(url, "StreamWish")
            else -> emptyList()
        }
    }

    private fun extractFromJson(scriptData: String): List<Video> {
        val jsonData = scriptData.substringAfter("__sveltekit_").substringAfter("=")
            .substringBefore(";").trim()

        return try {
            val jsonObj = json.parseToJsonElement(jsonData).jsonObject
            val embeds = jsonObj["data"]?.jsonArray?.get(3)?.jsonObject
                ?.get("data")?.jsonObject?.get("embeds")?.jsonObject
                ?.get("SUB")?.jsonArray ?: return emptyList()

            embeds.mapNotNull { embed ->
                val server = embed.jsonObject["server"]?.jsonPrimitive?.content ?: return@mapNotNull null
                val url = embed.jsonObject["url"]?.jsonPrimitive?.content ?: return@mapNotNull null

                when (server.lowercase()) {
                    "doodstream" -> DoodExtractor(client).videosFromUrl(url, "Doodstream")
                    "filemoon" -> FilemoonExtractor(client).videosFromUrl(url, "Filemoon")
                    "mp4upload" -> Mp4uploadExtractor(client).videosFromUrl(url, "Mp4upload")
                    "uqload" -> UqloadExtractor(client).videosFromUrl(url, "Uqload")
                    "voe" -> VoeExtractor(client).videosFromUrl(url, "Voe")
                    "streamwish" -> StreamWishExtractor(client, headers).videosFromUrl(url, "StreamWish")
                    else -> emptyList()
                }
            }.flatten()
        } catch (e: Exception) {
            Log.e("AnimeAV1", "Error parsing JSON", e)
            emptyList()
        }
    }

    // ============================= Utilities ==============================
    private fun String.toDate(): Long {
        return try {
            SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(this)?.time ?: 0L
        } catch (e: Exception) {
            0L
        }
    }

    // ============================== Search ================================
    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        return if (query.isNotBlank()) {
            GET("$baseUrl/buscar/$query/page/$page", headers)
        } else {
            popularAnimeRequest(page)
        }
    }

    override fun searchAnimeSelector() = popularAnimeSelector()

    override fun searchAnimeFromElement(element: Element) = popularAnimeFromElement(element)

    override fun searchAnimeNextPageSelector() = popularAnimeNextPageSelector()

    // ============================= Filters ================================
    override fun getFilterList() = AnimeFilterList()
}
