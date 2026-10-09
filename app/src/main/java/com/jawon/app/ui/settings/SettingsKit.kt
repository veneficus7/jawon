package com.jawon.app.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jawon.app.ui.common.READING_MAX_W
import com.jawon.app.ui.common.ScreenBar
import com.jawon.app.ui.theme.CrateBlack
import com.jawon.app.ui.theme.CrateEdge
import com.jawon.app.ui.theme.CrateRaised
import com.jawon.app.ui.theme.InterFamily
import com.jawon.app.ui.theme.JetBrainsMonoFamily
import com.jawon.app.ui.theme.LabelCream
import com.jawon.app.ui.theme.LabelDim
import com.jawon.app.ui.theme.LampAmber
import com.jawon.app.ui.theme.LocalPalette

/*
 * The pieces Settings is built from (0.7.2) — Apple's arrangement in Jawon's materials.
 *
 * Apple's guidance is a **hierarchy of lists**: options grouped under a header, one idea per row,
 * the current value at the row's end, and the rest a level down. That is the structure. The
 * materials are the room's: the card is the palette's raised surface, its corners are the mode's
 * own (`Palette.corner` — round under neon, square on the workbench), the section headers keep the
 * equipment-label mono, and values read out in it like a display. So a new user meets the layout
 * every phone has taught them, and it is still unmistakably Jawon's.
 *
 * Titles are sentence case in Inter, a face made for reading at small sizes. The all-capitals mono
 * that every row used to wear is a label, and a page made entirely of labels is what the owner
 * called hard to understand.
 */

/** A section: its header, a card of rows, and a line of footnote under it. */
@Composable
fun SettingsGroup(
    header: String?,
    footer: String? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    val corner = LocalPalette.current.corner
    Column(Modifier.fillMaxWidth().padding(top = 28.dp)) {
        if (header != null) {
            Text(
                text = header.uppercase(),
                fontFamily = JetBrainsMonoFamily,
                fontWeight = FontWeight.Bold,
                fontSize = 11.sp,
                letterSpacing = 2.sp,
                color = LampAmber.copy(alpha = 0.85f),
                modifier = Modifier.padding(start = 16.dp, bottom = 8.dp)
            )
        }
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(corner))
                .background(CrateRaised)
        ) {
            content()
        }
        if (footer != null) {
            Text(
                text = footer,
                fontFamily = InterFamily,
                fontSize = 13.sp,
                lineHeight = 18.sp,
                color = LabelDim,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp)
            )
        }
    }
}

/** The hairline between two rows, starting where the titles start. */
@Composable
fun SettingsDivider(inset: Boolean = true) {
    Box(
        Modifier
            .padding(start = if (inset) ROW_TEXT_START else 0.dp)
            .fillMaxWidth()
            .height(1.dp)
            .background(CrateEdge)
    )
}

/** The square of colour each row leads with, as iOS's Settings rows do — in the room's accent. */
@Composable
fun SettingsIcon(icon: ImageVector) {
    Box(
        Modifier
            .size(30.dp)
            .clip(RoundedCornerShape(minOf(LocalPalette.current.corner, 8.dp)))
            .background(LampAmber.copy(alpha = 0.16f)),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = null, tint = LampAmber, modifier = Modifier.size(18.dp))
    }
}

/** A row that opens a page: icon, title, the current value, and a chevron. */
@Composable
fun SettingsNavRow(
    icon: ImageVector,
    title: String,
    value: String? = null,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = ROW_MIN_HEIGHT)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        SettingsIcon(icon)
        RowTitle(title, Modifier.weight(1f))
        if (value != null) RowValue(value)
        Icon(
            Icons.AutoMirrored.Rounded.KeyboardArrowRight,
            contentDescription = null,
            tint = LabelDim.copy(alpha = 0.7f),
            modifier = Modifier.size(20.dp)
        )
    }
}

/**
 * A row with a switch. [summary] is the one line a person needs to decide; [detail], when there
 * is one, is the full text — often a promise `docs/privacy.md` makes in the same words — and opens
 * under the row on a tap of its text. Nothing is shortened away, it is only out of the way.
 */
@Composable
fun SettingsSwitchRow(
    icon: ImageVector,
    title: String,
    summary: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    detail: String? = null,
    enabled: Boolean = true
) {
    var open by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = ROW_MIN_HEIGHT)
                .padding(start = 16.dp, end = 12.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            SettingsIcon(icon)
            Column(
                Modifier
                    .weight(1f)
                    .then(if (detail != null) Modifier.clickable { open = !open } else Modifier),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                RowTitle(title, dim = !enabled)
                Text(
                    text = if (detail != null && !open) "$summary  More" else summary,
                    fontFamily = InterFamily,
                    fontSize = 13.sp,
                    lineHeight = 17.sp,
                    color = LabelDim
                )
            }
            Switch(
                checked = checked,
                onCheckedChange = onCheckedChange,
                enabled = enabled,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = CrateBlack,
                    checkedTrackColor = LampAmber,
                    uncheckedThumbColor = LabelDim,
                    uncheckedTrackColor = CrateBlack,
                    uncheckedBorderColor = CrateEdge
                )
            )
        }
        if (open && detail != null) {
            Text(
                text = detail,
                fontFamily = InterFamily,
                fontSize = 13.sp,
                lineHeight = 19.sp,
                color = LabelDim,
                modifier = Modifier.padding(start = ROW_TEXT_START, end = 20.dp, bottom = 14.dp)
            )
        }
    }
}

/** One choice of several, ticked when it is the one — iOS's checkmark list. */
@Composable
fun SettingsChoiceRow(
    title: String,
    selected: Boolean,
    summary: String? = null,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = ROW_MIN_HEIGHT)
            .clickable(role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            RowTitle(title)
            if (summary != null) {
                Text(
                    text = summary,
                    fontFamily = InterFamily,
                    fontSize = 13.sp,
                    lineHeight = 17.sp,
                    color = LabelDim
                )
            }
        }
        if (selected) {
            Icon(
                Icons.Rounded.Check,
                contentDescription = "Selected",
                tint = LampAmber,
                modifier = Modifier.size(22.dp)
            )
        }
    }
}

/** A detail page: the shared bar, and a readable column under it. */
@Composable
fun SettingsPage(
    title: String,
    onBack: () -> Unit,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        Modifier
            .fillMaxSize()
            .background(CrateBlack)
            .statusBarsPadding()
    ) {
        ScreenBar(title = title.uppercase(), onBack = onBack, backDescription = "Back to Settings")
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .widthIn(max = READING_MAX_W)
                .align(Alignment.CenterHorizontally)
                .padding(start = 16.dp, end = 16.dp, bottom = 48.dp),
            content = content
        )
    }
}

@Composable
private fun RowTitle(text: String, modifier: Modifier = Modifier, dim: Boolean = false) {
    Text(
        text = text,
        fontFamily = InterFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 16.sp,
        color = if (dim) LabelDim else LabelCream,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier
    )
}

@Composable
private fun RowValue(text: String) {
    Text(
        text = text,
        fontFamily = JetBrainsMonoFamily,
        fontSize = 13.sp,
        color = LabelDim,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.widthIn(max = 150.dp)
    )
}

private val ROW_MIN_HEIGHT = 56.dp
/** 16 padding + 30 icon + 16 gap: where a row's words begin, and where its divider starts. */
private val ROW_TEXT_START = 62.dp
