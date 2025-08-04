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

package android.media.projection;

import static android.view.Display.DEFAULT_DISPLAY;

import static java.lang.annotation.RetentionPolicy.SOURCE;

import android.annotation.FlaggedApi;
import android.annotation.IntDef;
import android.annotation.IntRange;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SuppressLint;
import android.os.Parcelable;

import com.android.media.projection.flags.Flags;

import java.lang.annotation.Retention;
import java.util.Arrays;
import java.util.Objects;

/**
 * Configure the {@link MediaProjection} session requested from
 * {@link MediaProjectionManager#createScreenCaptureIntent(MediaProjectionConfig)}.
 * <p>
 * This configuration should be used to provide the user with options for choosing the content to
 * be shared with the requesting application.
 */
public final class MediaProjectionConfig implements Parcelable {

    /**
     * Bitmask for setting whether this configuration is for projecting the whole display.
     */
    @FlaggedApi(Flags.FLAG_APP_CONTENT_SHARING)
    public static final int PROJECTION_SOURCE_DISPLAY = 1 << 1;

    /**
     * Bitmask for setting whether this configuration is for projecting the a custom region display.
     *
     * @hide
     */
    public static final int PROJECTION_SOURCE_DISPLAY_REGION = 1 << 2;

    /**
     * Bitmask for setting whether this configuration is for projecting the a single application.
     */
    @FlaggedApi(Flags.FLAG_APP_CONTENT_SHARING)
    public static final int PROJECTION_SOURCE_APP = 1 << 3;

    /**
     * Bitmask for setting whether this configuration is for projecting the content provided by an
     * application.
     */
    @FlaggedApi(com.android.media.projection.flags.Flags.FLAG_APP_CONTENT_SHARING)
    public static final int PROJECTION_SOURCE_APP_CONTENT = 1 << 4;

    /**
     * The user, rather than the host app, determines which region of the display to capture.
     *
     * @hide
     */
    public static final int CAPTURE_REGION_USER_CHOICE = 0;

    /**
     * @hide
     */
    public static final int DEFAULT_PROJECTION_SOURCES =
            PROJECTION_SOURCE_DISPLAY | PROJECTION_SOURCE_APP;

    /**
     * The host app specifies a particular display to capture.
     *
     * @hide
     */
    public static final int CAPTURE_REGION_FIXED_DISPLAY = 1;

    private static final int[] PROJECTION_SOURCES =
            new int[]{PROJECTION_SOURCE_DISPLAY, PROJECTION_SOURCE_DISPLAY_REGION,
                    PROJECTION_SOURCE_APP,
                    PROJECTION_SOURCE_APP_CONTENT};

    private static final String[] PROJECTION_SOURCES_STRING =
            new String[]{"PROJECTION_SOURCE_DISPLAY", "PROJECTION_SOURCE_DISPLAY_REGION",
                    "PROJECTION_SOURCE_APP", "PROJECTION_SOURCE_APP_CONTENT"};

    private static final int VALID_PROJECTION_SOURCES = createValidSourcesMask();

    private final int mInitialSelection;

    /** @hide */
    @IntDef(prefix = "CAPTURE_REGION_", value = {CAPTURE_REGION_USER_CHOICE,
            CAPTURE_REGION_FIXED_DISPLAY})
    @Retention(SOURCE)
    @Deprecated // Remove when FLAG_APP_CONTENT_SHARING is removed
    public @interface CaptureRegion {
    }

    /** @hide */
    @IntDef(flag = true, prefix = "PROJECTION_SOURCE_", value = {PROJECTION_SOURCE_DISPLAY,
            PROJECTION_SOURCE_DISPLAY_REGION, PROJECTION_SOURCE_APP, PROJECTION_SOURCE_APP_CONTENT})
    @Retention(SOURCE)
    public @interface MediaProjectionSource {
    }

    /**
     * The particular display to capture. Only used when {@link #PROJECTION_SOURCE_DISPLAY} is set,
     * ignored otherwise.
     * <p>
     * Only supports values of {@link android.view.Display#DEFAULT_DISPLAY}.
     */
    @IntRange(from = DEFAULT_DISPLAY, to = DEFAULT_DISPLAY)
    private final int mDisplayToCapture;

