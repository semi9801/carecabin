package app.carecabin.engine.care

import app.carecabin.engine.model.Actuator
import app.carecabin.engine.model.ActuatorDefaults
import app.carecabin.engine.model.Preset
import app.carecabin.engine.model.RestArea
import app.carecabin.engine.model.Seat
import app.carecabin.engine.model.VehicleSignals
import app.carecabin.engine.model.VoiceIntent

data class CareCommand(val actuator: Actuator, val value: Int, val reason: String, val restore: Boolean = false)

enum class SpeechKind { CURVE, AIR, BRAKE_TIP, REST, REPLY }

data class Speech(val kind: SpeechKind, val text: String)

enum class SickStage(val label: String) { OBSERVE("관찰"), MILD("완화"), CAUTION("주의"), REST("휴식") }

enum class AirStage(val label: String) { OBSERVE("관찰"), CYCLE("환기 사이클"), FIXED("외기 고정") }

enum class Question { REST }

/** Provisional thresholds (development plan 4-3). */
data class CareConstants(
    val mildEnter: Double = 30.0,
    val mildExit: Double = 25.0,
    val cautionEnter: Double = 50.0,
    val cautionExit: Double = 45.0,
    val restEnter: Double = 70.0,
    val exitHoldSec: Double = 180.0,
    val mildMinHoldSec: Double = 180.0,
    val cautionMinHoldSec: Double = 300.0,
    val co2Breath1: Double = 1000.0,
    val co2Breath2: Double = 1500.0,
    val co2Breath2Exit: Double = 1000.0,
    val breath2MinHoldSec: Double = 300.0,
    val predictSec: Double = 120.0,
    val cycleGapSec: Double = 30.0,
    val actuatorCooldownSec: Double = 10.0,
    val speechSameKindGapSec: Double = 600.0,
    val speechAnyGapSec: Double = 120.0,
    val restorePauseSec: Double = 600.0,
    val restDeclineCooldownSec: Double = 1200.0,
    val questionTimeoutSec: Double = 300.0,
    val arrivalVentSec: Double = 180.0,
    val movingWindowMaxPct: Int = 6,
    val highSpeedKmh: Double = 80.0,
    val cautionWindowPct: Int = 5,
    val arrivalWindowPct: Int = 30,
    val tempStepTenths: Int = 10,
)

data class CareInputs(
    val tSec: Double,
    /** MSI for occupied seats whose care is on. */
    val msi: Map<Seat, Double>,
    val curveAlert: Boolean,
    val nearestTurnM: Double?,
    val co2Ppm: Double,
    val secondsTo1000: Double,
    val freshNeededSec: Double,
    val speedKmh: Double,
    val moving: Boolean,
    val signals: VehicleSignals,
    val preset: Preset,
    val supported: Set<Actuator>,
    val tripDrivingSec: Double,
    val voice: List<VoiceIntent> = emptyList(),
    val arrivedAtWaypoint: Boolean = false,
    val restArea: RestArea? = null,
)

data class CareUiState(
    val stage: SickStage = SickStage.OBSERVE,
    val targetSeat: Seat? = null,
    val airStage: AirStage = AirStage.OBSERVE,
    val activeCare: List<String> = emptyList(),
    val question: Question? = null,
    val paused: Boolean = false,
    val restAdded: RestArea? = null,
    val seatStages: Map<Seat, SickStage> = emptyMap(),
    /** Seconds left in the current outside-air cycle (0 when none). */
    val freshRemainingSec: Int = 0,
)

data class CareOutput(
    val commands: List<CareCommand>,
    val speech: List<Speech>,
    val waypoint: RestArea?,
    val log: List<String>,
    val ui: CareUiState,
)

/**
 * Turns indices into care intents, applies hard rules and arbitration, and remembers the user's original
 * values so every change can be undone ("원래대로").
 * Priority: ① driving safety → ② at-risk occupant → ③ air quality → ④ comfort.
 */
