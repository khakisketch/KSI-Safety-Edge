/* Provenance: DJI PSDK sample-derived MIT glue via SI adaptation (code unchanged).
 * source repo: KSI-Site-Intelligence-Platform@cedeb27eb8aa49b3f2df3414a9a022dd544d5089
 * source path: archive/acquisition-2026/edge/psdk-tap/src/platform/usb_bulk_glue.h
 * original sha256: dbb9159cabdda8799ae93e1174b4054bcf8c58361424a87f4217c9275bb203b9
 */
/* edge/psdk-tap/platform — PSDK USB Bulk 핸들러 구현.
 *
 * Manifold3 samples 의 dji_sdk_config.h(edge/third_party/psdk/samples/sample_c/
 * platform/linux/manifold3/application/dji_sdk_config.h:41)는
 * CONFIG_HARDWARE_CONNECTION 을 DJI_USE_ONLY_USB_BULK_DEVICE 로 고정한다 — Manifold3
 * 은 UART 가 아니라 USB Bulk 로 기체와 통신한다. 이 파일이 그 경로를 구현한다.
 *
 * T_DjiHalUsbBulkInfo.isUsbHost 로 두 경로가 갈린다(dji_platform.h:169-174):
 *   - isUsbHost==false: 컴패니언 컴퓨터(Manifold3)가 USB 디바이스(gadget) 쪽이다.
 *     리눅스 FunctionFS 캐릭터 디바이스(/dev/usb-ffs/bulk<N>/ep{1,2})를 open()/
 *     read()/write() 로 직접 다룬다 — libusb 불필요. Manifold3 실기에서 실제로 쓰일
 *     경로로 추정된다(DJI 샘플의 hal_usb_bulk.c 가 이 파일 경로 규칙을 하드코딩한
 *     예시 3개(interfaceNum 2/6/3 → bulk2/bulk6/bulk3)를 실제로 쓰고 있었다 —
 *     edge/third_party/psdk/samples/sample_c/platform/linux/manifold3/hal/hal_usb_bulk.h:57-71.
 *     이 파일은 그 예시들에서 "폴더명 == interfaceNum" 규칙을 일반화했다 — 실기
 *     검증 전이므로 README 에 미검증으로 명시한다.)
 *   - isUsbHost==true: 컴패니언 컴퓨터가 USB 호스트 쪽이다. libusb 로 지정된
 *     vid/pid 장치를 열어 bulk transfer 한다. libusb-1.0 dev 헤더가 빌드 환경에
 *     없으면(LIBUSB_INSTALLED 미정의) 이 경로는 컴파일만 되고 항상
 *     DJI_ERROR_SYSTEM_MODULE_CODE_NONSUPPORT 를 반환하는 스텁이다 — DJI 자신의
 *     샘플(hal_usb_bulk.c)도 동일한 `#ifdef LIBUSB_INSTALLED` 가드를 쓴다(우리가
 *     지어낸 조건부 컴파일이 아니라 벤더 샘플의 기존 관례를 따른 것).
 */
#ifndef KSI_PSDK_TAP_PLATFORM_USB_BULK_GLUE_H
#define KSI_PSDK_TAP_PLATFORM_USB_BULK_GLUE_H

#include <dji_typedef.h>

#ifdef __cplusplus
extern "C" {
#endif

T_DjiReturnCode KsiUsbBulkGlue_Register(void);

#ifdef __cplusplus
}
#endif

#endif /* KSI_PSDK_TAP_PLATFORM_USB_BULK_GLUE_H */
