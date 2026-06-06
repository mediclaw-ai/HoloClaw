/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 * All rights reserved.
 *
 * This source code is licensed under the license found in the
 * LICENSE file in the root directory of this source tree.
 */

//
// DisplayViewModel.swift
//
// Drives the MWDATDisplay capability (new in SDK 0.7.0). It selects a
// display-capable device, starts a DeviceSession, attaches a Display, and sends
// view content to Meta Ray-Ban Display glasses. Display requires the Device
// Access Toolkit App Model — the app opts in via MWDAT.DAMEnabled in Info.plist.
//

import Combine
import MWDATCore
import MWDATDisplay

@MainActor
final class DisplayViewModel: ObservableObject {
  @Published var isConnecting: Bool = false
  @Published var isConnected: Bool = false
  @Published var statusMessage: String = ""
  @Published var showError: Bool = false
  @Published var errorMessage: String = ""

  private let wearables: WearablesInterface
  private let deviceSelector: AutoDeviceSelector
  private var deviceSession: DeviceSession?
  private var display: Display?
  private var displayStateToken: AnyListenerToken?
  private var sessionErrorTask: Task<Void, Never>?
  // Content queued while the display session is still coming up; sent on `.started`.
  private var pendingView: FlexBox?

  init(wearables: WearablesInterface) {
    self.wearables = wearables
    // Only auto-select devices that can render a display (e.g. Ray-Ban Display).
    self.deviceSelector = AutoDeviceSelector(wearables: wearables, filter: { $0.supportsDisplay() })
  }

  deinit {
    displayStateToken = nil
    sessionErrorTask?.cancel()
    deviceSession?.stop()
  }

  // MARK: - Public API

  /// Sends a "Hello World" card to the glasses display, attaching a display
  /// session first if one isn't already connected.
  func sendHelloWorld() async {
    await send(HelloWorldDisplay.helloWorld())
  }

  /// Stops the Display capability and the underlying device session.
  func disconnect() async {
    displayStateToken = nil
    sessionErrorTask?.cancel()
    sessionErrorTask = nil
    await display?.stop()
    display = nil
    deviceSession?.stop()
    deviceSession = nil
    pendingView = nil
    isConnected = false
    isConnecting = false
    statusMessage = ""
  }

  func dismissError() {
    showError = false
    errorMessage = ""
  }

  // MARK: - Private

  private func send(_ view: FlexBox) async {
    if let display, isConnected {
      await doSend(view, on: display)
      return
    }
    // Not connected yet: queue the content and bring the display session up.
    pendingView = view
    if display == nil {
      await attachToDisplay()
    }
  }

  private func doSend(_ view: FlexBox, on display: Display) async {
    do {
      // Each send replaces the previous root view shown on the glasses.
      try await display.send(view)
      statusMessage = "Sent \u{201C}Hello World\u{201D} to the glasses display"
    } catch {
      handleError((error as? DisplayError)?.description ?? error.localizedDescription)
    }
  }

  private func attachToDisplay() async {
    guard display == nil else { return }
    isConnecting = true
    statusMessage = "Connecting to display\u{2026}"

    do {
      let session = try wearables.createSession(deviceSelector: deviceSelector)
      deviceSession = session

      let stateStream = session.stateStream()
      let errorStream = session.errorStream()

      // Surface asynchronous session failures.
      sessionErrorTask = Task { [weak self] in
        for await error in errorStream {
          guard let self else { return }
          self.handleError(error.localizedDescription)
        }
      }

      try session.start()

      // The session may reach `.started` before the for-await begins iterating.
      if session.state == .started {
        await setupDisplay(on: session)
        return
      }
      for await state in stateStream {
        if state == .started {
          await setupDisplay(on: session)
          return
        }
        if state == .stopped {
          handleError("The device session stopped before the display could start.")
          return
        }
      }
    } catch DeviceSessionError.datAppOnTheGlassesUpdateRequired {
      handleError(DeviceSessionError.datAppOnTheGlassesUpdateRequired.localizedDescription)
    } catch DeviceSessionError.noEligibleDevice {
      handleError("No Meta Ray-Ban Display glasses found. Connect display-capable glasses and try again.")
    } catch {
      handleError("Failed to start display session: \(error.localizedDescription)")
    }
  }

  private func setupDisplay(on session: DeviceSession) async {
    guard display == nil else { return }
    do {
      let capability = try session.addDisplay()
      display = capability
      displayStateToken = capability.statePublisher.listen { [weak self] state in
        Task { @MainActor [weak self] in
          await self?.handleDisplayState(state, on: capability)
        }
      }
      await capability.start()
    } catch {
      handleError("Failed to attach display: \(error.localizedDescription)")
    }
  }

  private func handleDisplayState(_ state: DisplayState, on display: Display) async {
    switch state {
    case .starting:
      break
    case .started:
      isConnecting = false
      isConnected = true
      statusMessage = "Display connected"
      // Flush any content the user requested before the display was ready.
      if let view = pendingView {
        pendingView = nil
        await doSend(view, on: display)
      }
    case .stopping:
      isConnected = false
    case .stopped:
      isConnected = false
      displayStateToken = nil
      self.display = nil
      sessionErrorTask?.cancel()
      sessionErrorTask = nil
      deviceSession?.stop()
      deviceSession = nil
      isConnecting = false
    }
  }

  private func handleError(_ message: String) {
    errorMessage = message
    showError = true
    statusMessage = ""
    isConnecting = false
  }
}
