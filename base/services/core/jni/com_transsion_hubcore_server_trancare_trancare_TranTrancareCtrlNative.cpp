/**
 Transsion Top Secret
 Copyright (C) 2022 transsion  Inc
 add for trancare
 @project T-HUB SDK[SPD]
 @author chongyang.zhang@transsion.com
 @version 1.0,  05/2022
*/

#define LOG_TAG "TranLog"

#include <nativehelper/JNIHelp.h>
#include "jni.h"
#include <utils/Log.h>
#include <utils/misc.h>
#include <utils/String8.h>

#include <dirent.h>
#include <fcntl.h>
#include <stdio.h>
#include <string.h>
#include <sys/epoll.h>
#include <sys/timerfd.h>
#include <sys/types.h>
#include <sys/socket.h>
#include <arpa/inet.h>
#include <netinet/in.h>
#include <stdlib.h>
#include <errno.h>
#include <unistd.h>
#include <linux/ioctl.h>
#include <linux/rtc.h>
#include <linux/time.h>
#include <array>
#include <memory>
#include <utils/Vector.h>

namespace android {

struct ioc_tid {
    long tid;
    int state;
};

#define ATHENA_IOC_MAGIC    'F'
#define ATHENA_SET_TID  _IOW(ATHENA_IOC_MAGIC, 0x01, struct ioc_tid)
#define ATHENA_SET_TIDS _IOW(ATHENA_IOC_MAGIC, 0x02, int)
#define ATHENA_CLR_TID  _IO(ATHENA_IOC_MAGIC, 0x03)
#define ATHENA_GET_TID  _IOWR(ATHENA_IOC_MAGIC, 0x04, struct ioc_tid)
#define ATHENA_NFY_TID  _IO(ATHENA_IOC_MAGIC, 0x05)
#define ATHENA_SET_EXP  _IOW(ATHENA_IOC_MAGIC, 0x06, int)

#define PATH_ATHENA_CONFIG "/dev/tranlog_ctl"

#define NOTIFY_NATIVE "notify"
static void notify_native(int fd)
{
    if(fd < 0) {
        return;
    }

    write(fd, NOTIFY_NATIVE, strlen(NOTIFY_NATIVE));
}

static void transsion_server_tranlog_AthenaConfigSetTid(JNIEnv *env, jobject thiz, jlong tid, jint state)
{
    int fd  = -1;
    struct ioc_tid ioctl_tid;

    fd = open("/dev/tranlog_ctl", O_RDWR);
    if(fd < 0) {
        ALOGD("open tranlog_ctl failed, error:%d", errno);
        return;
    }

    ioctl_tid.tid = tid;
    ioctl_tid.state = state;
    ioctl(fd, ATHENA_SET_TID, &ioctl_tid);
    notify_native(fd);

    close(fd);
}

static void transsion_server_tranlog_AthenaConfigSetTids(JNIEnv *env, jobject thiz, jlongArray tids)
{
    int fd  = -1;
    //long long *set_tids = NULL;
    jlong *set_tids = NULL;
    int num = 0;

    fd = open("/dev/tranlog_ctl", O_RDWR);
    if(fd < 0) {
        ALOGD("open tranlog_ctl failed, error:%d", errno);
        return;
    }

    if (tids != nullptr) {
        num = ((int) env->GetArrayLength(tids)) + 1; //layout: count + [tids x n]
        set_tids = (jlong *)malloc(sizeof(jlong) * num);
        if(set_tids == NULL) {
            ALOGE("malloc tids failed, errno:%d", errno);
            close(fd);
            return;
        }
        memset(set_tids, 0, sizeof(int)*num);
        env->GetLongArrayRegion(tids, 0, num-1, &set_tids[1]);

        set_tids[0] = num - 1;
        ioctl(fd, ATHENA_SET_TIDS, set_tids);
    }


    notify_native(fd);

    close(fd);
    if(set_tids) {
        free(set_tids);
    }
}

static void transsion_server_tranlog_AthenaConfigClearTids(JNIEnv *env, jobject thiz)
{
    int fd  = -1;

    fd = open("/dev/tranlog_ctl", O_RDWR);
    if(fd < 0) {
        ALOGD("open tranlog_ctl failed, error:%d", errno);
        return;
    }

    ioctl(fd, ATHENA_CLR_TID, 0);
    notify_native(fd);

    close(fd);
}

static void transsion_server_tranlog_AthenaConfigSetUserExp(JNIEnv *env, jobject thiz, jint enable)
{
    int fd  = -1;

    fd = open("/dev/tranlog_ctl", O_RDWR);
    if(fd < 0) {
        ALOGD("open tranlog_ctl failed, error:%d", errno);
        return;
    }

    ioctl(fd, ATHENA_SET_EXP, &enable);
    notify_native(fd);

    close(fd);
}

static const JNINativeMethod sMethods[] = {
     /* name, signature, funcPtr */
     { "nativeTranSetTid","(JI)V", (void *)transsion_server_tranlog_AthenaConfigSetTid},
     { "nativeTranSetTids","([J)V", (void *)transsion_server_tranlog_AthenaConfigSetTids},
     { "nativeTranClearTids","()V", (void *)transsion_server_tranlog_AthenaConfigClearTids},
     { "nativeTranSetUserExp","(I)V", (void *)transsion_server_tranlog_AthenaConfigSetUserExp},
};

int register_com_transsion_hubcore_server_trancare_trancare_TranTrancareCtrlNative(JNIEnv* env)
{
    return jniRegisterNativeMethods(env, "com/transsion/hubcore/server/trancare/trancare/TranTrancareCtrlNative",
                                    sMethods, NELEM(sMethods));
}

} /* namespace android */
