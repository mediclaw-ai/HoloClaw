import Foundation
import SwiftUI

@MainActor
class GeminiSessionViewModel: ObservableObject {
  @Published var isGeminiActive: Bool = false
  @Published var connectionState: GeminiConnectionState = .disconnected
  @Published var isModelSpeaking: Bool = false
  @Published var errorMessage: String?
  @Published var userTranscript: String = ""
  @Published var aiTranscript: String = ""
  @Published var toolCallStatus: ToolCallStatus = .idle
  @Published var openClawConnectionState: OpenClawConnectionState = .notConfigured
  // Widgets to render in the field of view, set from Gemini `render_widgets` calls.
  @Published var widgets: [WidgetSpec] = []
  // Notifies the host when Gemini renders widgets, so it can also push them to the
  // glasses Display (SDK) during streaming.
  var onWidgetsRendered: (([WidgetSpec]) -> Void)?
  private let geminiService = GeminiLiveService()
  private let openClawBridge = OpenClawBridge()
  private var toolCallRouter: ToolCallRouter?
  private let audioManager = AudioManager()
  private let eventClient = OpenClawEventClient()
  private var lastVideoFrameTime: Date = .distantPast
  private var stateObservation: Task<Void, Never>?

  var streamingMode: StreamingMode = .glasses

  func startSession() async {
    guard !isGeminiActive else { return }

    guard GeminiConfig.isConfigured else {
      errorMessage = "Gemini API key not configured. Open GeminiConfig.swift and replace YOUR_GEMINI_API_KEY with your key from https://aistudio.google.com/apikey"
      return
    }

    isGeminiActive = true

    // Wire audio callbacks
    audioManager.onAudioCaptured = { [weak self] data in
      guard let self else { return }
      Task { @MainActor in
        // Mute mic while model speaks when speaker is on the phone
        // (loudspeaker + co-located mic overwhelms iOS echo cancellation)
        let speakerOnPhone = self.streamingMode == .iPhone || SettingsManager.shared.speakerOutputEnabled
        if speakerOnPhone && self.geminiService.isModelSpeaking { return }
        self.geminiService.sendAudio(data: data)
      }
    }

    geminiService.onAudioReceived = { [weak self] data in
      self?.audioManager.playAudio(data: data)
    }

    geminiService.onInterrupted = { [weak self] in
      self?.audioManager.stopPlayback()
    }

    geminiService.onTurnComplete = { [weak self] in
      guard let self else { return }
      Task { @MainActor in
        // Clear user transcript when AI finishes responding
        self.userTranscript = ""
      }
    }

    geminiService.onInputTranscription = { [weak self] text in
      guard let self else { return }
      Task { @MainActor in
        self.userTranscript += text
        self.aiTranscript = ""
      }
    }

    geminiService.onOutputTranscription = { [weak self] text in
      guard let self else { return }
      Task { @MainActor in
        self.aiTranscript += text
      }
    }

    // Handle unexpected disconnection
    geminiService.onDisconnected = { [weak self] reason in
      guard let self else { return }
      Task { @MainActor in
        guard self.isGeminiActive else { return }
        self.stopSession()
        self.errorMessage = "Connection lost: \(reason ?? "Unknown error")"
      }
    }

    // Check OpenClaw connectivity and start fresh session
    await openClawBridge.checkConnection()
    openClawBridge.resetSession()

    // Wire tool call handling
    toolCallRouter = ToolCallRouter(bridge: openClawBridge)

    geminiService.onToolCall = { [weak self] toolCall in
      guard let self else { return }
      Task { @MainActor in
        for call in toolCall.functionCalls {
          // `execute` decides the action: render widgets in the UI, or delegate a
          // real-world task to OpenClaw.
          let action = (call.args["action"] as? String)?.lowercased()
          let hasWidgets = ((call.args["widgets"] as? [[String: Any]])?.isEmpty == false)
          if action == "render" || hasWidgets {
            self.handleRenderWidgets(call)
          } else {
            let task = call.args["task"] as? String
            self.toolCallRouter?.handleToolCall(call) { [weak self] response in
              guard let self else { return }
              self.geminiService.sendToolResponse(response)
              self.maybeRenderResultLink(task: task, response: response)
            }
          }
        }
      }
    }

    geminiService.onToolCallCancellation = { [weak self] cancellation in
      guard let self else { return }
      Task { @MainActor in
        self.toolCallRouter?.cancelToolCalls(ids: cancellation.ids)
      }
    }

    // Observe service state
    stateObservation = Task { [weak self] in
      guard let self else { return }
      while !Task.isCancelled {
        try? await Task.sleep(nanoseconds: 100_000_000) // 100ms
        guard !Task.isCancelled else { break }
        self.connectionState = self.geminiService.connectionState
        self.isModelSpeaking = self.geminiService.isModelSpeaking
        self.toolCallStatus = self.openClawBridge.lastToolCallStatus
        self.openClawConnectionState = self.openClawBridge.connectionState
      }
    }

    // Setup audio
    do {
      try audioManager.setupAudioSession(useIPhoneMode: streamingMode == .iPhone)
    } catch {
      errorMessage = "Audio setup failed: \(error.localizedDescription)"
      isGeminiActive = false
      return
    }

    // Connect to Gemini and wait for setupComplete
    let setupOk = await geminiService.connect()

    if !setupOk {
      let msg: String
      if case .error(let err) = geminiService.connectionState {
        msg = err
      } else {
        msg = "Failed to connect to Gemini"
      }
      errorMessage = msg
      geminiService.disconnect()
      stateObservation?.cancel()
      stateObservation = nil
      isGeminiActive = false
      connectionState = .disconnected
      return
    }

    // Start mic capture
    do {
      try audioManager.startCapture()
    } catch {
      errorMessage = "Mic capture failed: \(error.localizedDescription)"
      geminiService.disconnect()
      stateObservation?.cancel()
      stateObservation = nil
      isGeminiActive = false
      connectionState = .disconnected
      return
    }

    // Connect to OpenClaw event stream for proactive notifications
    if SettingsManager.shared.proactiveNotificationsEnabled {
      eventClient.onNotification = { [weak self] text in
        guard let self else { return }
        Task { @MainActor in
          guard self.isGeminiActive, self.connectionState == .ready else { return }
          self.geminiService.sendTextMessage(text)
        }
      }
      eventClient.connect()
    }
  }

