/*
 * Copyright 2024 The Android Open Source Project
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

#pragma once

#include <vector>

#include <android/gui/ActivePicture.h>

namespace android {

class Layer;
class LayerFE;
struct CompositionResult;

// Keeps track of active pictures - layers that are undergoing picture processing.
class ActivePictureUpdater {
public:
    // Called for each visible layer when SurfaceFlinger finishes composing.
    void onLayerComposed(const Layer& layer, const LayerFE& layerFE,
                         const CompositionResult& result);

    // Update internals and return whether the set of active pictures have changed.
    bool updateAndHasChanged();

    // The current set of active pictures.
    const std::vector<gui::ActivePicture>& getActivePictures() const;

private:
    std::vector<gui::ActivePicture> mOldActivePictures;
    std::vector<gui::ActivePicture> mNewActivePictures;
};

} // namespace android
