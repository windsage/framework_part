/* Copyright (c) 2023-2024 Qualcomm Innovation Center, Inc. All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause-Clear
 */
#pragma once

#include <android/hardware/graphics/mapper/4.0/IMapper.h>

#include "../include/gui/Surface.h"

namespace android {

namespace libguiextension {

class QtiSurfaceExtension {
public:
    QtiSurfaceExtension(Surface* surface);
    ~QtiSurfaceExtension() = default;

    void qtiSetBufferDequeueDuration(std::string layerName, android_native_buffer_t* buffer,
                                     nsecs_t dequeue_duration);
    void qtiTrackTransaction(uint64_t frameNumber, int64_t timestamp);
    void qtiSendGfxTid();

private:
    bool isGame(std::string layerName);
    void InitializeMapper();
    void LoadQtiMapper5();

    bool mQtiIsGame = false;
    std::string mQtiLayerName = "";
    bool mEnableOptimalRefreshRate = false;
    sp<android::hardware::graphics::mapper::V4_0::IMapper> mMapper4 = nullptr;
};

} // namespace libguiextension
} // namespace android
