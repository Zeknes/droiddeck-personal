package com.droiddeck.launcher.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.SdCard
import androidx.compose.material.icons.outlined.Smartphone
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.zIndex
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.layout.boundsInParent
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.positionInRoot
import com.droiddeck.launcher.stores.StoreInstallRoot
import com.droiddeck.launcher.stores.StoreLanguage
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import android.view.KeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.blur
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import kotlin.math.roundToInt
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.automirrored.filled.Login
import androidx.compose.foundation.basicMarquee
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.droiddeck.launcher.R
import com.droiddeck.launcher.session.SessionPrefs
import com.droiddeck.launcher.stores.CatalogItem
import com.droiddeck.launcher.stores.gamejolt.GameJoltBrowseState
import com.droiddeck.launcher.stores.itch.ItchBrowseState
import com.droiddeck.launcher.stores.Store
import com.droiddeck.launcher.stores.StoreTab
import com.droiddeck.launcher.stores.StoresState
import com.droiddeck.launcher.stores.arrangeStoreTabs
import com.droiddeck.launcher.stores.download.DownloadStage
import com.droiddeck.launcher.stores.download.DownloadState
import com.droiddeck.launcher.stores.formatBytes
import androidx.compose.runtime.setValue


@Composable
internal fun CountPill(count: Int) {
    val pal = LocalPalette.current
    Text(
        count.toString(), fontSize = 11.sp, fontWeight = FontWeight.Bold, color = pal.onSignal, maxLines = 1,
        modifier = Modifier.landingPop().clip(RoundedCornerShape(8.dp)).background(pal.signal).padding(horizontal = 6.dp, vertical = 1.dp),
    )
}


@Composable
internal fun SectionLabel(title: String) {
    val pal = LocalPalette.current
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth().padding(top = 6.dp, start = 2.dp)) {
        Text(headingUppercase(title), fontSize = 12.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        Box(Modifier.weight(1f).height(1.dp).background(pal.line))
    }
}

@Composable
internal fun ModeHeader(
    title: String, query: String, onQuery: (String) -> Unit, placeholder: String,
    clearSearchFocus: Boolean = false,
    lead: (@Composable () -> Unit)? = null, inlineWhenStacked: Boolean = false, trailing: @Composable () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val pal = LocalPalette.current
    var searching by remember { mutableStateOf(query.isNotEmpty()) }
    var focusOnOpen by remember { mutableStateOf(false) }
    val open by animateFloatAsState(if (searching) 1f else 0f, Motion.sp(0.75f, 420f), label = "searchOpen")
    // Closing takes the field away with focus still in it; the search button gets it once it is back on screen.
    val searchIcon = remember { FocusRequester() }
    var toIcon by remember { mutableStateOf(false) }
    val close = {
        searching = false
        focusOnOpen = false
        toIcon = true
        if (query.isNotEmpty()) onQuery("")
    }
    BackHandler(enabled = searching && !clearSearchFocus) { close() }
    LaunchedEffect(toIcon, searching, open <= 0.01f) {
        if (searching) toIcon = false
        else if (toIcon && open <= 0.01f) { focusSoon { searchIcon }; toIcon = false }
    }
    val heading: @Composable () -> Unit = {
        if (lead != null) lead()
        else Text(
            title, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = colors.onBackground, maxLines = 1,
            modifier = Modifier.padding(start = 4.dp, end = 4.dp),
        )
    }
    val searchField: @Composable (Modifier) -> Unit = { modifier ->
        BoxWithConstraints(modifier, contentAlignment = Alignment.CenterStart) {
            val closable = maxWidth >= 80.dp
            val w = androidx.compose.ui.unit.lerp(44.dp, (maxWidth - if (closable) 36.dp else 0.dp).coerceIn(44.dp, 520.dp), open)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Box(Modifier.width(w).graphicsLayer { alpha = (open * 1.6f).coerceAtMost(1f) }) {
                    AdbTextField(
                        value = query, onValueChange = onQuery, label = "", keyboardType = KeyboardType.Text, imeAction = ImeAction.Search,
                        placeholder = placeholder, compact = true, showLabel = false, focusOnShow = focusOnOpen,
                        clearFocus = clearSearchFocus || !searching,
                        onFocusChange = { focused -> if (!focused && query.isEmpty()) { searching = false; focusOnOpen = false } },
                    )
                }
                if (searching && closable) SmallIconButton(Icons.Filled.Close, stringResource(R.string.common_close), tint = colors.onSurfaceVariant, onClick = close)
            }
        }
    }
    val searchButton: @Composable () -> Unit = {
        Box(Modifier.focusRequester(searchIcon)) { SearchIcon(stringResource(R.string.stores_search_open)) { searching = true; focusOnOpen = true } }
    }
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val stacked = maxWidth < NarrowPaneWidth
        val inline = stacked && inlineWhenStacked && maxWidth >= 440.dp
        Column(Modifier.fillMaxWidth()) {
            if (inline && !(searching || open > 0.01f)) Row(
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(vertical = 4.dp),
            ) {
                heading()
                Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                    ) {
                        searchButton()
                        trailing()
                    }
                }
            } else if (stacked) {
                Box(Modifier.padding(vertical = 4.dp)) { heading() }
                Row(
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).heightIn(min = 52.dp).padding(vertical = 4.dp),
                ) {
                    searchButton()
                    trailing()
                }
                if (searching || open > 0.01f) searchField(Modifier.fillMaxWidth().padding(bottom = 6.dp))
            } else Row(
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(vertical = 4.dp),
            ) {
                heading()
                if (searching || open > 0.01f) searchField(Modifier.weight(1f))
                else Box(Modifier.weight(1f)) { searchButton() }
                trailing()
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(pal.line))
        }
    }
}


