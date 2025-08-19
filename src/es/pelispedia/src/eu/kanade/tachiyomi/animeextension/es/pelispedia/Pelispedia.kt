package eu.kanade.tachiyomi.animeextension.es.pelispedia

import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.io.IOException

class Pelispedia : ParsedAnimeHttpSource() {

    override val name = "pelispedia"

    override val baseUrl = "https://pelispedia.is"

    override val lang = "es"

    override val supportsLatest = false

    // ============================== Populares ===============================
    override fun popularAnimeRequest(page: Int): Request {
        return GET("$baseUrl/cartelera-peliculas/page/$page/")
    }

    override fun popularAnimeSelector(): String = "div.MovieBlock > a.Block"

    override fun popularAnimeFromElement(element: Element): SAnime {
        val anime = SAnime.create()
        anime.title = element.select("div.Title").text()
        anime.thumbnail_url = element.select("img").attr("src")
        anime.setUrlWithoutDomain(element.attr("href"))
        return anime
    }

    override fun popularAnimeNextPageSelector(): String = "a.nextpostslink"

    // =============================== Búsqueda ================================
    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        return GET("$baseUrl/page/$page/?s=$query")
    }

    override fun searchAnimeSelector(): String = popularAnimeSelector()

    override fun searchAnimeFromElement(element: Element): SAnime = popularAnimeFromElement(element)

    override fun searchAnimeNextPageSelector(): String = popularAnimeNextPageSelector()

    // ========================= Detalles del Título ==========================
    override fun animeDetailsParse(document: Document): SAnime {
        val anime = SAnime.create()
        anime.title = document.select("h1.entry-title").text()
        anime.thumbnail_url = document.select("div.post-thumbnail figure img").attr("src")
        anime.description = document.select("div.description").text().trim()
        val genres = document.select("span.genres a").eachText()
        if (genres.isNotEmpty()) {
            anime.genre = genres.joinToString(", ")
        }
        anime.status = if (document.location().contains("/pelicula/")) SAnime.COMPLETED else SAnime.UNKNOWN
        return anime
    }

    // ============================ Lista de episodios ============================
    override fun episodeListParse(response: Response): List<SEpisode> {
        val episodes = mutableListOf<SEpisode>()
        val document = response.asJsoup()
        if (document.location().contains("/pelicula/")) {
            val episode = SEpisode.create().apply {
                episode_number = 1F
                name = document.selectFirst("h1.entry-title")?.text() ?: "Película"
                setUrlWithoutDomain(document.location())
            }
            episodes.add(episode)
        } else {
            document.select("ul#episode_by_temp article.post").forEach { element ->
                val episode = SEpisode.create().apply {
                    setUrlWithoutDomain(element.select("a.lnk-blk").attr("href"))
                    name = element.select("h2.entry-title").text()
                    val episodeNumber = element.select("span.num-epi").text().split("x").last().toFloatOrNull() ?: 1.0f
                    episode_number = episodeNumber
                }
                episodes.add(episode)
            }
        }
        return episodes.reversed()
    }

    override fun episodeListSelector(): String {
        throw UnsupportedOperationException()
    }

    override fun episodeFromElement(element: Element): SEpisode {
        throw UnsupportedOperationException()
    }

    // ============================ Enlaces de video ============================
    override fun videoListParse(response: Response): List<Video> {
        val document = response.asJsoup()
        val videos = mutableListOf<Video>()

        // El video se encuentra en los enlaces de descarga, dentro de una tabla.
        document.select("div.download-links a.btn").forEach { link ->
            val downloadUrl = link.attr("href")

            // Subimos al elemento padre (<tr>) para encontrar la calidad del video.
            val row = link.parents().firstOrNull { it.tagName() == "tr" }
            val quality = row?.selectFirst("td:nth-child(3)")?.text()?.trim() ?: "Descarga Directa"
            val server = row?.selectFirst("td:nth-child(1)")?.text()?.trim() ?: "Servidor"

            if (downloadUrl.isNotBlank()) {
                videos.add(Video(downloadUrl, "$server - $quality", downloadUrl, headers = null))
            }
        }

        // También busca videos en el reproductor incrustado.
        document.select("div#plays iframe, div.embed-player iframe").firstOrNull()?.attr("src")?.let { iframeUrl ->
            try {
                // Hacemos una petición para obtener el video del reproductor.
                val playerDoc = client.newCall(GET(iframeUrl, headers)).execute().asJsoup()
                playerDoc.select("video source").firstOrNull()?.attr("src")?.let { videoUrl ->
                    videos.add(Video(videoUrl, "Reproductor interno", videoUrl, headers = null))
                }
            } catch (e: Exception) {
                // Manejar errores si no se puede conectar o no se encuentra el video
            }
        }

        return videos
    }

    override fun videoListSelector(): String {
        throw IOException("La extracción de videos se realiza manualmente.")
    }

    override fun videoFromElement(element: Element): Video {
        throw IOException("La extracción de videos se realiza manualmente.")
    }

    override fun getFilterList() = AnimeFilterList()
}
