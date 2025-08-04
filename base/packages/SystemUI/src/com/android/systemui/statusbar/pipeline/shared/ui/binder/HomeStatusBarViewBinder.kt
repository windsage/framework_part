/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.systemui.statusbar.pipeline.shared.ui.binder

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.view.View
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.android.app.animation.Interpolators
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.lifecycle.repeatWhenAttached
import com.android.systemui.res.R
import com.android.systemui.scene.shared.flag.SceneContainerFlag
import com.android.systemui.statusbar.chips.mediaprojection.domain.model.MediaProjectionStopDialogModel
import com.android.systemui.statusbar.chips.notification.shared.StatusBarNotifChips
import com.android.systemui.statusbar.chips.ui.binder.OngoingActivityChipBinder
import com.android.systemui.statusbar.chips.ui.binder.OngoingActivityChipViewBinding
import com.android.systemui.statusbar.chips.ui.model.MultipleOngoingActivityChipsModelLegacy
import com.android.systemui.statusbar.chips.ui.model.OngoingActivityChipModel
import com.android.systemui.statusbar.core.StatusBarConnectedDisplays
import com.android.systemui.statusbar.core.StatusBarRootModernization
import com.android.systemui.statusbar.events.shared.model.SystemEventAnimationState
import com.android.systemui.statusbar.events.shared.model.SystemEventAnimationState.AnimatingIn
import com.android.systemui.statusbar.events.shared.model.SystemEventAnimationState.AnimatingOut
import com.android.systemui.statusbar.events.shared.model.SystemEventAnimationState.RunningChipAnim
import com.android.systemui.statusbar.notification.icon.ui.viewbinder.ConnectedDisplaysStatusBarNotificationIconViewStore
import com.android.systemui.statusbar.notification.shared.NotificationsLiveDataStoreRefactor
import com.android.systemui.statusbar.phone.fragment.CollapsedStatusBarFragment
import com.android.systemui.statusbar.phone.ongoingcall.StatusBarChipsModernization
import com.android.systemui.statusbar.pipeline.shared.ui.model.VisibilityModel
import com.android.systemui.statusbar.pipeline.shared.ui.viewmodel.HomeStatusBarViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * Interface to assist with binding the [CollapsedStatusBarFragment] to [HomeStatusBarViewModel].
 * Used only to enable easy testing of [CollapsedStatusBarFragment].
 */
interface HomeStatusBarViewBinder {
    /**
     * Binds the view to the view-model. [listener] will be notified whenever an event that may
     * change the status bar visibility occurs.
     *
     * Null chip animations are used when [StatusBarRootModernization] is off (i.e., when we are
     * binding from the fragment). If non-null, they control the animation of the system icon area
     * to support the chip animations.
     */
    fun bind(
        displayId: Int,
        view: View,
        viewModel: HomeStatusBarViewModel,
        systemEventChipAnimateIn: ((View) -> Unit)?,
        systemEventChipAnimateOut: ((View) -> Unit)?,
        listener: StatusBarVisibilityChangeListener?,
    )
}

