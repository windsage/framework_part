/*
 * Copyright (C) 2022 The Android Open Source Project
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

// QTI_BEGIN: 2024-01-30: Android_UI: SystemUI: Implementation for MSIM C_IWLAN feature
import android.telephony.SubscriptionManager
// QTI_END: 2024-01-30: Android_UI: SystemUI: Implementation for MSIM C_IWLAN feature
import androidx.annotation.VisibleForTesting
import com.android.app.tracing.coroutines.launchTraced as launch
import com.android.systemui.coroutines.newTracingContext
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.statusbar.phone.StatusBarLocation
import com.android.systemui.statusbar.pipeline.airplane.domain.interactor.AirplaneModeInteractor
import com.android.systemui.statusbar.pipeline.mobile.domain.interactor.MobileIconsInteractor
import com.android.systemui.statusbar.pipeline.mobile.ui.MobileViewLogger
import com.android.systemui.statusbar.pipeline.mobile.ui.VerboseMobileViewLogger
// QTI_BEGIN: 2024-01-30: Android_UI: SystemUI: Implementation for MSIM C_IWLAN feature
import com.android.systemui.statusbar.pipeline.mobile.domain.model.SignalIconModel
// QTI_END: 2024-01-30: Android_UI: SystemUI: Implementation for MSIM C_IWLAN feature
import com.android.systemui.statusbar.pipeline.mobile.ui.view.ModernStatusBarMobileView
import com.android.systemui.statusbar.pipeline.shared.ConnectivityConstants
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
/**
 * View model for describing the system's current mobile cellular connections. The result is a list
 * of [MobileIconViewModel]s which describe the individual icons and can be bound to
 * [ModernStatusBarMobileView].
 */
