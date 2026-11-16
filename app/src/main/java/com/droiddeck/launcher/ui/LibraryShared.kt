package com.droiddeck.launcher.ui

import android.view.KeyEvent
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.FilterAlt
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Sort
import androidx.compose.material.icons.filled.ViewList
import androidx.compose.material.icons.filled.ViewModule
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.droiddeck.launcher.R
import com.droiddeck.launcher.frontend.Library
import com.droiddeck.launcher.frontend.Soundtrack
import com.droiddeck.launcher.frontend.SoundtrackCollections
import com.droiddeck.launcher.frontend.SoundtrackIndex
import com.droiddeck.launcher.frontend.SoundtrackTracks
import com.droiddeck.launcher.playback.SoundtrackController
import com.droiddeck.launcher.session.SessionPrefs
import com.droiddeck.launcher.stores.CatalogItem
import com.droiddeck.launcher.stores.Store
import com.droiddeck.launcher.stores.StoresState
import com.droiddeck.launcher.stores.arrangeStoreTabs
import com.droiddeck.launcher.stores.download.DownloadEntry
import com.droiddeck.launcher.stores.download.DownloadStage
import com.droiddeck.launcher.stores.download.DownloadState
import com.droiddeck.launcher.stores.formatBytes
import com.droiddeck.launcher.stores.gamejolt.GameJoltCollections
import com.droiddeck.launcher.stores.gamejolt.GameJoltUpdates
import com.droiddeck.launcher.stores.itch.ItchCollections
import com.droiddeck.launcher.stores.itch.ItchUpdates
import com.droiddeck.launcher.stores.retainOrder
import com.droiddeck.launcher.stores.entryTime
import com.droiddeck.launcher.stores.sortGames
import kotlinx.coroutines.launch
import androidx.compose.runtime.setValue

internal object LibraryDisplay {
    val scales = listOf(0.75f, 0.875f, 1f, 1.2f, 1.45f)
    var level by mutableIntStateOf(2)
        private set
    var title by mutableStateOf(true)
        private set
    var size by mutableStateOf(true)
        private set

    fun load(context: android.content.Context) {
        level = SessionPrefs.libraryCardLevel(context).coerceIn(0, scales.lastIndex)
        title = SessionPrefs.libraryShowTitle(context)
        size = SessionPrefs.libraryShowSize(context)
    }

    fun step(context: android.content.Context, by: Int) {
        level = (level + by).coerceIn(0, scales.lastIndex)
        SessionPrefs.setLibraryCardLevel(context, level)
    }

    fun toggleTitle(context: android.content.Context) {
        title = !title
        SessionPrefs.setLibraryShowTitle(context, title)
    }

    fun toggleSize(context: android.content.Context) {
        size = !size
        SessionPrefs.setLibraryShowSize(context, size)
    }
}
internal val LibraryCardWidth = 150.dp

internal fun libraryTileWidth() = LibraryCardWidth * LibraryDisplay.scales[LibraryDisplay.level]

@Composable
internal fun ArtCard(
    title: String, wide: Any?, tall: Any?, download: DownloadEntry?, hot: Boolean, capsule: Boolean, chip: String?,
    badge: (@Composable BoxScope.() -> Unit)? = null, detail: @Composable () -> Unit,
) {
    val url = if (capsule) tall ?: wide else wide ?: tall
    val art = cardArtRequest(url, tall)
    val ratio = if (capsule) 5f / 7f else 16f / 9f
    var offRatio by remember(url) { mutableStateOf(false) }
    var trim by remember(url) { mutableStateOf(false) }
    val blurs = android.os.Build.VERSION.SDK_INT >= 31
    Box(
        Modifier.fillMaxWidth().aspectRatio(ratio).background(Color(0xFF101318))
            .then(if (url == null) Modifier.background(artBrush(hueOf(title))) else Modifier),
    ) {
        if (art != null && offRatio) {
            AsyncImage(model = art, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.matchParentSize().blur(32.dp))
            Spacer(Modifier.matchParentSize().background(Color.Black.copy(alpha = if (blurs) 0.18f else 0.6f)))
        }
        if (art != null) AsyncImage(
            model = art, contentDescription = null, contentScale = if (trim) ContentScale.Crop else ContentScale.Fit, modifier = Modifier.matchParentSize(),
            onSuccess = { r ->
                val d = r.result.drawable
                if (d.intrinsicWidth > 0 && d.intrinsicHeight > 0) {
                    val shape = d.intrinsicWidth.toFloat() / d.intrinsicHeight
                    trim = !capsule && shape > ratio * 1.25f
                    offRatio = !trim && kotlin.math.abs(shape / ratio - 1f) > 0.03f
                }
            },
        )
        badge?.invoke(this)
        if (chip != null) Box(Modifier.align(Alignment.TopEnd).padding(6.dp)) { StateChip(chip, ChipTone.BUSY, small = true) }
        val showName = LibraryDisplay.title || hot
        val showDetail = LibraryDisplay.size || hot
        if (showName || showDetail) Column(
            verticalArrangement = Arrangement.spacedBy(1.dp),
            modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                .background(Brush.verticalGradient(0f to Color.Transparent, 0.6f to Color.Black.copy(alpha = 0.78f), 1f to Color.Black.copy(alpha = 0.92f)))
                .padding(start = 8.dp, end = 8.dp, top = 20.dp, bottom = if (download != null) 14.dp else 6.dp),
        ) {
            if (showName) Text(title, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color.White, maxLines = if (hot) 2 else 1, overflow = TextOverflow.Ellipsis, lineHeight = 13.sp)
            if (showDetail) detail()
        }
        if (download != null) Box(Modifier.align(Alignment.BottomCenter).padding(start = 6.dp, end = 6.dp, bottom = 5.dp)) {
            ProgressBarThin(download.fraction, paused = download.state == DownloadState.PAUSED, verify = download.stage == DownloadStage.VERIFY)
        }
    }
}
@Composable
internal fun HeaderIconButton(description: String, icon: androidx.compose.ui.graphics.vector.ImageVector, key: String, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val pal = LocalPalette.current
    val src = remember { MutableInteractionSource() }
    val hot = rememberHot(src)
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier.paneItem("btn:$key").size(32.dp).clip(CircleShape).background(if (hot) pal.signal.copy(alpha = 0.14f) else Color.Transparent)
            .glideBorder(hot, CircleShape, pal.signal)
            .hoverable(src).clickable(interactionSource = src, indication = LocalIndication.current, role = Role.Button, onClick = onClick)
            .controllerConfirm(onClick = onClick)
            .semantics { contentDescription = description },
    ) { Icon(icon, null, tint = if (hot) pal.signal else colors.onSurfaceVariant, modifier = Modifier.size(18.dp)) }
}

@Composable
internal fun Modifier.paneEntry(): Modifier {
    val ff = LocalFrontFocus.current ?: return this
    androidx.compose.runtime.DisposableEffect(ff) {
        ff.primaryAttached++
        onDispose { ff.primaryAttached-- }
    }
    return focusRequester(ff.primary)
}
