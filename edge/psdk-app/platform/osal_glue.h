/* Provenance: DJI PSDK sample-derived MIT glue via SI adaptation (code unchanged).
 * source repo: KSI-Site-Intelligence-Platform@cedeb27eb8aa49b3f2df3414a9a022dd544d5089
 * source path: archive/acquisition-2026/edge/psdk-tap/src/platform/osal_glue.h
 * original sha256: 3ffc3ccb27ae958f3fb7a2748aa3a5abe65ea18b317ff7e45274e9cd98144433
 */
/* edge/psdk-tap/platform — PSDK OSAL 핸들러 구현(pthread 기반).
 *
 * DjiPlatform_RegOsalHandler 등록은 모든 PSDK 앱에 필수다(dji_platform.h:371-380,
 * "Users need to implement all the interfaces"). PSDK 는 이 OS 추상화 계층을 제공하지
 * 않는다 — 매 플랫폼 포팅마다 앱이 직접 구현해야 하는 부분이고, 여기 쓰인 함수
 * 시그니처는 전부 edge/third_party/psdk/psdk_lib/include/dji_platform.h 의
 * T_DjiOsalHandler 필드 타입에서 그대로 가져왔다(지어낸 것 없음).
 */
#ifndef KSI_PSDK_TAP_PLATFORM_OSAL_GLUE_H
#define KSI_PSDK_TAP_PLATFORM_OSAL_GLUE_H

#include <dji_typedef.h>

#ifdef __cplusplus
extern "C" {
#endif

/* DjiPlatform_RegOsalHandler(&handler) 를 대신 호출한다. */
T_DjiReturnCode KsiOsalGlue_Register(void);

#ifdef __cplusplus
}
#endif

#endif /* KSI_PSDK_TAP_PLATFORM_OSAL_GLUE_H */
