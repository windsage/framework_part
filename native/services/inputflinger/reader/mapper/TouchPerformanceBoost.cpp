/*
 * Copyright (C) 2025 Transsion Holdings
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

#ifdef __ANDROID__

#define LOG_TAG "TouchPerfBoost"

#include "TouchPerformanceBoost.h"

#include <cutils/properties.h>
#include <log/log.h>
#include <utils/Mutex.h>
#include <utils/SystemClock.h>

#include "client.h"
#include "mp-ctl.h"

namespace android {

// 触摸事件类型
enum TouchEventType {
    TOUCH_DOWN = 1,
    TOUCH_MOVE = 2,
    TOUCH_UP = 3,
};

class TouchPerformanceBoost {
public:
    static TouchPerformanceBoost &getInstance() {
        static TouchPerformanceBoost instance;
        return instance;
    }

    void optimizeForDown();
    void optimizeForMove();
    void optimizeForUp();
    void release();
    bool isAvailable();

private:
    TouchPerformanceBoost() = default;
    ~TouchPerformanceBoost();

    void optimizeInternal(TouchEventType type);

    mutable Mutex mLock;
    int mTouchBoostHandle = 0;
    nsecs_t mLastOptimizeTime = 0;

    static constexpr nsecs_t MIN_INTERVAL_NS = 50000000LL;    // 50ms最小间隔
    static constexpr const char *ENABLE_PROPERTY = "vendor.perf.touch_boost.enabled";
    static constexpr const char *DEFAULT_ENABLED = "1";

    bool isEnabledByProperty() const;
};

TouchPerformanceBoost::~TouchPerformanceBoost() {
    AutoMutex lock(mLock);
    if (mTouchBoostHandle > 0) {
        perf_lock_rel(mTouchBoostHandle);
    }
}

bool TouchPerformanceBoost::isEnabledByProperty() const {
    char value[PROPERTY_VALUE_MAX];
    property_get(ENABLE_PROPERTY, value, DEFAULT_ENABLED);
    return strcmp(value, "1") == 0;
}
void TouchPerformanceBoost::optimizeInternal(TouchEventType type) {
    if (!isEnabledByProperty()) {
        ALOGV("Touch performance boost is disabled by property");
        return;
    }
    AutoMutex lock(mLock);

    nsecs_t now = systemTime(SYSTEM_TIME_MONOTONIC);

    if (type == TOUCH_MOVE && (now - mLastOptimizeTime) < MIN_INTERVAL_NS) {
        return;
    }

    if (mTouchBoostHandle > 0) {
        perf_lock_rel(mTouchBoostHandle);
        mTouchBoostHandle = 0;
    }

    mTouchBoostHandle = perf_hint(VENDOR_HINT_TOUCH_BOOST, "", 0, type);
    mLastOptimizeTime = now;

    ALOGV("Touch perf boost: type=%d, handle=%d", type, mTouchBoostHandle);
}

void TouchPerformanceBoost::optimizeForDown() {
    optimizeInternal(TOUCH_DOWN);
}

void TouchPerformanceBoost::optimizeForMove() {
    optimizeInternal(TOUCH_MOVE);
}

void TouchPerformanceBoost::optimizeForUp() {
    optimizeInternal(TOUCH_UP);
}

void TouchPerformanceBoost::release() {
    AutoMutex lock(mLock);
    if (mTouchBoostHandle > 0) {
        perf_lock_rel(mTouchBoostHandle);
        mTouchBoostHandle = 0;
    }
}

bool TouchPerformanceBoost::isAvailable() {
    return get_perf_hal_ver() > 0.0;
}

}    // namespace android

void touchPerformanceBoostDown() {
    android::TouchPerformanceBoost::getInstance().optimizeForDown();
}

void touchPerformanceBoostMove() {
    android::TouchPerformanceBoost::getInstance().optimizeForMove();
}

void touchPerformanceBoostUp() {
    android::TouchPerformanceBoost::getInstance().optimizeForUp();
}

void touchPerformanceBoostRelease() {
    android::TouchPerformanceBoost::getInstance().release();
}

bool touchPerformanceBoostIsAvailable() {
    return android::TouchPerformanceBoost::getInstance().isAvailable();
}

void touchPerformanceBoostSetEnabled(bool enabled) {
    property_set("vendor.perf.touch_boost.enabled", enabled ? "1" : "0");
    ALOGI("Touch performance boost %s", enabled ? "enabled" : "disabled");
}

bool touchPerformanceBoostIsEnabled() {
    char value[PROPERTY_VALUE_MAX];
    property_get("vendor.perf.touch_boost.enabled", value, "1");
    return strcmp(value, "1") == 0;
}
#endif    // __ANDROID__
