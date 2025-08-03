/*
 * Copyright 2023 The Android Open Source Project
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

#include <common/FlagManager.h>
#include <common/trace.h>
#include <scheduler/FrameTargeter.h>
#include <scheduler/IVsyncSource.h>
#include <utils/Log.h>

namespace android::scheduler {
using namespace std::chrono_literals;

FrameTarget::FrameTarget(const std::string& displayLabel)
      : mFramePending("PrevFramePending " + displayLabel, false),
        mFrameMissed("PrevFrameMissed " + displayLabel, false),
        mHwcFrameMissed("PrevHwcFrameMissed " + displayLabel, false),
        mGpuFrameMissed("PrevGpuFrameMissed " + displayLabel, false) {}

std::pair<bool /* wouldBackpressure */, FrameTarget::PresentFence>
FrameTarget::expectedSignaledPresentFence(Period vsyncPeriod, Period minFramePeriod) const {
    SFTRACE_CALL();
    if (!FlagManager::getInstance().allow_n_vsyncs_in_targeter()) {
        const size_t i = static_cast<size_t>(targetsVsyncsAhead<2>(minFramePeriod));
        return {true, mPresentFencesLegacy[i]};
    }

    bool wouldBackpressure = true;
    auto expectedPresentTime = mExpectedPresentTime;
    for (size_t i = mPresentFences.size(); i != 0; --i) {
        const auto& fence = mPresentFences[i - 1];
        SFTRACE_FORMAT_INSTANT("fence at idx: %zu expectedPresentTime in %.2f", i - 1,
                               ticks<std::milli, float>(fence.expectedPresentTime -
                                                        TimePoint::now()));

        if (fence.expectedPresentTime + minFramePeriod < expectedPresentTime - vsyncPeriod / 2) {
            SFTRACE_FORMAT_INSTANT("would not backpressure");
            wouldBackpressure = false;
        }

        if (fence.expectedPresentTime <= mFrameBeginTime) {
            SFTRACE_FORMAT_INSTANT("fence at idx: %zu is %.2f before frame begin "
                                   "(wouldBackpressure=%s)",
                                   i - 1,
                                   ticks<std::milli, float>(mFrameBeginTime -
                                                            fence.expectedPresentTime),
                                   wouldBackpressure ? "true" : "false");
            return {wouldBackpressure, fence};
        }

        expectedPresentTime = fence.expectedPresentTime;
    }
    SFTRACE_FORMAT_INSTANT("No fence found");
    return {wouldBackpressure, PresentFence{}};
}

bool FrameTarget::wouldPresentEarly(Period vsyncPeriod, Period minFramePeriod) const {
    if (targetsVsyncsAhead<3>(minFramePeriod)) {
        return true;
    }

    const auto [wouldBackpressure, fence] =
            expectedSignaledPresentFence(vsyncPeriod, minFramePeriod);

    return !wouldBackpressure ||
            (fence.fenceTime->isValid() &&
             fence.fenceTime->getSignalTime() != Fence::SIGNAL_TIME_PENDING);
}

const FenceTimePtr& FrameTarget::presentFenceForPreviousFrame() const {
    if (FlagManager::getInstance().allow_n_vsyncs_in_targeter()) {
        if (mPresentFences.size() > 0) {
            return mPresentFences.back().fenceTime;
        }
        return FenceTime::NO_FENCE;
    }

    return mPresentFencesLegacy.front().fenceTime;
}

void FrameTargeter::beginFrame(const BeginFrameArgs& args, const IVsyncSource& vsyncSource) {
    return beginFrame(args, vsyncSource, &FrameTargeter::isFencePending);
}

