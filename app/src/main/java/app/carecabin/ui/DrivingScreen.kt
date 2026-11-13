package app.carecabin.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.carecabin.CareController
import app.carecabin.UiState
import app.carecabin.engine.care.AirStage
import app.carecabin.engine.care.Question
import app.carecabin.engine.care.SickStage
import app.carecabin.engine.model.Seat
import app.carecabin.engine.model.VoiceIntent
import kotlin.math.roundToInt

/**
 * Full dashboard — three cards and two buttons (원래대로 / 말로 하기). Shown only when the user asks for it
 * (split or second screen); the default while driving is the side toolbar in CompanionScreen.kt.
 * No settings, profiles or long text while driving (Korean driving-restriction policy).
 * State is never colour-only: every coloured dot or number also carries a stage word.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun DashboardScreen(s: UiState, c: CareController, canShrink: Boolean) {
    var keywords by remember { mutableStateOf(false) }
    val stopped = s.speedKmh < 1.0
    Column(Modifier.fillMaxSize().padding(28.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("케어캐빈", fontSize = 30.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.padding(8.dp))
            Text(if (s.paused) "자동 케어 쉬는 중 (10분)" else "주행 중 자동 케어", color = if (s.paused) Palette.fair else Palette.good, fontSize = 20.sp)
            Spacer(Modifier.weight(1f))
            s.cueNote?.let { Chip("시연 안내 · $it", Palette.sub) }
            if (canShrink) { Spacer(Modifier.padding(6.dp)); BigButton("내비와 함께 보기", primary = false) { c.setDashboard(false) } }
        }
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
            SicknessCard(s, Modifier.weight(1f).fillMaxHeight())
            AirCard(s, Modifier.weight(1f).fillMaxHeight())
            CareCard(s, Modifier.weight(1.15f).fillMaxHeight())
        }
        if (s.question == Question.REST) {
            Row(
                Modifier.fillMaxWidth().background(Palette.cardHi, RoundedCornerShape(18.dp)).border(2.dp, Palette.accent, RoundedCornerShape(18.dp)).padding(18.dp)
                    .semantics { liveRegion = LiveRegionMode.Polite },
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text("다음 휴게소에서 쉬어갈까요?", fontSize = 26.sp, modifier = Modifier.weight(1f))
                BigButton("응, 쉬어 갈게") { c.voice(VoiceIntent.YES) }
                BigButton("아니", primary = false) { c.voice(VoiceIntent.NO) }
            }
        }
        if (keywords) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("음성 대신 누르기", color = Palette.sub, fontSize = 16.sp)
                listOf("멀미 나" to VoiceIntent.SICK, "답답해" to VoiceIntent.STUFFY, "응" to VoiceIntent.YES, "아니" to VoiceIntent.NO).forEach { (t, v) ->
                    BigButton(t, primary = false) { c.voice(v); keywords = false }
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(18.dp), verticalAlignment = Alignment.CenterVertically) {
            BigButton("↺ 원래대로", Modifier.weight(1f), primary = false) { c.voice(VoiceIntent.RESTORE) }
            BigButton(if (s.listening) "듣는 중…" else "🎤 말로 하기", Modifier.weight(1f)) { if (!c.listen()) keywords = !keywords }
            Column(Modifier.weight(1f)) {
                if (stopped && s.running) {
                    BigButton("시연 종료", Modifier.fillMaxWidth(), primary = false) { c.stop() }
                } else {
                    Text("${s.speedKmh.roundToInt()} km/h · ${gearLabel(s.gear.name)} · ${fmt(s.tSec)} / ${fmt(s.durationSec)} · ${s.speed}×", fontSize = 16.sp, color = Palette.sub)
                    Text("위치·내비: 시나리오 재생 / 차량: ${s.vehicleSource.label}", fontSize = 14.sp, color = Palette.sub)
                    Disclaimer("추정 지표 · 의료 효능 아님")
                }
            }
        }
    }
}

@Composable
private fun SicknessCard(s: UiState, modifier: Modifier) {
    val msi = s.targetSeat?.let { s.seatMsi[it] } ?: s.seatMsi.filterKeys { !it.isDriver }.values.maxOrNull() ?: 0.0
    Panel(modifier, title = "속 케어 · 멀미지수(추정)", highlight = s.stage != SickStage.OBSERVE) {
        Row(verticalAlignment = Alignment.Bottom, modifier = Modifier.semantics(mergeDescendants = true) {
            contentDescription = "멀미지수 ${msi.roundToInt()}, ${s.stage.label}"
        }) {
            Text("${msi.roundToInt()}", fontSize = 80.sp, fontWeight = FontWeight.Bold, color = Palette.stage(s.stage))
            Spacer(Modifier.padding(6.dp))
            Text(s.stage.label, fontSize = 30.sp, color = Palette.stage(s.stage), modifier = Modifier.padding(bottom = 16.dp))
        }
        Bar(msi / 100.0, Palette.stage(s.stage))
        Spacer(Modifier.padding(4.dp))
        Text(s.targetSeat?.let { "${it.label} 집중 케어" } ?: "모든 좌석 살펴보는 중", fontSize = 20.sp, color = Palette.sub)
        Spacer(Modifier.padding(6.dp))
        SeatMap(s, Modifier.fillMaxWidth().weight(1f))
        Spacer(Modifier.padding(4.dp))
        Text("최근 3분 · 점선 50(주의)", color = Palette.sub, fontSize = 14.sp)
        Sparkline(s.msiHistory, Palette.stage(s.stage), Modifier.fillMaxWidth().height(44.dp), min = 0f, max = 100f, refs = listOf(50f))
    }
}

/** Top view of the cabin: four seats with index and stage word. */
@Composable
private fun SeatMap(s: UiState, modifier: Modifier) {
    Column(
        modifier.background(Palette.bg.copy(alpha = 0.55f), RoundedCornerShape(topStart = 60.dp, topEnd = 60.dp, bottomStart = 28.dp, bottomEnd = 28.dp))
            .border(2.dp, Palette.line, RoundedCornerShape(topStart = 60.dp, topEnd = 60.dp, bottomStart = 28.dp, bottomEnd = 28.dp))
            .padding(horizontal = 18.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("앞", color = Palette.sub, fontSize = 13.sp, modifier = Modifier.align(Alignment.CenterHorizontally))
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(12.dp)) { SeatTile(Seat.DRIVER, s, Modifier.weight(1f)); SeatTile(Seat.PASSENGER, s, Modifier.weight(1f)) }
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(12.dp)) { SeatTile(Seat.REAR_LEFT, s, Modifier.weight(1f)); SeatTile(Seat.REAR_RIGHT, s, Modifier.weight(1f)) }
    }
}

