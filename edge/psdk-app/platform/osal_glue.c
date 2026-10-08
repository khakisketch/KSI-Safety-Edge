/* Provenance: DJI PSDK sample-derived MIT glue via SI adaptation (code unchanged).
 * source repo: KSI-Site-Intelligence-Platform@cedeb27eb8aa49b3f2df3414a9a022dd544d5089
 * source path: archive/acquisition-2026/edge/psdk-tap/src/platform/osal_glue.c
 * original sha256: a662e44a4981155f33e5dc2f2c8f370c0bf108115fb42424473c781b35bb4db7
 */
#include "osal_glue.h"

#include <dji_platform.h>

#include <errno.h>
#include <pthread.h>
#include <semaphore.h>
#include <stdlib.h>
#include <string.h>
#include <sys/random.h>
#include <time.h>

/* pthread_setname_np 는 null 종료 포함 16바이트까지만 허용한다(TASK_COMM_LEN). */
#define KSI_OSAL_TASK_NAME_MAX 16

typedef struct {
    pthread_t thread;
} KsiOsalTaskHandle;

static T_DjiReturnCode KsiOsal_TaskCreate(const char *name, void *(*taskFunc)(void *),
                                           uint32_t stackSize, void *arg, T_DjiTaskHandle *task)
{
    KsiOsalTaskHandle *handle = malloc(sizeof(KsiOsalTaskHandle));
    if (handle == NULL) {
        return DJI_ERROR_SYSTEM_MODULE_CODE_MEMORY_ALLOC_FAILED;
    }

    pthread_attr_t attr;
    if (pthread_attr_init(&attr) != 0) {
        free(handle);
        return DJI_ERROR_SYSTEM_MODULE_CODE_SYSTEM_ERROR;
    }
    if (stackSize > 0) {
        /* PTHREAD_STACK_MIN 미만이면 glibc 가 알아서 실패시킨다 — 여기서 강제로 올리지 않는다. */
        pthread_attr_setstacksize(&attr, (size_t) stackSize);
    }

    int rc = pthread_create(&handle->thread, &attr, taskFunc, arg);
    pthread_attr_destroy(&attr);
    if (rc != 0) {
        free(handle);
        return DJI_ERROR_SYSTEM_MODULE_CODE_SYSTEM_ERROR;
    }

    if (name != NULL) {
        char truncated[KSI_OSAL_TASK_NAME_MAX];
        strncpy(truncated, name, KSI_OSAL_TASK_NAME_MAX - 1);
        truncated[KSI_OSAL_TASK_NAME_MAX - 1] = '\0';
        /* 이름 설정 실패는 치명적이지 않다 — 진단 편의 기능일 뿐이라 반환값을 무시한다. */
        (void) pthread_setname_np(handle->thread, truncated);
    }

    *task = handle;
    return DJI_ERROR_SYSTEM_MODULE_CODE_SUCCESS;
}

static T_DjiReturnCode KsiOsal_TaskDestroy(T_DjiTaskHandle task)
{
    if (task == NULL) {
        return DJI_ERROR_SYSTEM_MODULE_CODE_INVALID_PARAMETER;
    }
    KsiOsalTaskHandle *handle = (KsiOsalTaskHandle *) task;
    /* 이 경로는 실기 미검증이다(README 참조) — 정상 종료를 우선 시도하고, 그래도
     * 남아있으면 취소한다. 둘 다 최선 노력(best-effort)이며 실패해도 리소스는 해제한다. */
    pthread_cancel(handle->thread);
    pthread_join(handle->thread, NULL);
    free(handle);
    return DJI_ERROR_SYSTEM_MODULE_CODE_SUCCESS;
}

static T_DjiReturnCode KsiOsal_TaskSleepMs(uint32_t timeMs)
{
    struct timespec ts;
    ts.tv_sec = (time_t) (timeMs / 1000);
    ts.tv_nsec = (long) (timeMs % 1000) * 1000000L;
    struct timespec remaining = ts;
    while (nanosleep(&remaining, &remaining) != 0) {
        if (errno != EINTR) {
            return DJI_ERROR_SYSTEM_MODULE_CODE_SYSTEM_ERROR;
        }
    }
    return DJI_ERROR_SYSTEM_MODULE_CODE_SUCCESS;
}

