/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 * All rights reserved.
 *
 * This source code is licensed under the license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.meta.wearable.dat.externalsampleapps.cameraaccess.display

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.meta.wearable.dat.core.Wearables
import com.meta.wearable.dat.core.selectors.AutoDeviceSelector
import com.meta.wearable.dat.core.session.DeviceSession
import com.meta.wearable.dat.core.session.DeviceSessionState
import com.meta.wearable.dat.core.types.DeviceSessionError
import com.meta.wearable.dat.display.Display
import com.meta.wearable.dat.display.addDisplay
import com.meta.wearable.dat.display.types.DisplayConfiguration
import com.meta.wearable.dat.display.types.DisplayState
import com.meta.wearable.dat.display.views.ContentScope
import com.meta.wearable.dat.externalsampleapps.cameraaccess.display.DisplayWidgets.board
import com.meta.wearable.dat.externalsampleapps.cameraaccess.display.DisplayWidgets.helloWorld
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class DisplayUiState(
    val isConnecting: Boolean = false,
    val isConnected: Boolean = false,
    val statusMessage: String = "",
    val showError: Boolean = false,
    val errorMessage: String = "",
)

class DisplayViewModel(application: Application) : AndroidViewModel(application) {
    companion object {
        private const val TAG = "DisplayViewModel"
    }

    private val _uiState = MutableStateFlow(DisplayUiState())
    val uiState: StateFlow<DisplayUiState> = _uiState.asStateFlow()

    private val deviceSelector = AutoDeviceSelector(filter = { it.isDisplayCapable() })
    private var deviceSession: DeviceSession? = null
    private var display: Display? = null
    private var sessionStateJob: Job? = null
    private var sessionErrorJob: Job? = null
    private var displayStateJob: Job? = null
    private var pendingContent: (ContentScope.() -> Unit)? = null

    fun sendHelloWorld() {
        send { helloWorld() }
    }

    fun sendBoard(specs: List<WidgetSpec>) {
        if (specs.isEmpty()) return
        send { board(specs) }
    }

    fun disconnect() {
        displayStateJob?.cancel()
        sessionStateJob?.cancel()
        sessionErrorJob?.cancel()
        display?.stop()
        display = null
        deviceSession?.stop()
        deviceSession = null
        pendingContent = null
        _uiState.value = DisplayUiState()
    }

    fun dismissError() {
        _uiState.update { it.copy(showError = false, errorMessage = "") }
    }

    private fun send(content: ContentScope.() -> Unit) {
        if (display != null && _uiState.value.isConnected) {
            viewModelScope.launch { doSend(content) }
            return
        }
        pendingContent = content
        if (display == null) {
            attachToDisplay()
        }
    }

    private suspend fun doSend(content: ContentScope.() -> Unit) {
        val activeDisplay = display ?: return
        activeDisplay.sendContent(content).fold(
            onSuccess = {
                _uiState.update {
                    it.copy(statusMessage = "Sent content to the glasses display")
                }
            },
            onFailure = { error, _ ->
                handleError(error.description)
            },
        )
    }

    private fun attachToDisplay() {
        if (display != null) return
        _uiState.update {
            it.copy(isConnecting = true, statusMessage = "Connecting to display…")
        }

        Wearables.createSession(deviceSelector)
            .onSuccess { session ->
                deviceSession = session
                sessionErrorJob = viewModelScope.launch {
                    session.errors.collect { error -> handleError(error.description) }
                }
                session.start()
                sessionStateJob = viewModelScope.launch {
                    session.state.collect { state ->
                        when (state) {
                            DeviceSessionState.STARTED -> setupDisplay(session)
                            DeviceSessionState.STOPPED ->
                                handleError(
                                    "The device session stopped before the display could start."
                                )
                            else -> Unit
                        }
                    }
                }
            }
            .onFailure { error, _ ->
                val message =
                    when (error) {
                        DeviceSessionError.DAT_APP_ON_THE_GLASSES_UPDATE_REQUIRED ->
                            error.description
                        DeviceSessionError.NO_ELIGIBLE_DEVICE ->
                            "No Meta Ray-Ban Display glasses found. Connect display-capable glasses and try again."
                        else -> "Failed to start display session: ${error.description}"
                    }
                handleError(message)
            }
    }

    private fun setupDisplay(session: DeviceSession) {
        if (display != null) return
        viewModelScope.launch {
            session.addDisplay(DisplayConfiguration())
                .onSuccess { capability ->
                    display = capability
                    displayStateJob = viewModelScope.launch {
                        capability.state.collect { state -> handleDisplayState(state) }
                    }
                }
                .onFailure { error, _ ->
                    handleError("Failed to attach display: ${error.description}")
                }
        }
    }

    private fun handleDisplayState(state: DisplayState) {
        when (state) {
            DisplayState.STARTING -> Unit
            DisplayState.STARTED -> {
                _uiState.update {
                    it.copy(
                        isConnecting = false,
                        isConnected = true,
                        statusMessage = "Display connected",
                    )
                }
                pendingContent?.let { content ->
                    pendingContent = null
                    viewModelScope.launch { doSend(content) }
                }
            }
            DisplayState.STOPPING -> _uiState.update { it.copy(isConnected = false) }
            DisplayState.STOPPED, DisplayState.CLOSED -> {
                displayStateJob?.cancel()
                sessionStateJob?.cancel()
                sessionErrorJob?.cancel()
                display = null
                deviceSession?.stop()
                deviceSession = null
                pendingContent = null
                _uiState.update {
                    it.copy(isConnecting = false, isConnected = false, statusMessage = "")
                }
            }
        }
    }

    private fun handleError(message: String) {
        Log.e(TAG, message)
        _uiState.update {
            it.copy(
                errorMessage = message,
                showError = true,
                statusMessage = "",
                isConnecting = false,
            )
        }
    }

    override fun onCleared() {
        disconnect()
        super.onCleared()
    }
}
