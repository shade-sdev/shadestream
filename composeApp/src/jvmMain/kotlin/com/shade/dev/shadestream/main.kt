package com.shade.dev.shadestream

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.libtorrent4j.SessionManager

fun main() {
    setEnv("PLAYWRIGHT_BROWSERS_PATH", PLAYWRIGHT_DIR.absolutePath)
    installPlaywrightIfNeeded()

    application {
        val session = SessionManager()
        session.start()
        val scope = rememberCoroutineScope()

        LaunchedEffect(Unit) {
            scope.launch(Dispatchers.IO) {
                val target = "https://www.imdb.com/title/tt31193180"
                val result = scrapeImdb(target)
                val output = json.encodeToString(result)
                println("\n${"=".repeat(50)}")
                println(output)
            }
        }

        Window(
            onCloseRequest = ::exitApplication,
            title = "shademovies",
        ) {
            App()
        }
    }
}