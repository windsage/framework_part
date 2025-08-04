/*
 * Copyright (C) 2007 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

// QTI_BEGIN: 2023-01-17: Display: sf: Introduce QTI Extensions in AOSP
/* Changes from Qualcomm Innovation Center are provided under the following license:
 *
// QTI_END: 2023-01-17: Display: sf: Introduce QTI Extensions in AOSP
// QTI_BEGIN: 2024-02-29: Display: sf: consider smomo vote for content detection
 * Copyright (c) 2023-2024 Qualcomm Innovation Center, Inc. All rights reserved.
// QTI_END: 2024-02-29: Display: sf: consider smomo vote for content detection
// QTI_BEGIN: 2023-01-17: Display: sf: Introduce QTI Extensions in AOSP
 * SPDX-License-Identifier: BSD-3-Clause-Clear
 */

// QTI_END: 2023-01-17: Display: sf: Introduce QTI Extensions in AOSP
#pragma once

#include <sys/types.h>

/*
 * NOTE: Make sure this file doesn't include  anything from <gl/ > or <gl2/ >
 */

#include <android-base/stringprintf.h>
#include <android-base/strings.h>
#include <android-base/thread_annotations.h>
#include <android/gui/ActivePicture.h>
#include <android/gui/BnSurfaceComposer.h>
#include <android/gui/DisplayStatInfo.h>
#include <android/gui/DisplayState.h>
#include <android/gui/IActivePictureListener.h>
#include <android/gui/IJankListener.h>
#include <android/gui/ISurfaceComposerClient.h>
#include <common/trace.h>
#include <cutils/atomic.h>
#include <cutils/compiler.h>
#include <ftl/algorithm.h>
#include <ftl/future.h>
#include <ftl/non_null.h>
#include <gui/BufferQueue.h>
#include <gui/CompositorTiming.h>
#include <gui/FrameTimestamps.h>
#include <gui/ISurfaceComposer.h>
#include <gui/ITransactionCompletedListener.h>
#include <gui/LayerState.h>
#include <layerproto/LayerProtoHeader.h>
#include <math/mat4.h>
#include <renderengine/LayerSettings.h>
#include <serviceutils/PriorityDumper.h>
#include <system/graphics.h>
#include <ui/DisplayMap.h>
#include <ui/FenceTime.h>
#include <ui/PixelFormat.h>
#include <ui/Size.h>
#include <utils/Errors.h>
#include <utils/KeyedVector.h>
#include <utils/RefBase.h>
#include <utils/SortedVector.h>
#include <utils/threads.h>

#include <compositionengine/OutputColorSetting.h>
#include <compositionengine/impl/OutputCompositionState.h>
#include <scheduler/Fps.h>
#include <scheduler/PresentLatencyTracker.h>
#include <scheduler/Time.h>
#include <scheduler/TransactionSchedule.h>
#include <scheduler/interface/CompositionCoverage.h>
#include <scheduler/interface/ICompositor.h>
#include <ui/FenceResult.h>

#include <common/FlagManager.h>
#include "ActivePictureTracker.h"
#include "Display/DisplayModeController.h"
#include "Display/PhysicalDisplay.h"
#include "Display/VirtualDisplaySnapshot.h"
#include "DisplayDevice.h"
#include "DisplayHardware/HWC2.h"
#include "DisplayHardware/HWComposer.h"
#include "DisplayIdGenerator.h"
#include "Effects/Daltonizer.h"
#include "FrontEnd/DisplayInfo.h"
#include "FrontEnd/LayerCreationArgs.h"
#include "FrontEnd/LayerLifecycleManager.h"
#include "FrontEnd/LayerSnapshot.h"
#include "FrontEnd/LayerSnapshotBuilder.h"
#include "FrontEnd/TransactionHandler.h"
#include "LayerVector.h"
#include "MutexUtils.h"
#include "PowerAdvisor/PowerAdvisor.h"
#include "QueuedTransactionState.h"
#include "Scheduler/ISchedulerCallback.h"
#include "Scheduler/RefreshRateSelector.h"
#include "Scheduler/Scheduler.h"
#include "SurfaceFlingerFactory.h"
#include "ThreadContext.h"
#include "Tracing/LayerTracing.h"
#include "Tracing/TransactionTracing.h"
#include "TransactionCallbackInvoker.h"
#include "Utils/OnceFuture.h"

#include <algorithm>
#include <atomic>
#include <cstdint>
#include <functional>
#include <memory>
#include <mutex>
#include <optional>
#include <string>
#include <thread>
#include <type_traits>
#include <unordered_map>
#include <unordered_set>
#include <utility>
#include <vector>

#include <aidl/android/hardware/graphics/common/DisplayDecorationSupport.h>
#include <aidl/android/hardware/graphics/common/DisplayHotplugEvent.h>
#include <aidl/android/hardware/graphics/composer3/RefreshRateChangedDebugData.h>
#include "Client.h"

using namespace android::surfaceflinger;

namespace android {

class EventThread;
class FlagManager;
class FpsReporter;
class TunnelModeEnabledReporter;
class HdrLayerInfoReporter;
class IGraphicBufferProducer;
class Layer;
class MessageBase;
class RefreshRateOverlay;
class RegionSamplingThread;
class TimeStats;
class FrameTracer;
class ScreenCapturer;
class WindowInfosListenerInvoker;

using ::aidl::android::hardware::drm::HdcpLevels;
using ::aidl::android::hardware::graphics::common::DisplayHotplugEvent;
using ::aidl::android::hardware::graphics::composer3::RefreshRateChangedDebugData;
using frontend::TransactionHandler;
using gui::CaptureArgs;
using gui::DisplayCaptureArgs;
using gui::IRegionSamplingListener;
using gui::LayerCaptureArgs;
using gui::ScreenCaptureResults;

namespace frametimeline {
class FrameTimeline;
}

namespace os {
    class IInputFlinger;
}

namespace compositionengine {
class DisplaySurface;
class OutputLayer;

struct CompositionRefreshArgs;
class DisplayCreationArgsBuilder;
} // namespace compositionengine

namespace renderengine {
class RenderEngine;
} // namespace renderengine

// QTI_BEGIN: 2023-01-17: Display: sf: Introduce QTI Extensions in AOSP
namespace surfaceflingerextension {
class QtiSurfaceFlingerExtension;
// QTI_END: 2023-01-17: Display: sf: Introduce QTI Extensions in AOSP
// QTI_BEGIN: 2023-01-24: Display: sf: Add support for multiple displays
class QtiNullExtension;
// QTI_END: 2023-01-24: Display: sf: Add support for multiple displays
// QTI_BEGIN: 2023-03-06: Display: SF: Squash commit of SF Extensions.
class QtiSurfaceFlingerExtensionIntf;
// QTI_END: 2023-03-06: Display: SF: Squash commit of SF Extensions.
// QTI_BEGIN: 2023-01-17: Display: sf: Introduce QTI Extensions in AOSP
} // namespace surfaceflingerextension

// QTI_END: 2023-01-17: Display: sf: Introduce QTI Extensions in AOSP
enum {
    eTransactionNeeded = 0x01,
    eTraversalNeeded = 0x02,
    eDisplayTransactionNeeded = 0x04,
    eTransformHintUpdateNeeded = 0x08,
    eTransactionFlushNeeded = 0x10,
    eInputInfoUpdateNeeded = 0x20,
    eTransactionMask = 0x3f,
};

// Latch Unsignaled buffer behaviours
enum class LatchUnsignaledConfig {
    // All buffers are latched signaled.
    Disabled,

    // Latch unsignaled is permitted when a single layer is updated in a frame,
    // and the update includes just a buffer update (i.e. no sync transactions
    // or geometry changes).
    // Latch unsignaled is also only permitted when a single transaction is ready
    // to be applied. If we pass an unsignaled fence to HWC, HWC might miss presenting
    // the frame if the fence does not fire in time. If we apply another transaction,
    // we may penalize the other transaction unfairly.
    AutoSingleLayer,

