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

package com.android.systemui.volume.dialog.sliders.ui.compose

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.SliderColors
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.SliderState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasurePolicy
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.layout.layoutId
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastFirst
import kotlin.math.min

@Composable
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
fun SliderTrack(
    sliderState: SliderState,
    isEnabled: Boolean,
    modifier: Modifier = Modifier,
    colors: SliderColors = SliderDefaults.colors(),
    thumbTrackGapSize: Dp = 6.dp,
    trackCornerSize: Dp = 12.dp,
    trackInsideCornerSize: Dp = 2.dp,
    trackSize: Dp = 40.dp,
    isVertical: Boolean = false,
    activeTrackStartIcon: (@Composable BoxScope.(iconsState: SliderIconsState) -> Unit)? = null,
    activeTrackEndIcon: (@Composable BoxScope.(iconsState: SliderIconsState) -> Unit)? = null,
    inactiveTrackStartIcon: (@Composable BoxScope.(iconsState: SliderIconsState) -> Unit)? = null,
    inactiveTrackEndIcon: (@Composable BoxScope.(iconsState: SliderIconsState) -> Unit)? = null,
) {
    val isRtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val measurePolicy =
        remember(sliderState) {
            TrackMeasurePolicy(
                sliderState = sliderState,
                shouldMirrorIcons = !isVertical && isRtl || isVertical,
                isVertical = isVertical,
                gapSize = thumbTrackGapSize,
            )
        }
    Layout(
        measurePolicy = measurePolicy,
        content = {
            SliderDefaults.Track(
                sliderState = sliderState,
                colors = colors,
                enabled = isEnabled,
                trackCornerSize = trackCornerSize,
                trackInsideCornerSize = trackInsideCornerSize,
                drawStopIndicator = null,
                thumbTrackGapSize = thumbTrackGapSize,
                drawTick = { _, _ -> },
                modifier =
                    Modifier.then(
                            if (isVertical) {
                                Modifier.width(trackSize)
                            } else {
                                Modifier.height(trackSize)
                            }
                        )
                        .layoutId(Contents.Track),
            )

            TrackIcon(
                icon = activeTrackStartIcon,
                contents = Contents.Active.TrackStartIcon,
                isEnabled = isEnabled,
                colors = colors,
                state = measurePolicy,
            )
            TrackIcon(
                icon = activeTrackEndIcon,
                contents = Contents.Active.TrackEndIcon,
                isEnabled = isEnabled,
                colors = colors,
                state = measurePolicy,
            )
            TrackIcon(
                icon = inactiveTrackStartIcon,
                contents = Contents.Inactive.TrackStartIcon,
                isEnabled = isEnabled,
                colors = colors,
                state = measurePolicy,
            )
            TrackIcon(
                icon = inactiveTrackEndIcon,
                contents = Contents.Inactive.TrackEndIcon,
                isEnabled = isEnabled,
                colors = colors,
                state = measurePolicy,
            )
        },
        modifier = modifier,
    )
}

