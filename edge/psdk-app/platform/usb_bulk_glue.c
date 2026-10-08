/* Provenance: DJI PSDK sample-derived MIT glue via SI adaptation (code unchanged).
 * source repo: KSI-Site-Intelligence-Platform@cedeb27eb8aa49b3f2df3414a9a022dd544d5089
 * source path: archive/acquisition-2026/edge/psdk-tap/src/platform/usb_bulk_glue.c
 * original sha256: 61bb7d5dada66a3608deca4def68ae9c58c61697e379f0ed228032bbf87ec65a
 */
#include "usb_bulk_glue.h"

#include <dji_platform.h>

#ifdef LIBUSB_INSTALLED
#include <libusb-1.0/libusb.h>
#endif

#include <errno.h>
#include <fcntl.h>
#include <pthread.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

/* Manifold3/M4-계열 USB gadget 이 노출하는 것으로 관측된 고정 VID/PID·채널 표.
 * 원 출처: edge/third_party/psdk/samples/sample_c/platform/linux/manifold3/hal/
 * hal_usb_bulk.h (LINUX_USB_VID/PID, LINUX_USB_BULK{1,2,3}_* 매크로) — 벤더 샘플의
 * 하드코딩된 상수를 그대로 옮긴 값이었다.
 *
 * **2026-08-12 실기로 재검증, 벤더 값과 실측이 어긋남을 확인**: 벤더 샘플은
 * interfaceNum={2,6,3} 을 문서화하지만, 이 세션의 실기(`ls /dev/usb-ffs/`)에는
 * bulk2·bulk3·bulk4·bulk5 만 존재하고 **bulk6 은 없다**(오늘 RC 경유 펌웨어 갱신
 * 이후 채널 번호가 바뀐 것으로 추정 — 확인 필요, 갱신 전 상태는 관측 못 함).
 * interfaceNum=6 을 그대로 두면 InitDeviceModeChannel 의 open("/dev/usb-ffs/bulk6/…")
 * 이 ENOENT 로 실패한다. 실기에 실제로 존재하는 4개 채널 중 벤더 표에 없던 여분
 * (bulk4 또는 bulk5) 중 어느 쪽이 옛 "BULK2"(widget/HMS 등 2번째 논리채널) 대응인지는
 * 이 세션에서 확정하지 못했다 — bulk4 로 우선 시도한다(순번상 가장 가능성 높은 후보,
 * 실기 재검증 필요는 여전히 유효, README 참조). */
#define KSI_USB_VID 0x2CA3u
#define KSI_USB_PID 0x3181u

static const T_DjiHalUsbBulkChannelInfo kKnownManifold3Channels[DJI_HAL_USB_BULK_NUM_MAX] = {
    {.interfaceNum = 2, .endPointIn = 0x83, .endPointOut = 0x02},
    {.interfaceNum = 4, .endPointIn = 0x87, .endPointOut = 0x06},
    {.interfaceNum = 3, .endPointIn = 0x84, .endPointOut = 0x03},
};

typedef struct {
    bool is_usb_host;
    int ep_in_fd;  /* device 모드(FunctionFS)에서만 유효 */
    int ep_out_fd; /* device 모드(FunctionFS)에서만 유효 */
    /* g_active_channels_mutex 로 보호되는 in-flight WriteData/ReadData 호출 수.
     * DeInit 은 채널을 배열에서 뺀 뒤 이 값이 0 이 될 때까지 기다렸다가 free() 한다
     * (아래 AcquireActiveChannel/ReleaseActiveChannel/UnregisterAndDrainChannel 참조) —
     * blocking I/O(write/read, libusb_bulk_transfer(timeout=0)) 도중 DeInit 이 같은
     * 핸들을 free() 하는 use-after-free 창을 없앤다. */
    int refcount;
#ifdef LIBUSB_INSTALLED
    libusb_context *usb_context;         /* host 모드에서만 유효 */
    libusb_device_handle *usb_handle;    /* host 모드에서만 유효 */
    int interface_num;
    uint8_t endpoint_in;
    uint8_t endpoint_out;
#endif
} KsiUsbBulkChannel;

