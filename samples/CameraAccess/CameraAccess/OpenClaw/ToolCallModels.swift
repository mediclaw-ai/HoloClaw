import Foundation

// MARK: - Gemini Tool Call (parsed from server JSON)

struct GeminiFunctionCall {
  let id: String
  let name: String
  let args: [String: Any]
}

struct GeminiToolCall {
  let functionCalls: [GeminiFunctionCall]

  init?(json: [String: Any]) {
    guard let toolCall = json["toolCall"] as? [String: Any],
          let calls = toolCall["functionCalls"] as? [[String: Any]] else {
      return nil
    }
    self.functionCalls = calls.compactMap { call in
      guard let id = call["id"] as? String,
            let name = call["name"] as? String else { return nil }
      let args = call["args"] as? [String: Any] ?? [:]
      return GeminiFunctionCall(id: id, name: name, args: args)
    }
  }
}

// MARK: - Gemini Tool Call Cancellation

struct GeminiToolCallCancellation {
  let ids: [String]

  init?(json: [String: Any]) {
    guard let cancellation = json["toolCallCancellation"] as? [String: Any],
          let ids = cancellation["ids"] as? [String] else {
      return nil
    }
    self.ids = ids
  }
}

// MARK: - Tool Result

enum ToolResult {
  case success(String)
  case failure(String)

  var responseValue: [String: Any] {
    switch self {
    case .success(let result):
      return ["result": result]
    case .failure(let error):
      return ["error": error]
    }
  }
}

// MARK: - Tool Call Status (for UI)

enum ToolCallStatus: Equatable {
  case idle
  case executing(String)
  case completed(String)
  case failed(String, String)
  case cancelled(String)

  var displayText: String {
    switch self {
    case .idle: return ""
    case .executing(let name): return "Running: \(name)..."
    case .completed(let name): return "Done: \(name)"
    case .failed(let name, let err): return "Failed: \(name) - \(err)"
    case .cancelled(let name): return "Cancelled: \(name)"
    }
  }

  var isActive: Bool {
    if case .executing = self { return true }
    return false
  }
}

// MARK: - Tool Declarations (for Gemini setup message)

enum ToolDeclarations {

  static func allDeclarations() -> [[String: Any]] {
    return [execute]
  }

  /// The single tool. The model sets `action` so the app can decide between
  /// delegating a real-world task to OpenClaw and rendering widgets in the UI.
  static let execute: [String: Any] = [
    "name": "execute",
    "description": "Your single tool for taking any action. First DECIDE the kind of action and set \"action\":\n• action=\"render\": display visual widget cards in the user's field of view — provide \"widgets\".\n• action=\"delegate\": perform a real-world action (sending messages, searching, lists, reminders, notes, scheduling, smart-home control, app interactions, research, etc.) — provide a detailed \"task\". This is the only way anything real happens.",
    "parameters": [
      "type": "object",
      "properties": [
        "action": [
          "type": "string",
          "enum": ["delegate", "render"],
          "description": "delegate = perform a real-world action via the assistant (OpenClaw); render = show widget cards in the field of view.",
        ],
        "task": [
          "type": "string",
          "description": "For action=delegate: a clear, detailed description of what to do, with all relevant context (names, content, platforms, quantities, etc.).",
        ],
        "widgets": [
          "type": "array",
          "description": "For action=render: the widget cards to show, top to bottom. Each render replaces whatever is currently shown.",
          "items": [
            "type": "object",
            "properties": [
              "type": [
                "type": "string",
                "enum": ["text", "image", "table"],
                "description": "The widget type.",
              ],
              "title": [
                "type": "string",
                "description": "Optional heading shown at the top of the widget.",
              ],
              "body": [
                "type": "string",
                "description": "For type=text: the paragraph of text to show.",
              ],
              "imageKind": [
                "type": "string",
                "enum": ["map", "gallery", "calendar"],
                "description": "For type=image: which fixed image to show — map (a location/Google map), gallery (a photo gallery), or calendar (a calendar).",
              ],
              "caption": [
                "type": "string",
                "description": "For type=image: a short caption under the image.",
              ],
              "columns": [
                "type": "array",
                "items": ["type": "string"],
                "description": "For type=table: optional column header labels.",
              ],
              "rows": [
                "type": "array",
                "description": "For type=table: the rows; each row is an array of cell strings.",
                "items": [
                  "type": "array",
                  "items": ["type": "string"],
                ],
              ],
            ] as [String: Any],
            "required": ["type"],
          ] as [String: Any],
        ] as [String: Any],
      ] as [String: Any],
      "required": ["action"],
    ] as [String: Any],
    "behavior": "BLOCKING",
  ]
}
