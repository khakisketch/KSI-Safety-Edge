/* Provenance: DJI PSDK sample-derived MIT glue via SI adaptation (code unchanged).
 * source repo: KSI-Site-Intelligence-Platform@cedeb27eb8aa49b3f2df3414a9a022dd544d5089
 * source path: archive/acquisition-2026/edge/psdk-tap/src/platform/socket_glue.c
 * original sha256: 11544c928d33ab28385a2696fdbe0e7349093b7e70f96dbcb1a3bdb66e1c94cb
 */
#include "socket_glue.h"

#include <dji_platform.h>

#include <arpa/inet.h>
#include <errno.h>
#include <netinet/in.h>
#include <stdint.h>
#include <string.h>
#include <sys/socket.h>
#include <unistd.h>

/* fd 를 malloc 없이 handle 로 직접 인코딩한다 — fd 는 작은 정수라 포인터 폭에 항상
 * 들어간다. 소켓 하나마다 별도 힙 블록을 만드는 것보다 단순하고 leak 여지가 없다. */
static T_DjiSocketHandle FdToHandle(int fd)
{
    return (T_DjiSocketHandle) (intptr_t) fd;
}

static int HandleToFd(T_DjiSocketHandle handle)
{
    return (int) (intptr_t) handle;
}

static T_DjiReturnCode KsiSocket_Socket(E_DjiSocketMode mode, T_DjiSocketHandle *socketHandle)
{
    int type = (mode == DJI_SOCKET_MODE_UDP) ? SOCK_DGRAM : SOCK_STREAM;
    int fd = socket(AF_INET, type, 0);
    if (fd < 0) {
        return DJI_ERROR_SYSTEM_MODULE_CODE_SYSTEM_ERROR;
    }
    *socketHandle = FdToHandle(fd);
    return DJI_ERROR_SYSTEM_MODULE_CODE_SUCCESS;
}

static T_DjiReturnCode KsiSocket_Close(T_DjiSocketHandle socketHandle)
{
    if (close(HandleToFd(socketHandle)) != 0) {
        return DJI_ERROR_SYSTEM_MODULE_CODE_SYSTEM_ERROR;
    }
    return DJI_ERROR_SYSTEM_MODULE_CODE_SUCCESS;
}

static T_DjiReturnCode KsiSocket_Bind(T_DjiSocketHandle socketHandle, const char *ipAddr, uint32_t port)
{
    struct sockaddr_in addr;
    memset(&addr, 0, sizeof(addr));
    addr.sin_family = AF_INET;
    addr.sin_port = htons((uint16_t) port);
    if (ipAddr == NULL || ipAddr[0] == '\0') {
        addr.sin_addr.s_addr = htonl(INADDR_ANY);
    } else if (inet_pton(AF_INET, ipAddr, &addr.sin_addr) != 1) {
        return DJI_ERROR_SYSTEM_MODULE_CODE_INVALID_PARAMETER;
    }
    if (bind(HandleToFd(socketHandle), (struct sockaddr *) &addr, sizeof(addr)) != 0) {
        return DJI_ERROR_SYSTEM_MODULE_CODE_SYSTEM_ERROR;
    }
    return DJI_ERROR_SYSTEM_MODULE_CODE_SUCCESS;
}

static T_DjiReturnCode KsiSocket_UdpSendData(T_DjiSocketHandle socketHandle, const char *ipAddr, uint32_t port,
                                              const uint8_t *buf, uint32_t len, uint32_t *realLen)
{
    struct sockaddr_in addr;
    memset(&addr, 0, sizeof(addr));
    addr.sin_family = AF_INET;
    addr.sin_port = htons((uint16_t) port);
    if (inet_pton(AF_INET, ipAddr, &addr.sin_addr) != 1) {
        return DJI_ERROR_SYSTEM_MODULE_CODE_INVALID_PARAMETER;
    }
    ssize_t sent = sendto(HandleToFd(socketHandle), buf, len, 0, (struct sockaddr *) &addr, sizeof(addr));
    if (sent < 0) {
        *realLen = 0;
        return DJI_ERROR_SYSTEM_MODULE_CODE_SYSTEM_ERROR;
    }
    *realLen = (uint32_t) sent;
    return DJI_ERROR_SYSTEM_MODULE_CODE_SUCCESS;
}