  func stopSession() {
    eventClient.disconnect()
    toolCallRouter?.cancelAll()
    toolCallRouter = nil
    audioManager.stopCapture()
    geminiService.disconnect()
    stateObservation?.cancel()
    stateObservation = nil
    isGeminiActive = false
    connectionState = .disconnected
    isModelSpeaking = false
    userTranscript = ""
    aiTranscript = ""
    toolCallStatus = .idle
    widgets = []
  }

  /// Handles the Gemini `render_widgets` tool call: decodes the widgets, shows them
  /// in the field of view, and acknowledges the call.
  private func handleRenderWidgets(_ call: GeminiFunctionCall) {
    let specs = WidgetSpec.list(from: call.args)
    widgets = specs
    onWidgetsRendered?(specs)
    NSLog("[Widgets] render_widgets -> %d widget(s)", specs.count)
    let response: [String: Any] = [
      "toolResponse": [
        "functionResponses": [
          [
            "id": call.id,
            "name": call.name,
            "response": ["result": "Displayed \(specs.count) widget(s) in the field of view."],
          ]
        ]
      ]
    ]
    geminiService.sendToolResponse(response)
  }

  /// If a delegated music/vibe task returns a public URL (e.g. an Eleven Labs
  /// track), show that URL as a text widget — on the phone and the glasses.
  private func maybeRenderResultLink(task: String?, response: [String: Any]) {
    guard let result = Self.resultString(from: response),
          let url = Self.firstURL(in: result) else { return }
    let taskLower = (task ?? "").lowercased()
    let urlLower = url.lowercased()

    let isImage = taskLower.contains("image") || taskLower.contains("picture")
      || taskLower.contains("photo")
      || ["png", "jpg", "jpeg", "webp", "gif", "heic"].contains { urlLower.contains(".\($0)") }
    let isMusic = taskLower.contains("eleven") || taskLower.contains("music")
      || taskLower.contains("vibe")
      || ["mp3", "wav", "m4a", "aac", "ogg", "flac"].contains { urlLower.contains(".\($0)") }

    let widget: WidgetSpec
    if isImage {
      widget = WidgetSpec(kind: .image(url: url, caption: "Generated image"))
    } else if isMusic {
      widget = WidgetSpec(kind: .text(title: "Your vibe track 🎵", body: url))
    } else {
      return
    }
    widgets = [widget]
    onWidgetsRendered?([widget])
    NSLog("[Widgets] auto-rendered %@ link: %@", isImage ? "image" : "music", url)
  }

  private static func resultString(from response: [String: Any]) -> String? {
    guard let toolResponse = response["toolResponse"] as? [String: Any],
          let functionResponses = toolResponse["functionResponses"] as? [[String: Any]],
          let resp = functionResponses.first?["response"] as? [String: Any] else { return nil }
    return (resp["result"] as? String) ?? (resp["error"] as? String)
  }

  private static func firstURL(in text: String) -> String? {
    guard let detector = try? NSDataDetector(
      types: NSTextCheckingResult.CheckingType.link.rawValue) else { return nil }
    let range = NSRange(text.startIndex..., in: text)
    return detector.firstMatch(in: text, options: [], range: range)?.url?.absoluteString
  }

  func sendVideoFrameIfThrottled(image: UIImage) {
    guard SettingsManager.shared.videoStreamingEnabled else { return }
    guard isGeminiActive, connectionState == .ready else { return }
    let now = Date()
    guard now.timeIntervalSince(lastVideoFrameTime) >= GeminiConfig.videoFrameInterval else { return }
    lastVideoFrameTime = now
    geminiService.sendVideoFrame(image: image)
  }

}
