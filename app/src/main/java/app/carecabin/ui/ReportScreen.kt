package app.carecabin.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.carecabin.CareController
import app.carecabin.UiState
import app.carecabin.engine.model.Seat
import app.carecabin.engine.session.TripSample
import kotlin.math.roundToInt

/** Screen 3 — after the trip (parked): summary card + CO₂ curve vs "recirculation only". */
@Composable
fun ReportScreen(s: UiState, c: CareController) {
    val r = s.report ?: return
    Column(Modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("공간 리포트", fontSize = 34.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.padding(8.dp))
            Text("${(r.durationSec / 60).roundToInt()}분 · 탑승 ${r.occupantCount}명 · 휴식 ${r.restCount}회", color = Palette.sub, fontSize = 20.sp)
            Spacer(Modifier.weight(1f))
            BigButton("같은 설정으로 다시", primary = false) { c.start() }
            Spacer(Modifier.padding(6.dp))
            BigButton("처음으로") { c.backToPreset() }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
            Metric("멀미 위험 구간", "${r.sickZoneCount}곳", "멀미지수 50 이상", Modifier.weight(1f))
            Metric("공기 '좋음' 유지율", "${(r.goodAirRatio * 100).roundToInt()}%", "추정 CO₂ 1,000ppm 미만", Modifier.weight(1f))
            Metric("자동 케어", "${r.autoCareCount}회", "공조·시트·창문 조정", Modifier.weight(1f))
            Metric("원래대로 요청", "${r.restoreCount}회", "말 한마디로 복원", Modifier.weight(1f))
        }
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
            Panel(Modifier.weight(2f).fillMaxHeight(), title = "실내 공기 (추정) — 케어캐빈 vs 내기순환만 유지했다면") {
                Co2Chart(r.samples, Modifier.fillMaxWidth().weight(1f))
                Row(horizontalArrangement = Arrangement.spacedBy(20.dp), verticalAlignment = Alignment.CenterVertically) {
                    Dot(Palette.accent); Text("케어캐빈", fontSize = 16.sp)
                    Dot(Palette.poor); Text("내기순환만(반사실)", fontSize = 16.sp)
                    Dot(Palette.sub, 10.dp); Text("ppm 기준선 (3,000 이상은 위쪽에서 잘림)", fontSize = 16.sp, color = Palette.sub)
                }
            }
            Panel(Modifier.weight(1f).fillMaxHeight(), title = "좌석별 최고 멀미지수") {
                Seat.entries.forEach { seat ->
                    val v = r.maxMsiBySeat[seat]
                    val occ = s.preset.occupants[seat]
                    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Dot(if (v == null) Palette.line else Palette.stage(stageOf(v)))
                        Spacer(Modifier.padding(6.dp))
                        Column(Modifier.weight(1f)) {
                            Text(seat.label, fontSize = 20.sp)
                            Text(
                                when { occ == null && !seat.isDriver -> "빈 좌석"; v == null -> "케어 끔"; else -> listOfNotNull(occ?.alias?.ifBlank { null }, occ?.sensitivity?.label).joinToString(" · ").ifEmpty { "보통" } },
                                fontSize = 14.sp, color = Palette.sub,
                            )
                        }
                        Text(v?.let { "${it.roundToInt()} ${stageOf(it).label}" } ?: "-", fontSize = 22.sp, fontWeight = FontWeight.SemiBold, color = if (v == null) Palette.sub else Palette.stage(stageOf(v)))
                    }
                }
                Spacer(Modifier.weight(1f))
                Text("관찰된 경향이며 인과 효과를 뜻하지 않습니다.", color = Palette.sub, fontSize = 15.sp)
            }
        }
        Disclaimer()
    }
}

private fun stageOf(v: Double) = when {
    v > 70 -> app.carecabin.engine.care.SickStage.REST
    v >= 50 -> app.carecabin.engine.care.SickStage.CAUTION
    v >= 30 -> app.carecabin.engine.care.SickStage.MILD
    else -> app.carecabin.engine.care.SickStage.OBSERVE
}

@Composable
private fun Metric(title: String, value: String, sub: String, modifier: Modifier) {
    Panel(modifier, title = title) {
        Text(value, fontSize = 44.sp, fontWeight = FontWeight.Bold)
        Text(sub, fontSize = 15.sp, color = Palette.sub)
    }
}

@Composable
private fun Co2Chart(samples: List<TripSample>, modifier: Modifier) {
    Canvas(modifier.padding(vertical = 12.dp)) {
        if (samples.size < 2) return@Canvas
        val maxT = samples.last().tSec.coerceAtLeast(1.0)
        // Cap at 3,000 ppm so the care curve stays readable; the counterfactual is clipped at the top.
        val maxY = 3000.0
        val minY = 400.0
        val left = 90f
        fun x(t: Double) = (left + t / maxT * (size.width - left)).toFloat()
        fun y(v: Double) = (size.height - (v.coerceIn(minY, maxY) - minY) / (maxY - minY) * size.height).toFloat()
        val label = android.graphics.Paint().apply { color = android.graphics.Color.rgb(159, 176, 195); textSize = 22f; isAntiAlias = true }
        listOf(1000.0, 2000.0, 3000.0).forEach { ref ->
            drawLine(Palette.sub, Offset(left, y(ref)), Offset(size.width, y(ref)), strokeWidth = 2f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 10f)))
            drawContext.canvas.nativeCanvas.drawText("%,d".format(ref.toInt()), 0f, y(ref) + 8f, label)
        }
        fun line(sel: (TripSample) -> Double) = Path().apply {
            samples.forEachIndexed { i, s -> if (i == 0) moveTo(x(s.tSec), y(sel(s))) else lineTo(x(s.tSec), y(sel(s))) }
        }
        drawPath(line { it.co2RecircOnly }, Palette.poor, style = Stroke(width = 4f))
        drawPath(line { it.co2 }, Palette.accent, style = Stroke(width = 6f))
    }
}
