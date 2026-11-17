package com.mastifdogo.devotion.ui

import android.content.Context
import android.graphics.Typeface
import android.os.Build
import android.text.SpannableString
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.text.style.UnderlineSpan
import android.util.TypedValue
import android.view.ActionMode
import android.view.GestureDetector
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.widget.TextView
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.viewinterop.AndroidView
import com.mastifdogo.devotion.data.Annotation
import com.mastifdogo.devotion.logic.CommentMarker
import com.mastifdogo.devotion.logic.HighlightLogic
import com.mastifdogo.devotion.logic.Passage
import kotlin.math.roundToInt

private const val MENU_HIGHLIGHT = 0x44650001
private const val MENU_COMMENT = 0x44650002

/**
 * An entry body that is selected like text to copy (long-press, drag handles) and whose selection
 * menu also offers "Highlight" and "Comment". It is a platform TextView: Compose's own selection
 * cannot add items to the floating toolbar with stable APIs. A short tap on a link opens it, a
 * short tap on a highlight (or its comment marker) opens that annotation.
 *
 * All offsets passed to the callbacks are body offsets.
 */
@Composable
fun EntryBodyText(
    body: String,
    annotations: List<Annotation>,
    links: List<Passage?>,
    dark: Boolean,
    onHighlight: (Int, Int) -> Unit,
    onComment: (Int, Int) -> Unit,
    onOpenAnnotation: (Annotation) -> Unit,
    onOpenLink: (Int) -> Unit,
    modifier: Modifier = Modifier,
    /** Ranges (body offsets) drawn as verse numbers: bold, smaller, primary colour. */
    verseNumbers: List<Passage> = emptyList(),
    /** A range (body offsets) marked with a tinted background, e.g. the referenced verses. */
    marked: Passage? = null,
    markColor: Color = Color.Unspecified,
    /** A second tinted range (body offsets), e.g. the verse being read aloud. */
    reading: Passage? = null,
    readingColor: Color = Color.Unspecified,
    /** When set, a tap on verse number i (or the space after it) calls this instead of anything else. */
    onTapNumber: ((Int) -> Unit)? = null,
    /**
     * When [scrollKey] changes and [scrollToOffset] is set, [onScrollTarget] receives the y (in
     * pixels, from the top of this text) of the line holding that body offset, once laid out.
     */
    scrollToOffset: Int? = null,
    scrollKey: Any? = null,
    onScrollTarget: (Int) -> Unit = {},
    /** Filled in so the caller can ask where a body offset is drawn (for "follow along"). */
    locator: BodyLocator? = null,
) {
    val colors = MaterialTheme.colorScheme
    val style = MaterialTheme.typography.bodyLarge
    val density = LocalDensity.current
    val textColor = colors.onSurface.toArgb()
    val linkColor = colors.primary.toArgb()
    val selectionColor = colors.primary.copy(alpha = 0.35f).toArgb()
    val textSizeSp = if (style.fontSize.isSp) style.fontSize.value else 16f
    val lineHeightPx = if (style.lineHeight.isSp) with(density) { style.lineHeight.toPx() } else 0f

    val markers = remember(body, annotations) { HighlightLogic.commentMarkers(annotations, body.length) }
    val numberColor = colors.primary.toArgb()
    val markArgb = if (markColor == Color.Unspecified) 0 else markColor.toArgb()
    val readingArgb = if (readingColor == Color.Unspecified) 0 else readingColor.toArgb()
    val content = remember(
        body, annotations, links, markers, dark, linkColor, verseNumbers, marked, markArgb, numberColor, reading, readingArgb,
    ) {
        buildBodyText(
            body, annotations, links, markers, dark, linkColor, verseNumbers, marked, markArgb, numberColor, reading, readingArgb,
        )
    }
    val callbacks = remember { BodyCallbacks() }

    AndroidView(
        factory = { context -> createBodyView(context, callbacks) },
        modifier = modifier,
        update = { view ->
            callbacks.body = body
            callbacks.annotations = annotations
            callbacks.links = links
            callbacks.markers = markers
            callbacks.onHighlight = onHighlight
            callbacks.onComment = onComment
            callbacks.onOpenAnnotation = onOpenAnnotation
            callbacks.onOpenLink = onOpenLink
            callbacks.verseNumbers = verseNumbers
            callbacks.onTapNumber = onTapNumber
            locator?.resolve = { bodyOffset ->
                val layout = view.layout
                if (layout == null) {
                    null
                } else {
                    val display = HighlightLogic.toDisplayStart(markers, bodyOffset).coerceIn(0, view.text.length)
                    val line = layout.getLineForOffset(display)
                    (layout.getLineTop(line) + layout.getLineBottom(line)) / 2 + view.totalPaddingTop
                }
            }
            view.setTextColor(textColor)
            view.setHighlightColor(selectionColor)
            view.setTextSize(TypedValue.COMPLEX_UNIT_SP, textSizeSp)
            applyLineHeight(view, lineHeightPx)
            // Only replace the text when it really changed: setText drops the current selection.
            if (view.tag !== content) {
                view.setText(content, TextView.BufferType.SPANNABLE)
                view.tag = content
            }
            val target = scrollToOffset
            if (target != null && scrollKey != null && scrollKey != callbacks.lastScrollKey) {
                callbacks.lastScrollKey = scrollKey
                val displayTarget = HighlightLogic.toDisplayStart(markers, target)
                val report = onScrollTarget
                // After the next layout pass, when the TextView knows where its lines are.
                view.post {
                    val layout = view.layout
                    if (layout != null) {
                        val offset = displayTarget.coerceIn(0, view.text.length)
                        report(layout.getLineTop(layout.getLineForOffset(offset)) + view.totalPaddingTop)
                    }
                }
            }
        },
    )
}