@Composable
private fun TrackIcon(
    icon: (@Composable BoxScope.(sliderIconsState: SliderIconsState) -> Unit)?,
    isEnabled: Boolean,
    contents: Contents,
    state: SliderIconsState,
    colors: SliderColors,
    modifier: Modifier = Modifier,
) {
    icon ?: return
    /*
    ignore icons mirroring for the rtl layouts here because icons positioning is handled by the
    TrackMeasurePolicy. It ensures that active icons are always above the active track and the
    same for inactive
    */
    val iconColor =
        when (contents) {
            is Contents.Inactive ->
                if (isEnabled) {
                    colors.inactiveTickColor
                } else {
                    colors.disabledInactiveTickColor
                }
            is Contents.Active ->
                if (isEnabled) {
                    colors.activeTickColor
                } else {
                    colors.disabledActiveTickColor
                }
            is Contents.Track -> {
                error("$contents is unsupported by the TrackIcon")
            }
        }
    Box(modifier = modifier.layoutId(contents).fillMaxSize()) {
        CompositionLocalProvider(LocalContentColor provides iconColor) { icon(state) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
private class TrackMeasurePolicy(
    private val sliderState: SliderState,
    private val shouldMirrorIcons: Boolean,
    private val gapSize: Dp,
    private val isVertical: Boolean,
) : MeasurePolicy, SliderIconsState {

    private val isVisible: Map<Contents, MutableState<Boolean>> =
        mutableMapOf(
            Contents.Active.TrackStartIcon to mutableStateOf(false),
            Contents.Active.TrackEndIcon to mutableStateOf(false),
            Contents.Inactive.TrackStartIcon to mutableStateOf(false),
            Contents.Inactive.TrackEndIcon to mutableStateOf(false),
        )

    override val isActiveTrackStartIconVisible: Boolean
        get() = isVisible.getValue(Contents.Active.TrackStartIcon.resolve()).value

    override val isActiveTrackEndIconVisible: Boolean
        get() = isVisible.getValue(Contents.Active.TrackEndIcon.resolve()).value

    override val isInactiveTrackStartIconVisible: Boolean
        get() = isVisible.getValue(Contents.Inactive.TrackStartIcon.resolve()).value

    override val isInactiveTrackEndIconVisible: Boolean
        get() = isVisible.getValue(Contents.Inactive.TrackEndIcon.resolve()).value

    override fun MeasureScope.measure(
        measurables: List<Measurable>,
        constraints: Constraints,
    ): MeasureResult {
        val track = measurables.fastFirst { it.layoutId == Contents.Track }.measure(constraints)

        val iconSize = min(track.width, track.height)
        val iconConstraints = constraints.copy(maxWidth = iconSize, maxHeight = iconSize)

        val components = buildMap {
            put(Contents.Track, track)
            for (measurable in measurables) {
                // don't measure track a second time
                if (measurable.layoutId != Contents.Track) {
                    put(
                        (measurable.layoutId as Contents).resolve(),
                        measurable.measure(iconConstraints),
                    )
                }
            }
        }

        return layout(track.width, track.height) {
            val gapSizePx = gapSize.roundToPx()
            val coercedValueAsFraction =
                if (shouldMirrorIcons) {
                    1 - sliderState.coercedValueAsFraction
                } else {
                    sliderState.coercedValueAsFraction
                }
            for (iconLayoutId in components.keys) {
                val iconPlaceable = components.getValue(iconLayoutId)
                if (isVertical) {
                    iconPlaceable.place(
                        0,
                        iconLayoutId.calculatePosition(
                            placeableDimension = iconPlaceable.height,
                            containerDimension = track.height,
                            gapSize = gapSizePx,
                            coercedValueAsFraction = coercedValueAsFraction,
                        ),
                    )
                } else {
                    iconPlaceable.place(
                        iconLayoutId.calculatePosition(
                            placeableDimension = iconPlaceable.width,
                            containerDimension = track.width,
                            gapSize = gapSizePx,
                            coercedValueAsFraction = coercedValueAsFraction,
                        ),
                        0,
                    )
                }

                // isVisible is only relevant for the icons
                if (iconLayoutId != Contents.Track) {
                    val isVisibleState = isVisible.getValue(iconLayoutId)
                    val newIsVisible =
                        iconLayoutId.isVisible(
                            placeableDimension =
                                if (isVertical) iconPlaceable.height else iconPlaceable.width,
                            containerDimension = if (isVertical) track.height else track.width,
                            gapSize = gapSizePx,
                            coercedValueAsFraction = coercedValueAsFraction,
                        )
                    if (isVisibleState.value != newIsVisible) {
                        isVisibleState.value = newIsVisible
                    }
                }
            }
        }
    }

    private fun Contents.resolve(): Contents {
        return if (shouldMirrorIcons) {
            mirrored
        } else {
            this
        }
    }
}

private sealed interface Contents {

    data object Track : Contents {

        override val mirrored: Contents
            get() = error("unsupported for Track")

        override fun calculatePosition(
            placeableDimension: Int,
            containerDimension: Int,
            gapSize: Int,
            coercedValueAsFraction: Float,
        ): Int = 0

        override fun isVisible(
            placeableDimension: Int,
            containerDimension: Int,
            gapSize: Int,
            coercedValueAsFraction: Float,
        ): Boolean = true
    }

    interface Active : Contents {

        override fun isVisible(
            placeableDimension: Int,
            containerDimension: Int,
            gapSize: Int,
            coercedValueAsFraction: Float,
        ): Boolean =
            (containerDimension * coercedValueAsFraction - gapSize).toInt() > placeableDimension

        data object TrackStartIcon : Active {

            override val mirrored: Contents
                get() = Inactive.TrackEndIcon

            override fun calculatePosition(
                placeableDimension: Int,
                containerDimension: Int,
                gapSize: Int,
                coercedValueAsFraction: Float,
            ): Int = 0
        }

        data object TrackEndIcon : Active {

            override val mirrored: Contents
                get() = Inactive.TrackStartIcon

            override fun calculatePosition(
                placeableDimension: Int,
                containerDimension: Int,
                gapSize: Int,
                coercedValueAsFraction: Float,
            ): Int =
                (containerDimension * coercedValueAsFraction - placeableDimension - gapSize).toInt()
        }
    }

    interface Inactive : Contents {

        override fun isVisible(
            placeableDimension: Int,
            containerDimension: Int,
            gapSize: Int,
            coercedValueAsFraction: Float,
        ): Boolean =
            containerDimension - (containerDimension * coercedValueAsFraction + gapSize) >
                placeableDimension

        data object TrackStartIcon : Inactive {

            override val mirrored: Contents
                get() = Active.TrackEndIcon

            override fun calculatePosition(
                placeableDimension: Int,
                containerDimension: Int,
                gapSize: Int,
                coercedValueAsFraction: Float,
            ): Int = (containerDimension * coercedValueAsFraction + gapSize).toInt()
        }

        data object TrackEndIcon : Inactive {

            override val mirrored: Contents
                get() = Active.TrackStartIcon

            override fun calculatePosition(
                placeableDimension: Int,
                containerDimension: Int,
                gapSize: Int,
                coercedValueAsFraction: Float,
            ): Int = containerDimension - placeableDimension
        }
    }

    fun calculatePosition(
        placeableDimension: Int,
        containerDimension: Int,
        gapSize: Int,
        coercedValueAsFraction: Float,
    ): Int

    fun isVisible(
        placeableDimension: Int,
        containerDimension: Int,
        gapSize: Int,
        coercedValueAsFraction: Float,
    ): Boolean

    /**
     * [Contents] that is visually on the opposite side of the current one on the slider. This is
     * handy when dealing with the rtl layouts
     */
    val mirrored: Contents
}

/** Provides visibility state for each of the Slider's icons. */
interface SliderIconsState {
    val isActiveTrackStartIconVisible: Boolean
    val isActiveTrackEndIconVisible: Boolean
    val isInactiveTrackStartIconVisible: Boolean
    val isInactiveTrackEndIconVisible: Boolean
}
