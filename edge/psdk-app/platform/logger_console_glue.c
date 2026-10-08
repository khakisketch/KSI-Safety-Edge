/* Provenance: DJI PSDK sample-derived MIT glue via SI adaptation (code unchanged).
 * source repo: KSI-Site-Intelligence-Platform@cedeb27eb8aa49b3f2df3414a9a022dd544d5089
 * source path: archive/acquisition-2026/edge/psdk-tap/src/platform/logger_console_glue.c
 * original sha256: 56f2945a7f0d6f651aafaa71ac32db632d65021125a32e2a602210c2ddc4c9a8
 */
#include "logger_console_glue.h"

#include <dji_logger.h>
#include <dji_platform.h>

#include <stdio.h>

static T_DjiReturnCode KsiLoggerConsole_Write(const uint8_t *data, uint16_t dataLen)
{
    fwrite(data, 1, dataLen, stderr);
    return DJI_ERROR_SYSTEM_MODULE_CODE_SUCCESS;
}

T_DjiReturnCode KsiLoggerConsoleGlue_Register(void)
{
    T_DjiLoggerConsole console = {
        .func = KsiLoggerConsole_Write,
        .consoleLevel = DJI_LOGGER_CONSOLE_LOG_LEVEL_INFO,
        .isSupportColor = false,
    };
    return DjiLogger_AddConsole(&console);
}