/* 호스트 실측(2026-08-04, 하드웨어 없는 aarch64 워크스테이션)에서 실제로 관측한 문제:
 * UsbBulkInit 이 실패(SYSTEM_ERROR)를 반환했는데도 PSDK 코어(DjiAccessAdapter_Init →
 * DjiAccessAdapter_IdentifyAircraftSeries)가 곧이어 UsbBulkWriteData 를 호출했고, 그때
 * 넘어온 usbBulkHandle 이 우리가 절대 반환한 적 없는 값이었다(Init 실패 경로에서는
 * *usbBulkHandle 을 건드리지 않고 그냥 return 하므로, PSDK 내부의 미초기화 메모리가
 * 그대로 넘어온 것으로 보인다) — 그 값을 그대로 역참조해 SIGSEGV 로 죽었다(gdb 백트레이스
 * 확보, README 참조). PSDK 자체(.a 블롭)의 실패 경로 처리를 우리가 고칠 수는 없으니,
 * 우리 경계에서 "우리가 실제로 내준 핸들인가"를 확인하는 방어선을 둔다 — 모르는 핸들이면
 * 역참조하지 않고 INVALID_PARAMETER 로 정직하게 실패한다(세그폴트 대신). */
#define KSI_USB_BULK_MAX_ACTIVE_CHANNELS 8
static KsiUsbBulkChannel *g_active_channels[KSI_USB_BULK_MAX_ACTIVE_CHANNELS];
static pthread_mutex_t g_active_channels_mutex = PTHREAD_MUTEX_INITIALIZER;
/* refcount 가 0 으로 드레인될 때마다 broadcast 된다 — DeInit 이 기다리는 신호.
 * 항상 g_active_channels_mutex 를 잡은 채로 wait/signal 한다(모든 갱신이 이미
 * 그 락 아래에 있으므로 별도 락은 필요 없다). */
static pthread_cond_t g_active_channels_drained = PTHREAD_COND_INITIALIZER;

static bool RegisterActiveChannel(KsiUsbBulkChannel *channel)
{
    bool registered = false;
    pthread_mutex_lock(&g_active_channels_mutex);
    for (int i = 0; i < KSI_USB_BULK_MAX_ACTIVE_CHANNELS; ++i) {
        if (g_active_channels[i] == NULL) {
            g_active_channels[i] = channel;
            registered = true;
            break;
        }
    }
    pthread_mutex_unlock(&g_active_channels_mutex);
    return registered;
}

/* 배열에서 channel 을 찾으면 그 자리에서 즉시 refcount++ 하고 포인터를 반환한다
 * ("살아있는 핸들인지 확인"과 "그 핸들을 계속 쓰겠다고 예약"을 한 락 구간에서
 * 원자적으로 처리 — WriteData/ReadData 전용). DeInit 이 이미 배열에서 뺀 뒤라면
 * (드레인 대기 중이거나 free 이후) NULL 을 반환하므로 호출자는 절대 역참조하지
 * 않는다. */
static KsiUsbBulkChannel *AcquireActiveChannel(const void *handle)
{
    KsiUsbBulkChannel *found = NULL;
    pthread_mutex_lock(&g_active_channels_mutex);
    for (int i = 0; i < KSI_USB_BULK_MAX_ACTIVE_CHANNELS; ++i) {
        if (g_active_channels[i] == handle) {
            found = g_active_channels[i];
            found->refcount++;
            break;
        }
    }
    pthread_mutex_unlock(&g_active_channels_mutex);
    return found;
}

/* AcquireActiveChannel 로 얻은 참조를 반납한다. WriteData/ReadData 는 블로킹
 * I/O 가 끝난 뒤(성공/실패 무관) 반드시 이걸 호출해야 DeInit 의 드레인 대기가
 * 풀린다. */
static void ReleaseActiveChannel(KsiUsbBulkChannel *channel)
{
    pthread_mutex_lock(&g_active_channels_mutex);
    channel->refcount--;
    if (channel->refcount == 0) {
        pthread_cond_broadcast(&g_active_channels_drained);
    }
    pthread_mutex_unlock(&g_active_channels_mutex);
}

/* DeInit 전용: channel 을 배열에서 빼서 새 Acquire 를 더 이상 못 받게 막은 뒤,
 * 이미 진행 중인(블로킹 I/O 안에 있는) 호출들의 refcount 가 0 으로 드레인될
 * 때까지 같은 락 아래에서 pthread_cond_wait 로 기다린다. 반환값이 false 면
 * handle 이 애초에 활성 채널이 아니었다는 뜻 — 호출자는 free() 하면 안 된다. */
