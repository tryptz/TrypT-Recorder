package tf.monochrome.recorder.editor.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.zIndex
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import tf.monochrome.recorder.R
import tf.monochrome.recorder.editor.model.AudioRole
import tf.monochrome.recorder.editor.model.MixerFx
import tf.monochrome.recorder.editor.model.Project
import tf.monochrome.recorder.editor.model.RoleMix
import tf.monochrome.recorder.editor.model.SnapinType
import tf.monochrome.recorder.editor.model.durationUs
import tf.monochrome.recorder.editor.ui.fx.FLKnobControl
import tf.monochrome.recorder.editor.ui.fx.FxChainColors
import tf.monochrome.recorder.editor.ui.fx.FxPresets
import tf.monochrome.recorder.editor.ui.fx.FxVisual
import tf.monochrome.recorder.editor.ui.fx.rememberFxChainDragState
import tf.monochrome.recorder.editor.model.getParamDefs
import kotlin.math.log10
import kotlin.math.pow

private val channels = AudioRole.entries + listOf(null)
private val accents = listOf(
    Color(0xFF7CB1FF), Color(0xFF58DBC2), Color(0xFFC397FF), Color(0xFFFF9278), Color(0xFFC9EF40),
)
private val panelColor = Color(0xFF1F2025)
private val mutedText = Color(0xFFB6B7C0)
private const val minimumDb = -60f
private val maximumDb = gainDb(1.5f)
private const val unityFader = 0.76f
private const val maxInserts = 16

