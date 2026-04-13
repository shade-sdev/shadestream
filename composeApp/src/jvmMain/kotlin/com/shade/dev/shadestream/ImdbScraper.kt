package com.shade.dev.shadestream

import com.microsoft.playwright.*
import com.microsoft.playwright.options.LoadState
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.io.File
import kotlin.random.Random

private val APP_DIR = File(System.getProperty("user.home"), ".shadestream").also { it.mkdirs() }
val PLAYWRIGHT_DIR = File(APP_DIR, "browsers").also { it.mkdirs() }

fun installPlaywrightIfNeeded() {
    val marker = File(PLAYWRIGHT_DIR, ".installed")
    if (!marker.exists()) {
        println("[Playwright] Installing Browsers to ${PLAYWRIGHT_DIR.absolutePath}...")
        PLAYWRIGHT_DIR.mkdirs()
        marker.createNewFile()
        CLI.main(arrayOf("install"))
        println("[Playwright] Install complete.")
    }
}

@Suppress("UNCHECKED_CAST")
fun setEnv(key: String, value: String) {
    val processEnvironment = Class.forName("java.lang.ProcessEnvironment")
    val field = processEnvironment.getDeclaredField("theEnvironment").apply { isAccessible = true }
    val env = field.get(null) as MutableMap<String, String>
    env[key] = value
}

@Serializable
data class IMDBTitle(
    val url: String,
    val title: String? = null,
    val year: String? = null,
    val rating: String? = null,
    val ratingCount: String? = null,
    val genre: List<String>? = null,
    val director: List<String>? = null,
    val cast: List<String>? = null,
    val plot: String? = null,
    val runtime: String? = null,
    val metascore: String? = null,
    val contentRating: String? = null,
    val posterUrl: String? = null,
)

private val USER_AGENTS = listOf(
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36",
    "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/123.0.0.0 Safari/537.36",
    "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36",
)

private val CONTENT_RATINGS = setOf("G", "PG", "PG-13", "R", "NC-17", "TV-MA", "TV-14", "TV-G")

val json = Json { prettyPrint = true; encodeDefaults = false }

fun humanDelay(minMs: Long = 1200, maxMs: Long = 3500) {
    Thread.sleep(Random.nextLong(minMs, maxMs))
}

fun scrollPage(page: Page) {
    val scrolls = Random.nextInt(3, 7)
    repeat(scrolls) {
        page.mouse().wheel(0.0, Random.nextInt(300, 700).toDouble())
        Thread.sleep(Random.nextLong(300, 800))
    }
}

fun createBrowserContext(playwright: Playwright): Pair<Browser, BrowserContext> {
    val browser = playwright.chromium().launch(
        BrowserType.LaunchOptions()
            .setHeadless(true)
            .setArgs(
                listOf(
                    "--no-sandbox",
                    "--disable-blink-features=AutomationControlled",
                    "--disable-infobars",
                )
            )
    )

    val context = browser.newContext(
        Browser.NewContextOptions()
            .setUserAgent(USER_AGENTS.random())
            .setViewportSize(1280, 800)
            .setLocale("en-US")
            .setTimezoneId("America/New_York")
            .setExtraHTTPHeaders(
                mapOf(
                    "Accept-Language" to "en-US,en;q=0.9",
                    "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
                    "sec-ch-ua" to "\"Chromium\";v=\"124\", \"Google Chrome\";v=\"124\"",
                    "sec-ch-ua-platform" to "\"Windows\"",
                )
            )
    )

    context.addInitScript(
        """
        Object.defineProperty(navigator, 'webdriver', { get: () => undefined });
        Object.defineProperty(navigator, 'languages', { get: () => ['en-US', 'en'] });
        Object.defineProperty(navigator, 'plugins', { get: () => [1, 2, 3] });
        """.trimIndent()
    )

    return Pair(browser, context)
}

