package eu.kanade.tachiyomi.animeextension.es.animeav1

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
    override fun popularAnimeRequest(page: Int) = GET("$baseUrl/catalogo?page=$page")

    override fun popularAnimeSelector() = "article.anime-card, div.grid a.anime-card, div.grid article a[href^=/media/]"

    override fun popularAnimeFromElement(element: Element): SAnime {
        return SAnime.create().apply {
            val link = element.selectFirst("a[href]")!!
            setUrlWithoutDomain(link.attr("href"))
            title = link.attr("title").ifEmpty { link.text().ifEmpty { element.selectFirst("h2, h3")?.text().orEmpty() } }
            thumbnail_url = element.selectFirst("figure img, div.grid a.anime-card img")?.let { img ->
                img.attr("abs:src").ifEmpty { img.attr("abs:data-src") }
            }
        }
    }

    override fun popularAnimeNextPageSelector(): String = "a[rel=next], .pagination a:matchesOwn((?i)sig|next)"

    // =========================== Anime Details ============================
    override fun animeDetailsParse(document: Document): SAnime {
        return SAnime.create().apply {
            setUrlWithoutDomain(document.location())
            title = document.selectFirst("h1.text-2xl, h1.title, .entry-title")?.text().orEmpty()
            thumbnail_url = document.selectFirst("div.anime-poster img, .poster img, .thumb img, article figure img")
                ?.let { it.attr("abs:src").ifEmpty { it.attr("abs:data-src") } }
            document.selectFirst("div.entry, .entry-content, .sinopsis, .description")?.let {
                description = it.select("p").joinToString("\n") { p -> p.text() }.trim()
            }
            val genres = document.select("div.flex.flex-wrap a.btn-xs, .genres a, .category a, .genxed a")
                .eachText().filter { it.isNotBlank() }
            if (genres.isNotEmpty()) genre = genres.joinToString(", ")
            val text = document.text()
            status = when {
                Regex("(?i)finalizado|completad[oa]").containsMatchIn(text) -> SAnime.COMPLETED
                Regex("(?i)en emisi[oó]n|ongoing").containsMatchIn(text) -> SAnime.ONGOING
                else -> SAnime.UNKNOWN
            }
        }
    }

    // ============================== Episodes ==============================
    override fun episodeListParse(response: Response): List<SEpisode> {
        val document = response.asJsoup()
        val episodes = document.select("div.grid.grid-cols-2 > article")
        return episodes.mapNotNull { element ->
            SEpisode.create().apply {
                val link = element.selectFirst("a") ?: return@mapNotNull null
                setUrlWithoutDomain(link.attr("href"))
                val epNumElement = element.selectFirst("div.bg-line.text-subs.rounded span")
                val epNumStr = epNumElement?.text()?.trim()
                episode_number = epNumStr?.toFloatOrNull() ?: 0f
                name = "Episodio $epNumStr"
                val dateStr = element.selectFirst("time[datetime], span.text-xs, .date, .fecha")?.text()?.trim()
                date_upload = dateStr?.toDate() ?: 0L
            }
        }.reversed()
    }

    // ============================ Video Links =============================
    override fun videoListParse(response: Response): List<Video> {
        val document = response.asJsoup()
        val videos = mutableListOf<Video>()

        document.select("script").firstOrNull { it.data().contains("embeds") }?.data()?.let { scriptData ->
            videos.addAll(extractFromJson(scriptData))
        }

        if (videos.isEmpty()) {
            document.select("iframe, iframe.aspect-video").firstOrNull()?.attr("src")?.let { iframeUrl ->
                videos.addAll(extractFromIframe(iframeUrl))
            }
        }

        return videos.distinctBy { it.url }
    }

    private fun extractFromIframe(url: String): List<Video> {
        val low = url.lowercase()
        return when {
            "dood" in low -> DoodExtractor(client).videosFromUrl(url, "Doodstream")
            "filemoon" in low -> FilemoonExtractor(client).videosFromUrl(url, "Filemoon")
            "mp4upload" in low -> Mp4uploadExtractor(client).videosFromUrl(url, "mp4upload")
            "uqload" in low -> UqloadExtractor(client).videosFromUrl(url, "Uqload")
            "voe" in low -> VoeExtractor(client, headers).videosFromUrl(url, "Voe")
            "streamwish" in low || "wish" in low -> StreamWishExtractor(client, headers).videosFromUrl(url, "StreamWish")
            else -> emptyList()
        }
    }

    private fun extractFromJson(scriptData: String): List<Video> {
        val jsonCandidate = scriptData
            .substringAfter("kit.start(app, element, ")
            .substringBeforeLast("});")
            .trim()
            .replace("void 0", "null")
            .replace("\n", "")

        val videos = mutableListOf<Video>()

        try {
            val root = json.parseToJsonElement(jsonCandidate).jsonObject
            val embeds = root["data"]?.jsonArray
                ?.getOrNull(2)?.jsonObject
                ?.get("data")?.jsonObject
                ?.get("episode")?.jsonObject
                ?.get("embeds")?.jsonObject
                ?: return emptyList()

            embeds.forEach { itEl ->
                val type = (itEl.key as? String)?.lowercase() ?: return@forEach
                val videoList = itEl.value.jsonArray

                videoList.forEach { videoEl ->
                    val obj = videoEl.jsonObject
                    val server = obj["server"]?.jsonPrimitive?.content?.lowercase() ?: return@forEach
                    val url = obj["url"]?.jsonPrimitive?.content ?: return@forEach

                    val newVideos = when (server) {
                        "mp4upload" -> Mp4uploadExtractor(client).videosFromUrl(url, "MP4Upload ($type)")
                        "dood", "doodstream" -> DoodExtractor(client).videosFromUrl(url, "Doodstream ($type)")
                        "filemoon" -> FilemoonExtractor(client).videosFromUrl(url, "Filemoon ($type)")
                        "uqload" -> UqloadExtractor(client).videosFromUrl(url, "Uqload ($type)")
                        "voe" -> VoeExtractor(client, headers).videosFromUrl(url, "Voe ($type)")
                        "streamwish", "wish", "hls" -> StreamWishExtractor(client, headers).videosFromUrl(url, "StreamWish ($type)")
                        else -> emptyList()
                    }
                    videos.addAll(newVideos)
                }
            }
            return videos
        } catch (_: Exception) {
            return emptyList()
        }
    }

    // ============================= Utilities ==============================
    override fun String.toDate(): Long {
        val patterns = listOf("yyyy-MM-dd", "dd/MM/yyyy", "yyyy/MM/dd", "dd-MM-yyyy")
        for (p in patterns) {
            try {
                val d = SimpleDateFormat(p, Locale.US).parse(this)
                if (d != null) return d.time
            } catch (_: Exception) { }
        }
        return 0L
    }

    // ============================== Search ================================
    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        return if (query.isNotBlank()) {
            GET("$baseUrl/catalogo?search=$query&page=$page".replace("?page=1", ""), headers)
        } else {
            popularAnimeRequest(page)
        }
    }

    override fun searchAnimeSelector() = "article.anime-card, div.grid a.anime-card"

    override fun searchAnimeFromElement(element: Element): SAnime {
        return SAnime.create().apply {
            val link = element.selectFirst("a[href]")!!
            setUrlWithoutDomain(link.attr("href"))
            title = element.selectFirst("h3")?.text() ?: link.attr("title")
            thumbnail_url = element.selectFirst("img")?.attr("abs:src")?.takeIf { it.isNotBlank() }
                ?: element.selectFirst("img")?.attr("abs:data-src")
        }
    }

    override fun searchAnimeNextPageSelector() = popularAnimeNextPageSelector()

    override fun getFilterList() = AnimeFilterList()
}
