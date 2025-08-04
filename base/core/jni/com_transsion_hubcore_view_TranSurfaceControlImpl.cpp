/**
 Transsion Top Secret
 Copyright (C) 2022 transsion  Inc
 add for tran perf
 @project T-HUB SDK[SPD]
 @author song.tang@transsion.com
 @version 1.0,  07/2023
*/

#include <nativehelper/JNIHelp.h>
#include <nativehelper/ScopedUtfChars.h>
#include "jni.h"
#include <gui/SurfaceComposerClient.h>
#include <stdio.h>
#include <string.h>

namespace android {

static jboolean nativeRequiresClientComposition(JNIEnv* env, jclass) {
    return static_cast<jboolean>(SurfaceComposerClient::getRequiresClientComposition());
}

//SPD: add for sfcpupolicy by song.tang 20241120 start
static void nativeSetTransitionState(JNIEnv* env, jclass clazz, jboolean isBegin) {
    SurfaceComposerClient::setTransitionState(isBegin);
}
//SPD: add for sfcpupolicy by song.tang 20241120 end

/*
 * JNI registration.
 */
static const JNINativeMethod sMethods[] = {
    {"nativeRequiresClientComposition", "()Z",(void*)nativeRequiresClientComposition },
    //SPD: add for sfcpupolicy by song.tang 20241120 start
    {"nativeSetTransitionState", "(Z)V", (void*)nativeSetTransitionState},
    //SPD: add for sfcpupolicy by song.tang 20241120 end
};

int register_com_transsion_hubcore_view_TranSurfaceControlImpl(JNIEnv* env)
{
    return jniRegisterNativeMethods(env, "com/transsion/hubcore/view/TranSurfaceControlImpl",
                                        sMethods, NELEM(sMethods));
}

}; // namespace android
