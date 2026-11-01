package app.carecabin.platform

import android.content.Context

/** sim flavor: no Pleos SDK on the classpath. Runs anywhere (phone, tablet, plain AAOS emulator). */
object PlatformFactory {
    fun create(context: Context): Platform = object : Platform {
        override val name = "sim"
        override val vehicle = null
        override val speaker: Speaker = AndroidTtsSpeaker(context)
        override val listener: Listener? = null
        override val navi: NaviPort? = null
        override val location: LocationPort? = null
        override fun status() = listOf(
            "빌드: sim (Pleos SDK 미포함)",
            "차량: 시뮬레이터",
            "음성 안내: ${speaker.name}",
            "음성 인식: 없음 → 키워드 테스트 버튼",
            "위치·내비: 시나리오 재생",
        )
        override fun release() = speaker.release()
    }
}
