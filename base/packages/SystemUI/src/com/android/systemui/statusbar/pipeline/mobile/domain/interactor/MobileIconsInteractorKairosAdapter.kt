/*
 * Copyright (C) 2024 The Android Open Source Project
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
package com.android.systemui.statusbar.pipeline.mobile.domain.interactor
import android.content.Context
import com.android.settingslib.SignalIcon
import com.android.settingslib.mobile.MobileMappings
import com.android.systemui.Flags
import com.android.systemui.KairosActivatable
import com.android.systemui.KairosBuilder
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.kairos.ExperimentalKairosApi
import com.android.systemui.kairos.KairosNetwork
import com.android.systemui.kairos.buildSpec
import com.android.systemui.kairos.combine
import com.android.systemui.kairos.map
import com.android.systemui.kairos.mapValues
import com.android.systemui.kairos.toColdConflatedFlow
import com.android.systemui.kairosBuilder
import com.android.systemui.log.table.TableLogBuffer
import com.android.systemui.log.table.TableLogBufferFactory
import com.android.systemui.statusbar.pipeline.mobile.data.model.MobileIconCustomizationMode
import com.android.systemui.statusbar.pipeline.mobile.data.model.NetworkNameModel
import com.android.systemui.statusbar.pipeline.mobile.data.model.SubscriptionModel
import com.android.systemui.statusbar.pipeline.mobile.data.repository.MobileConnectionsRepository
import com.android.systemui.statusbar.pipeline.mobile.data.repository.MobileConnectionsRepositoryKairos
import com.android.systemui.statusbar.pipeline.mobile.data.repository.prod.FullMobileConnectionRepository.Factory.Companion.MOBILE_CONNECTION_BUFFER_SIZE
import com.android.systemui.statusbar.pipeline.mobile.data.repository.prod.FullMobileConnectionRepository.Factory.Companion.tableBufferLogName
import com.android.systemui.statusbar.pipeline.mobile.domain.model.NetworkTypeIconModel
import com.android.systemui.statusbar.pipeline.mobile.domain.model.SignalIconModel
import com.android.systemui.statusbar.pipeline.mobile.util.MobileMappingsProxy
import com.android.systemui.statusbar.pipeline.shared.data.model.DataActivityModel
import com.android.systemui.statusbar.policy.data.repository.UserSetupRepository
import com.android.systemui.utils.coroutines.flow.flatMapLatestConflated
import dagger.Provides
import dagger.multibindings.ElementsIntoSet
import javax.inject.Inject
import javax.inject.Provider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.MutableStateFlow
@ExperimentalKairosApi
@SysUISingleton
class MobileIconsInteractorKairosAdapter
@Inject
constructor(
    private val kairosInteractor: MobileIconsInteractorKairos,
    private val repo: MobileConnectionsRepository,
    repoK: MobileConnectionsRepositoryKairos,
    kairosNetwork: KairosNetwork,
    @Application scope: CoroutineScope,
    context: Context,
    mobileMappingsProxy: MobileMappingsProxy,
    private val userSetupRepo: UserSetupRepository,
    private val logFactory: TableLogBufferFactory,
) : MobileIconsInteractor, KairosBuilder by kairosBuilder() {
    private val interactorsBySubIdK = buildIncremental {
        kairosInteractor.icons
            .mapValues { (subId, interactor) ->
                buildSpec { MobileIconInteractorKairosAdapter(interactor) }
            }
            .applyLatestSpecForKey()
    }
    private val interactorsBySubId =
        interactorsBySubIdK
            .toColdConflatedFlow(kairosNetwork)
            .stateIn(scope, SharingStarted.Eagerly, emptyMap())
    override val defaultDataSubId: Flow<Int?>
        get() = repo.defaultDataSubId
    override val mobileIsDefault: StateFlow<Boolean> =
        kairosInteractor.mobileIsDefault
            .toColdConflatedFlow(kairosNetwork)
            .stateIn(scope, SharingStarted.WhileSubscribed(), repo.mobileIsDefault.value)
    override val filteredSubscriptions: Flow<List<SubscriptionModel>> =
        kairosInteractor.filteredSubscriptions.toColdConflatedFlow(kairosNetwork)
    override val icons: StateFlow<List<MobileIconInteractor>> =
        interactorsBySubIdK
            .map { it.values.toList() }
            .toColdConflatedFlow(kairosNetwork)
            .stateIn(scope, SharingStarted.WhileSubscribed(), emptyList())
    override val isStackable: StateFlow<Boolean> =
        kairosInteractor.isStackable
            .toColdConflatedFlow(kairosNetwork)
            .stateIn(scope, SharingStarted.WhileSubscribed(), false)
    override val activeMobileDataSubscriptionId: StateFlow<Int?>
        get() = repo.activeMobileDataSubscriptionId
    override val activeDataConnectionHasDataEnabled: StateFlow<Boolean> =
        kairosInteractor.activeDataConnectionHasDataEnabled
            .toColdConflatedFlow(kairosNetwork)
            .stateIn(scope, SharingStarted.WhileSubscribed(), false)
    override val activeDataIconInteractor: StateFlow<MobileIconInteractor?> =
        combine(repoK.activeMobileDataSubscriptionId, interactorsBySubIdK) { subId, interactors ->
                interactors[subId]
            }
            .toColdConflatedFlow(kairosNetwork)
            .stateIn(scope, SharingStarted.WhileSubscribed(), null)
    override val alwaysShowDataRatIcon: StateFlow<Boolean> =
        kairosInteractor.alwaysShowDataRatIcon
            .toColdConflatedFlow(kairosNetwork)
            .stateIn(scope, SharingStarted.WhileSubscribed(), false)
    override val alwaysUseCdmaLevel: StateFlow<Boolean> =
        kairosInteractor.alwaysUseCdmaLevel
            .toColdConflatedFlow(kairosNetwork)
            .stateIn(scope, SharingStarted.WhileSubscribed(), false)
    override val isSingleCarrier: StateFlow<Boolean> =
        kairosInteractor.isSingleCarrier
            .toColdConflatedFlow(kairosNetwork)
            .stateIn(scope, SharingStarted.WhileSubscribed(), false)
    override val defaultMobileIconMapping: StateFlow<Map<String, SignalIcon.MobileIconGroup>> =
        kairosInteractor.defaultMobileIconMapping
            .toColdConflatedFlow(kairosNetwork)
            .stateIn(scope, SharingStarted.WhileSubscribed(), emptyMap())
    override val defaultMobileIconGroup: StateFlow<SignalIcon.MobileIconGroup> =
        kairosInteractor.defaultMobileIconGroup
            .toColdConflatedFlow(kairosNetwork)
            .stateIn(
                scope,
                SharingStarted.WhileSubscribed(),
                mobileMappingsProxy.getDefaultIcons(MobileMappings.Config.readConfig(context)),
            )
    override val isDefaultConnectionFailed: StateFlow<Boolean> =
        kairosInteractor.isDefaultConnectionFailed
            .toColdConflatedFlow(kairosNetwork)
            .stateIn(scope, SharingStarted.WhileSubscribed(), false)
    override val isUserSetUp: StateFlow<Boolean>
        get() = userSetupRepo.isUserSetUp
    override val isForceHidden: Flow<Boolean> =
        kairosInteractor.isForceHidden
            .toColdConflatedFlow(kairosNetwork)
            .stateIn(scope, SharingStarted.WhileSubscribed(), false)
    override val isDeviceInEmergencyCallsOnlyMode: Flow<Boolean>
        get() = repo.isDeviceEmergencyCallCapable

    override val showVowifiIcon = MutableStateFlow(false)
    override val showVolteIcon = MutableStateFlow(false)
    override val networkTypeIconCustomization = MutableStateFlow(MobileIconCustomizationMode())
    override val hideNoInternetState = MutableStateFlow(false)
    override val alwaysUseRsrpLevelForLte = MutableStateFlow(false)

    override fun getMobileConnectionInteractorForSubId(subId: Int): MobileIconInteractor =
        object : MobileIconInteractor {
            override val tableLogBuffer: TableLogBuffer =
                logFactory.getOrCreate(tableBufferLogName(subId), MOBILE_CONNECTION_BUFFER_SIZE)
            override val activity: Flow<DataActivityModel> = latest { activity }
            override val mobileIsDefault: Flow<Boolean> = latest { mobileIsDefault }
            override val isDataConnected: Flow<Boolean> = latest { isDataConnected }
            override val isInService: Flow<Boolean> = latest { isInService }
            override val isEmergencyOnly: Flow<Boolean> = latest { isEmergencyOnly }
            override val isDataEnabled: Flow<Boolean> = latest { isDataEnabled }
            override val alwaysShowDataRatIcon: Flow<Boolean> = latest { alwaysShowDataRatIcon }
            override val signalLevelIcon: Flow<SignalIconModel> = latest { signalLevelIcon }
            override val networkTypeIconGroup: Flow<NetworkTypeIconModel> = latest {
                networkTypeIconGroup
            }
            override val showSliceAttribution: Flow<Boolean> = latest { showSliceAttribution }
            override val isNonTerrestrial: Flow<Boolean> = latest { isNonTerrestrial }
            override val networkName: Flow<NetworkNameModel> = latest { networkName }
            override val carrierName: Flow<String> = latest { carrierName }
            override val isSingleCarrier: Flow<Boolean> = latest { isSingleCarrier }
            override val isRoaming: Flow<Boolean> = latest { isRoaming }
            override val isForceHidden: Flow<Boolean> = latest { isForceHidden }

            override val isConnectionFailed = latest { isConnectionFailed }
            override val customizedNetworkName = latest { customizedNetworkName }
            override val customizedCarrierName = latest { customizedCarrierName }
            override val customizedIcon = latest { customizedIcon }
            override val voWifiAvailable = latest { voWifiAvailable }
            override val showVowifiIcon = latest { showVowifiIcon }
            override val imsInfo = latest { imsInfo }
            override val showVolteIcon = latest { showVolteIcon }
            override val networkTypeIconCustomization = latest { networkTypeIconCustomization }
            override val hideNoInternetState = latest { hideNoInternetState }
            override val alwaysUseRsrpLevelForLte = latest { alwaysUseRsrpLevelForLte }

            override val isAllowedDuringAirplaneMode: Flow<Boolean> = latest {
                isAllowedDuringAirplaneMode
            }
            override val carrierNetworkChangeActive: Flow<Boolean> = latest {
                carrierNetworkChangeActive
            }

            private fun <T> latest(block: MobileIconInteractor.() -> Flow<T>): Flow<T> =
                interactorsBySubId.flatMapLatestConflated { it[subId]?.block() ?: emptyFlow() }
        }

    @dagger.Module
    object Module {
        @Provides
        @ElementsIntoSet
        fun kairosActivatable(
            impl: Provider<MobileIconsInteractorKairosAdapter>
        ): Set<@JvmSuppressWildcards KairosActivatable> =
            if (Flags.statusBarMobileIconKairos()) setOf(impl.get()) else emptySet()
    }
}
