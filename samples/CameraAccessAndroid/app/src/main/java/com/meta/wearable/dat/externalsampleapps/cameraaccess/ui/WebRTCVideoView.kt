/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 * All rights reserved.
 *
 * This source code is licensed under the license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.meta.wearable.dat.externalsampleapps.cameraaccess.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import org.webrtc.EglBase
import org.webrtc.RendererCommon
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoTrack

/** Compose wrapper for WebRTC SurfaceViewRenderer (matches iOS RTCVideoView). */
@Composable
fun WebRTCVideoView(
    videoTrack: VideoTrack?,
    eglContext: EglBase.Context,
    modifier: Modifier = Modifier,
) {
  var renderer by remember { mutableStateOf<SurfaceViewRenderer?>(null) }

  DisposableEffect(videoTrack, renderer) {
    val view = renderer
    view?.let { videoTrack?.addSink(it) }
    onDispose {
      if (view != null && videoTrack != null) {
        videoTrack.removeSink(view)
      }
    }
  }

  AndroidView(
      factory = { ctx ->
        SurfaceViewRenderer(ctx).apply {
          init(eglContext, null)
          setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FILL)
          setEnableHardwareScaler(true)
          renderer = this
          videoTrack?.addSink(this)
        }
      },
      update = { view ->
        if (renderer !== view) {
          renderer?.let { old ->
            videoTrack?.removeSink(old)
          }
          renderer = view
          videoTrack?.addSink(view)
        }
      },
      onRelease = { view ->
        videoTrack?.removeSink(view)
        renderer = null
        view.release()
      },
      modifier = modifier,
  )
}
