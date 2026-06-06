/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 * All rights reserved.
 *
 * This source code is licensed under the license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.meta.wearable.dat.externalsampleapps.cameraaccess.wearables

import com.meta.wearable.dat.camera.types.VideoQuality
import com.meta.wearable.dat.core.types.DeviceIdentifier
import com.meta.wearable.dat.core.types.RegistrationState
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf

data class WearablesUiState(
    val registrationState: RegistrationState = RegistrationState.UNAVAILABLE,
    val devices: ImmutableList<DeviceIdentifier> = persistentListOf(),
    val recentError: String? = null,
    val isStreaming: Boolean = false,
    val isDebugMenuVisible: Boolean = false,
    val isGettingStartedSheetVisible: Boolean = false,
    val isFirmwareUpdateRequired: Boolean = false,
    val isDatAppUpdateRequired: Boolean = false,
    val hasActiveDevice: Boolean = false,
    val canRegister: Boolean = false,
    val isPhoneMode: Boolean = false,
    val isSettingsVisible: Boolean = false,
    val selectedVideoQuality: VideoQuality = VideoQuality.LOW,
) {
  val isRegistered: Boolean =
      registrationState == RegistrationState.REGISTERED ||
          registrationState == RegistrationState.UNREGISTERING

  val isRegistering: Boolean = registrationState == RegistrationState.REGISTERING

  val canStartRegistration: Boolean = canRegister && !isRegistering

  val resolutionLabel: String
    get() =
        when (selectedVideoQuality) {
          VideoQuality.LOW -> "360x640"
          VideoQuality.MEDIUM -> "504x896"
          VideoQuality.HIGH -> "720x1280"
        }
}
