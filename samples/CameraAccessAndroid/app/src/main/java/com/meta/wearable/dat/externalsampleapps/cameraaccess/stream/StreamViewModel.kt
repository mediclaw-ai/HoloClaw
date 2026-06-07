/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 * All rights reserved.
 *
 * This source code is licensed under the license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.meta.wearable.dat.externalsampleapps.cameraaccess.stream

import android.annotation.SuppressLint
import android.app.Application
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.util.Log
import androidx.core.content.FileProvider
import androidx.exifinterface.media.ExifInterface
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.meta.wearable.dat.camera.Stream
import com.meta.wearable.dat.camera.addStream
import com.meta.wearable.dat.camera.types.PhotoData
import com.meta.wearable.dat.camera.types.StreamConfiguration
import com.meta.wearable.dat.camera.types.StreamError
import com.meta.wearable.dat.camera.types.StreamState
import com.meta.wearable.dat.camera.types.VideoFrame
import com.meta.wearable.dat.core.Wearables
import com.meta.wearable.dat.core.selectors.DeviceSelector
import com.meta.wearable.dat.core.session.DeviceSession
import com.meta.wearable.dat.core.session.DeviceSessionState
import com.meta.wearable.dat.core.types.DeviceSessionError
import com.meta.wearable.dat.display.Display
import com.meta.wearable.dat.display.addDisplay
import com.meta.wearable.dat.display.types.DisplayConfiguration
import com.meta.wearable.dat.display.types.DisplayState
import com.meta.wearable.dat.externalsampleapps.cameraaccess.R
import com.meta.wearable.dat.externalsampleapps.cameraaccess.display.DisplayWidgets.board
import com.meta.wearable.dat.externalsampleapps.cameraaccess.display.WidgetSpec
import com.meta.wearable.dat.externalsampleapps.cameraaccess.gemini.GeminiSessionViewModel
import com.meta.wearable.dat.externalsampleapps.cameraaccess.phone.PhoneCameraManager
import com.meta.wearable.dat.externalsampleapps.cameraaccess.wearables.WearablesViewModel
import com.meta.wearable.dat.externalsampleapps.cameraaccess.webrtc.WebRTCSessionViewModel
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

