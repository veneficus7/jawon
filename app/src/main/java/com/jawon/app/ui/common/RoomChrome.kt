package com.jawon.app.ui.common

import androidx.compose.material.icons.filled.Autorenew
import androidx.compose.foundation.border
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Row
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

/**
 * **The way back from a deck**: a small mono button at the top left, *‹ TAPES* or *‹ CRATE*. The
 * vinyl and cassette decks had no way out on screen but Back (2026-11-14) — a gesture nobody is
 * told about, in a room built to look like nothing but itself. Small and quiet, over the wall,
 * where it reads as a label rather than a toolbar; still a 48dp target.
 */
@Composable
internal fun BackToChip(label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(24.dp))
            .clickable(role = Role.Button, onClickLabel = "Back to the $label", onClick = onClick)
            .background(androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.38f))
            .heightIn(min = 48.dp)
            .padding(start = 10.dp, end = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = androidx.compose.material.icons.Icons.AutoMirrored.Filled.KeyboardArrowLeft,
            contentDescription = null,
            tint = LabelCream.copy(alpha = 0.86f),
            modifier = Modifier.size(24.dp)
        )
        Text(
            text = label.uppercase(),
            style = JawonTextStyle.mono.copy(color = LabelCream.copy(alpha = 0.86f)),
            maxLines = 1
        )
    }
}

/**
 * **A side is over, and the deck is waiting for a hand**: said plainly, and done by a tap. The
 * vinyl room said it only as *SIDE B · TURN OVER* in small type on the sleeve, and the cassette
 * room on the J-card, so the music stopping read as the app stopping (2026-11-14). Touching the
 * record or the tape still turns it; this is the same thing, where it can be seen.
 */
@Composable
internal fun TurnOverPrompt(label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(24.dp))
            .clickable(role = Role.Button, onClick = onClick)
            .background(com.jawon.app.ui.theme.CrateRaised.copy(alpha = 0.94f))
            .border(1.dp, com.jawon.app.ui.theme.LampAmber.copy(alpha = 0.7f), RoundedCornerShape(24.dp))
            .heightIn(min = 48.dp)
            .padding(start = 14.dp, end = 18.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = androidx.compose.material.icons.Icons.Default.Autorenew,
            contentDescription = null,
            tint = com.jawon.app.ui.theme.LampAmber,
            modifier = Modifier.size(22.dp)
        )
        androidx.compose.foundation.layout.Spacer(Modifier.size(10.dp))
        Text(
            text = label.uppercase(),
            style = JawonTextStyle.mono.copy(color = LabelCream),
            maxLines = 1
        )
    }
}
