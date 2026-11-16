package com.droiddeck.launcher.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import com.droiddeck.launcher.R

@Composable
internal fun Wordmark(height: Dp, modifier: Modifier = Modifier, ink: Color = MaterialTheme.colorScheme.onBackground, ball: Color = LocalPalette.current.signal) {
    Text(stringResource(R.string.app_name), modifier = modifier, color = ink,
        style = MaterialTheme.typography.titleLarge, fontSize = (height.value * 0.65f).sp, maxLines = 1)
}
