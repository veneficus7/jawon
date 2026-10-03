package com.jawon.app.ui.common

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jawon.app.ui.theme.ArchivoFamily
import com.jawon.app.ui.theme.CrateBlack
import com.jawon.app.ui.theme.CrateEdge
import com.jawon.app.ui.theme.CrateRaised
import com.jawon.app.ui.theme.JawonType
import com.jawon.app.ui.theme.JetBrainsMonoFamily
import com.jawon.app.ui.theme.LabelCream
import com.jawon.app.ui.theme.LabelDim
import com.jawon.app.ui.theme.LampAmber

/**
 * The small amber caption over a field or a picker.
 *
 * Amber at three-quarters at rest — the house style since Add Album was brought into it — and
 * full amber while the field under it has focus, so the label says which field the keyboard is
 * typing into without anything else on the screen moving.
 */
@Composable
fun FieldLabel(text: String, active: Boolean = false) {
    val color by animateColorAsState(
        if (active) LampAmber else LampAmber.copy(alpha = 0.75f),
        label = "fieldLabel"
    )
    Text(
        text = text,
        fontFamily = JetBrainsMonoFamily,
        fontWeight = FontWeight.Bold,
        fontSize = JawonType.Fine,
        letterSpacing = 2.sp,
        color = color
    )
}

/** Which lettering a field types in. */
enum class FieldFace {
    /** The equipment label: names, addresses, searches — anything that is not a record's sleeve. */
    MONO,

    /** The sleeve: a record's own title and artist, typed in the face the wall prints them in. */
    SLEEVE
}

/**
 * The one text field every flat screen uses.
 *
 * **Why one.** Six fields on four screens had each been set up by hand, and each was a little
 * different: a placeholder smaller than its own text, three letter-spacings, labels in two
 * colours, Material's rounded top corners on a screen of square hairline boards — and no form
 * that moved on to its next field from the keyboard. None of it was wrong enough to notice alone;
 * together it is what makes an app feel assembled rather than made.
 *
 * Square, because everything round it is a flat printed edge. The ground is always one step away
 * from what the field sits on — raised on the screen's black, sunk to black on a raised board
 * ([onRaised]) — so a field reads as a place to type wherever it is.
 *
 * Forms chain themselves: give every field but the last `ImeAction.Next` and the keyboard walks
 * down the form, which is Compose's default for Next when no action is supplied.
 *
 * @param label the caption above the field; it brightens while the field has focus.
 * @param onFocusChanged whether the field itself holds focus — for a field that commits when you
 *   tap away from it.
 */
@Composable
fun JawonTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
    placeholder: String? = null,
    face: FieldFace = FieldFace.MONO,
    onRaised: Boolean = false,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    onFocusChanged: (Boolean) -> Unit = {}
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val style = fieldStyle(face)
    val ground = if (onRaised) CrateBlack else CrateRaised

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (label != null) FieldLabel(label, active = focused)
        TextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            textStyle = style,
            placeholder = placeholder?.let { { Text(it, style = style.copy(color = LabelDim)) } },
            keyboardOptions = keyboardOptions,
            keyboardActions = keyboardActions,
            visualTransformation = visualTransformation,
            interactionSource = interaction,
            shape = RectangleShape,
            colors = TextFieldDefaults.colors(
                focusedContainerColor = ground,
                unfocusedContainerColor = ground,
                disabledContainerColor = ground,
                focusedTextColor = LabelCream,
                unfocusedTextColor = LabelCream,
                focusedIndicatorColor = LampAmber,
                unfocusedIndicatorColor = CrateEdge,
                cursorColor = LampAmber
            ),
            modifier = Modifier
                .fillMaxWidth()
                .onFocusChanged { onFocusChanged(it.isFocused) }
        )
    }
}

@Composable
private fun fieldStyle(face: FieldFace): TextStyle = when (face) {
    FieldFace.MONO -> MaterialTheme.typography.bodyLarge.copy(
        fontFamily = JetBrainsMonoFamily,
        fontWeight = FontWeight.Medium,
        fontSize = JawonType.Title,
        letterSpacing = 1.2.sp
    )
    FieldFace.SLEEVE -> MaterialTheme.typography.bodyLarge.copy(
        fontFamily = ArchivoFamily,
        fontWeight = FontWeight.ExtraBold,
        fontSize = JawonType.Subhead,
        letterSpacing = 0.5.sp
    )
}

/**
 * A record's title or its artist. Proper names, so each word starts capitalised — and no
 * autocorrect, because a record's name is exactly the kind of word a dictionary "fixes"
 * ("Baduizm" is not a typo). Next walks on to the following field.
 */
val NAME_KEYS = KeyboardOptions(
    capitalization = KeyboardCapitalization.Words,
    autoCorrectEnabled = false,
    imeAction = ImeAction.Next
)

/** A year: digits only, and the last field of the form. */
val YEAR_KEYS = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done)