/**
 * Lets the caller find where text is drawn: [lineCenterY] gives the y (pixels from the top of the
 * body view) of the middle of the line holding a body offset, or null before the first layout.
 * Comment markers inserted into the shown text are accounted for.
 */
class BodyLocator {
    internal var resolve: (Int) -> Int? = { null }

    fun lineCenterY(bodyOffset: Int): Int? = resolve(bodyOffset)
}

/** The latest data and callbacks, read by the view's listeners (which are created once). */
private class BodyCallbacks {
    var body: String = ""
    var annotations: List<Annotation> = emptyList()
    var links: List<Passage?> = emptyList()
    var markers: List<CommentMarker> = emptyList()
    var onHighlight: (Int, Int) -> Unit = { _, _ -> }
    var onComment: (Int, Int) -> Unit = { _, _ -> }
    var onOpenAnnotation: (Annotation) -> Unit = {}
    var onOpenLink: (Int) -> Unit = {}
    var verseNumbers: List<Passage> = emptyList()
    var onTapNumber: ((Int) -> Unit)? = null
    var lastScrollKey: Any? = null

    /** The current selection as a body range, or null when nothing (or only a marker) is selected. */
    fun selectedBodyRange(view: TextView): Pair<Int, Int>? {
        val a = minOf(view.selectionStart, view.selectionEnd)
        val b = maxOf(view.selectionStart, view.selectionEnd)
        if (a < 0 || a == b) return null
        val start = HighlightLogic.toBody(markers, a).coerceIn(0, body.length)
        val end = HighlightLogic.toBody(markers, b).coerceIn(0, body.length)
        return if (start < end) start to end else null
    }

    /** A short tap on display offset [displayOffset]: link first, then marker, then highlight. */
    fun tap(displayOffset: Int) {
        val markerId = HighlightLogic.markerAt(markers, displayOffset)
        if (markerId != null) {
            annotations.firstOrNull { it.id == markerId && !it.deleted }?.let { onOpenAnnotation(it) }
            return
        }
        val offset = HighlightLogic.toBody(markers, displayOffset)
        // A verse number (only when the caller wants those taps): never the verse text itself.
        val tapNumber = onTapNumber
        if (tapNumber != null) {
            val number = HighlightLogic.numberAt(verseNumbers, offset)
            if (number >= 0) {
                tapNumber(number)
                return
            }
        }
        val link = HighlightLogic.linkAt(links, offset)
        if (link >= 0) {
            onOpenLink(link)
            return
        }
        HighlightLogic.annotationAt(annotations, body.length, offset)?.let { onOpenAnnotation(it) }
    }
}