class CareOrchestrator(private val c: CareConstants = CareConstants()) {
    private class StageState(var stage: SickStage = SickStage.OBSERVE, var enteredAt: Double = 0.0, var belowSince: Double? = null)

    private data class Intent(val actuator: Actuator, val value: Int, val priority: Int, val reason: String)

    private val stages = mutableMapOf<Seat, StageState>()
    private val originals = linkedMapOf<Actuator, Int>()
    private val lastCommanded = mutableMapOf<Actuator, Int>()
    private val lastCommandAt = mutableMapOf<Actuator, Double>()
    private val lastSpokenByKind = mutableMapOf<SpeechKind, Double>()
    private var lastSpokenAny = Double.NEGATIVE_INFINITY
    private var freshUntil = Double.NEGATIVE_INFINITY
    private var lastCycleEnd = Double.NEGATIVE_INFINITY
    private var breath2Since: Double? = null
    private var breath2BelowSince: Double? = null
    private var prevCurveAlert = false
    private var pausedUntil = Double.NEGATIVE_INFINITY
    private var question: Question? = null
    private var questionAt = 0.0
    private var restCooldownUntil = Double.NEGATIVE_INFINITY
    private var restAdded: RestArea? = null
    private var brakeTipDone = false
    private var arrivalVentUntil = Double.NEGATIVE_INFINITY
    private var forcedRestSeat: Seat? = null
    private var rainRuleLogged = false

    var autoCareCount = 0; private set
    var restoreCount = 0; private set
    var restAcceptedCount = 0; private set

