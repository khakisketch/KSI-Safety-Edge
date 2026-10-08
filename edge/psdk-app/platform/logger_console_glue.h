/* Provenance: DJI PSDK sample-derived MIT glue via SI adaptation (code unchanged).
 * source repo: KSI-Site-Intelligence-Platform@cedeb27eb8aa49b3f2df3414a9a022dd544d5089
 * source path: archive/acquisition-2026/edge/psdk-tap/src/platform/logger_console_glue.h
 * original sha256: ad60b36f6454a9acd3861c70e6d159b86c19c445c97883965513a65c61010184
 */
/* edge/psdk-tap/platform — PSDK 로그를 stderr 로 흘려보낸다.
 * stdout 은 NDJSON 산출물 전용으로 비워둔다(--out 이 아니라 stdout 리다이렉트로 쓸 수도
 * 있는 운영 방식을 막지 않기 위함) — PSDK 자체 로그는 항상 stderr.
 */
#ifndef KSI_PSDK_TAP_PLATFORM_LOGGER_CONSOLE_GLUE_H
#define KSI_PSDK_TAP_PLATFORM_LOGGER_CONSOLE_GLUE_H

#include <dji_typedef.h>

#ifdef __cplusplus
extern "C" {
#endif

T_DjiReturnCode KsiLoggerConsoleGlue_Register(void);

#ifdef __cplusplus
}
#endif

#endif /* KSI_PSDK_TAP_PLATFORM_LOGGER_CONSOLE_GLUE_H */
