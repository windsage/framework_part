/*
 * Copyright 2019 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

#define EGL_EGLEXT_PROTOTYPES
#include <EGL/egl.h>
#include <EGL/eglext.h>

#include <gui/BufferQueue.h>
#include <surfacetexture/ImageConsumer.h>
#include <surfacetexture/SurfaceTexture.h>

// QTI_BEGIN: 2024-02-27: Graphics: nativedisplay: fix video call flicker issue
#include "../QtiExtension/QtiImageConsumerExtension.h"


// QTI_END: 2024-02-27: Graphics: nativedisplay: fix video call flicker issue
// Macro for including the SurfaceTexture name in log messages
#define IMG_LOGE(x, ...) ALOGE("[%s] " x, st.mName.c_str(), ##__VA_ARGS__)

namespace android {

// QTI_BEGIN: 2024-02-27: Graphics: nativedisplay: fix video call flicker issue
ImageConsumer::ImageConsumer() {
    mQtiImageConsumerExtn = std::make_shared<android::libnativedisplay::QtiImageConsumerExtension>(this);
}

// QTI_END: 2024-02-27: Graphics: nativedisplay: fix video call flicker issue
void ImageConsumer::onReleaseBufferLocked(int buf) {
    mImageSlots[buf].eglFence() = EGL_NO_SYNC_KHR;
}

sp<GraphicBuffer> ImageConsumer::dequeueBuffer(int* outSlotid, android_dataspace* outDataspace,
                                               HdrMetadata* outHdrMetadata, bool* outQueueEmpty,
                                               SurfaceTexture& st,
                                               SurfaceTexture_createReleaseFence createFence,
                                               SurfaceTexture_fenceWait fenceWait,
                                               void* fencePassThroughHandle) {
    BufferItem item;
    status_t err;
    err = st.acquireBufferLocked(&item, 0);
    if (err != OK) {
        if (err != BufferQueue::NO_BUFFER_AVAILABLE) {
            IMG_LOGE("Error acquiring buffer: %s (%d)", strerror(err), err);
        } else {
            int slot = st.mCurrentTexture;
            if (slot != BufferItem::INVALID_BUFFER_SLOT) {
                *outQueueEmpty = true;
                *outDataspace = st.mCurrentDataSpace;
                *outSlotid = slot;
                return st.mSlots[slot].mGraphicBuffer;
            }
        }
        return nullptr;
    }

    int slot = item.mSlot;
    *outQueueEmpty = false;
    if (item.mFence->isValid()) {
        // If fence is not signaled, that means frame is not ready and
        // outQueueEmpty is set to true. By the time the fence is signaled,
        // there may be a new buffer queued. This is a proper detection for an
        // empty queue and it is needed to avoid infinite loop in
        // ASurfaceTexture_dequeueBuffer (see b/159921224).
        *outQueueEmpty = item.mFence->getStatus() == Fence::Status::Unsignaled;

        // Wait on the producer fence for the buffer to be ready.
        err = fenceWait(item.mFence->get(), fencePassThroughHandle);
        if (err != OK) {
            st.releaseBufferLocked(slot, st.mSlots[slot].mGraphicBuffer);
            return nullptr;
        }
    }

    // Release old buffer.
    if (st.mCurrentTexture != BufferItem::INVALID_BUFFER_SLOT) {
        // If needed, set the released slot's fence to guard against a producer
        // accessing the buffer before the outstanding accesses have completed.
        int releaseFenceId = -1;
        EGLDisplay display = EGL_NO_DISPLAY;
        err = createFence(st.mUseFenceSync, &mImageSlots[slot].eglFence(), &display,
                          &releaseFenceId, fencePassThroughHandle);
        if (OK != err) {
            st.releaseBufferLocked(slot, st.mSlots[slot].mGraphicBuffer);
            return nullptr;
        }

        if (releaseFenceId != -1) {
            sp<Fence> releaseFence(new Fence(releaseFenceId));
            status_t err = st.addReleaseFenceLocked(st.mCurrentTexture,
                                                    st.mSlots[st.mCurrentTexture].mGraphicBuffer,
                                                    releaseFence);
            if (err != OK) {
                IMG_LOGE("dequeueImage: error adding release fence: %s (%d)", strerror(-err), err);
                st.releaseBufferLocked(slot, st.mSlots[slot].mGraphicBuffer);
                return nullptr;
            }
        }

        // Finally release the old buffer.
#if COM_ANDROID_GRAPHICS_LIBGUI_FLAGS(BQ_GL_FENCE_CLEANUP)
        EGLSyncKHR previousFence = mImageSlots[st.mCurrentTexture].eglFence();
        if (previousFence != EGL_NO_SYNC_KHR) {
            // Most platforms will be using native fences, so it's unlikely that we'll ever have to
            // process an eglFence. Ideally we can remove this code eventually. In the mean time, do
            // our best to wait for it so the buffer stays valid, otherwise return an error to the
            // caller.
            //
            // EGL_SYNC_FLUSH_COMMANDS_BIT_KHR so that we don't wait forever on a fence that hasn't
            // shown up on the GPU yet.
            EGLint result = eglClientWaitSyncKHR(display, previousFence,
                                                 EGL_SYNC_FLUSH_COMMANDS_BIT_KHR, 1000000000);
            if (result == EGL_FALSE) {
                IMG_LOGE("dequeueBuffer: error %#x waiting for fence", eglGetError());
            } else if (result == EGL_TIMEOUT_EXPIRED_KHR) {
                IMG_LOGE("dequeueBuffer: timeout waiting for fence");
            }
            eglDestroySyncKHR(display, previousFence);
        }

        status_t status = st.releaseBufferLocked(st.mCurrentTexture,
                                                 st.mSlots[st.mCurrentTexture].mGraphicBuffer);
#else
        status_t status =
                st.releaseBufferLocked(st.mCurrentTexture,
                                       st.mSlots[st.mCurrentTexture].mGraphicBuffer, display,
                                       mImageSlots[st.mCurrentTexture].eglFence());
#endif
        if (status < NO_ERROR) {
            IMG_LOGE("dequeueImage: failed to release buffer: %s (%d)", strerror(-status), status);
            err = status;
            // Keep going, with error raised.
        }
    }

// QTI_BEGIN: 2024-02-27: Graphics: nativedisplay: fix video call flicker issue
    mQtiImageConsumerExtn->updateBufferDataSpace(st.mSlots[slot].mGraphicBuffer, item);

// QTI_END: 2024-02-27: Graphics: nativedisplay: fix video call flicker issue
    // Update the state.
    st.mCurrentTexture = slot;
    st.mCurrentCrop = item.mCrop;
    st.mCurrentTransform = item.mTransform;
    st.mCurrentScalingMode = item.mScalingMode;
    st.mCurrentTimestamp = item.mTimestamp;
    st.mCurrentDataSpace = item.mDataSpace;
    st.mCurrentFence = item.mFence;
    st.mCurrentFenceTime = item.mFenceTime;
    st.mCurrentFrameNumber = item.mFrameNumber;
    st.computeCurrentTransformMatrixLocked();

    *outDataspace = item.mDataSpace;
    *outHdrMetadata = item.mHdrMetadata;
    *outSlotid = slot;
    return st.mSlots[slot].mGraphicBuffer;
}

} /* namespace android */
