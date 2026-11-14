package com.jawon.app.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jawon.app.ui.theme.CrateRaised
import com.jawon.app.ui.theme.InterFamily
import com.jawon.app.ui.theme.JetBrainsMonoFamily
import com.jawon.app.ui.theme.LabelCream
import com.jawon.app.ui.theme.LabelDim
import com.jawon.app.ui.theme.LampAmber
import com.jawon.app.ui.theme.LocalPalette

/**
 * **The first time in a room** (0.7.4): one card, two lines on what to do here, GOT IT, and GUIDE for
 * the rest (Settings → Guide). Shown once per room per phone — `AppPreferences.hintsSeen` — and
 * never over a room you already know. Before it, nothing told a new user that the disc scratches,
 * that the phone is the click wheel, or that the showroom is where the rooms are.
 */
@Composable
fun FirstRunHint(
    title: String,
    body: String,
    onGotIt: () -> Unit,
    onGuide: () -> Unit,
    modifier: Modifier = Modifier
) {
    val corner = minOf(LocalPalette.current.corner, 16.dp)
    val shape = RoundedCornerShape(corner)
    Column(
        modifier = modifier
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .widthIn(max = 460.dp)
            .fillMaxWidth()
            .clip(shape)
            .background(CrateRaised.copy(alpha = 0.97f))
            .border(1.dp, LampAmber.copy(alpha = 0.45f), shape)
            .semantics { liveRegion = LiveRegionMode.Polite }
            .padding(start = 16.dp, end = 8.dp, top = 14.dp, bottom = 4.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            JawonMark(size = 18.dp, contentDescription = null)
            Text(
                text = title,
                fontFamily = InterFamily,
                fontWeight = FontWeight.SemiBold,
                fontSize = 16.sp,
                color = LabelCream
            )
        }
        Text(
            text = body,
            fontFamily = InterFamily,
            fontSize = 14.sp,
            lineHeight = 20.sp,
            color = LabelDim,
            modifier = Modifier.padding(end = 8.dp)
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End
        ) {
            HintButton("GUIDE", LabelDim, onGuide)
            HintButton("GOT IT", LampAmber, onGotIt)
        }
    }
}

@Composable
private fun HintButton(label: String, colour: androidx.compose.ui.graphics.Color, onClick: () -> Unit) {
    Text(
        text = label,
        fontFamily = JetBrainsMonoFamily,
        fontWeight = FontWeight.Bold,
        fontSize = 13.sp,
        letterSpacing = 1.6.sp,
        color = colour,
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp)
    )
}

/** The rooms' first-run hints, by id — what `AppPreferences.hintsSeen` remembers. */
object Hints {
    const val CD = "cd"
    const val POCKET = "pocket"
    /** The vinyl room has two places, and a card for each: the crate, and the deck. */
    const val VINYL = "vinyl"
    const val VINYL_DECK = "vinyl_deck"
    /** And the cassette room's two: the racks, and the deck. */
    const val TAPE = "tape"
    const val TAPE_DECK = "tape_deck"
    const val SHOWROOM = "showroom"
}
