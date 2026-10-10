package al.thana.widgets

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/** THA'NA Phone · grafit & bronz */
object T {
    val Bg = Color(0xFF0C0D0F)
    val Surface = Color(0xFF16181B)
    val Raised = Color(0xFF1E2125)
    val Border = Color(0xFF24272B)
    val Ink = Color(0xFFF2EEE7)
    val Muted = Color(0xFF8D8A84)
    val Brass = Color(0xFFC9A46A)
    val OnBrass = Color(0xFF15120C)
    val Good = Color(0xFF6FBF95)
    val Warn = Color(0xFFD98E4A)
    val Bad = Color(0xFFE07A6B)

    // Serif për shifrat e mëdha; sans për UI; mono për tabela.
    val Display = TextStyle(fontFamily = FontFamily.Serif, fontSize = 40.sp, color = Ink)
    val Title = TextStyle(fontFamily = FontFamily.Serif, fontSize = 28.sp, color = Ink)
    val Label = TextStyle(fontSize = 11.sp, letterSpacing = 1.4.sp, fontWeight = FontWeight.SemiBold, color = Muted)
    val Body = TextStyle(fontSize = 15.sp, color = Ink)
    val Small = TextStyle(fontSize = 12.sp, color = Muted)
    val Num = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp, color = Ink)
}

@Composable
fun ThanaTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            background = T.Bg, surface = T.Surface, primary = T.Brass, onPrimary = T.OnBrass,
            onBackground = T.Ink, onSurface = T.Ink, outline = T.Border, error = T.Bad,
        ),
        content = content,
    )
}
