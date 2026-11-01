package app.carecabin.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.carecabin.CapabilityProfile
import app.carecabin.CareController
import app.carecabin.UiState
import app.carecabin.VehicleSource

/** Screen 4 — diagnostics (development and demo, parked only): bound implementations, capability, logs. */
@Composable
fun DiagnosticsScreen(s: UiState, c: CareController) {
    Column(Modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("진단", fontSize = 34.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.padding(8.dp))
            Text("개발·시연용 · 정차 중에만", color = Palette.sub, fontSize = 20.sp)
            Spacer(Modifier.weight(1f))
            BigButton("돌아가기") { c.backToPreset() }
        }
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
            Panel(Modifier.weight(1f).fillMaxHeight(), title = "연결된 구현") {
                s.platformStatus.forEach { Text("• $it", fontSize = 17.sp, modifier = Modifier.padding(vertical = 3.dp)) }
                Text("• 주행 제한(CarUxRestrictions): ${if (s.uxRestricted) "제한 중 — 설정 잠금" else "제한 없음(또는 비차량 기기)"}", fontSize = 17.sp, modifier = Modifier.padding(vertical = 3.dp))
                Spacer(Modifier.padding(8.dp))
                Text("차량 신호", color = Palette.sub, fontSize = 18.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    VehicleSource.entries.forEach { v -> Toggle(v.label, s.vehicleSource == v, Modifier.weight(1f)) { c.setVehicleSource(v) } }
                }
                Spacer(Modifier.padding(8.dp))
                Text("시뮬레이터 차종 (미지원 수단 대체 동작 확인)", color = Palette.sub, fontSize = 18.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    CapabilityProfile.entries.forEach { p -> Toggle(p.label, s.profile == p, Modifier.weight(1f)) { c.setProfile(p) } }
                }
                Spacer(Modifier.padding(8.dp))
                Text("위치·내비 TBT는 Virtual Console로 재현할 수 없어 앱 안의 시나리오 재생기로 넣습니다. Pleos 차량을 고르면 기어·와이퍼·탑승은 Virtual Console에서 직접 바꿉니다(화면 오른쪽 위 안내).", color = Palette.sub, fontSize = 15.sp)
            }
            Panel(Modifier.weight(1.4f).fillMaxHeight(), title = "최근 트립 로그 (명령·음성·이벤트)") {
                LazyColumn {
                    items(s.logs.reversed()) { l ->
                        Text("%d:%02d  %s".format(l.tSec / 60, l.tSec % 60, l.text), fontSize = 15.sp, modifier = Modifier.padding(vertical = 2.dp))
                    }
                }
            }
        }
        Disclaimer("모델 시뮬레이션 · SDK 연결 · Pleos 런타임 동작 · 실차 증거는 서로 다른 것입니다")
    }
}