    // All buffers are latched unsignaled. This behaviour is discouraged as it
    // can break sync transactions, stall the display and cause undesired side effects.
    // This is equivalent to ignoring the acquire fence when applying transactions.
    Always,
};

using DisplayColorSetting = compositionengine::OutputColorSetting;

class SurfaceFlinger : public BnSurfaceComposer,
                       public PriorityDumper,
                       private IBinder::DeathRecipient,
                       private HWC2::ComposerCallback,
                       private ICompositor,
                       private scheduler::ISchedulerCallback,
                       private compositionengine::ICEPowerCallback {
public:
    struct SkipInitializationTag {};

    SurfaceFlinger(surfaceflinger::Factory&, SkipInitializationTag) ANDROID_API;
    explicit SurfaceFlinger(surfaceflinger::Factory&) ANDROID_API;

    // Set scheduling policy and attributes of main thread.
    static void setSchedFifo(bool enabled, const char* whence);
    static void setSchedAttr(bool enabled, const char* whence);

    static char const* getServiceName() ANDROID_API { return "SurfaceFlinger"; }

    // If fences from sync Framework are supported.
    static bool hasSyncFramework;

    // The offset in nanoseconds to use when VsyncController timestamps present fence
    // signaling time.
    static int64_t dispSyncPresentTimeOffset;

    // Some hardware can do RGB->YUV conversion more efficiently in hardware
    // controlled by HWC than in hardware controlled by the video encoder.
    // This instruct VirtualDisplaySurface to use HWC for such conversion on
    // GL composition.
    static bool useHwcForRgbToYuv;

    // Controls the number of buffers SurfaceFlinger will allocate for use in
    // FramebufferSurface
    static int64_t maxFrameBufferAcquiredBuffers;

    // Controls the minimum acquired buffers SurfaceFlinger will suggest via
    // ISurfaceComposer.getMaxAcquiredBufferCount().
    static int64_t minAcquiredBuffers;

    // Controls the maximum acquired buffers SurfaceFlinger will suggest via
    // ISurfaceComposer.getMaxAcquiredBufferCount().
    // Value is set through ro.surface_flinger.max_acquired_buffers.
    static std::optional<int64_t> maxAcquiredBuffersOpt;

    // Controls the maximum width and height in pixels that the graphics pipeline can support for
    // GPU fallback composition. For example, 8k devices with 4k GPUs, or 4k devices with 2k GPUs.
    static uint32_t maxGraphicsWidth;
    static uint32_t maxGraphicsHeight;

    static bool useContextPriority;

    // The data space and pixel format that SurfaceFlinger expects hardware composer
    // to composite efficiently. Meaning under most scenarios, hardware composer
    // will accept layers with the data space and pixel format.
    static ui::Dataspace defaultCompositionDataspace;
    static ui::PixelFormat defaultCompositionPixelFormat;

    // The data space and pixel format that SurfaceFlinger expects hardware composer
    // to composite efficiently for wide color gamut surfaces. Meaning under most scenarios,
    // hardware composer will accept layers with the data space and pixel format.
    static ui::Dataspace wideColorGamutCompositionDataspace;
    static ui::PixelFormat wideColorGamutCompositionPixelFormat;

    static constexpr SkipInitializationTag SkipInitialization;

    static LatchUnsignaledConfig enableLatchUnsignaledConfig;

    // must be called before clients can connect
    void init() ANDROID_API;

    // starts SurfaceFlinger main loop in the current thread
    void run() ANDROID_API;

    // Indicates frame activity, i.e. whether commit and/or composite is taking place.
    enum class FrameHint { kNone, kActive };

    // Schedule commit of transactions on the main thread ahead of the next VSYNC.
    void scheduleCommit(FrameHint, Duration workDurationSlack = Duration::fromNs(0));
    // As above, but also force composite regardless if transactions were committed.
    void scheduleComposite(FrameHint);
    // As above, but also force dirty geometry to repaint.
    void scheduleRepaint();
    // Schedule sampling independently from commit or composite.
    void scheduleSample();

    surfaceflinger::Factory& getFactory() { return mFactory; }

    // The CompositionEngine encapsulates all composition related interfaces and actions.
    compositionengine::CompositionEngine& getCompositionEngine() const;

    renderengine::RenderEngine& getRenderEngine() const;

    void onLayerFirstRef(Layer*);
    void onLayerDestroyed(Layer*);
    void onLayerUpdate();

    // Called when all clients have released all their references to
    // this layer. The layer may still be kept alive by its parents but
    // the client can no longer modify this layer directly.
    void onHandleDestroyed(sp<Layer>& layer, uint32_t layerId);

    TransactionCallbackInvoker& getTransactionCallbackInvoker() {
        return mTransactionCallbackInvoker;
    }

    // If set, disables reusing client composition buffers. This can be set by
    // debug.sf.disable_client_composition_cache
    bool mDisableClientCompositionCache = false;

    // Disables expensive rendering for all displays
    // This is scheduled on the main thread
    void disableExpensiveRendering();

    // If set, composition engine tries to predict the composition strategy provided by HWC
    // based on the previous frame. If the strategy can be predicted, gpu composition will
    // run parallel to the hwc validateDisplay call and re-run if the predition is incorrect.
    bool mPredictCompositionStrategy = false;

    // If true, then any layer with a SMPTE 170M transfer function is decoded using the sRGB
    // transfer instead. This is mainly to preserve legacy behavior, where implementations treated
    // SMPTE 170M as sRGB prior to color management being implemented, and now implementations rely
    // on this behavior to increase contrast for some media sources.
    bool mTreat170mAsSrgb = false;

    // If true, then screenshots with an enhanced render intent will dim in gamma space.
    // The purpose is to ensure that screenshots appear correct during system animations for devices
    // that require that dimming must occur in gamma space.
    bool mDimInGammaSpaceForEnhancedScreenshots = false;

    // Allows to ignore physical orientation provided through hwc API in favour of
    // 'ro.surface_flinger.primary_display_orientation'.
    // TODO(b/246793311): Clean up a temporary property
    bool mIgnoreHwcPhysicalDisplayOrientation = false;

    void forceFutureUpdate(int delayInMs);
    const DisplayDevice* getDisplayFromLayerStack(ui::LayerStack)
            REQUIRES(mStateLock, kMainThreadContext);

    // TODO (b/259407931): Remove.
    // TODO (b/281857977): This should be annotated with REQUIRES(kMainThreadContext), but this
    // would require thread safety annotations throughout the frontend (in particular Layer and
    // LayerFE).
    static ui::Transform::RotationFlags getActiveDisplayRotationFlags() {
        return sActiveDisplayRotationFlags;
    }

// QTI_BEGIN: 2024-02-28: Display: sf: Add check to acquire mStateLock in qtiCheckVirtualDisplayHint
    bool mRequestDisplayModeFlag = false;
    std::thread::id mFlagThread = std::this_thread::get_id();

// QTI_END: 2024-02-28: Display: sf: Add check to acquire mStateLock in qtiCheckVirtualDisplayHint
protected:
    // We're reference counted, never destroy SurfaceFlinger directly
    virtual ~SurfaceFlinger();

    virtual std::shared_ptr<renderengine::ExternalTexture> getExternalTextureFromBufferData(
            BufferData& bufferData, const char* layerName, uint64_t transactionId);

    // Returns true if any display matches a `bool(const DisplayDevice&)` predicate.
    template <typename Predicate>
    bool hasDisplay(Predicate p) const REQUIRES(mStateLock) {
        return static_cast<bool>(findDisplay(p));
    }

    bool exceedsMaxRenderTargetSize(uint32_t width, uint32_t height) const {
        return width > mMaxRenderTargetSize || height > mMaxRenderTargetSize;
    }

private:
    friend class BufferLayer;
    friend class Client;
    friend class FpsReporter;
    friend class TunnelModeEnabledReporter;
    friend class Layer;
    friend class RefreshRateOverlay;
    friend class RegionSamplingThread;
    friend class SurfaceComposerAIDL;

// QTI_BEGIN: 2023-01-17: Display: sf: Introduce QTI Extensions in AOSP
    friend class ::android::surfaceflingerextension::QtiSurfaceFlingerExtension;
// QTI_END: 2023-01-17: Display: sf: Introduce QTI Extensions in AOSP
// QTI_BEGIN: 2023-01-24: Display: sf: Add support for multiple displays
    friend class ::android::surfaceflingerextension::QtiNullExtension;
// QTI_END: 2023-01-24: Display: sf: Add support for multiple displays
    // For unit tests
    friend class TestableSurfaceFlinger;
    friend class TransactionApplicationTest;
    friend class TunnelModeEnabledReporterTest;

    using TransactionSchedule = scheduler::TransactionSchedule;
    using GetLayerSnapshotsFunction = std::function<std::vector<std::pair<Layer*, sp<LayerFE>>>()>;
    using DumpArgs = Vector<String16>;
    using Dumper = std::function<void(const DumpArgs&, bool asProto, std::string&)>;

    class State {
    public:
        explicit State(LayerVector::StateSet set) : stateSet(set) {}
        State& operator=(const State& other) {
            // We explicitly don't copy stateSet so that, e.g., mDrawingState
            // always uses the Drawing StateSet.
            displays = other.displays;
            colorMatrixChanged = other.colorMatrixChanged;
            if (colorMatrixChanged) {
                colorMatrix = other.colorMatrix;
            }
            globalShadowSettings = other.globalShadowSettings;

            return *this;
        }

        const LayerVector::StateSet stateSet = LayerVector::StateSet::Invalid;

        // TODO(b/241285876): Replace deprecated DefaultKeyedVector with ftl::SmallMap.
        DefaultKeyedVector<wp<IBinder>, DisplayDeviceState> displays;

        std::optional<size_t> getDisplayIndex(PhysicalDisplayId displayId) const {
            for (size_t i = 0; i < displays.size(); i++) {
                const auto& state = displays.valueAt(i);
                if (state.physical && state.physical->id == displayId) {
                    return i;
                }
            }

            return {};
        }

        bool colorMatrixChanged = true;
        mat4 colorMatrix;

        ShadowSettings globalShadowSettings;
    };

    // Keeps track of pending buffers per layer handle in the transaction queue or current/drawing
    // state before the buffers are latched. The layer owns the atomic counters and decrements the
    // count in the main thread when dropping or latching a buffer.
    //
    // The binder threads increment the same counter when a new transaction containing a buffer is
    // added to the transaction queue. The map is updated with the layer handle lifecycle updates.
    // This is done to avoid lock contention with the main thread.
    class BufferCountTracker {
    public:
        void increment(uint32_t layerId) {
            std::lock_guard<std::mutex> lock(mLock);
            auto it = mCounterByLayerId.find(layerId);
            if (it != mCounterByLayerId.end()) {
                auto [name, pendingBuffers] = it->second;
                int32_t count = ++(*pendingBuffers);
                SFTRACE_INT(name.c_str(), count);
            } else {
                ALOGW("Layer ID not found! %d", layerId);
            }
        }

        void add(uint32_t layerId, const std::string& name, std::atomic<int32_t>* counter) {
            std::lock_guard<std::mutex> lock(mLock);
            mCounterByLayerId[layerId] = std::make_pair(name, counter);
        }

        void remove(uint32_t layerId) {
            std::lock_guard<std::mutex> lock(mLock);
            mCounterByLayerId.erase(layerId);
        }

    private:
        std::mutex mLock;
        std::unordered_map<uint32_t, std::pair<std::string, std::atomic<int32_t>*>>
                mCounterByLayerId GUARDED_BY(mLock);
    };

    enum class BootStage {
        BOOTLOADER,
        BOOTANIMATION,
        FINISHED,
    };

    template <typename F, std::enable_if_t<!std::is_member_function_pointer_v<F>>* = nullptr>
    static Dumper dumper(F&& dump) {
        using namespace std::placeholders;
        return std::bind(std::forward<F>(dump), _3);
    }

    Dumper lockedDumper(Dumper dump) {
        return [this, dump](const DumpArgs& args, bool asProto, std::string& result) -> void {
            TimedLock lock(mStateLock, s2ns(1), __func__);
            if (!lock.locked()) {
                base::StringAppendF(&result, "Dumping without lock after timeout: %s (%d)\n",
                                    strerror(-lock.status), lock.status);
            }
            dump(args, asProto, result);
        };
    }

    template <typename F, std::enable_if_t<std::is_member_function_pointer_v<F>>* = nullptr>
    Dumper dumper(F dump) {
        using namespace std::placeholders;
        return lockedDumper(std::bind(dump, this, _3));
    }

    template <typename F>
    Dumper argsDumper(F dump) {
        using namespace std::placeholders;
        return lockedDumper(std::bind(dump, this, _1, _3));
    }

    template <typename F>
    Dumper protoDumper(F dump) {
        using namespace std::placeholders;
        return lockedDumper(std::bind(dump, this, _1, _2, _3));
    }

    Dumper mainThreadDumperImpl(Dumper dumper) {
        return [this, dumper](const DumpArgs& args, bool asProto, std::string& result) -> void {
            mScheduler
                    ->schedule(
                            [&args, asProto, &result, dumper]() FTL_FAKE_GUARD(kMainThreadContext)
                                    FTL_FAKE_GUARD(mStateLock) { dumper(args, asProto, result); })
                    .get();
        };
    }

    template <typename F, std::enable_if_t<std::is_member_function_pointer_v<F>>* = nullptr>
    Dumper mainThreadDumper(F dump) {
        using namespace std::placeholders;
        return mainThreadDumperImpl(std::bind(dump, this, _3));
    }

    template <typename F, std::enable_if_t<std::is_member_function_pointer_v<F>>* = nullptr>
    Dumper argsMainThreadDumper(F dump) {
        using namespace std::placeholders;
        return mainThreadDumperImpl(std::bind(dump, this, _1, _3));
    }

    // Maximum allowed number of display frames that can be set through backdoor
    static const int MAX_ALLOWED_DISPLAY_FRAMES = 2048;

    static const size_t MAX_LAYERS = 4096;

    static bool callingThreadHasUnscopedSurfaceFlingerAccess(bool usePermissionCache = true)
            EXCLUDES(mStateLock);

    // IBinder overrides:
    status_t onTransact(uint32_t code, const Parcel& data, Parcel* reply, uint32_t flags) override;
    status_t dump(int fd, const Vector<String16>& args) override { return priorityDump(fd, args); }

    // ISurfaceComposer implementation:
    sp<IBinder> createVirtualDisplay(const std::string& displayName, bool isSecure,
                                     gui::ISurfaceComposer::OptimizationPolicy optimizationPolicy,
                                     const std::string& uniqueId,
                                     float requestedRefreshRate = 0.0f);
    status_t destroyVirtualDisplay(const sp<IBinder>& displayToken);
    std::vector<PhysicalDisplayId> getPhysicalDisplayIds() const EXCLUDES(mStateLock) {
        Mutex::Autolock lock(mStateLock);
        return getPhysicalDisplayIdsLocked();
    }

    sp<IBinder> getPhysicalDisplayToken(PhysicalDisplayId displayId) const;
    status_t setTransactionState(TransactionState&&) override;
    void bootFinished();
    status_t getSupportedFrameTimestamps(std::vector<FrameEvent>* outSupported) const;
    sp<IDisplayEventConnection> createDisplayEventConnection(
            gui::ISurfaceComposer::VsyncSource vsyncSource =
                    gui::ISurfaceComposer::VsyncSource::eVsyncSourceApp,
            EventRegistrationFlags eventRegistration = {},
            const sp<IBinder>& layerHandle = nullptr);

    void captureDisplay(const DisplayCaptureArgs&, const sp<IScreenCaptureListener>&);
    void captureDisplay(DisplayId, const CaptureArgs&, const sp<IScreenCaptureListener>&);
    ScreenCaptureResults captureLayersSync(const LayerCaptureArgs&);
    void captureLayers(const LayerCaptureArgs&, const sp<IScreenCaptureListener>&);

    status_t getDisplayStats(const sp<IBinder>& displayToken, DisplayStatInfo* stats);
    status_t getDisplayState(const sp<IBinder>& displayToken, ui::DisplayState*)
            EXCLUDES(mStateLock);
    status_t getStaticDisplayInfo(int64_t displayId, ui::StaticDisplayInfo*) EXCLUDES(mStateLock);
    status_t getDynamicDisplayInfoFromId(int64_t displayId, ui::DynamicDisplayInfo*)
            EXCLUDES(mStateLock);
    status_t getDynamicDisplayInfoFromToken(const sp<IBinder>& displayToken,
                                            ui::DynamicDisplayInfo*) EXCLUDES(mStateLock);
    void getDynamicDisplayInfoInternal(ui::DynamicDisplayInfo*&, const sp<DisplayDevice>&,
                                       const display::DisplaySnapshot&);
    status_t getDisplayNativePrimaries(const sp<IBinder>& displayToken, ui::DisplayPrimaries&);
    status_t setActiveColorMode(const sp<IBinder>& displayToken, ui::ColorMode colorMode);
    status_t getBootDisplayModeSupport(bool* outSupport) const;
    status_t setBootDisplayMode(const sp<display::DisplayToken>&, DisplayModeId);
    status_t getOverlaySupport(gui::OverlayProperties* outProperties) const;
    status_t clearBootDisplayMode(const sp<IBinder>& displayToken);
    status_t getHdrConversionCapabilities(
            std::vector<gui::HdrConversionCapability>* hdrConversionCapaabilities) const;
    status_t setHdrConversionStrategy(const gui::HdrConversionStrategy& hdrConversionStrategy,
                                      int32_t*);
    status_t getHdrOutputConversionSupport(bool* outSupport) const;
    void setAutoLowLatencyMode(const sp<IBinder>& displayToken, bool on);
    void setGameContentType(const sp<IBinder>& displayToken, bool on);
    status_t getMaxLayerPictureProfiles(const sp<IBinder>& displayToken, int32_t* outMaxProfiles);
    void setPowerMode(const sp<IBinder>& displayToken, int mode);
    status_t overrideHdrTypes(const sp<IBinder>& displayToken,
                              const std::vector<ui::Hdr>& hdrTypes);
    status_t onPullAtom(const int32_t atomId, std::vector<uint8_t>* pulledData, bool* success);
    status_t getCompositionPreference(ui::Dataspace* outDataspace, ui::PixelFormat* outPixelFormat,
                                      ui::Dataspace* outWideColorGamutDataspace,
                                      ui::PixelFormat* outWideColorGamutPixelFormat) const;
    status_t getDisplayedContentSamplingAttributes(const sp<IBinder>& displayToken,
                                                   ui::PixelFormat* outFormat,
                                                   ui::Dataspace* outDataspace,
                                                   uint8_t* outComponentMask) const;
    status_t setDisplayContentSamplingEnabled(const sp<IBinder>& displayToken, bool enable,
                                              uint8_t componentMask, uint64_t maxFrames);
    status_t getDisplayedContentSample(const sp<IBinder>& displayToken, uint64_t maxFrames,
                                       uint64_t timestamp, DisplayedFrameStats* outStats) const;
    status_t getProtectedContentSupport(bool* outSupported) const;
    status_t isWideColorDisplay(const sp<IBinder>& displayToken, bool* outIsWideColorDisplay) const;
    status_t addRegionSamplingListener(const Rect& samplingArea, const sp<IBinder>& stopLayerHandle,
                                       const sp<IRegionSamplingListener>& listener);
    status_t removeRegionSamplingListener(const sp<IRegionSamplingListener>& listener);
    status_t addFpsListener(int32_t taskId, const sp<gui::IFpsListener>& listener);
    status_t removeFpsListener(const sp<gui::IFpsListener>& listener);
    status_t addTunnelModeEnabledListener(const sp<gui::ITunnelModeEnabledListener>& listener);
    status_t removeTunnelModeEnabledListener(const sp<gui::ITunnelModeEnabledListener>& listener);
    status_t setDesiredDisplayModeSpecs(const sp<IBinder>& displayToken,
                                        const gui::DisplayModeSpecs&);
    status_t getDesiredDisplayModeSpecs(const sp<IBinder>& displayToken, gui::DisplayModeSpecs*);
    status_t getDisplayBrightnessSupport(const sp<IBinder>& displayToken, bool* outSupport) const;
    status_t setDisplayBrightness(const sp<IBinder>& displayToken,
                                  const gui::DisplayBrightness& brightness);
    status_t addHdrLayerInfoListener(const sp<IBinder>& displayToken,
                                     const sp<gui::IHdrLayerInfoListener>& listener);
    status_t removeHdrLayerInfoListener(const sp<IBinder>& displayToken,
                                        const sp<gui::IHdrLayerInfoListener>& listener);
    status_t notifyPowerBoost(int32_t boostId);
    status_t setGlobalShadowSettings(const half4& ambientColor, const half4& spotColor,
                                     float lightPosY, float lightPosZ, float lightRadius);
    status_t getDisplayDecorationSupport(
            const sp<IBinder>& displayToken,
            std::optional<aidl::android::hardware::graphics::common::DisplayDecorationSupport>*
                    outSupport) const;
    status_t setFrameRate(const sp<IGraphicBufferProducer>& surface, float frameRate,
                          int8_t compatibility, int8_t changeFrameRateStrategy);

    //SPD:added for if sf uses client composition by song.tang 20230704 start
    status_t getRequiresClientComposition(bool* outSupported) const;
    //SPD:added for if sf uses client composition by song.tang 20230704 end
    //SPD: add for sfcpupolicy by song.tang 20241120 start
    status_t setTransitionState(bool isBegin);
    //SPD: add for sfcpupolicy by song.tang 20241120 end
    //SPD:add for sf thread info by sifeng.tian 20231117 start
    status_t getSurfaceFlingerTid(int* pTid) const;
    status_t getRenderEnginTid(int* pTid) const;
    status_t getHwcTids(std::vector<int>* pTids) const;
    //SPD:add for sf thread info by sifeng.tian 20231117 end

    status_t setFrameTimelineInfo(const sp<IGraphicBufferProducer>& surface,
                                  const gui::FrameTimelineInfo& frameTimelineInfo);

    status_t setGameModeFrameRateOverride(uid_t uid, float frameRate);

    status_t setGameDefaultFrameRateOverride(uid_t uid, float frameRate);

    status_t updateSmallAreaDetection(std::vector<std::pair<int32_t, float>>& uidThresholdMappings);

    status_t setSmallAreaDetectionThreshold(int32_t appId, float threshold);

    int getGpuContextPriority();

    status_t getMaxAcquiredBufferCount(int* buffers) const;

    status_t addWindowInfosListener(const sp<gui::IWindowInfosListener>& windowInfosListener,
                                    gui::WindowInfosListenerInfo* outResult);
    status_t removeWindowInfosListener(
            const sp<gui::IWindowInfosListener>& windowInfosListener) const;

    status_t getStalledTransactionInfo(
            int pid, std::optional<TransactionHandler::StalledTransactionInfo>& result);

    void updateHdcpLevels(hal::HWDisplayId hwcDisplayId, int32_t connectedLevel, int32_t maxLevel);

    void addActivePictureListener(const sp<gui::IActivePictureListener>& listener);

    void removeActivePictureListener(const sp<gui::IActivePictureListener>& listener);

    // IBinder::DeathRecipient overrides:
    void binderDied(const wp<IBinder>& who) override;

    // HWC2::ComposerCallback overrides:
    void onComposerHalVsync(hal::HWDisplayId, nsecs_t timestamp,
                            std::optional<hal::VsyncPeriodNanos>) override;
    void onComposerHalHotplugEvent(hal::HWDisplayId, DisplayHotplugEvent) override;
    void onComposerHalRefresh(hal::HWDisplayId) override;
    void onComposerHalVsyncPeriodTimingChanged(hal::HWDisplayId,
                                               const hal::VsyncPeriodChangeTimeline&) override;
    void onComposerHalSeamlessPossible(hal::HWDisplayId) override;
    void onComposerHalVsyncIdle(hal::HWDisplayId) override;
    void onRefreshRateChangedDebug(const RefreshRateChangedDebugData&) override;
    void onComposerHalHdcpLevelsChanged(hal::HWDisplayId, const HdcpLevels& levels) override;

    // ICompositor overrides:
    void configure() override REQUIRES(kMainThreadContext);
    bool commit(PhysicalDisplayId pacesetterId, const scheduler::FrameTargets&) override
            REQUIRES(kMainThreadContext);
    CompositeResultsPerDisplay composite(PhysicalDisplayId pacesetterId,
                                         const scheduler::FrameTargeters&) override
            REQUIRES(kMainThreadContext);

    void sample() override;

    // ISchedulerCallback overrides:
    void requestHardwareVsync(PhysicalDisplayId, bool) override;
    void requestDisplayModes(std::vector<display::DisplayModeRequest>) override;
    void kernelTimerChanged(bool expired) override;
    void onChoreographerAttached() override;
    void onExpectedPresentTimePosted(TimePoint expectedPresentTime, ftl::NonNull<DisplayModePtr>,
                                     Fps renderRate) override;
    void onCommitNotComposited() override
            REQUIRES(kMainThreadContext);
    void vrrDisplayIdle(bool idle) override;

    // ICEPowerCallback overrides:
    void notifyCpuLoadUp() override;

    using KernelIdleTimerController = scheduler::RefreshRateSelector::KernelIdleTimerController;

    // Get the controller and timeout that will help decide how the kernel idle timer will be
    // configured and what value to use as the timeout.
    std::pair<std::optional<KernelIdleTimerController>, std::chrono::milliseconds>
            getKernelIdleTimerProperties(PhysicalDisplayId) REQUIRES(mStateLock);

    // Show spinner with refresh rate overlay
    bool mRefreshRateOverlaySpinner = false;
    // Show render rate with refresh rate overlay
    bool mRefreshRateOverlayRenderRate = false;
    // Show render rate overlay offseted to the middle of the screen (e.g. for circular displays)
    bool mRefreshRateOverlayShowInMiddle = false;
    // Show hdr sdr ratio overlay
    bool mHdrSdrRatioOverlay = false;

    void setDesiredMode(display::DisplayModeRequest&&) REQUIRES(mStateLock);

    status_t setActiveModeFromBackdoor(const sp<display::DisplayToken>&, DisplayModeId, Fps minFps,
                                       Fps maxFps);

    void initiateDisplayModeChanges() REQUIRES(kMainThreadContext) REQUIRES(mStateLock);

    // Returns whether the commit stage should proceed. The return value is ignored when finalizing
    // immediate mode changes, which happen toward the end of the commit stage.
    // TODO: b/355427258 - Remove the return value once the `synced_resolution_switch` flag is live.
    bool finalizeDisplayModeChange(PhysicalDisplayId) REQUIRES(kMainThreadContext)
            REQUIRES(mStateLock);

    void dropModeRequest(PhysicalDisplayId) REQUIRES(kMainThreadContext);
    void applyActiveMode(PhysicalDisplayId) REQUIRES(kMainThreadContext);

    // Called on the main thread in response to setPowerMode()
    void setPhysicalDisplayPowerMode(const sp<DisplayDevice>& display, hal::PowerMode mode)
            REQUIRES(mStateLock, kMainThreadContext);
    void setVirtualDisplayPowerMode(const sp<DisplayDevice>& display, hal::PowerMode mode)
            REQUIRES(mStateLock, kMainThreadContext);

    // Returns whether to optimize globally for performance instead of power.
    bool shouldOptimizeForPerformance() REQUIRES(mStateLock);

    // Turns on power optimizations, for example when there are no displays to be optimized for
    // performance.
    static void enablePowerOptimizations(const char* whence);

    // Turns off power optimizations.
    static void disablePowerOptimizations(const char* whence);

    // Enables or disables power optimizations depending on whether there are displays that should
    // be optimized for performance.
    void applyOptimizationPolicy(const char* whence) REQUIRES(mStateLock);

    // Returns the preferred mode for PhysicalDisplayId if the Scheduler has selected one for that
    // display. Falls back to the display's defaultModeId otherwise.
    ftl::Optional<scheduler::FrameRateMode> getPreferredDisplayMode(
            PhysicalDisplayId, DisplayModeId defaultModeId) const REQUIRES(mStateLock);

    status_t setDesiredDisplayModeSpecsInternal(
            const sp<DisplayDevice>&, const scheduler::RefreshRateSelector::PolicyVariant&)
            EXCLUDES(mStateLock) REQUIRES(kMainThreadContext);

    // TODO(b/241285191): Look up RefreshRateSelector on Scheduler to remove redundant parameter.
    status_t applyRefreshRateSelectorPolicy(PhysicalDisplayId,
                                            const scheduler::RefreshRateSelector&)
            REQUIRES(mStateLock, kMainThreadContext);

    void commitTransactions() REQUIRES(kMainThreadContext, mStateLock);
    void commitTransactionsLocked(uint32_t transactionFlags)
            REQUIRES(mStateLock, kMainThreadContext);
    void doCommitTransactions() REQUIRES(mStateLock);

    std::vector<std::pair<Layer*, LayerFE*>> moveSnapshotsToCompositionArgs(
            compositionengine::CompositionRefreshArgs& refreshArgs, bool cursorOnly)
            REQUIRES(kMainThreadContext);
    void moveSnapshotsFromCompositionArgs(compositionengine::CompositionRefreshArgs& refreshArgs,
                                          const std::vector<std::pair<Layer*, LayerFE*>>& layers)
            REQUIRES(kMainThreadContext);
    // Return true if we must composite this frame
    bool updateLayerSnapshots(VsyncId vsyncId, nsecs_t frameTimeNs, bool transactionsFlushed,
                              bool& out) REQUIRES(kMainThreadContext);
    void updateLayerHistory(nsecs_t now) REQUIRES(kMainThreadContext);

    void updateInputFlinger(VsyncId vsyncId, TimePoint frameTime) REQUIRES(kMainThreadContext);
    void persistDisplayBrightness(bool needsComposite) REQUIRES(kMainThreadContext);
    void buildWindowInfos(std::vector<gui::WindowInfo>& outWindowInfos,
                          std::vector<gui::DisplayInfo>& outDisplayInfos)
            REQUIRES(kMainThreadContext);
    void commitInputWindowCommands() REQUIRES(mStateLock);
    void updateCursorAsync() REQUIRES(kMainThreadContext);

    void initScheduler(const sp<const DisplayDevice>&) REQUIRES(kMainThreadContext, mStateLock);

    void resetPhaseConfiguration(Fps) REQUIRES(mStateLock, kMainThreadContext);

    /*
     * Transactions
     */
    bool applyTransactionState(const FrameTimelineInfo& info,
                               std::vector<ResolvedComposerState>& state,
                               std::span<DisplayState> displays, uint32_t flags,
                               const InputWindowCommands& inputWindowCommands,
                               const int64_t desiredPresentTime, bool isAutoTimestamp,
                               const std::vector<uint64_t>& uncacheBufferIds,
                               const int64_t postTime, bool hasListenerCallbacks,
                               const std::vector<ListenerCallbacks>& listenerCallbacks,
                               int originPid, int originUid, uint64_t transactionId)
            REQUIRES(mStateLock, kMainThreadContext);
    // Flush pending transactions that were presented after desiredPresentTime.
    // For test only
    bool flushTransactionQueues() REQUIRES(kMainThreadContext);

    bool applyTransactions(std::vector<QueuedTransactionState>&) REQUIRES(kMainThreadContext);
    bool applyAndCommitDisplayTransactionStatesLocked(
            std::vector<QueuedTransactionState>& transactions)
            REQUIRES(kMainThreadContext, mStateLock);

    // Returns true if there is at least one transaction that needs to be flushed
    bool transactionFlushNeeded() REQUIRES(kMainThreadContext);
    void addTransactionReadyFilters() REQUIRES(kMainThreadContext);
    TransactionHandler::TransactionReadiness transactionReadyTimelineCheck(
            const TransactionHandler::TransactionFlushState& flushState)
            REQUIRES(kMainThreadContext);
    TransactionHandler::TransactionReadiness transactionReadyBufferCheck(
            const TransactionHandler::TransactionFlushState& flushState)
            REQUIRES(kMainThreadContext);

    uint32_t updateLayerCallbacksAndStats(const FrameTimelineInfo&, ResolvedComposerState&,
                                          int64_t desiredPresentTime, bool isAutoTimestamp,
                                          int64_t postTime, uint64_t transactionId)
            REQUIRES(mStateLock, kMainThreadContext);
    uint32_t getTransactionFlags() const;

    // Sets the masked bits, and schedules a commit if needed.
    void setTransactionFlags(uint32_t mask, TransactionSchedule = TransactionSchedule::Late,
                             const sp<IBinder>& applyToken = nullptr,
                             FrameHint = FrameHint::kActive);

    // Clears and returns the masked bits.
    uint32_t clearTransactionFlags(uint32_t mask);

    static LatchUnsignaledConfig getLatchUnsignaledConfig();
    bool shouldLatchUnsignaled(const layer_state_t&, size_t numStates, bool firstTransaction) const;
    bool applyTransactionsLocked(std::vector<QueuedTransactionState>& transactions)
            REQUIRES(mStateLock, kMainThreadContext);
    uint32_t setDisplayStateLocked(const DisplayState& s) REQUIRES(mStateLock);
    uint32_t addInputWindowCommands(const InputWindowCommands& inputWindowCommands)
            REQUIRES(mStateLock);
    bool frameIsEarly(TimePoint expectedPresentTime, VsyncId) const;

    /*
     * Layer management
     */
    status_t createLayer(LayerCreationArgs& args, gui::CreateSurfaceResult& outResult);

    status_t createBufferStateLayer(LayerCreationArgs& args, sp<IBinder>* outHandle,
                                    sp<Layer>* outLayer);

    status_t createEffectLayer(const LayerCreationArgs& args, sp<IBinder>* outHandle,
                               sp<Layer>* outLayer);

    // Checks if there are layer leaks before creating layer
    status_t checkLayerLeaks();

    status_t mirrorLayer(const LayerCreationArgs& args, const sp<IBinder>& mirrorFromHandle,
                         gui::CreateSurfaceResult& outResult);

    status_t mirrorDisplay(DisplayId displayId, const LayerCreationArgs& args,
                           gui::CreateSurfaceResult& outResult);

    // add a layer to SurfaceFlinger
    status_t addClientLayer(LayerCreationArgs& args, const sp<IBinder>& handle,
                            const sp<Layer>& layer, const wp<Layer>& parentLayer,
                            uint32_t* outTransformHint);

    // Creates a promise for a future release fence for a layer. This allows for
    // the layer to keep track of when its buffer can be released.
    void attachReleaseFenceFutureToLayer(Layer* layer, LayerFE* layerFE, ui::LayerStack layerStack);

    // Checks if a protected layer exists in a list of layers.
    bool layersHasProtectedLayer(const std::vector<std::pair<Layer*, sp<LayerFE>>>& layers) const;

    using OutputCompositionState = compositionengine::impl::OutputCompositionState;

    /*
     * Parameters used across screenshot methods.
     */
    struct ScreenshotArgs {
        // Contains the sequence ID of the parent layer if the screenshot is
        // initiated though captureLayers(), or the display that the render
        // result will be on if initiated through captureDisplay()
        std::variant<int32_t, wp<const DisplayDevice>> captureTypeVariant;

        // Display ID of the display the result will be on
        ftl::Optional<DisplayIdVariant> displayIdVariant{std::nullopt};

        // If true, transform is inverted from the parent layer snapshot
        bool childrenOnly{false};

        // Source crop of the render area
        Rect sourceCrop;

        // Transform to be applied on the layers to transform them
        // into the logical render area
        ui::Transform transform;

        // Size of the physical render area
        ui::Size reqSize;

        // Composition dataspace of the render area
        ui::Dataspace dataspace;

        // If false, the secure layer is blacked out or skipped
        // when rendered to an insecure render area
        bool isSecure{false};

        // If true, the render result may be used for system animations
        // that must preserve the exact colors of the display
        bool seamlessTransition{false};

        // Current display brightness of the output composition state
        float displayBrightnessNits{-1.f};

        // SDR white point of the output composition state
        float sdrWhitePointNits{-1.f};

        // Current active color mode of the output composition state
        ui::ColorMode colorMode{ui::ColorMode::NATIVE};

        // Current active render intent of the output composition state
        ui::RenderIntent renderIntent{ui::RenderIntent::COLORIMETRIC};
    };

    bool getSnapshotsFromMainThread(ScreenshotArgs& args,
                                    GetLayerSnapshotsFunction getLayerSnapshotsFn,
                                    std::vector<std::pair<Layer*, sp<LayerFE>>>& layers);

    void captureScreenCommon(ScreenshotArgs& args, GetLayerSnapshotsFunction, ui::Size bufferSize,
                             ui::PixelFormat, bool allowProtected, bool grayscale,
                             const sp<IScreenCaptureListener>&);

    bool getDisplayStateOnMainThread(ScreenshotArgs& args) REQUIRES(kMainThreadContext);

    ftl::SharedFuture<FenceResult> captureScreenshot(
            ScreenshotArgs& args, const std::shared_ptr<renderengine::ExternalTexture>& buffer,
            bool regionSampling, bool grayscale, bool isProtected,
            const sp<IScreenCaptureListener>& captureListener,
            const std::vector<std::pair<Layer*, sp<LayerFE>>>& layers,
            const std::shared_ptr<renderengine::ExternalTexture>& hdrBuffer = nullptr,
            const std::shared_ptr<renderengine::ExternalTexture>& gainmapBuffer = nullptr);

    ftl::SharedFuture<FenceResult> renderScreenImpl(
            ScreenshotArgs& args, const std::shared_ptr<renderengine::ExternalTexture>&,
            bool regionSampling, bool grayscale, bool isProtected, ScreenCaptureResults&,
            const std::vector<std::pair<Layer*, sp<LayerFE>>>& layers);

    void readPersistentProperties();

    uint32_t getMaxAcquiredBufferCountForCurrentRefreshRate(uid_t uid) const;

    /*
     * Display and layer stack management
     */

    // Called during boot and restart after system_server death, setting the stage for bootanimation
    // before DisplayManager takes over.
    void initializeDisplays() REQUIRES(kMainThreadContext);

    sp<const DisplayDevice> getDisplayDeviceLocked(const wp<IBinder>& displayToken) const
            REQUIRES(mStateLock) {
        return const_cast<SurfaceFlinger*>(this)->getDisplayDeviceLocked(displayToken);
    }

    sp<DisplayDevice> getDisplayDeviceLocked(const wp<IBinder>& displayToken) REQUIRES(mStateLock) {
        return mDisplays.get(displayToken)
                .or_else(ftl::static_ref<sp<DisplayDevice>>([] { return nullptr; }))
                .value();
    }

    sp<const DisplayDevice> getDisplayDeviceLocked(PhysicalDisplayId id) const
            REQUIRES(mStateLock) {
        return const_cast<SurfaceFlinger*>(this)->getDisplayDeviceLocked(id);
    }

    sp<DisplayDevice> getDisplayDeviceLocked(PhysicalDisplayId id) REQUIRES(mStateLock) {
        if (const auto token = getPhysicalDisplayTokenLocked(id)) {
            return getDisplayDeviceLocked(token);
        }
        return nullptr;
    }

    sp<const DisplayDevice> getDisplayDeviceLocked(DisplayId id) const REQUIRES(mStateLock) {
        // TODO(b/182939859): Replace tokens with IDs for display lookup.
        return findDisplay([id](const auto& display) { return display.getId() == id; });
    }

    std::shared_ptr<compositionengine::Display> getCompositionDisplayLocked(DisplayId id) const
            REQUIRES(mStateLock) {
        if (const auto display = getDisplayDeviceLocked(id)) {
            return display->getCompositionDisplay();
        }
        return nullptr;
    }

    // Returns the primary display or (for foldables) the active display.
    sp<const DisplayDevice> getDefaultDisplayDeviceLocked() const REQUIRES(mStateLock) {
        return const_cast<SurfaceFlinger*>(this)->getDefaultDisplayDeviceLocked();
    }

    sp<DisplayDevice> getDefaultDisplayDeviceLocked() REQUIRES(mStateLock) {
        return getDisplayDeviceLocked(mActiveDisplayId);
    }

    sp<const DisplayDevice> getDefaultDisplayDevice() const EXCLUDES(mStateLock) {
        Mutex::Autolock lock(mStateLock);
        return getDefaultDisplayDeviceLocked();
    }

    using DisplayDeviceAndSnapshot = std::pair<sp<DisplayDevice>, display::DisplaySnapshotRef>;

    // Combinator for ftl::Optional<PhysicalDisplay>::and_then.
    auto getDisplayDeviceAndSnapshot() REQUIRES(mStateLock) {
        return [this](const display::PhysicalDisplay& display) REQUIRES(
                       mStateLock) -> ftl::Optional<DisplayDeviceAndSnapshot> {
            if (auto device = getDisplayDeviceLocked(display.snapshot().displayId())) {
                return std::make_pair(std::move(device), display.snapshotRef());
            }

            return {};
        };
    }

    // Returns the first display that matches a `bool(const DisplayDevice&)` predicate.
    template <typename Predicate>
    sp<DisplayDevice> findDisplay(Predicate p) const REQUIRES(mStateLock) {
        const auto it = std::find_if(mDisplays.begin(), mDisplays.end(),
                                     [&](const auto& pair)
                                             REQUIRES(mStateLock) { return p(*pair.second); });

        return it == mDisplays.end() ? nullptr : it->second;
    }

    std::vector<PhysicalDisplayId> getPhysicalDisplayIdsLocked() const REQUIRES(mStateLock);

    // mark a region of a layer stack dirty. this updates the dirty
    // region of all screens presenting this layer stack.
    void invalidateLayerStack(const ui::LayerFilter& layerFilter, const Region& dirty);

    ui::LayerFilter makeLayerFilterForDisplay(DisplayIdVariant displayId, ui::LayerStack layerStack)
            REQUIRES(mStateLock) {
        return {layerStack,
                asPhysicalDisplayId(displayId)
                        .and_then(display::getPhysicalDisplay(mPhysicalDisplays))
                        .transform(&display::PhysicalDisplay::isInternal)
                        .value_or(false)};
    }

    /*
     * H/W composer
     */
    // The following thread safety rules apply when accessing HWComposer:
    // 1. When reading display state from HWComposer on the main thread, it's not necessary to
    //    acquire mStateLock.
    // 2. When accessing HWComposer on a thread other than the main thread, we always
    //    need to acquire mStateLock. This is because the main thread could be
    //    in the process of writing display state, e.g. creating or destroying a display.
    HWComposer& getHwComposer() const;

    /*
     * Compositing
     */
    void onCompositionPresented(PhysicalDisplayId pacesetterId, const scheduler::FrameTargeters&,
                                nsecs_t presentStartTime) REQUIRES(kMainThreadContext);

    /*
     * Display management
     */
    std::pair<DisplayModes, DisplayModePtr> loadDisplayModes(PhysicalDisplayId) const
            REQUIRES(mStateLock);

    // TODO(b/241285876): Move to DisplayConfigurator.
    //
    // Returns whether displays have been added/changed/removed, i.e. whether ICompositor should
    // commit display transactions.
    bool configureLocked() REQUIRES(mStateLock) REQUIRES(kMainThreadContext)
            EXCLUDES(mHotplugMutex);

    // Returns the active mode ID, or nullopt on hotplug failure.
    std::optional<DisplayModeId> processHotplugConnect(PhysicalDisplayId, hal::HWDisplayId,
                                                       DisplayIdentificationInfo&&,
                                                       const char* displayString,
                                                       HWComposer::HotplugEvent event)
            REQUIRES(mStateLock, kMainThreadContext);
    void processHotplugDisconnect(PhysicalDisplayId, const char* displayString)
            REQUIRES(mStateLock, kMainThreadContext);

    sp<DisplayDevice> setupNewDisplayDeviceInternal(
            const wp<IBinder>& displayToken,
            std::shared_ptr<compositionengine::Display> compositionDisplay,
            const DisplayDeviceState& state,
            const sp<compositionengine::DisplaySurface>& displaySurface,
// QTI_BEGIN: 2023-03-06: Display: SF: Squash commit of SF Extensions.
            const sp<IGraphicBufferProducer>& producer,
            surfaceflingerextension::QtiDisplaySurfaceExtensionIntf* mQtiDSExtnIntf = nullptr)
            REQUIRES(mStateLock);
// QTI_END: 2023-03-06: Display: SF: Squash commit of SF Extensions.
    void processDisplayChangesLocked() REQUIRES(mStateLock, kMainThreadContext);
    void processDisplayAdded(const wp<IBinder>& displayToken, const DisplayDeviceState&)
            REQUIRES(mStateLock, kMainThreadContext);
    void processDisplayRemoved(const wp<IBinder>& displayToken)
            REQUIRES(mStateLock, kMainThreadContext);
    void processDisplayChanged(const wp<IBinder>& displayToken,
                               const DisplayDeviceState& currentState,
                               const DisplayDeviceState& drawingState)
            REQUIRES(mStateLock, kMainThreadContext);

    /*
     * Display identification
     */
    sp<display::DisplayToken> getPhysicalDisplayTokenLocked(PhysicalDisplayId displayId) const
            REQUIRES(mStateLock) {
        return mPhysicalDisplays.get(displayId)
                .transform([](const display::PhysicalDisplay& display) { return display.token(); })
                .or_else([] { return std::optional<sp<display::DisplayToken>>(nullptr); })
                .value();
    }

    std::optional<PhysicalDisplayId> getPhysicalDisplayIdLocked(
            const sp<display::DisplayToken>&) const REQUIRES(mStateLock);

    // Returns the first display connected at boot.
    //
    // TODO(b/229851933): SF conflates the primary display with the first display connected at boot,
    // which typically has DisplayConnectionType::Internal. (Theoretically, it must be an internal
    // display because SF does not support disconnecting it, though in practice HWC may circumvent
    // this limitation.)
    sp<IBinder> getPrimaryDisplayTokenLocked() const REQUIRES(mStateLock) {
        return getPhysicalDisplayTokenLocked(getPrimaryDisplayIdLocked());
    }

    PhysicalDisplayId getPrimaryDisplayIdLocked() const REQUIRES(mStateLock) {
        return getHwComposer().getPrimaryDisplayId();
    }

    // Toggles use of HAL/GPU virtual displays.
    void enableHalVirtualDisplays(bool);

    // Virtual display lifecycle for ID generation and HAL allocation.
    std::optional<VirtualDisplayIdVariant> acquireVirtualDisplay(
            ui::Size, ui::PixelFormat, const std::string& uniqueId,
            compositionengine::DisplayCreationArgsBuilder&) REQUIRES(mStateLock);

    template <typename ID>
    void acquireVirtualDisplaySnapshot(ID displayId, const std::string& uniqueId) {
        std::lock_guard lock(mVirtualDisplaysMutex);
        const bool emplace_success =
                mVirtualDisplays.try_emplace(displayId, displayId, uniqueId).second;
        if (!emplace_success) {
            ALOGW("%s: Virtual display snapshot with the same ID already exists", __func__);
        }
    }

    void releaseVirtualDisplay(VirtualDisplayIdVariant displayId);
    void releaseVirtualDisplaySnapshot(VirtualDisplayId displayId);

    // Returns a display other than `mActiveDisplayId` that can be activated, if any.
    sp<DisplayDevice> getActivatableDisplay() const REQUIRES(mStateLock, kMainThreadContext);

    void onActiveDisplayChangedLocked(const DisplayDevice* inactiveDisplayPtr,
                                      const DisplayDevice& activeDisplay)
            REQUIRES(mStateLock, kMainThreadContext);

    void onActiveDisplaySizeChanged(const DisplayDevice&);

    /*
     * Debugging & dumpsys
     */
    void dumpAll(const DumpArgs& args, const std::string& compositionLayers,
                 std::string& result) const EXCLUDES(mStateLock);
    void dumpHwcLayersMinidump(std::string& result) const REQUIRES(mStateLock, kMainThreadContext);

    void appendSfConfigString(std::string& result) const;
    void listLayers(std::string& result) const REQUIRES(kMainThreadContext);
    void dumpStats(const DumpArgs& args, std::string& result) const
            REQUIRES(mStateLock, kMainThreadContext);
    void clearStats(const DumpArgs& args, std::string& result) REQUIRES(kMainThreadContext);
    void dumpTimeStats(const DumpArgs& args, bool asProto, std::string& result) const;
    void dumpFrameTimeline(const DumpArgs& args, std::string& result) const;
    void logFrameStats(TimePoint now) REQUIRES(kMainThreadContext);

    void dumpScheduler(std::string& result) const REQUIRES(mStateLock);
    void dumpEvents(std::string& result) const REQUIRES(mStateLock);
    void dumpVsync(std::string& result) const REQUIRES(mStateLock);

    void dumpCompositionDisplays(std::string& result) const REQUIRES(mStateLock);
    void dumpDisplays(std::string& result) const REQUIRES(mStateLock);
    void dumpDisplayIdentificationData(std::string& result) const REQUIRES(mStateLock);
    void dumpRawDisplayIdentificationData(const DumpArgs&, std::string& result) const;
    void dumpWideColorInfo(std::string& result) const REQUIRES(mStateLock);
    void dumpHdrInfo(std::string& result) const REQUIRES(mStateLock);
    void dumpFrontEnd(std::string& result) REQUIRES(kMainThreadContext);
    void dumpVisibleFrontEnd(std::string& result) REQUIRES(mStateLock, kMainThreadContext);

    perfetto::protos::LayersProto dumpDrawingStateProto(uint32_t traceFlags) const
            REQUIRES(kMainThreadContext);
    google::protobuf::RepeatedPtrField<perfetto::protos::DisplayProto> dumpDisplayProto() const;
    void doActiveLayersTracingIfNeeded(bool isCompositionComputed, bool visibleRegionDirty,
                                       TimePoint, VsyncId) REQUIRES(kMainThreadContext);
    perfetto::protos::LayersSnapshotProto takeLayersSnapshotProto(uint32_t flags, TimePoint,
                                                                  VsyncId, bool visibleRegionDirty)
            REQUIRES(kMainThreadContext);

    // Dumps state from HW Composer
    void dumpHwc(std::string& result) const;
    perfetto::protos::LayersProto dumpProtoFromMainThread(
            uint32_t traceFlags = LayerTracing::TRACE_ALL) EXCLUDES(mStateLock);
    void dumpPlannerInfo(const DumpArgs& args, std::string& result) const REQUIRES(mStateLock);

    status_t doDump(int fd, const DumpArgs& args, bool asProto);

    status_t dumpCritical(int fd, const DumpArgs&, bool asProto);

    status_t dumpAll(int fd, const DumpArgs& args, bool asProto) override {
        return doDump(fd, args, asProto);
    }

    static mat4 calculateColorMatrix(float saturation);

    void updateColorMatrixLocked();

    // Verify that transaction is being called by an approved process:
    // either AID_GRAPHICS or AID_SYSTEM.
    status_t CheckTransactCodeCredentials(uint32_t code);

    // Add transaction to the Transaction Queue

    /*
     * Generic Layer Metadata
     */
    const std::unordered_map<std::string, uint32_t>& getGenericLayerMetadataKeyMap() const;

    static int calculateMaxAcquiredBufferCount(Fps refreshRate,
                                               std::chrono::nanoseconds presentLatency);
    int getMaxAcquiredBufferCountForRefreshRate(Fps refreshRate) const;

    bool isHdrLayer(const frontend::LayerSnapshot& snapshot) const;

    ui::Rotation getPhysicalDisplayOrientation(PhysicalDisplayId, bool isPrimary) const
            REQUIRES(mStateLock);
    void traverseLegacyLayers(const LayerVector::Visitor& visitor) const
            REQUIRES(kMainThreadContext);

    void initBootProperties();
    void initTransactionTraceWriter();

    surfaceflinger::Factory& mFactory;
    pid_t mPid;

    // TODO: b/328459745 - Encapsulate in a SystemProperties object.
    utils::OnceFuture mInitBootPropsFuture;

    utils::OnceFuture mRenderEnginePrimeCacheFuture;

    // mStateLock has conventions related to the current thread, because only
    // the main thread should modify variables protected by mStateLock.
    // - read access from a non-main thread must lock mStateLock, since the main
    // thread may modify these variables.
    // - write access from a non-main thread is not permitted.
    // - read access from the main thread can use an ftl::FakeGuard, since other
    // threads must not modify these variables.
    // - write access from the main thread must lock mStateLock, since another
    // thread may be reading these variables.
    mutable Mutex mStateLock;
    State mCurrentState{LayerVector::StateSet::Current};
    std::atomic<int32_t> mTransactionFlags = 0;
    std::atomic<uint32_t> mUniqueTransactionId = 1;

    // Buffers that have been discarded by clients and need to be evicted from per-layer caches so
    // the graphics memory can be immediately freed.
    std::vector<uint64_t> mBufferIdsToUncache;

    // global color transform states
    Daltonizer mDaltonizer;
    float mGlobalSaturationFactor = 1.0f;
    mat4 mClientColorMatrix;

    // protected by mStateLock (but we could use another lock)
    bool mLayersRemoved = false;
    bool mLayersAdded = false;

    std::atomic_bool mMustComposite = false;
    std::atomic_bool mGeometryDirty = false;

    // constant members (no synchronization needed for access)
    const nsecs_t mBootTime = systemTime();
    bool mIsUserBuild = true;
    bool mHasReliablePresentFences = false;

    // Can only accessed from the main thread, these members
    // don't need synchronization
    State mDrawingState{LayerVector::StateSet::Drawing};
    bool mVisibleRegionsDirty = false;

    bool mHdrLayerInfoChanged = false;

    struct LayerEvent {
        uid_t uid;
        int32_t layerId;
        ui::Dataspace dataspace;
        std::chrono::milliseconds timeSinceLastEvent;
    };
    std::vector<LayerEvent> mLayerEvents;

    // Used to ensure we omit a callback when HDR layer info listener is newly added but the
    // scene hasn't changed
    bool mAddingHDRLayerInfoListener = false;
    bool mIgnoreHdrCameraLayers = false;

    // Set during transaction application stage to track if the input info or children
    // for a layer has changed.
    // TODO: Also move visibleRegions over to a boolean system.
    bool mUpdateInputInfo = false;
    bool mSomeChildrenChanged;
    bool mUpdateAttachedChoreographer = false;

    struct LayerIntHash {
        size_t operator()(const std::pair<sp<Layer>, gui::GameMode>& k) const {
            return std::hash<Layer*>()(k.first.get()) ^
                    std::hash<int32_t>()(static_cast<int32_t>(k.second));
        }
    };

    // TODO(b/238781169) validate these on composition
    // Tracks layers that have pending frames which are candidates for being
    // latched.
    std::unordered_set<std::pair<sp<Layer>, gui::GameMode>, LayerIntHash> mLayersWithQueuedFrames;
    std::unordered_set<sp<Layer>, SpHash<Layer>> mLayersWithBuffersRemoved;

    // Sorted list of layers that were composed during previous frame. This is used to
    // avoid an expensive traversal of the layer hierarchy when there are no
    // visible region changes. Because this is a list of strong pointers, this will
    // extend the life of the layer but this list is only updated in the main thread.
    std::vector<sp<Layer>> mPreviouslyComposedLayers;

    BootStage mBootStage = BootStage::BOOTLOADER;

    struct HotplugEvent {
        hal::HWDisplayId hwcDisplayId;
        HWComposer::HotplugEvent event;
    };

    std::mutex mHotplugMutex;
    std::vector<HotplugEvent> mPendingHotplugEvents GUARDED_BY(mHotplugMutex);

    // Displays are composited in `mDisplays` order. Internal displays are inserted at boot and
    // never removed, so take precedence over external and virtual displays.
    //
    // May be read from any thread, but must only be written from the main thread.
    ui::DisplayMap<wp<IBinder>, const sp<DisplayDevice>> mDisplays GUARDED_BY(mStateLock);

    display::PhysicalDisplays mPhysicalDisplays GUARDED_BY(mStateLock);

    mutable std::mutex mVirtualDisplaysMutex;
    ftl::SmallMap<VirtualDisplayId, const display::VirtualDisplaySnapshot, 2> mVirtualDisplays
            GUARDED_BY(mVirtualDisplaysMutex);

    // The inner or outer display for foldables, while unfolded or folded, respectively.
    std::atomic<PhysicalDisplayId> mActiveDisplayId;

    display::DisplayModeController mDisplayModeController;

    struct {
        DisplayIdGenerator<GpuVirtualDisplayId> gpu;
        std::optional<DisplayIdGenerator<HalVirtualDisplayId>> hal;
    } mVirtualDisplayIdGenerators;

    std::atomic_uint mDebugFlashDelay = 0;
    std::atomic_bool mDebugDisableHWC = false;
    std::atomic_bool mDebugDisableTransformHint = false;
    std::atomic<nsecs_t> mDebugInTransaction = 0;
    std::atomic_bool mForceFullDamage = false;

    bool mLayerCachingEnabled = false;
    bool mBackpressureGpuComposition = false;

    LayerTracing mLayerTracing;
    std::optional<TransactionTracing> mTransactionTracing;

    const std::shared_ptr<TimeStats> mTimeStats;
    const std::unique_ptr<FrameTracer> mFrameTracer;
    const std::unique_ptr<frametimeline::FrameTimeline> mFrameTimeline;

    VsyncId mLastCommittedVsyncId;

    // If blurs should be enabled on this device.
    bool mSupportsBlur = false;

    TransactionCallbackInvoker mTransactionCallbackInvoker;

    std::atomic<size_t> mNumLayers = 0;

    // to linkToDeath
    sp<IBinder> mWindowManager;
    // We want to avoid multiple calls to BOOT_FINISHED as they come in on
    // different threads without a lock and could trigger unsynchronized writes to
    // to mWindowManager or mInputFlinger
    std::atomic<bool> mBootFinished = false;

    std::thread::id mMainThreadId = std::this_thread::get_id();

    DisplayColorSetting mDisplayColorSetting = DisplayColorSetting::kEnhanced;

    // Color mode forced by setting persist.sys.sf.color_mode, it must:
    //     1. not be NATIVE color mode, NATIVE color mode means no forced color mode;
    //     2. be one of the supported color modes returned by hardware composer, otherwise
    //        it will not be respected.
    // persist.sys.sf.color_mode will only take effect when persist.sys.sf.native_mode
    // is not set to 1.
    // This property can be used to force SurfaceFlinger to always pick a certain color mode.
    ui::ColorMode mForceColorMode = ui::ColorMode::NATIVE;

    // Whether to enable wide color gamut (e.g. Display P3) for internal displays that support it.
    // If false, wide color modes are filtered out for all internal displays.
    bool mSupportsWideColor = false;

    ui::Dataspace mDefaultCompositionDataspace;
    ui::Dataspace mWideColorGamutCompositionDataspace;

    std::unique_ptr<renderengine::RenderEngine> mRenderEngine;
    std::atomic<int> mNumTrustedPresentationListeners = 0;

    std::unique_ptr<compositionengine::CompositionEngine> mCompositionEngine;
    std::unique_ptr<HWComposer> mHWComposer;

    CompositionCoveragePerDisplay mCompositionCoverage;

    // mMaxRenderTargetSize is only set once in init() so it doesn't need to be protected by
    // any mutex.
    size_t mMaxRenderTargetSize{1};

    const std::string mHwcServiceName;

    std::unique_ptr<scheduler::Scheduler> mScheduler;

    scheduler::PresentLatencyTracker mPresentLatencyTracker GUARDED_BY(kMainThreadContext);

    bool mLumaSampling = true;
    sp<RegionSamplingThread> mRegionSamplingThread;
    sp<FpsReporter> mFpsReporter;
    sp<TunnelModeEnabledReporter> mTunnelModeEnabledReporter;
    ui::DisplayPrimaries mInternalDisplayPrimaries;

    const float mEmulatedDisplayDensity;
    const float mInternalDisplayDensity;

    // Should only be accessed by the main thread.
    sp<os::IInputFlinger> mInputFlinger;
    InputWindowCommands mInputWindowCommands;

    std::unique_ptr<adpf::PowerAdvisor> mPowerAdvisor;

    void enableRefreshRateOverlay(bool enable) REQUIRES(mStateLock, kMainThreadContext);

    void enableHdrSdrRatioOverlay(bool enable) REQUIRES(mStateLock, kMainThreadContext);

    // Flag used to set override desired display mode from backdoor
    bool mDebugDisplayModeSetByBackdoor = false;

    BufferCountTracker mBufferCountTracker;

    std::unordered_map<DisplayId, sp<HdrLayerInfoReporter>> mHdrLayerInfoListeners
            GUARDED_BY(mStateLock);

    ActivePictureTracker mActivePictureTracker GUARDED_BY(kMainThreadContext);
    ActivePictureTracker::Listeners mActivePictureListenersToAdd GUARDED_BY(mStateLock);
    ActivePictureTracker::Listeners mActivePictureListenersToRemove GUARDED_BY(mStateLock);

    std::atomic<ui::Transform::RotationFlags> mActiveDisplayTransformHint;

    // Must only be accessed on the main thread.
    // TODO (b/259407931): Remove.
    static ui::Transform::RotationFlags sActiveDisplayRotationFlags;

    bool isRefreshRateOverlayEnabled() const REQUIRES(mStateLock) {
        return hasDisplay(
                [](const auto& display) { return display.isRefreshRateOverlayEnabled(); });
    }
    bool isHdrSdrRatioOverlayEnabled() const REQUIRES(mStateLock) {
        return hasDisplay(
                [](const auto& display) { return display.isHdrSdrRatioOverlayEnabled(); });
    }
    std::function<std::vector<std::pair<Layer*, sp<LayerFE>>>()> getLayerSnapshotsForScreenshots(
            std::optional<ui::LayerStack> layerStack, uint32_t uid,
            std::function<bool(const frontend::LayerSnapshot&, bool& outStopTraversal)>
                    snapshotFilterFn);
    std::function<std::vector<std::pair<Layer*, sp<LayerFE>>>()> getLayerSnapshotsForScreenshots(
            std::optional<ui::LayerStack> layerStack, uint32_t uid,
            std::unordered_set<uint32_t> excludeLayerIds);
    std::function<std::vector<std::pair<Layer*, sp<LayerFE>>>()> getLayerSnapshotsForScreenshots(
            uint32_t rootLayerId, uint32_t uid, std::unordered_set<uint32_t> excludeLayerIds,
            bool childrenOnly, const std::optional<FloatRect>& optionalParentCrop);

    const sp<WindowInfosListenerInvoker> mWindowInfosListenerInvoker;

    // returns the framerate of the layer with the given sequence ID
    float getLayerFramerate(nsecs_t now, int32_t id) const {
        return mScheduler->getLayerFramerate(now, id);
    }

    bool mPowerHintSessionEnabled;
    // Whether a display should be turned on when initialized
    bool mSkipPowerOnForQuiescent;

    // used for omitting vsync callbacks to apps when the display is not updatable
    int mRefreshableDisplays GUARDED_BY(mStateLock) = 0;
    void incRefreshableDisplays() REQUIRES(mStateLock);
    void decRefreshableDisplays() REQUIRES(mStateLock);

    //SPD: add for sfcpupolicy by song.tang 20241120 start
    bool mIsTransitionBegin = false;
    std::vector<int> mHwcTids;
    //SPD: add for sfcpupolicy by song.tang 20241120 end

    frontend::LayerLifecycleManager mLayerLifecycleManager GUARDED_BY(kMainThreadContext);
    frontend::LayerHierarchyBuilder mLayerHierarchyBuilder GUARDED_BY(kMainThreadContext);
    frontend::LayerSnapshotBuilder mLayerSnapshotBuilder GUARDED_BY(kMainThreadContext);

    mutable std::mutex mCreatedLayersLock;
    std::vector<sp<Layer>> mCreatedLayers GUARDED_BY(mCreatedLayersLock);
    std::vector<std::pair<uint32_t, std::string>> mDestroyedHandles GUARDED_BY(mCreatedLayersLock);
    std::vector<std::unique_ptr<frontend::RequestedLayerState>> mNewLayers
            GUARDED_BY(mCreatedLayersLock);
    std::vector<LayerCreationArgs> mNewLayerArgs GUARDED_BY(mCreatedLayersLock);
    // These classes do not store any client state but help with managing transaction callbacks
    // and stats.
    std::unordered_map<uint32_t, sp<Layer>> mLegacyLayers GUARDED_BY(kMainThreadContext);

// QTI_BEGIN: 2023-01-17: Display: sf: Introduce QTI Extensions in AOSP
    surfaceflingerextension::QtiSurfaceFlingerExtensionIntf* mQtiSFExtnIntf = nullptr;
// QTI_END: 2023-01-17: Display: sf: Introduce QTI Extensions in AOSP
// QTI_BEGIN: 2024-02-29: Display: sf: consider smomo vote for content detection
    std::mutex mSmomoMutex;
// QTI_END: 2024-02-29: Display: sf: consider smomo vote for content detection


    TransactionHandler mTransactionHandler GUARDED_BY(kMainThreadContext);
    ui::DisplayMap<ui::LayerStack, frontend::DisplayInfo> mFrontEndDisplayInfos
            GUARDED_BY(kMainThreadContext);
    bool mFrontEndDisplayInfosChanged GUARDED_BY(kMainThreadContext) = false;

    // WindowInfo ids visible during the last commit.
    std::unordered_set<int32_t> mVisibleWindowIds GUARDED_BY(kMainThreadContext);

    // Mirroring
    // Map of displayid to mirrorRoot
    ftl::SmallMap<int64_t, sp<SurfaceControl>, 3> mMirrorMapForDebug;

    // NotifyExpectedPresentHint
    enum class NotifyExpectedPresentHintStatus {
        // Represents that framework can start sending hint if required.
        Start,
        // Represents that the hint is already sent.
        Sent,
        // Represents that the hint will be scheduled with a new frame.
        ScheduleOnPresent,
        // Represents that a hint will be sent instantly by scheduling on the main thread.
        ScheduleOnTx
    };
    struct NotifyExpectedPresentData {
        TimePoint lastExpectedPresentTimestamp{};
        Fps lastFrameInterval{};
        // hintStatus is read and write from multiple threads such as
        // main thread, EventThread. And is atomic for that reason.
        std::atomic<NotifyExpectedPresentHintStatus> hintStatus =
                NotifyExpectedPresentHintStatus::Start;
    };
    std::unordered_map<PhysicalDisplayId, NotifyExpectedPresentData> mNotifyExpectedPresentMap;
    void sendNotifyExpectedPresentHint(PhysicalDisplayId displayId) override
            REQUIRES(kMainThreadContext);
    void scheduleNotifyExpectedPresentHint(PhysicalDisplayId displayId,
                                           VsyncId vsyncId = VsyncId{
                                                   FrameTimelineInfo::INVALID_VSYNC_ID});
    void notifyExpectedPresentIfRequired(PhysicalDisplayId, Period vsyncPeriod,
                                         TimePoint expectedPresentTime, Fps frameInterval,
                                         std::optional<Period> timeoutOpt);

    void sfdo_enableRefreshRateOverlay(bool active);
    void sfdo_setDebugFlash(int delay);
    void sfdo_scheduleComposite();
    void sfdo_scheduleCommit();
    void sfdo_forceClientComposition(bool enabled);
};

class SurfaceComposerAIDL : public gui::BnSurfaceComposer {
public:
    explicit SurfaceComposerAIDL(sp<SurfaceFlinger> sf) : mFlinger(std::move(sf)) {}