static T_DjiReturnCode KsiOsal_MutexCreate(T_DjiMutexHandle *mutex)
{
    pthread_mutex_t *handle = malloc(sizeof(pthread_mutex_t));
    if (handle == NULL) {
        return DJI_ERROR_SYSTEM_MODULE_CODE_MEMORY_ALLOC_FAILED;
    }
    if (pthread_mutex_init(handle, NULL) != 0) {
        free(handle);
        return DJI_ERROR_SYSTEM_MODULE_CODE_SYSTEM_ERROR;
    }
    *mutex = handle;
    return DJI_ERROR_SYSTEM_MODULE_CODE_SUCCESS;
}

static T_DjiReturnCode KsiOsal_MutexDestroy(T_DjiMutexHandle mutex)
{
    if (mutex == NULL) {
        return DJI_ERROR_SYSTEM_MODULE_CODE_INVALID_PARAMETER;
    }
    pthread_mutex_destroy((pthread_mutex_t *) mutex);
    free(mutex);
    return DJI_ERROR_SYSTEM_MODULE_CODE_SUCCESS;
}

static T_DjiReturnCode KsiOsal_MutexLock(T_DjiMutexHandle mutex)
{
    if (pthread_mutex_lock((pthread_mutex_t *) mutex) != 0) {
        return DJI_ERROR_SYSTEM_MODULE_CODE_SYSTEM_ERROR;
    }
    return DJI_ERROR_SYSTEM_MODULE_CODE_SUCCESS;
}

static T_DjiReturnCode KsiOsal_MutexUnlock(T_DjiMutexHandle mutex)
{
    if (pthread_mutex_unlock((pthread_mutex_t *) mutex) != 0) {
        return DJI_ERROR_SYSTEM_MODULE_CODE_SYSTEM_ERROR;
    }
    return DJI_ERROR_SYSTEM_MODULE_CODE_SUCCESS;
}

static T_DjiReturnCode KsiOsal_SemaphoreCreate(uint32_t initValue, T_DjiSemaHandle *semaphore)
{
    sem_t *handle = malloc(sizeof(sem_t));
    if (handle == NULL) {
        return DJI_ERROR_SYSTEM_MODULE_CODE_MEMORY_ALLOC_FAILED;
    }
    if (sem_init(handle, 0, initValue) != 0) {
        free(handle);
        return DJI_ERROR_SYSTEM_MODULE_CODE_SYSTEM_ERROR;
    }
    *semaphore = handle;
    return DJI_ERROR_SYSTEM_MODULE_CODE_SUCCESS;
}

static T_DjiReturnCode KsiOsal_SemaphoreDestroy(T_DjiSemaHandle semaphore)
{
    if (semaphore == NULL) {
        return DJI_ERROR_SYSTEM_MODULE_CODE_INVALID_PARAMETER;
    }
    sem_destroy((sem_t *) semaphore);
    free(semaphore);
    return DJI_ERROR_SYSTEM_MODULE_CODE_SUCCESS;
}

static T_DjiReturnCode KsiOsal_SemaphoreWait(T_DjiSemaHandle semaphore)
{
    int rc;
    do {
        rc = sem_wait((sem_t *) semaphore);
    } while (rc != 0 && errno == EINTR);
    return rc == 0 ? DJI_ERROR_SYSTEM_MODULE_CODE_SUCCESS : DJI_ERROR_SYSTEM_MODULE_CODE_SYSTEM_ERROR;
}

