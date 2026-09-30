package net.awkay.spanishreader.ui.reader

import android.widget.Toast
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.NavigateBefore
import androidx.compose.material.icons.automirrored.filled.NavigateNext
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.TextDecrease
import androidx.compose.material.icons.filled.TextIncrease
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalIconToggleButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import net.awkay.spanishreader.audio.AudioState
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
    val audio by vm.audio.collectAsStateWithLifecycle()
    val following by vm.following.collectAsStateWithLifecycle()
    val phraseSpans by vm.phraseSpans.collectAsStateWithLifecycle()
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
    val spoken = if (audio.started) audio.currentSentence else null

    // Page turns we make ourselves while following the audio; any other turn during playback means the user
    // swiped away, so stop dragging them back.
    var autoTarget by remember { mutableStateOf<Int?>(null) }
    val latestAudio by rememberUpdatedState(audio)
    val latestFollowing by rememberUpdatedState(following)
    LaunchedEffect(pager) {
        snapshotFlow { pager.settledPage }.collect { page ->
            vm.onPageSettled(page)
            val a = latestAudio
            if (autoTarget == page) autoTarget = null
            else if (latestFollowing && a.isPlaying && a.currentSentence?.pageIndex != page) vm.stopFollowing()
        }
    }
    LaunchedEffect(spoken?.pageIndex, following) {
        val target = spoken?.pageIndex ?: return@LaunchedEffect
        if (following && pager.currentPage != target) {
            autoTarget = target
            pager.animateScrollToPage(target)
        }
    }

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
                    IconButton(onClick = onListen) { Icon(Icons.Default.Headphones, "Sentence list") }
                },
            )
        },
        bottomBar = {
            PlayerBar(
                audio = audio,
                following = following,
                onPlayPause = { vm.playPause(pager.currentPage) },
                onPrevious = vm::previousSentence,
                onNext = vm::nextSentence,
                onLoop = vm::toggleLoop,
                onFollow = vm::follow,
            )
        },
    ) { padding ->
        HorizontalPager(pager, Modifier.fillMaxSize().padding(padding), beyondViewportPageCount = 1) { index ->
            val page = c.pages[index]
            val isLast = index == c.pages.lastIndex
            val scroll = rememberScrollState()
            Column(Modifier.fillMaxSize().verticalScroll(scroll).padding(horizontal = 20.dp, vertical = 12.dp)) {
                PageText(
                    page, statuses, selection?.token, fontSize, phraseSpans,
                    highlightSentence = spoken?.takeIf { it.pageIndex == index }?.sentenceIndex,
                    scroll = scroll.takeIf { following },
                    onTap = vm::onWordTapped,
                )
                Spacer(Modifier.height(24.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { scope.launch { pager.animateScrollToPage(index - 1) } }, enabled = index > 0) {
                        Icon(Icons.AutoMirrored.Filled.NavigateBefore, "Previous page")
                    }
                    if (isLast) {
                        Button(onClick = {
                            scope.launch {
                                val n = vm.finishLesson()
                                Toast.makeText(context, "Lesson finished: $n words added at level 1", Toast.LENGTH_SHORT).show()
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
                val blueOnPage = page.wordForms.distinct().count { (statuses[it] ?: WordStatus.NEW) == WordStatus.NEW }
                if (blueOnPage > 0) {
                    TextButton(
                        onClick = {
                            scope.launch {
                                val n = vm.markPageKnown(index)
                                Toast.makeText(context, "$n words marked Known", Toast.LENGTH_SHORT).show()
                            }
                        },
                        modifier = Modifier.align(Alignment.CenterHorizontally).padding(top = 8.dp),
                    ) { Text("Mark all $blueOnPage blue words Known") }
                }
                Text(
                    "Turning the page adds the remaining blue words to your vocabulary at level 1.",
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
            phrases = phraseSpans[sel.token.index].orEmpty(),
            onStatus = vm::setStatus,
            onRetry = vm::retryLookup,
            onImprove = vm::improve,
            onSpeak = { vm.speak(sel.token.text) },
            onDismiss = vm::dismissSelection,
        )
    }
}

@Composable
private fun PlayerBar(
    audio: AudioState,
    following: Boolean,
    onPlayPause: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onLoop: () -> Unit,
    onFollow: () -> Unit,
) {
    Surface(tonalElevation = 3.dp) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 8.dp, vertical = 4.dp)) {
            audio.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, maxLines = 2) }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onPrevious, enabled = audio.started) { Icon(Icons.Default.SkipPrevious, "Previous sentence") }
                FilledIconButton(onClick = onPlayPause) {
                    if (audio.preparing) CircularProgressIndicator(Modifier.size(20.dp), color = MaterialTheme.colorScheme.onPrimary, strokeWidth = 2.dp)
                    else Icon(if (audio.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow, if (audio.isPlaying) "Pause" else "Play")
                }
                IconButton(onClick = onNext, enabled = audio.started) { Icon(Icons.Default.SkipNext, "Next sentence") }
                FilledTonalIconToggleButton(checked = audio.loop, onCheckedChange = { onLoop() }) { Icon(Icons.Default.RepeatOne, "Loop sentence") }
                if (audio.started && !following) {
                    TextButton(onClick = onFollow) {
                        Icon(Icons.Default.MyLocation, null, Modifier.size(18.dp))
                        Text(" Follow")
                    }
                }
            }
        }
    }
}

