/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 * All rights reserved.
 *
 * This source code is licensed under the license found in the
 * LICENSE file in the root directory of this source tree.
 */

//
// NativeWidgets.swift
//
// Pure-SwiftUI versions of the display widgets (no MWDATDisplay DSL). These are the
// "SwiftUI" counterparts to the SDK FlexBox builders in DisplayWidgets.swift —
// hand-built native views that mirror the on-glasses card styling for the phone.
//

import SwiftUI

/// A schedule table, built natively in SwiftUI.
struct NativeTableWidget: View {
  struct Row: Identifiable {
    let id = UUID()
    let time: String
    let stop: String
  }

  var title: String = "Tour Schedule"
  var rows: [Row] = [
    Row(time: "09:00", stop: "Hofburg"),
    Row(time: "10:30", stop: "Stephansdom"),
    Row(time: "12:00", stop: "Belvedere"),
  ]

  var body: some View {
    VStack(alignment: .leading, spacing: 8) {
      Text(title)
        .font(.system(size: 20, weight: .bold))
        .foregroundColor(.white)

      tableRow(time: "Time", stop: "Stop", isHeader: true)
      ForEach(rows) { row in
        tableRow(time: row.time, stop: row.stop, isHeader: false)
      }
    }
    .padding(20)
    .displayCardStyle()
  }

  private func tableRow(time: String, stop: String, isHeader: Bool) -> some View {
    HStack(spacing: 12) {
      Text(time)
        .frame(width: 64, alignment: .leading)
      Text(stop)
      Spacer(minLength: 0)
    }
    .font(.system(size: isHeader ? 12 : 15))
    .foregroundColor(isHeader ? .white.opacity(0.6) : .white)
  }
}

/// An image card, built natively in SwiftUI (AsyncImage).
struct NativeImageWidget: View {
  var imageURL: URL? = URL(string: DisplayWidgets.mapImageURL)
  var caption: String = "Hofburg — city map"

  var body: some View {
    VStack(spacing: 8) {
      AsyncImage(url: imageURL) { phase in
        if let image = phase.image {
          image.resizable().aspectRatio(contentMode: .fit)
        } else if phase.error != nil {
          ZStack {
            Color.white.opacity(0.08)
            Image(systemName: "photo").foregroundColor(.white.opacity(0.5))
          }
        } else {
          ZStack {
            Color.white.opacity(0.06)
            ProgressView()
          }
        }
      }
      .frame(height: 150)
      .frame(maxWidth: .infinity)
      .clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))

      Text(caption)
        .font(.system(size: 13))
        .foregroundColor(.white.opacity(0.6))
    }
    .padding(16)
    .displayCardStyle()
  }
}

/// Shared dark, rounded card style that mirrors the Display `.card` look. Used by
/// the static native widgets and by the dynamic WidgetBoardView.
extension View {
  func displayCardStyle() -> some View {
    background(
      RoundedRectangle(cornerRadius: 18, style: .continuous)
        .fill(.black.opacity(0.55))
        .overlay(
          RoundedRectangle(cornerRadius: 18, style: .continuous)
            .strokeBorder(.white.opacity(0.12), lineWidth: 1)
        )
    )
  }
}

#Preview("Native widgets") {
  ZStack {
    LinearGradient(colors: [Color(white: 0.4), Color(white: 0.1)], startPoint: .top, endPoint: .bottom)
      .ignoresSafeArea()
    VStack(spacing: 16) {
      NativeTableWidget()
      NativeImageWidget()
    }
    .padding()
  }
}
