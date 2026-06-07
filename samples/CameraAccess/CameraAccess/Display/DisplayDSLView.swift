/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 * All rights reserved.
 *
 * This source code is licensed under the license found in the
 * LICENSE file in the root directory of this source tree.
 */

//
// DisplayDSLView.swift
//
// Native SwiftUI renderer for the MWDATDisplay DSL.
//
// The SDK only *sends* Display views to the glasses (Display.send over a
// DeviceSession); it has no phone-side renderer. The DSL types (FlexBox / Text /
// Button / Image / Icon) are, however, public readable structs, so this view
// walks that same component model and renders it natively — letting us mirror the
// on-glasses widget on the iPhone screen from a single source of truth
// (e.g. `HelloWorldDisplay.helloWorld()`).
//
// MWDATDisplay's `Text`/`Button`/`Image` names collide with SwiftUI, so SDK types
// are written fully-qualified (`MWDATDisplay.Text`) and SwiftUI types are written
// `SwiftUI.Text` where ambiguous.
//

import MWDATDisplay
import SwiftUI

/// Renders a Display `FlexBox` (the root view sent to the glasses) natively.
struct DisplayDSLView: View {
  let flexBox: MWDATDisplay.FlexBox

  var body: some View {
    FlexBoxView(flexBox: flexBox)
  }
}

// MARK: - FlexBox

private struct FlexBoxView: View {
  let flexBox: MWDATDisplay.FlexBox

  var body: some View {
    stack
      .padding(edgeInsets)
      .background(backgroundView)
      .modifier(TapModifier(onTap: flexBox.onClick))
  }

  @ViewBuilder
  private var stack: some View {
    switch flexBox.direction {
    case .row, .rowReverse:
      HStack(alignment: crossVerticalAlignment, spacing: flexBox.spacing) {
        ForEach(Array(orderedChildren.enumerated()), id: \.offset) { item in
          ComponentView(component: item.element)
            .modifier(FlexGrowModifier(component: item.element, isRow: true))
        }
      }
    default:  // .column / .columnReverse / future
      VStack(alignment: crossHorizontalAlignment, spacing: flexBox.spacing) {
        ForEach(Array(orderedChildren.enumerated()), id: \.offset) { item in
          ComponentView(component: item.element)
            .modifier(FlexGrowModifier(component: item.element, isRow: false))
        }
      }
    }
  }

  private var orderedChildren: [any MWDATDisplay.ViewComponent] {
    switch flexBox.direction {
    case .rowReverse, .columnReverse: return flexBox.children.reversed()
    default: return flexBox.children
    }
  }

  private var crossHorizontalAlignment: HorizontalAlignment {
    switch flexBox.crossAlignment {
    case .start: return .leading
    case .end: return .trailing
    default: return .center  // .center / .stretch
    }
  }

  private var crossVerticalAlignment: VerticalAlignment {
    switch flexBox.crossAlignment {
    case .start: return .top
    case .end: return .bottom
    default: return .center
    }
  }

  private var edgeInsets: SwiftUI.EdgeInsets {
    guard let p = flexBox.padding else { return SwiftUI.EdgeInsets() }
    return SwiftUI.EdgeInsets(top: p.top, leading: p.leading, bottom: p.bottom, trailing: p.trailing)
  }

  @ViewBuilder
  private var backgroundView: some View {
    switch flexBox.background {
    case .card:
      RoundedRectangle(cornerRadius: 18, style: .continuous)
        .fill(.black.opacity(0.55))
        .overlay(
          RoundedRectangle(cornerRadius: 18, style: .continuous)
            .strokeBorder(.white.opacity(0.12), lineWidth: 1)
        )
    default:  // .none / future
      Color.clear
    }
  }
}

/// Applies an optional tap handler (FlexBox.onTap) without changing the view type.
private struct TapModifier: ViewModifier {
  let onTap: (@Sendable () -> Void)?

  @ViewBuilder
  func body(content: Content) -> some View {
    if let onTap {
      content.onTapGesture { onTap() }
    } else {
      content
    }
  }
}

/// Honors a child FlexBox's `flexGrow` by letting it expand along the parent axis
/// (e.g. equal-width table columns in a row).
private struct FlexGrowModifier: ViewModifier {
  let component: any MWDATDisplay.ViewComponent
  let isRow: Bool

  @ViewBuilder
  func body(content: Content) -> some View {
    if let flexBox = component as? MWDATDisplay.FlexBox, flexBox.flexGrow > 0 {
      if isRow {
        content.frame(maxWidth: .infinity)
      } else {
        content.frame(maxHeight: .infinity)
      }
    } else {
      content
    }
  }
}

// MARK: - Leaf components

private struct ComponentView: View {
  let component: any MWDATDisplay.ViewComponent

