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

package com.android.systemui.wallpapers.data.repository

import android.app.WallpaperInfo
import android.app.WallpaperManager
import android.app.WallpaperManager.FLAG_LOCK
import android.content.ComponentName
import android.content.Intent
import android.content.pm.UserInfo
import android.platform.test.annotations.EnableFlags
import android.provider.Settings
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.app.wallpaperManager
import com.android.internal.R
import com.android.systemui.SysuiTestCase
import com.android.systemui.broadcast.broadcastDispatcher
import com.android.systemui.common.ui.data.repository.fakeConfigurationRepository
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.kosmos.testScope
import com.android.systemui.res.R as SysUIR
import com.android.systemui.shared.Flags as SharedFlags
import com.android.systemui.testKosmos
import com.android.systemui.user.data.model.SelectedUserModel
import com.android.systemui.user.data.model.SelectionStatus
import com.android.systemui.user.data.repository.fakeUserRepository
import com.android.systemui.util.settings.fakeSettings
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.eq
import org.mockito.kotlin.any
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

@SmallTest
@RunWith(AndroidJUnit4::class)
class WallpaperRepositoryImplTest : SysuiTestCase() {
    private var isWallpaperSupported = true
    private val kosmos =
        testKosmos().apply {
            wallpaperManager =
                mock<WallpaperManager>() {
                    on { isWallpaperSupported } doAnswer { isWallpaperSupported }
                }
        }
    private val secureSettings = kosmos.fakeSettings
    private val testScope = kosmos.testScope
    private val userRepository = kosmos.fakeUserRepository
    private val broadcastDispatcher = kosmos.broadcastDispatcher
    private val configRepository = kosmos.fakeConfigurationRepository

    // Initialized in each test since certain flows rely on mocked data that isn't
    // modifiable after start, like wallpaperManager.isWallpaperSupported
    private lateinit var underTest: WallpaperRepositoryImpl

    lateinit var focalAreaTarget: String

    @Before
    fun setUp() {
        focalAreaTarget = context.resources.getString(SysUIR.string.focal_area_target)
    }

    @Test
    fun wallpaperInfo_nullInfo() =
        testScope.runTest {
            underTest = kosmos.wallpaperRepository
            val latest by collectLastValue(underTest.wallpaperInfo)

            whenever(kosmos.wallpaperManager.getWallpaperInfoForUser(any())).thenReturn(null)

            broadcastDispatcher.sendIntentToMatchingReceiversOnly(
                context,
                Intent(Intent.ACTION_WALLPAPER_CHANGED),
            )

            assertThat(latest).isNull()
        }

    @Test
    fun wallpaperInfo_hasInfoFromManager() =
        testScope.runTest {
            underTest = kosmos.wallpaperRepository
            val latest by collectLastValue(underTest.wallpaperInfo)

            whenever(kosmos.wallpaperManager.getWallpaperInfoForUser(any()))
                .thenReturn(UNSUPPORTED_WP)

            broadcastDispatcher.sendIntentToMatchingReceiversOnly(
                context,
                Intent(Intent.ACTION_WALLPAPER_CHANGED),
            )

            assertThat(latest).isEqualTo(UNSUPPORTED_WP)
        }

    @Test
    fun wallpaperInfo_initialValueIsFetched() =
        testScope.runTest {
            underTest = kosmos.wallpaperRepository
            whenever(kosmos.wallpaperManager.getWallpaperInfoForUser(USER_WITH_SUPPORTED_WP.id))
                .thenReturn(SUPPORTED_WP)
            userRepository.setUserInfos(listOf(USER_WITH_SUPPORTED_WP))
            userRepository.setSelectedUserInfo(USER_WITH_SUPPORTED_WP)

            // Start up the repo and let it run the initial fetch
            underTest.wallpaperInfo
            runCurrent()

            assertThat(underTest.wallpaperInfo.value).isEqualTo(SUPPORTED_WP)
        }