void FrameTargeter::beginFrame(const BeginFrameArgs& args, const IVsyncSource& vsyncSource,
                               IsFencePendingFuncPtr isFencePendingFuncPtr) {
    mVsyncId = args.vsyncId;
    mFrameBeginTime = args.frameBeginTime;
    mDebugPresentTimeDelay = args.debugPresentTimeDelay;

    // The `expectedVsyncTime`, which was predicted when this frame was scheduled, is normally in
    // the future relative to `frameBeginTime`, but may not be for delayed frames. Adjust
    // `mExpectedPresentTime` accordingly, but not `mScheduledPresentTime`.
    const TimePoint lastScheduledPresentTime = mScheduledPresentTime;
    mScheduledPresentTime = args.expectedVsyncTime;

    const Period vsyncPeriod = vsyncSource.period();
    const Period minFramePeriod = vsyncSource.minFramePeriod();

    // Calculate the expected present time once and use the cached value throughout this frame to
    // make sure all layers are seeing this same value.
    if (args.expectedVsyncTime >= args.frameBeginTime) {
        mExpectedPresentTime = args.expectedVsyncTime;
    } else {
        mExpectedPresentTime = vsyncSource.vsyncDeadlineAfter(args.frameBeginTime);
        if (args.sfWorkDuration > vsyncPeriod) {
            // Inflate the expected present time if we're targeting the next VSYNC.
            mExpectedPresentTime += vsyncPeriod;
        }
    }

    if (!mSupportsExpectedPresentTime) {
        mEarliestPresentTime =
                computeEarliestPresentTime(vsyncPeriod, minFramePeriod, args.hwcMinWorkDuration);
    }

    SFTRACE_FORMAT("%s %" PRId64 " vsyncIn %.2fms%s", __func__, ftl::to_underlying(args.vsyncId),
                   ticks<std::milli, float>(mExpectedPresentTime - TimePoint::now()),
                   mExpectedPresentTime == args.expectedVsyncTime ? "" : " (adjusted)");

    const auto [wouldBackpressure, fence] =
            expectedSignaledPresentFence(vsyncPeriod, minFramePeriod);

    // In cases where the present fence is about to fire, give it a small grace period instead of
    // giving up on the frame.
    const int graceTimeForPresentFenceMs = [&] {
        const bool considerBackpressure =
                mBackpressureGpuComposition || !mCompositionCoverage.test(CompositionCoverage::Gpu);

        if (!FlagManager::getInstance().allow_n_vsyncs_in_targeter()) {
            return static_cast<int>(considerBackpressure);
        }

        if (!wouldBackpressure || !considerBackpressure) {
            return 0;
        }

        return static_cast<int>((std::abs(fence.expectedPresentTime.ns() - mFrameBeginTime.ns()) <=
                                 Duration(1ms).ns()));
    }();

    // Pending frames may trigger backpressure propagation.
    const auto& isFencePending = *isFencePendingFuncPtr;
    mFramePending = fence.fenceTime != FenceTime::NO_FENCE &&
            isFencePending(fence.fenceTime, graceTimeForPresentFenceMs);

    // A frame is missed if the prior frame is still pending. If no longer pending, then we still
    // count the frame as missed if the predicted present time was further in the past than when the
    // fence actually fired. Add some slop to correct for drift. This should generally be smaller
    // than a typical frame duration, but should not be so small that it reports reasonable drift as
    // a missed frame.
    mFrameMissed = mFramePending || [&] {
        const nsecs_t pastPresentTime = fence.fenceTime->getSignalTime();
        if (pastPresentTime < 0) return false;
        mLastSignaledFrameTime = {.signalTime = TimePoint::fromNs(pastPresentTime),
                                  .expectedPresentTime = fence.expectedPresentTime};
        SFTRACE_FORMAT_INSTANT("LastSignaledFrameTime expectedPresentTime %.2f ago, signalTime "
                               "%.2f ago",
                               ticks<std::milli, float>(mLastSignaledFrameTime.expectedPresentTime -
                                                        TimePoint::now()),
                               ticks<std::milli, float>(mLastSignaledFrameTime.signalTime -
                                                        TimePoint::now()));
        const nsecs_t frameMissedSlop = vsyncPeriod.ns() / 2;
        return lastScheduledPresentTime.ns() < pastPresentTime - frameMissedSlop;
    }();

    mHwcFrameMissed = mFrameMissed && mCompositionCoverage.test(CompositionCoverage::Hwc);
    mGpuFrameMissed = mFrameMissed && mCompositionCoverage.test(CompositionCoverage::Gpu);

    if (mFrameMissed) mFrameMissedCount++;
    if (mHwcFrameMissed) mHwcFrameMissedCount++;
    if (mGpuFrameMissed) mGpuFrameMissedCount++;

    mWouldBackpressureHwc = mFramePending && wouldBackpressure;
}

std::optional<TimePoint> FrameTargeter::computeEarliestPresentTime(Period vsyncPeriod,
                                                                   Period minFramePeriod,
                                                                   Duration hwcMinWorkDuration) {
    if (wouldPresentEarly(vsyncPeriod, minFramePeriod)) {
        return previousFrameVsyncTime(minFramePeriod) - hwcMinWorkDuration;
    }
    return {};
}

void FrameTargeter::endFrame(const CompositeResult& result) {
    mCompositionCoverage = result.compositionCoverage;
}

FenceTimePtr FrameTargeter::setPresentFence(sp<Fence> presentFence) {
    auto presentFenceTime = std::make_shared<FenceTime>(presentFence);
    return setPresentFence(std::move(presentFence), std::move(presentFenceTime));
}

FenceTimePtr FrameTargeter::setPresentFence(sp<Fence> presentFence, FenceTimePtr presentFenceTime) {
    if (FlagManager::getInstance().allow_n_vsyncs_in_targeter()) {
        addFence(std::move(presentFence), presentFenceTime, mExpectedPresentTime);
    } else {
        mPresentFencesLegacy[1] = mPresentFencesLegacy[0];
        mPresentFencesLegacy[0] = {std::move(presentFence), presentFenceTime, mExpectedPresentTime};
    }
    return presentFenceTime;
}

void FrameTargeter::dump(utils::Dumper& dumper) const {
    // There are scripts and tests that expect this (rather than "name=value") format.
    dumper.dump({}, "Total missed frame count: " + std::to_string(mFrameMissedCount));
    dumper.dump({}, "HWC missed frame count: " + std::to_string(mHwcFrameMissedCount));
    dumper.dump({}, "GPU missed frame count: " + std::to_string(mGpuFrameMissedCount));
}

bool FrameTargeter::isFencePending(const FenceTimePtr& fence, int graceTimeMs) {
    SFTRACE_CALL();
    const status_t status = fence->wait(graceTimeMs);

    // This is the same as Fence::Status::Unsignaled, but it saves a call to getStatus,
    // which calls wait(0) again internally.
    return status == -ETIME;
}

} // namespace android::scheduler