  var body: some View {
    if let text = component as? MWDATDisplay.Text {
      SwiftUI.Text(text.content)
        .font(displayFont(for: text.style))
        .foregroundColor(displayColor(for: text.color))
        .multilineTextAlignment(.center)
    } else if let button = component as? MWDATDisplay.Button {
      buttonView(button)
    } else if let image = component as? MWDATDisplay.Image {
      imageView(image)
    } else if let icon = component as? MWDATDisplay.Icon {
      SwiftUI.Image(systemName: sfSymbol(for: icon.name))
        .foregroundColor(.white)
    } else if let nested = component as? MWDATDisplay.FlexBox {
      FlexBoxView(flexBox: nested)
    } else {
      EmptyView()
    }
  }

  @ViewBuilder
  private func buttonView(_ button: MWDATDisplay.Button) -> some View {
    SwiftUI.Button(action: { button.onClick?() }) {
      HStack(spacing: 6) {
        if let iconName = button.iconName {
          SwiftUI.Image(systemName: sfSymbol(for: iconName))
        }
        SwiftUI.Text(button.label).fontWeight(.semibold)
      }
      .padding(.horizontal, 16)
      .padding(.vertical, 10)
      .frame(maxWidth: .infinity)
      .background(buttonBackground(button.style))
      .foregroundColor(buttonForeground(button.style))
      .clipShape(Capsule())
      .overlay(
        Capsule().strokeBorder(
          .white.opacity(button.style == .outline ? 0.6 : 0),
          lineWidth: 1
        )
      )
    }
    .buttonStyle(.plain)
  }

  @ViewBuilder
  private func imageView(_ image: MWDATDisplay.Image) -> some View {
    let isIcon = image.sizePreset == .icon
    AsyncImage(url: URL(string: image.uri)) { phase in
      if let img = phase.image {
        img.resizable().aspectRatio(contentMode: .fit)
      } else if phase.error != nil {
        Color.white.opacity(0.12)
      } else {
        ProgressView()
      }
    }
    .frame(width: isIcon ? 28 : nil, height: isIcon ? 28 : 120)
    .frame(maxWidth: isIcon ? nil : .infinity)
    .clipShape(RoundedRectangle(cornerRadius: corner(image.cornerRadius), style: .continuous))
  }
}

// MARK: - Style mapping (Display enums -> SwiftUI)

private func displayFont(for style: MWDATDisplay.TextStyle) -> Font {
  switch style {
  case .heading: return .system(size: 22, weight: .bold)
  case .meta: return .system(size: 12, weight: .regular)
  default: return .system(size: 15, weight: .regular)  // .body
  }
}

private func displayColor(for color: MWDATDisplay.TextColor) -> Color {
  switch color {
  case .secondary: return .white.opacity(0.6)
  default: return .white  // .primary
  }
}

private func buttonBackground(_ style: MWDATDisplay.ButtonStyle) -> Color {
  switch style {
  case .primary: return .white
  case .secondary: return .white.opacity(0.18)
  default: return .clear  // .outline
  }
}

private func buttonForeground(_ style: MWDATDisplay.ButtonStyle) -> Color {
  switch style {
  case .primary: return .black
  default: return .white  // .secondary / .outline
  }
}

private func corner(_ radius: MWDATDisplay.CornerRadius) -> CGFloat {
  switch radius {
  case .small: return 8
  case .medium: return 14
  default: return 0  // .none
  }
}

/// Best-effort mapping from the Display IconName set to SF Symbols.
private func sfSymbol(for name: MWDATDisplay.IconName) -> String {
  switch name {
  case .checkmark, .checkmarkCircle: return "checkmark"
  case .arrowLeft: return "arrow.left"
  case .arrowRight: return "arrow.right"
  case .triangleRight, .triangleRightCircle: return "play.fill"
  case .gear: return "gearshape"
  case .bell: return "bell"
  case .heart: return "heart"
  case .star: return "star"
  case .house: return "house"
  case .person, .personCircle: return "person"
  case .phone: return "phone"
  case .calendar: return "calendar"
  case .clock: return "clock"
  case .metaAi, .starCircleTriangleAi: return "sparkles"
  case .musicNote: return "music.note"
  case .videoCamera: return "video"
  case .compassNorthUpRed: return "location.north"
  case .exclamationTriangle: return "exclamationmark.triangle"
  case .exclamationCircle, .iCircle: return "exclamationmark.circle"
  case .plus, .plusCircle: return "plus"
  case .x: return "xmark"
  case .cart, .shoppingBag: return "cart"
  case .magicWand: return "wand.and.stars"
  case .lightBulb: return "lightbulb"
  case .eye: return "eye"
  case .smartGlasses: return "eyeglasses"
  default: return "circle"
  }
}

#Preview {
  ZStack {
    Color.gray
    DisplayDSLView(flexBox: HelloWorldDisplay.helloWorld())
  }
  .ignoresSafeArea()
}
