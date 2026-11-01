package app.carecabin.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.carecabin.engine.care.SickStage
import app.carecabin.engine.session.AirGrade

object Palette {
    val bg = Color(0xFF0E1621)
    val card = Color(0xFF16212E)
    val cardHi = Color(0xFF1D2B3B)
    val line = Color(0xFF2A3B4F)
    val text = Color(0xFFE8EEF5)
    val sub = Color(0xFF9FB0C3)
    val accent = Color(0xFF5CC8FF)
    val good = Color(0xFF4CD58A)
    val fair = Color(0xFFF2C94C)
    val poor = Color(0xFFF2994A)
    val bad = Color(0xFFEB5757)

    fun stage(s: SickStage?) = when (s) {
        SickStage.MILD -> fair
        SickStage.CAUTION -> poor
        SickStage.REST -> bad
        else -> good
    }

    fun air(g: AirGrade) = when (g) {
        AirGrade.GOOD -> good
        AirGrade.FAIR -> fair
        AirGrade.POOR -> poor
        AirGrade.BAD -> bad
    }
}

@Composable
fun CareCabinTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Palette.accent, background = Palette.bg, surface = Palette.bg,
            onPrimary = Color(0xFF00273A), onBackground = Palette.text, onSurface = Palette.text,
        ),
    ) { Surface(color = Palette.bg, content = content) }
}

@Composable
fun Panel(modifier: Modifier = Modifier, title: String? = null, highlight: Boolean = false, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier
            .background(if (highlight) Palette.cardHi else Palette.card, RoundedCornerShape(20.dp))
            .padding(24.dp),
    ) {
        if (title != null) Text(title, color = Palette.sub, fontSize = 18.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(bottom = 10.dp))
        content()
    }
}

@Composable
fun Dot(color: Color, size: Dp = 16.dp) = Box(Modifier.size(size).background(color, CircleShape))

@Composable
fun Chip(text: String, color: Color = Palette.accent) {
    Text(
        text, color = color, fontSize = 18.sp,
        modifier = Modifier.background(color.copy(alpha = 0.14f), RoundedCornerShape(50)).padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

/** Large touch targets (≥ 76 dp, development plan 5-1). */
@Composable
fun BigButton(text: String, modifier: Modifier = Modifier, primary: Boolean = true, onClick: () -> Unit) {
    if (primary) {
        Button(onClick, modifier.heightIn(min = 76.dp), shape = RoundedCornerShape(18.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Palette.accent)) { Text(text, fontSize = 22.sp, fontWeight = FontWeight.SemiBold) }
    } else {
        OutlinedButton(onClick, modifier.heightIn(min = 76.dp), shape = RoundedCornerShape(18.dp)) { Text(text, fontSize = 22.sp, color = Palette.text) }
    }
}

/** Small trend line (no animation). [refs] draw dashed reference levels. */
@Composable
fun Sparkline(values: List<Float>, color: Color, modifier: Modifier, min: Float, max: Float, refs: List<Float> = emptyList()) {
    androidx.compose.foundation.Canvas(modifier) {
        fun y(v: Float) = size.height - ((v.coerceIn(min, max) - min) / (max - min)) * size.height
        refs.forEach { r ->
            drawLine(Palette.sub.copy(alpha = 0.6f), androidx.compose.ui.geometry.Offset(0f, y(r)), androidx.compose.ui.geometry.Offset(size.width, y(r)),
                strokeWidth = 1.5f, pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(8f, 8f)))
        }
        if (values.size < 2) return@Canvas
        val step = size.width / 35f // 36 samples = 3 minutes
        val x0 = size.width - step * (values.size - 1)
        val path = androidx.compose.ui.graphics.Path().apply {
            values.forEachIndexed { i, v -> if (i == 0) moveTo(x0, y(v)) else lineTo(x0 + i * step, y(v)) }
        }
        drawPath(path, color, style = androidx.compose.ui.graphics.drawscope.Stroke(width = 4f))
    }
}

@Composable
fun Disclaimer(text: String = "추정 지표이며 실측값이 아닙니다 · 의료적 판단이나 효능을 나타내지 않습니다") {
    Text(text, color = Palette.sub, fontSize = 14.sp, modifier = Modifier.fillMaxWidth())
}
