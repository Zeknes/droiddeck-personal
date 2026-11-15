package com.droiddeck.launcher.ui

import androidx.compose.foundation.LocalIndication
import androidx.compose.ui.semantics.Role
import androidx.compose.runtime.CompositionLocalProvider
import com.droiddeck.launcher.stores.GameFacts
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.graphics.Shadow
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.VideogameAsset
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.droiddeck.launcher.R
import com.droiddeck.launcher.frontend.GameRemoval
import com.droiddeck.launcher.frontend.Library
import com.droiddeck.launcher.runtime.WowAddons
import com.droiddeck.launcher.session.GameSaves
import com.droiddeck.launcher.session.SessionState
import com.droiddeck.launcher.stores.CatalogItem
import com.droiddeck.launcher.stores.InstalledStoreGame
import com.droiddeck.launcher.stores.Lookup
import com.droiddeck.launcher.stores.Store
import com.droiddeck.launcher.stores.StoreGameSidecar
import com.droiddeck.launcher.stores.StoreInstallRoot
import com.droiddeck.launcher.stores.StoreLanguage
import com.droiddeck.launcher.stores.StoresState
import com.droiddeck.launcher.stores.gamejolt.GameJoltApi
import com.droiddeck.launcher.stores.battlenet.BattleNetInstall
import com.droiddeck.launcher.stores.download.DownloadQueue
import com.droiddeck.launcher.stores.download.DownloadState
import com.droiddeck.launcher.stores.formatBytes
import com.droiddeck.launcher.stores.xbox.XboxBackend
import com.droiddeck.launcher.stores.xbox.XboxCloudGames
import com.droiddeck.launcher.stores.xbox.XboxContent
import com.droiddeck.launcher.stores.xbox.xboxCloud
import com.droiddeck.launcher.stores.xbox.xboxPc
import com.droiddeck.launcher.stores.xbox.XboxRuntime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue

@Composable
internal fun SourceBadge(source: String) {
    val c = sourceColours(source)
    Row(
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp),
        modifier = Modifier.glass(CircleShape, 0.06f).padding(horizontal = 10.dp, vertical = 4.dp),
    ) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(c.dot))
        Text(sourceLabel(source), fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.8.sp, color = MaterialTheme.colorScheme.onBackground, maxLines = 1)
    }
}

@Composable
internal fun IconAction(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, id: String, danger: Boolean = false, enabled: Boolean = true, frosted: Boolean = false, tint: Color? = null, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val pill = LocalPillButtons.current
    val shape = if (pill) CircleShape else Shape12
    val pal = LocalPalette.current
    val src = remember { MutableInteractionSource() }
    val hot = rememberHot(src) && enabled
    val accent = if (danger) colors.error else pal.signal
    val fill = when {
        hot -> accent.copy(alpha = if (danger) 0.22f else 0.14f)
        pill -> Color.White.copy(alpha = 0.09f)
        danger -> Color.Black.copy(alpha = 0.34f)
        else -> Color.White.copy(alpha = 0.03f)
    }
    val button: @Composable () -> Unit = {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.paneItem(id).downToFirstTile().size(if (pill) 40.dp else 44.dp).clip(shape)
                .background(fill)
                .glideBorder(hot, shape, accent, if (pill) Color.White.copy(alpha = 0.14f) else pal.line2)
                .alpha(if (enabled) 1f else 0.45f)
                .hoverable(src).clickable(interactionSource = src, indication = LocalIndication.current, role = androidx.compose.ui.semantics.Role.Button, enabled = enabled, onClick = onClick)
                .controllerConfirm(enabled = enabled, onClick = onClick)
                .semantics { contentDescription = label },
        ) {
            Icon(icon, contentDescription = null, tint = tint ?: if (danger && hot) colors.error else if (danger && !pill) Color.White else colors.onBackground, modifier = Modifier.size(18.dp))
        }
    }
    if (frosted) Box(Modifier.glass(CircleShape)) { button() } else button()
}

@Composable
internal fun Modifier.tabPanel(): Modifier {
    if (LocalGlass.current != null) return glass(RoundedCornerShape(22.dp), 0.22f).padding(horizontal = 18.dp, vertical = 14.dp)
    val shape = RoundedCornerShape(16.dp)
    return clip(shape).background(MaterialTheme.colorScheme.surface).border(1.dp, LocalPalette.current.line, shape).padding(horizontal = 16.dp, vertical = 14.dp)
}

@Composable
internal fun FactRow(label: String, value: String, labelWidth: androidx.compose.ui.unit.Dp = 150.dp) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier.fillMaxWidth().readable().padding(horizontal = 8.dp, vertical = 7.dp),
    ) {
        Text(label, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(labelWidth))
        Text(value, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onBackground, modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}


@Composable
internal fun Modifier.readable(): Modifier {
    val pal = LocalPalette.current
    val src = remember { MutableInteractionSource() }
    val hot = rememberHot(src)
    val shape = RoundedCornerShape(8.dp)
    return clip(shape).glideBorder(hot, shape, pal.signal, restColor = Color.Transparent).focusable(interactionSource = src)
}
