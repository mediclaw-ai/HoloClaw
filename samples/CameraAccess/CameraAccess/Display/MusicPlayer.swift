/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 * All rights reserved.
 *
 * This source code is licensed under the license found in the
 * LICENSE file in the root directory of this source tree.
 */

//
// MusicPlayer.swift
//
// Plays a music URL (e.g. an Eleven Labs track returned by OpenClaw) via AVPlayer.
// The DAT SDK has no glasses audio-playback API, so playback uses iOS audio, which
// routes to the Ray-Ban Meta glasses over Bluetooth (A2DP) when they are connected
// as the active output. MusicPlayerWidget is the play/pause card shown in the FOV.
//

import AVFoundation
import Combine
import SwiftUI

@MainActor
final class MusicPlayer: ObservableObject {
  static let shared = MusicPlayer()

  @Published private(set) var isPlaying = false
  @Published private(set) var currentURL: String?

  private var player: AVPlayer?
  private var endObserver: NSObjectProtocol?

  private init() {}

  /// True if the given URL is the current track and it is playing.
  func isPlaying(url: String) -> Bool {
    isPlaying && currentURL == url
  }

  /// Starts (or resumes) playback of `url`. Audio routes to the active output,
  /// including Ray-Ban Meta glasses when connected over Bluetooth.
  func play(url: String) {
    guard let parsed = URL(string: url) else { return }
    configureSession()
    if currentURL != url || player == nil {
      loadItem(parsed, url: url)
    }
    player?.play()
    isPlaying = true
  }

  func pause() {
    player?.pause()
    isPlaying = false
  }

  func toggle(url: String) {
    if isPlaying(url: url) {
      pause()
    } else {
      play(url: url)
    }
  }

  func stop() {
    player?.pause()
    player = nil
    isPlaying = false
    currentURL = nil
  }

  // MARK: - Private

  private func loadItem(_ parsed: URL, url: String) {
    if let endObserver {
      NotificationCenter.default.removeObserver(endObserver)
      self.endObserver = nil
    }
    let item = AVPlayerItem(url: parsed)
    player = AVPlayer(playerItem: item)
    currentURL = url
    endObserver = NotificationCenter.default.addObserver(
      forName: .AVPlayerItemDidPlayToEndTime, object: item, queue: .main
    ) { [weak self] _ in
      Task { @MainActor in self?.isPlaying = false }
    }
  }

  private func configureSession() {
    let session = AVAudioSession.sharedInstance()
    try? session.setCategory(.playback, options: [.allowBluetoothA2DP, .mixWithOthers])
    try? session.setActive(true)
  }
}

/// Native music-player card: a play/pause toggle + the track title.
struct MusicPlayerWidget: View {
  let url: String
  let title: String
  @ObservedObject private var player = MusicPlayer.shared

  var body: some View {
    HStack(spacing: 14) {
      Button {
        player.toggle(url: url)
      } label: {
        Image(systemName: player.isPlaying(url: url) ? "pause.circle.fill" : "play.circle.fill")
          .font(.system(size: 44))
          .foregroundColor(.white)
      }
      .buttonStyle(.plain)

      VStack(alignment: .leading, spacing: 2) {
        Text(title)
          .font(.system(size: 16, weight: .semibold))
          .foregroundColor(.white)
        Text(player.isPlaying(url: url) ? "Playing on your glasses…" : "Tap to play")
          .font(.system(size: 12))
          .foregroundColor(.white.opacity(0.6))
      }
      Spacer(minLength: 0)
    }
    .padding(16)
    .frame(maxWidth: .infinity, alignment: .leading)
    .displayCardStyle()
  }
}