    @Test
    fun wallpaperInfo_updatesOnUserChanged() =
        testScope.runTest {
            underTest = kosmos.wallpaperRepository
            val latest by collectLastValue(underTest.wallpaperInfo)

            val user3 = UserInfo(/* id= */ 3, /* name= */ "user3", /* flags= */ 0)
            val user3Wp = mock<WallpaperInfo>()
            whenever(kosmos.wallpaperManager.getWallpaperInfoForUser(user3.id)).thenReturn(user3Wp)

            val user4 = UserInfo(/* id= */ 4, /* name= */ "user4", /* flags= */ 0)
            val user4Wp = mock<WallpaperInfo>()
            whenever(kosmos.wallpaperManager.getWallpaperInfoForUser(user4.id)).thenReturn(user4Wp)

            userRepository.setUserInfos(listOf(user3, user4))

            // WHEN user3 is selected
            userRepository.setSelectedUserInfo(user3)

            // THEN user3's wallpaper is used
            assertThat(latest).isEqualTo(user3Wp)

            // WHEN the user is switched to user4
            userRepository.setSelectedUserInfo(user4)

            // THEN user4's wallpaper is used
            assertThat(latest).isEqualTo(user4Wp)
        }

    @Test
    fun wallpaperInfo_doesNotUpdateOnUserChanging() =
        testScope.runTest {
            underTest = kosmos.wallpaperRepository
            val latest by collectLastValue(underTest.wallpaperInfo)

            val user3 = UserInfo(/* id= */ 3, /* name= */ "user3", /* flags= */ 0)
            val user3Wp = mock<WallpaperInfo>()
            whenever(kosmos.wallpaperManager.getWallpaperInfoForUser(user3.id)).thenReturn(user3Wp)

            val user4 = UserInfo(/* id= */ 4, /* name= */ "user4", /* flags= */ 0)
            val user4Wp = mock<WallpaperInfo>()
            whenever(kosmos.wallpaperManager.getWallpaperInfoForUser(user4.id)).thenReturn(user4Wp)

            userRepository.setUserInfos(listOf(user3, user4))

            // WHEN user3 is selected
            userRepository.setSelectedUserInfo(user3)

            // THEN user3's wallpaper is used
            assertThat(latest).isEqualTo(user3Wp)

            // WHEN the user has started switching to user4 but hasn't finished yet
            userRepository.selectedUser.value =
                SelectedUserModel(user4, SelectionStatus.SELECTION_IN_PROGRESS)

            // THEN the wallpaper still matches user3
            assertThat(latest).isEqualTo(user3Wp)
        }

    @Test
    fun wallpaperInfo_updatesOnIntent() =
        testScope.runTest {
            underTest = kosmos.wallpaperRepository
            val latest by collectLastValue(underTest.wallpaperInfo)

            val wp1 = mock<WallpaperInfo>()
            whenever(kosmos.wallpaperManager.getWallpaperInfoForUser(any())).thenReturn(wp1)

            assertThat(latest).isEqualTo(wp1)

            // WHEN the info is new and a broadcast is sent
            val wp2 = mock<WallpaperInfo>()
            whenever(kosmos.wallpaperManager.getWallpaperInfoForUser(any())).thenReturn(wp2)
            broadcastDispatcher.sendIntentToMatchingReceiversOnly(
                context,
                Intent(Intent.ACTION_WALLPAPER_CHANGED),
            )

            // THEN the flow updates
            assertThat(latest).isEqualTo(wp2)
        }