static bool UnregisterAndDrainChannel(KsiUsbBulkChannel *channel)
{
    bool found = false;
    pthread_mutex_lock(&g_active_channels_mutex);
    for (int i = 0; i < KSI_USB_BULK_MAX_ACTIVE_CHANNELS; ++i) {
        if (g_active_channels[i] == channel) {
            g_active_channels[i] = NULL;
            found = true;
            break;
        }
    }
    if (found) {
        while (channel->refcount > 0) {
            pthread_cond_wait(&g_active_channels_drained, &g_active_channels_mutex);
        }
    }
    pthread_mutex_unlock(&g_active_channels_mutex);
    return found;
}

static T_DjiReturnCode InitDeviceModeChannel(KsiUsbBulkChannel *channel, uint16_t interfaceNum)
{
    char in_path[64];
    char out_path[64];
    /* 폴더명 == interfaceNum, ep2=IN, ep1=OUT — 벤더 샘플의 세 하드코딩 예시에서
     * 관측된 규칙을 일반화한 것(usb_bulk_glue.h 상단 주석 참조). */
    snprintf(in_path, sizeof(in_path), "/dev/usb-ffs/bulk%u/ep2", (unsigned) interfaceNum);
    snprintf(out_path, sizeof(out_path), "/dev/usb-ffs/bulk%u/ep1", (unsigned) interfaceNum);

    channel->ep_in_fd = open(in_path, O_RDWR);
    if (channel->ep_in_fd < 0) {
        return DJI_ERROR_SYSTEM_MODULE_CODE_SYSTEM_ERROR;
    }
    channel->ep_out_fd = open(out_path, O_RDWR);
    if (channel->ep_out_fd < 0) {
        close(channel->ep_in_fd);
        channel->ep_in_fd = -1;
        return DJI_ERROR_SYSTEM_MODULE_CODE_SYSTEM_ERROR;
    }
    return DJI_ERROR_SYSTEM_MODULE_CODE_SUCCESS;
}

#ifdef LIBUSB_INSTALLED
static T_DjiReturnCode InitHostModeChannel(KsiUsbBulkChannel *channel, T_DjiHalUsbBulkInfo info)
{
    if (libusb_init(&channel->usb_context) != 0) {
        return DJI_ERROR_SYSTEM_MODULE_CODE_SYSTEM_ERROR;
    }
    channel->usb_handle = libusb_open_device_with_vid_pid(channel->usb_context, info.vid, info.pid);
    if (channel->usb_handle == NULL) {
        libusb_exit(channel->usb_context);
        return DJI_ERROR_SYSTEM_MODULE_CODE_SYSTEM_ERROR;
    }
    channel->interface_num = info.channelInfo.interfaceNum;
    channel->endpoint_in = (uint8_t) info.channelInfo.endPointIn;
    channel->endpoint_out = (uint8_t) info.channelInfo.endPointOut;
    if (libusb_claim_interface(channel->usb_handle, channel->interface_num) != 0) {
        libusb_close(channel->usb_handle);
        libusb_exit(channel->usb_context);
        return DJI_ERROR_SYSTEM_MODULE_CODE_SYSTEM_ERROR;
    }
    return DJI_ERROR_SYSTEM_MODULE_CODE_SUCCESS;
}
#endif