@SysUISingleton
class HomeStatusBarViewBinderImpl
@Inject
constructor(
    private val viewStoreFactory: ConnectedDisplaysStatusBarNotificationIconViewStore.Factory
) : HomeStatusBarViewBinder {
    override fun bind(
        displayId: Int,
        view: View,
        viewModel: HomeStatusBarViewModel,
        systemEventChipAnimateIn: ((View) -> Unit)?,
        systemEventChipAnimateOut: ((View) -> Unit)?,
        listener: StatusBarVisibilityChangeListener?,
    ) {
        // Set some top-level views to gone before we get started
        val primaryChipView: View = view.requireViewById(R.id.ongoing_activity_chip_primary)
        val systemInfoView = view.requireViewById<View>(R.id.status_bar_end_side_content)
        val clockView = view.requireViewById<View>(R.id.clock)
        val notificationIconsArea = view.requireViewById<View>(R.id.notificationIcons)

        // CollapsedStatusBarFragment doesn't need this
        if (StatusBarRootModernization.isEnabled) {
            // GONE because this shouldn't take space in the layout
            primaryChipView.hideInitially(state = View.GONE)
            systemInfoView.hideInitially()
            clockView.hideInitially()
            notificationIconsArea.hideInitially()
        }

        view.repeatWhenAttached {
            repeatOnLifecycle(Lifecycle.State.CREATED) {
                val iconViewStore =
                    if (StatusBarConnectedDisplays.isEnabled) {
                        viewStoreFactory.create(displayId).also {
                            lifecycleScope.launch { it.activate() }
                        }
                    } else {
                        null
                    }
                listener?.let { listener ->
                    launch {
                        viewModel.isTransitioningFromLockscreenToOccluded.collect {
                            listener.onStatusBarVisibilityMaybeChanged()
                        }
                    }
                }

                listener?.let { listener ->
                    launch {
                        viewModel.transitionFromLockscreenToDreamStartedEvent.collect {
                            listener.onTransitionFromLockscreenToDreamStarted()
                        }
                    }
                }

                if (NotificationsLiveDataStoreRefactor.isEnabled) {
                    val lightsOutView: View = view.requireViewById(R.id.notification_lights_out)
                    launch {
                        viewModel.areNotificationsLightsOut.collect { show ->
                            animateLightsOutView(lightsOutView, show)
                        }
                    }
                }

                if (com.android.media.projection.flags.Flags.showStopDialogPostCallEnd()) {
                    launch {
                        viewModel.mediaProjectionStopDialogDueToCallEndedState.collect { stopDialog
                            ->
                            if (stopDialog is MediaProjectionStopDialogModel.Shown) {
                                stopDialog.createAndShowDialog()
                            }
                        }
                    }
                }

                if (!StatusBarNotifChips.isEnabled && !StatusBarChipsModernization.isEnabled) {
                    val primaryChipViewBinding =
                        OngoingActivityChipBinder.createBinding(primaryChipView)

                    launch {
                        combine(
                                viewModel.primaryOngoingActivityChip,
                                viewModel.canShowOngoingActivityChips,
                                ::Pair,
                            )
                            .distinctUntilChanged()
                            .collect { (primaryChipModel, areChipsAllowed) ->
                                OngoingActivityChipBinder.bind(
                                    primaryChipModel,
                                    primaryChipViewBinding,
                                    iconViewStore,
                                )

                                if (StatusBarRootModernization.isEnabled) {
                                    bindLegacyPrimaryOngoingActivityChipWithVisibility(
                                        areChipsAllowed,
                                        primaryChipModel,
                                        primaryChipViewBinding,
                                    )
                                } else {
                                    when (primaryChipModel) {
                                        is OngoingActivityChipModel.Active ->
                                            listener?.onOngoingActivityStatusChanged(
                                                hasPrimaryOngoingActivity = true,
                                                hasSecondaryOngoingActivity = false,
                                                shouldAnimate = true,
                                            )

                                        is OngoingActivityChipModel.Inactive ->
                                            listener?.onOngoingActivityStatusChanged(
                                                hasPrimaryOngoingActivity = false,
                                                hasSecondaryOngoingActivity = false,
                                                shouldAnimate = primaryChipModel.shouldAnimate,
                                            )
                                    }
                                }
                            }
                    }
                }

                if (StatusBarNotifChips.isEnabled && !StatusBarChipsModernization.isEnabled) {
                    // Create view bindings here so we don't keep re-fetching child views each time
                    // the chip model changes.
                    val primaryChipViewBinding =
                        OngoingActivityChipBinder.createBinding(primaryChipView)
                    val secondaryChipViewBinding =
                        OngoingActivityChipBinder.createBinding(
                            view.requireViewById(R.id.ongoing_activity_chip_secondary)
                        )
                    OngoingActivityChipBinder.updateTypefaces(primaryChipViewBinding)
                    OngoingActivityChipBinder.updateTypefaces(secondaryChipViewBinding)
                    launch {
                        combine(
                                viewModel.ongoingActivityChipsLegacy,
                                viewModel.canShowOngoingActivityChips,
                                ::Pair,
                            )
                            .distinctUntilChanged()
                            .collect { (chips, areChipsAllowed) ->
                                OngoingActivityChipBinder.bind(
                                    chips.primary,
                                    primaryChipViewBinding,
                                    iconViewStore,
                                )
                                OngoingActivityChipBinder.bind(
                                    chips.secondary,
                                    secondaryChipViewBinding,
                                    iconViewStore,
                                )
                                if (StatusBarRootModernization.isEnabled) {
                                    bindOngoingActivityChipsWithVisibility(
                                        areChipsAllowed,
                                        chips,
                                        primaryChipViewBinding,
                                        secondaryChipViewBinding,
                                    )
                                } else {
                                    listener?.onOngoingActivityStatusChanged(
                                        hasPrimaryOngoingActivity =
                                            chips.primary is OngoingActivityChipModel.Active,
                                        hasSecondaryOngoingActivity =
                                            chips.secondary is OngoingActivityChipModel.Active,
                                        // TODO(b/364653005): Figure out the animation story here.
                                        shouldAnimate = true,
                                    )
                                }
                            }
                    }
                    launch {
                        viewModel.contentArea.collect { _ ->
                            OngoingActivityChipBinder.resetPrimaryChipWidthRestrictions(
                                primaryChipViewBinding,
                                viewModel.ongoingActivityChipsLegacy.value.primary,
                            )
                            OngoingActivityChipBinder.resetSecondaryChipWidthRestrictions(
                                secondaryChipViewBinding,
                                viewModel.ongoingActivityChipsLegacy.value.secondary,
                            )
                            view.requestLayout()
                        }
                    }
                }

                if (SceneContainerFlag.isEnabled) {
                    listener?.let { listener ->
                        launch {
                            viewModel.isHomeStatusBarAllowed.collect {
                                listener.onIsHomeStatusBarAllowedBySceneChanged(it)
                            }
                        }
                    }
                }

                if (StatusBarRootModernization.isEnabled) {
                    // TODO(b/393445203): figure out the best story for this stub view. This crashes
                    // if we move it up to the top of [bind]
                    val operatorNameView = view.requireViewById<View>(R.id.operator_name_frame)
                    operatorNameView.isVisible = false

                    StatusBarOperatorNameViewBinder.bind(
                        operatorNameView,
                        viewModel.operatorNameViewModel,
                        viewModel.areaTint,
                    )
                    launch {
                        viewModel.shouldShowOperatorNameView.collect {
                            operatorNameView.isVisible = it
                        }
                    }

                    launch { viewModel.isClockVisible.collect { clockView.adjustVisibility(it) } }

                    launch {
                        viewModel.isNotificationIconContainerVisible.collect {
                            notificationIconsArea.adjustVisibility(it)
                        }
                    }

                    launch {
                        viewModel.systemInfoCombinedVis.collect { (baseVis, animState) ->
                            // Broadly speaking, the baseVis controls the view.visibility, and
                            // the animation state uses only alpha to achieve its effect. This
                            // means that we can always modify the visibility, and if we're
                            // animating we can use the animState to handle it. If we are not
                            // animating, then we can use the baseVis default animation
                            if (animState.isAnimatingChip()) {
                                // Just apply the visibility of the view, but don't animate
                                systemInfoView.visibility = baseVis.visibility
                                // Now apply the animation state, with its animator
                                when (animState) {
                                    AnimatingIn -> {
                                        systemEventChipAnimateIn?.invoke(systemInfoView)
                                    }
                                    AnimatingOut -> {
                                        systemEventChipAnimateOut?.invoke(systemInfoView)
                                    }
                                    else -> {
                                        // Nothing to do here
                                    }
                                }
                            } else {
                                systemInfoView.adjustVisibility(baseVis)
                            }
                        }
                    }
                }
            }
        }
    }

    /** Bind the (legacy) single primary ongoing activity chip with the status bar visibility */
    private fun bindLegacyPrimaryOngoingActivityChipWithVisibility(
        areChipsAllowed: Boolean,
        primaryChipModel: OngoingActivityChipModel,
        primaryChipViewBinding: OngoingActivityChipViewBinding,
    ) {
        if (!areChipsAllowed) {
            primaryChipViewBinding.rootView.hide(shouldAnimateChange = false)
        } else {
            when (primaryChipModel) {
                is OngoingActivityChipModel.Active -> {
                    primaryChipViewBinding.rootView.show(shouldAnimateChange = true)
                }

                is OngoingActivityChipModel.Inactive -> {
                    primaryChipViewBinding.rootView.hide(
                        state = View.GONE,
                        shouldAnimateChange = primaryChipModel.shouldAnimate,
                    )
                }
            }
        }
    }

    /** Bind the primary/secondary chips along with the home status bar's visibility */
    private fun bindOngoingActivityChipsWithVisibility(
        areChipsAllowed: Boolean,
        chips: MultipleOngoingActivityChipsModelLegacy,
        primaryChipViewBinding: OngoingActivityChipViewBinding,
        secondaryChipViewBinding: OngoingActivityChipViewBinding,
    ) {
        if (!areChipsAllowed) {
            primaryChipViewBinding.rootView.hide(shouldAnimateChange = false)
            secondaryChipViewBinding.rootView.hide(shouldAnimateChange = false)
        } else {
            primaryChipViewBinding.rootView.adjustVisibility(chips.primary.toVisibilityModel())
            secondaryChipViewBinding.rootView.adjustVisibility(chips.secondary.toVisibilityModel())
        }
    }

    private fun SystemEventAnimationState.isAnimatingChip() =
        when (this) {
            AnimatingIn,
            AnimatingOut,
            RunningChipAnim -> true
            else -> false
        }

    private fun OngoingActivityChipModel.toVisibilityModel(): VisibilityModel {
        return VisibilityModel(
            visibility = if (this is OngoingActivityChipModel.Active) View.VISIBLE else View.GONE,
            // TODO(b/364653005): Figure out the animation story here.
            shouldAnimateChange = true,
        )
    }

    private fun animateLightsOutView(view: View, visible: Boolean) {
        view.animate().cancel()

        val alpha = if (visible) 1f else 0f
        val duration = if (visible) 750L else 250L
        val visibility = if (visible) View.VISIBLE else View.GONE

        if (visible) {
            view.alpha = 0f
            view.visibility = View.VISIBLE
        }

        view
            .animate()
            .alpha(alpha)
            .setDuration(duration)
            .setListener(
                object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        view.alpha = alpha
                        view.visibility = visibility
                        // Unset the listener, otherwise this may persist for
                        // another view property animation
                        view.animate().setListener(null)
                    }
                }
            )
            .start()
    }

    private fun View.adjustVisibility(model: VisibilityModel) {
        if (model.visibility == View.VISIBLE) {
            this.show(model.shouldAnimateChange)
        } else {
            this.hide(model.visibility, model.shouldAnimateChange)
        }
    }

    /**
     * Hide the view for initialization, but skip if it's already hidden and does not cancel
     * animations.
     */
    private fun View.hideInitially(state: Int = View.INVISIBLE) {
        if (visibility == View.INVISIBLE || visibility == View.GONE) {
            return
        }
        alpha = 0f
        visibility = state
    }

    // See CollapsedStatusBarFragment#hide.
    private fun View.hide(state: Int = View.INVISIBLE, shouldAnimateChange: Boolean) {
        animate().cancel()

        if (
            (visibility == View.INVISIBLE && state == View.INVISIBLE) ||
                (visibility == View.GONE && state == View.GONE)
        ) {
            return
        }
        val isAlreadyHidden = visibility == View.INVISIBLE || visibility == View.GONE
        if (!shouldAnimateChange || isAlreadyHidden) {
            alpha = 0f
            visibility = state
            return
        }

        animate()
            .alpha(0f)
            .setDuration(CollapsedStatusBarFragment.FADE_OUT_DURATION.toLong())
            .setStartDelay(0)
            .setInterpolator(Interpolators.ALPHA_OUT)
            .withEndAction { visibility = state }
    }

    // See CollapsedStatusBarFragment#show.
    private fun View.show(shouldAnimateChange: Boolean) {
        animate().cancel()
        if (visibility == View.VISIBLE && alpha >= 1f) {
            return
        }
        visibility = View.VISIBLE
        if (!shouldAnimateChange) {
            alpha = 1f
            return
        }
        animate()
            .alpha(1f)
            .setDuration(CollapsedStatusBarFragment.FADE_IN_DURATION.toLong())
            .setInterpolator(Interpolators.ALPHA_IN)
            .setStartDelay(CollapsedStatusBarFragment.FADE_IN_DELAY.toLong())
            // We need to clean up any pending end action from animateHide if we call both hide and
            // show in the same frame before the animation actually gets started.
            // cancel() doesn't really remove the end action.
            .withEndAction(null)

        // TODO(b/364360986): Synchronize the motion with the Keyguard fading if necessary.
    }
}

/** Listener for various events that may affect the status bar's visibility. */
interface StatusBarVisibilityChangeListener {
    /**
     * Called when the status bar visibility might have changed due to the device moving to a
     * different state.
     */
    fun onStatusBarVisibilityMaybeChanged()

    /** Called when a transition from lockscreen to dream has started. */
    fun onTransitionFromLockscreenToDreamStarted()

    /**
     * Called when the status of the ongoing activity chip (active or not active) has changed.
     *
     * @param shouldAnimate true if the chip should animate in/out, and false if the chip should
     *   immediately appear/disappear.
     */
    fun onOngoingActivityStatusChanged(
        hasPrimaryOngoingActivity: Boolean,
        hasSecondaryOngoingActivity: Boolean,
        shouldAnimate: Boolean,
    )

    /**
     * Called when the scene state has changed such that the home status bar is newly allowed or no
     * longer allowed. See [HomeStatusBarViewModel.isHomeStatusBarAllowed].
     */
    fun onIsHomeStatusBarAllowedBySceneChanged(isHomeStatusBarAllowedByScene: Boolean)
}
