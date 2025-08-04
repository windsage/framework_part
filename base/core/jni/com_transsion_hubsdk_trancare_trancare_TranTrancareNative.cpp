/**
 Transsion Top Secret
 Copyright (C) 2022 transsion  Inc
 add for trancare
 @project T-HUB SDK[SPD]
 @author chongyang.zhang@transsion.com
 @version 1.0,  05/2022
*/

#include <android-base/macros.h>
#include <assert.h>
#include <cutils/properties.h>
#include <log/log.h>               // For LOGGER_ENTRY_MAX_PAYLOAD.
#include <utils/Log.h>
#include <utils/String8.h>

#include "jni.h"
#include <nativehelper/JNIHelp.h>
#include "utils/misc.h"
#include "core_jni_helpers.h"
#include "android_util_Log.h"

#include <tranlog/libtranlog.h>

namespace android {

static jint trancare_native_jni(JNIEnv* env, jobject clazz,
        jint dest, jint type, jstring tagObj, jstring msgObj)
{
    const char* tag = NULL;
    const char* msg = NULL;

    if (msgObj == NULL || tagObj == NULL) {
        ALOGE("[trancare_native_jni] msgObj or tagObj is NULL!!!");
        return -1;
    }

    tag = env->GetStringUTFChars(tagObj, NULL);
    msg = env->GetStringUTFChars(msgObj, NULL);

    if (tag == NULL || msg == NULL) {
        ALOGE("[trancare_native_jni] get msg or tag is NULL!!!");
        return -1;
    }

    int res = tranlog(tag, dest, type, msg);

    env->ReleaseStringUTFChars(tagObj, tag);
    env->ReleaseStringUTFChars(msgObj, msg);

    return res;
}

static void trancare_setnv_s32_jni(JNIEnv* env, jobject clazz,
       jstring keyObj, jint value, jint op)
{
    const char* key = NULL;

    if (keyObj == NULL) {
        return;
    }

    key = env->GetStringUTFChars(keyObj, NULL);

    tranlog_setnv_s32(key, value, op);
    env->ReleaseStringUTFChars(keyObj, key);
}

static void trancare_setnv_s64_jni(JNIEnv* env, jobject clazz,
       jstring keyObj, jlong value, jint op)
{
    const char* key = NULL;

    if (keyObj == NULL) {
        return;
    }

    key = env->GetStringUTFChars(keyObj, NULL);

    tranlog_setnv_s64(key, value, op);
    env->ReleaseStringUTFChars(keyObj, key);
}

static void trancare_setnv_float_jni(JNIEnv* env, jobject clazz,
       jstring keyObj, jfloat value, jint op)
{
    const char* key = NULL;

    if (keyObj == NULL) {
        return;
    }

    key = env->GetStringUTFChars(keyObj, NULL);

    tranlog_setnv_float(key, value, op);
    env->ReleaseStringUTFChars(keyObj, key);
}

static void trancare_setnv_string_jni(JNIEnv* env, jobject clazz,
       jstring keyObj, jstring msgObj)
{
    const char* key = NULL;
    const char* msg = NULL;

    if (keyObj == NULL) {
        return;
    }

    if (msgObj == NULL) {
        return;
    }

    key = env->GetStringUTFChars(keyObj, NULL);
    msg = env->GetStringUTFChars(msgObj, NULL);

    tranlog_setnv_string(key, msg);

    env->ReleaseStringUTFChars(keyObj, key);
    env->ReleaseStringUTFChars(msgObj, msg);
}

/*
 * JNI registration.
 */
static const JNINativeMethod gMethods[] = {
    /* name, signature, funcPtr */
    { "trancare_native",  "(IILjava/lang/String;Ljava/lang/String;)I", (void*) trancare_native_jni },
    { "trancare_native_setnv_int",  "(Ljava/lang/String;II)V", (void*) trancare_setnv_s32_jni },
    { "trancare_native_setnv_long",  "(Ljava/lang/String;JI)V", (void*) trancare_setnv_s64_jni },
    { "trancare_native_setnv_float",  "(Ljava/lang/String;FI)V", (void*) trancare_setnv_float_jni },
    { "trancare_native_setnv_string",  "(Ljava/lang/String;Ljava/lang/String;)V", (void*) trancare_setnv_string_jni },
};

int register_com_transsion_hubsdk_trancare_trancare_TranTrancareNative(JNIEnv* env)
{
    return RegisterMethodsOrDie(env, "com/transsion/hubsdk/trancare/trancare/TranTrancareNative", gMethods, NELEM(gMethods));
}

}; // namespace android
