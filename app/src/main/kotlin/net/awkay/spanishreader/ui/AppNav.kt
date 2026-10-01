package net.awkay.spanishreader.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import net.awkay.spanishreader.ShareInbox
import net.awkay.spanishreader.ui.importer.ImportScreen
import net.awkay.spanishreader.ui.library.LibraryScreen
import net.awkay.spanishreader.ui.library.SharedLibraryScreen
import net.awkay.spanishreader.ui.listen.ListenScreen
import net.awkay.spanishreader.ui.reader.ReaderScreen
import net.awkay.spanishreader.ui.settings.SettingsScreen
import net.awkay.spanishreader.ui.vocab.VocabularyScreen

object Routes {
    const val LIBRARY = "library"
    const val IMPORT = "import"
    const val VOCAB = "vocab"
    const val SETTINGS = "settings"
    const val SHARED = "shared"
    fun reader(id: Long) = "reader/$id"
    fun listen(id: Long) = "listen/$id"
}

@Composable
fun AppNav() {
    val nav = rememberNavController()
    val pendingShare by ShareInbox.pending.collectAsStateWithLifecycle()
    LaunchedEffect(pendingShare) {
        if (pendingShare != null && nav.currentDestination?.route != Routes.IMPORT) nav.navigate(Routes.IMPORT)
    }
    val lessonArg = listOf(navArgument("id") { type = NavType.LongType })
    NavHost(nav, startDestination = Routes.LIBRARY) {
        composable(Routes.LIBRARY) {
            LibraryScreen(
                onOpen = { nav.navigate(Routes.reader(it)) },
                onListen = { nav.navigate(Routes.listen(it)) },
                onImport = { nav.navigate(Routes.IMPORT) },
                onVocabulary = { nav.navigate(Routes.VOCAB) },
                onSettings = { nav.navigate(Routes.SETTINGS) },
                onGetFromWeb = { nav.navigate(Routes.SHARED) },
            )
        }
        composable(Routes.SHARED) {
            SharedLibraryScreen(
                onBack = { nav.popBackStack() },
                onOpen = { id -> nav.navigate(Routes.reader(id)) { popUpTo(Routes.LIBRARY) } },
                onSettings = { nav.navigate(Routes.SETTINGS) },
            )
        }
        composable(Routes.IMPORT) {
            ImportScreen(
                onBack = { nav.popBackStack() },
                onImported = { id -> nav.navigate(Routes.reader(id)) { popUpTo(Routes.LIBRARY) } },
            )
        }
        composable("reader/{id}", lessonArg) { entry ->
            val id = entry.arguments!!.getLong("id")
            ReaderScreen(id, onBack = { nav.popBackStack() }, onListen = { nav.navigate(Routes.listen(id)) })
        }
        composable("listen/{id}", lessonArg) { entry ->
            val id = entry.arguments!!.getLong("id")
            ListenScreen(id, onBack = { nav.popBackStack() }, onRead = { nav.navigate(Routes.reader(id)) { popUpTo(Routes.LIBRARY) } })
        }
        composable(Routes.VOCAB) { VocabularyScreen(onBack = { nav.popBackStack() }) }
        composable(Routes.SETTINGS) { SettingsScreen(onBack = { nav.popBackStack() }) }
    }
}