@Composable
internal fun MixerPanel(vm: EditorViewModel, project: Project, playing: Boolean, playhead: Long, close: () -> Unit) {
    val peaks by vm.mixerPeaks.collectAsStateWithLifecycle()
    var selected by rememberSaveable { mutableIntStateOf(0) }
    var showEffects by rememberSaveable { mutableStateOf(false) }
    var picking by rememberSaveable { mutableStateOf(false) }
    var expanded by rememberSaveable { mutableStateOf<String?>(null) }
    val selectedRole = channels[selected]
    val channel = project.mixer.channel(selectedRole)
    val accent = accents[selected]

    fun change(role: AudioRole?, preview: Boolean = false, update: (RoleMix) -> RoleMix) {
        val transform: (Project) -> Project = { it.copy(mixer = it.mixer.editChannel(role, update)) }
        if (preview) vm.preview(transform) else vm.edit(transform)
    }

    fun back() {
        when {
            picking -> picking = false
            showEffects -> showEffects = false
            else -> close()
        }
    }

    Dialog(
        onDismissRequest = ::back,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        Surface(Modifier.fillMaxSize(), color = Color(0xFF121318), contentColor = Color(0xFFF4F4F7)) {
            Column(
                Modifier.fillMaxSize().background(
                    Brush.verticalGradient(listOf(Color(0xFF242821), Color(0xFF18191E), Color(0xFF121318))),
                ).systemBarsPadding(),
            ) {
                Row(
                    Modifier.fillMaxWidth().background(panelColor).padding(end = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = ::back) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.action_back))
                    }
                    Text(
                        stringResource(if (showEffects) R.string.mixer_fx_chain else R.string.pro_mixer),
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                    )
                    TextButton(
                        onClick = { vm.edit { it.copy(mixer = it.mixer.copy(fxEnabled = !it.mixer.fxEnabled)) } },
                        colors = ButtonDefaults.textButtonColors(
                            containerColor = if (project.mixer.fxEnabled) accents.last() else Color(0xFF32333B),
                            contentColor = if (project.mixer.fxEnabled) Color(0xFF161A0A) else Color.White,
                        ),
                    ) {
                        Icon(Icons.Filled.PowerSettingsNew, null, Modifier.size(17.dp))
                        Text(stringResource(if (project.mixer.fxEnabled) R.string.mixer_fx_on else R.string.mixer_fx_off))
                    }
                }
                if (showEffects) {
                    Row(
                        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        channels.forEachIndexed { i, role ->
                            FilterChip(
                                selected == i, { selected = i; expanded = null },
                                label = { Text(channelName(role)) },
                                leadingIcon = {
                                    Box(Modifier.size(8.dp).background(accents[i], CircleShape))
                                },
                            )
                        }
                    }
                    val listState = rememberLazyListState()
                    val scope = rememberCoroutineScope()
                    var reordered by remember(selected) { mutableStateOf<List<MixerFx>?>(null) }
                    val effects = reordered ?: channel.effects
                    val drag = rememberFxChainDragState(
                        listState, scope,
                        indexOfKey = { key -> effects.indexOfFirst { it.id == key }.takeIf { it >= 0 } },
                        onMove = { from, to ->
                            reordered = (reordered ?: channel.effects).toMutableList().apply { add(to, removeAt(from)) }
                        },
                        onCommit = { _, _ -> reordered?.let { next -> change(selectedRole) { it.copy(effects = next) } } },
                    )
                    LazyColumn(
                        Modifier.weight(1f).fillMaxWidth().padding(horizontal = 8.dp),
                        state = listState,
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        item {
                            Text(
                                stringResource(R.string.fx_chain_input, channelName(selectedRole)),
                                modifier = Modifier.fillMaxWidth().background(panelColor, RoundedCornerShape(8.dp)).padding(14.dp),
                                color = mutedText, fontWeight = FontWeight.Bold,
                            )
                        }
                        items(effects, key = { it.id }) { fx ->
                            val index = effects.indexOf(fx)
                            val dragging = drag.draggingKey == fx.id
                            FxCard(
                                fx, FxChainColors.categoryColor(fx.type.category), expanded == fx.id && !drag.isDragging,
                                position = index + 1,
                                modifier = Modifier.zIndex(if (dragging) 1f else 0f)
                                    .graphicsLayer { translationY = drag.translationFor(fx.id) },
                                dragHandle = Modifier.pointerInput(fx.id, drag) {
                                    detectDragGesturesAfterLongPress(
                                        onDragStart = { drag.onDragStart(fx.id) },
                                        onDrag = { event, delta -> event.consume(); drag.onDrag(delta.y) },
                                        onDragEnd = { drag.onDragEnd(); reordered = null },
                                        onDragCancel = { drag.cancel(); reordered = null },
                                    )
                                },
                                open = { expanded = if (expanded == fx.id) null else fx.id },
                                canMoveUp = index > 0,
                                canMoveDown = index < channel.effects.lastIndex,
                                move = { step ->
                                    change(selectedRole) { bus ->
                                        val next = bus.effects.toMutableList()
                                        val from = next.indexOfFirst { it.id == fx.id }
                                        if (from >= 0 && from + step in next.indices) {
                                            next.add(from + step, next.removeAt(from))
                                        }
                                        bus.copy(effects = next)
                                    }
                                },
                                remove = { change(selectedRole) { it.copy(effects = it.effects.filterNot { item -> item.id == fx.id }) } },
                                update = { preview, edit ->
                                    change(selectedRole, preview) { bus ->
                                        bus.copy(effects = bus.effects.map { if (it.id == fx.id) edit(it) else it })
                                    }
                                },
                                commit = vm::commit,
                            )
                        }
                        item {
                            Button(
                                onClick = { picking = true },
                                enabled = channel.effects.size < maxInserts,
                                colors = ButtonDefaults.buttonColors(containerColor = accent, contentColor = Color(0xFF111318)),
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Icon(Icons.Filled.Add, null, Modifier.padding(end = 6.dp))
                                Text(stringResource(R.string.mixer_add_effect))
                            }
                        }
                    }
                } else {
                    BoxWithConstraints(Modifier.fillMaxWidth().weight(1f).padding(horizontal = 8.dp, vertical = 12.dp)) {
                        val stripWidth = ((maxWidth - 6.dp * (channels.size - 1)) / channels.size).coerceAtLeast(66.dp)
                        Row(Modifier.fillMaxHeight().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            channels.forEachIndexed { i, role ->
                                MixerStrip(
                                    name = channelName(role),
                                    mix = project.mixer.channel(role),
                                    accent = accents[i],
                                    selected = selected == i,
                                    master = role == null,
                                    solo = role != null && project.mixer.solo == role,
                                    peakLeft = if (playing) peaks.getOrElse(i * 2) { 0f } else 0f,
                                    peakRight = if (playing) peaks.getOrElse(i * 2 + 1) { 0f } else 0f,
                                    select = { selected = i },
                                    openEffects = { selected = i; expanded = null; showEffects = true },
                                    gain = { value -> change(role, true) { it.copy(gain = value) } },
                                    commit = vm::commit,
                                    mute = { change(role) { it.copy(muted = !it.muted) } },
                                    toggleSolo = {
                                        vm.edit { p ->
                                            p.copy(mixer = p.mixer.copy(solo = role.takeUnless { p.mixer.solo == it }))
                                        }
                                    },
                                    modifier = Modifier.width(stripWidth).fillMaxHeight(),
                                )
                            }
                        }
                    }
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(stringResource(R.string.mixer_pan, channelName(selectedRole)), modifier = Modifier.weight(1f))
                        Text(
                            when {
                                channel.balance < -0.01f -> stringResource(R.string.mixer_pan_left, (-channel.balance * 100).toInt())
                                channel.balance > 0.01f -> stringResource(R.string.mixer_pan_right, (channel.balance * 100).toInt())
                                else -> stringResource(R.string.mixer_pan_center)
                            },
                            color = accent,
                            fontFamily = FontFamily.Monospace,
                        )
                        TextButton(onClick = { change(selectedRole) { it.copy(balance = 0f) } }) {
                            Text(stringResource(R.string.mixer_reset), color = accent)
                        }
                    }
                    Slider(
                        channel.balance,
                        { value -> change(selectedRole, true) { it.copy(balance = value) } },
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                        valueRange = -1f..1f,
                        onValueChangeFinished = vm::commit,
                        colors = SliderDefaults.colors(thumbColor = accent, activeTrackColor = accent),
                    )
                }
                HorizontalDivider(color = Color(0xFF363741))
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = vm::togglePlay) {
                        Icon(
                            if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                            stringResource(if (playing) R.string.action_pause else R.string.action_play),
                        )
                    }
                    Text(formatMixerTime(playhead), color = mutedText, fontFamily = FontFamily.Monospace)
                    Slider(
                        playhead.toFloat().coerceIn(0f, project.durationUs.toFloat().coerceAtLeast(1f)),
                        { vm.scrubTo(it.toLong()) },
                        modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                        valueRange = 0f..project.durationUs.toFloat().coerceAtLeast(1f),
                        colors = SliderDefaults.colors(thumbColor = accents.last(), activeTrackColor = accents.last()),
                    )
                    Text(formatMixerTime(project.durationUs), color = mutedText, fontFamily = FontFamily.Monospace)
                }
            }
        }
        if (picking) {
            FxPicker(close = { picking = false }) { type ->
                val fx = MixerFx(type)
                change(selectedRole) { it.copy(effects = it.effects + fx) }
                expanded = fx.id
                picking = false
            }
        }
    }
}