static T_DjiReturnCode KsiUsbBulk_Init(T_DjiHalUsbBulkInfo usbBulkInfo, T_DjiUsbBulkHandle *usbBulkHandle)
{
    KsiUsbBulkChannel *channel = calloc(1, sizeof(KsiUsbBulkChannel));
    if (channel == NULL) {
        return DJI_ERROR_SYSTEM_MODULE_CODE_MEMORY_ALLOC_FAILED;
    }
    channel->ep_in_fd = -1;
    channel->ep_out_fd = -1;
    channel->is_usb_host = usbBulkInfo.isUsbHost;

    T_DjiReturnCode result;
    if (!usbBulkInfo.isUsbHost) {
        result = InitDeviceModeChannel(channel, usbBulkInfo.channelInfo.interfaceNum);
    } else {
#ifdef LIBUSB_INSTALLED
        result = InitHostModeChannel(channel, usbBulkInfo);
#else
        /* libusb-1.0 dev 헤더가 빌드 환경에 없다 — 지어낸 성공을 반환하지 않는다.
         * README 의 "libusb 없이 호스트 검증" 절 참조. */
        result = DJI_ERROR_SYSTEM_MODULE_CODE_NONSUPPORT;
#endif
    }

    if (result != DJI_ERROR_SYSTEM_MODULE_CODE_SUCCESS) {
        free(channel);
        return result;
    }
    if (!RegisterActiveChannel(channel)) {
        /* 동시 채널이 KSI_USB_BULK_MAX_ACTIVE_CHANNELS 를 넘는 비정상 상황 — 실제로는
         * Manifold3 이 채널 3개(DJI_HAL_USB_BULK_NUM_MAX)만 쓰므로 발생하지 않아야
         * 한다. 조용히 반쪽짜리 핸들을 내주지 않고 자원을 정리한 뒤 실패시킨다. */
        if (!channel->is_usb_host) {
            close(channel->ep_in_fd);
            close(channel->ep_out_fd);
        }
#ifdef LIBUSB_INSTALLED
        else if (channel->usb_handle != NULL) {
            libusb_release_interface(channel->usb_handle, channel->interface_num);
            libusb_close(channel->usb_handle);
            libusb_exit(channel->usb_context);
        }
#endif
        free(channel);
        return DJI_ERROR_SYSTEM_MODULE_CODE_MEMORY_ALLOC_FAILED;
    }
    *usbBulkHandle = channel;
    return DJI_ERROR_SYSTEM_MODULE_CODE_SUCCESS;
}

static T_DjiReturnCode KsiUsbBulk_DeInit(T_DjiUsbBulkHandle usbBulkHandle)
{
    if (usbBulkHandle == NULL) {
        return DJI_ERROR_SYSTEM_MODULE_CODE_INVALID_PARAMETER;
    }
    KsiUsbBulkChannel *channel = (KsiUsbBulkChannel *) usbBulkHandle;
    /* 배열에서 빼고, 이미 진행 중인 WriteData/ReadData 가 다 끝날 때까지(refcount==0)
     * 기다린 뒤에야 아래에서 fd 를 닫고 free() 한다 — 그 전에는 이 핸들을 참조하는
     * 다른 스레드의 블로킹 I/O 가 살아있을 수 있다. */
    if (!UnregisterAndDrainChannel(channel)) {
        return DJI_ERROR_SYSTEM_MODULE_CODE_INVALID_PARAMETER;
    }
    if (!channel->is_usb_host) {
        if (channel->ep_in_fd >= 0) {
            close(channel->ep_in_fd);
        }
        if (channel->ep_out_fd >= 0) {
            close(channel->ep_out_fd);
        }
    }
#ifdef LIBUSB_INSTALLED
    else {
        if (channel->usb_handle != NULL) {
            libusb_release_interface(channel->usb_handle, channel->interface_num);
            libusb_close(channel->usb_handle);
        }
        if (channel->usb_context != NULL) {
            libusb_exit(channel->usb_context);
        }
    }
#endif
    free(channel);
    return DJI_ERROR_SYSTEM_MODULE_CODE_SUCCESS;
}