static T_DjiReturnCode KsiSocket_UdpRecvData(T_DjiSocketHandle socketHandle, char *ipAddr, uint32_t *port,
                                              uint8_t *buf, uint32_t len, uint32_t *realLen)
{
    struct sockaddr_in addr;
    socklen_t addr_len = sizeof(addr);
    ssize_t received = recvfrom(HandleToFd(socketHandle), buf, len, 0, (struct sockaddr *) &addr, &addr_len);
    if (received < 0) {
        *realLen = 0;
        return DJI_ERROR_SYSTEM_MODULE_CODE_SYSTEM_ERROR;
    }
    if (ipAddr != NULL) {
        inet_ntop(AF_INET, &addr.sin_addr, ipAddr, DJI_IP_ADDR_STR_SIZE_MAX);
    }
    if (port != NULL) {
        *port = ntohs(addr.sin_port);
    }
    *realLen = (uint32_t) received;
    return DJI_ERROR_SYSTEM_MODULE_CODE_SUCCESS;
}

static T_DjiReturnCode KsiSocket_TcpListen(T_DjiSocketHandle socketHandle)
{
    if (listen(HandleToFd(socketHandle), 1) != 0) {
        return DJI_ERROR_SYSTEM_MODULE_CODE_SYSTEM_ERROR;
    }
    return DJI_ERROR_SYSTEM_MODULE_CODE_SUCCESS;
}

static T_DjiReturnCode KsiSocket_TcpAccept(T_DjiSocketHandle socketHandle, char *ipAddr, uint32_t *port,
                                            T_DjiSocketHandle *outSocketHandle)
{
    struct sockaddr_in addr;
    socklen_t addr_len = sizeof(addr);
    int client_fd = accept(HandleToFd(socketHandle), (struct sockaddr *) &addr, &addr_len);
    if (client_fd < 0) {
        return DJI_ERROR_SYSTEM_MODULE_CODE_SYSTEM_ERROR;
    }
    if (ipAddr != NULL) {
        inet_ntop(AF_INET, &addr.sin_addr, ipAddr, DJI_IP_ADDR_STR_SIZE_MAX);
    }
    if (port != NULL) {
        *port = ntohs(addr.sin_port);
    }
    *outSocketHandle = FdToHandle(client_fd);
    return DJI_ERROR_SYSTEM_MODULE_CODE_SUCCESS;
}

static T_DjiReturnCode KsiSocket_TcpConnect(T_DjiSocketHandle socketHandle, const char *ipAddr, uint32_t port)
{
    struct sockaddr_in addr;
    memset(&addr, 0, sizeof(addr));
    addr.sin_family = AF_INET;
    addr.sin_port = htons((uint16_t) port);
    if (inet_pton(AF_INET, ipAddr, &addr.sin_addr) != 1) {
        return DJI_ERROR_SYSTEM_MODULE_CODE_INVALID_PARAMETER;
    }
    if (connect(HandleToFd(socketHandle), (struct sockaddr *) &addr, sizeof(addr)) != 0) {
        return DJI_ERROR_SYSTEM_MODULE_CODE_SYSTEM_ERROR;
    }
    return DJI_ERROR_SYSTEM_MODULE_CODE_SUCCESS;
}

static T_DjiReturnCode KsiSocket_TcpSendData(T_DjiSocketHandle socketHandle,
                                              const uint8_t *buf, uint32_t len, uint32_t *realLen)
{
    ssize_t sent = send(HandleToFd(socketHandle), buf, len, 0);
    if (sent < 0) {
        *realLen = 0;
        return DJI_ERROR_SYSTEM_MODULE_CODE_SYSTEM_ERROR;
    }
    *realLen = (uint32_t) sent;
    return DJI_ERROR_SYSTEM_MODULE_CODE_SUCCESS;
}

static T_DjiReturnCode KsiSocket_TcpRecvData(T_DjiSocketHandle socketHandle,
                                              uint8_t *buf, uint32_t len, uint32_t *realLen)
{
    ssize_t received = recv(HandleToFd(socketHandle), buf, len, 0);
    if (received < 0) {
        *realLen = 0;
        return DJI_ERROR_SYSTEM_MODULE_CODE_SYSTEM_ERROR;
    }
    *realLen = (uint32_t) received;
    return DJI_ERROR_SYSTEM_MODULE_CODE_SUCCESS;
}

T_DjiReturnCode KsiSocketGlue_Register(void)
{
    T_DjiSocketHandler handler = {
        .Socket = KsiSocket_Socket,
        .Close = KsiSocket_Close,
        .Bind = KsiSocket_Bind,
        .UdpSendData = KsiSocket_UdpSendData,
        .UdpRecvData = KsiSocket_UdpRecvData,
        .TcpListen = KsiSocket_TcpListen,
        .TcpAccept = KsiSocket_TcpAccept,
        .TcpConnect = KsiSocket_TcpConnect,
        .TcpSendData = KsiSocket_TcpSendData,
        .TcpRecvData = KsiSocket_TcpRecvData,
    };
    return DjiPlatform_RegSocketHandler(&handler);
}