/**
 * The page as an annotated string, plus each sentence's character range (for the audio highlight) and each colored
 * word's range and color (status colors are drawn as separate rounded boxes, not as text background).
 */
private class PageAnnotation(
    val text: AnnotatedString,
    val sentenceRanges: Map<Int, IntRange>,
    val coloredWords: List<Pair<IntRange, Color>>,
)

@Composable
private fun PageText(
    page: Page,
    statuses: Map<String, WordStatus>,
    selected: Token?,
    fontSize: Int,
    phraseSpans: Map<Int, List<PhraseSpan>>,
    highlightSentence: Int?,
    /** When set, the page scrolls to keep the highlighted sentence in view. */
    scroll: ScrollState?,
    onTap: (Token) -> Unit,
) {
    val onSurface = MaterialTheme.colorScheme.onSurface
    val highlightColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
    val built: PageAnnotation = remember(page, statuses, selected, onSurface, phraseSpans) {
        val tokens = page.tokens.dropWhile { !it.isWord && it.text.isBlank() }.dropLastWhile { it.text.isBlank() }
        val ranges = HashMap<Int, IntRange>()
        val colored = ArrayList<Pair<IntRange, Color>>()
        // Expressions are underlined, including the spaces between their adjacent words.
        val underlined = HashSet<Int>()
        for (t in tokens) phraseSpans[t.index]?.forEach { span ->
            underlined += span.tokenIndices
            span.tokenIndices.forEach { i -> if (i + 2 in span.tokenIndices) underlined += i + 1 }
        }
        val text = buildAnnotatedString {
            for (t in tokens) {
                val start = length
                val form = t.normalized
                if (form == null) {
                    if (t.index in underlined) withStyle(SpanStyle(textDecoration = TextDecoration.Underline)) { append(t.text) }
                    else append(t.text)
                } else {
                    val status = statuses[form] ?: WordStatus.NEW
                    val color = StatusColors.background(status)
                    if (color.alpha > 0f) colored += (start until start + t.text.length) to color
                    val style = SpanStyle(
                        color = onSurface,
                        fontWeight = if (t == selected) FontWeight.Bold else null,
                        textDecoration = if (t.index in underlined) TextDecoration.Underline else TextDecoration.None,
                    )
                    withLink(LinkAnnotation.Clickable(tag = t.index.toString(), styles = TextLinkStyles(style = style)) { onTap(t) }) {
                        append(t.text)
                    }
                }
                if (t.text.isNotBlank()) {
                    val r = ranges[t.sentenceIndex]
                    ranges[t.sentenceIndex] = if (r == null) start until length else r.first until length
                }
            }
        }
        PageAnnotation(text, ranges, colored)
    }
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    val density = LocalDensity.current
    val fontPx = with(density) { fontSize.sp.toPx() }
    val padX = with(density) { 1.dp.toPx() }
    val corner = CornerRadius(with(density) { 5.dp.toPx() })
    // Boxes hug the letters (not the full 1.6× line), so lines and neighbouring words stay visibly apart.
    val boxes: List<Pair<Rect, Color>> = remember(layout, built) {
        val l = layout ?: return@remember emptyList()
        built.coloredWords.mapNotNull { (r, color) ->
            if (r.last >= l.layoutInput.text.length) return@mapNotNull null
            val line = l.getLineForOffset(r.first)
            if (l.getLineForOffset(r.last) != line) return@mapNotNull null
            val baseline = l.getLineBaseline(line)
            Rect(
                left = l.getBoundingBox(r.first).left - padX,
                top = baseline - fontPx * 0.92f,
                right = l.getBoundingBox(r.last).right + padX,
                bottom = baseline + fontPx * 0.30f,
            ) to color
        }
    }
    val range = highlightSentence?.let { built.sentenceRanges[it] }

    LaunchedEffect(range, layout, scroll != null) {
        val l = layout ?: return@LaunchedEffect
        val r = range ?: return@LaunchedEffect
        val s = scroll ?: return@LaunchedEffect
        val top = l.getBoundingBox(r.first).top.toInt()
        val bottom = l.getBoundingBox(r.last).bottom.toInt()
        val viewport = s.viewportSize
        if (viewport > 0 && (top < s.value || bottom > s.value + viewport)) {
            s.animateScrollTo((top - viewport / 4).coerceAtLeast(0))
        }
    }

    Text(
        built.text,
        style = MaterialTheme.typography.bodyLarge.copy(fontSize = fontSize.sp, lineHeight = (fontSize * 1.6f).sp),
        color = onSurface,
        onTextLayout = { layout = it },
        modifier = Modifier.drawBehind {
            val l = layout
            if (l != null && range != null) drawPath(l.getPathForRange(range.first, range.last + 1), highlightColor)
            for ((rect, color) in boxes) drawRoundRect(color, rect.topLeft, rect.size, corner)
        },
    )
}

