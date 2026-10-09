package com.jawon.app.ui.common

import com.jawon.app.ui.theme.JawonType
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jawon.app.ui.theme.CrateEdge
import com.jawon.app.ui.theme.JetBrainsMonoFamily
import com.jawon.app.ui.theme.LabelDim
import com.jawon.app.ui.theme.LampAmber

/**
 * The bar across the top of every 2D screen: a way back, the screen's name in the mono label
 * face, any actions the screen needs at the right, and a hairline under it.
 *
 * **Shared, after a spell of being copied.** Each screen used to carry its own private copy, on
 * the reasoning that eleven lines were not worth an API. Seven copies later they had drifted —
 * the record shop's back was a bare "←" glyph with no touch target and its title a size up, the
 * CD screen's title had gone cream — which is the thing a shared bar exists to prevent. A
 * premium app is mostly the same decision made the same way everywhere.
 *
 * Hairline, not a shadow: everything in this room is a flat printed edge.
 */
@Composable
fun ScreenBar(
    title: String,
    onBack: () -> Unit,
    backDescription: String = "Back",
    actions: @Composable RowScope.() -> Unit = {}
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 8.dp, end = 8.dp, top = 6.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onBack) {
            Icon(
                Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = backDescription,
                tint = LabelDim
            )
        }
        // Jawon's mark beside every screen's name (0.7.2): the brand travels with the bar, so
        // there is no flat screen that could be any other app's. Decorative to TalkBack — the
        // title says where you are.
        JawonMark(
            size = 18.dp,
            contentDescription = null,
            modifier = Modifier.padding(end = 10.dp)
        )
        Text(
            text = title,
            fontFamily = JetBrainsMonoFamily,
            fontWeight = FontWeight.Bold,
            fontSize = JawonType.Title,
            letterSpacing = 3.sp,
            color = LampAmber,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        actions()
    }
    Box(
        Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(CrateEdge)
    )
}
