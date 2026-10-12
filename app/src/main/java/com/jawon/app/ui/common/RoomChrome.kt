package com.jawon.app.ui.common

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jawon.app.ui.theme.JawonTextStyle
import com.jawon.app.ui.theme.LabelCream

/**
 * One room-chrome action: a real 48dp target with a quiet mono label under it.
 *
 * The CD room's, moved out of `WallSceneScreen` when the vinyl room needed the same two buttons
 * (`ENGINEERING.md`, "a new room is a sibling, never a branch": what is common moves out first). The
 * way out of a room — to the other rooms, to Settings — looks the same from every room.
 */
@Composable
internal fun ChromeAction(
    icon: ImageVector?,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    /** Drawn in place of [icon] — the ROOMS button wears the mark, which is no vector. */
    iconContent: (@Composable () -> Unit)? = null
) {
    // The whole column is the target (0.7.2): it used to be the 48dp icon alone, so a thumb landing
    // on the word under it did nothing. A shade brighter than dim, too — it sits on the room's dark.
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
            if (iconContent != null) iconContent()
            else if (icon != null) Icon(icon, contentDescription = null, tint = LabelCream.copy(alpha = 0.82f))
        }
        // One line, and grown with the phone's text size only so far (0.7.5): at Android's largest
        // the five labels broke mid-word ("SHELV / ES"). The icon above carries the meaning; past
        // 1.3x the word stays at 1.3x and ellipsises before it wraps.
        val density = LocalDensity.current
        val base = JawonTextStyle.mono.fontSize
        val capped = if (density.fontScale > CHROME_TEXT_MAX_SCALE) {
            (base.value * CHROME_TEXT_MAX_SCALE / density.fontScale).sp
        } else {
            base
        }
        Text(
            text = label,
            style = JawonTextStyle.mono.copy(color = LabelCream.copy(alpha = 0.72f), fontSize = capped),
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/** How far the bar's labels grow with the phone's text size. */
private const val CHROME_TEXT_MAX_SCALE = 1.3f
