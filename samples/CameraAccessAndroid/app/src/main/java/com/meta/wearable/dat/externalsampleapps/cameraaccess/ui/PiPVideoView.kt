/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 * All rights reserved.
 *
 * This source code is licensed under the license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.meta.wearable.dat.externalsampleapps.cameraaccess.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.webrtc.EglBase
import org.webrtc.VideoTrack

/** FaceTime-style PiP layout for bidirectional WebRTC streaming (matches iOS PiPVideoView). */
@Composable
fun PiPVideoView(
    localFrame: Bitmap?,
    remoteVideoTrack: VideoTrack?,
    hasRemoteVideo: Boolean,
    eglContext: EglBase.Context?,
    modifier: Modifier = Modifier,
) {
  var isSwapped by remember { mutableStateOf(false) }
  val pipWidth = 120.dp
  val pipHeight = 160.dp

  Box(modifier = modifier.fillMaxSize()) {
    VideoLayer(
        isRemote = isSwapped,
        localFrame = localFrame,
        remoteVideoTrack = remoteVideoTrack,
        hasRemoteVideo = hasRemoteVideo,
        eglContext = eglContext,
        modifier = Modifier.fillMaxSize(),
    )

    Box(
        modifier =
            Modifier.align(Alignment.TopEnd)
                .padding(top = 16.dp, end = 16.dp)
                .size(pipWidth, pipHeight)
                .shadow(8.dp, RoundedCornerShape(12.dp))
                .clip(RoundedCornerShape(12.dp))
                .border(1.dp, Color.White.copy(alpha = 0.3f), RoundedCornerShape(12.dp))
                .clickable { isSwapped = !isSwapped },
    ) {
      VideoLayer(
          isRemote = !isSwapped,
          localFrame = localFrame,
          remoteVideoTrack = remoteVideoTrack,
          hasRemoteVideo = hasRemoteVideo,
          eglContext = eglContext,
          modifier = Modifier.fillMaxSize(),
      )
    }
  }
}

@Composable
private fun VideoLayer(
    isRemote: Boolean,
    localFrame: Bitmap?,
    remoteVideoTrack: VideoTrack?,
    hasRemoteVideo: Boolean,
    eglContext: EglBase.Context?,
    modifier: Modifier = Modifier,
) {
  if (isRemote) {
    if (hasRemoteVideo && remoteVideoTrack != null && eglContext != null) {
      WebRTCVideoView(
          videoTrack = remoteVideoTrack,
          eglContext = eglContext,
          modifier = modifier,
      )
    } else {
      RemotePlaceholder(modifier = modifier)
    }
  } else if (localFrame != null) {
    Image(
        bitmap = localFrame.asImageBitmap(),
        contentDescription = null,
        modifier = modifier,
        contentScale = ContentScale.Crop,
    )
  } else {
    Box(modifier = modifier.background(Color.Black))
  }
}

@Composable
private fun RemotePlaceholder(modifier: Modifier = Modifier) {
  Box(
      modifier = modifier.background(Color(0xFF262626)),
      contentAlignment = Alignment.Center,
  ) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
      Icon(
          imageVector = Icons.Default.Person,
          contentDescription = null,
          tint = Color.White.copy(alpha = 0.4f),
          modifier = Modifier.size(32.dp),
      )
      Spacer(modifier = Modifier.height(8.dp))
      Text(text = "No video", color = Color.White.copy(alpha = 0.4f), fontSize = 11.sp)
    }
  }
}
