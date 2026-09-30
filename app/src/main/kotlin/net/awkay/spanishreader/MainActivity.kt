package net.awkay.spanishreader

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import net.awkay.spanishreader.ui.LibraryScreen
import net.awkay.spanishreader.ui.SpanishReaderTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val app = application as SpanishReaderApp
        setContent {
            SpanishReaderTheme {
                LibraryScreen(app.database.lessons())
            }
        }
    }
}
