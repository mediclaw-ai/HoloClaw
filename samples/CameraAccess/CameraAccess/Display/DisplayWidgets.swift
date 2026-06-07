/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 * All rights reserved.
 *
 * This source code is licensed under the license found in the
 * LICENSE file in the root directory of this source tree.
 */

//
// DisplayWidgets.swift
//
// SDK (MWDATDisplay DSL) widget builders. The returned FlexBox is the single
// source of truth: it renders on Meta Ray-Ban Display glasses via Display.send(...)
// and on the iPhone screen via DisplayDSLView. Kept SwiftUI-free so the DSL names
// (Text / Image / FlexBox) stay unambiguous.
//

import MWDATDisplay

enum DisplayWidgets {
  /// Image placeholder used by both the SDK and the SwiftUI widget versions.
  static let mapImageURL =
    "https://www.ski-plus-city.com/fileadmin/_processed_/7/b/csm_Hofburg_map_karte_26aee83d0d.jpg"

  /// A schedule "table": a heading, a header row, and data rows. The DSL has no
  /// dedicated table type, so a table is a column of row FlexBoxes.
  static func table() -> FlexBox {
    FlexBox(
      direction: .column,
      spacing: 6,
      alignment: .start,
      crossAlignment: .stretch,
      padding: EdgeInsets(all: 20)
    ) {
      Text("Tour Schedule", style: .heading)
      tableRow("Time", "Stop", header: true)
      tableRow("09:00", "Hofburg", header: false)
      tableRow("10:30", "Stephansdom", header: false)
      tableRow("12:00", "Belvedere", header: false)
    }
    .background(.card)
  }

  /// An image card: a remote image with a caption.
  static func imageCard() -> FlexBox {
    FlexBox(
      direction: .column,
      spacing: 8,
      alignment: .center,
      crossAlignment: .stretch,
      padding: EdgeInsets(all: 16)
    ) {
      Image(uri: mapImageURL, sizePreset: .fill, cornerRadius: .medium)
      Text("Hofburg — city map", style: .body, color: .secondary)
    }
    .background(.card)
  }

  private static func tableRow(_ time: String, _ stop: String, header: Bool) -> FlexBox {
    FlexBox(direction: .row, spacing: 12, alignment: .start, crossAlignment: .center) {
      Text(time, style: header ? .meta : .body, color: .secondary)
      Text(stop, style: header ? .meta : .body, color: header ? .secondary : .primary)
    }
  }

  // MARK: - Dynamic widgets (the SDK form of a WidgetSpec, e.g. from Gemini)

  /// A column of widget cards — the SDK/glasses-renderable form of a widget board.
  static func board(for specs: [WidgetSpec]) -> FlexBox {
    FlexBox(direction: .column, spacing: 12, alignment: .start, crossAlignment: .stretch) {
      for spec in specs {
        flexBox(for: spec)
      }
    }
  }

  /// The SDK FlexBox for a single dynamic widget.
  static func flexBox(for spec: WidgetSpec) -> FlexBox {
    switch spec.kind {
    case let .text(title, body):
      return FlexBox(
        direction: .column, spacing: 6, alignment: .start, crossAlignment: .stretch,
        padding: EdgeInsets(all: 18)
      ) {
        if let title, !title.isEmpty { Text(title, style: .heading) }
        if !body.isEmpty { Text(body, style: .body) }
      }
      .background(.card)

    case let .image(url, caption):
      return FlexBox(
        direction: .column, spacing: 8, alignment: .center, crossAlignment: .stretch,
        padding: EdgeInsets(all: 16)
      ) {
        Image(uri: url, sizePreset: .fill, cornerRadius: .medium)
        Text((caption?.isEmpty == false) ? caption! : "Image", style: .body, color: .secondary)
      }
      .background(.card)

    case let .table(title, columns, rows):
      return FlexBox(
        direction: .column, spacing: 6, alignment: .start, crossAlignment: .stretch,
        padding: EdgeInsets(all: 20)
      ) {
        if let title, !title.isEmpty { Text(title, style: .heading) }
        if let columns, !columns.isEmpty { tableRowFlex(columns, header: true) }
        for row in rows {
          tableRowFlex(row, header: false)
        }
      }
      .background(.card)
    }
  }

  /// A table row whose cells share width equally (via flexGrow).
  private static func tableRowFlex(_ cells: [String], header: Bool) -> FlexBox {
    FlexBox(direction: .row, spacing: 12, alignment: .start, crossAlignment: .start) {
      for cell in cells {
        FlexBox(direction: .column, alignment: .start, crossAlignment: .start) {
          Text(cell, style: header ? .meta : .body, color: header ? .secondary : .primary)
        }
        .flexGrow(1)
      }
    }
  }
}
