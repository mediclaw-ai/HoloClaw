/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 * All rights reserved.
 *
 * This source code is licensed under the license found in the
 * LICENSE file in the root directory of this source tree.
 */

//
// HelloWorldDisplay.swift
//
// Builds the on-glasses view components rendered on Meta Ray-Ban Display glasses
// via the MWDATDisplay capability. This file intentionally imports only
// MWDATDisplay (no SwiftUI) so the Display DSL names (Text, FlexBox, ...) are
// unambiguous — see the SDK note about Text/Button/Image colliding with SwiftUI.
//

import MWDATDisplay

enum HelloWorldDisplay {
  /// A simple centered card that shows "Hello World" on the glasses display.
  ///
  /// `send(_:)` requires exactly one root `DisplayableView`; here that root is a
  /// `FlexBox` containing two `Text` components.
  static func helloWorld() -> FlexBox {
    FlexBox(
      direction: .column,
      spacing: 8,
      alignment: .center,
      crossAlignment: .center,
      padding: EdgeInsets(all: 24)
    ) {
      Text("Hello World", style: .heading)
      Text("Sent from VisionClaw", style: .body, color: .secondary)
    }
    .background(.card)
  }
}
