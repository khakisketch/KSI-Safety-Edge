package ksi.safetyedge.rc

import android.content.Context
import ksi.safetyedge.rc.DroneLink

/**
 * 시험용 Edge 링크(TCP) 설정 — **개발·통합시험용**, 제품 경로가 아니다.
 *
 * WHY THIS EXISTS
 *   RC의 제품 MOP 경로는 기체 데이터 채널을 거친다(MSDK). 기체가 없으면
 *   RC가 실제 Edge의 관측을 받을 수 없다. 그래서 **같은 바이트를 같은
 *   코드로** 처리하는 시험용 경로를 둔다:
 *   - 수신·검증·상태·저장은 제품과 동일한 코드(rc-core의 MOP 파서);
 *   - 물리 경로만 다르다(LAN). 기체 명령은 통제권 경로가 없어 실행을
 *     주장할 수 없으므로 게이트가 그대로 막는다.
 *
 * HONESTY
 *   - 이 링크가 붙은 화면은 경로가 "TEST_TCP"임을 구분해 표시한다.
 *   - 기체 연결 사실은 **알 수 없음**(null)이다.
 *   - 설정을 바꾸면 **앱을 다시 시작**해야 한다(링크는 시작 시 한 번 정해진다).
 */
object TestEdgeLink {
    private const val PREFS = "ksi_test_edge_link"
    private const val KEY_HOST = "host"
    private const val KEY_PORT = "port"

    /** The configured test link, or null when the product path must be used. */
    fun from(context: Context): DroneLink? {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val host = prefs.getString(KEY_HOST, null)?.trim().orEmpty()
        val port = prefs.getInt(KEY_PORT, 0)
        if (host.isEmpty() || port !in 1..65535) return null
        return TcpDroneLink(host, port)
    }

    /** True when a test link target is configured (the pref exists). */
    fun isConfigured(context: Context): Boolean {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val host = prefs.getString(KEY_HOST, null)?.trim().orEmpty()
        val port = prefs.getInt(KEY_PORT, 0)
        return host.isNotEmpty() && port in 1..65535
    }

    /** Stores the test link target (an empty host clears it). */
    fun save(context: Context, host: String, port: Int) {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val clean = host.trim()
        if (clean.isEmpty() || port !in 1..65535) {
            prefs.edit().remove(KEY_HOST).remove(KEY_PORT).apply()
            return
        }
        prefs.edit().putString(KEY_HOST, clean).putInt(KEY_PORT, port).apply()
    }

    /** One line for the settings row: the current target, or "사용 안 함". */
    fun describe(context: Context): String {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val host = prefs.getString(KEY_HOST, null)?.trim().orEmpty()
        val port = prefs.getInt(KEY_PORT, 0)
        return if (host.isEmpty() || port !in 1..65535) {
            "시험용 Edge 링크(TCP): 사용 안 함 — 기체 경유 MOP 사용"
        } else {
            "시험용 Edge 링크(TCP): $host:$port — 앱 재시작 후 적용"
        }
    }
}
