/*
 * Copyright 2021 The Android Open Source Project
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

#include <fuzzer/FuzzedDataProvider.h>
#include <linux/input.h>

#include "../../InputDeviceMetricsSource.h"
#include "../InputEventTimeline.h"
#include "NotifyArgsBuilders.h"
#include "dispatcher/LatencyTracker.h"

namespace android {

namespace inputdispatcher {

/**
 * A processor of InputEventTimelines that does nothing with the provided data.
 */
class EmptyProcessor : public InputEventTimelineProcessor {
public:
    /**
     * Just ignore the provided timeline
     */
    void processTimeline(const InputEventTimeline& timeline) override {
        for (const auto& [token, connectionTimeline] : timeline.connectionTimelines) {
            connectionTimeline.isComplete();
        }
    };

    void pushLatencyStatistics() override {}

    std::string dump(const char* prefix) const { return ""; };
};

static sp<IBinder> getConnectionToken(FuzzedDataProvider& fdp,
                                      std::array<sp<IBinder>, 10>& tokens) {
    const bool useExistingToken = fdp.ConsumeBool();
    if (useExistingToken) {
        return tokens[fdp.ConsumeIntegralInRange<size_t>(0ul, tokens.size() - 1)];
    }
    return sp<BBinder>::make();
}

extern "C" int LLVMFuzzerTestOneInput(uint8_t* data, size_t size) {
    FuzzedDataProvider fdp(data, size);

    EmptyProcessor emptyProcessor;
    LatencyTracker tracker(emptyProcessor);

    // Make some pre-defined tokens to ensure that some timelines are complete.
    std::array<sp<IBinder> /*token*/, 10> predefinedTokens;
    for (sp<IBinder>& token : predefinedTokens) {
        token = sp<BBinder>::make();
    }

    // Randomly invoke LatencyTracker api's until randomness is exhausted.
    while (fdp.remaining_bytes() > 0) {
        fdp.PickValueInArray<std::function<void()>>({
                [&]() -> void {
                    const int32_t inputEventId = fdp.ConsumeIntegral<int32_t>();
                    const nsecs_t eventTime = fdp.ConsumeIntegral<nsecs_t>();
                    const nsecs_t readTime = fdp.ConsumeIntegral<nsecs_t>();
                    const DeviceId deviceId = fdp.ConsumeIntegral<int32_t>();
                    const int32_t source = fdp.ConsumeIntegral<int32_t>();
                    std::set<InputDeviceUsageSource> sources = {
                            fdp.ConsumeEnum<InputDeviceUsageSource>()};
                    const int32_t inputEventActionType = fdp.ConsumeIntegral<int32_t>();
                    const InputEventType inputEventType = fdp.ConsumeEnum<InputEventType>();
                    const NotifyMotionArgs args =
                            MotionArgsBuilder(inputEventActionType, source, inputEventId)
                                    .eventTime(eventTime)
                                    .readTime(readTime)
                                    .deviceId(deviceId)
                                    .pointer(PointerBuilder(/*id=*/0, ToolType::FINGER)
                                                     .x(100)
                                                     .y(200))
                                    .build();
                    tracker.trackListener(args);
                },
                [&]() -> void {
                    const int32_t inputEventId = fdp.ConsumeIntegral<int32_t>();
                    sp<IBinder> connectionToken = getConnectionToken(fdp, predefinedTokens);
                    const nsecs_t deliveryTime = fdp.ConsumeIntegral<nsecs_t>();
                    const nsecs_t consumeTime = fdp.ConsumeIntegral<nsecs_t>();
                    const nsecs_t finishTime = fdp.ConsumeIntegral<nsecs_t>();
                    tracker.trackFinishedEvent(inputEventId, connectionToken, deliveryTime,
                                               consumeTime, finishTime);
                },
                [&]() -> void {
                    const int32_t inputEventId = fdp.ConsumeIntegral<int32_t>();
                    sp<IBinder> connectionToken = getConnectionToken(fdp, predefinedTokens);
                    std::array<nsecs_t, GraphicsTimeline::SIZE> graphicsTimeline{};
                    for (nsecs_t& t : graphicsTimeline) {
                        t = fdp.ConsumeIntegral<nsecs_t>();
                    }
                    tracker.trackGraphicsLatency(inputEventId, connectionToken, graphicsTimeline);
                },
        })();
    }

    return 0;
}

} // namespace inputdispatcher

} // namespace android