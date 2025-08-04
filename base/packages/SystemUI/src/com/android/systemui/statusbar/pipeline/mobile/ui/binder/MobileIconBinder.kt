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

// QTI_BEGIN: 2023-04-01: Android_UI: SystemUI: Readapt the Volte HD icon
/*
 * Changes from Qualcomm Innovation Center are provided under the following license:
 * Copyright (c) 2023 Qualcomm Innovation Center, Inc. All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause-Clear
 */

// QTI_END: 2023-04-01: Android_UI: SystemUI: Readapt the Volte HD icon
package com.android.systemui.statusbar.pipeline.mobile.ui.binder
import android.annotation.ColorInt
import android.content.res.ColorStateList
import android.view.View
import android.view.View.GONE
import android.view.View.VISIBLE
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.Space
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.android.app.tracing.coroutines.launchTraced as launch
import com.android.settingslib.graph.SignalDrawable
import com.android.systemui.Flags.statusBarStaticInoutIndicators
import com.android.systemui.common.ui.binder.IconViewBinder
import com.android.systemui.lifecycle.repeatWhenAttached
import com.android.systemui.plugins.DarkIconDispatcher
import com.android.systemui.res.R
import com.android.systemui.statusbar.StatusBarIconView
import com.android.systemui.statusbar.StatusBarIconView.STATE_HIDDEN
// QTI_BEGIN: 2024-03-10: Android_UI: SystemUI: Readapt the ShadeCarrier SPN display customization
import com.android.systemui.statusbar.phone.StatusBarLocation
// QTI_END: 2024-03-10: Android_UI: SystemUI: Readapt the ShadeCarrier SPN display customization
import com.android.systemui.statusbar.pipeline.mobile.domain.model.SignalIconModel
import com.android.systemui.statusbar.pipeline.mobile.ui.MobileViewLogger
import com.android.systemui.statusbar.pipeline.mobile.ui.viewmodel.LocationBasedMobileViewModel
import com.android.systemui.statusbar.pipeline.shared.ui.binder.ModernStatusBarViewBinding
import com.android.systemui.statusbar.pipeline.shared.ui.binder.ModernStatusBarViewVisibilityHelper
import com.android.systemui.statusbar.pipeline.shared.ui.binder.StatusBarViewBinderConstants.ALPHA_ACTIVE
import com.android.systemui.statusbar.pipeline.shared.ui.binder.StatusBarViewBinderConstants.ALPHA_INACTIVE
import com.android.systemui.util.kotlin.pairwiseBy
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
data class MobileIconColors(@ColorInt val tint: Int, @ColorInt val contrast: Int)
object MobileIconBinder {
    /** Binds the view to the view-model, continuing to update the former based on the latter */
    @JvmStatic
    fun bind(
        view: ViewGroup,
        viewModel: LocationBasedMobileViewModel,
        @StatusBarIconView.VisibleState initialVisibilityState: Int = STATE_HIDDEN,
        logger: MobileViewLogger,
    ): ModernStatusBarViewBinding {
        val mobileGroupView = view.requireViewById<ViewGroup>(R.id.mobile_group)
        val activityContainer = view.requireViewById<View>(R.id.inout_container)
        val activityIn = view.requireViewById<ImageView>(R.id.mobile_in)
        val activityOut = view.requireViewById<ImageView>(R.id.mobile_out)
        val networkTypeView = view.requireViewById<ImageView>(R.id.mobile_type)
        val networkTypeContainer = view.requireViewById<FrameLayout>(R.id.mobile_type_container)
        val iconView = view.requireViewById<ImageView>(R.id.mobile_signal)
        val mobileDrawable = SignalDrawable(view.context)
        val roamingView = view.requireViewById<ImageView>(R.id.mobile_roaming)
        val roamingSpace = view.requireViewById<Space>(R.id.mobile_roaming_space)
        val dotView = view.requireViewById<StatusBarIconView>(R.id.status_bar_dot)
// QTI_BEGIN: 2023-04-01: Android_UI: SystemUI: Readapt the Volte HD icon
        val volteView = view.requireViewById<ImageView>(R.id.mobile_volte)
// QTI_END: 2023-04-01: Android_UI: SystemUI: Readapt the Volte HD icon
        view.isVisible = viewModel.isVisible.value
        iconView.isVisible = true
        // TODO(b/238425913): We should log this visibility state.
        @StatusBarIconView.VisibleState
        val visibilityState: MutableStateFlow<Int> = MutableStateFlow(initialVisibilityState)
        val iconTint: MutableStateFlow<MobileIconColors> =
            MutableStateFlow(
                MobileIconColors(
                    tint = DarkIconDispatcher.DEFAULT_ICON_TINT,
                    contrast = DarkIconDispatcher.DEFAULT_INVERSE_ICON_TINT,
                )
            )
        val decorTint: MutableStateFlow<Int> = MutableStateFlow(viewModel.defaultColor)
        var isCollecting = false
        view.repeatWhenAttached {
            lifecycleScope.launch {
                repeatOnLifecycle(Lifecycle.State.CREATED) {
                    // isVisible controls the visibility state of the outer group, and thus it needs
                    // to run in the CREATED lifecycle so it can continue to watch while invisible
                    // See (b/291031862) for details
                    launch {
                        viewModel.isVisible.collect { isVisible ->
                            viewModel.verboseLogger?.logBinderReceivedVisibility(
                                view,
                                viewModel.subscriptionId,
                                isVisible,
                            )
                            view.isVisible = isVisible
                            // [StatusIconContainer] can get out of sync sometimes. Make sure to
                            // request another layout when this changes.
                            view.requestLayout()
                        }
                    }
                }
            }
            lifecycleScope.launch {
                repeatOnLifecycle(Lifecycle.State.STARTED) {
                    logger.logCollectionStarted(view, viewModel)
                    isCollecting = true
                    launch {
                        visibilityState.collect { state ->
                            ModernStatusBarViewVisibilityHelper.setVisibilityState(
                                state,
                                mobileGroupView,
                                dotView,
                            )
                            view.requestLayout()
                        }
                    }
                    // Set the icon for the triangle
                    launch {
                        viewModel.icon
                            .pairwiseBy(initialValue = null) { oldIcon, newIcon ->
                                // Make sure we requestLayout if the number of levels changes
                                val shouldRequestLayout =
                                    when {
                                        oldIcon == null -> true
                                        oldIcon is SignalIconModel.Cellular &&
                                            newIcon is SignalIconModel.Cellular -> {
                                            oldIcon.numberOfLevels != newIcon.numberOfLevels
                                        }
                                        else -> false
                                    }
                                Pair(shouldRequestLayout, newIcon)
                            }
                            .collect { (shouldRequestLayout, newIcon) ->
                                viewModel.verboseLogger?.logBinderReceivedSignalIcon(
                                    view,
                                    viewModel.subscriptionId,
                                    newIcon,
                                )
                                if (newIcon is SignalIconModel.Cellular) {
                                    iconView.setImageDrawable(mobileDrawable)
                                    mobileDrawable.level = newIcon.toSignalDrawableState()
                                } else if (newIcon is SignalIconModel.Satellite) {
                                    IconViewBinder.bind(newIcon.icon, iconView)
                                }
                                if (shouldRequestLayout) {
                                    iconView.requestLayout()
                                }
                            }
                    }
                    launch {
                        viewModel.contentDescription.distinctUntilChanged().collect {
                            MobileContentDescriptionViewBinder.bind(it, view)
                        }
                    }
                    // Set the network type icon
                    launch {
                        viewModel.networkTypeIcon.distinctUntilChanged().collect { dataTypeId ->
                            viewModel.verboseLogger?.logBinderReceivedNetworkTypeIcon(
                                view,
                                viewModel.subscriptionId,
                                dataTypeId,
                            )
                            dataTypeId?.let { IconViewBinder.bind(dataTypeId, networkTypeView) }
                            val prevVis = networkTypeContainer.visibility
                            networkTypeContainer.visibility =
// QTI_BEGIN: 2024-03-10: Android_UI: SystemUI: Readapt the ShadeCarrier SPN display customization
                                if (dataTypeId != null
                                    && viewModel.location != StatusBarLocation.SHADE_CARRIER_GROUP)
                                    VISIBLE else GONE
// QTI_END: 2024-03-10: Android_UI: SystemUI: Readapt the ShadeCarrier SPN display customization

                            if (prevVis != networkTypeContainer.visibility) {
                                view.requestLayout()
                            }
                        }
                    }
                    // Set the network type background
                    launch {
                        viewModel.networkTypeBackground.collect { background ->
                            networkTypeContainer.setBackgroundResource(background?.res ?: 0)
                            // Tint will invert when this bit changes
                            if (background?.res != null) {
                                networkTypeContainer.backgroundTintList =
                                    ColorStateList.valueOf(iconTint.value.tint)
                                networkTypeView.imageTintList =
                                    ColorStateList.valueOf(iconTint.value.contrast)
                            } else {
                                networkTypeView.imageTintList =
                                    ColorStateList.valueOf(iconTint.value.tint)
                            }
                        }
                    }
                    // Set the roaming indicator
                    launch {
                        viewModel.roaming.distinctUntilChanged().collect { isRoaming ->
                            roamingView.isVisible = isRoaming
                            roamingSpace.isVisible = isRoaming
                        }
                    }
                    if (statusBarStaticInoutIndicators()) {
                        // Set the opacity of the activity indicators
                        launch {
                            viewModel.activityInVisible.collect { visible ->
                                activityIn.imageAlpha =
                                    (if (visible) ALPHA_ACTIVE else ALPHA_INACTIVE)
                            }
                        }
                        launch {
                            viewModel.activityOutVisible.collect { visible ->
                                activityOut.imageAlpha =
                                    (if (visible) ALPHA_ACTIVE else ALPHA_INACTIVE)
                            }
                        }
                    } else {
                        // Set the activity indicators
                        launch { viewModel.activityInVisible.collect { activityIn.isVisible = it } }
                        launch {
                            viewModel.activityOutVisible.collect { activityOut.isVisible = it }
                        }
                    }
                    launch {
                        viewModel.activityContainerVisible.collect {
                            activityContainer.isVisible = it
                        }
                    }
                    // Set the tint
                    launch {
                        iconTint.collect { colors ->
                            val tint = ColorStateList.valueOf(colors.tint)
                            val contrast = ColorStateList.valueOf(colors.contrast)
                            iconView.imageTintList = tint
                            // If the bg is visible, tint it and use the contrast for the fg
                            if (viewModel.networkTypeBackground.value != null) {
                                networkTypeContainer.backgroundTintList = tint
                                networkTypeView.imageTintList = contrast
                            } else {
                                networkTypeView.imageTintList = tint
                            }
                            roamingView.imageTintList = tint
                            activityIn.imageTintList = tint
                            activityOut.imageTintList = tint
                            dotView.setDecorColor(colors.tint)
                            volteView.imageTintList = tint
                        }
                    }

                    launch {
                        viewModel.volteId.distinctUntilChanged().collect { volteId ->
// QTI_BEGIN: 2024-04-18: Android_UI: SystemUI: Fix HD icon overlap signalStrength icon issue
                            val prevVisibility = volteView.visibility;
// QTI_END: 2024-04-18: Android_UI: SystemUI: Fix HD icon overlap signalStrength icon issue
// QTI_BEGIN: 2024-03-10: Android_UI: SystemUI: Readapt the ShadeCarrier SPN display customization
                            if (volteId != 0 &&
                                viewModel.location != StatusBarLocation.SHADE_CARRIER_GROUP) {
// QTI_END: 2024-03-10: Android_UI: SystemUI: Readapt the ShadeCarrier SPN display customization
                                volteView.visibility = VISIBLE
                                volteView.setImageResource(volteId)
                            } else {
                                volteView.visibility = GONE
                            }
// QTI_BEGIN: 2024-04-18: Android_UI: SystemUI: Fix HD icon overlap signalStrength icon issue
                            if (prevVisibility != volteView.visibility) {
                                view.requestLayout()
                            }
// QTI_END: 2024-04-18: Android_UI: SystemUI: Fix HD icon overlap signalStrength icon issue
// QTI_BEGIN: 2023-04-01: Android_UI: SystemUI: Readapt the Volte HD icon
                        }
                    }

// QTI_END: 2023-04-01: Android_UI: SystemUI: Readapt the Volte HD icon
                    launch { viewModel.showSignalStrengthIcon.collect { iconView.isVisible = it } }

                    launch { decorTint.collect { tint -> dotView.setDecorColor(tint) } }
                    try {
                        awaitCancellation()
                    } finally {
                        isCollecting = false
                        logger.logCollectionStopped(view, viewModel)
                    }
                }
            }
        }
        return object : ModernStatusBarViewBinding {
            override fun getShouldIconBeVisible(): Boolean {
                return viewModel.isVisible.value
            }
            override fun onVisibilityStateChanged(@StatusBarIconView.VisibleState state: Int) {
                visibilityState.value = state
            }
            override fun onIconTintChanged(newTint: Int, contrastTint: Int) {
                iconTint.value = MobileIconColors(tint = newTint, contrast = contrastTint)
            }
            override fun onDecorTintChanged(newTint: Int) {
                decorTint.value = newTint
            }
            override fun isCollecting(): Boolean {
                return isCollecting
            }
        }
    }
}