internal fun storePalette(base: Palette, store: Store?): Palette {
    val (accent, deep) = when (store) {
        Store.GOG -> Color(0xFFC25BC8) to Color(0xFF9A3FA1)
        Store.EPIC -> Color(0xFFE6E6E6) to Color(0xFFBDBDBD)
        Store.AMAZON -> Color(0xFFFF9900) to Color(0xFFE07800)
        Store.XBOX -> Color(0xFF70C850) to Color(0xFF107C10)
        Store.ITCH -> Color(0xFFFF5C5C) to Color(0xFFD93B3B)
        Store.GAMEJOLT -> Color(0xFFCCFF00) to Color(0xFFA3CC00)
        Store.BATTLENET -> Color(0xFF148EFF) to Color(0xFF0B6BD3)
        Store.UBISOFT -> Color(0xFF2E7DFF) to Color(0xFF1A5BD6)
        Store.EA -> Color(0xFFFF4747) to Color(0xFFE03030)
        Store.ROCKSTAR -> Color(0xFFFCAF17) to Color(0xFFD98E00)
        null -> return base
    }
    val on = if (accent.luminance() > 0.18f) Color(0xFF0B0B0D) else Color.White
    return Palette(
        base.id, base.label, base.detail, base.background, base.surface, base.surfaceVariant, base.line, base.line2,
        base.onBackground, base.onSurfaceVariant, primary = accent, primary2 = deep, onPrimary = on, signal = accent,
        good = base.good, error = base.error,
    )
}

internal fun flathubPalette(base: Palette): Palette = Palette(
    base.id, base.label, base.detail, base.background, base.surface, base.surfaceVariant, base.line, base.line2,
    base.onBackground, base.onSurfaceVariant, primary = FlathubTeal, primary2 = Color(0xFF2E958D), onPrimary = FlathubInk, signal = FlathubTeal,
    good = base.good, error = base.error,
)

@Composable
internal fun FlathubTheme(content: @Composable () -> Unit) {
    val base = LocalPalette.current
    val p = remember(base) { flathubPalette(base) }
    val scheme = MaterialTheme.colorScheme.copy(primary = p.primary, onPrimary = p.onPrimary)
    CompositionLocalProvider(LocalPalette provides p) { MaterialTheme(colorScheme = scheme, typography = MaterialTheme.typography, content = content) }
}

@Composable
internal fun StoreTheme(store: Store?, content: @Composable () -> Unit) {
    val base = LocalPalette.current
    val p = remember(base, store) { storePalette(base, store) }
    val scheme = MaterialTheme.colorScheme.copy(primary = p.primary, onPrimary = p.onPrimary)
    CompositionLocalProvider(LocalPalette provides p) { MaterialTheme(colorScheme = scheme, typography = MaterialTheme.typography, content = content) }
}
@Composable
internal fun SearchIcon(description: String, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val pal = LocalPalette.current
    val src = remember { MutableInteractionSource() }
    val hot = rememberHot(src)
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier.paneItem("storesearch").size(40.dp).clip(CircleShape).background(if (hot) pal.signal.copy(alpha = 0.14f) else Color.Transparent)
            .glideBorder(hot, CircleShape, pal.signal)
            .hoverable(src).clickable(interactionSource = src, indication = LocalIndication.current, role = Role.Button, onClick = onClick)
            .controllerConfirm(onClick = onClick),
    ) { Icon(Icons.Filled.Search, description, tint = if (hot) pal.signal else colors.onSurfaceVariant, modifier = Modifier.size(20.dp)) }
}

