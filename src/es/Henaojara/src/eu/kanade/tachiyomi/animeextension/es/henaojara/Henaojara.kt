package eu.kanade.tachiyomi.animeextension.es.henaojara

import android.util.Log
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
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

class Henaojara : ConfigurableAnimeSource, ParsedAnimeHttpSource() {

    override val name = "Henaojara"
    override val baseUrl = "https://henaojara.com"
    override val lang = "es"
    override val supportsLatest = true

    private val preferences by getPreferencesLazy()

    // =============================== Sección Popular ==============================

    override fun popularAnimeRequest(page: Int): Request = GET("$baseUrl/animeonline/category/categorias/page/$page/", headers)

    override fun popularAnimeSelector() = "ul.MovieList.NoLmtxt li.TPostMv article.TPost"

    override fun popularAnimeFromElement(element: Element) = SAnime.create().apply {
        setUrlWithoutDomain(element.selectFirst("a")!!.attr("href"))
        title = element.selectFirst("h3.Title")!!.text()
        thumbnail_url = element.selectFirst("img")?.attr("src")
    }

    override fun popularAnimeNextPageSelector() = "div.pagination > ul > li.active + li a"

    // =============================== Últimas Actualizaciones ===============================

    override fun latestUpdatesRequest(page: Int): Request = GET("$baseUrl/animeonline/category/estrenos/page/$page/", headers)

    override fun latestUpdatesSelector() = "ul.MovieList.NoLmtxt li.TPostMv article.TPost"

    override fun latestUpdatesFromElement(element: Element) = SAnime.create().apply {
        setUrlWithoutDomain(element.selectFirst("a")!!.attr("href"))
        title = element.selectFirst("h3.Title")!!.text()
        thumbnail_url = element.selectFirst("img")?.attr("src")
    }

    override fun latestUpdatesNextPageSelector() = "div.pagination > ul > li.active + li a"

    // =============================== Búsqueda ===============================

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val filterList = if (filters.isEmpty()) getFilterList() else filters
        val categoryFilter = filterList.find { it is CategoryFilter } as? CategoryFilter