@SysUISingleton
class MobileIconsViewModel
@Inject
constructor(
    val logger: MobileViewLogger,
    private val verboseLogger: VerboseMobileViewLogger,
    private val interactor: MobileIconsInteractor,
    private val airplaneModeInteractor: AirplaneModeInteractor,
    private val constants: ConnectivityConstants,
    @Background private val scope: CoroutineScope,
) {
    @VisibleForTesting
    val reuseCache = ConcurrentHashMap<Int, Pair<MobileIconViewModel, CoroutineScope>>()
    val activeMobileDataSubscriptionId: StateFlow<Int?> = interactor.activeMobileDataSubscriptionId

// QTI_BEGIN: 2024-08-01: Android_UI: SystemUI: Fix DDS signal strength is null issue.
    val subscriptionIdsFlow: StateFlow<List<Int>> =
        interactor.filteredSubscriptions
            .mapLatest { subscriptions ->
                subscriptions.map { subscriptionModel -> subscriptionModel.subscriptionId }
            }
            .stateIn(scope, SharingStarted.WhileSubscribed(), listOf())

// QTI_END: 2024-08-01: Android_UI: SystemUI: Fix DDS signal strength is null issue.
// QTI_BEGIN: 2024-01-30: Android_UI: SystemUI: Implementation for MSIM C_IWLAN feature
    private val ddsIcon: StateFlow<SignalIconModel?> =
// QTI_END: 2024-01-30: Android_UI: SystemUI: Implementation for MSIM C_IWLAN feature
// QTI_BEGIN: 2024-08-01: Android_UI: SystemUI: Fix DDS signal strength is null issue.
        combine(interactor.defaultDataSubId, subscriptionIdsFlow) {
                defaultDataSubId, subscriptionIdsFlow ->
// QTI_END: 2024-08-01: Android_UI: SystemUI: Fix DDS signal strength is null issue.
                if (defaultDataSubId ?: -1 > SubscriptionManager.INVALID_SUBSCRIPTION_ID
// QTI_BEGIN: 2024-08-01: Android_UI: SystemUI: Fix DDS signal strength is null issue.
                    && subscriptionIdsFlow.contains(defaultDataSubId)) {
// QTI_END: 2024-08-01: Android_UI: SystemUI: Fix DDS signal strength is null issue.
                    commonViewModelForSub(defaultDataSubId ?: -1)
// QTI_BEGIN: 2024-01-30: Android_UI: SystemUI: Implementation for MSIM C_IWLAN feature
                } else {
                    null
                }
            }
            .flatMapLatest { viewModel ->
                viewModel?.icon ?: flowOf(null)
            }
            .stateIn(scope, SharingStarted.WhileSubscribed(), null)

// QTI_END: 2024-01-30: Android_UI: SystemUI: Implementation for MSIM C_IWLAN feature
    val mobileSubViewModels: StateFlow<List<MobileIconViewModelCommon>> =
        subscriptionIdsFlow
            .map { ids -> ids.map { commonViewModelForSub(it) } }
            .stateIn(scope, SharingStarted.WhileSubscribed(), emptyList())
    private val firstMobileSubViewModel: StateFlow<MobileIconViewModelCommon?> =
        mobileSubViewModels
            .map {
                if (it.isEmpty()) {
                    null
                } else {
                    // Mobile icons get reversed by [StatusBarIconController], so the last element
                    // in this list will show up visually first.
                    it.last()
                }
            }
            .stateIn(scope, SharingStarted.WhileSubscribed(), null)
    /**
     * A flow that emits `true` if the mobile sub that's displayed first visually is showing its
     * network type icon and `false` otherwise.
     */
    val firstMobileSubShowingNetworkTypeIcon: StateFlow<Boolean> =
        firstMobileSubViewModel
            .flatMapLatest { firstMobileSubViewModel ->
                firstMobileSubViewModel?.networkTypeIcon?.map { it != null } ?: flowOf(false)
            }
            .stateIn(scope, SharingStarted.WhileSubscribed(), false)
    /** Whether all of [mobileSubViewModels] are visible or not. */
    private val iconsAreAllVisible =
        mobileSubViewModels.flatMapLatest { viewModels ->
            combine(viewModels.map { it.isVisible }) { isVisibleArray -> isVisibleArray.all { it } }
        }

    val isStackable: StateFlow<Boolean> =
        combine(iconsAreAllVisible, interactor.isStackable) { isVisible, isStackable ->
                isVisible && isStackable
            }
            .stateIn(scope, SharingStarted.WhileSubscribed(), false)
    init {
// QTI_BEGIN: 2024-01-30: Android_UI: SystemUI: Implementation for MSIM C_IWLAN feature
        interactor.setDdsIconFLow(ddsIcon)
// QTI_END: 2024-01-30: Android_UI: SystemUI: Implementation for MSIM C_IWLAN feature
        scope.launch { subscriptionIdsFlow.collect { invalidateCaches(it) } }
    }
    fun viewModelForSub(subId: Int, location: StatusBarLocation): LocationBasedMobileViewModel {
        val common = commonViewModelForSub(subId)
        return LocationBasedMobileViewModel.viewModelForLocation(
            common,
            interactor.getMobileConnectionInteractorForSubId(subId),
            verboseLogger,
            location,
            scope,
        )
    }
    private fun commonViewModelForSub(subId: Int): MobileIconViewModelCommon {
        return reuseCache.getOrPut(subId) { createViewModel(subId) }.first
    }
    private fun createViewModel(subId: Int): Pair<MobileIconViewModel, CoroutineScope> {
        // Create a child scope so we can cancel it
        val vmScope = scope.createChildScope(newTracingContext("MobileIconViewModel"))
        val vm =
            MobileIconViewModel(
                subId,
                interactor.getMobileConnectionInteractorForSubId(subId),
                airplaneModeInteractor,
                constants,
                vmScope,
            )
        return Pair(vm, vmScope)
    }
    private fun CoroutineScope.createChildScope(extraContext: CoroutineContext) =
        CoroutineScope(coroutineContext + Job(coroutineContext[Job]) + extraContext)
    private fun invalidateCaches(subIds: List<Int>) {
        reuseCache.keys
            .filter { !subIds.contains(it) }
            .forEach { id ->
                reuseCache
                    .remove(id)
                    // Cancel the view model's scope after removing it
                    ?.second
                    ?.cancel()
            }
    }
}