    fun evaluate(i: CareInputs): CareOutput {
        val t = i.tSec
        val speech = mutableListOf<Speech>()
        val log = mutableListOf<String>()
        val commands = mutableListOf<CareCommand>()
        var waypoint: RestArea? = null
        fun say(kind: SpeechKind, text: String) {
            val exemptAny = kind == SpeechKind.REST || kind == SpeechKind.REPLY || kind == SpeechKind.CURVE
            val sameOk = kind == SpeechKind.REPLY || kind == SpeechKind.REST ||
                t - (lastSpokenByKind[kind] ?: Double.NEGATIVE_INFINITY) >= c.speechSameKindGapSec
            val anyOk = exemptAny || t - lastSpokenAny >= c.speechAnyGapSec
            if (sameOk && anyOk) {
                speech += Speech(kind, text); lastSpokenByKind[kind] = t; lastSpokenAny = t
            }
        }

        // ---- voice intents (explicit user requests win over the pause) ----
        for (v in i.voice) when (v) {
            VoiceIntent.RESTORE -> {
                commands += restoreAll(t, "원래대로 요청")
                say(SpeechKind.REPLY, "원래 설정으로 되돌렸어요. 10분 동안 자동 케어를 쉴게요.")
                log += "원래대로: 모든 값 복원, 10분 일시중지"
            }
            VoiceIntent.STUFFY -> {
                pausedUntil = minOf(pausedUntil, t)
                freshUntil = maxOf(freshUntil, t + 180)
                say(SpeechKind.REPLY, "바깥 공기로 3분 바꿀게요.")
                log += "답답해: 외기 3분"
            }
            VoiceIntent.SICK -> {
                pausedUntil = minOf(pausedUntil, t)
                val seat = i.msi.filterKeys { !it.isDriver }.maxByOrNull { it.value }?.key
                    ?: i.msi.keys.firstOrNull { !it.isDriver }
                if (seat != null) {
                    forcedRestSeat = seat
                    restCooldownUntil = Double.NEGATIVE_INFINITY
                    log += "멀미 호소: ${seat.label} 휴식 단계"
                }
            }
            VoiceIntent.YES -> if (question == Question.REST) {
                question = null
                val area = i.restArea
                if (area != null) {
                    waypoint = area; restAdded = area; restAcceptedCount++
                    say(SpeechKind.REPLY, "${area.name}를 경유지로 추가했어요.")
                    log += "쉬어가기 수락: 경유지 ${area.name}"
                } else {
                    say(SpeechKind.REPLY, "가까운 휴게소를 찾지 못했어요. 안전한 곳에서 쉬어 가세요.")
                }
            }
            VoiceIntent.NO -> if (question == Question.REST) {
                question = null; restCooldownUntil = t + c.restDeclineCooldownSec; forcedRestSeat = null
                say(SpeechKind.REPLY, "알겠어요. 계속 살펴볼게요.")
                log += "쉬어가기 거절: 20분 쿨다운"
            }
        }
        if (question != null && t - questionAt > c.questionTimeoutSec) question = null
        if (i.arrivedAtWaypoint) {
            arrivalVentUntil = t + c.arrivalVentSec
            restAdded = null; forcedRestSeat = null
            say(SpeechKind.REPLY, "휴게소에 도착했어요. 3분 동안 실내 공기를 바꿀게요.")
            log += "경유지 도착: 전면 환기 3분"
        }
        val paused = t < pausedUntil

        // ---- sickness stages per seat (hysteresis) ----
        for ((seat, m) in i.msi) updateStage(seat, m, i.curveAlert, t)
        stages.keys.retainAll(i.msi.keys)
        forcedRestSeat?.let { s -> stages.getOrPut(s) { StageState() }.apply { if (stage != SickStage.REST) { stage = SickStage.REST; enteredAt = t } } }
        val target = stages.filterKeys { !it.isDriver }
            .maxWithOrNull(compareBy({ it.value.stage.ordinal }, { i.msi[it.key] ?: 0.0 }))
            ?.takeIf { it.value.stage != SickStage.OBSERVE }?.key
        val targetStage = target?.let { stages[it]?.stage } ?: SickStage.OBSERVE

        // ---- air stages ----
        val autoRecirc = i.signals.autoRecirculation
        val airAllowed = i.preset.permissions.climate && !autoRecirc
        if (airAllowed && !paused && t >= freshUntil && t - lastCycleEnd >= c.cycleGapSec &&
            (i.co2Ppm >= c.co2Breath1 || i.secondsTo1000 < c.predictSec)) {
            freshUntil = t + i.freshNeededSec
            say(SpeechKind.AIR, "바깥 공기를 잠깐 들였어요. 냉난방 설정은 그대로예요.")
            log += "숨1: 외기 ${i.freshNeededSec.toInt()}초 (추정 CO₂ ${i.co2Ppm.toInt()}ppm)"
        }
        if (freshUntil != Double.NEGATIVE_INFINITY && t >= freshUntil && lastCycleEnd < freshUntil) lastCycleEnd = freshUntil
        if (i.co2Ppm >= c.co2Breath2 && breath2Since == null) { breath2Since = t; log += "숨2: 외기 고정" }
        breath2Since?.let { since ->
            breath2BelowSince = if (i.co2Ppm < c.co2Breath2Exit) breath2BelowSince ?: t else null
            if (t - since >= c.breath2MinHoldSec && breath2BelowSince != null) { breath2Since = null; breath2BelowSince = null }
        }
        if (paused && i.co2Ppm >= c.co2Breath2) say(SpeechKind.AIR, "공기가 많이 탁해져서 바깥 공기를 들일게요.")
        val cycleOn = t < freshUntil
        val airStage = when { breath2Since != null -> AirStage.FIXED; cycleOn -> AirStage.CYCLE; else -> AirStage.OBSERVE }

        // ---- rest proposal ----
        val restWanted = targetStage == SickStage.REST || (i.co2Ppm >= 1800 && i.tripDrivingSec >= 5400)
        if (restWanted && question == null && restAdded == null && t >= restCooldownUntil && i.moving) {
            question = Question.REST; questionAt = t
            say(SpeechKind.REST, "다음 휴게소에서 쉬어갈까요?")
            log += "휴식 제안"
        }

        // ---- curve preview speech ----
        if (i.curveAlert && !prevCurveAlert && target != null) {
            say(SpeechKind.CURVE, "앞으로 2분간 커브예요. 먼 곳을 봐 주세요.")
            log += "커브 예고 (${i.nearestTurnM?.toInt() ?: 0}m 앞)"
        }
        prevCurveAlert = i.curveAlert

        // ---- intents ----
        val intents = mutableListOf<Intent>()
        fun cur(a: Actuator) = originals[a] ?: i.signals.actuators[a] ?: lastCommanded[a] ?: ActuatorDefaults.of(a)
        fun sup(a: Actuator) = a in i.supported
        val arrivalVent = t < arrivalVentUntil
        if (arrivalVent) {
            for (s in i.signals.occupied) if (sup(Actuator.Window(s))) intents += Intent(Actuator.Window(s), c.arrivalWindowPct, 1, "도착 환기")
            if (sup(Actuator.Recirculation)) intents += Intent(Actuator.Recirculation, 0, 1, "도착 환기")
            if (sup(Actuator.FanSpeed)) intents += Intent(Actuator.FanSpeed, 8, 1, "도착 환기")
        }
        if (target != null && targetStage >= SickStage.MILD) {
            val s = target
            val why = "${s.label} 멀미 케어"
            when {
                sup(Actuator.ZoneTemp(s)) -> intents += Intent(Actuator.ZoneTemp(s), cur(Actuator.ZoneTemp(s)) - c.tempStepTenths, 2, why)
                sup(Actuator.SeatVent(s)) -> intents += Intent(Actuator.SeatVent(s), 1, 2, why)
                !s.isDriver && s != Seat.PASSENGER && sup(Actuator.ZoneTemp(Seat.PASSENGER)) && Seat.PASSENGER !in i.msi.keys ->
                    intents += Intent(Actuator.ZoneTemp(Seat.PASSENGER), cur(Actuator.ZoneTemp(Seat.PASSENGER)) - c.tempStepTenths, 2, "$why(대체: 동승석 존)")
                sup(Actuator.FanSpeed) -> intents += Intent(Actuator.FanSpeed, cur(Actuator.FanSpeed) + 1, 2, "$why(대체: 풍량)")
            }
            when {
                sup(Actuator.FanFace(s)) -> intents += Intent(Actuator.FanFace(s), 1, 2, why)
                s != Seat.PASSENGER && sup(Actuator.FanFace(Seat.PASSENGER)) -> intents += Intent(Actuator.FanFace(Seat.PASSENGER), 1, 2, "$why(대체: 동승석 풍향)")
            }
            if (airAllowed && sup(Actuator.Recirculation)) intents += Intent(Actuator.Recirculation, 0, 2, why)
            if (sup(Actuator.Ambient)) intents += Intent(Actuator.Ambient, 1, 4, why)
            if (targetStage >= SickStage.CAUTION) {
                if (sup(Actuator.SeatVent(s))) intents += Intent(Actuator.SeatVent(s), 2, 2, why)
                if (sup(Actuator.Window(s))) intents += Intent(Actuator.Window(s), c.cautionWindowPct, 2, why)
                if (!brakeTipDone && i.preset.permissions.brakeTip && i.moving) {
                    brakeTipDone = true
                    say(SpeechKind.BRAKE_TIP, "동승자를 위해 조금만 부드럽게 제동해 주세요.")
                }
            }
        }
        if (airAllowed && (cycleOn || breath2Since != null)) {
            val step = if (breath2Since != null) 2 else 1
            if (sup(Actuator.Recirculation)) intents += Intent(Actuator.Recirculation, 0, 3, "공기 케어")
            if (sup(Actuator.FanSpeed)) intents += Intent(Actuator.FanSpeed, cur(Actuator.FanSpeed) + step, 3, "공기 케어")
        }

        // ---- hard rules, permissions, arbitration ----
        val rainBlocked = i.moving && i.signals.wiperOn && intents.any { it.actuator is Actuator.Window }
        if (rainBlocked && !rainRuleLogged) log += "우천 규칙: 창문 대신 바람"
        rainRuleLogged = rainBlocked
        val kept = intents.filter { allowed(it, i, paused) }
        val desired = kept.groupBy { it.actuator }.mapValues { (a, list) ->
            val best = list.minOf { it.priority }
            val top = list.filter { it.priority == best }
            val value = when (a) {
                Actuator.FanSpeed -> top.maxOf { it.value }
                is Actuator.Window -> top.minOf { it.value }
                else -> top.first().value
            }
            Intent(a, clamp(a, value), best, top.first().reason)
        }

        // ---- commands: apply desired, restore what is no longer wanted ----
        for ((a, intent) in desired) {
            val current = i.signals.actuators[a] ?: lastCommanded[a] ?: ActuatorDefaults.of(a)
            if (a !in originals) originals[a] = current
            if (intent.value != current && lastCommanded[a] != intent.value && cooldownOk(a, t)) {
                commands += CareCommand(a, intent.value, intent.reason)
                lastCommanded[a] = intent.value; lastCommandAt[a] = t; autoCareCount++
            }
        }
        val toRestore = originals.keys.filter { it !in desired }
        for (a in toRestore) {
            if (!cooldownOk(a, t)) continue
            val orig = originals.remove(a) ?: continue
            val current = i.signals.actuators[a] ?: lastCommanded[a]
            if (current != orig) {
                commands += CareCommand(a, orig, "복귀", restore = true)
                lastCommanded[a] = orig; lastCommandAt[a] = t
            }
        }

        val ui = CareUiState(
            stage = targetStage,
            targetSeat = target,
            airStage = airStage,
            activeCare = desired.values.sortedBy { it.actuator == Actuator.Ambient }.map { describe(it, cycleOn) }.distinct().take(6),
            question = question,
            paused = paused,
            restAdded = restAdded,
            seatStages = stages.mapValues { it.value.stage },
            freshRemainingSec = if (cycleOn) (freshUntil - t).toInt().coerceAtLeast(0) else 0,
        )
        return CareOutput(commands, speech, waypoint, log, ui)
    }