        return if (categoryFilter != null && categoryFilter.state > 0) {
            val categorySlug = getCategoryList()[categoryFilter.state].second
            val requestUrl: String

            when (categorySlug) {
                "emision", "estrenos", "pelicula", "sci-fi-fantasy", "sin-categoria" -> {
                    requestUrl = "$baseUrl/animeonline/category/$categorySlug/page/$page/"
                }
                else -> {
                    requestUrl = "$baseUrl/animeonline/category/categorias/$categorySlug/page/$page/"
                }
            }
            Log.d("Henaojara", "DEBUG: URL de solicitud de categoría filtrada: $requestUrl")
            GET(requestUrl, headers)
        } else {
            val url = "$baseUrl/page/$page/?s=$query".toHttpUrlOrNull() ?: throw Exception("URL de búsqueda mal formada")
            Log.d("Henaojara", "DEBUG: URL de solicitud de búsqueda: $url")
            GET(url.toString(), headers)
        }
    }

    override fun searchAnimeSelector() = "ul.MovieList.NoLmtxt li.TPostMv article.TPost"

    override fun searchAnimeFromElement(element: Element) = SAnime.create().apply {
        setUrlWithoutDomain(element.selectFirst("a")!!.attr("href"))
        title = element.selectFirst("h3.Title")!!.text()
        thumbnail_url = element.selectFirst("img")?.attr("src")
    }

    override fun searchAnimeNextPageSelector() = "div.pagination > ul > li.active + li a"

    // =========================== Detalles del Anime ============================

    override fun animeDetailsParse(document: Document) = SAnime.create().apply {
        title = document.selectFirst("h1.Title")?.text() ?: ""
        genre = document.select("div.Genrs a").eachText().joinToString(", ")
        description = document.selectFirst("div.Description")?.text()
        status = when (document.selectFirst("div.PostInfo span.status")?.text()) {
            "En emision" -> SAnime.ONGOING
            "Finalizada" -> SAnime.COMPLETED
            else -> SAnime.UNKNOWN
        }
        thumbnail_url = document.selectFirst("div.Image img")?.attr("src")
    }

    // ============================== Episodios ==============================

    override fun episodeListParse(response: Response): List<SEpisode> {
        val document = response.asJsoup()
        val episodeList = mutableListOf<SEpisode>()
        val genres = document.select("div.Genrs a").eachText()

        val animeSlug = response.request.url.pathSegments.lastOrNull() ?: return emptyList()

        if (genres.any { it.contains("Peliculas", true) || it.contains("Pelicula", true) }) {
            episodeList.add(
                SEpisode.create().apply {
                    episode_number = 1f
                    name = "Película"
                    setUrlWithoutDomain("$baseUrl/animeonline/episode/$animeSlug-1x1/")
                },
            )
            return episodeList
        }

        val episodesText = document.selectFirst("div.container.status-container")?.text()
        val episodeCountMatch = "(\\d+)\\s+Episodios".toRegex().find(episodesText.orEmpty())
        val episodeCount = episodeCountMatch?.groupValues?.get(1)?.toIntOrNull() ?: 0

        for (i in 1..episodeCount) {
            val episodeUrl = "$baseUrl/animeonline/episode/$animeSlug-1x$i/"
            episodeList.add(
                SEpisode.create().apply {
                    episode_number = i.toFloat()
                    name = "Episodio $i"
                    setUrlWithoutDomain(episodeUrl)
                },
            )
        }

        return episodeList.reversed()
    }

    override fun episodeListSelector() = throw UnsupportedOperationException()
    override fun episodeFromElement(element: Element) = throw UnsupportedOperationException()

    // ============================ Enlaces de Video =============================

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
        Log.d("Henaojara", "Iniciando videoListParse para URL: ${response.request.url}")

        val playerOptions = document.select("div.TPlayerNv .Button.STPb")
        if (playerOptions.isEmpty()) {
            Log.e("Henaojara", "ERROR: No se encontraron botones de reproductor. La estructura HTML pudo haber cambiado en: ${response.request.url}")
            val directIframe = document.selectFirst("iframe[src]")
            if (directIframe != null) {
                val iframeSrc = directIframe.attr("src")
                Log.w("Henaojara", "ADVERTENCIA: No hay botones de reproductor, pero se encontró un iframe directo. Intentando extraer de él.")
                when {
                    "dood" in iframeSrc -> doodExtractor.videosFromUrl(iframeSrc)?.let { videoList.addAll(it) }
                    "filemoon" in iframeSrc -> filemoonExtractor.videosFromUrl(iframeSrc)?.let { videoList.addAll(it) }
                    "streamwish" in iframeSrc -> streamWishExtractor.videosFromUrl(iframeSrc)?.let { videoList.addAll(it) }
                    "mixdrop" in iframeSrc -> mixDropExtractor.videosFromUrl(iframeSrc)?.let { videoList.addAll(it) }
                    "ok.ru" in iframeSrc -> okruExtractor.videosFromUrl(iframeSrc)?.let { videoList.addAll(it) }
                    "streamtape" in iframeSrc -> streamtapeExtractor.videosFromUrl(iframeSrc)?.let { videoList.addAll(it) }
                    "uqload" in iframeSrc -> uqloadExtractor.videosFromUrl(iframeSrc)?.let { videoList.addAll(it) }
                    "vidguard" in iframeSrc -> vidguardExtractor.videosFromUrl(iframeSrc)?.let { videoList.addAll(it) }
                    "voe" in iframeSrc -> voeExtractor.videosFromUrl(iframeSrc)?.let { videoList.addAll(it) }
                    else -> {
                        videoList.add(Video(iframeSrc, "Iframe Directo - Fallback", iframeSrc))
                        Log.w("Henaojara", "ADVERTENCIA: Iframe directo desconocido añadido como video directo: $iframeSrc")
                    }
                }
            } else {
                Log.e("Henaojara", "ERROR: No se encontraron botones de reproductor, y no hay iframe directo en la página principal.")
            }
            if (videoList.isEmpty()) {
                Log.e("Henaojara", "FINAL: No se encontraron videos ni con el fallback. Revisa el HTML de la página principal para cambios en el reproductor.")
            } else {
                Log.d("Henaojara", "FINAL: Se encontraron ${videoList.size} videos (del fallback).")
            }
            return videoList.distinctBy { it.url }
        }

        playerOptions.forEach { option ->
            val playerUrl = option.attr("data-url").ifEmpty {
                option.attr("data-player-url").ifEmpty {
                    option.attr("onclick")
                        .substringAfter("change_player('", "")
                        .substringBefore("');", "")
                }
            }

            if (playerUrl.isNotEmpty()) {
                Log.d("Henaojara", "Procesando URL del reproductor desde el botón: $playerUrl")
                try {
                    when {
                        // Manejar URLs de reproductores específicos de JKAnime desde Henaojara
                        "jkplayer.net" in playerUrl || "jkanime.net" in playerUrl -> {
                            Log.d("Henaojara", "Reproductor JKAnime detectado: $playerUrl")
                            val playerResponse = client.newCall(GET(playerUrl, headers)).execute()
                            val playerDocument = playerResponse.asJsoup()
                            val iframeSrc = playerDocument.select("#reproductor iframe").attr("src")
                            if (iframeSrc.isNotEmpty()) {
                                Log.d("Henaojara", "JKAnime iframe src encontrado: $iframeSrc")
                                when {
                                    "dood" in iframeSrc -> doodExtractor.videosFromUrl(iframeSrc)?.let { videoList.addAll(it) }
                                    "filemoon" in iframeSrc -> filemoonExtractor.videosFromUrl(iframeSrc)?.let { videoList.addAll(it) }
                                    "streamwish" in iframeSrc -> streamWishExtractor.videosFromUrl(iframeSrc)?.let { videoList.addAll(it) }
                                    "mixdrop" in iframeSrc -> mixDropExtractor.videosFromUrl(iframeSrc)?.let { videoList.addAll(it) }
                                    "ok.ru" in iframeSrc -> okruExtractor.videosFromUrl(iframeSrc)?.let { videoList.addAll(it) }
                                    "streamtape" in iframeSrc -> streamtapeExtractor.videosFromUrl(iframeSrc)?.let { videoList.addAll(it) }
                                    "uqload" in iframeSrc -> uqloadExtractor.videosFromUrl(iframeSrc)?.let { videoList.addAll(it) }
                                    "vidguard" in iframeSrc -> vidguardExtractor.videosFromUrl(iframeSrc)?.let { videoList.addAll(it) }
                                    "voe" in iframeSrc -> voeExtractor.videosFromUrl(iframeSrc)?.let { videoList.addAll(it) }
                                    else -> Log.w("Henaojara", "JKAnime: Tipo de iframe desconocido encontrado, no se pudo extraer de: $iframeSrc")
                                }
                            } else {
                                Log.e("Henaojara", "JKAnime: Iframe #reproductor no encontrado para la URL del reproductor: $playerUrl.")
                            }
                        }
                        // Manejar URLs de reproductores específicos de Lat.Ani/Latfan desde Henaojara
                        "lat.ani" in playerUrl || "latfan.online" in playerUrl -> {
                            Log.d("Henaojara", "Reproductor Lat.Ani/Latfan detectado: $playerUrl")
                            val playerResponse = client.newCall(GET(playerUrl, headers)).execute()
                            val playerDocument = playerResponse.asJsoup()
                            val sourceSrc = playerDocument.select("source[src]").attr("src")
                            if (sourceSrc.isNotEmpty()) {
                                videoList.add(Video(sourceSrc, "Lat.Ani - Video", sourceSrc))
                                Log.d("Henaojara", "URL de video de origen Lat.Ani añadida: $sourceSrc")
                            } else {
                                val iframeSrc = playerDocument.select("iframe[src]").attr("src")
                                if (iframeSrc.isNotEmpty()) {
                                    videoList.add(Video(iframeSrc, "Lat.Ani - Iframe", iframeSrc))
                                    Log.d("Henaojara", "URL de video de iframe Lat.Ani añadida: $iframeSrc")
                                } else {
                                    Log.e("Henaojara", "Lat.Ani: No se encontró <source> o <iframe> para la URL del reproductor: $playerUrl.")
                                }
                            }
                        }
                        // Manejar URLs de reproductores específicos de Yhenao desde Henaojara
                        "yhenao" in playerUrl -> {
                            Log.d("Henaojara", "Reproductor Yhenao detectado: $playerUrl")
                            val playerResponse = client.newCall(GET(playerUrl, headers)).execute()
                            val playerDocument = playerResponse.asJsoup()
                            val script = playerDocument.select("script:containsData(player.src)").lastOrNull()?.data()
                            val embedUrlRegex = """player\.src\s*=\s*['"](.*?)['"]""".toRegex()
                            var embedUrl: String? = null

                            // Simplificamos la extracción, sin JsUnpacker
                            embedUrl = script?.let { embedUrlRegex.find(it)?.groupValues?.get(1) }

                            if (embedUrl != null && embedUrl.isNotEmpty()) {
                                Log.d("Henaojara", "URL de incrustación Yhenao encontrada: $embedUrl")
                                when {
                                    "dood" in embedUrl -> doodExtractor.videosFromUrl(embedUrl)?.let { videoList.addAll(it) }
                                    "filemoon" in embedUrl -> filemoonExtractor.videosFromUrl(embedUrl)?.let { videoList.addAll(it) }
                                    "streamwish" in embedUrl -> streamWishExtractor.videosFromUrl(embedUrl)?.let { videoList.addAll(it) }
                                    "mixdrop" in embedUrl -> mixDropExtractor.videosFromUrl(embedUrl)?.let { videoList.addAll(it) }
                                    "ok.ru" in embedUrl -> okruExtractor.videosFromUrl(embedUrl)?.let { videoList.addAll(it) }
                                    "streamtape" in embedUrl -> streamtapeExtractor.videosFromUrl(embedUrl)?.let { videoList.addAll(it) }
                                    "uqload" in embedUrl -> uqloadExtractor.videosFromUrl(embedUrl)?.let { videoList.addAll(it) }
                                    "vidguard" in embedUrl -> vidguardExtractor.videosFromUrl(embedUrl)?.let { videoList.addAll(it) }
                                    "voe" in embedUrl -> voeExtractor.videosFromUrl(embedUrl)?.let { videoList.addAll(it) }
                                    else -> {
                                        videoList.add(Video(embedUrl, "Yhenao - Directo", embedUrl))
                                        Log.w("Henaojara", "ADVERTENCIA: URL de incrustación Yhenao desconocida añadida como video directo: $embedUrl")
                                    }
                                }
                            } else {
                                Log.e("Henaojara", "Yhenao: No se encontró URL de incrustación en el script para: $playerUrl.")
                            }
                        }
                        // Comprobaciones de extractores directos predeterminados (si playerUrl ya es un enlace de host directo)
                        "dood" in playerUrl -> doodExtractor.videosFromUrl(playerUrl)?.let { videoList.addAll(it) }
                        "filemoon" in playerUrl -> filemoonExtractor.videosFromUrl(playerUrl)?.let { videoList.addAll(it) }
                        "streamwish" in playerUrl -> streamWishExtractor.videosFromUrl(playerUrl)?.let { videoList.addAll(it) }
                        "mixdrop" in playerUrl -> mixDropExtractor.videosFromUrl(playerUrl)?.let { videoList.addAll(it) }
                        "ok.ru" in playerUrl -> okruExtractor.videosFromUrl(playerUrl)?.let { videoList.addAll(it) }
                        "streamtape" in playerUrl -> streamtapeExtractor.videosFromUrl(playerUrl)?.let { videoList.addAll(it) }
                        "uqload" in playerUrl -> uqloadExtractor.videosFromUrl(playerUrl)?.let { videoList.addAll(it) }
                        "vidguard" in playerUrl -> vidguardExtractor.videosFromUrl(playerUrl)?.let { videoList.addAll(it) }
                        "voe" in playerUrl -> voeExtractor.videosFromUrl(playerUrl)?.let { videoList.addAll(it) }
                        else -> {
                            Log.d("Henaojara", "Intentando extracción genérica para: $playerUrl")
                            val playerResponse = client.newCall(GET(playerUrl, headers)).execute()
                            val playerDocument = playerResponse.asJsoup()

                            val script = playerDocument.select("script:containsData(player.src)").lastOrNull()?.data()
                            val iframeDirectSrc = playerDocument.select("iframe[src]").attr("src")
                            var extractedUrl: String? = null

                            if (script != null) {
                                val embedUrlRegex = """player\.src\s*=\s*['"](.*?)['"]""".toRegex()
                                extractedUrl = script.let { embedUrlRegex.find(it)?.groupValues?.get(1) }
                            }

                            if (extractedUrl == null && iframeDirectSrc.isNotEmpty()) {
                                extractedUrl = iframeDirectSrc
                            }

                            if (extractedUrl != null && extractedUrl.isNotEmpty()) {
                                Log.d("Henaojara", "URL genérica extraída encontrada: $extractedUrl")
                                when {
                                    "dood" in extractedUrl -> doodExtractor.videosFromUrl(extractedUrl)?.let { videoList.addAll(it) }
                                    "filemoon" in extractedUrl -> filemoonExtractor.videosFromUrl(extractedUrl)?.let { videoList.addAll(it) }
                                    "streamwish" in extractedUrl -> streamWishExtractor.videosFromUrl(extractedUrl)?.let { videoList.addAll(it) }
                                    "mixdrop" in extractedUrl -> mixDropExtractor.videosFromUrl(extractedUrl)?.let { videoList.addAll(it) }
                                    "ok.ru" in extractedUrl -> okruExtractor.videosFromUrl(extractedUrl)?.let { videoList.addAll(it) }
                                    "streamtape" in extractedUrl -> streamtapeExtractor.videosFromUrl(extractedUrl)?.let { videoList.addAll(it) }
                                    "uqload" in extractedUrl -> uqloadExtractor.videosFromUrl(extractedUrl)?.let { videoList.addAll(it) }
                                    "vidguard" in extractedUrl -> vidguardExtractor.videosFromUrl(extractedUrl)?.let { videoList.addAll(it) }
                                    "voe" in extractedUrl -> voeExtractor.videosFromUrl(extractedUrl)?.let { videoList.addAll(it) }
                                    else -> {
                                        videoList.add(Video(extractedUrl, "Genérico - Directo", extractedUrl))
                                        Log.w("Henaojara", "ADVERTENCIA: URL genérica desconocida añadida como video directo: $extractedUrl")
                                    }
                                }
                            } else {
                                Log.e("Henaojara", "No se pudo extraer el video genéricamente (ni script ni iframe) para: $playerUrl")
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e("Henaojara", "ERROR: Falló al procesar la URL del reproductor $playerUrl: ${e.message}", e)
                    try {
                        val errorPageHtml = client.newCall(GET(playerUrl, headers)).execute().asJsoup().html()
                        Log.e("Henaojara", "DEBUG: HTML completo de la página del reproductor problemática ($playerUrl): ${errorPageHtml.take(5000)}...")
                    } catch (fetchError: Exception) {
                        Log.e("Henaojara", "DEBUG: No se pudo obtener el HTML de la página del reproductor problemática para depuración: ${fetchError.message}")
                    }
                }
            } else {
                Log.e("Henaojara", "ERROR: playerUrl vacío o no se pudo extraer de la opción del reproductor. HTML del elemento: ${option.html()}")
            }
        }

        if (videoList.isEmpty()) {
            Log.e("Henaojara", "RESULTADO FINAL: No se encontraron videos después de procesar todas las opciones del reproductor.")
        } else {
            Log.d("Henaojara", "RESULTADO FINAL: Se encontraron ${videoList.size} videos.")
        }

        return videoList.distinctBy { it.url }
    }

    override fun videoListSelector() = throw UnsupportedOperationException()
    override fun videoFromElement(element: Element) = throw UnsupportedOperationException()
    override fun videoUrlParse(document: Document) = throw UnsupportedOperationException()

    // ============================== Filtros ===============================

    override fun getFilterList() = AnimeFilterList(
        AnimeFilter.Header("La búsqueda por texto ignora el filtro"),
        CategoryFilter(getCategoryList()),
    )

    private class CategoryFilter(categories: Array<Pair<String, String>>) :
        AnimeFilter.Select<Pair<String, String>>("Categorías", categories, 0) {
        private val categoriesArray = categories
        fun toUriPart(): String {
            return if (state > 0) categoriesArray[state].second else ""
        }
    }

    private fun getCategoryList(): Array<Pair<String, String>> {
        return arrayOf(
            Pair("Seleccionar", ""),
            Pair("CASTELLANO", "espanol-castellano"),
            Pair("LATINO", "latino"),
            Pair("SUBTITULADO", "subtitulos"),
            Pair("ACCIÓN", "accion"),
            Pair("AMOR", "amor"),
            Pair("ARTES MARCIALES", "artes-marciales"),
            Pair("AVENTURA", "aventura"),
            Pair("CIENCIA FICCIÓN", "ciencia-ficcion"),
            Pair("COCINAS", "cocinas"),
            Pair("COMEDIA", "comedia"),
            Pair("DEMENCIA", "demencia"),
            Pair("DEMONIOS", "demonios"),
            Pair("DEPORTES", "deportes"),
            Pair("DRAMA", "drama"),
            Pair("ECCHI", "ecchi"),
            Pair("EMISIÓN", "emision"),
            Pair("ESCOLAR", "escolar"),
            Pair("ESTRENOS", "estrenos"),
            Pair("FAMILIA", "familia"),
            Pair("FANTASÍA", "fantasia"),
            Pair("GORE", "gore"),
            Pair("HAREM", "harem"),
            Pair("HECCHI", "hecchi"),
            Pair("HISTÓRICO", "historico"),
            Pair("ISEKAI", "isekai"),
            Pair("JUEGOS", "juegos"),
            Pair("MAGIA", "magia"),
            Pair("MECHA", "mecha"),
            Pair("MILITAR", "militar"),
            Pair("MISTERIO", "misterio"),
            Pair("MÚSICA", "musica"),
            Pair("PELÍCULAS", "pelicula"),
            Pair("PIRATAS", "piratas"),
            Pair("PSICOLÓGICO", "psicologico"),
            Pair("RECUERDOS", "recuerdos"),
            Pair("ROBOTS", "robots"),
            Pair("ROMANCE", "romance"),
            Pair("SAMURÁI", "samuraio"),
            Pair("Sci-Fi & Fantasy", "sci-fi-fantasy"),
            Pair("SEINEN", "seinen"),
            Pair("SHOUJO", "shoujo"),
            Pair("SHOUNEN", "shounen"),
            Pair("Sin categoría", "sin-categoria"),
            Pair("SOBRENATURAL", "sobrenatural"),
            Pair("STUDIO GHIBLI", "studio-ghibli"),
            Pair("SUPERPODERES", "superpoderes"),
            Pair("SUSPENSO", "suspenso"),
            Pair("TERROR", "terror"),
            Pair("VAMPIROS", "vampiros"),
            Pair("YAOI", "yaoi"),
            Pair("YURI", "yuri"),
        )
    }

    // ============================= Utilidades ==============================

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
            title = "Servidor Preferido"
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
            title = "Calidad Preferida"
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
