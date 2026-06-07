/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 * All rights reserved.
 *
 * This source code is licensed under the license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.meta.wearable.dat.externalsampleapps.cameraaccess.ui

import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.meta.wearable.dat.camera.types.StreamState
import com.meta.wearable.dat.externalsampleapps.cameraaccess.R
import androidx.compose.material3.Text
import com.meta.wearable.dat.externalsampleapps.cameraaccess.display.DisplayPreviewBoard
import com.meta.wearable.dat.externalsampleapps.cameraaccess.display.WidgetBoardView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.meta.wearable.dat.externalsampleapps.cameraaccess.gemini.GeminiSessionViewModel
import com.meta.wearable.dat.externalsampleapps.cameraaccess.stream.StreamViewModel
import com.meta.wearable.dat.externalsampleapps.cameraaccess.stream.StreamingMode
import com.meta.wearable.dat.externalsampleapps.cameraaccess.wearables.WearablesViewModel
import com.meta.wearable.dat.externalsampleapps.cameraaccess.webrtc.WebRTCConnectionState
import com.meta.wearable.dat.externalsampleapps.cameraaccess.webrtc.WebRTCSessionViewModel

@Composable
fun StreamScreen(
    wearablesViewModel: WearablesViewModel,
    isPhoneMode: Boolean = false,
    modifier: Modifier = Modifier,
    streamViewModel: StreamViewModel =
        viewModel(
            factory =
                StreamViewModel.Factory(
                    application = (LocalActivity.current as ComponentActivity).application,
                    wearablesViewModel = wearablesViewModel,
                ),
        ),
    geminiViewModel: GeminiSessionViewModel = viewModel(),
    webrtcViewModel: WebRTCSessionViewModel = viewModel(),
) {
    val streamUiState by streamViewModel.uiState.collectAsStateWithLifecycle()
    val geminiUiState by geminiViewModel.uiState.collectAsStateWithLifecycle()
    val webrtcUiState by webrtcViewModel.uiState.collectAsStateWithLifecycle()
    val lifecycleOwner = LocalLifecycleOwner.current
    val context = LocalContext.current

    LaunchedEffect(geminiViewModel, streamViewModel) {
        streamViewModel.geminiViewModel = geminiViewModel
        geminiViewModel.onWidgetsRendered = { specs ->
            streamViewModel.sendWidgetsToGlasses(specs)
        }
    }

    LaunchedEffect(webrtcViewModel) {
        streamViewModel.webrtcViewModel = webrtcViewModel
    }

    LaunchedEffect(isPhoneMode) {
        if (isPhoneMode) {
            geminiViewModel.streamingMode = StreamingMode.PHONE
            streamViewModel.startPhoneCamera(lifecycleOwner)
        } else {
            geminiViewModel.streamingMode = StreamingMode.GLASSES
            streamViewModel.startStream()
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            geminiViewModel.onWidgetsRendered = null
            if (geminiUiState.isGeminiActive) geminiViewModel.stopSession()
            if (webrtcUiState.isActive) webrtcViewModel.stopSession()
            streamViewModel.stopStream()
        }
    }

    LaunchedEffect(geminiUiState.errorMessage) {
        geminiUiState.errorMessage?.let { msg ->
            Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
            geminiViewModel.clearError()
        }
    }
    LaunchedEffect(webrtcUiState.errorMessage) {
        webrtcUiState.errorMessage?.let { msg ->
            Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
            webrtcViewModel.clearError()
        }
    }

    val showPiP =
        webrtcUiState.isActive && webrtcUiState.connectionState is WebRTCConnectionState.Connected

    Box(modifier = modifier.fillMaxSize().background(Color.Black)) {
        if (showPiP) {
            PiPVideoView(
                localFrame = streamUiState.videoFrame,
                remoteVideoTrack = webrtcUiState.remoteVideoTrack,
                hasRemoteVideo = webrtcUiState.hasRemoteVideo,
                eglContext = webrtcViewModel.eglContext,
                modifier = Modifier.fillMaxSize(),
            )
        } else if (streamUiState.videoFrame != null && streamUiState.hasReceivedFirstFrame) {
            key(streamUiState.videoFrameCount) {
                Image(
                    bitmap = streamUiState.videoFrame!!.asImageBitmap(),
                    contentDescription = stringResource(R.string.live_stream),
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )
            }
        } else if (
            streamUiState.streamState == StreamState.STARTING ||
                (streamUiState.videoFrame == null && streamUiState.streamState != StreamState.STOPPED)
        ) {
            CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
        }

        if (geminiUiState.widgets.isNotEmpty()) {
            Column(
                modifier =
                    Modifier.fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 20.dp)
                        .padding(top = 70.dp, bottom = 140.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                if (streamUiState.streamingMode == StreamingMode.GLASSES) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        WidgetSectionLabel("SDK · MWDATDisplay (FlexBox)")
                        DisplayPreviewBoard(
                            widgets = geminiUiState.widgets,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        WidgetSectionLabel("Compose · native")
                        WidgetBoardView(
                            widgets = geminiUiState.widgets,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                } else {
                    DisplayPreviewBoard(
                        widgets = geminiUiState.widgets,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }

        Box(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
            Column(
                modifier = Modifier.align(Alignment.TopStart).statusBarsPadding().padding(top = 8.dp),
            ) {
                if (geminiUiState.isGeminiActive) {
                    GeminiOverlay(uiState = geminiUiState)
                }
                if (webrtcUiState.isActive) {
                    Spacer(modifier = Modifier.height(4.dp))
                    WebRTCOverlay(uiState = webrtcUiState)
                }
            }

            ControlsRow(
                onStopStream = {
                    if (geminiUiState.isGeminiActive) geminiViewModel.stopSession()
                    if (webrtcUiState.isActive) webrtcViewModel.stopSession()
                    streamViewModel.stopStream()
                    wearablesViewModel.navigateToDeviceSelection()
                },
                onCapturePhoto = { streamViewModel.capturePhoto() },
                onToggleAI = {
                    if (geminiUiState.isGeminiActive) {
                        geminiViewModel.stopSession()
                    } else {
                        geminiViewModel.startSession(context)
                    }
                },
                isAIActive = geminiUiState.isGeminiActive,
                onToggleLive = {
                    if (webrtcUiState.isActive) {
                        webrtcViewModel.stopSession()
                    } else {
                        webrtcViewModel.startSession()
                    }
                },
                isLiveActive = webrtcUiState.isActive,
                showCaptureButton = streamUiState.streamingMode == StreamingMode.GLASSES,
                aiEnabled = !webrtcUiState.isActive,
                liveEnabled = !geminiUiState.isGeminiActive,
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }
    }

    streamUiState.capturedPhoto?.let { photo ->
        if (streamUiState.isShareDialogVisible) {
            SharePhotoDialog(
                photo = photo,
                onDismiss = { streamViewModel.hideShareDialog() },
                onShare = { bitmap ->
                    streamViewModel.sharePhoto(bitmap)
                    streamViewModel.hideShareDialog()
                },
            )
        }
    }
}

@Composable
private fun WidgetSectionLabel(text: String) {
    Text(
        text = text,
        fontSize = 11.sp,
        fontWeight = FontWeight.SemiBold,
        color = Color.White.copy(alpha = 0.7f),
        modifier = Modifier.fillMaxWidth(),
    )
}
