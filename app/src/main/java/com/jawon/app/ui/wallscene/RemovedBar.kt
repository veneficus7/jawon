package com.jawon.app.ui.wallscene

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jawon.app.ui.theme.CrateEdge
import com.jawon.app.ui.theme.CrateRaised
import com.jawon.app.ui.theme.InterFamily
import com.jawon.app.ui.theme.JetBrainsMonoFamily
import com.jawon.app.ui.theme.LabelCream
import com.jawon.app.ui.theme.LampAmber
import com.jawon.app.ui.theme.LocalPalette

/**
 * **"Removed. UNDO"** (0.7.4) — under the wall for as long as a record just taken off it can still
 * come back ([WallSceneViewModel.removed]). The same card as the now-playing bar, so the two read
 * as one family; a hairline along its foot drains away with the window, so the time it gives you
 * is something you can see rather than a surprise when it goes.
 */
@Composable
internal fun RemovedBar(
    removed: WallSceneViewModel.Removed,
    windowMs: Long,
    onUndo: () -> Unit,
    modifier: Modifier = Modifier
) {
    val left = remember(removed.albumId) { Animatable(1f) }
    LaunchedEffect(removed.albumId) {
        left.animateTo(0f, tween(windowMs.toInt(), easing = LinearEasing))
    }
    // Read in composition, not in the draw lambda below, which is not composable.
    val rail = LampAmber.copy(alpha = 0.7f)
    val corner = minOf(LocalPalette.current.corner, 16.dp)
    val shape = RoundedCornerShape(corner)
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(shape)
                .background(CrateRaised.copy(alpha = 0.96f))
                .border(1.dp, CrateEdge, shape)
                .drawWithContent {
                    drawContent()
                    val h = 2.dp.toPx()
                    drawRect(rail, Offset(0f, size.height - h), Size(size.width * left.value, h))
                }
                .semantics { liveRegion = LiveRegionMode.Polite }
                .padding(start = 16.dp, end = 6.dp, top = 6.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = if (removed.title.isBlank()) "Removed from your shelves" else "Removed “${removed.title}”",
                fontFamily = InterFamily,
                fontWeight = FontWeight.Medium,
                fontSize = 14.sp,
                color = LabelCream,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = "UNDO",
                fontFamily = JetBrainsMonoFamily,
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp,
                letterSpacing = 1.6.sp,
                color = LampAmber,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(onClick = onUndo)
                    .padding(horizontal = 14.dp, vertical = 12.dp)
            )
        }
    }
}
