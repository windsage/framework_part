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

package com.android.systemui.statusbar.pipeline.mobile.ui.viewmodel

import androidx.compose.runtime.getValue
import com.android.systemui.KairosBuilder
import com.android.systemui.common.shared.model.Icon
import com.android.systemui.kairos.ExperimentalKairosApi
import com.android.systemui.kairos.State as KairosState
import com.android.systemui.kairos.combine
import com.android.systemui.kairos.flatMap
import com.android.systemui.kairos.stateOf
import com.android.systemui.kairosBuilder
import com.android.systemui.statusbar.pipeline.mobile.domain.model.SignalIconModel
import com.android.systemui.statusbar.pipeline.mobile.ui.viewmodel.StackedMobileIconViewModel.DualSim
import com.android.systemui.util.composable.kairos.hydratedComposeStateOf
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject

@OptIn(ExperimentalKairosApi::class)
class StackedMobileIconViewModelKairos
@AssistedInject
constructor(mobileIcons: MobileIconsViewModelKairos) :
    KairosBuilder by kairosBuilder(), StackedMobileIconViewModel {

    private val isStackable: Boolean by
        hydratedComposeStateOf(mobileIcons.isStackable, initialValue = false)

    private val iconList: KairosState<List<MobileIconViewModelKairos>> =
        combine(mobileIcons.icons, mobileIcons.activeSubscriptionId) { iconsBySubId, activeSubId ->
            buildList {
                activeSubId?.let { iconsBySubId[activeSubId]?.let { add(it) } }
                addAll(iconsBySubId.values.asSequence().filter { it.subscriptionId != activeSubId })
            }
        }

    override val dualSim: DualSim? by
        hydratedComposeStateOf(
            iconList.flatMap { icons ->
                icons.map { it.icon }.combine { signalIcons -> tryParseDualSim(signalIcons) }
            },
            initialValue = null,
        )

    override val networkTypeIcon: Icon.Resource? by
        hydratedComposeStateOf(
            iconList.flatMap { icons -> icons.firstOrNull()?.networkTypeIcon ?: stateOf(null) },
            initialValue = null,
        )

    override val isIconVisible: Boolean
        get() = isStackable && dualSim != null

    private fun tryParseDualSim(icons: List<SignalIconModel>): DualSim? {
        var first: SignalIconModel.Cellular? = null
        var second: SignalIconModel.Cellular? = null
        for (icon in icons) {
            when {
                icon !is SignalIconModel.Cellular -> continue
                first == null -> first = icon
                second == null -> second = icon
                else -> return null
            }
        }
        return first?.let { second?.let { DualSim(first, second) } }
    }

    @AssistedFactory
    interface Factory {
        fun create(): StackedMobileIconViewModelKairos
    }
}