private fun createBodyView(context: Context, callbacks: BodyCallbacks): TextView {
    val view = TextView(context)
    view.setTextIsSelectable(true)
    view.customSelectionActionModeCallback = object : ActionMode.Callback {
        override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
            menu.add(Menu.NONE, MENU_HIGHLIGHT, 1, "Highlight")
            menu.add(Menu.NONE, MENU_COMMENT, 2, "Comment")
            return true
        }

        override fun onPrepareActionMode(mode: ActionMode, menu: Menu): Boolean = false

        override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean {
            val id = item.itemId
            if (id != MENU_HIGHLIGHT && id != MENU_COMMENT) return false
            // Read the selection before finishing the mode, which collapses it.
            val range = callbacks.selectedBodyRange(view)
            mode.finish()
            if (range != null) {
                if (id == MENU_HIGHLIGHT) {
                    callbacks.onHighlight(range.first, range.second)
                } else {
                    callbacks.onComment(range.first, range.second)
                }
            }
            return true
        }

        override fun onDestroyActionMode(mode: ActionMode) {
        }
    }
    // Short taps are detected here rather than with LinkMovementMethod, which does not work
    // together with selectable text. Returning false lets the TextView handle every event too.
    val detector = GestureDetector(
        context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapUp(e: MotionEvent): Boolean {
                val offset = charOffsetAt(view, e.x, e.y)
                if (offset >= 0) callbacks.tap(offset)
                return false
            }
        },
    )
    view.setOnTouchListener { _, event ->
        detector.onTouchEvent(event)
        false
    }
    return view
}

/** Offset of the character under (x, y) in view coordinates, or -1 when the point is not on text. */
private fun charOffsetAt(view: TextView, x: Float, y: Float): Int {
    val layout = view.layout ?: return -1
    val localX = x - view.totalPaddingLeft + view.scrollX
    val localY = y - view.totalPaddingTop + view.scrollY
    if (localY < 0f || localY > layout.height) return -1
    val line = layout.getLineForVertical(localY.toInt())
    if (localX < layout.getLineLeft(line) || localX > layout.getLineRight(line)) return -1
    var offset = layout.getOffsetForHorizontal(line, localX)
    // getOffsetForHorizontal gives the nearest boundary; step back to the character under the finger.
    if (offset > layout.getLineStart(line) && layout.getPrimaryHorizontal(offset) > localX) offset--
    return if (offset < view.text.length) offset else -1
}

private fun applyLineHeight(view: TextView, px: Float) {
    if (px <= 0f) return
    val target = px.roundToInt()
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        if (view.lineHeight != target) view.setLineHeight(target)
    } else {
        val natural = view.paint.getFontMetricsInt(null)
        view.setLineSpacing((target - natural).coerceAtLeast(0).toFloat(), 1f)
    }
}

/** The body with comment markers inserted, highlight backgrounds and link styling. */
private fun buildBodyText(
    body: String,
    annotations: List<Annotation>,
    links: List<Passage?>,
    markers: List<CommentMarker>,
    dark: Boolean,
    linkColor: Int,
    verseNumbers: List<Passage>,
    marked: Passage?,
    markArgb: Int,
    numberColor: Int,
    reading: Passage?,
    readingArgb: Int,
): CharSequence {
    val text = SpannableString(HighlightLogic.displayText(body, markers))
    // The marked range first, so highlights are drawn over it.
    if (marked != null && markArgb != 0) {
        val start = HighlightLogic.toDisplayStart(markers, marked.start)
        val end = HighlightLogic.toDisplayEnd(markers, marked.end)
        if (start < end) text.setSpan(BackgroundColorSpan(markArgb), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }
    // The verse being read: over the referenced-verse tint, under the user's highlights.
    if (reading != null && readingArgb != 0) {
        val start = HighlightLogic.toDisplayStart(markers, reading.start)
        val end = HighlightLogic.toDisplayEnd(markers, reading.end)
        if (start < end) text.setSpan(BackgroundColorSpan(readingArgb), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }
    for (number in verseNumbers) {
        val start = HighlightLogic.toDisplayStart(markers, number.start)
        val end = HighlightLogic.toDisplayEnd(markers, number.end)
        if (start >= end) continue
        text.setSpan(StyleSpan(Typeface.BOLD), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        text.setSpan(RelativeSizeSpan(0.75f), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        text.setSpan(ForegroundColorSpan(numberColor), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }
    for (run in HighlightLogic.styledRuns(body.length, annotations, links)) {
        val start = HighlightLogic.toDisplayStart(markers, run.start)
        val end = HighlightLogic.toDisplayEnd(markers, run.end)
        if (start >= end) continue
        val background = highlightColor(run.color, dark)
        if (background != null) {
            text.setSpan(BackgroundColorSpan(background.toArgb()), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        if (run.link >= 0) {
            text.setSpan(ForegroundColorSpan(linkColor), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            text.setSpan(UnderlineSpan(), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }
    for (marker in markers) {
        val start = HighlightLogic.toDisplayEnd(markers, marker.offset)
        val end = start + HighlightLogic.MARKER.length
        text.setSpan(RelativeSizeSpan(0.85f), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }
    return text
}
