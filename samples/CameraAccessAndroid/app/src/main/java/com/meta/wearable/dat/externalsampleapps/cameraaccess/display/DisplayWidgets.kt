/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 * All rights reserved.
 *
 * This source code is licensed under the license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.meta.wearable.dat.externalsampleapps.cameraaccess.display

import com.meta.wearable.dat.display.views.Alignment
import com.meta.wearable.dat.display.views.ContentScope
import com.meta.wearable.dat.display.views.CornerRadius
import com.meta.wearable.dat.display.views.Direction
import com.meta.wearable.dat.display.views.FlexBoxBackground
import com.meta.wearable.dat.display.views.FlexBoxScope
import com.meta.wearable.dat.display.views.ImageSize
import com.meta.wearable.dat.display.views.TextColor
import com.meta.wearable.dat.display.views.TextStyle

object DisplayWidgets {
    const val MAP_IMAGE_URL =
        "https://www.ski-plus-city.com/fileadmin/_processed_/7/b/csm_Hofburg_map_karte_26aee83d0d.jpg"

    fun ContentScope.helloWorld() {
        flexBox(
            direction = Direction.COLUMN,
            gap = 8,
            alignment = Alignment.CENTER,
            crossAlignment = Alignment.CENTER,
            padding = 24,
            background = FlexBoxBackground.CARD,
        ) {
            text("Hello World", style = TextStyle.HEADING)
            text("Sent from VisionClaw", style = TextStyle.BODY, color = TextColor.SECONDARY)
        }
    }

    fun ContentScope.board(specs: List<WidgetSpec>) {
        flexBox(direction = Direction.COLUMN, gap = 12) {
            for (spec in specs) {
                flexBoxForSpec(spec)
            }
        }
    }

    private fun FlexBoxScope.flexBoxForSpec(spec: WidgetSpec) {
        when (val kind = spec.kind) {
            is WidgetSpec.Kind.Text ->
                flexBox(
                    direction = Direction.COLUMN,
                    gap = 6,
                    padding = 18,
                    background = FlexBoxBackground.CARD,
                ) {
                    if (!kind.title.isNullOrEmpty()) {
                        text(kind.title, style = TextStyle.HEADING)
                    }
                    if (kind.body.isNotEmpty()) {
                        text(kind.body, style = TextStyle.BODY)
                    }
                }
            is WidgetSpec.Kind.Image ->
                flexBox(
                    direction = Direction.COLUMN,
                    gap = 8,
                    alignment = Alignment.CENTER,
                    padding = 16,
                    background = FlexBoxBackground.CARD,
                ) {
                    image(
                        uri = kind.imageKind.url,
                        sizePreset = ImageSize.FILL,
                        cornerRadius = CornerRadius.MEDIUM,
                    )
                    val caption =
                        kind.caption?.takeIf { it.isNotEmpty() } ?: kind.imageKind.defaultCaption
                    text(caption, style = TextStyle.BODY, color = TextColor.SECONDARY)
                }
            is WidgetSpec.Kind.Table ->
                flexBox(
                    direction = Direction.COLUMN,
                    gap = 6,
                    padding = 20,
                    background = FlexBoxBackground.CARD,
                ) {
                    if (!kind.title.isNullOrEmpty()) {
                        text(kind.title, style = TextStyle.HEADING)
                    }
                    if (!kind.columns.isNullOrEmpty()) {
                        tableRowFlex(kind.columns, header = true)
                    }
                    for (row in kind.rows) {
                        tableRowFlex(row, header = false)
                    }
                }
        }
    }

    private fun FlexBoxScope.tableRowFlex(cells: List<String>, header: Boolean) {
        flexBox(direction = Direction.ROW, gap = 12) {
            for (cell in cells) {
                flexBox(flexGrow = 1f) {
                    text(
                        cell,
                        style = if (header) TextStyle.META else TextStyle.BODY,
                        color = if (header) TextColor.SECONDARY else TextColor.PRIMARY,
                    )
                }
            }
        }
    }
}