@Composable
private fun MixerStrip(
    name: String,
    mix: RoleMix,
    accent: Color,
    selected: Boolean,
    master: Boolean,
    solo: Boolean,
    peakLeft: Float,
    peakRight: Float,
    select: () -> Unit,
    openEffects: () -> Unit,
    gain: (Float) -> Unit,
    commit: () -> Unit,
    mute: () -> Unit,
    toggleSolo: () -> Unit,
    modifier: Modifier,
) {
    val shape = RoundedCornerShape(15.dp)
    val faderLabel = stringResource(R.string.mixer_fader, name)
    Column(
        modifier.background(Brush.verticalGradient(listOf(accent.copy(alpha = 0.15f), panelColor)), shape)
            .border(1.dp, if (selected) accent else accent.copy(alpha = 0.35f), shape)
            .padding(horizontal = 3.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier.size(44.dp).clickable(onClickLabel = name, onClick = select),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                Modifier.size(31.dp)
                    .background(if (selected) accent else Color.Transparent, CircleShape)
                    .border(1.dp, accent, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Text(name.take(1), color = if (selected) Color(0xFF14161A) else accent, fontWeight = FontWeight.Bold)
            }
        }
        Text(name, style = MaterialTheme.typography.labelSmall, maxLines = 1, fontWeight = FontWeight.Bold)
        TextButton(onClick = openEffects) {
            Text(stringResource(R.string.mixer_fx_count, mix.effects.count { it.enabled }), color = accent, maxLines = 1)
        }
        Row(
            Modifier.weight(1f).fillMaxWidth().padding(horizontal = 1.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Slider(
                faderPosition(mix.gain),
                { position ->
                    val db = if (position < unityFader) minimumDb * (1f - position / unityFader)
                    else maximumDb * (position - unityFader) / (1f - unityFader)
                    gain(if (position == 0f) 0f else 10f.pow(db / 20f))
                },
                modifier = Modifier.width(41.dp).fillMaxHeight().verticalFader()
                    .semantics { contentDescription = faderLabel },
                onValueChangeFinished = commit,
                colors = SliderDefaults.colors(thumbColor = accent, activeTrackColor = accent, inactiveTrackColor = Color(0xFF111216)),
            )
            PeakMeter(peakLeft, peakRight, accent, Modifier.width(13.dp).fillMaxHeight().padding(vertical = 14.dp))
        }
        Text(
            if (mix.gain <= 0f) "−∞" else "%+.1f".format(gainDb(mix.gain)),
            modifier = Modifier.padding(top = 3.dp).background(Color(0xFF15161A), RoundedCornerShape(6.dp))
                .padding(horizontal = 5.dp, vertical = 4.dp),
            color = accent,
            fontFamily = FontFamily.Monospace,
            style = MaterialTheme.typography.labelMedium,
        )
        StripButton("M", stringResource(R.string.pro_mute), mix.muted, accent, mute)
        if (!master) StripButton("S", stringResource(R.string.pro_solo), solo, accent, toggleSolo)
        else Text(
            stringResource(R.string.mixer_peak),
            Modifier.height(44.dp).padding(top = 12.dp),
            color = if (maxOf(peakLeft, peakRight) >= 1f) Color(0xFFFF806E) else mutedText,
            style = MaterialTheme.typography.labelSmall,
        )
    }
}

@Composable
private fun StripButton(letter: String, label: String, active: Boolean, accent: Color, onClick: () -> Unit) {
    Box(
        Modifier.size(44.dp).clickable(onClickLabel = label, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier.size(30.dp).background(if (active) accent else Color(0xFF33343C), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text(letter, color = if (active) Color(0xFF15161A) else mutedText, fontWeight = FontWeight.Bold)
        }
    }
}

private fun Modifier.verticalFader(): Modifier = layout { measurable, constraints ->
    val slider = measurable.measure(
        Constraints(
            minWidth = constraints.minHeight, maxWidth = constraints.maxHeight,
            minHeight = constraints.minWidth, maxHeight = constraints.maxWidth,
        ),
    )
    layout(slider.height, slider.width) {
        slider.place((slider.height - slider.width) / 2, (slider.width - slider.height) / 2)
    }
}.graphicsLayer { rotationZ = -90f }

@Composable
private fun PeakMeter(left: Float, right: Float, accent: Color, modifier: Modifier) {
    Canvas(modifier) {
        val segments = 30
        val segmentHeight = size.height / segments
        val laneWidth = (size.width - 2.dp.toPx()) / 2f
        for (channel in 0..1) {
            val peak = if (channel == 0) left else right
            val fraction = ((gainDb(peak) - minimumDb) / -minimumDb).coerceIn(0f, 1f)
            for (segment in 0 until segments) {
                val lit = segment < fraction * segments
                val tone = if (segment >= segments - 2 && peak >= 1f) Color(0xFFFF735D) else accent
                drawRect(
                    if (lit) tone else tone.copy(alpha = 0.12f),
                    Offset(channel * (laneWidth + 2.dp.toPx()), size.height - (segment + 1) * segmentHeight),
                    Size(laneWidth, segmentHeight * 0.76f),
                )
            }
        }
    }
}

@Composable
private fun FxCard(
    fx: MixerFx,
    accent: Color,
    expanded: Boolean,
    position: Int,
    modifier: Modifier,
    dragHandle: Modifier,
    open: () -> Unit,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    move: (Int) -> Unit,
    remove: () -> Unit,
    update: (Boolean, (MixerFx) -> MixerFx) -> Unit,
    commit: () -> Unit,
) {
    val scheme = darkColorScheme(
        surfaceContainerLowest = Color(0xFF17171D),
        surfaceContainerHighest = Color(0xFF343741),
        onSurfaceVariant = Color(0xFFBCBBC5),
    )
    MaterialTheme(colorScheme = scheme) {
        Surface(modifier, shape = RoundedCornerShape(14.dp), color = Color(0xFF24242B), contentColor = Color.White) {
            Column(Modifier.fillMaxWidth()) {
                Row(Modifier.fillMaxWidth().height(48.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.width(4.dp).fillMaxHeight().background(accent.copy(alpha = if (fx.enabled) 1f else 0.3f)))
                    Icon(
                        Icons.Filled.DragHandle, stringResource(R.string.fx_reorder),
                        modifier = dragHandle.padding(7.dp).size(20.dp), tint = mutedText,
                    )
                    Text(position.toString(), color = mutedText, style = MaterialTheme.typography.labelSmall)
                    Text(
                        fx.type.displayName, color = if (fx.enabled) accent else mutedText, fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f).clickable(onClick = open).padding(8.dp),
                    )
                    IconButton(onClick = { update(false) { it.copy(enabled = !it.enabled) } }) {
                        Icon(Icons.Filled.PowerSettingsNew, stringResource(R.string.mixer_bypass), tint = if (fx.enabled) accent else mutedText)
                    }
                    IconButton(onClick = open) {
                        Icon(
                            if (expanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                            stringResource(if (expanded) R.string.mixer_collapse else R.string.mixer_expand),
                        )
                    }
                    IconButton(onClick = remove) { Icon(Icons.Filled.Close, stringResource(R.string.mixer_remove_effect)) }
                }
                if (expanded) {
                    Column(Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
                        val defs = remember(fx.type) { getParamDefs(fx.type) }
                        val setParam: (Int, Float) -> Unit = { index, value ->
                            update(true) { it.copy(values = it.values + (index to value)) }
                        }
                        FxVisual(fx, accent, onParam = setParam, onFinish = commit)
                        Row(
                            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(vertical = 4.dp),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            remember(fx.type) { FxPresets.forType(fx.type) }.forEach { preset ->
                                FilterChip(
                                    selected = preset.matches(defs, fx.values, fx.wet),
                                    onClick = { update(false) { it.copy(values = preset.values, wet = preset.dryWet) } },
                                    label = { Text(preset.name) },
                                )
                            }
                        }
                        val indices = defs.indices.toList()
                        val rows = if (fx.type == SnapinType.EQ_10BAND) listOf(listOf(0)) + indices.drop(1).chunked(5)
                        else indices.chunked(4)
                        for ((rowIndex, row) in rows.withIndex()) {
                            if (fx.type == SnapinType.EQ_10BAND && rowIndex > 0) {
                                Text(stringResource(R.string.fx_eq_band, rowIndex), color = mutedText, style = MaterialTheme.typography.labelSmall)
                            }
                            Row(Modifier.fillMaxWidth()) {
                                for (index in row) {
                                    val parameter = defs[index]
                                    FLKnobControl(
                                        parameter.name, fx.values[index] ?: parameter.default, parameter.min, parameter.max,
                                        parameter.unit, accent, { setParam(index, it) }, Modifier.weight(1f),
                                        steps = parameter.steps, default = parameter.default, onFinish = commit,
                                    )
                                }
                                repeat((if (fx.type == SnapinType.EQ_10BAND) 5 else 4) - row.size) {
                                    Box(Modifier.weight(1f))
                                }
                            }
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(stringResource(R.string.mixer_wet), color = mutedText, style = MaterialTheme.typography.labelSmall)
                            Slider(
                                fx.wet, { value -> update(true) { it.copy(wet = value) } },
                                modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                                onValueChangeFinished = commit,
                                colors = SliderDefaults.colors(thumbColor = accent, activeTrackColor = accent),
                            )
                            Text("${(fx.wet * 100).toInt()}%", color = accent, fontFamily = FontFamily.Monospace)
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(stringResource(R.string.fx_oversampling), color = mutedText, style = MaterialTheme.typography.labelSmall)
                            for (factor in listOf(1, 2, 4)) {
                                FilterChip(
                                    selected = fx.oversampling == factor,
                                    onClick = { update(false) { it.copy(oversampling = factor) } },
                                    label = { Text(stringResource(R.string.fx_oversampling_factor, factor)) },
                                )
                            }
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                            TextButton(onClick = { update(false) { it.copy(values = emptyMap(), wet = 1f, oversampling = 1) } }) {
                                Text(stringResource(R.string.mixer_reset))
                            }
                            TextButton(enabled = canMoveUp, onClick = { move(-1) }) { Text(stringResource(R.string.mixer_move_up)) }
                            TextButton(enabled = canMoveDown, onClick = { move(1) }) { Text(stringResource(R.string.mixer_move_down)) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FxPicker(close: () -> Unit, add: (SnapinType) -> Unit) {
    AlertDialog(
        onDismissRequest = close,
        title = { Text(stringResource(R.string.mixer_add_effect)) },
        text = {
            LazyColumn(Modifier.heightIn(max = 440.dp)) {
                SnapinType.entries.groupBy { it.category }.forEach { (group, types) ->
                    item { Text(group.displayName, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 12.dp, bottom = 4.dp)) }
                    items(types) { type ->
                        Text(
                            type.displayName,
                            modifier = Modifier.fillMaxWidth().clickable { add(type) }.padding(vertical = 12.dp),
                        )
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = close) { Text(stringResource(android.R.string.cancel)) } },
    )
}

@Composable
private fun channelName(role: AudioRole?): String =
    stringResource(if (role == null) R.string.mixer_master else roleLabel(role))

private fun gainDb(gain: Float): Float = if (gain > 0f) 20f * log10(gain) else minimumDb

private fun faderPosition(gain: Float): Float {
    val db = gainDb(gain).coerceIn(minimumDb, maximumDb)
    return if (db < 0f) unityFader * (1f - db / minimumDb)
    else unityFader + (1f - unityFader) * db / maximumDb
}

private fun formatMixerTime(us: Long): String {
    val seconds = us / 1_000_000
    return "%d:%02d".format(seconds / 60, seconds % 60)
}