    /** "원래대로": every remembered value back, care paused for 10 minutes. */
    private fun restoreAll(t: Double, reason: String): List<CareCommand> {
        val out = originals.map { (a, v) -> CareCommand(a, v, reason, restore = true) }
        out.forEach { lastCommanded[it.actuator] = it.value; lastCommandAt[it.actuator] = t }
        originals.clear()
        pausedUntil = t + c.restorePauseSec
        freshUntil = minOf(freshUntil, t); breath2Since = null; breath2BelowSince = null
        stages.values.forEach { it.stage = SickStage.OBSERVE; it.enteredAt = t; it.belowSince = null }
        forcedRestSeat = null; question = null; arrivalVentUntil = Double.NEGATIVE_INFINITY
        restoreCount++
        return out
    }

    /** Trip end: quiet full restore without pausing. */
    fun restoreForTripEnd(t: Double): List<CareCommand> {
        val out = originals.map { (a, v) -> CareCommand(a, v, "트립 종료", restore = true) }
        out.forEach { lastCommanded[it.actuator] = it.value; lastCommandAt[it.actuator] = t }
        originals.clear()
        return out
    }

    private fun updateStage(seat: Seat, m: Double, curveAlert: Boolean, t: Double) {
        val s = stages.getOrPut(seat) { StageState(enteredAt = t) }
        val entry = when {
            m > c.restEnter -> SickStage.REST
            m >= c.cautionEnter -> SickStage.CAUTION
            m >= c.mildEnter || (curveAlert && !seat.isDriver) -> SickStage.MILD
            else -> SickStage.OBSERVE
        }
        if (entry > s.stage) { s.stage = entry; s.enteredAt = t; s.belowSince = null; return }
        when (s.stage) {
            SickStage.REST -> if (m <= c.restEnter && forcedRestSeat != seat) { s.stage = SickStage.CAUTION; s.enteredAt = t }
            SickStage.CAUTION -> {
                s.belowSince = if (m < c.cautionExit) s.belowSince ?: t else null
                if (t - s.enteredAt >= c.cautionMinHoldSec && s.belowSince?.let { t - it >= c.exitHoldSec } == true) {
                    s.stage = SickStage.MILD; s.enteredAt = t; s.belowSince = null
                }
            }
            SickStage.MILD -> {
                s.belowSince = if (m < c.mildExit && !curveAlert) s.belowSince ?: t else null
                if (t - s.enteredAt >= c.mildMinHoldSec && s.belowSince?.let { t - it >= c.exitHoldSec } == true) {
                    s.stage = SickStage.OBSERVE; s.enteredAt = t; s.belowSince = null
                }
            }
            SickStage.OBSERVE -> Unit
        }
    }