static T_DjiReturnCode KsiUsbBulk_WriteData(T_DjiUsbBulkHandle usbBulkHandle, const uint8_t *buf, uint32_t len,
                                             uint32_t *realLen)
{
    /* AcquireActiveChannel 이 "활성 채널인지 확인"과 "refcount++ 로 이 호출이 끝날
     * 때까지 DeInit 의 free() 를 막기" 를 한 락 구간에서 원자적으로 한다 — 확인과
     * 역참조 사이에 DeInit 이 끼어들 틈이 없다(위 usb_bulk-uaf 주석 참조). */
    KsiUsbBulkChannel *channel = AcquireActiveChannel(usbBulkHandle);
    if (channel == NULL) {
        /* 우리가 내준 적 없는(또는 이미 DeInit 중인) 핸들 — 역참조하지 않고
         * 정직하게 실패한다. */
        *realLen = 0;
        return DJI_ERROR_SYSTEM_MODULE_CODE_INVALID_PARAMETER;
    }
    T_DjiReturnCode result;
    if (!channel->is_usb_host) {
        ssize_t written = write(channel->ep_out_fd, buf, len);
        if (written < 0) {
            *realLen = 0;
            result = DJI_ERROR_SYSTEM_MODULE_CODE_SYSTEM_ERROR;
        } else {
            *realLen = (uint32_t) written;
            result = DJI_ERROR_SYSTEM_MODULE_CODE_SUCCESS;
        }
    } else {
#ifdef LIBUSB_INSTALLED
        int transferred = 0;
        int rc = libusb_bulk_transfer(channel->usb_handle, channel->endpoint_out, (unsigned char *) buf,
                                       (int) len, &transferred, 0);
        if (rc != 0) {
            *realLen = 0;
            result = DJI_ERROR_SYSTEM_MODULE_CODE_SYSTEM_ERROR;
        } else {
            *realLen = (uint32_t) transferred;
            result = DJI_ERROR_SYSTEM_MODULE_CODE_SUCCESS;
        }
#else
        *realLen = 0;
        result = DJI_ERROR_SYSTEM_MODULE_CODE_NONSUPPORT;
#endif
    }
    ReleaseActiveChannel(channel);
    return result;
}

static T_DjiReturnCode KsiUsbBulk_ReadData(T_DjiUsbBulkHandle usbBulkHandle, uint8_t *buf, uint32_t len,
                                            uint32_t *realLen)
{
    KsiUsbBulkChannel *channel = AcquireActiveChannel(usbBulkHandle);
    if (channel == NULL) {
        *realLen = 0;
        return DJI_ERROR_SYSTEM_MODULE_CODE_INVALID_PARAMETER;
    }
    T_DjiReturnCode result;
    if (!channel->is_usb_host) {
        ssize_t bytes_read = read(channel->ep_in_fd, buf, len);
        if (bytes_read < 0) {
            *realLen = 0;
            result = DJI_ERROR_SYSTEM_MODULE_CODE_SYSTEM_ERROR;
        } else {
            *realLen = (uint32_t) bytes_read;
            result = DJI_ERROR_SYSTEM_MODULE_CODE_SUCCESS;
        }
    } else {
#ifdef LIBUSB_INSTALLED
        int transferred = 0;
        int rc = libusb_bulk_transfer(channel->usb_handle, channel->endpoint_in, buf, (int) len, &transferred, 0);
        if (rc != 0) {
            *realLen = 0;
            result = DJI_ERROR_SYSTEM_MODULE_CODE_SYSTEM_ERROR;
        } else {
            *realLen = (uint32_t) transferred;
            result = DJI_ERROR_SYSTEM_MODULE_CODE_SUCCESS;
        }
#else
        *realLen = 0;
        result = DJI_ERROR_SYSTEM_MODULE_CODE_NONSUPPORT;
#endif
    }
    ReleaseActiveChannel(channel);
    return result;
}

static T_DjiReturnCode KsiUsbBulk_GetDeviceInfo(T_DjiHalUsbBulkDeviceInfo *deviceInfo)
{
    if (deviceInfo == NULL) {
        return DJI_ERROR_SYSTEM_MODULE_CODE_INVALID_PARAMETER;
    }
    deviceInfo->vid = KSI_USB_VID;
    deviceInfo->pid = KSI_USB_PID;
    memcpy(deviceInfo->channelInfo, kKnownManifold3Channels, sizeof(kKnownManifold3Channels));
    return DJI_ERROR_SYSTEM_MODULE_CODE_SUCCESS;
}

T_DjiReturnCode KsiUsbBulkGlue_Register(void)
{
    T_DjiHalUsbBulkHandler handler = {
        .UsbBulkInit = KsiUsbBulk_Init,
        .UsbBulkDeInit = KsiUsbBulk_DeInit,
        .UsbBulkWriteData = KsiUsbBulk_WriteData,
        .UsbBulkReadData = KsiUsbBulk_ReadData,
        .UsbBulkGetDeviceInfo = KsiUsbBulk_GetDeviceInfo,
    };
    return DjiPlatform_RegHalUsbBulkHandler(&handler);
}
