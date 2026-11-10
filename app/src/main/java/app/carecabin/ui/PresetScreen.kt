package app.carecabin.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import app.carecabin.CareController
import app.carecabin.Screen
import app.carecabin.UiState
import app.carecabin.engine.model.CarClass
import app.carecabin.engine.model.Seat
import app.carecabin.engine.model.Sensitivity
import app.carecabin.engine.sim.Scenarios

/** Screen 1 — before departure (parked): who sits where, sensitivity, allowed care, demo scenario. */
@Composable
fun PresetScreen(s: UiState, c: CareController) {
    Column(Modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("케어캐빈", fontSize = 36.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.padding(8.dp))
            Text("출발 전 탑승자 설정 · 정차 중", color = Palette.sub, fontSize = 20.sp)
            Spacer(Modifier.weight(1f))
            BigButton("진단", primary = false) { c.open(Screen.DIAGNOSTICS) }
        }
        if (s.uxRestricted) {
            Text(
                "주행 중에는 설정을 바꿀 수 없어요. 정차한 뒤 바꿔 주세요.", fontSize = 20.sp, color = Palette.fair,
                modifier = Modifier.fillMaxWidth().background(Palette.fair.copy(alpha = 0.12f), RoundedCornerShape(14.dp)).padding(16.dp),
            )
        }
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            Panel(Modifier.weight(1.1f).fillMaxHeight(), title = "좌석별 탑승자 · 눌러서 민감도 바꾸기") {
                Text("앞", color = Palette.sub, fontSize = 16.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    SeatCard(Seat.DRIVER, s, c, Modifier.weight(1f)); SeatCard(Seat.PASSENGER, s, c, Modifier.weight(1f))
                }
                Spacer(Modifier.padding(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    SeatCard(Seat.REAR_LEFT, s, c, Modifier.weight(1f)); SeatCard(Seat.REAR_RIGHT, s, c, Modifier.weight(1f))
                }
                Spacer(Modifier.padding(6.dp))
                Text("누를 때마다: 보통 → 멀미 민감 → 케어 끔 → 빈 좌석", color = Palette.sub, fontSize = 15.sp)
                Text("나이는 묻지 않습니다. 별칭·민감도는 이 기기 안에만 저장합니다.", color = Palette.sub, fontSize = 15.sp)
                Spacer(Modifier.padding(6.dp))
                Text("차급 (실내 공기 체적)", color = Palette.sub, fontSize = 18.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    CarClass.entries.forEach { cc ->
                        Toggle("${cc.label} ${cc.volumeM3}m³", s.preset.carClass == cc, Modifier.weight(1f)) { c.setCarClass(cc) }
                    }
                }
            }
            Panel(Modifier.weight(1f).fillMaxHeight(), title = "주행 중 자동으로 해도 되는 케어") {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    val p = s.preset.permissions
                    PermRow("공조·외기 사이클과 좌석별 온도", "운전석 온도는 바꾸지 않아요", p.climate) { v -> c.setPermissions { it.copy(climate = v) } }
                    PermRow("시트 통풍·열선", null, p.seat) { v -> c.setPermissions { it.copy(seat = v) } }
                    PermRow("차분한 단색 조명", null, p.ambient) { v -> c.setPermissions { it.copy(ambient = v) } }
                    PermRow("부드러운 제동 안내", "트립당 1번", p.brakeTip) { v -> c.setPermissions { it.copy(brakeTip = v) } }
                }
            }
            Panel(Modifier.weight(1f).fillMaxHeight(), title = "시연 시나리오") {
                Scenarios.all.forEach { sc ->
                    val sel = s.scenarioId == sc.id
                    Column(
                        Modifier.fillMaxWidth().padding(bottom = 12.dp)
                            .border(2.dp, if (sel) Palette.accent else Palette.line, RoundedCornerShape(16.dp))
                            .clickable { c.selectScenario(sc.id) }.padding(16.dp),
                    ) {
                        Text(sc.title, fontSize = 21.sp, fontWeight = FontWeight.SemiBold, color = if (sel) Palette.accent else Palette.text)
                        Text(sc.summary, fontSize = 15.sp, color = Palette.sub)
                    }
                }
                Text("배속", color = Palette.sub, fontSize = 18.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    listOf(1, 4, 10).forEach { x -> Toggle("${x}×", s.speed == x, Modifier.weight(1f)) { c.setSpeed(x) } }
                }
                Spacer(Modifier.weight(1f))
                Text("위치·내비 신호: 시나리오 재생 / 차량 신호: ${s.vehicleSource.label}", color = Palette.sub, fontSize = 15.sp)
                Spacer(Modifier.padding(4.dp))
                BigButton("이대로 출발", Modifier.fillMaxWidth()) { c.start() }
            }
        }
        Disclaimer()
    }
}

@Composable
private fun SeatCard(seat: Seat, s: UiState, c: CareController, modifier: Modifier) {
    val occ = s.preset.occupants[seat]
    val present = occ != null || seat.isDriver
    val color = when (occ?.sensitivity) { Sensitivity.HIGH -> Palette.poor; Sensitivity.OFF -> Palette.sub; null -> Palette.line; else -> Palette.good }
    Column(
        modifier.heightIn(min = 130.dp)
            .background(if (present) Palette.cardHi else Palette.card, RoundedCornerShape(16.dp))
            .border(2.dp, color, RoundedCornerShape(16.dp))
            .clickable(role = Role.Button, onClickLabel = "민감도 바꾸기") { c.cycleSensitivity(seat) }
            .semantics(mergeDescendants = true) { stateDescription = if (present) (occ?.sensitivity ?: Sensitivity.NORMAL).label else "빈 좌석" }
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(seat.label, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
        if (present) {
            Text(occ?.alias?.ifBlank { null } ?: if (seat.isDriver) "운전자" else "동승자", color = Palette.sub, fontSize = 16.sp)
            Chip(occ?.sensitivity?.label ?: Sensitivity.NORMAL.label, color)
        } else {
            Text("빈 좌석", color = Palette.sub, fontSize = 16.sp)
        }
    }
}

@Composable
private fun PermRow(title: String, sub: String?, on: Boolean, set: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 76.dp).toggleable(value = on, role = Role.Switch, onValueChange = set), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 19.sp)
            if (sub != null) Text(sub, fontSize = 14.sp, color = Palette.sub)
        }
        Switch(checked = on, onCheckedChange = null)
    }
}

/** Single-choice button: ≥ 76 dp tall, centred label, announced as a selectable option. */
@Composable
fun Toggle(text: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    androidx.compose.foundation.layout.Box(
        modifier
            .heightIn(min = 76.dp)
            .background(if (selected) Palette.accent else Palette.cardHi, RoundedCornerShape(14.dp))
            .border(if (selected) 0.dp else 1.dp, Palette.line, RoundedCornerShape(14.dp))
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, fontSize = 18.sp, color = if (selected) Color(0xFF00273A) else Palette.text, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
    }
}