@SuppressLint("AutoCloseableUse")
class StreamViewModel(
    application: Application,
    private val wearablesViewModel: WearablesViewModel,
) : AndroidViewModel(application) {

  companion object {
    private const val TAG = "StreamViewModel"
    private val INITIAL_STATE = StreamUiState()
    private val SESSION_TERMINAL_STATES = setOf(StreamState.CLOSED)
  }

  private val deviceSelector: DeviceSelector = wearablesViewModel.deviceSelector
  private var session: DeviceSession? = null

  private val _uiState = MutableStateFlow(INITIAL_STATE)
  val uiState: StateFlow<StreamUiState> = _uiState.asStateFlow()

  private var videoJob: Job? = null
  private var stateJob: Job? = null
  private var errorJob: Job? = null
  @SuppressLint("MissingGuardedByAnnotation") private var sessionErrorJob: Job? = null
  private var sessionStateJob: Job? = null
  private var stream: Stream? = null
  private var previousDeviceSessionState: DeviceSessionState? = null

  private var presentationQueue: PresentationQueue? = null
  private var phoneCameraManager: PhoneCameraManager? = null
  private var glassesDisplay: Display? = null
  private var glassesDisplayStateJob: Job? = null
  private var pendingGlassesSpecs: List<WidgetSpec>? = null

  var geminiViewModel: GeminiSessionViewModel? = null
  var webrtcViewModel: WebRTCSessionViewModel? = null

  fun startStream() {
    StreamingService.start(getApplication())

    videoJob?.cancel()
    stateJob?.cancel()
    errorJob?.cancel()
    sessionErrorJob?.cancel()
    sessionStateJob?.cancel()
    presentationQueue?.stop()
    presentationQueue = null
    previousDeviceSessionState = null

    _uiState.update { it.copy(streamingMode = StreamingMode.GLASSES) }

    val queue =
        PresentationQueue(
            bufferDelayMs = 100L,
            maxQueueSize = 15,
            onFrameReady = { frame ->
              viewModelScope.launch(Dispatchers.Main) {
                _uiState.update {
                  it.copy(videoFrame = frame.bitmap, videoFrameCount = it.videoFrameCount + 1)
                }
                geminiViewModel?.sendVideoFrameIfThrottled(frame.bitmap)
                webrtcViewModel?.pushVideoFrame(frame.bitmap)
              }
            },
        )
    presentationQueue = queue
    queue.start()

    if (session == null) {
      previousDeviceSessionState = null
      Wearables.createSession(deviceSelector)
          .onSuccess { createdSession ->
            session = createdSession
            sessionErrorJob = viewModelScope.launch {
              createdSession.errors.collect { error -> handleSessionError(error) }
            }
            session?.start()
          }
          .onFailure { error, _ ->
            Log.e(TAG, "Failed to create session: ${error.description}")
            handleSessionError(error)
          }
      if (session == null) return
    }
    startStreamInternal()
  }

  fun startPhoneCamera(lifecycleOwner: LifecycleOwner) {
    StreamingService.start(getApplication())

    val manager = PhoneCameraManager(getApplication())
    phoneCameraManager = manager

    manager.onFrameCaptured = { bitmap ->
      _uiState.update {
        it.copy(
            videoFrame = bitmap,
            videoFrameCount = it.videoFrameCount + 1,
            streamingMode = StreamingMode.PHONE,
            streamState = StreamState.STREAMING,
        )
      }
      geminiViewModel?.sendVideoFrameIfThrottled(bitmap)
      webrtcViewModel?.pushVideoFrame(bitmap)
    }

    _uiState.update {
      it.copy(
          streamingMode = StreamingMode.PHONE,
          streamState = StreamState.STREAMING,
      )
    }
    manager.start(lifecycleOwner)
    Log.d(TAG, "Phone camera mode started")
  }

  private fun startStreamInternal() {
    val videoQuality = wearablesViewModel.uiState.value.selectedVideoQuality
    sessionStateJob = viewModelScope.launch {
      session?.state?.collect { currentState ->
        val prevState = previousDeviceSessionState
        previousDeviceSessionState = currentState

        if (currentState == DeviceSessionState.STARTED) {
          wearablesViewModel.setDatAppUpdateRequired(false)
          if (prevState == DeviceSessionState.PAUSED && stream != null) {
            Log.d(TAG, "Session resumed from PAUSED — stream stays alive")
            return@collect
          }

          videoJob?.cancel()
          stateJob?.cancel()
          errorJob?.cancel()
          stream?.stop()
          stream = null
          session
              ?.addStream(StreamConfiguration(videoQuality = videoQuality, frameRate = 24))
              ?.onSuccess { addedStream ->
                stream = addedStream
                videoJob = viewModelScope.launch {
                  stream?.videoStream?.collect { handleVideoFrame(it) }
                }
                stateJob = viewModelScope.launch {
                  stream?.state?.collect { streamState ->
                    val prevStreamState = _uiState.value.streamState
                    _uiState.update { it.copy(streamState = streamState) }

                    val wasActive = prevStreamState !in SESSION_TERMINAL_STATES
                    val isTerminated = streamState in SESSION_TERMINAL_STATES
                    if (wasActive && isTerminated) {
                      stopStream()
                      wearablesViewModel.navigateToDeviceSelection()
                    }
                  }
                }
                errorJob = viewModelScope.launch {
                  stream?.errorStream?.collect { error ->
                    if (error == StreamError.STREAM_ERROR) return@collect
                    stopStream()
                    wearablesViewModel.navigateToDeviceSelection()
                    wearablesViewModel.setRecentError(error.description)
                  }
                }
                stream?.start()
              }
              ?.onFailure { error, _ ->
                Log.e(TAG, "Failed to add stream to session: ${error.description}")
              }
        } else if (currentState == DeviceSessionState.PAUSED) {
          Log.d(TAG, "Session paused (tap gesture) — keeping stream alive for resume")
        } else if (currentState == DeviceSessionState.STOPPED) {
          teardownGlassesDisplay()
        }
      }
    }
  }

  /// Renders widgets on the glasses Display by attaching to the active streaming
  /// DeviceSession — the same mechanism as "Hello World on Display". No-op in phone mode.
  fun sendWidgetsToGlasses(specs: List<WidgetSpec>) {
    if (specs.isEmpty()) return
    viewModelScope.launch {
      if (_uiState.value.streamingMode != StreamingMode.GLASSES) return@launch
      val activeSession = session ?: return@launch
      if (activeSession.state.value != DeviceSessionState.STARTED) return@launch

      val activeDisplay = glassesDisplay
      if (activeDisplay != null && activeDisplay.state.value == DisplayState.STARTED) {
        sendBoardToDisplay(activeDisplay, specs)
        return@launch
      }

      pendingGlassesSpecs = specs
      if (glassesDisplay != null) return@launch

      activeSession.addDisplay(DisplayConfiguration())
          .onSuccess { display ->
            glassesDisplay = display
            glassesDisplayStateJob = viewModelScope.launch {
              display.state.collect { state ->
                when (state) {
                  DisplayState.STARTED -> {
                    pendingGlassesSpecs?.let { pending ->
                      pendingGlassesSpecs = null
                      sendBoardToDisplay(display, pending)
                    }
                  }
                  DisplayState.STOPPED, DisplayState.CLOSED -> {
                    glassesDisplayStateJob?.cancel()
                    glassesDisplayStateJob = null
                    glassesDisplay = null
                  }
                  else -> Unit
                }
              }
            }
          }
          .onFailure { error, _ ->
            Log.w(TAG, "addDisplay on streaming session failed: ${error.description}")
            glassesDisplay = null
            pendingGlassesSpecs = null
          }
    }
  }

  private suspend fun sendBoardToDisplay(display: Display, specs: List<WidgetSpec>) {
    display.sendContent { board(specs) }.fold(
        onSuccess = { Log.d(TAG, "Sent ${specs.size} widget(s) to glasses display") },
        onFailure = { error, _ ->
          Log.w(TAG, "Send to glasses display failed: ${error.description}")
        },
    )
  }

  private fun teardownGlassesDisplay() {
    glassesDisplayStateJob?.cancel()
    glassesDisplayStateJob = null
    pendingGlassesSpecs = null
    val activeDisplay = glassesDisplay
    glassesDisplay = null
    activeDisplay?.stop()
  }

  fun stopStream() {
    StreamingService.stop(getApplication())

    phoneCameraManager?.stop()
    phoneCameraManager = null

    videoJob?.cancel()
    videoJob = null
    stateJob?.cancel()
    stateJob = null
    errorJob?.cancel()
    errorJob = null
    sessionErrorJob?.cancel()
    sessionErrorJob = null
    sessionStateJob?.cancel()
    sessionStateJob = null
    presentationQueue?.stop()
    presentationQueue = null
    teardownGlassesDisplay()
    _uiState.update { INITIAL_STATE }
    stream?.stop()
    stream = null
    session?.stop()
    session = null
  }

  private fun handleSessionError(error: DeviceSessionError) {
    Log.e(TAG, "Session error: ${error.description}")
    val alreadyShowingUpdateRequired =
        wearablesViewModel.uiState.value.isFirmwareUpdateRequired ||
            wearablesViewModel.uiState.value.isDatAppUpdateRequired

    if (
        error == DeviceSessionError.SESSION_ENDED_BY_DEVICE &&
            shouldTreatSessionEndedAsDatAppUpdateRequired()
    ) {
      wearablesViewModel.setDatAppUpdateRequired(true)
      wearablesViewModel.setRecentError(
          getApplication<Application>().getString(R.string.update_required_dat_app_message)
      )
      stopStream()
      wearablesViewModel.navigateToDeviceSelection()
      return
    }

    if (alreadyShowingUpdateRequired && error == DeviceSessionError.SESSION_ENDED_BY_DEVICE) {
      stopStream()
      wearablesViewModel.navigateToDeviceSelection()
      return
    }

    if (error == DeviceSessionError.DAT_APP_ON_THE_GLASSES_UPDATE_REQUIRED) {
      wearablesViewModel.setDatAppUpdateRequired(true)
    }
    wearablesViewModel.setRecentError(error.description)
    stopStream()
    wearablesViewModel.navigateToDeviceSelection()
  }

  private fun shouldTreatSessionEndedAsDatAppUpdateRequired(): Boolean {
    val sessionNeverStarted =
        previousDeviceSessionState != DeviceSessionState.STARTED &&
            previousDeviceSessionState != DeviceSessionState.PAUSED
    return sessionNeverStarted
  }

  fun capturePhoto() {
    if (uiState.value.isCapturing) return

    if (uiState.value.streamingMode == StreamingMode.PHONE) {
      uiState.value.videoFrame?.let { frame ->
        _uiState.update { it.copy(capturedPhoto = frame, isShareDialogVisible = true) }
      }
      return
    }

    if (uiState.value.streamState == StreamState.STREAMING) {
      _uiState.update { it.copy(isCapturing = true) }

      viewModelScope.launch {
        stream
            ?.capturePhoto()
            ?.onSuccess { photoData ->
              handlePhotoData(photoData)
              _uiState.update { it.copy(isCapturing = false) }
            }
            ?.onFailure { error, _ ->
              Log.e(TAG, "Photo capture failed: ${error.description}")
              _uiState.update { it.copy(isCapturing = false) }
            }
      }
    }
  }

  fun showShareDialog() {
    _uiState.update { it.copy(isShareDialogVisible = true) }
  }

  fun hideShareDialog() {
    _uiState.update { it.copy(isShareDialogVisible = false) }
  }

  fun sharePhoto(bitmap: Bitmap) {
    val context = getApplication<Application>()
    val imagesFolder = File(context.cacheDir, "images")
    try {
      imagesFolder.mkdirs()
      val file = File(imagesFolder, "shared_image.png")
      FileOutputStream(file).use { stream ->
        bitmap.compress(Bitmap.CompressFormat.PNG, 90, stream)
      }

      val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
      val intent = Intent(Intent.ACTION_SEND)
      intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
      intent.putExtra(Intent.EXTRA_STREAM, uri)
      intent.type = "image/png"
      intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)

      val chooser = Intent.createChooser(intent, "Share Image")
      chooser.flags = Intent.FLAG_ACTIVITY_NEW_TASK
      context.startActivity(chooser)
    } catch (e: IOException) {
      Log.e(TAG, "Failed to share photo", e)
    }
  }

  private fun handleVideoFrame(videoFrame: VideoFrame) {
    val bitmap =
        YuvToBitmapConverter.convert(
            videoFrame.buffer,
            videoFrame.width,
            videoFrame.height,
        )
    if (bitmap != null) {
      presentationQueue?.enqueue(bitmap, videoFrame.presentationTimeUs)
    } else {
      Log.e(TAG, "Failed to convert YUV to bitmap")
    }
  }

  private fun handlePhotoData(photo: PhotoData) {
    val capturedPhoto =
        when (photo) {
          is PhotoData.Bitmap -> photo.bitmap
          is PhotoData.HEIC -> {
            val byteArray = ByteArray(photo.data.remaining())
            photo.data.get(byteArray)
            decodeHeic(byteArray, getTransform(getExifInfo(byteArray)))
          }
        }
    _uiState.update { it.copy(capturedPhoto = capturedPhoto, isShareDialogVisible = true) }
  }

  private fun decodeHeic(heicBytes: ByteArray, transform: Matrix): Bitmap {
    val bitmap = BitmapFactory.decodeByteArray(heicBytes, 0, heicBytes.size)
    return applyTransform(bitmap, transform)
  }

  private fun getExifInfo(heicBytes: ByteArray): ExifInterface? {
    return try {
      ByteArrayInputStream(heicBytes).use { ExifInterface(it) }
    } catch (e: IOException) {
      Log.w(TAG, "Failed to read EXIF from HEIC", e)
      null
    }
  }

  private fun getTransform(exifInfo: ExifInterface?): Matrix {
    val matrix = Matrix()
    if (exifInfo == null) return matrix

    when (
        exifInfo.getAttributeInt(
            ExifInterface.TAG_ORIENTATION,
            ExifInterface.ORIENTATION_NORMAL,
        )
    ) {
      ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
      ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
      ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
      ExifInterface.ORIENTATION_TRANSPOSE -> {
        matrix.postRotate(90f)
        matrix.postScale(-1f, 1f)
      }
      ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
      ExifInterface.ORIENTATION_TRANSVERSE -> {
        matrix.postRotate(270f)
        matrix.postScale(-1f, 1f)
      }
      ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
      ExifInterface.ORIENTATION_NORMAL,
      ExifInterface.ORIENTATION_UNDEFINED -> Unit
    }
    return matrix
  }

  private fun applyTransform(bitmap: Bitmap, matrix: Matrix): Bitmap {
    if (matrix.isIdentity) return bitmap
    return try {
      val transformed = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
      if (transformed != bitmap) bitmap.recycle()
      transformed
    } catch (e: OutOfMemoryError) {
      Log.e(TAG, "Failed to apply transformation due to memory", e)
      bitmap
    }
  }

  override fun onCleared() {
    super.onCleared()
    stopStream()
  }

  class Factory(
      private val application: Application,
      private val wearablesViewModel: WearablesViewModel,
  ) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
      if (modelClass.isAssignableFrom(StreamViewModel::class.java)) {
        @Suppress("UNCHECKED_CAST", "KotlinGenericsCast")
        return StreamViewModel(
            application = application,
            wearablesViewModel = wearablesViewModel,
        ) as T
      }
      throw IllegalArgumentException("Unknown ViewModel class")
    }
  }
}