internal val CardWidth = 112.dp
internal val CapsuleWidth = 92.dp

internal fun syncCount(line: String): Pair<Int, Int>? {
    val match = Regex("(\\d+)\\s*/\\s*(\\d+)").find(line) ?: return null
    val done = match.groupValues[1].toIntOrNull() ?: return null
    val total = match.groupValues[2].toIntOrNull()?.takeIf { it > 0 } ?: return null
    return done to total
}

@Composable
internal fun SyncBar(line: String) {
    val count = syncCount(line) ?: return
    val done = count.first
    val total = count.second
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
        Box(Modifier.weight(1f)) { androidx.compose.material3.LinearProgressIndicator(progress = { (done.toFloat() / total).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth().height(3.dp)) }
        Text("$done/$total", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
    }
}

@Composable
internal fun cardArtRequest(url: Any?, cover: Any?): coil.request.ImageRequest? {
    val ctx = LocalContext.current
    val under = (cover as? String)?.takeIf { it != url }
    return remember(url, under) {
        url?.let { coil.request.ImageRequest.Builder(ctx).data(it).apply { if (under != null) placeholderMemoryCacheKey(under) }.build() }
    }
}

@Composable
internal fun ProgressBarThin(fraction: Float, paused: Boolean = false, verify: Boolean = false, height: Dp = 4.dp, done: Boolean = false) {
    val pal = LocalPalette.current
    val colors = MaterialTheme.colorScheme
    val fill = when { done -> pal.good; paused -> Color(0xFFFFC24D); verify -> Color(0xFF9B6DFF); else -> pal.signal }
    val shown = androidx.compose.animation.core.animateFloatAsState(
        fraction.coerceIn(0f, 1f), androidx.compose.animation.core.tween(250, easing = androidx.compose.animation.core.LinearEasing), label = "bar",
    )
    Box(
        Modifier.fillMaxWidth().height(height).clip(RoundedCornerShape(height / 2)).background(colors.surfaceVariant)
            .drawBehind { drawRect(fill, size = androidx.compose.ui.geometry.Size(size.width * shown.value, size.height)) },
    )
}

@Composable
internal fun downloadLabel(d: com.droiddeck.launcher.stores.download.DownloadEntry): String = when (d.state) {
    DownloadState.PAUSED -> stringResource(R.string.stores_dl_paused)
    DownloadState.QUEUED -> stringResource(R.string.stores_dl_queued)
    else -> if (d.stageFraction >= 0f) stringResource(R.string.stores_dl_active_percent, activeStageLabel(d), d.percent) else activeStageLabel(d)
}

@Composable
internal fun activeStageLabel(d: com.droiddeck.launcher.stores.download.DownloadEntry): String = when (d.stage) {
    DownloadStage.MANIFEST -> when {
        d.store == Store.BATTLENET && d.detail.isNotBlank() -> d.detail
        d.stageItemsTotal > 0 -> stringResource(R.string.stores_stage_checking)
        else -> stringResource(R.string.stores_stage_manifest)
    }
    DownloadStage.DOWNLOAD -> stringResource(R.string.stores_stage_downloading)
    DownloadStage.VERIFY -> stringResource(R.string.stores_stage_verifying)
    DownloadStage.INSTALL -> stringResource(R.string.stores_stage_installing)
    DownloadStage.DONE -> stringResource(R.string.stores_stage_done)
}

@Composable
internal fun ConfirmButton(label: String, confirm: String, compact: Boolean = false, onConfirm: () -> Unit) {
    var armed by remember { mutableStateOf(false) }
    LaunchedEffect(armed) { if (armed) { kotlinx.coroutines.delay(4000); armed = false } }
    SecondaryButton(if (armed) confirm else label, compact = compact) { if (!armed) armed = true else { armed = false; onConfirm() } }
}

internal fun openStoreUrl(ctx: android.content.Context, item: CatalogItem) = openUrl(ctx, item.storeUrl)

internal fun openUrl(ctx: android.content.Context, url: String) {
    if (url.isBlank()) return
    runCatching { ctx.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url)).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) }
}

internal fun launchStoreGame(store: Store, id: String, s: FrontEndState, a: FrontEndActions) {
    val game = s.steamGames.firstOrNull { it.source == store.id && it.storeId == id }
    if (game != null) a.onSteamGame(game) else a.onStoreGame(store, id)
}
