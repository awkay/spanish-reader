package net.awkay.spanishreader.ui.reader

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.NavigateBefore
import androidx.compose.material.icons.automirrored.filled.NavigateNext
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.TextDecrease
import androidx.compose.material.icons.filled.TextIncrease
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import net.awkay.spanishreader.core.text.Page
import net.awkay.spanishreader.core.text.Token
import net.awkay.spanishreader.core.vocab.WordStatus
import net.awkay.spanishreader.ui.BackButton
import net.awkay.spanishreader.ui.StatusColors
import net.awkay.spanishreader.ui.appViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderScreen(lessonId: Long, onBack: () -> Unit, onListen: () -> Unit) {
    val vm = appViewModel(key = "reader-$lessonId") { ReaderViewModel(it, lessonId) }
    val content by vm.content.collectAsStateWithLifecycle()
    val missing by vm.missing.collectAsStateWithLifecycle()
    val statuses by vm.statuses.collectAsStateWithLifecycle()
    val fontSize by vm.fontSize.collectAsStateWithLifecycle()
    val selection by vm.selection.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    val c = content
    if (c == null) {
        Box(Modifier.fillMaxSize(), Alignment.Center) {
            if (missing) Text("This lesson no longer exists.") else CircularProgressIndicator()
        }
        return
    }
    val pager = rememberPagerState(initialPage = c.initialPage) { c.pages.size }
    LaunchedEffect(pager) { snapshotFlow { pager.settledPage }.collect(vm::onPageSettled) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(c.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("Page ${pager.currentPage + 1} of ${c.pages.size}", style = MaterialTheme.typography.bodySmall)
                    }
                },
                navigationIcon = { BackButton(onBack) },
                actions = {
                    IconButton(onClick = { vm.changeFontSize(-2) }) { Icon(Icons.Default.TextDecrease, "Smaller text") }
                    IconButton(onClick = { vm.changeFontSize(2) }) { Icon(Icons.Default.TextIncrease, "Larger text") }
                    IconButton(onClick = onListen) { Icon(Icons.Default.Headphones, "Listen") }
                },
            )
        },
    ) { padding ->
        HorizontalPager(pager, Modifier.fillMaxSize().padding(padding), beyondViewportPageCount = 1) { index ->
            val page = c.pages[index]
            val isLast = index == c.pages.lastIndex
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 12.dp)) {
                PageText(page, statuses, selection?.token, fontSize, vm::onWordTapped)
                Spacer(Modifier.height(24.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { scope.launch { pager.animateScrollToPage(index - 1) } }, enabled = index > 0) {
                        Icon(Icons.AutoMirrored.Filled.NavigateBefore, "Previous page")
                    }
                    if (isLast) {
                        Button(onClick = {
                            scope.launch {
                                val n = vm.finishLesson()
                                Toast.makeText(context, "Lesson finished: $n words moved to Known", Toast.LENGTH_SHORT).show()
                                onBack()
                            }
                        }) { Text("Finish lesson") }
                    } else {
                        Button(onClick = { scope.launch { pager.animateScrollToPage(index + 1) } }) {
                            Text("Next page")
                            Icon(Icons.AutoMirrored.Filled.NavigateNext, null)
                        }
                    }
                }
                Text(
                    "Turning the page marks the remaining blue words as Known.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp, bottom = 32.dp),
                )
            }
        }
    }

    selection?.let { sel ->
        WordSheet(
            selection = sel,
            status = statuses[sel.form] ?: WordStatus.NEW,
            onStatus = vm::setStatus,
            onRetry = vm::retryLookup,
            onSpeak = { vm.speak(sel.token.text) },
            onDismiss = vm::dismissSelection,
        )
    }
}

@Composable
private fun PageText(page: Page, statuses: Map<String, WordStatus>, selected: Token?, fontSize: Int, onTap: (Token) -> Unit) {
    val onSurface = MaterialTheme.colorScheme.onSurface
    val annotated: AnnotatedString = remember(page, statuses, selected, onSurface) {
        val tokens = page.tokens.dropWhile { !it.isWord && it.text.isBlank() }.dropLastWhile { it.text.isBlank() }
        buildAnnotatedString {
            for (t in tokens) {
                val form = t.normalized
                if (form == null) {
                    append(t.text)
                    continue
                }
                val status = statuses[form] ?: WordStatus.NEW
                val style = SpanStyle(
                    color = onSurface,
                    background = StatusColors.background(status),
                    textDecoration = if (t == selected) TextDecoration.Underline else TextDecoration.None,
                )
                withLink(LinkAnnotation.Clickable(tag = t.index.toString(), styles = TextLinkStyles(style = style)) { onTap(t) }) {
                    append(t.text)
                }
            }
        }
    }
    Text(
        annotated,
        style = MaterialTheme.typography.bodyLarge.copy(fontSize = fontSize.sp, lineHeight = (fontSize * 1.6f).sp),
        color = if (annotated.isEmpty()) Color.Unspecified else onSurface,
    )
}
