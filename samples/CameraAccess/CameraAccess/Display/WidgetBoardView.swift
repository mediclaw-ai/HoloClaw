/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 * All rights reserved.
 *
 * This source code is licensed under the license found in the
 * LICENSE file in the root directory of this source tree.
 */

//
// WidgetBoardView.swift
//
// Native SwiftUI renderer for a dynamic list of widgets (WidgetSpec) produced from
// a Gemini `render_widgets` tool call. Renders the three widget types — text, image,
// and table — each as a card in the shared Display card style.
//

import SwiftUI

struct WidgetBoardView: View {
  let widgets: [WidgetSpec]

  var body: some View {
    VStack(spacing: 12) {
      ForEach(widgets) { widget in
        WidgetCard(widget: widget)
      }
    }
  }
}

private struct WidgetCard: View {
  let widget: WidgetSpec

  var body: some View {
    switch widget.kind {
    case let .text(title, content):
      TextWidgetCard(title: title, bodyText: content)
    case let .image(url, caption):
      NativeImageWidget(
        imageURL: URL(string: url),
        caption: (caption?.isEmpty == false) ? caption! : "Image"
      )
    case let .table(title, columns, rows):
      DynamicTableWidget(title: title, columns: columns, rows: rows)
    }
  }
}

// MARK: - Text

private struct TextWidgetCard: View {
  let title: String?
  let bodyText: String

  var body: some View {
    VStack(alignment: .leading, spacing: 6) {
      if let title, !title.isEmpty {
        Text(title)
          .font(.system(size: 18, weight: .bold))
          .foregroundColor(.white)
      }
      if !bodyText.isEmpty {
        Text(bodyText)
          .font(.system(size: 15))
          .foregroundColor(.white.opacity(0.85))
          .fixedSize(horizontal: false, vertical: true)
      }
    }
    .frame(maxWidth: .infinity, alignment: .leading)
    .padding(18)
    .displayCardStyle()
  }
}

// MARK: - Table

private struct DynamicTableWidget: View {
  let title: String?
  let columns: [String]?
  let rows: [[String]]

  private var columnCount: Int {
    max(columns?.count ?? 0, rows.map(\.count).max() ?? 0)
  }

  var body: some View {
    VStack(alignment: .leading, spacing: 8) {
      if let title, !title.isEmpty {
        Text(title)
          .font(.system(size: 20, weight: .bold))
          .foregroundColor(.white)
      }
      if let columns, !columns.isEmpty {
        row(cells: columns, isHeader: true)
      }
      ForEach(Array(rows.enumerated()), id: \.offset) { item in
        row(cells: item.element, isHeader: false)
      }
    }
    .frame(maxWidth: .infinity, alignment: .leading)
    .padding(20)
    .displayCardStyle()
  }

  private func row(cells: [String], isHeader: Bool) -> some View {
    HStack(alignment: .top, spacing: 12) {
      ForEach(0 ..< max(columnCount, 1), id: \.self) { i in
        Text(i < cells.count ? cells[i] : "")
          .frame(maxWidth: .infinity, alignment: .leading)
      }
    }
    .font(.system(size: isHeader ? 12 : 15))
    .foregroundColor(isHeader ? .white.opacity(0.6) : .white)
  }
}

#Preview("Widget board") {
  ZStack {
    LinearGradient(colors: [Color(white: 0.4), Color(white: 0.1)], startPoint: .top, endPoint: .bottom)
      .ignoresSafeArea()
    ScrollView {
      WidgetBoardView(widgets: [
        WidgetSpec(kind: .text(title: "Vienna afternoon", body: "Here's a quick plan for the old town.")),
        WidgetSpec(kind: .image(url: WidgetImageKind.map.url, caption: "Hofburg area")),
        WidgetSpec(kind: .table(
          title: "Schedule",
          columns: ["Time", "Stop"],
          rows: [["09:00", "Hofburg"], ["10:30", "Stephansdom"], ["12:00", "Belvedere"]]
        )),
      ])
      .padding()
    }
  }
}
