/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 * All rights reserved.
 *
 * This source code is licensed under the license found in the
 * LICENSE file in the root directory of this source tree.
 */

//
// StreamSessionViewModel.swift
//
// Core view model demonstrating video streaming from Meta wearable devices using the DAT SDK.
// This class showcases the key streaming patterns: device selection, session management,
// video frame handling, photo capture, and error handling.
//

import CoreImage
import CoreMedia
import CoreVideo
import MWDATCamera
import MWDATCore
import MWDATDisplay
import SwiftUI
import VideoToolbox

enum StreamingStatus {
  case streaming
  case waiting
  case stopped
}

enum StreamingMode {
  case glasses
  case iPhone
}

@MainActor
class StreamSessionViewModel: ObservableObject {
  @Published var currentVideoFrame: UIImage?
  @Published var hasReceivedFirstFrame: Bool = false
  @Published var streamingStatus: StreamingStatus = .stopped
  @Published var showError: Bool = false
  @Published var errorMessage: String = ""
  @Published var hasActiveDevice: Bool = false
  @Published var streamingMode: StreamingMode = .glasses
  @Published var selectedResolution: StreamingResolution = .low

  var isStreaming: Bool {
    streamingStatus != .stopped
  }

  var resolutionLabel: String {
    switch selectedResolution {
    case .low: return "360x640"
    case .medium: return "504x896"
    case .high: return "720x1280"
    @unknown default: return "Unknown"
    }
  }

  // Photo capture properties
  @Published var capturedPhoto: UIImage?
  @Published var showPhotoPreview: Bool = false

  // Gemini Live integration
  var geminiSessionVM: GeminiSessionViewModel?

  // WebRTC Live streaming integration
  var webrtcSessionVM: WebRTCSessionViewModel?

  // The core DAT SDK objects. In SDK 0.7.0 streaming is session-scoped: a DeviceSession
  // is created for the selected device and the camera Stream is added to it as a capability.
  // Both are created lazily when the user starts streaming.
  private var deviceSession: DeviceSession?
  private var stream: MWDATCamera.Stream?
  // Glasses Display capability, attached to the SAME DeviceSession during glasses
  // streaming so Gemini widgets render on the glasses (the SDK path), the same way
  // the "Hello World on Display" flow does.
  private var glassesDisplay: MWDATDisplay.Display?
  private var glassesDisplayToken: AnyListenerToken?
  private var pendingGlassesBoard: MWDATDisplay.FlexBox?
  // Listener tokens are used to manage DAT SDK event subscriptions
  private var stateListenerToken: AnyListenerToken?
  private var videoFrameListenerToken: AnyListenerToken?
  private var errorListenerToken: AnyListenerToken?
  private var photoDataListenerToken: AnyListenerToken?
  private let wearables: WearablesInterface
  private let deviceSelector: AutoDeviceSelector
  private var deviceMonitorTask: Task<Void, Never>?
  private var sessionStateObserverTask: Task<Void, Never>?
  private var iPhoneCameraManager: IPhoneCameraManager?

  // CPU-based CIContext for rendering decoded pixel buffers in background
  private let cpuCIContext = CIContext(options: [.useSoftwareRenderer: true])
  // VideoDecoder for decompressing HEVC/H.264 frames in background
  private let videoDecoder = VideoDecoder()
  private var backgroundFrameCount = 0
  private var bgDiagLogged = false

  init(wearables: WearablesInterface) {
    self.wearables = wearables
    // Let the SDK auto-select from available devices
    let selector = AutoDeviceSelector(wearables: wearables)
    self.deviceSelector = selector

    // Monitor device availability
    deviceMonitorTask = Task { @MainActor [weak self] in
      for await device in selector.activeDeviceStream() {
        guard let self else { return }
        self.hasActiveDevice = device != nil
      }
    }

    setupVideoDecoder()
  }

  deinit {
    deviceMonitorTask?.cancel()
    sessionStateObserverTask?.cancel()
    deviceSession?.stop()
  }

  private func setupVideoDecoder() {
    videoDecoder.setFrameCallback { [weak self] decodedFrame in
      Task { @MainActor [weak self] in
        guard let self else { return }
        let pixelBuffer = decodedFrame.pixelBuffer
        let width = CVPixelBufferGetWidth(pixelBuffer)
        let height = CVPixelBufferGetHeight(pixelBuffer)
        let ciImage = CIImage(cvPixelBuffer: pixelBuffer)
        let rect = CGRect(x: 0, y: 0, width: width, height: height)
        if let cgImage = self.cpuCIContext.createCGImage(ciImage, from: rect) {
          let image = UIImage(cgImage: cgImage)
          self.geminiSessionVM?.sendVideoFrameIfThrottled(image: image)
          self.webrtcSessionVM?.pushVideoFrame(image)
          if self.backgroundFrameCount <= 5 || self.backgroundFrameCount % 120 == 0 {
            NSLog("[Stream] Background frame #%d decoded and forwarded (%dx%d)",
                  self.backgroundFrameCount, width, height)
          }
        }
      }
    }
  }