static T_DjiReturnCode KsiOsal_SemaphoreTimedWait(T_DjiSemaHandle semaphore, uint32_t waitTimeMs)
{
    struct timespec deadline;
    /* sem_timedwait 는 CLOCK_REALTIME 절대시각을 요구한다(POSIX 고정 — CLOCK_MONOTONIC 로
     * 바꾸는 옵션이 없다). */
    clock_gettime(CLOCK_REALTIME, &deadline);
    deadline.tv_sec += (time_t) (waitTimeMs / 1000);
    deadline.tv_nsec += (long) (waitTimeMs % 1000) * 1000000L;
    if (deadline.tv_nsec >= 1000000000L) {
        deadline.tv_sec += 1;
        deadline.tv_nsec -= 1000000000L;
    }

    int rc;
    do {
        rc = sem_timedwait((sem_t *) semaphore, &deadline);
    } while (rc != 0 && errno == EINTR);
    if (rc == 0) {
        return DJI_ERROR_SYSTEM_MODULE_CODE_SUCCESS;
    }
    if (errno == ETIMEDOUT) {
        return DJI_ERROR_SYSTEM_MODULE_CODE_TIMEOUT;
    }
    return DJI_ERROR_SYSTEM_MODULE_CODE_SYSTEM_ERROR;
}

static T_DjiReturnCode KsiOsal_SemaphorePost(T_DjiSemaHandle semaphore)
{
    if (sem_post((sem_t *) semaphore) != 0) {
        return DJI_ERROR_SYSTEM_MODULE_CODE_SYSTEM_ERROR;
    }
    return DJI_ERROR_SYSTEM_MODULE_CODE_SUCCESS;
}

static T_DjiReturnCode KsiOsal_GetTimeMs(uint32_t *ms)
{
    struct timespec ts;
    if (clock_gettime(CLOCK_MONOTONIC, &ts) != 0) {
        return DJI_ERROR_SYSTEM_MODULE_CODE_SYSTEM_ERROR;
    }
    *ms = (uint32_t) ((int64_t) ts.tv_sec * 1000 + ts.tv_nsec / 1000000);
    return DJI_ERROR_SYSTEM_MODULE_CODE_SUCCESS;
}

static T_DjiReturnCode KsiOsal_GetTimeUs(uint64_t *us)
{
    struct timespec ts;
    if (clock_gettime(CLOCK_MONOTONIC, &ts) != 0) {
        return DJI_ERROR_SYSTEM_MODULE_CODE_SYSTEM_ERROR;
    }
    *us = (uint64_t) ((int64_t) ts.tv_sec * 1000000 + ts.tv_nsec / 1000);
    return DJI_ERROR_SYSTEM_MODULE_CODE_SUCCESS;
}

static T_DjiReturnCode KsiOsal_GetRandomNum(uint16_t *randomNum)
{
    /* getrandom(2) — 시드 관리가 필요 없고 스레드 안전하다(Linux 3.17+). */
    ssize_t n = getrandom(randomNum, sizeof(*randomNum), 0);
    if (n != (ssize_t) sizeof(*randomNum)) {
        return DJI_ERROR_SYSTEM_MODULE_CODE_SYSTEM_ERROR;
    }
    return DJI_ERROR_SYSTEM_MODULE_CODE_SUCCESS;
}

static void *KsiOsal_Malloc(uint32_t size)
{
    return malloc(size);
}

static void KsiOsal_Free(void *ptr)
{
    free(ptr);
}

T_DjiReturnCode KsiOsalGlue_Register(void)
{
    T_DjiOsalHandler handler = {
        .TaskCreate = KsiOsal_TaskCreate,
        .TaskDestroy = KsiOsal_TaskDestroy,
        .TaskSleepMs = KsiOsal_TaskSleepMs,
        .MutexCreate = KsiOsal_MutexCreate,
        .MutexDestroy = KsiOsal_MutexDestroy,
        .MutexLock = KsiOsal_MutexLock,
        .MutexUnlock = KsiOsal_MutexUnlock,
        .SemaphoreCreate = KsiOsal_SemaphoreCreate,
        .SemaphoreDestroy = KsiOsal_SemaphoreDestroy,
        .SemaphoreWait = KsiOsal_SemaphoreWait,
        .SemaphoreTimedWait = KsiOsal_SemaphoreTimedWait,
        .SemaphorePost = KsiOsal_SemaphorePost,
        .GetTimeMs = KsiOsal_GetTimeMs,
        .GetTimeUs = KsiOsal_GetTimeUs,
        .GetRandomNum = KsiOsal_GetRandomNum,
        .Malloc = KsiOsal_Malloc,
        .Free = KsiOsal_Free,
    };
    return DjiPlatform_RegOsalHandler(&handler);
}
