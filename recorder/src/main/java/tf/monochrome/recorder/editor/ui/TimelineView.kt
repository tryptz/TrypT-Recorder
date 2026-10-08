package tf.monochrome.recorder.editor.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.CallSplit
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import tf.monochrome.recorder.capture.formatDuration
import tf.monochrome.recorder.editor.model.AudioClip
import tf.monochrome.recorder.editor.model.AudioKind
import tf.monochrome.recorder.editor.model.Project
import tf.monochrome.recorder.editor.model.TextItem
import tf.monochrome.recorder.editor.model.VideoClip
import tf.monochrome.recorder.editor.model.assignLanes
import tf.monochrome.recorder.editor.model.durationUs
import tf.monochrome.recorder.media.Thumbnails
import tf.monochrome.recorder.ui.DetachedTeal
import tf.monochrome.recorder.ui.MusicViolet
import tf.monochrome.recorder.ui.TextAmber
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

private val VideoLaneHeight = 56.dp
private val TextLaneHeight = 26.dp
private val AudioLaneHeight = 34.dp

/**
 * The timeline: the playhead is fixed in the middle and the tracks scroll
 * under it, as in most phone editors. Scrolling by hand seeks; playback
 * scrolls the tracks along.
 *
 * Telling the two apart needs no flags: when the scroll position is where the
 * playhead says it should be, playback (or a seek) put it there. When it is
 * not, the user moved it.
 */