  /// Update the resolution used for the next streaming session.
  /// Only call when not actively streaming; the new resolution is applied
  /// when the camera Stream is (re)created in `startSession()`.
  func updateResolution(_ resolution: StreamingResolution) {
    guard !isStreaming else { return }
    selectedResolution = resolution
    NSLog("[Stream] Resolution changed to %@", resolutionLabel)
  }

  private func setupListeners(for stream: MWDATCamera.Stream) {
    // Subscribe to session state changes using the DAT SDK listener pattern
    stateListenerToken = stream.statePublisher.listen { [weak self] state in
      Task { @MainActor [weak self] in
        self?.updateStatusFromState(state)
      }
    }

    // Subscribe to video frames from the device camera
    // This callback fires whether the app is in the foreground or background,
    // enabling continuous streaming even when the screen is locked.
    videoFrameListenerToken = stream.videoFramePublisher.listen { [weak self] videoFrame in
      Task { @MainActor [weak self] in
        guard let self else { return }

        let isInBackground = UIApplication.shared.applicationState == .background

        if !isInBackground {
          self.backgroundFrameCount = 0
          self.bgDiagLogged = false
          if let image = videoFrame.makeUIImage() {
            self.currentVideoFrame = image
            if !self.hasReceivedFirstFrame {
              self.hasReceivedFirstFrame = true
            }
            self.geminiSessionVM?.sendVideoFrameIfThrottled(image: image)
            self.webrtcSessionVM?.pushVideoFrame(image)
          }
        } else {
          // In background: makeUIImage() uses VideoToolbox GPU rendering which iOS suspends.
          // Instead, use our VideoDecoder (VTDecompressionSession) to decode compressed
          // frames into pixel buffers, then convert via CPU CIContext.
          self.backgroundFrameCount += 1

          let sampleBuffer = videoFrame.sampleBuffer
          let hasCompressedData = CMSampleBufferGetDataBuffer(sampleBuffer) != nil

          if hasCompressedData {
            // Compressed frame (HEVC/H.264) - decode via VTDecompressionSession
            do {
              try self.videoDecoder.decode(sampleBuffer)
            } catch {
              if self.backgroundFrameCount <= 5 || self.backgroundFrameCount % 120 == 0 {
                NSLog("[Stream] Background frame #%d decode error: %@",
                      self.backgroundFrameCount, String(describing: error))
              }
            }
          } else if let pixelBuffer = CMSampleBufferGetImageBuffer(sampleBuffer) {
            // Raw pixel buffer - convert directly via CPU CIContext
            let width = CVPixelBufferGetWidth(pixelBuffer)
            let height = CVPixelBufferGetHeight(pixelBuffer)
            let ciImage = CIImage(cvPixelBuffer: pixelBuffer)
            let rect = CGRect(x: 0, y: 0, width: width, height: height)
            if let cgImage = self.cpuCIContext.createCGImage(ciImage, from: rect) {
              let image = UIImage(cgImage: cgImage)
              self.geminiSessionVM?.sendVideoFrameIfThrottled(image: image)
              self.webrtcSessionVM?.pushVideoFrame(image)
            }
            self.videoDecoder.invalidateSession()
          }
        }
      }
    }

    // Subscribe to streaming errors
    errorListenerToken = stream.errorPublisher.listen { [weak self] error in
      Task { @MainActor [weak self] in
        guard let self else { return }
        // Suppress device-not-found errors when user hasn't started streaming yet
        if self.streamingStatus == .stopped {
          if case .deviceNotConnected = error { return }
          if case .deviceNotFound = error { return }
        }
        let newErrorMessage = self.formatStreamingError(error)
        if newErrorMessage != self.errorMessage {
          self.showError(newErrorMessage)
        }
      }
    }

    updateStatusFromState(stream.state)

    // Subscribe to photo capture events
    photoDataListenerToken = stream.photoDataPublisher.listen { [weak self] photoData in
      Task { @MainActor [weak self] in
        guard let self else { return }
        if let uiImage = UIImage(data: photoData.data) {
          self.capturedPhoto = uiImage
          self.showPhotoPreview = true
        }
      }
    }
  }

  private func clearListeners() {
    stateListenerToken = nil
    videoFrameListenerToken = nil
    errorListenerToken = nil
    photoDataListenerToken = nil
  }

