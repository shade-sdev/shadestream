package com.shade.dev.shadestream

import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import org.libtorrent4j.SessionManager

fun main() {
    application {
        val session = SessionManager()
        session.start()
        installPlaywrightIfNeeded()

        Window(
            onCloseRequest = ::exitApplication,
            title = "shademovies",
        ) {
            App()
        }
    }
}

fun installPlaywrightIfNeeded() {
    val target = "https://www.imdb.com/title/tt31193180"
    val result = scrapeImdb(target)

    val output = json.encodeToString(result)
    println("\n${"=".repeat(50)}")
    println(output)
}