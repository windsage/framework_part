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

package com.android.systemui.statusbar.pipeline.mobile.data.repository.prod

import android.util.IndentingPrintWriter
import androidx.annotation.VisibleForTesting
import com.android.systemui.KairosBuilder
import com.android.systemui.kairos.BuildSpec
import com.android.systemui.kairos.ExperimentalKairosApi
import com.android.systemui.kairos.State
import com.android.systemui.kairos.combine
import com.android.systemui.kairos.flatMap
import com.android.systemui.kairosBuilder
import com.android.systemui.log.table.TableLogBuffer
import com.android.systemui.log.table.logDiffsForTable
import com.android.systemui.statusbar.pipeline.mobile.data.model.DataConnectionState
import com.android.systemui.statusbar.pipeline.mobile.data.model.NetworkNameModel
import com.android.systemui.statusbar.pipeline.mobile.data.model.ResolvedNetworkType
import com.android.systemui.statusbar.pipeline.mobile.data.repository.MobileConnectionRepositoryKairos
import com.android.systemui.statusbar.pipeline.shared.data.model.DataActivityModel
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import java.io.PrintWriter

/**
 * A repository that fully implements a mobile connection.
 *
 * This connection could either be a typical mobile connection (see [MobileConnectionRepositoryImpl]
 * or a carrier merged connection (see [CarrierMergedConnectionRepository]). This repository
 * switches between the two types of connections based on whether the connection is currently
 * carrier merged.
 */