    /**
     * The region to capture. Defaults to the user's choice.
     */
    @CaptureRegion
    @Deprecated // Remove when FLAG_APP_CONTENT_SHARING is removed
    private int mRegionToCapture;

    /**
     * The region to capture. Defaults to the user's choice.
     */
    @MediaProjectionSource
    private final int mProjectionSources;

    /**
     * @see #getRequesterHint()
     */
    @Nullable
    private final String mRequesterHint;

    /**
     * Customized instance, with region set to the provided value.
     * @deprecated To be removed FLAG_APP_CONTENT_SHARING is removed
     */
    @Deprecated // Remove when FLAG_APP_CONTENT_SHARING is removed
    private MediaProjectionConfig(@CaptureRegion int captureRegion) {
        if (Flags.appContentSharing()) {
            throw new UnsupportedOperationException(
                    "Flag FLAG_APP_CONTENT_SHARING enabled. This method must not be called.");
        }
        mRegionToCapture = captureRegion;
        mDisplayToCapture = DEFAULT_DISPLAY;

        mRequesterHint = null;
        mInitialSelection = -1;
        mProjectionSources = -1;
    }

    /**
     * Customized instance, with region set to the provided value.
     */
    private MediaProjectionConfig(@MediaProjectionSource int projectionSource,
            @Nullable String requesterHint, int displayId, int initialSelection) {
        if (!Flags.appContentSharing()) {
            throw new UnsupportedOperationException(
                    "Flag FLAG_APP_CONTENT_SHARING disabled. This method must not be called");
        }
        if (projectionSource == 0) {
            mProjectionSources = DEFAULT_PROJECTION_SOURCES;
        } else {
            mProjectionSources = projectionSource;
        }
        mRequesterHint = requesterHint;
        mDisplayToCapture = displayId;
        mInitialSelection = initialSelection;
    }

    /**
     * Returns an instance which restricts the user to capturing the default display.
     */
    @NonNull
    public static MediaProjectionConfig createConfigForDefaultDisplay() {
        if (Flags.appContentSharing()) {
            return new Builder().setSourceEnabled(PROJECTION_SOURCE_DISPLAY, true).build();
        } else {
            return new MediaProjectionConfig(CAPTURE_REGION_FIXED_DISPLAY);
        }
    }

    /**
     * Returns an instance which allows the user to decide which region is captured. The consent
     * dialog presents the user with all possible options. If the user selects display capture,
     * then only the {@link android.view.Display#DEFAULT_DISPLAY} is supported.
     * <p>
     * When passed in to
     * {@link MediaProjectionManager#createScreenCaptureIntent(MediaProjectionConfig)}, the consent
     * dialog shown to the user will be the same as if just
     * {@link MediaProjectionManager#createScreenCaptureIntent()} was invoked.
     * </p>
     */
    @NonNull
    public static MediaProjectionConfig createConfigForUserChoice() {
        if (Flags.appContentSharing()) {
            return new MediaProjectionConfig.Builder().build();
        } else {
            return new MediaProjectionConfig(CAPTURE_REGION_USER_CHOICE);
        }
    }

    /**
     * Returns string representation of the captured region.
     */
    @NonNull
    @Deprecated // Remove when FLAG_APP_CONTENT_SHARING is removed
    private static String captureRegionToString(int value) {
        return switch (value) {
            case CAPTURE_REGION_USER_CHOICE -> "CAPTURE_REGION_USERS_CHOICE";
            case CAPTURE_REGION_FIXED_DISPLAY -> "CAPTURE_REGION_GIVEN_DISPLAY";
            default -> Integer.toHexString(value);
        };
    }

    /**
     * Returns string representation of the captured region.
     */
    @NonNull
    private static String projectionSourceToString(int value) {
        StringBuilder stringBuilder = new StringBuilder();
        for (int i = 0; i < PROJECTION_SOURCES.length; i++) {
            if ((value & PROJECTION_SOURCES[i]) > 0) {
                stringBuilder.append(PROJECTION_SOURCES_STRING[i]);
                stringBuilder.append(" ");
                value &= ~PROJECTION_SOURCES[i];
            }
        }
        if (value > 0) {
            stringBuilder.append("Unknown projection sources: ");
            stringBuilder.append(Integer.toHexString(value));
        }
        return stringBuilder.toString();
    }