    private fun allowed(it: Intent, i: CareInputs, paused: Boolean): Boolean {
        val a = it.actuator
        val p = i.preset.permissions
        // Paused after "원래대로": only the air-safety stage (entered at CO₂ ≥ 1,500, drowsiness risk; held until < 1,000) may act.
        if (paused && !(it.priority == 3 && breath2Since != null)) return false
        // Hard rules: never touch the driver's own zone or seat; never the driver's window.
        when (a) {
            is Actuator.ZoneTemp -> if (a.seat.isDriver) return false
            is Actuator.SeatVent -> if (a.seat.isDriver) return false
            is Actuator.FanFace -> if (a.seat.isDriver) return false
            is Actuator.Window -> if (a.seat.isDriver && i.moving) return false
            else -> Unit
        }
        if (a is Actuator.Window && i.moving) {
            if (i.signals.wiperOn) return false
            if (i.speedKmh >= c.highSpeedKmh) return false
        }
        return when (a) {
            Actuator.Recirculation, Actuator.FanSpeed, is Actuator.ZoneTemp, is Actuator.FanFace -> p.climate
            is Actuator.SeatVent -> p.seat
            is Actuator.Window -> p.window
            Actuator.Ambient -> p.ambient
        }
    }

    private fun clamp(a: Actuator, v: Int): Int = when (a) {
        Actuator.FanSpeed -> v.coerceIn(1, 8)
        is Actuator.ZoneTemp -> v.coerceIn(160, 300)
        is Actuator.SeatVent -> v.coerceIn(0, 3)
        is Actuator.Window -> v.coerceIn(0, 100)
        else -> v
    }