    @Test
    fun wallpaperInfo_wallpaperNotSupported_alwaysNull() =
        testScope.runTest {
            isWallpaperSupported = false
            underTest = kosmos.wallpaperRepository

            val latest by collectLastValue(underTest.wallpaperInfo)
            assertThat(latest).isNull()

            // Even WHEN there *is* current wallpaper
            val wp1 = mock<WallpaperInfo>()
            whenever(kosmos.wallpaperManager.getWallpaperInfoForUser(any())).thenReturn(wp1)
            broadcastDispatcher.sendIntentToMatchingReceiversOnly(
                context,
                Intent(Intent.ACTION_WALLPAPER_CHANGED),
            )

            // THEN the value is still null because wallpaper isn't supported
            assertThat(latest).isNull()
        }

    @Test
    @EnableFlags(SharedFlags.FLAG_AMBIENT_AOD)
    fun wallpaperSupportsAmbientMode_deviceDoesNotSupport_false() =
        testScope.runTest {
            underTest = kosmos.wallpaperRepository
            secureSettings.putInt(Settings.Secure.DOZE_ALWAYS_ON_WALLPAPER_ENABLED, 1)
            context.orCreateTestableResources.addOverride(
                R.bool.config_dozeSupportsAodWallpaper,
                false,
            )

            val latest by collectLastValue(underTest.wallpaperSupportsAmbientMode)
            assertThat(latest).isFalse()
        }

    @Test
    @EnableFlags(SharedFlags.FLAG_AMBIENT_AOD)
    fun wallpaperSupportsAmbientMode_deviceDoesSupport_true() =
        testScope.runTest {
            underTest = kosmos.wallpaperRepository
            secureSettings.putInt(Settings.Secure.DOZE_ALWAYS_ON_WALLPAPER_ENABLED, 1)
            context.orCreateTestableResources.addOverride(
                R.bool.config_dozeSupportsAodWallpaper,
                false,
            )
            configRepository.onAnyConfigurationChange()
            val latest by collectLastValue(underTest.wallpaperSupportsAmbientMode)
            assertThat(latest).isFalse()

            // Validate that a configuration change recalculates the flow
            context.orCreateTestableResources.addOverride(
                R.bool.config_dozeSupportsAodWallpaper,
                true,
            )
            configRepository.onAnyConfigurationChange()
            assertThat(latest).isTrue()
        }

    @Test
    @EnableFlags(SharedFlags.FLAG_AMBIENT_AOD)
    fun wallpaperSupportsAmbientMode_deviceDoesSupport_settingDisabled_false() =
        testScope.runTest {
            underTest = kosmos.wallpaperRepository
            secureSettings.putInt(Settings.Secure.DOZE_ALWAYS_ON_WALLPAPER_ENABLED, 0)
            context.orCreateTestableResources.addOverride(
                R.bool.config_dozeSupportsAodWallpaper,
                true,
            )

            val latest by collectLastValue(underTest.wallpaperSupportsAmbientMode)
            assertThat(latest).isFalse()
        }

    @Test
    @EnableFlags(SharedFlags.FLAG_EXTENDED_WALLPAPER_EFFECTS)
    fun shouldSendNotificationLayout_setExtendedEffectsWallpaper() =
        testScope.runTest {
            underTest = kosmos.wallpaperRepository
            val latest by collectLastValue(underTest.shouldSendFocalArea)
            val extendedEffectsWallpaper =
                mock<WallpaperInfo>().apply {
                    whenever(this.component).thenReturn(ComponentName(context, focalAreaTarget))
                }

            whenever(kosmos.wallpaperManager.getWallpaperInfoForUser(any()))
                .thenReturn(extendedEffectsWallpaper)
            broadcastDispatcher.sendIntentToMatchingReceiversOnly(
                context,
                Intent(Intent.ACTION_WALLPAPER_CHANGED),
            )
            assertThat(latest).isTrue()
        }