    @Override
    public String toString() {
        if (Flags.appContentSharing()) {
            return ("MediaProjectionConfig{mInitialSelection=%d, mDisplayToCapture=%d, "
                    + "mProjectionSource=%s, mRequesterHint='%s'}").formatted(mInitialSelection,
                    mDisplayToCapture, projectionSourceToString(mProjectionSources),
                    mRequesterHint);
        } else {
            return "MediaProjectionConfig { " + "displayToCapture = " + mDisplayToCapture + ", "
                    + "regionToCapture = " + captureRegionToString(mRegionToCapture) + " }";
        }
    }

    /**
     * The particular display to capture. Only used when {@link #PROJECTION_SOURCE_DISPLAY} is
     * set; ignored otherwise.
     * <p>
     * Only supports values of {@link android.view.Display#DEFAULT_DISPLAY}.
     *
     * @hide
     */
    public int getDisplayToCapture() {
        return mDisplayToCapture;
    }

    /**
     * The region to capture. Defaults to the user's choice.
     *
     * @hide
     */
    public @CaptureRegion int getRegionToCapture() {
        return mRegionToCapture;
    }

    /**
     * A bitmask representing of requested projection sources.
     * <p>
     * The system supports different kind of media projection session. Although the user is
     * picking the target content, the requesting application can configure the choices displayed
     * to the user.
     */
    @FlaggedApi(Flags.FLAG_APP_CONTENT_SHARING)
    public @MediaProjectionSource int getProjectionSources() {
        return mProjectionSources;
    }

    @Override
    public boolean equals(@Nullable Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        MediaProjectionConfig that = (MediaProjectionConfig) o;
        if (Flags.appContentSharing()) {
            return mDisplayToCapture == that.mDisplayToCapture
                    && mProjectionSources == that.mProjectionSources
                    && mInitialSelection == that.mInitialSelection
                    && Objects.equals(mRequesterHint, that.mRequesterHint);
        } else {
            return mDisplayToCapture == that.mDisplayToCapture
                    && mRegionToCapture == that.mRegionToCapture;
        }
    }

    @Override
    public int hashCode() {
        int _hash = 1;
        if (Flags.appContentSharing()) {
            return Objects.hash(mDisplayToCapture, mProjectionSources, mInitialSelection,
                    mRequesterHint);
        } else {
            _hash = 31 * _hash + mDisplayToCapture;
            _hash = 31 * _hash + mRegionToCapture;
        }
        return _hash;
    }

    @Override
    public void writeToParcel(@NonNull android.os.Parcel dest, int flags) {
        dest.writeInt(mDisplayToCapture);
        if (Flags.appContentSharing()) {
            dest.writeInt(mProjectionSources);
            dest.writeString(mRequesterHint);
            dest.writeInt(mInitialSelection);
        } else {
            dest.writeInt(mRegionToCapture);
        }
    }

    @Override
    public int describeContents() {
        return 0;
    }

    /** @hide */
    /* package-private */ MediaProjectionConfig(@NonNull android.os.Parcel in) {
        mDisplayToCapture = in.readInt();
        if (Flags.appContentSharing()) {
            mProjectionSources = in.readInt();
            mRequesterHint = in.readString();
            mInitialSelection = in.readInt();
        } else {
            mRegionToCapture = in.readInt();
            mProjectionSources = -1;
            mRequesterHint = null;
            mInitialSelection = -1;
        }
    }

    public static final @NonNull Parcelable.Creator<MediaProjectionConfig> CREATOR =
            new Parcelable.Creator<>() {
                @Override
                public MediaProjectionConfig[] newArray(int size) {
                    return new MediaProjectionConfig[size];
                }

                @Override
                public MediaProjectionConfig createFromParcel(@NonNull android.os.Parcel in) {
                    return new MediaProjectionConfig(in);
                }
            };

    /**
     * Returns true if the provided source should be enabled.
     *
     * @param projectionSource projection source integer to check for. The parameter can also be a
     *                         bitmask of multiple sources.
     */
    @FlaggedApi(Flags.FLAG_APP_CONTENT_SHARING)
    public boolean isSourceEnabled(@MediaProjectionSource int projectionSource) {
        return (mProjectionSources & projectionSource) > 0;
    }

