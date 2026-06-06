/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 * All rights reserved.
 *
 * This source code is licensed under the license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.meta.wearable.dat.externalsampleapps.cameraaccess.mockdevicekit

import com.meta.wearable.dat.mockdevice.api.MockRaybanMeta
import com.meta.wearable.dat.mockdevice.api.camera.CameraFacing

data class MockDeviceInfo(
    val device: MockRaybanMeta,
    val deviceId: String,
    val deviceName: String,
    val hasCameraFeed: Boolean = false,
    val hasCapturedImage: Boolean = false,
    val cameraSource: CameraFacing? = null,
    val isPoweredOn: Boolean = false,
    val isDonned: Boolean = false,
    val isUnfolded: Boolean = false,
)

data class MockDeviceKitUiState(
    val isEnabled: Boolean = false,
    val pairedDevices: List<MockDeviceInfo> = emptyList(),
)