fun parseTitlePage(html: String, url: String): IMDBTitle {
    val doc: Document = Jsoup.parse(html)

    val title = (doc.selectFirst("span[data-testid=hero__primary-text]")
        ?: doc.selectFirst("h1"))?.text()

    val metaItems = doc.select("ul.ipc-inline-list--show-dividers li")
        .map { it.text().trim() }

    var year: String? = null
    var contentRating: String? = null
    var runtime: String? = null

    for (t in metaItems) {
        when {
            t.length == 4 && t.all { it.isDigit() } -> year = t
            t in CONTENT_RATINGS -> contentRating = t
            (t.contains("h") || t.contains("m")) && t.any { it.isDigit() } -> runtime = t
        }
    }

    // Rating
    val ratingSpans = doc.select("div[data-testid=hero-rating-bar__aggregate-rating__score] span")
    val rating = ratingSpans.firstOrNull()?.text()

    // Rating count
    val ratingCount = doc
        .select("div[data-testid=hero-rating-bar__aggregate-rating] div")
        .firstOrNull { it.className().contains("RatingCount", ignoreCase = true) }
        ?.text()

    // Genres
    val genre = doc.select("div[data-testid=genres] a")
        .map { it.text() }
        .ifEmpty { null }

    // Plot
    val plot = (doc.selectFirst("span[data-testid=plot-xs_to_m]")
        ?: doc.selectFirst("span[data-testid=plot-l]"))?.text()

    // Director(s) & cast from principal credits
    val directors = mutableListOf<String>()
    val cast = mutableListOf<String>()

    doc.select("li[data-testid=title-pc-principal-credit]").forEach { credit ->
        val labelText = credit.select("span").firstOrNull()?.text()?.lowercase() ?: ""
        val names = credit.select("a").map { it.text() }
        when {
            "director" in labelText -> directors.addAll(names)
            "star" in labelText || "cast" in labelText -> cast.addAll(names)
        }
    }

    // Fallback cast
    val finalCast = cast.ifEmpty {
        doc.select("a[data-testid=title-cast-item__actor]")
            .map { it.text() }
            .take(10)
    }.ifEmpty { null }

    // Metascore
    val metascore = doc
        .select("span[data-testid=score-meta], div[class*=metacritic]")
        .firstOrNull()?.text()

    // Poster
    val posterUrl = doc.selectFirst("div[data-testid=hero-media__poster] img")
        ?.attr("src")
        ?.takeIf { it.isNotBlank() }

    return IMDBTitle(
        url = url,
        title = title,
        year = year,
        rating = rating,
        ratingCount = ratingCount,
        genre = genre,
        director = directors.ifEmpty { null },
        cast = finalCast,
        plot = plot,
        runtime = runtime,
        metascore = metascore,
        contentRating = contentRating,
        posterUrl = posterUrl,
    )
}

fun scrapeImdb(url: String): IMDBTitle {
    Playwright.create().use { playwright ->
        val (browser, context) = createBrowserContext(playwright)

        try {
            val page = context.newPage()
            println("[→] Navigating to $url")

            page.navigate(url)
            page.waitForLoadState(LoadState.DOMCONTENTLOADED)

            humanDelay(1500, 2500)
            scrollPage(page)
            humanDelay(500, 1500)

            val html = page.content()
            return parseTitlePage(html, url)

        } finally {
            context.close()
            browser.close()
        }
    }
}

/**
 * Scrape multiple titles with polite delays between requests.
 */
fun scrapeMultiple(urls: List<String>): List<IMDBTitle> {
    return urls.mapIndexed { index, url ->
        val result = scrapeImdb(url)
        if (index < urls.lastIndex) {
            val delay = Random.nextLong(3000, 8000)
            println("[⏳] Waiting ${delay}ms before next request...")
            Thread.sleep(delay)
        }
        result
    }
}

fun main() {
    val target = "https://www.imdb.com/title/tt28650488"
    val result = scrapeImdb(target)

    val output = json.encodeToString(result)
    println("\n${"=".repeat(50)}")
    println(output)

    File("imdb_result.json").writeText(output)
    println("\n[✓] Saved to imdb_result.json")
}