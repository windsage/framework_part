/*
 *  Copyright (C) 2023 The Android Open Source Project
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 */

package com.android.systemui.keyguard.domain.interactor

import android.util.Log
import com.android.keyguard.ClockEventController
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.keyguard.data.repository.KeyguardClockRepository
import com.android.systemui.keyguard.shared.model.ClockSize
import com.android.systemui.keyguard.shared.model.ClockSizeSetting
import com.android.systemui.keyguard.shared.model.KeyguardState.AOD
import com.android.systemui.keyguard.shared.model.KeyguardState.DOZING
import com.android.systemui.keyguard.shared.model.KeyguardState.GONE
import com.android.systemui.keyguard.shared.model.KeyguardState.LOCKSCREEN
import com.android.systemui.media.controls.domain.pipeline.interactor.MediaCarouselInteractor
import com.android.systemui.plugins.clocks.ClockController
import com.android.systemui.plugins.clocks.ClockId
import com.android.systemui.scene.shared.flag.SceneContainerFlag
import com.android.systemui.shade.domain.interactor.ShadeModeInteractor
import com.android.systemui.statusbar.notification.domain.interactor.ActiveNotificationsInteractor
import com.android.systemui.statusbar.notification.domain.interactor.HeadsUpNotificationInteractor
import com.android.systemui.statusbar.notification.promoted.PromotedNotificationUi
import com.android.systemui.statusbar.notification.promoted.domain.interactor.AODPromotedNotificationInteractor
import com.android.systemui.util.kotlin.combine
import com.android.systemui.utils.coroutines.flow.flatMapLatestConflated
import com.android.systemui.wallpapers.domain.interactor.WallpaperFocalAreaInteractor
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

private val TAG = KeyguardClockInteractor::class.simpleName

