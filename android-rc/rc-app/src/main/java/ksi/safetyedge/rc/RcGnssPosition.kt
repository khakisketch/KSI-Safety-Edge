package ksi.safetyedge.rc

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager

/**
 * D2 — RC 자체 GNSS 위치 (선택 기능).
 *
 * 현장 자동 추천은 **선택 기능**이므로 이 값이 없거나 권한이 거부되어도
 * 비행·AI 관측·기록은 계속 동작한다(추천이 없으면 수동 선택으로 간다).
 *
 * 위치 출처를 **끊지 않는다**: 기체 위치(MSDK)와 RC GNSS는 서로 다른 사실이며,
 * 이쪽은 "이 RC가 스스로 측정한 위치"일 뿐 기체 위치가 아니다.
 */
object RcGnssPosition {

    /**
     * 마지막으로 알고 있는 위치. null = 이 기기가 위치를 확인할 수 없음
     * (권한 없음·제공자 없음·수신 없음). 0으로 대체하지 않는다.
     *
     * **읽기를 먼저 시도한다**: 이 기기(RC Plus 2)에서는 앱이 권한을 직접 선언하지
     * 않아도(DJI MSDK AAR이 선언) 시스템이 앱 프로세스에 위치를 제공할 수 있다.
     * 권한 체크를 선행 조건으로 두면 실제로 읽을 수 있는 위치를 "없음"으로
     * 잘못 판정한다(실기기에서 확인). 그래서 읽기를 시도하고 SecurityException만
     * "없음"으로 처리한다.
     */
    @SuppressLint("MissingPermission")
    fun lastKnown(context: Context): Pair<Double, Double>? {
        val manager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            ?: return null
        val providers = try {
            manager.getProviders(true)
        } catch (_: SecurityException) {
            return null
        } catch (_: Exception) {
            return null
        }
        var best: Location? = null
        for (provider in providers) {
            val location = try {
                @Suppress("MissingPermission")
                manager.getLastKnownLocation(provider)
            } catch (_: SecurityException) {
                null
            } catch (_: Exception) {
                null
            } ?: continue
            if (best == null || location.time > best.time) best = location
        }
        val chosen = best ?: return null
        if (!chosen.hasAccuracy()) return null
        return chosen.latitude to chosen.longitude
    }

    /**
     * 권한 상태(fine 기준). **위치 가용성의 근거로 쓰지 않는다** —
     * 이 기기에서는 권한 체크와 실제 읽기 가능이 어긋난다(위 주석).
     * 권한 상태를 그대로 알리려고만 쓴다.
     *
     * Android 12+에서는 coarse를 부여하면 fine도 "부여됨"으로 보고되므로,
     * coarse만 허용받은 기기가 "위치 확인 가능"으로 잘못 표시된다.
     */
    fun hasFineLocation(context: Context): Boolean =
        try {
            context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED
        } catch (_: Exception) {
            false
        }

    /** 위치 정확도(m). null = 알 수 없음. */
    @SuppressLint("MissingPermission")
    fun lastAccuracyM(context: Context): Double? {
        val manager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            ?: return null
        val providers = try {
            manager.getProviders(true)
        } catch (_: Exception) {
            return null
        }
        var best: Location? = null
        for (provider in providers) {
            val location = try {
                @Suppress("MissingPermission")
                manager.getLastKnownLocation(provider)
            } catch (_: Exception) {
                null
            } ?: continue
            if (best == null || location.time > best.time) best = location
        }
        return best?.takeIf { it.hasAccuracy() }?.accuracy?.toDouble()
    }
}
