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

#ifndef TOUCH_PERFORMANCE_BOOST_H
#define TOUCH_PERFORMANCE_BOOST_H

#ifdef __ANDROID__
void touchPerformanceBoostDown();
void touchPerformanceBoostMove();
void touchPerformanceBoostUp();
void touchPerformanceBoostRelease();
bool touchPerformanceBoostIsAvailable();
void touchPerformanceBoostSetEnabled(bool enabled);
bool touchPerformanceBoostIsEnabled();
#else
// host空实现
inline void touchPerformanceBoostDown() {}
inline void touchPerformanceBoostMove() {}
inline void touchPerformanceBoostUp() {}
inline void touchPerformanceBoostRelease() {}
inline bool touchPerformanceBoostIsAvailable() {
    return false;
}
void touchPerformanceBoostSetEnabled(bool enabled) {}
bool touchPerformanceBoostIsEnabled() {
    return false;
}
#endif

#endif    // TOUCH_PERFORMANCE_BOOST_H
