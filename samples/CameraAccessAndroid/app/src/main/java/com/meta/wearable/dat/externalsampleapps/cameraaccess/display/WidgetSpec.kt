/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 * All rights reserved.
 *
 * This source code is licensed under the license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.meta.wearable.dat.externalsampleapps.cameraaccess.display

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

enum class WidgetImageKind(val url: String, val defaultCaption: String) {
    MAP(
        "https://www.ski-plus-city.com/fileadmin/_processed_/7/b/csm_Hofburg_map_karte_26aee83d0d.jpg",
        "Map",
    ),
    GALLERY(
        "https://ps.w.org/final-tiles-grid-gallery-lite/assets/screenshot-1.jpg?rev=1180077",
        "Photo gallery",
    ),
    CALENDAR(
        "https://img.magnific.com/premium-vector/june-july-2026-monthly-calendar-illustration-white-background-vol-02_1213128-1423.jpg?semt=ais_hybrid&w=740&q=80",
        "Calendar",
    ),
    ;

    companion object {
        fun from(raw: String): WidgetImageKind =
            entries.find { it.name.equals(raw, ignoreCase = true) } ?: MAP
    }
}

data class WidgetSpec(
    val id: String = UUID.randomUUID().toString(),
    val kind: Kind,
) {
    sealed class Kind {
        data class Text(val title: String?, val body: String) : Kind()

        data class Image(val imageKind: WidgetImageKind, val caption: String?) : Kind()

        data class Table(val title: String?, val columns: List<String>?, val rows: List<List<String>>) :
            Kind()
    }

    companion object {
        fun listFrom(args: Map<String, Any?>): List<WidgetSpec> {
            val rawWidgets =
                when (val widgets = args["widgets"]) {
                    is JSONArray -> widgets
                    is List<*> -> JSONArray(widgets)
                    else -> return emptyList()
                }
            return buildList {
                for (i in 0 until rawWidgets.length()) {
                    val dict = rawWidgets.optJSONObject(i) ?: continue
                    fromDictionary(dict)?.let { add(it) }
                }
            }
        }

        private fun fromDictionary(dict: JSONObject): WidgetSpec? {
            val type = dict.optString("type", "").lowercase()
            return when (type) {
                "text" -> {
                    val body = dict.optString("body", dict.optString("text", ""))
                    val title = dict.optString("title").takeIf { it.isNotEmpty() }
                    if (body.isEmpty() && title.isNullOrEmpty()) return null
                    WidgetSpec(kind = Kind.Text(title = title, body = body))
                }
                "image" -> {
                    val kindString =
                        dict.optString("imageKind", dict.optString("kind", "map")).lowercase()
                    val imageKind = WidgetImageKind.from(kindString)
                    val caption = dict.optString("caption").takeIf { it.isNotEmpty() }
                    WidgetSpec(kind = Kind.Image(imageKind = imageKind, caption = caption))
                }
                "table" -> {
                    val columns = dict.optJSONArray("columns")?.toStringList()
                    val rows =
                        buildList {
                            val rawRows = dict.optJSONArray("rows") ?: JSONArray()
                            for (i in 0 until rawRows.length()) {
                                val row = rawRows.optJSONArray(i) ?: continue
                                add(row.toStringList())
                            }
                        }
                    if (rows.isEmpty() && columns.isNullOrEmpty()) return null
                    val title = dict.optString("title").takeIf { it.isNotEmpty() }
                    WidgetSpec(kind = Kind.Table(title = title, columns = columns, rows = rows))
                }
                else -> null
            }
        }

        private fun JSONArray.toStringList(): List<String> =
            buildList {
                for (i in 0 until length()) {
                    add(stringify(opt(i)))
                }
            }

        private fun stringify(value: Any?): String =
            when (value) {
                null -> ""
                is String -> value
                is Boolean -> if (value) "Yes" else "No"
                is Number -> value.toString()
                else -> value.toString()
            }
    }
}