@ExperimentalKairosApi
class FullMobileConnectionRepositoryKairos
@AssistedInject
constructor(
    @Assisted override val subId: Int,
    @Assisted override val tableLogBuffer: TableLogBuffer,
    @Assisted private val mobileRepo: MobileConnectionRepositoryKairos,
    @Assisted private val carrierMergedRepoSpec: BuildSpec<MobileConnectionRepositoryKairos>,
    @Assisted private val isCarrierMerged: State<Boolean>,
) : MobileConnectionRepositoryKairos, KairosBuilder by kairosBuilder() {

    private var dumpCache: DumpCache? = null

    init {
        onActivated {
            logDiffsForTable(isCarrierMerged, tableLogBuffer, columnName = "isCarrierMerged")
            combine(isCarrierMerged, activeRepo) { isCarrierMerged, activeRepo ->
                    DumpCache(isCarrierMerged, activeRepo)
                }
                .observe { dumpCache = it }
        }
    }

    @VisibleForTesting
    val activeRepo: State<MobileConnectionRepositoryKairos> = buildState {
        isCarrierMerged.mapLatestBuild { merged ->
            if (merged) {
                carrierMergedRepoSpec.applySpec()
            } else {
                mobileRepo
            }
        }
    }

    override val carrierId: State<Int> = activeRepo.flatMap { it.carrierId }

    override val cdmaRoaming: State<Boolean> = activeRepo.flatMap { it.cdmaRoaming }

    override val isEmergencyOnly: State<Boolean> =
        activeRepo
            .flatMap { it.isEmergencyOnly }
            .also {
                onActivated { logDiffsForTable(it, tableLogBuffer, columnName = COL_EMERGENCY) }
            }

    override val isRoaming: State<Boolean> =
        activeRepo
            .flatMap { it.isRoaming }
            .also { onActivated { logDiffsForTable(it, tableLogBuffer, columnName = COL_ROAMING) } }

    override val operatorAlphaShort: State<String?> =
        activeRepo
            .flatMap { it.operatorAlphaShort }
            .also {
                onActivated { logDiffsForTable(it, tableLogBuffer, columnName = COL_OPERATOR) }
            }

    override val isInService: State<Boolean> =
        activeRepo
            .flatMap { it.isInService }
            .also {
                onActivated { logDiffsForTable(it, tableLogBuffer, columnName = COL_IS_IN_SERVICE) }
            }

    override val isNonTerrestrial: State<Boolean> =
        activeRepo
            .flatMap { it.isNonTerrestrial }
            .also { onActivated { logDiffsForTable(it, tableLogBuffer, columnName = COL_IS_NTN) } }

    override val isGsm: State<Boolean> =
        activeRepo
            .flatMap { it.isGsm }
            .also { onActivated { logDiffsForTable(it, tableLogBuffer, columnName = COL_IS_GSM) } }

    override val cdmaLevel: State<Int> =
        activeRepo
            .flatMap { it.cdmaLevel }
            .also {
                onActivated { logDiffsForTable(it, tableLogBuffer, columnName = COL_CDMA_LEVEL) }
            }

    override val primaryLevel: State<Int> =
        activeRepo
            .flatMap { it.primaryLevel }
            .also {
                onActivated { logDiffsForTable(it, tableLogBuffer, columnName = COL_PRIMARY_LEVEL) }
            }

    override val satelliteLevel: State<Int> =
        activeRepo
            .flatMap { it.satelliteLevel }
            .also {
                onActivated {
                    logDiffsForTable(it, tableLogBuffer, columnName = COL_SATELLITE_LEVEL)
                }
            }

    override val dataConnectionState: State<DataConnectionState> =
        activeRepo
            .flatMap { it.dataConnectionState }
            .also { onActivated { logDiffsForTable(it, tableLogBuffer, columnPrefix = "") } }

    override val dataActivityDirection: State<DataActivityModel> =
        activeRepo
            .flatMap { it.dataActivityDirection }
            .also { onActivated { logDiffsForTable(it, tableLogBuffer, columnPrefix = "") } }

    override val carrierNetworkChangeActive: State<Boolean> =
        activeRepo
            .flatMap { it.carrierNetworkChangeActive }
            .also {
                onActivated {
                    logDiffsForTable(it, tableLogBuffer, columnName = COL_CARRIER_NETWORK_CHANGE)
                }
            }

    override val resolvedNetworkType: State<ResolvedNetworkType> =
        activeRepo
            .flatMap { it.resolvedNetworkType }
            .also { onActivated { logDiffsForTable(it, tableLogBuffer, columnPrefix = "") } }

    override val dataEnabled: State<Boolean> =
        activeRepo
            .flatMap { it.dataEnabled }
            .also {
                onActivated { logDiffsForTable(it, tableLogBuffer, columnName = "dataEnabled") }
            }

    override val inflateSignalStrength: State<Boolean> =
        activeRepo
            .flatMap { it.inflateSignalStrength }
            .also { onActivated { logDiffsForTable(it, tableLogBuffer, columnName = "inflate") } }

    override val allowNetworkSliceIndicator: State<Boolean> =
        activeRepo
            .flatMap { it.allowNetworkSliceIndicator }
            .also {
                onActivated { logDiffsForTable(it, tableLogBuffer, columnName = "allowSlice") }
            }

    override val numberOfLevels: State<Int> = activeRepo.flatMap { it.numberOfLevels }

    override val networkName: State<NetworkNameModel> =
        activeRepo
            .flatMap { it.networkName }
            .also { onActivated { logDiffsForTable(it, tableLogBuffer, columnPrefix = "intent") } }

    override val carrierName: State<NetworkNameModel> =
        activeRepo
            .flatMap { it.carrierName }
            .also { onActivated { logDiffsForTable(it, tableLogBuffer, columnPrefix = "sub") } }

    override val isAllowedDuringAirplaneMode: State<Boolean> =
        activeRepo.flatMap { it.isAllowedDuringAirplaneMode }

    override val hasPrioritizedNetworkCapabilities: State<Boolean> =
        activeRepo.flatMap { it.hasPrioritizedNetworkCapabilities }

    override val isInEcmMode: State<Boolean> = activeRepo.flatMap { it.isInEcmMode }

    fun dump(pw: PrintWriter) {
        val cache = dumpCache ?: return
        val ipw = IndentingPrintWriter(pw, "  ")

        ipw.println("MobileConnectionRepository[$subId]")
        ipw.increaseIndent()

        ipw.println("carrierMerged=${cache.isCarrierMerged}")

        ipw.print("Type (cellular or carrier merged): ")
        when (cache.activeRepo) {
            is CarrierMergedConnectionRepositoryKairos -> ipw.println("Carrier merged")
            is MobileConnectionRepositoryKairosImpl -> ipw.println("Cellular")
        }

        ipw.increaseIndent()
        ipw.println("Provider: ${cache.activeRepo}")
        ipw.decreaseIndent()

        ipw.decreaseIndent()
    }

    private data class DumpCache(
        val isCarrierMerged: Boolean,
        val activeRepo: MobileConnectionRepositoryKairos,
    )

    @AssistedFactory
    interface Factory {
        fun create(
            subId: Int,
            mobileLogger: TableLogBuffer,
            isCarrierMerged: State<Boolean>,
            mobileRepo: MobileConnectionRepositoryKairos,
            mergedRepoSpec: BuildSpec<MobileConnectionRepositoryKairos>,
        ): FullMobileConnectionRepositoryKairos
    }

    companion object {
        const val COL_CARRIER_ID = "carrierId"
        const val COL_CARRIER_NETWORK_CHANGE = "carrierNetworkChangeActive"
        const val COL_CDMA_LEVEL = "cdmaLevel"
        const val COL_EMERGENCY = "emergencyOnly"
        const val COL_IS_NTN = "isNtn"
        const val COL_IS_GSM = "isGsm"
        const val COL_IS_IN_SERVICE = "isInService"
        const val COL_OPERATOR = "operatorName"
        const val COL_PRIMARY_LEVEL = "primaryLevel"
        const val COL_SATELLITE_LEVEL = "satelliteLevel"
        const val COL_ROAMING = "roaming"
    }
}
