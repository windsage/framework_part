/*
 * Copyright (C) 2012 The Android Open Source Project
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

//#define LOG_NDEBUG 0
#define LOG_TAG "BufferItemConsumer"
//#define ATRACE_TAG ATRACE_TAG_GRAPHICS
#include <utils/Log.h>

#include <inttypes.h>

#include <com_android_graphics_libgui_flags.h>
#include <gui/BufferItem.h>
#include <gui/BufferItemConsumer.h>
#include <ui/BufferQueueDefs.h>
#include <ui/GraphicBuffer.h>

#define BI_LOGV(x, ...) ALOGV("[%s] " x, mName.c_str(), ##__VA_ARGS__)
// #define BI_LOGD(x, ...) ALOGD("[%s] " x, mName.c_str(), ##__VA_ARGS__)
// #define BI_LOGI(x, ...) ALOGI("[%s] " x, mName.c_str(), ##__VA_ARGS__)
// #define BI_LOGW(x, ...) ALOGW("[%s] " x, mName.c_str(), ##__VA_ARGS__)
#define BI_LOGE(x, ...) ALOGE("[%s] " x, mName.c_str(), ##__VA_ARGS__)

namespace android {

#if COM_ANDROID_GRAPHICS_LIBGUI_FLAGS(WB_CONSUMER_BASE_OWNS_BQ)
BufferItemConsumer::BufferItemConsumer(uint64_t consumerUsage, int bufferCount,
                                       bool controlledByApp, bool isConsumerSurfaceFlinger)
      : ConsumerBase(controlledByApp, isConsumerSurfaceFlinger) {
    initialize(consumerUsage, bufferCount);
}

BufferItemConsumer::BufferItemConsumer(const sp<IGraphicBufferProducer>& producer,
                                       const sp<IGraphicBufferConsumer>& consumer,
                                       uint64_t consumerUsage, int bufferCount,
                                       bool controlledByApp)
      : ConsumerBase(producer, consumer, controlledByApp) {
    initialize(consumerUsage, bufferCount);
}
#endif // COM_ANDROID_GRAPHICS_LIBGUI_FLAGS(WB_CONSUMER_BASE_OWNS_BQ)

BufferItemConsumer::BufferItemConsumer(
        const sp<IGraphicBufferConsumer>& consumer, uint64_t consumerUsage,
        int bufferCount, bool controlledByApp) :
    ConsumerBase(consumer, controlledByApp)
{
    initialize(consumerUsage, bufferCount);
}

void BufferItemConsumer::initialize(uint64_t consumerUsage, int bufferCount) {
    status_t err = mConsumer->setConsumerUsageBits(consumerUsage);
    LOG_ALWAYS_FATAL_IF(err != OK, "Failed to set consumer usage bits to %#" PRIx64, consumerUsage);
    if (bufferCount != DEFAULT_MAX_BUFFERS) {
        err = mConsumer->setMaxAcquiredBufferCount(bufferCount);
        LOG_ALWAYS_FATAL_IF(err != OK, "Failed to set max acquired buffer count to %d",
                            bufferCount);
    }
}

BufferItemConsumer::~BufferItemConsumer() {}

void BufferItemConsumer::setBufferFreedListener(
        const wp<BufferFreedListener>& listener) {
    Mutex::Autolock _l(mMutex);
    mBufferFreedListener = listener;
}

status_t BufferItemConsumer::acquireBuffer(BufferItem *item,
        nsecs_t presentWhen, bool waitForFence) {
    status_t err;

    if (!item) return BAD_VALUE;

    Mutex::Autolock _l(mMutex);

    err = acquireBufferLocked(item, presentWhen);
    if (err != OK) {
        if (err != NO_BUFFER_AVAILABLE) {
            BI_LOGE("Error acquiring buffer: %s (%d)", strerror(err), err);
        }
        return err;
    }

    if (waitForFence) {
        err = item->mFence->waitForever("BufferItemConsumer::acquireBuffer");
        if (err != OK) {
            BI_LOGE("Failed to wait for fence of acquired buffer: %s (%d)",
                    strerror(-err), err);
            return err;
        }
    }

    item->mGraphicBuffer = mSlots[item->mSlot].mGraphicBuffer;

    return OK;
}

status_t BufferItemConsumer::releaseBuffer(const BufferItem &item,
        const sp<Fence>& releaseFence) {
    Mutex::Autolock _l(mMutex);
    return releaseBufferSlotLocked(item.mSlot, item.mGraphicBuffer, releaseFence);
}

#if COM_ANDROID_GRAPHICS_LIBGUI_FLAGS(WB_PLATFORM_API_IMPROVEMENTS)
status_t BufferItemConsumer::releaseBuffer(const sp<GraphicBuffer>& buffer,
                                           const sp<Fence>& releaseFence) {
    Mutex::Autolock _l(mMutex);

    if (buffer == nullptr) {
        return BAD_VALUE;
    }

    int slotIndex = getSlotForBufferLocked(buffer);
    if (slotIndex == INVALID_BUFFER_SLOT) {
        return BAD_VALUE;
    }

    return releaseBufferSlotLocked(slotIndex, buffer, releaseFence);
}
#endif // COM_ANDROID_GRAPHICS_LIBGUI_FLAGS(WB_PLATFORM_API_IMPROVEMENTS)

status_t BufferItemConsumer::releaseBufferSlotLocked(int slotIndex, const sp<GraphicBuffer>& buffer,
                                                     const sp<Fence>& releaseFence) {
    status_t err;

    err = addReleaseFenceLocked(slotIndex, buffer, releaseFence);
    if (err != OK) {
        BI_LOGE("Failed to addReleaseFenceLocked");
    }

    err = releaseBufferLocked(slotIndex, buffer);
    if (err != OK && err != IGraphicBufferConsumer::STALE_BUFFER_SLOT) {
        BI_LOGE("Failed to release buffer: %s (%d)",
                strerror(-err), err);
    }
    return err;
}

void BufferItemConsumer::freeBufferLocked(int slotIndex) {
    sp<BufferFreedListener> listener = mBufferFreedListener.promote();
    if (listener != nullptr && mSlots[slotIndex].mGraphicBuffer != nullptr) {
        // Fire callback if we have a listener registered and the buffer being freed is valid.
        BI_LOGV("actually calling onBufferFreed");
        listener->onBufferFreed(mSlots[slotIndex].mGraphicBuffer);
    }
    ConsumerBase::freeBufferLocked(slotIndex);
}

} // namespace android