    binder::Status bootFinished() override;
    binder::Status createDisplayEventConnection(
            VsyncSource vsyncSource, EventRegistration eventRegistration,
            const sp<IBinder>& layerHandle,
            sp<gui::IDisplayEventConnection>* outConnection) override;
    binder::Status createConnection(sp<gui::ISurfaceComposerClient>* outClient) override;
    binder::Status createVirtualDisplay(
            const std::string& displayName, bool isSecure,
            gui::ISurfaceComposer::OptimizationPolicy optimizationPolicy,
            const std::string& uniqueId, float requestedRefreshRate,
            sp<IBinder>* outDisplay) override;
    binder::Status destroyVirtualDisplay(const sp<IBinder>& displayToken) override;
    binder::Status getPhysicalDisplayIds(std::vector<int64_t>* outDisplayIds) override;
    binder::Status getPhysicalDisplayToken(int64_t displayId, sp<IBinder>* outDisplay) override;
    binder::Status setPowerMode(const sp<IBinder>& display, int mode) override;
    binder::Status getSupportedFrameTimestamps(std::vector<FrameEvent>* outSupported) override;
    binder::Status getDisplayStats(const sp<IBinder>& display,
                                   gui::DisplayStatInfo* outStatInfo) override;
    binder::Status getDisplayState(const sp<IBinder>& display,
                                   gui::DisplayState* outState) override;
    binder::Status getStaticDisplayInfo(int64_t displayId,
                                        gui::StaticDisplayInfo* outInfo) override;
    binder::Status getDynamicDisplayInfoFromId(int64_t displayId,
                                               gui::DynamicDisplayInfo* outInfo) override;
    binder::Status getDynamicDisplayInfoFromToken(const sp<IBinder>& display,
                                                  gui::DynamicDisplayInfo* outInfo) override;
    binder::Status getDisplayNativePrimaries(const sp<IBinder>& display,
                                             gui::DisplayPrimaries* outPrimaries) override;
    binder::Status setActiveColorMode(const sp<IBinder>& display, int colorMode) override;
    binder::Status setBootDisplayMode(const sp<IBinder>& display, int displayModeId) override;
    binder::Status clearBootDisplayMode(const sp<IBinder>& display) override;
    binder::Status getBootDisplayModeSupport(bool* outMode) override;
    binder::Status getOverlaySupport(gui::OverlayProperties* outProperties) override;
    binder::Status getHdrConversionCapabilities(
            std::vector<gui::HdrConversionCapability>*) override;
    binder::Status setHdrConversionStrategy(const gui::HdrConversionStrategy& hdrConversionStrategy,
                                            int32_t*) override;
    binder::Status getHdrOutputConversionSupport(bool* outSupport) override;
    binder::Status setAutoLowLatencyMode(const sp<IBinder>& display, bool on) override;
    binder::Status setGameContentType(const sp<IBinder>& display, bool on) override;
    binder::Status getMaxLayerPictureProfiles(const sp<IBinder>& display,
                                              int32_t* outMaxProfiles) override;
    binder::Status captureDisplay(const DisplayCaptureArgs&,
                                  const sp<IScreenCaptureListener>&) override;
    binder::Status captureDisplayById(int64_t, const CaptureArgs&,
                                      const sp<IScreenCaptureListener>&) override;
    binder::Status captureLayers(const LayerCaptureArgs&,
                                 const sp<IScreenCaptureListener>&) override;
    binder::Status captureLayersSync(const LayerCaptureArgs&, ScreenCaptureResults* results);