/** Manages and encapsulates the clock components of the lockscreen root view. */
@SysUISingleton
class KeyguardClockInteractor
@Inject
constructor(
    mediaCarouselInteractor: MediaCarouselInteractor,
    activeNotificationsInteractor: ActiveNotificationsInteractor,
    aodPromotedNotificationInteractor: AODPromotedNotificationInteractor,
    shadeModeInteractor: ShadeModeInteractor,
    keyguardInteractor: KeyguardInteractor,
    keyguardTransitionInteractor: KeyguardTransitionInteractor,
    headsUpNotificationInteractor: HeadsUpNotificationInteractor,
    @Application private val applicationScope: CoroutineScope,
    val keyguardClockRepository: KeyguardClockRepository,
    private val wallpaperFocalAreaInteractor: WallpaperFocalAreaInteractor,
) {
    private val isOnAod: Flow<Boolean> =
        keyguardTransitionInteractor.currentKeyguardState.map { it == AOD }

    /**
     * The clock size setting explicitly selected by the user. When it is `SMALL`, the large clock
     * is never shown. When it is `DYNAMIC`, the clock size gets determined based on a combination
     * of system signals.
     */
    val selectedClockSize: StateFlow<ClockSizeSetting> = keyguardClockRepository.selectedClockSize

    val currentClockId: Flow<ClockId> = keyguardClockRepository.currentClockId

    val currentClock: StateFlow<ClockController?> = keyguardClockRepository.currentClock

    val previewClock: Flow<ClockController> = keyguardClockRepository.previewClock

    val clockEventController: ClockEventController = keyguardClockRepository.clockEventController

    var clock: ClockController? by keyguardClockRepository.clockEventController::clock

    private val isAodPromotedNotificationPresent: Flow<Boolean> =
        if (PromotedNotificationUi.isEnabled) {
            aodPromotedNotificationInteractor.isPresent
        } else {
            flowOf(false)
        }

    private val areAnyNotificationsPresent: Flow<Boolean> =
        if (PromotedNotificationUi.isEnabled) {
            combine(
                activeNotificationsInteractor.areAnyNotificationsPresent,
                isAodPromotedNotificationPresent,
            ) { areAnyNotificationsPresent, isAodPromotedNotificationPresent ->
                areAnyNotificationsPresent || isAodPromotedNotificationPresent
            }
        } else {
            activeNotificationsInteractor.areAnyNotificationsPresent
        }

    private val dynamicClockSize: Flow<ClockSize> =
        if (SceneContainerFlag.isEnabled) {
            combine(
                shadeModeInteractor.isShadeLayoutWide,
                areAnyNotificationsPresent,
                mediaCarouselInteractor.hasActiveMediaOrRecommendation,
                keyguardInteractor.isDozing,
                isOnAod,
            ) { isShadeLayoutWide, hasNotifs, hasMedia, isDozing, isOnAod ->
                when {
                    keyguardClockRepository.shouldForceSmallClock && !isOnAod -> ClockSize.SMALL
                    !isShadeLayoutWide && (hasNotifs || hasMedia) -> ClockSize.SMALL
                    !isShadeLayoutWide -> ClockSize.LARGE
                    hasMedia && !isDozing -> ClockSize.SMALL
                    else -> ClockSize.LARGE
                }
            }
        } else {
            keyguardClockRepository.clockSize
        }

    val clockSize: StateFlow<ClockSize> =
        selectedClockSize
            .flatMapLatestConflated { selectedSize ->
                if (selectedSize == ClockSizeSetting.SMALL) {
                    flowOf(ClockSize.SMALL)
                } else {
                    dynamicClockSize
                }
            }
            .stateIn(
                scope = applicationScope,
                started = SharingStarted.Eagerly,
                initialValue = ClockSize.LARGE,
            )

    val clockShouldBeCentered: Flow<Boolean> =
        if (SceneContainerFlag.isEnabled) {
            combine(
                shadeModeInteractor.isShadeLayoutWide,
                areAnyNotificationsPresent,
                isAodPromotedNotificationPresent,
                isOnAod,
                headsUpNotificationInteractor.isHeadsUpOrAnimatingAway,
                keyguardInteractor.isDozing,
            ) {
                isShadeLayoutWide,
                areAnyNotificationsPresent,
                isAodPromotedNotificationPresent,
                isOnAod,
                isHeadsUp,
                isDozing ->
                when {
                    !isShadeLayoutWide -> true
                    !areAnyNotificationsPresent -> true
                    // Pulsing notification appears on the right. Move clock left to avoid overlap.
                    isHeadsUp && isDozing -> false
                    isAodPromotedNotificationPresent -> false
                    else -> isOnAod
                }
            }
        } else {
            combine(
                    shadeModeInteractor.isShadeLayoutWide,
                    areAnyNotificationsPresent,
                    isAodPromotedNotificationPresent,
                    keyguardInteractor.dozeTransitionModel,
                    keyguardTransitionInteractor.startedKeyguardTransitionStep.map { it.to == AOD },
                    keyguardTransitionInteractor.startedKeyguardTransitionStep.map {
                        it.to == LOCKSCREEN
                    },
                    keyguardTransitionInteractor.startedKeyguardTransitionStep.map {
                        it.to == DOZING
                    },
                    keyguardInteractor.isPulsing,
                    keyguardTransitionInteractor.startedKeyguardTransitionStep.map { it.to == GONE },
                ) {
                    isShadeLayoutWide,
                    areAnyNotificationsPresent,
                    isAodPromotedNotificationPresent,
                    dozeTransitionModel,
                    startedToAod,
                    startedToLockScreen,
                    startedToDoze,
                    isPulsing,
                    startedToGone ->
                    when {
                        !isShadeLayoutWide -> true
                        // [areAnyNotificationsPresent] also reacts to notification stack in
                        // homescreen
                        // it may cause unnecessary `false` emission when there's notification in
                        // homescreen
                        // but none in lockscreen when going from GONE to AOD / DOZING
                        // use null to skip emitting wrong value
                        startedToGone || startedToDoze -> null
                        startedToLockScreen -> !areAnyNotificationsPresent
                        startedToAod -> !(isPulsing || isAodPromotedNotificationPresent)
                        else -> true
                    }
                }
                .filterNotNull()
        }

    fun setClockSize(size: ClockSize) {
        SceneContainerFlag.assertInLegacyMode()
        keyguardClockRepository.setClockSize(size)
    }

    val renderedClockId: ClockId
        get() {
            return clock?.config?.id
                ?: run {
                    Log.e(TAG, "No clock is available")
                    "MISSING_CLOCK_ID"
                }
        }

    fun handleFidgetTap(x: Float, y: Float) {
        if (!com.android.systemui.Flags.clockFidgetAnimation()) {
            return
        }

        if (selectedClockSize.value == ClockSizeSetting.DYNAMIC) {
            clockEventController.handleFidgetTap(x, y)
        } else {
            wallpaperFocalAreaInteractor.setTapPosition(x, y)
        }
    }

    fun animateFoldToAod(foldFraction: Float) {
        clock?.let { clock ->
            clock.smallClock.animations.fold(foldFraction)
            clock.largeClock.animations.fold(foldFraction)
        }
    }

    fun setNotificationStackDefaultTop(top: Float) {
        wallpaperFocalAreaInteractor.setNotificationDefaultTop(top)
    }
}
