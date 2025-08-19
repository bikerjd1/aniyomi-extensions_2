package eu.kanade.tachiyomi.extension.es.pelispedia

import eu.kanade.tachiyomi.lib.playlistutils.PlaylistUtils
import eu.kanade.tachiyomi.lib.unpacker.Unpacker
import eu.kanade.tachiyomi.multisrc.dooplay.DooPlay
import eu.kanade.tachiyomi.multisrc.dooplay.DooPlayFilters
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document

class Pelispedia : DooPlay(
    "es", // language
    "Pelispedia", // source name
    "https://pelispedia.is", // base URL
) {

    override val id: Long = 123456789012345 // You need to generate a unique ID

    // Override client to add custom headers if needed
    override val client = super.client.newBuilder()
        .addInterceptor { chain ->
            val originalRequest = chain.request()
            val request = originalRequest.newBuilder()
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.124 Safari/537.36")
                .header("Referer", baseUrl)
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8")
                .header("Accept-Language", "es-ES,es;q=0.8,en-US;q=0.5,en;q=0.3")
                .build()
            chain.proceed(request)
        }
        .build()

    // Use PlaylistUtils for handling video extraction
    private val playlistUtils by lazy { PlaylistUtils(client, headers) }

    // Use Unpacker for handling obfuscated JavaScript
    private val unpacker by lazy { Unpacker() }

    // Override popular manga request
    override fun popularMangaRequest(page: Int): Request {
        return GET("$baseUrl/cartelera-peliculas/", headers)
    }

    // Override popular manga parse
    override fun popularMangaParse(response: Response): MangasPage {
        val document = response.asJsoup()
        val mangas = mutableListOf<SManga>()
        val hasNextPage = false // Implement pagination if needed

        // Parse movie elements
        val movieElements = document.select("ul.post-lst li article.post")
        for (element in movieElements) {
            val manga = SManga.create()
            manga.title = element.select("h2.entry-title").text()
            manga.url = element.select("a.lnk-blk").attr("href")
            manga.thumbnail_url = element.select("figure img").attr("src")
            manga.initialized = true
            mangas.add(manga)
        }

        return MangasPage(mangas, hasNextPage)
    }

    // Override latest updates request
    override fun latestUpdatesRequest(page: Int): Request {
        return GET(baseUrl, headers)
    }

    // Override latest updates parse
    override fun latestUpdatesParse(response: Response): MangasPage {
        val document = response.asJsoup()
        val mangas = mutableListOf<SManga>()
        val hasNextPage = false

        // Parse latest movies
        val latestMovies = document.select("section.widget_list_movies_series:first-child ul.post-lst li article.post")
        for (element in latestMovies) {
            val manga = SManga.create()
            manga.title = element.select("h2.entry-title").text()
            manga.url = element.select("a.lnk-blk").attr("href")
            manga.thumbnail_url = element.select("figure img").attr("src")
            manga.initialized = true
            mangas.add(manga)
        }

        // Parse latest series
        val latestSeries = document.select("section.widget_list_movies_series:last-child ul.post-lst li article.post")
        for (element in latestSeries) {
            val manga = SManga.create()
            manga.title = element.select("h2.entry-title").text()
            manga.url = element.select("a.lnk-blk").attr("href")
            manga.thumbnail_url = element.select("figure img").attr("src")
            manga.initialized = true
            mangas.add(manga)
        }

        return MangasPage(mangas, hasNextPage)
    }

    // Override search manga request
    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val url = "$baseUrl/?s=$query".toHttpUrl().newBuilder()
        return GET(url.toString(), headers)
    }

    // Override search manga parse
    override fun searchMangaParse(response: Response): MangasPage {
        val document = response.asJsoup()
        val mangas = mutableListOf<SManga>()
        val hasNextPage = false

        val searchResults = document.select("article.post")
        for (element in searchResults) {
            val manga = SManga.create()
            manga.title = element.select("h2.entry-title").text()
            manga.url = element.select("a.lnk-blk").attr("href")
            manga.thumbnail_url = element.select("figure img").attr("src")
            manga.initialized = true
            mangas.add(manga)
        }

        return MangasPage(mangas, hasNextPage)
    }

    // Override manga details parse
    override fun mangaDetailsParse(document: Document): SManga {
        val manga = SManga.create()

        // Title
        manga.title = document.select("h1.entry-title").text()

        // Thumbnail
        manga.thumbnail_url = document.select("div.post-thumbnail figure img").attr("src")

        // Description (if available)
        val description = document.select("div.entry-content p").text()
        if (description.isNotBlank()) {
            manga.description = description
        }

        // Genre
        val genres = document.select("span.genres a").joinToString { it.text() }
        if (genres.isNotBlank()) {
            manga.genre = genres
        }

        // Status (assuming all are completed since they're movies/series)
        manga.status = SManga.COMPLETED

        return manga
    }

    // Override chapter list parse for series
    override fun chapterListParse(response: Response): List<SChapter> {
        val document = response.asJsoup()
        val chapters = mutableListOf<SChapter>()

        // Check if this is a series with episodes
        val episodeList = document.select("ul#episode_by_temp li")
        if (episodeList.isNotEmpty()) {
            // It's a series with episodes
            for (episode in episodeList) {
                val chapter = SChapter.create()
                chapter.name = episode.select("h2.entry-title").text()
                chapter.url = episode.select("a.lnk-blk").attr("href")
                chapter.chapter_number = episode.select("span.num-epi").text()
                    .replace("x", ".").toFloatOrNull() ?: 0f

                // Parse date if available
                val dateText = episode.select("span.time").text()
                if (dateText.isNotBlank()) {
                    chapter.date_upload = parseRelativeDate(dateText)
                }

                chapters.add(chapter)
            }
        } else {
            // It's a movie, create a single chapter
            val chapter = SChapter.create()
            chapter.name = "Película completa"
            chapter.url = response.request.url.toString()
            chapter.chapter_number = 1f
            chapter.date_upload = System.currentTimeMillis()
            chapters.add(chapter)
        }

        return chapters
    }

    // Helper function to parse relative dates like "2 meses hace"
    private fun parseRelativeDate(dateText: String): Long {
        return try {
            val currentTime = System.currentTimeMillis()
            when {
                dateText.contains("hace") -> {
                    val parts = dateText.split(" ")
                    val amount = parts[0].toInt()
                    when {
                        dateText.contains("hora") -> currentTime - (amount * 60 * 60 * 1000)
                        dateText.contains("día") -> currentTime - (amount * 24 * 60 * 60 * 1000)
                        dateText.contains("semana") -> currentTime - (amount * 7 * 24 * 60 * 60 * 1000)
                        dateText.contains("mes") -> currentTime - (amount * 30 * 24 * 60 * 60 * 1000)
                        else -> currentTime
                    }
                }
                else -> currentTime
            }
        } catch (e: Exception) {
            System.currentTimeMillis()
        }
    }

    // Override page list parse - This is the most important part for video extraction
    override fun pageListParse(response: Response): List<Page> {
        val document = response.asJsoup()
        val pages = mutableListOf<Page>()

        // Try to find the iframe with the video
        val iframe = document.select("iframe[data-src]").first()
        if (iframe != null) {
            val iframeUrl = iframe.attr("data-src")
            if (iframeUrl.isNotBlank()) {
                // This is where we need to handle the video extraction
                // For now, we'll just add the iframe URL as a page
                pages.add(Page(0, iframeUrl, ""))
            }
        }

        // If no iframe found, try to find download links
        if (pages.isEmpty()) {
            val downloadLinks = document.select("div.download-links table tbody tr")
            for ((index, link) in downloadLinks.withIndex()) {
                val downloadUrl = link.select("a").attr("href")
                if (downloadUrl.isNotBlank()) {
                    pages.add(Page(index, downloadUrl, "Descarga ${link.select("td:nth-child(3)").text()}"))
                }
            }
        }

        return pages
    }

    // Override image URL parse
    override fun imageUrlParse(document: Document): String {
        return document.select("div.post-thumbnail figure img").attr("src")
    }

    // Override getFilterList to add custom filters if needed
    override fun getFilterList(): FilterList {
        return FilterList(
            DooPlayFilters.SortFilter(),
            DooPlayFilters.GenreFilter(),
            DooPlayFilters.YearFilter(),
            DooPlayFilters.QualityFilter(),
        )
    }
}
