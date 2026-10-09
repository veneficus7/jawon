package com.jawon.app.navigation

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jawon.app.Format
import com.jawon.app.ui.common.JawonMark
import com.jawon.app.ui.theme.CrateEdge
import com.jawon.app.ui.theme.CrateRaised
import com.jawon.app.ui.theme.InterFamily
import com.jawon.app.ui.theme.JetBrainsMonoFamily
import com.jawon.app.ui.theme.LabelCream
import com.jawon.app.ui.theme.LabelDim
import com.jawon.app.ui.theme.LampAmber
import com.jawon.app.ui.theme.LocalPalette

/** What the switcher offers, in the showroom's order: every room the app is going to have. */
internal enum class RoomChoice(val format: Format?, val title: String) {
    CD(Format.CD, "CD"),
    POCKET(Format.POCKET, "Pocket"),
    VINYL(null, "Vinyl"),
    CASSETTE(null, "Cassette")
}

/**
 * **The rooms, under your thumb** (0.7.2) — opened from ROOMS in the CD room's bar or the pocket
 * player's own Rooms row. The showroom is the walk between rooms; this is the door beside you: a
 * card at the foot of the screen with every room on it, the one you are in marked, the ones not
 * built yet shown as coming. A tap goes, and since every room is kept once built (the stage), it
 * goes at once — fast enough to tap back and forth for the pleasure of it, which the owner said
 * people would.
 *
 * Over a scrim that a tap dismisses, and Back closes it before anything else.
 */
@Composable
internal fun RoomSwitcher(
    current: Format,
    offered: List<Format>,
    shakeOn: Boolean,
    onPick: (Format) -> Unit,
    onDismiss: () -> Unit
) {
    BackHandler(onBack = onDismiss)
    val corner = LocalPalette.current.corner
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.45f))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onDismiss
            )
    ) {
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(16.dp)
                .widthIn(max = 460.dp)
                .fillMaxWidth()
                .clip(RoundedCornerShape(corner))
                .background(CrateRaised)
                // Taps on the card stay on the card.
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = {}
                )
                .padding(horizontal = 16.dp, vertical = 18.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                JawonMark(size = 20.dp, contentDescription = null)
                Text(
                    text = "ROOMS",
                    fontFamily = JetBrainsMonoFamily,
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp,
                    letterSpacing = 3.sp,
                    color = LampAmber
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                RoomChoice.entries.forEach { choice ->
                    val format = choice.format
                    val open = format != null && format in offered
                    val here = format == current
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(minOf(corner, 12.dp)))
                            .border(
                                width = if (here) 2.dp else 1.dp,
                                color = if (here) LampAmber else CrateEdge,
                                shape = RoundedCornerShape(minOf(corner, 12.dp))
                            )
                            .clickable(enabled = open && !here, role = Role.Button) {
                                format?.let(onPick)
                            }
                            .padding(vertical = 14.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        val ink = when {
                            here -> LampAmber
                            open -> LabelCream
                            else -> LabelDim.copy(alpha = 0.5f)
                        }
                        Canvas(Modifier.size(40.dp)) { drawRoomGlyph(choice, ink) }
                        Text(
                            text = choice.title,
                            fontFamily = InterFamily,
                            fontWeight = FontWeight.Medium,
                            fontSize = 14.sp,
                            color = if (open || here) LabelCream else LabelDim,
                            textAlign = TextAlign.Center
                        )
                        Text(
                            text = when {
                                here -> "HERE"
                                open -> " "
                                else -> "COMING"
                            },
                            fontFamily = JetBrainsMonoFamily,
                            fontSize = 9.sp,
                            letterSpacing = 1.5.sp,
                            color = if (here) LampAmber else LabelDim
                        )
                    }
                }
            }
            if (shakeOn) {
                Text(
                    text = "Or shake the phone to flip to the next room.",
                    fontFamily = InterFamily,
                    fontSize = 13.sp,
                    color = LabelDim,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

/** Each room's object, drawn as a line: the disc, the player, the record, the tape. */
private fun DrawScope.drawRoomGlyph(choice: RoomChoice, ink: Color) {
    val s = size.minDimension
    val c = Offset(size.width / 2f, size.height / 2f)
    val line = Stroke(width = s * 0.06f)
    when (choice) {
        RoomChoice.CD -> {
            drawCircle(ink, radius = s * 0.44f, center = c, style = line)
            drawCircle(ink, radius = s * 0.17f, center = c, style = line)
            drawCircle(ink, radius = s * 0.05f, center = c)
        }
        RoomChoice.POCKET -> {
            val w = s * 0.56f
            val h = s * 0.86f
            drawRoundRect(
                ink, topLeft = Offset(c.x - w / 2, c.y - h / 2), size = Size(w, h),
                cornerRadius = CornerRadius(s * 0.08f), style = line
            )
            drawRoundRect(
                ink, topLeft = Offset(c.x - w * 0.36f, c.y - h * 0.40f), size = Size(w * 0.72f, h * 0.30f),
                cornerRadius = CornerRadius(s * 0.03f), style = line
            )
            drawCircle(ink, radius = w * 0.26f, center = Offset(c.x, c.y + h * 0.20f), style = line)
            drawCircle(ink, radius = w * 0.08f, center = Offset(c.x, c.y + h * 0.20f))
        }
        RoomChoice.VINYL -> {
            drawCircle(ink, radius = s * 0.46f, center = c, style = line)
            drawCircle(ink, radius = s * 0.34f, center = c, style = Stroke(width = s * 0.02f))
            drawCircle(ink, radius = s * 0.26f, center = c, style = Stroke(width = s * 0.02f))
            drawCircle(ink, radius = s * 0.13f, center = c)
        }
        RoomChoice.CASSETTE -> {
            val w = s * 0.92f
            val h = s * 0.60f
            drawRoundRect(
                ink, topLeft = Offset(c.x - w / 2, c.y - h / 2), size = Size(w, h),
                cornerRadius = CornerRadius(s * 0.06f), style = line
            )
            drawCircle(ink, radius = s * 0.09f, center = Offset(c.x - w * 0.22f, c.y - h * 0.04f), style = line)
            drawCircle(ink, radius = s * 0.09f, center = Offset(c.x + w * 0.22f, c.y - h * 0.04f), style = line)
            drawLine(
                ink, Offset(c.x - w * 0.30f, c.y + h * 0.30f), Offset(c.x + w * 0.30f, c.y + h * 0.30f),
                strokeWidth = s * 0.05f
            )
        }
    }
}
