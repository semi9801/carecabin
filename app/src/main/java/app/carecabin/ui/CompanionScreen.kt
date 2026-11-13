package app.carecabin.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.carecabin.CareController
import app.carecabin.NoticeKind
import app.carecabin.UiState
import app.carecabin.engine.care.Question
import app.carecabin.engine.care.SickStage
import app.carecabin.engine.model.Seat
import app.carecabin.engine.model.VoiceIntent
import kotlin.math.roundToInt

/** A window narrower than this is treated as a split-screen pane: the toolbar fills it and no backdrop is drawn. */
private val NARROW_WINDOW = 800.dp
private const val TOOLBAR_FRACTION = 0.27f
private val TOOLBAR_MIN = 440.dp

/**
 * Screen 2 — while driving. CareCabin is a driving aid, not something to watch: the navigation keeps the
 * screen and a toolbar comes in on the right only when care was applied, the sickness index is high, or a
 * question needs an answer. Care notices close themselves after 8 seconds.
 *
 * - wide window: navigation area + toolbar (or an edge tab when there is nothing to say)
 * - narrow window (split-screen pane): the toolbar alone
 * - dashboard asked for by the user: the full three-card view
 *
 * The navigation area here is a drawn placeholder. Showing the toolbar over another navigation app on a real
 * vehicle needs split-screen or an overlay window, which has not been verified on Pleos Connect.
 */
@Composable
fun DrivingScreen(s: UiState, c: CareController) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val narrow = maxWidth < NARROW_WINDOW
        val toolbarWidth = (maxWidth * TOOLBAR_FRACTION).coerceAtLeast(TOOLBAR_MIN)
        when {
            narrow -> Toolbar(s, c, Modifier.fillMaxSize(), standalone = true)
            s.dashboard -> DashboardScreen(s, c, canShrink = true)
            else -> Row(Modifier.fillMaxSize()) {
                Box(Modifier.weight(1f).fillMaxHeight()) {
                    NaviPlaceholder(s, Modifier.fillMaxSize())
                    if (!s.toolbarVisible) EdgeTab(s, Modifier.align(Alignment.CenterEnd)) { c.openToolbar() }
                }
                if (s.toolbarVisible) {
                    Box(Modifier.width(1.dp).fillMaxHeight().background(Palette.line))
                    Toolbar(s, c, Modifier.width(toolbarWidth).fillMaxHeight(), standalone = false)
                }
            }
        }
    }
}

/** What stays on screen when there is nothing to say: one dot and two words. */
@Composable
private fun EdgeTab(s: UiState, modifier: Modifier, onOpen: () -> Unit) {
    val color = if (s.paused) Palette.fair else Palette.good
    val word = if (s.paused) "쉬는 중" else "케어 중"
    Column(
        modifier
            .background(Palette.card, RoundedCornerShape(topStart = 18.dp, bottomStart = 18.dp))
            .border(1.dp, Palette.line, RoundedCornerShape(topStart = 18.dp, bottomStart = 18.dp))
            .clickable(onClickLabel = "케어캐빈 열기", role = Role.Button, onClick = onOpen)
            .defaultMinSize(minWidth = 76.dp, minHeight = 96.dp)
            .padding(horizontal = 14.dp, vertical = 16.dp)
            .semantics(mergeDescendants = true) { contentDescription = "케어캐빈 $word, 눌러서 열기" },
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
    ) {
        Dot(color)
        Text(word, fontSize = 16.sp, color = Palette.sub)
    }
}