    // TODO(b/239076119): Remove deprecated AIDL.
    [[deprecated]] binder::Status clearAnimationFrameStats() override {
        return binder::Status::ok();
    }
    [[deprecated]] binder::Status getAnimationFrameStats(gui::FrameStats*) override {
        return binder::Status::ok();
    }

    binder::Status overrideHdrTypes(const sp<IBinder>& display,
                                    const std::vector<int32_t>& hdrTypes) override;
    binder::Status onPullAtom(int32_t atomId, gui::PullAtomData* outPullData) override;
    binder::Status getCompositionPreference(gui::CompositionPreference* outPref) override;
    binder::Status getDisplayedContentSamplingAttributes(
            const sp<IBinder>& display, gui::ContentSamplingAttributes* outAttrs) override;
    binder::Status setDisplayContentSamplingEnabled(const sp<IBinder>& display, bool enable,
                                                    int8_t componentMask,
                                                    int64_t maxFrames) override;
    binder::Status getDisplayedContentSample(const sp<IBinder>& display, int64_t maxFrames,
                                             int64_t timestamp,
                                             gui::DisplayedFrameStats* outStats) override;
    binder::Status getProtectedContentSupport(bool* outSupporte) override;
    //SPD:added for if sf uses client composition by song.tang 20230704 start
    binder::Status getRequiresClientComposition(bool* outSupported) override;
    //SPD:added for if sf uses client composition by song.tang 20230704 end
    //SPD: add for sfcpupolicy by song.tang 20241120 start
    binder::Status setTransitionState(bool isBegin) override;
    //SPD: add for sfcpupolicy by song.tang 20241120 end
    //SPD:add for sf thread info by sifeng.tian 20231117 start
    binder::Status getSurfaceFlingerTid(int* pTid) override;
    binder::Status getRenderEnginTid(int* pTid) override;
    binder::Status getHwcTids(std::vector<int>* pTids) override;
    //SPD:add for sf thread info by sifeng.tian 20231117 end
    binder::Status isWideColorDisplay(const sp<IBinder>& token,
                                      bool* outIsWideColorDisplay) override;
    binder::Status addRegionSamplingListener(
            const gui::ARect& samplingArea, const sp<IBinder>& stopLayerHandle,
            const sp<gui::IRegionSamplingListener>& listener) override;
    binder::Status removeRegionSamplingListener(
            const sp<gui::IRegionSamplingListener>& listener) override;
    binder::Status addFpsListener(int32_t taskId, const sp<gui::IFpsListener>& listener) override;
    binder::Status removeFpsListener(const sp<gui::IFpsListener>& listener) override;
    binder::Status addTunnelModeEnabledListener(
            const sp<gui::ITunnelModeEnabledListener>& listener) override;
    binder::Status removeTunnelModeEnabledListener(
            const sp<gui::ITunnelModeEnabledListener>& listener) override;
    binder::Status setDesiredDisplayModeSpecs(const sp<IBinder>& displayToken,
                                              const gui::DisplayModeSpecs&) override;
    binder::Status getDesiredDisplayModeSpecs(const sp<IBinder>& displayToken,
                                              gui::DisplayModeSpecs* outSpecs) override;
    binder::Status getDisplayBrightnessSupport(const sp<IBinder>& displayToken,
                                               bool* outSupport) override;
    binder::Status setDisplayBrightness(const sp<IBinder>& displayToken,
                                        const gui::DisplayBrightness& brightness) override;
    binder::Status addHdrLayerInfoListener(const sp<IBinder>& displayToken,
                                           const sp<gui::IHdrLayerInfoListener>& listener) override;
    binder::Status removeHdrLayerInfoListener(
            const sp<IBinder>& displayToken,
            const sp<gui::IHdrLayerInfoListener>& listener) override;

