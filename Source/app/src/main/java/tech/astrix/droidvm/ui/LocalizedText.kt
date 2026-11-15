package tech.astrix.droidvm.ui

import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import tech.astrix.droidvm.i18n.tr
import androidx.compose.material3.Text as M3Text

/** Drop-in replacement for material3 Text that translates English UI strings (see I18n). */
@Composable
fun Text(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    fontSize: TextUnit = TextUnit.Unspecified,
    fontWeight: FontWeight? = null,
    fontFamily: FontFamily? = null,
    textAlign: TextAlign? = null,
    lineHeight: TextUnit = TextUnit.Unspecified,
    overflow: TextOverflow = TextOverflow.Clip,
    softWrap: Boolean = true,
    maxLines: Int = Int.MAX_VALUE,
    minLines: Int = 1,
    style: TextStyle = LocalTextStyle.current,
) = M3Text(
    text = if (fontFamily == FontFamily.Monospace) text else tr(text),
    modifier = modifier, color = color, fontSize = fontSize, fontWeight = fontWeight, fontFamily = fontFamily,
    textAlign = textAlign, lineHeight = lineHeight, overflow = overflow, softWrap = softWrap, maxLines = maxLines,
    minLines = minLines, style = style,
)

@Composable
fun LocalizedSnackbarHost(state: SnackbarHostState) = SnackbarHost(state) { d -> Snackbar { Text(d.visuals.message) } }