@Composable
private fun SeatTile(seat: Seat, s: UiState, modifier: Modifier) {
    val occupied = seat in s.occupied
    val stage = s.seatStages[seat]
    val msi = s.seatMsi[seat]
    val color = if (!occupied) Palette.line else Palette.stage(stage)
    val focus = s.targetSeat == seat
    // A curve ahead lifts every passenger to MILD before the index rises: call that "커브 대비", not "완화".
    val word = if (stage == SickStage.MILD && (msi ?: 0.0) < 30) "커브 대비" else (stage ?: SickStage.OBSERVE).label
    val desc = when {
        !occupied -> "${seat.label} 빈 좌석"
        msi == null -> "${seat.label} 케어 끔"
        else -> "${seat.label} 멀미지수 ${msi.roundToInt()} $word"
    }
    Column(
        modifier.fillMaxHeight()
            .background(if (focus) color.copy(alpha = 0.18f) else Palette.card, RoundedCornerShape(14.dp))
            .border(if (focus) 3.dp else 1.dp, color, RoundedCornerShape(14.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .semantics(mergeDescendants = true) { contentDescription = desc },
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(shortSeat(seat), fontSize = 17.sp, color = Palette.sub, maxLines = 1)
        if (!occupied) {
            Text("빈 좌석", fontSize = 18.sp, color = Palette.sub)
        } else if (msi == null) {
            Text("케어 끔", fontSize = 18.sp, color = Palette.sub)
        } else {
            Text("${msi.roundToInt()}", fontSize = 40.sp, fontWeight = FontWeight.Bold, color = color)
            Text(word, fontSize = 18.sp, color = color)
        }
    }
}

@Composable
private fun AirCard(s: UiState, modifier: Modifier) {
    Panel(modifier, title = "숨 케어 · 실내 CO₂(추정)", highlight = s.airStage != AirStage.OBSERVE) {
        Row(verticalAlignment = Alignment.Bottom, modifier = Modifier.semantics(mergeDescendants = true) {
            contentDescription = "실내 이산화탄소 추정 ${s.co2.roundToInt()} ppm, ${s.airGrade.label}"
        }) {
            Text("${s.co2.roundToInt()}", fontSize = 80.sp, fontWeight = FontWeight.Bold, color = Palette.air(s.airGrade))
            Spacer(Modifier.padding(6.dp))
            Text("ppm · ${s.airGrade.label}", fontSize = 26.sp, color = Palette.air(s.airGrade), modifier = Modifier.padding(bottom = 18.dp))
        }
        Bar((s.co2 - 400) / 1600.0, Palette.air(s.airGrade))
        Spacer(Modifier.padding(4.dp))
        Text("탑승 ${s.occupied.size}명 · ${s.airStage.label}", fontSize = 20.sp, color = Palette.sub)
        Spacer(Modifier.padding(8.dp))
        Column(
            Modifier.fillMaxWidth().background(Palette.bg.copy(alpha = 0.55f), RoundedCornerShape(16.dp)).padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text("앞으로", color = Palette.sub, fontSize = 15.sp)
            Text(airForecast(s), fontSize = 23.sp, fontWeight = FontWeight.SemiBold)
        }
        Spacer(Modifier.padding(8.dp))
        Text("최근 3분 · 점선 1,000ppm", color = Palette.sub, fontSize = 14.sp)
        Sparkline(s.co2History, Palette.air(s.airGrade), Modifier.fillMaxWidth().weight(1f).padding(vertical = 8.dp), min = 400f, max = 2000f, refs = listOf(1000f))
        Spacer(Modifier.padding(4.dp))
        Text("센서 없이 탑승 인원·내외기·풍량·창문으로 계산", fontSize = 14.sp, color = Palette.sub)
    }
}

private fun airForecast(s: UiState): String = when {
    s.freshRemainingSec > 0 -> "바깥 공기 넣는 중 · ${fmt(s.freshRemainingSec)} 남음"
    s.airStage == AirStage.FIXED -> "외기 유지 · 1,000ppm 아래로 내려가면 원래대로"
    s.co2 >= 1000 -> "곧 환기해요"
    s.secondsTo1000.isFinite() && s.secondsTo1000 < 3600 -> "약 ${(s.secondsTo1000 / 60).roundToInt().coerceAtLeast(1)}분 뒤 탁해질 예정 · 그 전에 환기"
    else -> "지금 설정이면 탁해지지 않아요"
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CareCard(s: UiState, modifier: Modifier) {
    Panel(modifier, title = "지금 하고 있는 케어") {
        if (s.activeCare.isEmpty()) Text(if (s.paused) "쉬는 중이에요 · 공기가 많이 탁해지면 다시 움직여요" else "살펴보는 중이에요", fontSize = 21.sp, color = Palette.sub)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            s.activeCare.forEach { Chip(it) }
        }
        Spacer(Modifier.padding(10.dp))
        Text("미리 준비하는 중", color = Palette.sub, fontSize = 17.sp)
        Text(
            when {
                s.curveAlert && s.nearestTurnM != null && s.nearestTurnM < 300 -> "커브 구간 주행 중"
                s.curveAlert && s.nearestTurnM != null -> "%.1fkm 뒤 커브 구간".format(s.nearestTurnM / 1000)
                s.restAdded != null -> "${s.restAdded.name} 경유 중"
                else -> "앞길에 큰 커브 없음"
            },
            fontSize = 24.sp, fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.weight(1f))
        s.recentSpeech?.let {
            Row(
                Modifier.fillMaxWidth().background(Palette.accent.copy(alpha = 0.12f), RoundedCornerShape(14.dp)).padding(16.dp)
                    .semantics { liveRegion = LiveRegionMode.Polite },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("🔊", fontSize = 20.sp, modifier = Modifier.semantics { contentDescription = "음성 안내" })
                Spacer(Modifier.padding(4.dp))
                Text(it, fontSize = 20.sp, color = Palette.accent, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun Bar(fraction: Double, color: androidx.compose.ui.graphics.Color) {
    Box(Modifier.fillMaxWidth().height(14.dp).background(Palette.line, RoundedCornerShape(7.dp))) {
        Box(Modifier.fillMaxWidth(fraction.coerceIn(0.0, 1.0).toFloat()).height(14.dp).background(color, RoundedCornerShape(7.dp)))
    }
}

private fun shortSeat(s: Seat) = when (s) { Seat.DRIVER -> "운전석"; Seat.PASSENGER -> "동승석"; Seat.REAR_LEFT -> "뒷좌석 왼쪽"; Seat.REAR_RIGHT -> "뒷좌석 오른쪽" }

private fun gearLabel(g: String) = when (g) { "PARK" -> "P"; "DRIVE" -> "D"; "REVERSE" -> "R"; "NEUTRAL" -> "N"; else -> "-" }

internal fun fmt(sec: Int) = "%d:%02d".format(sec / 60, sec % 60)