    private fun cooldownOk(a: Actuator, t: Double) = t - (lastCommandAt[a] ?: Double.NEGATIVE_INFINITY) >= c.actuatorCooldownSec

    private fun describe(it: Intent, cycleOn: Boolean): String {
        val a = it.actuator
        val orig = originals[a]
        return when (a) {
            Actuator.Recirculation -> if (cycleOn) "외기 사이클" else "외기"
            Actuator.FanSpeed -> "풍량 ${orig ?: "-"}→${it.value}"
            Actuator.Ambient -> "차분한 조명"
            is Actuator.ZoneTemp -> "${short(a.seat)} ${fmtTemp(orig)}→${fmtTemp(it.value)}℃"
            is Actuator.FanFace -> "${short(a.seat)} 얼굴 쪽 바람"
            is Actuator.SeatVent -> "${short(a.seat)} 시트 통풍 ${it.value}단"
            is Actuator.Window -> "${short(a.seat)} 창문 ${it.value}%"
        }
    }

    private fun short(s: Seat) = when (s) { Seat.DRIVER -> "운전석"; Seat.PASSENGER -> "동승석"; Seat.REAR_LEFT -> "뒷왼"; Seat.REAR_RIGHT -> "뒷오" }

    private fun fmtTemp(tenths: Int?) = tenths?.let { "%.1f".format(it / 10.0) } ?: "-"
}