    /**
     * Returns a bit mask of one, and only one, of the projection type flag.
     */
    @FlaggedApi(Flags.FLAG_APP_CONTENT_SHARING)
    @MediaProjectionSource
    public int getInitiallySelectedSource() {
        return mInitialSelection;
    }

    /**
     * A hint set by the requesting app indicating who the requester of this {@link MediaProjection}
     * session is.
     * <p>
     * The UI component prompting the user for the permission to start the session can use
     * this hint to provide more information about the origin of the request (e.g. a browser
     * tab title, a meeting id if sharing to a video conferencing app, a player name if
     * sharing the screen within a game).
     *
     * @return the hint to be displayed if set, null otherwise.
     */
    @FlaggedApi(Flags.FLAG_APP_CONTENT_SHARING)
    @Nullable
    public CharSequence getRequesterHint() {
        return mRequesterHint;
    }

    private static int createValidSourcesMask() {
        int validSources = 0;
        for (int projectionSource : PROJECTION_SOURCES) {
            validSources |= projectionSource;
        }
        return validSources;
    }

    @FlaggedApi(Flags.FLAG_APP_CONTENT_SHARING)
    public static final class Builder {
        private int mOptions = 0;
        private String mRequesterHint = null;

        @MediaProjectionSource
        private int mInitialSelection;

        public Builder() {
            if (!Flags.appContentSharing()) {
                throw new UnsupportedOperationException("Flag FLAG_APP_CONTENT_SHARING disabled");
            }
        }

        /**
         * Indicates which projection source the UI component should display to the user
         * first. Calling this method without enabling the respective choice will have no effect.
         *
         * @return instance of this {@link Builder}.
         * @see #setSourceEnabled(int, boolean)
         */
        @NonNull
        public Builder setInitiallySelectedSource(@MediaProjectionSource int projectionSource) {
            for (int source : PROJECTION_SOURCES) {
                if (projectionSource == source) {
                    mInitialSelection = projectionSource;
                    return this;
                }
            }
            throw new IllegalArgumentException(
                    ("projectionSource is no a valid projection source. projectionSource must be "
                            + "one of %s but was %s")
                            .formatted(Arrays.toString(PROJECTION_SOURCES_STRING),
                                    projectionSourceToString(projectionSource)));
        }

        /**
         * Let the requesting app indicate who the requester of this {@link MediaProjection}
         * session is..
         * <p>
         * The UI component prompting the user for the permission to start the session can use
         * this hint to provide more information about the origin of the request (e.g. a browser
         * tab title, a meeting id if sharing to a video conferencing app, a player name if
         * sharing the screen within a game).
         * <p>
         * Note that setting this won't hide or change the name of the application
         * requesting the session.
         *
         * @return instance of this {@link Builder}.
         */
        @NonNull
        public Builder setRequesterHint(@Nullable String requesterHint) {
            mRequesterHint = requesterHint;
            return this;
        }

        /**
         * Set whether the UI component requesting the user permission to share their screen
         * should display an option to share the specified source
         *
         * @param source  the projection source to enable or disable
         * @param enabled true to enable the source, false otherwise
         * @return this instance for chaining.
         * @throws IllegalArgumentException if the source is not one of the valid sources.
         */
        @NonNull
        @SuppressLint("MissingGetterMatchingBuilder") // isSourceEnabled is defined
        public Builder setSourceEnabled(@MediaProjectionSource int source, boolean enabled) {
            if ((source & VALID_PROJECTION_SOURCES) == 0) {
                throw new IllegalArgumentException(
                        ("source is no a valid projection source. source must be "
                                + "any of %s but was %s")
                                .formatted(Arrays.toString(PROJECTION_SOURCES_STRING),
                                        projectionSourceToString(source)));
            }
            mOptions = enabled ? mOptions | source : mOptions & ~source;
            return this;
        }

        /**
         * Builds a new immutable instance of {@link MediaProjectionConfig}
         */
        @NonNull
        public MediaProjectionConfig build() {
            return new MediaProjectionConfig(mOptions, mRequesterHint, DEFAULT_DISPLAY,
                    mInitialSelection);
        }
    }
}
