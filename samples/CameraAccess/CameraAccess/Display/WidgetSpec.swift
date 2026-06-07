/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 * All rights reserved.
 *
 * This source code is licensed under the license found in the
 * LICENSE file in the root directory of this source tree.
 */

//
// WidgetSpec.swift
//
// Data model for dynamically-rendered widgets. The model is produced from a
// Gemini `render_widgets` tool call (see ToolDeclarations.renderWidgets) and
// consumed by WidgetBoardView (native SwiftUI). Images use a fixed URL chosen by
// `kind` rather than an arbitrary URL from the model.
//

import Foundation

/// Fixed image sources. The model only chooses the *kind*; the URL is fixed here.
enum WidgetImageKind: String, Sendable, CaseIterable {
  case map
  case gallery
  case calendar

  var url: String {
    switch self {
    case .map:
      return "https://www.ski-plus-city.com/fileadmin/_processed_/7/b/csm_Hofburg_map_karte_26aee83d0d.jpg"
    case .gallery:
      return "https://ps.w.org/final-tiles-grid-gallery-lite/assets/screenshot-1.jpg?rev=1180077"
    case .calendar:
      return "https://img.magnific.com/premium-vector/june-july-2026-monthly-calendar-illustration-white-background-vol-02_1213128-1423.jpg?semt=ais_hybrid&w=740&q=80"
    }
  }

  var defaultCaption: String {
    switch self {
    case .map: return "Map"
    case .gallery: return "Photo gallery"
    case .calendar: return "Calendar"
    }
  }
}

/// One widget to render. The associated data is filled from the Gemini response.
struct WidgetSpec: Identifiable, Sendable {
  enum Kind: Sendable {
    case text(title: String?, body: String)
    // `url` is resolved from a fixed WidgetImageKind for Gemini render calls, or is
    // an arbitrary public URL for app-generated images (e.g. from OpenClaw).
    case image(url: String, caption: String?)
    case table(title: String?, columns: [String]?, rows: [[String]])
  }

  let id = UUID()
  let kind: Kind
}

extension WidgetSpec {
  /// Decodes the array of widgets from a `render_widgets` tool call's args.
  static func list(from args: [String: Any]) -> [WidgetSpec] {
    guard let rawWidgets = args["widgets"] as? [[String: Any]] else { return [] }
    return rawWidgets.compactMap { WidgetSpec(dictionary: $0) }
  }

  init?(dictionary dict: [String: Any]) {
    let type = (dict["type"] as? String ?? "").lowercased()
    switch type {
    case "text":
      let body = (dict["body"] as? String) ?? (dict["text"] as? String) ?? ""
      let title = dict["title"] as? String
      guard !body.isEmpty || (title?.isEmpty == false) else { return nil }
      self.kind = .text(title: title, body: body)

    case "image":
      // render_widgets only offers fixed image kinds, so resolve the URL from the kind.
      let kindString = ((dict["imageKind"] as? String) ?? (dict["kind"] as? String) ?? "map").lowercased()
      let imageKind = WidgetImageKind(rawValue: kindString) ?? .map
      let provided = dict["caption"] as? String
      let caption = (provided?.isEmpty == false) ? provided! : imageKind.defaultCaption
      self.kind = .image(url: imageKind.url, caption: caption)

    case "table":
      let columns = (dict["columns"] as? [Any])?.map { Self.string($0) }
      let rawRows = (dict["rows"] as? [[Any]]) ?? []
      let rows = rawRows.map { $0.map { Self.string($0) } }
      guard !rows.isEmpty || (columns?.isEmpty == false) else { return nil }
      self.kind = .table(title: dict["title"] as? String, columns: columns, rows: rows)

    default:
      return nil
    }
  }

  /// Coerces a JSON cell value (String / NSNumber / Bool) into a display string.
  private static func string(_ value: Any) -> String {
    if let s = value as? String { return s }
    if let b = value as? Bool { return b ? "Yes" : "No" }
    if let n = value as? NSNumber { return n.stringValue }
    return String(describing: value)
  }
}
