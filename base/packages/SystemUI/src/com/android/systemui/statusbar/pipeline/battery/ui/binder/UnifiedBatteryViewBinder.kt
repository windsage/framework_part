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

package com.android.systemui.statusbar.pipeline.battery.ui.binder

import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.android.systemui.lifecycle.repeatWhenAttached
import com.android.systemui.statusbar.phone.domain.interactor.IsAreaDark
import com.android.systemui.statusbar.pipeline.battery.ui.composable.UnifiedBattery
import com.android.systemui.statusbar.pipeline.battery.ui.viewmodel.BatteryViewModel
import com.android.systemui.statusbar.pipeline.battery.ui.viewmodel.BatteryViewModel.Companion.STATUS_BAR_BATTERY_HEIGHT
import com.android.systemui.statusbar.pipeline.battery.ui.viewmodel.BatteryViewModel.Companion.STATUS_BAR_BATTERY_WIDTH
import kotlinx.coroutines.flow.Flow

/** In cases where the battery needs to be bound to an existing android view */
object UnifiedBatteryViewBinder {
    /** Seats the [UnifiedBattery] into the given [ComposeView] root. */
    @JvmStatic
    fun bind(
        view: ComposeView,
        viewModelFactory: BatteryViewModel.Factory,
        isAreaDark: Flow<IsAreaDark>,
    ) {
        view.repeatWhenAttached {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                view.apply {
                    isVisible = true
                    setViewCompositionStrategy(
                        ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed
                    )
                    setContent {
                        val isDark by isAreaDark.collectAsStateWithLifecycle(IsAreaDark { true })
                        UnifiedBattery(
                            modifier =
                                Modifier.height(STATUS_BAR_BATTERY_HEIGHT)
                                    .width(STATUS_BAR_BATTERY_WIDTH),
                            viewModelFactory = viewModelFactory,
                            isDark = isDark,
                        )
                    }
                }
            }
        }
    }
}
