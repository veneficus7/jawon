package com.jawon.app.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jawon.app.ui.theme.CrateEdge
import com.jawon.app.ui.theme.JawonType
import com.jawon.app.ui.theme.JetBrainsMonoFamily
import com.jawon.app.ui.theme.LabelCream
import com.jawon.app.ui.theme.LabelDim

/**
 * What a screen says when it has nothing to show yet: a short rule, what is missing, and one
 * sentence on how it comes to be there.
 *
 * Per `PLAN.md` §4 an empty state should read as waiting rather than broken — so it is quiet,
 * centred and unhurried, never an error's amber. The two screens that have one used to draw it
 * two different ways, one under a rule in dim lettering near the top, the other a size larger in
 * cream in the middle of the page; this is both of them agreeing.
 *
 * Placement stays with the caller: a page that is otherwise empty passes `fillMaxSize()` and the
 * note sits in its middle; a list passes nothing and the note sits where the list would start.
 */
@Composable
fun EmptyNote(title: String, body: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically)
    ) {
        Box(
            Modifier
                .width(90.dp)
                .height(1.dp)
                .background(CrateEdge)
        )
        Text(
            text = title,
            fontFamily = JetBrainsMonoFamily,
            fontWeight = FontWeight.Bold,
            fontSize = JawonType.Title,
            letterSpacing = 2.5.sp,
            color = LabelCream,
            textAlign = TextAlign.Center
        )
        Text(
            text = body,
            fontFamily = JetBrainsMonoFamily,
            fontSize = JawonType.Body,
            lineHeight = 18.sp,
            color = LabelDim,
            textAlign = TextAlign.Center,
            // A sentence, not a paragraph: kept to a width it can be read at in one glance.
            modifier = Modifier.widthIn(max = 300.dp)
        )
    }
}
