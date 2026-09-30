package net.awkay.spanishreader

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import net.awkay.spanishreader.ui.AppNav
import net.awkay.spanishreader.ui.SpanishReaderTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) ShareInbox.offer(this, intent)
        setContent {
            SpanishReaderTheme {
                AppNav()
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        ShareInbox.offer(this, intent)
    }
}
