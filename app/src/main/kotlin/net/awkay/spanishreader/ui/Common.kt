package net.awkay.spanishreader.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import net.awkay.spanishreader.SpanishReaderApp

@Composable
fun BackButton(onBack: () -> Unit) {
    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
}

/** A ViewModel scoped to the current navigation entry, built from the app container. */
@Composable
inline fun <reified VM : ViewModel> appViewModel(key: String? = null, noinline create: (SpanishReaderApp) -> VM): VM {
    val app = LocalContext.current.applicationContext as SpanishReaderApp
    return viewModel(key = key) { create(app) }
}
