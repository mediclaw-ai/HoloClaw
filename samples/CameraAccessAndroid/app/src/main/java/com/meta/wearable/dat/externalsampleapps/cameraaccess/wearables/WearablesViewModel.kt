/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 * All rights reserved.
 *
 * This source code is licensed under the license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.meta.wearable.dat.externalsampleapps.cameraaccess.wearables

import android.app.Activity
import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.meta.wearable.dat.camera.types.VideoQuality
import com.meta.wearable.dat.core.Wearables
import com.meta.wearable.dat.core.selectors.AutoDeviceSelector
import com.meta.wearable.dat.externalsampleapps.cameraaccess.BuildConfig
import com.meta.wearable.dat.mockdevice.MockDeviceKit
import com.meta.wearable.dat.core.selectors.DeviceSelector
import com.meta.wearable.dat.core.types.DeviceCompatibility
import com.meta.wearable.dat.core.types.DeviceIdentifier
import com.meta.wearable.dat.core.types.Permission
import com.meta.wearable.dat.core.types.PermissionStatus
import com.meta.wearable.dat.core.types.RegistrationState
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class WearablesViewModel(application: Application) : AndroidViewModel(application) {
  private val _uiState = MutableStateFlow(WearablesUiState())
  val uiState: StateFlow<WearablesUiState> = _uiState.asStateFlow()

  val deviceSelector: DeviceSelector by lazy { AutoDeviceSelector() }
  private var deviceSelectorJob: Job? = null

  private var monitoringStarted = false
  private val deviceMonitoringJobs = mutableMapOf<DeviceIdentifier, Job>()
  private val deviceCompatibility = mutableMapOf<DeviceIdentifier, DeviceCompatibility>()

  fun startMonitoring() {
    if (monitoringStarted) return
    monitoringStarted = true

    deviceSelectorJob = viewModelScope.launch {
      deviceSelector.activeDeviceFlow().collect { device ->
        _uiState.update { it.copy(hasActiveDevice = device != null) }
      }
    }

    viewModelScope.launch {
      Wearables.registrationState.collect { value ->
        val previousState = _uiState.value.registrationState
        val showGettingStartedSheet =
            value == RegistrationState.REGISTERED && previousState == RegistrationState.REGISTERING
        _uiState.update {
          it.copy(registrationState = value, isGettingStartedSheetVisible = showGettingStartedSheet)
        }
      }
    }

    viewModelScope.launch {
      Wearables.devices.collect { value ->
        _uiState.update { it.copy(devices = value.toList().toImmutableList()) }
        monitorDeviceCompatibility(value)
        refreshMockDeviceState()
      }
    }
  }

  fun refreshMockDeviceState() {
    if (!BuildConfig.DEBUG) {
      _uiState.update { it.copy(hasMockDevice = false) }
      return
    }
    val hasMock =
        MockDeviceKit.getInstance(getApplication()).pairedDevices.isNotEmpty()
    _uiState.update { it.copy(hasMockDevice = hasMock) }
  }

  private fun monitorDeviceCompatibility(devices: Set<DeviceIdentifier>) {
    val removedDevices = deviceMonitoringJobs.keys - devices
    removedDevices.forEach { deviceId ->
      deviceMonitoringJobs[deviceId]?.cancel()
      deviceMonitoringJobs.remove(deviceId)
      deviceCompatibility.remove(deviceId)
    }
    updateFirmwareUpdateRequired()

    val newDevices = devices - deviceMonitoringJobs.keys
    newDevices.forEach { deviceId ->
      val job = viewModelScope.launch {
        Wearables.devicesMetadata[deviceId]?.collect { metadata ->
          deviceCompatibility[deviceId] = metadata.compatibility
          updateFirmwareUpdateRequired()
          if (metadata.compatibility == DeviceCompatibility.DEVICE_UPDATE_REQUIRED) {
            val deviceName = metadata.name.ifEmpty { deviceId }
            setRecentError("Device '$deviceName' requires an update to work with this app")
          }
        }
      }
      deviceMonitoringJobs[deviceId] = job
    }
  }

  fun startRegistration(activity: Activity) {
    Wearables.startRegistration(activity)
  }

  fun startUnregistration(activity: Activity) {
    Wearables.startUnregistration(activity)
  }

  fun openFirmwareUpdate(activity: Activity) {
    Wearables.openFirmwareUpdate(activity).onFailure { error, _ ->
      setRecentError(error.description)
    }
  }

  fun openDATGlassesAppUpdate(activity: Activity) {
    Wearables.openDATGlassesAppUpdate(activity).onFailure { error, _ ->
      setRecentError(error.description)
    }
  }

  fun updateVideoQuality(quality: VideoQuality) {
    if (!_uiState.value.isStreaming) {
      _uiState.update { it.copy(selectedVideoQuality = quality) }
    }
  }

  fun navigateToStreaming(onRequestWearablesPermission: suspend (Permission) -> PermissionStatus) {
    viewModelScope.launch {
      val permission = Permission.CAMERA
      val result = Wearables.checkPermissionStatus(permission)

      result.onFailure { error, _ ->
        setRecentError("Permission check error: ${error.description}")
        return@launch
      }

      if (result.getOrNull() == PermissionStatus.Granted) {
        _uiState.update { it.copy(isStreaming = true, isPhoneMode = false) }
        return@launch
      }

      when (onRequestWearablesPermission(permission)) {
        PermissionStatus.Denied -> setRecentError("Permission denied")
        PermissionStatus.Granted ->
            _uiState.update { it.copy(isStreaming = true, isPhoneMode = false) }
      }
    }
  }

  fun navigateToPhoneMode() {
    _uiState.update { it.copy(isStreaming = true, isPhoneMode = true) }
  }

  fun navigateToDeviceSelection() {
    _uiState.update { it.copy(isStreaming = false, isPhoneMode = false) }
  }

  fun showSettings() {
    _uiState.update { it.copy(isSettingsVisible = true) }
  }

  fun hideSettings() {
    _uiState.update { it.copy(isSettingsVisible = false) }
  }

  fun showDebugMenu() {
    _uiState.update { it.copy(isDebugMenuVisible = true) }
  }

  fun hideDebugMenu() {
    _uiState.update { it.copy(isDebugMenuVisible = false) }
    refreshMockDeviceState()
  }

  fun clearRecentError() {
    _uiState.update { it.copy(recentError = null) }
  }

  internal fun setRecentError(error: String) {
    _uiState.update { it.copy(recentError = error) }
  }

  internal fun setDatAppUpdateRequired(required: Boolean) {
    _uiState.update { it.copy(isDatAppUpdateRequired = required) }
  }

  fun onPermissionsResult(permissionsResult: Map<String, Boolean>, onAllGranted: () -> Unit) {
    val granted = permissionsResult.entries.all { it.value }
    _uiState.update { it.copy(canRegister = granted) }
    if (granted) {
      onAllGranted()
      startMonitoring()
    } else {
      _uiState.update {
        it.copy(recentError = "Allow All Permissions (Bluetooth, Bluetooth Connect, Internet, Microphone, Camera)")
      }
    }
  }

  fun showGettingStartedSheet() {
    _uiState.update { it.copy(isGettingStartedSheetVisible = true) }
  }

  fun hideGettingStartedSheet() {
    _uiState.update { it.copy(isGettingStartedSheetVisible = false) }
  }

  override fun onCleared() {
    super.onCleared()
    deviceMonitoringJobs.values.forEach { it.cancel() }
    deviceMonitoringJobs.clear()
    deviceSelectorJob?.cancel()
  }

  private fun updateFirmwareUpdateRequired() {
    val isRequired =
        deviceCompatibility.values.any { it == DeviceCompatibility.DEVICE_UPDATE_REQUIRED }
    _uiState.update { it.copy(isFirmwareUpdateRequired = isRequired) }
  }
}
