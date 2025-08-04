/*
 * Copyright (C) 2025 The Android Open Source Project
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

package com.android.systemui.keyguard.ui.viewmodel

import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.keyguard.domain.interactor.FromPrimaryBouncerTransitionInteractor
import com.android.systemui.keyguard.shared.model.Edge
import com.android.systemui.keyguard.shared.model.KeyguardState.DREAMING
import com.android.systemui.keyguard.shared.model.KeyguardState.PRIMARY_BOUNCER
import com.android.systemui.keyguard.ui.KeyguardTransitionAnimationFlow
import com.android.systemui.keyguard.ui.transitions.BlurConfig
import com.android.systemui.keyguard.ui.transitions.PrimaryBouncerTransition
import com.android.systemui.scene.shared.model.Overlays
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

@SysUISingleton
class PrimaryBouncerToDreamingTransitionViewModel
@Inject
constructor(blurConfig: BlurConfig, animationFlow: KeyguardTransitionAnimationFlow) :
    PrimaryBouncerTransition {
    private val transitionAnimation =
        animationFlow
            .setup(
                duration = FromPrimaryBouncerTransitionInteractor.TO_DREAMING_DURATION,
                edge = Edge.create(from = Overlays.Bouncer, to = DREAMING),
            )
            .setupWithoutSceneContainer(edge = Edge.create(from = PRIMARY_BOUNCER, to = DREAMING))

    override val windowBlurRadius: Flow<Float> =
        transitionAnimation.sharedFlow(
            onStart = { blurConfig.maxBlurRadiusPx },
            onStep = {
                transitionProgressToBlurRadius(
                    blurConfig.maxBlurRadiusPx,
                    endBlurRadius = blurConfig.minBlurRadiusPx,
                    transitionProgress = it,
                )
            },
            onFinish = { blurConfig.minBlurRadiusPx },
        )

    override val notificationBlurRadius: Flow<Float> = emptyFlow()
}