@Composable
private fun Toolbar(s: UiState, c: CareController, modifier: Modifier, standalone: Boolean) {
    val sick = s.question == Question.REST || s.notice?.kind == NoticeKind.SICK
    val headColor = when {
        sick -> Palette.stage(s.stage)
        s.notice != null -> Palette.fair
        s.paused -> Palette.fair
        else -> Palette.good
    }
    Column(modifier.background(Palette.card).padding(24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Dot(headColor)
            Spacer(Modifier.padding(5.dp))
            Text("케어캐빈", fontSize = 24.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            Text(
                when {
                    sick -> s.stage.label
                    s.notice != null && !s.toolbarOpen -> "8초 뒤 닫힘"
                    s.paused -> "자동 케어 쉬는 중"
                    else -> "주행 중 자동 케어"
                },
                fontSize = 16.sp, color = Palette.sub,
            )
        }
        when {
            sick -> SickPanel(s, c)
            s.notice != null -> NoticePanel(s, c)
            else -> StatusPanel(s, c, standalone)
        }
        Disclaimer("추정 지표 · 의료 효능 아님")
    }
}

/** High sickness index: which seat, how high, what is being done, and (when asked) whether to rest. */
@Composable
private fun ColumnScope.SickPanel(s: UiState, c: CareController) {
    val seat = s.targetSeat ?: s.seatMsi.filterKeys { !it.isDriver }.maxByOrNull { it.value }?.key
    val msi = seat?.let { s.seatMsi[it] } ?: 0.0
    val color = Palette.stage(s.stage)
    Column(Modifier.semantics(mergeDescendants = true) {
        liveRegion = LiveRegionMode.Polite
        contentDescription = "${seat?.label ?: "동승자"} 멀미지수 ${msi.roundToInt()}, ${s.stage.label}"
    }) {
        Text("${seat?.label ?: "동승자"} · 멀미지수(추정)", fontSize = 18.sp, color = Palette.sub)
        Row(verticalAlignment = Alignment.Bottom) {
            Text("${msi.roundToInt()}", fontSize = 88.sp, fontWeight = FontWeight.Bold, color = color)
            Spacer(Modifier.padding(6.dp))
            Text(s.stage.label, fontSize = 30.sp, color = color, modifier = Modifier.padding(bottom = 18.dp))
        }
    }
    CareChips(s)
    Spacer(Modifier.weight(1f))
    SpeechLine(s)
    if (s.question == Question.REST) {
        Text(
            "다음 휴게소에서 쉬어갈까요?", fontSize = 24.sp,
            modifier = Modifier.fillMaxWidth().background(Palette.cardHi, RoundedCornerShape(16.dp)).border(2.dp, Palette.accent, RoundedCornerShape(16.dp)).padding(18.dp)
                .semantics { liveRegion = LiveRegionMode.Polite },
        )
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            BigButton("응, 쉬어 갈게", Modifier.weight(1f)) { c.voice(VoiceIntent.YES) }
            BigButton("아니", Modifier.weight(1f), primary = false) { c.voice(VoiceIntent.NO) }
        }
    } else {
        RestoreAndClose(c)
    }
}

/** Care was applied: say what was done, then get out of the way. */
@Composable
private fun ColumnScope.NoticePanel(s: UiState, c: CareController) {
    val n = s.notice ?: return
    Text(n.title, fontSize = 30.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
    CareChips(s)
    if (n.kind == NoticeKind.AIR) {
        Text("실내 CO₂(추정) ${s.co2.roundToInt()}ppm · ${s.airGrade.label}", fontSize = 18.sp, color = Palette.sub)
        if (s.freshRemainingSec > 0) {
            Column(Modifier.fillMaxWidth().background(Palette.cardHi, RoundedCornerShape(16.dp)).padding(16.dp)) {
                Text("남은 시간", fontSize = 16.sp, color = Palette.sub)
                Text(fmt(s.freshRemainingSec), fontSize = 34.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    } else {
        aheadText(s)?.let { Text(it, fontSize = 18.sp, color = Palette.sub) }
    }
    Spacer(Modifier.weight(1f))
    SpeechLine(s)
    RestoreAndClose(c)
}

/** Opened by hand (or a split-screen pane): the two numbers, the care in progress, and the controls. */
@Composable
private fun ColumnScope.StatusPanel(s: UiState, c: CareController, standalone: Boolean) {
    var keywords by remember { mutableStateOf(false) }
    val msi = s.seatMsi.filterKeys { !it.isDriver }.values.maxOrNull() ?: 0.0
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Reading("멀미지수(추정)", "${msi.roundToInt()}", s.stage.label, Palette.stage(s.stage), Modifier.weight(1f))
        Reading("실내 CO₂(추정)", "${s.co2.roundToInt()}", "ppm · ${s.airGrade.label}", Palette.air(s.airGrade), Modifier.weight(1f))
    }
    if (s.activeCare.isEmpty()) {
        Text(if (s.paused) "쉬는 중이에요 · 공기가 많이 탁해지면 다시 움직여요" else "살펴보는 중이에요", fontSize = 20.sp, color = Palette.sub)
    } else {
        CareChips(s)
    }
    aheadText(s)?.let { Text(it, fontSize = 18.sp, color = Palette.sub) }
    Spacer(Modifier.weight(1f))
    SpeechLine(s)
    Text("${s.speedKmh.roundToInt()} km/h · ${fmt(s.tSec)} / ${fmt(s.durationSec)} · ${s.speed}×", fontSize = 14.sp, color = Palette.sub)
    Text("위치·내비: 시나리오 재생 / 차량: ${s.vehicleSource.label}", fontSize = 14.sp, color = Palette.sub)
    if (keywords) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            BigButton("멀미 나", Modifier.weight(1f), primary = false) { c.voice(VoiceIntent.SICK); keywords = false }
            BigButton("답답해", Modifier.weight(1f), primary = false) { c.voice(VoiceIntent.STUFFY); keywords = false }
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        BigButton("↺ 원래대로", Modifier.weight(1f), primary = false) { c.voice(VoiceIntent.RESTORE) }
        BigButton(if (s.listening) "듣는 중…" else "🎤 말로 하기", Modifier.weight(1f)) { if (!c.listen()) keywords = !keywords }
    }
    if (s.speedKmh < 1.0 && s.running) {
        BigButton("시연 종료", Modifier.fillMaxWidth(), primary = false) { c.stop() }
    } else if (!standalone) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            BigButton("자세히 보기", Modifier.weight(1f), primary = false) { c.setDashboard(true) }
            BigButton("닫기", Modifier.weight(1f), primary = false) { c.closeToolbar() }
        }
    }
}

