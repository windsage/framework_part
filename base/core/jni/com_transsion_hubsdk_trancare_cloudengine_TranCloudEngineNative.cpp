/**
 Transsion Top Secret
 Copyright (C) 2022 transsion  Inc
 add for cloudengine
 @project T-HUB SDK[SPD]
 @author chongyang.zhang@transsion.com
 @version 1.0,  05/2022
*/

#define LOG_TAG "TranLog"

#include <nativehelper/JNIHelp.h>
#include <nativehelper/ScopedUtfChars.h>
#include "jni.h"
#include <utils/Log.h>
#include <utils/misc.h>
#include <utils/String8.h>

#include <dirent.h>
#include <stdio.h>
#include <string.h>
#include <fcntl.h>
#include <sys/timerfd.h>
#include <sys/types.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <arpa/inet.h>
#include <netinet/in.h>
#include <stdlib.h>
#include <errno.h>
#include <unistd.h>
#include <linux/rtc.h>
#include <linux/time.h>
#include <array>
#include <memory>
#include <utils/Vector.h>
#include <linux/ioctl.h>

namespace android {

struct kernel_cloud_config {
    int id_len;
    char *id;
    int config_len;
    char *config;
};

#define CLOUD_ENGINE_IOC_MAGIC    'F'
#define CLOUD_ENGINE_UPDATE_CONFIG  _IOW(CLOUD_ENGINE_IOC_MAGIC, 0x01, struct kernel_cloud_config)
#define CLOUD_ENGINE_NOTIDY_REG     _IO(CLOUD_ENGINE_IOC_MAGIC, 0x02)

#define PATH_TRANCARE_CONFIG "/dev/tranlog_config"
#define PATH_CLOUD_ENGINE_CTL "/dev/tranlog_cloudctl"

static void cloudEngineUpdate(JNIEnv *env, jobject thiz,jstring id)
{
    int fd  = -1;
    if(id == NULL)
    {
        ALOGD("[cloud engine jni] id is null !!!!!");
        return;
    }
    fd = open(PATH_TRANCARE_CONFIG, O_RDWR | O_CLOEXEC);
    if(fd < 0) {
        ALOGD("open tranlog_config failed, error:%d", errno);
        return;
    }
    const char *idStr = env->GetStringUTFChars(id, NULL);
    if (idStr) {
        write(fd, idStr, strlen(idStr));
        env->ReleaseStringUTFChars(id, idStr);
    }
    close(fd);
}

static void cloudEngienKernelUpdate(JNIEnv *env, jobject thiz,jstring id, jstring config)
{
    int fd  = -1;
    struct kernel_cloud_config kernelConfig;
    if(id == NULL || config == NULL)
    {
        ALOGD("[cloud engine jni] id or config is null !!!!!");
        return;
    }

    fd = open(PATH_CLOUD_ENGINE_CTL, O_RDWR | O_CLOEXEC);
    if(fd < 0) {
        ALOGD("open tranlog_cloudctl failed, error:%d", errno);
        return;
    }

    const char *idStr = env->GetStringUTFChars(id, NULL);
    if(!idStr) {
        ALOGD("[cloudEngienKernelUpdate] alloc id failed");
        close(fd);
        return;
    }

    const char *configStr = env->GetStringUTFChars(config, NULL);
    if (!configStr) {
        ALOGD("[cloudEngienKernelUpdate] alloc config failed");
        close(fd);
        env->ReleaseStringUTFChars(id, idStr);
        return;
    }

    int idLen = strlen(idStr);
    int configLen = strlen(configStr);

    kernelConfig.id_len = idLen;
    kernelConfig.id = (char *)idStr;
    kernelConfig.config_len = configLen;
    kernelConfig.config = (char *)configStr;

    if (ioctl(fd, CLOUD_ENGINE_UPDATE_CONFIG, &kernelConfig) < 0) {
        ALOGD("[cloudEngienKernelUpdate] ioctl error: id = %s", idStr);
    }

    if(idStr) {
        env->ReleaseStringUTFChars(id, idStr);
    }
    if(configStr) {
        env->ReleaseStringUTFChars(config, configStr);
    }
    close(fd);
}

static void cloudEngineKernelReg(JNIEnv *env, jobject thiz)
{
    int fd  = -1;
    fd = open(PATH_CLOUD_ENGINE_CTL, O_RDWR | O_CLOEXEC);
    if(fd < 0) {
        ALOGD("open tranlog_cloudctl failed, error:%d", errno);
        return;
    }
    ioctl(fd, CLOUD_ENGINE_NOTIDY_REG, 0);

    close(fd);
}

static jboolean cloudEngineSetFileChmod(JNIEnv *env, jobject thiz, jstring path)
{
    jboolean success = false;
    int err  = -1;
    const char* filePath = path ? env->GetStringUTFChars(path, NULL) : NULL;
    if(!filePath) {
        ALOGE("[SetFileChmod]  file path is NULL!");
        goto exit;
    }

    err = chmod(filePath, 0777);
    ALOGE("[SetFileChmod]  file chmod error = %d errno=%d",err,errno);
    if(err == 0)
    {
        success = true;
    }
exit:
    if (filePath) {
        env->ReleaseStringUTFChars(path, filePath);
    }
    return success;
}

static const JNINativeMethod sMethods[] = {
     /* name, signature, funcPtr */
     { "nativeTranCloudEngineUpdate","(Ljava/lang/String;)V", (void *)cloudEngineUpdate},
     { "nativeTranCloudEngienKernelUpdate","(Ljava/lang/String;Ljava/lang/String;)V", (void *)cloudEngienKernelUpdate},
     { "nativeTranCloudEngineKernelReg","()V", (void *)cloudEngineKernelReg},
     { "nativeTranCloudEngineSetFileChmod","(Ljava/lang/String;)Z", (void *)cloudEngineSetFileChmod},
};

int register_com_transsion_hubsdk_trancare_trancare_TranCloudEngineNative(JNIEnv* env)
{
    return jniRegisterNativeMethods(env, "com/transsion/hubsdk/trancare/cloudengine/TranCloudEngineNative",
                                    sMethods, NELEM(sMethods));
}

} /* namespace android */