@Composable
fun Timeline(
    project: Project,
    playheadUs: Long,
    selection: Selection,
    pixelsPerSecond: Dp,
    onScrub: (Long) -> Unit,
    onSelect: (Selection) -> Unit,
    onDragAudio: (id: String, startUs: Long) -> Unit,
    onDragText: (id: String, startUs: Long) -> Unit,
    onDragEnd: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val totalUs = project.durationUs
    // Compose cannot measure a layout wider than about 2^18 px; hold the zoom under that.
    val maxPxPerSec = 150_000f / max(totalUs / 1_000_000f, 1f)
    val pxPerSec = min(with(density) { pixelsPerSecond.toPx() }, maxPxPerSec)
    val dpPerUs = with(density) { pxPerSec.toDp().value } / 1_000_000f
    val pxPerUs = pxPerSec / 1_000_000f
    fun usToDp(us: Long): Dp = (us * dpPerUs).dp

    val scroll = rememberScrollState()
    val playhead by rememberUpdatedState(playheadUs)
    val scale by rememberUpdatedState(pxPerUs)
    val scrub by rememberUpdatedState(onScrub)

    // Until the first sync the scroll sits at 0 whatever the playhead says;
    // reading that as a scrub would throw the playhead back to the start.
    var synced by remember { mutableStateOf(false) }
    LaunchedEffect(playheadUs, pxPerUs) {
        if (!scroll.isScrollInProgress) {
            scroll.scrollTo((playheadUs * pxPerUs).roundToInt())
            synced = true
        }
    }
    LaunchedEffect(Unit) {
        snapshotFlow { scroll.value }.collect { v ->
            if (synced && abs(v - (playhead * scale).roundToInt()) > 2) scrub((v / scale).toLong())
        }
    }

    val lanes = remember(project.audio) { assignLanes(project.audio) }
    val noRipple = remember { MutableInteractionSource() }

    BoxWithConstraints(modifier.background(MaterialTheme.colorScheme.surfaceContainerLow)) {
        val half = maxWidth / 2
        Row(
            Modifier
                .fillMaxSize()
                .horizontalScroll(scroll),
        ) {
            Spacer(Modifier.width(half))
            Column(
                Modifier
                    .width(usToDp(totalUs))
                    .fillMaxHeight()
                    .clickable(interactionSource = noRipple, indication = null) { onSelect(Selection.None) },
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Ruler(totalUs, pxPerSec)
                Row(Modifier.height(VideoLaneHeight)) {
                    project.clips.forEach { clip ->
                        ClipBlock(
                            clip = clip,
                            width = usToDp(clip.durationUs),
                            selected = selection == Selection.Clip(clip.id),
                            onClick = { onSelect(Selection.Clip(clip.id)) },
                        )
                    }
                }
                if (project.texts.isNotEmpty()) {
                    Box(Modifier.fillMaxWidth().height(TextLaneHeight)) {
                        project.texts.forEach { t ->
                            TextBlock(
                                item = t,
                                x = usToDp(t.startUs),
                                width = usToDp(t.endUs - t.startUs),
                                selected = selection == Selection.Text(t.id),
                                pxPerUs = pxPerUs,
                                onClick = { onSelect(Selection.Text(t.id)) },
                                onDrag = { start -> onDragText(t.id, start) },
                                onDragEnd = onDragEnd,
                            )
                        }
                    }
                }
                lanes.forEach { lane ->
                    Box(Modifier.fillMaxWidth().height(AudioLaneHeight)) {
                        lane.forEach { a ->
                            AudioBlock(
                                clip = a,
                                x = usToDp(a.startUs),
                                width = usToDp(a.durationUs),
                                selected = selection == Selection.Audio(a.id),
                                pxPerUs = pxPerUs,
                                onClick = { onSelect(Selection.Audio(a.id)) },
                                onDrag = { start -> onDragAudio(a.id, start) },
                                onDragEnd = onDragEnd,
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.width(half))
        }
        // The playhead.
        Box(
            Modifier
                .align(Alignment.TopCenter)
                .width(2.dp)
                .fillMaxHeight()
                .background(Color.White),
        )
    }
}

@Composable
private fun Ruler(totalUs: Long, pxPerSec: Float) {
    val measurer = rememberTextMeasurer()
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    val minSpacingPx = with(LocalDensity.current) { 64.dp.toPx() }
    val step = remember(pxPerSec) {
        listOf(0.5f, 1f, 2f, 5f, 10f, 15f, 30f, 60f, 120f, 300f, 600f).firstOrNull { it * pxPerSec >= minSpacingPx } ?: 600f
    }
    Canvas(Modifier.fillMaxWidth().height(18.dp)) {
        val totalSec = totalUs / 1_000_000f
        var s = 0f
        while (s <= totalSec) {
            val x = s * pxPerSec
            drawLine(labelColor, Offset(x, size.height * 0.55f), Offset(x, size.height), strokeWidth = 1.5f)
            val label = formatDuration((s * 1000).toLong())
            drawText(measurer, label, Offset(x + 4f, 0f), style = androidx.compose.ui.text.TextStyle(color = labelColor, fontSize = 9.sp))
            s += step
        }
    }
}

@Composable
private fun ClipBlock(clip: VideoClip, width: Dp, selected: Boolean, onClick: () -> Unit) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val heightPx = with(density) { VideoLaneHeight.roundToPx() }
    // Tiles about as wide as the lane is tall; never more than 30 per clip.
    val tiles = ceil(width.value / VideoLaneHeight.value).toInt().coerceIn(1, 30)
    Box(
        Modifier
            .width(width)
            .fillMaxHeight()
            .padding(horizontal = 1.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(Color(0xFF202020))
            .then(if (selected) Modifier.border(2.dp, Color.White, RoundedCornerShape(6.dp)) else Modifier)
            .clickable(onClick = onClick),
    ) {
        Row(Modifier.fillMaxSize()) {
            repeat(tiles) { i ->
                val atUs = clip.trimStartUs + ((i + 0.5f) / tiles * clip.sourceSpanUs).toLong()
                val frame by produceState<android.graphics.Bitmap?>(null, clip.source.uri, atUs) {
                    value = Thumbnails.frame(context, clip.source.uri, atUs, heightPx / 2)
                }
                Box(Modifier.weight(1f).fillMaxHeight()) {
                    frame?.let {
                        Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                    }
                }
            }
        }
        Row(
            Modifier
                .align(Alignment.TopStart)
                .padding(3.dp),
            horizontalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            if (clip.speed != 1f) Badge("${clip.speed.toString().removeSuffix(".0")}×")
            if (clip.audioDetached) BadgeIcon { Icon(Icons.AutoMirrored.Filled.CallSplit, null, Modifier.width(12.dp), tint = DetachedTeal) }
            else if (clip.volume == 0f && clip.source.hasAudio) BadgeIcon { Icon(Icons.AutoMirrored.Filled.VolumeOff, null, Modifier.width(12.dp)) }
        }
    }
}

@Composable
private fun Badge(text: String) {
    Text(
        text,
        fontSize = 10.sp,
        color = Color.White,
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(Color(0xAA000000))
            .padding(horizontal = 4.dp, vertical = 1.dp),
    )
}

@Composable
private fun BadgeIcon(content: @Composable () -> Unit) {
    Box(
        Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(Color(0xAA000000))
            .padding(2.dp),
    ) { content() }
}

@Composable
private fun AudioBlock(
    clip: AudioClip,
    x: Dp,
    width: Dp,
    selected: Boolean,
    pxPerUs: Float,
    onClick: () -> Unit,
    onDrag: (Long) -> Unit,
    onDragEnd: () -> Unit,
) {
    val color = if (clip.kind == AudioKind.DETACHED) DetachedTeal else MusicViolet
    FreeBlock(
        x = x,
        width = width,
        startUs = clip.startUs,
        color = color,
        label = clip.source.displayName,
        selected = selected,
        pxPerUs = pxPerUs,
        onClick = onClick,
        onDrag = onDrag,
        onDragEnd = onDragEnd,
    )
}

@Composable
private fun TextBlock(
    item: TextItem,
    x: Dp,
    width: Dp,
    selected: Boolean,
    pxPerUs: Float,
    onClick: () -> Unit,
    onDrag: (Long) -> Unit,
    onDragEnd: () -> Unit,
) = FreeBlock(
    x = x,
    width = width,
    startUs = item.startUs,
    color = TextAmber,
    label = item.text,
    selected = selected,
    pxPerUs = pxPerUs,
    onClick = onClick,
    onDrag = onDrag,
    onDragEnd = onDragEnd,
)

/** A block placed freely on its lane; once selected, it can be dragged along it. */
@Composable
private fun FreeBlock(
    x: Dp,
    width: Dp,
    startUs: Long,
    color: Color,
    label: String,
    selected: Boolean,
    pxPerUs: Float,
    onClick: () -> Unit,
    onDrag: (Long) -> Unit,
    onDragEnd: () -> Unit,
) {
    val start by rememberUpdatedState(startUs)
    val drag by rememberUpdatedState(onDrag)
    val end by rememberUpdatedState(onDragEnd)
    val dragModifier = if (selected) {
        Modifier.pointerInput(pxPerUs) {
            var from = 0L
            var moved = 0f
            detectHorizontalDragGestures(
                onDragStart = { from = start; moved = 0f },
                onDragEnd = { end() },
                onDragCancel = { end() },
            ) { change, dx ->
                change.consume()
                moved += dx
                drag((from + moved / pxPerUs).toLong().coerceAtLeast(0))
            }
        }
    } else {
        Modifier
    }
    Box(
        Modifier
            .offset(x = x)
            .width(width)
            .fillMaxHeight()
            .padding(horizontal = 1.dp)
            .clip(RoundedCornerShape(5.dp))
            .background(color.copy(alpha = if (selected) 0.85f else 0.45f))
            .then(if (selected) Modifier.border(2.dp, Color.White, RoundedCornerShape(5.dp)) else Modifier)
            .then(dragModifier)
            .clickable(onClick = onClick)
            .padding(horizontal = 6.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(label, fontSize = 11.sp, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