    @Test
    @EnableFlags(SharedFlags.FLAG_EXTENDED_WALLPAPER_EFFECTS)
    fun shouldSendNotificationLayout_setNotExtendedEffectsWallpaper() =
        testScope.runTest {
            underTest = kosmos.wallpaperRepository
            val latest by collectLastValue(underTest.shouldSendFocalArea)
            val extendedEffectsWallpaper =
                mock<WallpaperInfo>().apply {
                    whenever(this.component).thenReturn(ComponentName("", focalAreaTarget))
                }
            whenever(kosmos.wallpaperManager.getWallpaperInfoForUser(any()))
                .thenReturn(extendedEffectsWallpaper)
            broadcastDispatcher.sendIntentToMatchingReceiversOnly(
                context,
                Intent(Intent.ACTION_WALLPAPER_CHANGED),
            )
            assertThat(latest).isTrue()

            whenever(kosmos.wallpaperManager.getWallpaperInfoForUser(any()))
                .thenReturn(UNSUPPORTED_WP)
            broadcastDispatcher.sendIntentToMatchingReceiversOnly(
                context,
                Intent(Intent.ACTION_WALLPAPER_CHANGED),
            )
            runCurrent()

            assertThat(latest).isFalse()
        }

    @Test
    @EnableFlags(SharedFlags.FLAG_EXTENDED_WALLPAPER_EFFECTS)
    fun shouldSendNotificationLayout_setExtendedEffectsWallpaperOnlyForHomescreen() =
        testScope.runTest {
            underTest = kosmos.wallpaperRepository
            val latest by collectLastValue(underTest.shouldSendFocalArea)
            val extendedEffectsWallpaper =
                mock<WallpaperInfo>().apply {
                    whenever(this.component).thenReturn(ComponentName("", focalAreaTarget))
                }

            whenever(kosmos.wallpaperManager.lockScreenWallpaperExists()).thenReturn(true)
            whenever(kosmos.wallpaperManager.getWallpaperInfoForUser(any()))
                .thenReturn(extendedEffectsWallpaper)
            whenever(kosmos.wallpaperManager.getWallpaperInfo(eq(FLAG_LOCK), any()))
                .thenReturn(UNSUPPORTED_WP)
            broadcastDispatcher.sendIntentToMatchingReceiversOnly(
                context,
                Intent(Intent.ACTION_WALLPAPER_CHANGED),
            )
            assertThat(latest).isFalse()
        }

    @Test
    @EnableFlags(SharedFlags.FLAG_EXTENDED_WALLPAPER_EFFECTS)
    fun shouldSendNotificationLayout_setExtendedEffectsWallpaperOnlyForLockscreen() =
        testScope.runTest {
            underTest = kosmos.wallpaperRepository
            val latest by collectLastValue(underTest.shouldSendFocalArea)
            val extendedEffectsWallpaper =
                mock<WallpaperInfo>().apply {
                    whenever(this.component).thenReturn(ComponentName("", focalAreaTarget))
                }
            whenever(kosmos.wallpaperManager.lockScreenWallpaperExists()).thenReturn(true)
            whenever(kosmos.wallpaperManager.getWallpaperInfoForUser(any()))
                .thenReturn(UNSUPPORTED_WP)
            whenever(kosmos.wallpaperManager.getWallpaperInfo(eq(FLAG_LOCK), any()))
                .thenReturn(extendedEffectsWallpaper)
            broadcastDispatcher.sendIntentToMatchingReceiversOnly(
                context,
                Intent(Intent.ACTION_WALLPAPER_CHANGED),
            )
            assertThat(latest).isTrue()
        }

    private companion object {
        val USER_WITH_UNSUPPORTED_WP = UserInfo(/* id= */ 3, /* name= */ "user3", /* flags= */ 0)
        val UNSUPPORTED_WP =
            mock<WallpaperInfo>().apply { whenever(this.supportsAmbientMode()).thenReturn(false) }

        val USER_WITH_SUPPORTED_WP = UserInfo(/* id= */ 4, /* name= */ "user4", /* flags= */ 0)
        val SUPPORTED_WP =
            mock<WallpaperInfo>().apply { whenever(this.supportsAmbientMode()).thenReturn(true) }
    }
}
