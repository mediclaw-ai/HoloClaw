/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 * All rights reserved.
 *
 * This source code is licensed under the license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.meta.wearable.dat.externalsampleapps.cameraaccess

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.platform.app.InstrumentationRegistry
import com.meta.wearable.dat.mockdevice.MockDeviceKit
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
@LargeTest
class InstrumentationTest {

  companion object {
    private const val TAG = "InstrumentationTest"
  }

  @get:Rule val composeTestRule = createAndroidComposeRule<MainActivity>()
  val targetContext: Context = InstrumentationRegistry.getInstrumentation().targetContext

  @Before
  fun setup() {
    grantPermissions()
  }

  @After
  fun tearDown() {
    MockDeviceKit.getInstance(targetContext).disable()
  }

  @Test
  fun showsHomeScreenOnLaunch() {
    val homeTip = targetContext.getString(R.string.home_tip_video)
    composeTestRule.waitUntilExactlyOneExists(hasText(homeTip), timeoutMillis = 5000)
  }

  @Test
  fun showsNonStreamScreenWhenMockPaired() {
    val nonStreamScreenText = targetContext.getString(R.string.non_stream_screen_description)
    val mockDeviceKit = MockDeviceKit.getInstance(targetContext)
    mockDeviceKit.enable()
    mockDeviceKit.pairRaybanMeta().powerOn()

    composeTestRule.waitUntilExactlyOneExists(hasText(nonStreamScreenText), timeoutMillis = 5000)
  }

  @Test
  fun streamingStopsWhenDeviceFolded() {
    val startStreamButtonTitle = targetContext.getString(R.string.stream_button_title)
    val streamContentDescription = targetContext.getString(R.string.live_stream)
    val nonStreamScreenText = targetContext.getString(R.string.non_stream_screen_description)

    val mockDeviceKit = MockDeviceKit.getInstance(targetContext)
    mockDeviceKit.enable()
    val device = mockDeviceKit.pairRaybanMeta()
    device.powerOn()
    device.don()
    device.unfold()
    device.services.camera.setCameraFeed(getFileUri("plant.mp4"))

    composeTestRule.onNodeWithText(startStreamButtonTitle).performClick()
    composeTestRule.waitUntilExactlyOneExists(
        hasContentDescription(streamContentDescription),
        timeoutMillis = 10000,
    )

    device.fold()

    composeTestRule.waitUntilExactlyOneExists(hasText(nonStreamScreenText), timeoutMillis = 10000)
  }

  @Test
  fun startThenStopStreaming() {
    val startStreamButtonTitle = targetContext.getString(R.string.stream_button_title)
    val streamContentDescription = targetContext.getString(R.string.live_stream)
    val captureButtonIcon = targetContext.getString(R.string.capture_photo)
    val capturedImageContentDescription = targetContext.getString(R.string.captured_photo)

    val mockDeviceKit = MockDeviceKit.getInstance(targetContext)
    mockDeviceKit.enable()
    val device = mockDeviceKit.pairRaybanMeta()
    device.powerOn()
    device.don()
    val mockCameraKit = device.services.camera
    mockCameraKit.setCameraFeed(getFileUri("plant.mp4"))
    mockCameraKit.setCapturedImage(getFileUri("plant.png"))

    composeTestRule.onNodeWithText(startStreamButtonTitle).performClick()
    composeTestRule.waitUntilExactlyOneExists(
        hasContentDescription(streamContentDescription),
        timeoutMillis = 10000,
    )

    composeTestRule.onNodeWithContentDescription(captureButtonIcon).performClick()
    composeTestRule.waitUntilExactlyOneExists(
        hasContentDescription(capturedImageContentDescription),
        timeoutMillis = 15000,
    )
  }

  private fun grantPermissions() {
    grantPermission("android.permission.BLUETOOTH")
    grantPermission("android.permission.BLUETOOTH_CONNECT")
    grantPermission("android.permission.INTERNET")
    grantPermission("android.permission.CAMERA")
    grantPermission("android.permission.RECORD_AUDIO")
  }

  private fun grantPermission(permission: String) {
    val packageName = targetContext.packageName
    try {
      val instrumentation = InstrumentationRegistry.getInstrumentation()
      instrumentation.uiAutomation.executeShellCommand("pm grant $packageName $permission")
      Log.d(TAG, "Granted permission: $permission")
    } catch (e: IOException) {
      Log.e(TAG, "Failed to grant permission", e)
    }
  }

  private fun copyAssetToCache(assetName: String): File {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val assetManager = InstrumentationRegistry.getInstrumentation().context.assets
    val outFile = File(context.cacheDir, assetName)
    assetManager.open(assetName).use { input ->
      FileOutputStream(outFile).use { output -> input.copyTo(output) }
    }
    return outFile
  }

  private fun getFileUri(assetName: String): Uri = Uri.fromFile(copyAssetToCache(assetName))
}
