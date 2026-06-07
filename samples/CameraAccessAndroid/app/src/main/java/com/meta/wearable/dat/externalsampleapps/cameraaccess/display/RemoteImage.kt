/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 * All rights reserved.
 *
 * This source code is licensed under the license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.meta.wearable.dat.externalsampleapps.cameraaccess.display

import android.graphics.BitmapFactory
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.foundation.Image
import androidx.compose.material3.CircularProgressIndicator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

private val imageClient = OkHttpClient()

@Composable
internal fun RemoteImage(url: String, modifier: Modifier = Modifier) {
    var bitmap by remember(url) { mutableStateOf<android.graphics.Bitmap?>(null) }
    var failed by remember(url) { mutableStateOf(false) }

    LaunchedEffect(url) {
        failed = false
        bitmap = null
        bitmap =
            withContext(Dispatchers.IO) {
                runCatching {
                    val response = imageClient.newCall(Request.Builder().url(url).build()).execute()
                    response.use { result ->
                        if (!result.isSuccessful) return@runCatching null
                        result.body?.byteStream()?.use { stream ->
                            BitmapFactory.decodeStream(stream)
                        }
                    }
                }.getOrNull()
            }
        if (bitmap == null) failed = true
    }

    Box(
        modifier = modifier.background(Color.White.copy(alpha = if (failed) 0.08f else 0.06f)),
        contentAlignment = Alignment.Center,
    ) {
        when {
            bitmap != null ->
                Image(
                    bitmap = bitmap!!.asImageBitmap(),
                    contentDescription = null,
                    modifier = Modifier.matchParentSize(),
                    contentScale = ContentScale.Fit,
                )
            failed -> Unit
            else -> CircularProgressIndicator(color = Color.White.copy(alpha = 0.7f))
        }
    }
}
