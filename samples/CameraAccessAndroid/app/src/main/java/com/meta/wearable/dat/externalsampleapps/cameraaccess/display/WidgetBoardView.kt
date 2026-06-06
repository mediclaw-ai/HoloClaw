/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 * All rights reserved.
 *
 * This source code is licensed under the license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.meta.wearable.dat.externalsampleapps.cameraaccess.display

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun WidgetBoardView(
    widgets: List<WidgetSpec>,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier, verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(12.dp)) {
        for (widget in widgets) {
            WidgetCard(widget = widget)
        }
    }
}

@Composable
private fun WidgetCard(widget: WidgetSpec) {
    when (val kind = widget.kind) {
        is WidgetSpec.Kind.Text -> TextWidgetCard(title = kind.title, bodyText = kind.body)
        is WidgetSpec.Kind.Image ->
            ImageWidgetCard(
                imageUrl = kind.imageKind.url,
                caption = kind.caption?.takeIf { it.isNotEmpty() } ?: kind.imageKind.defaultCaption,
            )
        is WidgetSpec.Kind.Table ->
            TableWidgetCard(title = kind.title, columns = kind.columns, rows = kind.rows)
    }
}

@Composable
private fun TextWidgetCard(title: String?, bodyText: String) {
    Column(
        modifier = Modifier.fillMaxWidth().displayCardStyle().padding(18.dp),
    ) {
        if (!title.isNullOrEmpty()) {
            Text(
                text = title,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White,
            )
        }
        if (bodyText.isNotEmpty()) {
            Text(
                text = bodyText,
                fontSize = 15.sp,
                color = Color.White.copy(alpha = 0.85f),
            )
        }
    }
}

@Composable
private fun ImageWidgetCard(imageUrl: String, caption: String) {
    Column(
        modifier = Modifier.fillMaxWidth().displayCardStyle().padding(16.dp),
    ) {
        RemoteImage(
            url = imageUrl,
            modifier =
                Modifier.fillMaxWidth()
                    .height(150.dp)
                    .clip(RoundedCornerShape(14.dp)),
        )
        Text(
            text = caption,
            fontSize = 13.sp,
            color = Color.White.copy(alpha = 0.6f),
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}

@Composable
private fun TableWidgetCard(
    title: String?,
    columns: List<String>?,
    rows: List<List<String>>,
) {
    val columnCount = maxOf(columns?.size ?: 0, rows.maxOfOrNull { it.size } ?: 0)

    Column(
        modifier = Modifier.fillMaxWidth().displayCardStyle().padding(20.dp),
    ) {
        if (!title.isNullOrEmpty()) {
            Text(
                text = title,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White,
            )
        }
        if (!columns.isNullOrEmpty()) {
            TableRow(cells = columns, columnCount = columnCount, isHeader = true)
        }
        for (row in rows) {
            TableRow(cells = row, columnCount = columnCount, isHeader = false)
        }
    }
}

@Composable
private fun TableRow(cells: List<String>, columnCount: Int, isHeader: Boolean) {
    Row(modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
        for (i in 0 until maxOf(columnCount, 1)) {
            Text(
                text = cells.getOrNull(i).orEmpty(),
                modifier = Modifier.weight(1f),
                fontSize = if (isHeader) 12.sp else 15.sp,
                color = if (isHeader) Color.White.copy(alpha = 0.6f) else Color.White,
            )
        }
    }
}

@Composable
fun DisplayPreviewBoard(
    widgets: List<WidgetSpec>,
    modifier: Modifier = Modifier,
) {
    // Android SDK display content is builder-only; mirror the on-glasses layout natively.
    WidgetBoardView(widgets = widgets, modifier = modifier)
}

@Composable
private fun Modifier.displayCardStyle(): Modifier {
    val shape = RoundedCornerShape(18.dp)
    return clip(shape)
        .background(Color.Black.copy(alpha = 0.55f), shape)
        .border(1.dp, Color.White.copy(alpha = 0.12f), shape)
}