    binder::Status notifyPowerBoost(int boostId) override;
    binder::Status setGlobalShadowSettings(const gui::Color& ambientColor,
                                           const gui::Color& spotColor, float lightPosY,
                                           float lightPosZ, float lightRadius) override;
    binder::Status getDisplayDecorationSupport(
            const sp<IBinder>& displayToken,
            std::optional<gui::DisplayDecorationSupport>* outSupport) override;
    binder::Status setGameModeFrameRateOverride(int32_t uid, float frameRate) override;
    binder::Status setGameDefaultFrameRateOverride(int32_t uid, float frameRate) override;
    binder::Status enableRefreshRateOverlay(bool active) override;
    binder::Status setDebugFlash(int delay) override;
    binder::Status scheduleComposite() override;
    binder::Status scheduleCommit() override;
    binder::Status forceClientComposition(bool enabled) override;
    binder::Status updateSmallAreaDetection(const std::vector<int32_t>& appIds,
                                            const std::vector<float>& thresholds) override;
    binder::Status setSmallAreaDetectionThreshold(int32_t appId, float threshold) override;
    binder::Status getGpuContextPriority(int32_t* outPriority) override;
    binder::Status getMaxAcquiredBufferCount(int32_t* buffers) override;
    binder::Status addWindowInfosListener(const sp<gui::IWindowInfosListener>& windowInfosListener,
                                          gui::WindowInfosListenerInfo* outInfo) override;
    binder::Status removeWindowInfosListener(
            const sp<gui::IWindowInfosListener>& windowInfosListener) override;
    binder::Status getStalledTransactionInfo(
            int pid, std::optional<gui::StalledTransactionInfo>* outInfo) override;
    binder::Status getSchedulingPolicy(gui::SchedulingPolicy* outPolicy) override;
    binder::Status notifyShutdown() override;
    binder::Status addJankListener(const sp<IBinder>& layer,
                                   const sp<gui::IJankListener>& listener) override;
    binder::Status flushJankData(int32_t layerId) override;
    binder::Status removeJankListener(int32_t layerId, const sp<gui::IJankListener>& listener,
                                      int64_t afterVsync) override;
    binder::Status addActivePictureListener(const sp<gui::IActivePictureListener>& listener);
    binder::Status removeActivePictureListener(const sp<gui::IActivePictureListener>& listener);

private:
    static const constexpr bool kUsePermissionCache = true;
    status_t checkAccessPermission(bool usePermissionCache = kUsePermissionCache);
    status_t checkControlDisplayBrightnessPermission();
    status_t checkReadFrameBufferPermission();
    status_t checkObservePictureProfilesPermission();
    static void getDynamicDisplayInfoInternal(ui::DynamicDisplayInfo& info,
                                              gui::DynamicDisplayInfo*& outInfo);

private:
    const sp<SurfaceFlinger> mFlinger;
};

} // namespace android