  func handleStartStreaming() async {
    let permission = Permission.camera
    do {
      let status = try await wearables.checkPermissionStatus(permission)
      if status == .granted {
        await startSession()
        return
      }
      let requestStatus = try await wearables.requestPermission(permission)
      if requestStatus == .granted {
        await startSession()
        return
      }
      showError("Permission denied")
    } catch {
      showError("Permission error: \(error.description)")
    }
  }

  func startSession() async {
    guard stream == nil else { return }

    let session: DeviceSession
    do {
      session = try await getOrCreateStartedSession()
    } catch DeviceSessionError.datAppOnTheGlassesUpdateRequired {
      showError(DeviceSessionError.datAppOnTheGlassesUpdateRequired.localizedDescription)
      return
    } catch {
      showError("Failed to start session: \(error.localizedDescription)")
      return
    }

    guard session.state == .started else {
      showError("Device session is not ready. Please try again.")
      return
    }

    let config = StreamConfiguration(
      videoCodec: MWDATCamera.VideoCodec.raw,
      resolution: selectedResolution,
      frameRate: 24)

    guard let newStream = try? session.addStream(config: config) else {
      showError("Failed to start camera stream. Please try again.")
      return
    }
    stream = newStream
    streamingStatus = .waiting
    setupListeners(for: newStream)
    await newStream.start()
  }

  /// Returns a DeviceSession in the `.started` state, reusing the existing one when possible.
  /// Streaming is scoped to a started DeviceSession in SDK 0.7.0.
  private func getOrCreateStartedSession() async throws -> DeviceSession {
    if let session = deviceSession, session.state == .started {
      return session
    }

    if deviceSession?.state == .stopped {
      deviceSession = nil
    }

    // Wait for an in-progress session to finish starting.
    if let session = deviceSession {
      // The session may have already transitioned to .started before the
      // for-await loop begins iterating (stateStream doesn't buffer past events).
      if session.state == .started {
        startSessionStateObserver(for: session)
        return session
      }
      try await waitForSessionStart(
        stateStream: session.stateStream(),
        errorStream: session.errorStream())
      startSessionStateObserver(for: session)
      return session
    }

    // Create a new session for the auto-selected device.
    do {
      let session = try wearables.createSession(deviceSelector: deviceSelector)
      deviceSession = session

      let stateStream = session.stateStream()
      let errorStream = session.errorStream()
      try session.start()

      // The state change may be delivered on another thread before the
      // for-await loop begins iterating, and the stream does not buffer.
      if session.state == .started {
        startSessionStateObserver(for: session)
        return session
      }
      try await waitForSessionStart(stateStream: stateStream, errorStream: errorStream)
      startSessionStateObserver(for: session)
      return session
    } catch {
      deviceSession = nil
      throw error
    }
  }

  private func waitForSessionStart(
    stateStream: AsyncStream<DeviceSessionState>,
    errorStream: AsyncStream<DeviceSessionError>
  ) async throws {
    try await withThrowingTaskGroup(of: Void.self) { group in
      group.addTask {
        for await state in stateStream {
          if state == .started { return }
          if state == .stopped {
            throw DeviceSessionError.unexpectedError(description: "The session failed to start")
          }
        }
        guard !Task.isCancelled else { return }
        throw DeviceSessionError.unexpectedError(description: "The session failed to start")
      }

      group.addTask {
        for await sessionError in errorStream {
          throw sessionError
        }
        guard !Task.isCancelled else { return }
        throw DeviceSessionError.unexpectedError(description: "The session failed to start")
      }

      guard try await group.next() != nil else {
        throw DeviceSessionError.unexpectedError(description: "The session failed to start")
      }
      group.cancelAll()
    }
  }

  /// Drops the cached device session once it reaches `.stopped` so a fresh one is
  /// created on the next start.
  private func startSessionStateObserver(for session: DeviceSession) {
    sessionStateObserverTask?.cancel()
    sessionStateObserverTask = Task { @MainActor [weak self] in
      for await state in session.stateStream() {
        guard let self else { return }
        if state == .stopped {
          self.deviceSession = nil
          self.glassesDisplay = nil
          self.glassesDisplayToken = nil
          self.pendingGlassesBoard = nil
          self.sessionStateObserverTask = nil
          return
        }
      }
    }
  }

  private func showError(_ message: String) {
    errorMessage = message
    showError = true
  }