@Composable
private fun Reading(title: String, value: String, word: String, color: Color, modifier: Modifier) {
    Column(
        modifier.background(Palette.cardHi, RoundedCornerShape(16.dp)).padding(16.dp)
            .semantics(mergeDescendants = true) { contentDescription = "$title $value $word" },
    ) {
        Text(title, fontSize = 15.sp, color = Palette.sub, maxLines = 1)
        Text(value, fontSize = 46.sp, fontWeight = FontWeight.Bold, color = color)
        Text(word, fontSize = 17.sp, color = color, maxLines = 1)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CareChips(s: UiState) {
    if (s.activeCare.isEmpty()) return
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        s.activeCare.take(4).forEach { Chip(it) }
    }
}

@Composable
private fun SpeechLine(s: UiState) {
    s.recentSpeech?.let {
        Text(
            "🔊 $it", fontSize = 18.sp, color = Palette.accent, maxLines = 2, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth().background(Palette.accent.copy(alpha = 0.12f), RoundedCornerShape(14.dp)).padding(14.dp),
        )
    }
}

@Composable
private fun RestoreAndClose(c: CareController) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        BigButton("↺ 원래대로", Modifier.weight(1f), primary = false) { c.voice(VoiceIntent.RESTORE) }
        BigButton("닫기", Modifier.weight(1f), primary = false) { c.closeToolbar() }
    }
}

private fun aheadText(s: UiState): String? = when {
    s.curveAlert && s.nearestTurnM != null && s.nearestTurnM < 300 -> "커브 구간 주행 중"
    s.curveAlert && s.nearestTurnM != null -> "%.1fkm 뒤 커브 구간".format(s.nearestTurnM / 1000)
    s.restAdded != null -> "${s.restAdded.name} 경유 중"
    else -> null
}

/**
 * Stand-in for the navigation app that owns the rest of the screen. A drawing, not a map: it carries a label
 * saying so, and the only live text is the scenario's own turn distance.
 */
@Composable
private fun NaviPlaceholder(s: UiState, modifier: Modifier) {
    Box(modifier.background(Color(0xFF18222E))) {
        Canvas(Modifier.fillMaxSize()) {
            fun p(x: Float, y: Float) = Offset(size.width * x, size.height * y)
            fun road(color: Color, width: Float, pts: List<Offset>) {
                val path = Path().apply { pts.forEachIndexed { i, o -> if (i == 0) moveTo(o.x, o.y) else lineTo(o.x, o.y) } }
                drawPath(path, color, style = Stroke(width = width, cap = StrokeCap.Round))
            }
            val unit = size.height / 100f
            road(Color(0xFF263545), unit * 2.4f, listOf(p(0.18f, 1.05f), p(0.28f, 0.66f)))
            road(Color(0xFF263545), unit * 2.4f, listOf(p(0.42f, 0.42f), p(0.34f, -0.05f)))
            road(Color(0xFF263545), unit * 2.4f, listOf(p(0.66f, 0.34f), p(0.84f, 1.05f)))
            road(Color(0xFF2F4257), unit * 4.2f, listOf(p(-0.02f, 0.79f), p(0.28f, 0.66f), p(0.42f, 0.42f), p(0.66f, 0.34f), p(1.04f, 0.10f)))
            road(Palette.accent, unit * 1.6f, listOf(p(0.12f, 0.73f), p(0.28f, 0.66f), p(0.42f, 0.42f), p(0.66f, 0.34f), p(0.86f, 0.215f)))
            drawCircle(Palette.text, unit * 2.1f, p(0.12f, 0.73f))
        }
        Column(Modifier.align(Alignment.TopStart).padding(28.dp).background(Palette.bg, RoundedCornerShape(16.dp)).padding(horizontal = 20.dp, vertical = 14.dp)) {
            Text(
                when {
                    s.nearestTurnM == null -> "경로 안내 중"
                    s.nearestTurnM < 1000 -> "${(s.nearestTurnM / 10).roundToInt() * 10}m 앞 회전"
                    else -> "%.1fkm 앞 회전".format(s.nearestTurnM / 1000)
                },
                fontSize = 30.sp, fontWeight = FontWeight.SemiBold,
            )
            Text("내비게이션 자리 · 시연용 그림", fontSize = 16.sp, color = Palette.sub)
        }
        Column(Modifier.align(Alignment.BottomStart).padding(28.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            s.cueNote?.let { Chip("시연 안내 · $it", Palette.sub) }
            Text(
                "${s.speedKmh.roundToInt()} km/h · ${fmt(s.tSec)} / ${fmt(s.durationSec)} · ${s.speed}×",
                fontSize = 16.sp, color = Palette.sub,
                modifier = Modifier.background(Palette.bg, RoundedCornerShape(12.dp)).padding(horizontal = 16.dp, vertical = 10.dp),
            )
        }
    }
}