  /// Renders the given widgets on the glasses Display (SDK path) by attaching a
  /// Display capability to the active streaming DeviceSession — the same mechanism
  /// as "Hello World on Display". Only applies when streaming from glasses; in
  /// iPhone mode there is no DeviceSession, so this is a no-op.
  func sendWidgetsToGlasses(_ specs: [WidgetSpec]) async {
    guard streamingMode == .glasses,
          let session = deviceSession, session.state == .started else { return }

    let board = DisplayWidgets.board(for: specs)

    // Display already running: just send (each send replaces the current content).
    if let display = glassesDisplay, display.state == .started {
      do {
        try await display.send(board)
      } catch {
        NSLog("[Display] send to glasses failed: %@", error.localizedDescription)
      }
      return
    }

    // Otherwise queue this board and attach a Display capability to the session once.
    pendingGlassesBoard = board
    guard glassesDisplay == nil else { return }
    do {
      let display = try session.addDisplay()
      glassesDisplay = display
      glassesDisplayToken = display.statePublisher.listen { [weak self] state in
        Task { @MainActor [weak self] in
          guard let self else { return }
          switch state {
          case .started:
            if let pending = self.pendingGlassesBoard {
              self.pendingGlassesBoard = nil
              try? await self.glassesDisplay?.send(pending)
            }
          case .stopped:
            self.glassesDisplayToken = nil
            self.glassesDisplay = nil
          default:
            break
          }
        }
      }
      await display.start()
    } catch {
      // e.g. the connected glasses don't support a display — widgets still show on
      // the phone overlay.
      NSLog("[Display] addDisplay on streaming session failed: %@", error.localizedDescription)
      glassesDisplay = nil
      pendingGlassesBoard = nil
    }
  }

  func stopSession() async {
    if streamingMode == .iPhone {
      stopIPhoneSession()
      return
    }
    let activeStream = stream
    stream = nil
    clearListeners()
    streamingStatus = .stopped
    currentVideoFrame = nil
    hasReceivedFirstFrame = false
    await activeStream?.stop()
    // Tear down the glasses Display capability alongside the camera stream.
    glassesDisplayToken = nil
    pendingGlassesBoard = nil
    let activeDisplay = glassesDisplay
    glassesDisplay = nil
    await activeDisplay?.stop()
  }

  // MARK: - iPhone Camera Mode

  func handleStartIPhone() async {
    let granted = await IPhoneCameraManager.requestPermission()
    if granted {
      startIPhoneSession()
    } else {
      showError("Camera permission denied. Please grant access in Settings.")
    }
  }

  private func startIPhoneSession() {
    streamingMode = .iPhone
    let camera = IPhoneCameraManager()
    camera.onFrameCaptured = { [weak self] image in
      Task { @MainActor [weak self] in
        guard let self else { return }
        self.currentVideoFrame = image
        if !self.hasReceivedFirstFrame {
          self.hasReceivedFirstFrame = true
        }
        self.geminiSessionVM?.sendVideoFrameIfThrottled(image: image)
        self.webrtcSessionVM?.pushVideoFrame(image)
      }
    }
    camera.start()
    iPhoneCameraManager = camera
    streamingStatus = .streaming
    NSLog("[Stream] iPhone camera mode started")
  }

  private func stopIPhoneSession() {
    iPhoneCameraManager?.stop()
    iPhoneCameraManager = nil
    currentVideoFrame = nil
    hasReceivedFirstFrame = false
    streamingStatus = .stopped
    streamingMode = .glasses
    NSLog("[Stream] iPhone camera mode stopped")
  }

  func dismissError() {
    showError = false
    errorMessage = ""
  }

  func capturePhoto() {
    stream?.capturePhoto(format: .jpeg)
  }

  func dismissPhotoPreview() {
    showPhotoPreview = false
    capturedPhoto = nil
  }

  private func updateStatusFromState(_ state: StreamState) {
    switch state {
    case .stopped:
      currentVideoFrame = nil
      streamingStatus = .stopped
    case .waitingForDevice, .starting, .stopping, .paused:
      streamingStatus = .waiting
    case .streaming:
      streamingStatus = .streaming
    }
  }

  private func formatStreamingError(_ error: StreamError) -> String {
    switch error {
    case .internalError:
      return "An internal error occurred. Please try again."
    case .deviceNotFound:
      return "Device not found. Please ensure your device is connected."
    case .deviceNotConnected:
      return "Device not connected. Please check your connection and try again."
    case .timeout:
      return "The operation timed out. Please try again."
    case .videoStreamingError:
      return "Video streaming failed. Please try again."
    case .permissionDenied:
      return "Camera permission denied. Please grant permission in Settings."
    case .hingesClosed:
      return "The hinges on the glasses were closed. Please open the hinges and try again."
    case .thermalCritical, .thermalEmergency:
      return "The glasses are too hot to keep streaming. Please let them cool down and try again."
    case .peakPowerShutdown:
      return "The glasses shut down to protect the battery. Please try again."
    case .batteryCritical:
      return "The glasses' battery is too low to stream. Please charge them and try again."
    @unknown default:
      return "An unknown streaming error occurred."
    }
  }
}
