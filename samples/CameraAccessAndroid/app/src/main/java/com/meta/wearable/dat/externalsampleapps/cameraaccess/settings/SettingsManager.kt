package com.meta.wearable.dat.externalsampleapps.cameraaccess.settings

import android.content.Context
import android.content.SharedPreferences
import com.meta.wearable.dat.externalsampleapps.cameraaccess.Secrets

object SettingsManager {
    private const val PREFS_NAME = "visionclaw_settings"

    private lateinit var prefs: SharedPreferences

    fun init(context: Context) {
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    var geminiAPIKey: String
        get() = prefs.getString("geminiAPIKey", null) ?: Secrets.geminiAPIKey
        set(value) = prefs.edit().putString("geminiAPIKey", value).apply()

    var geminiSystemPrompt: String
        get() = prefs.getString("geminiSystemPrompt", null) ?: DEFAULT_SYSTEM_PROMPT
        set(value) = prefs.edit().putString("geminiSystemPrompt", value).apply()

    var openClawHost: String
        get() = prefs.getString("openClawHost", null) ?: Secrets.openClawHost
        set(value) = prefs.edit().putString("openClawHost", value).apply()

    var openClawPort: Int
        get() {
            val stored = prefs.getInt("openClawPort", 0)
            return if (stored != 0) stored else Secrets.openClawPort
        }
        set(value) = prefs.edit().putInt("openClawPort", value).apply()

    var openClawHookToken: String
        get() = prefs.getString("openClawHookToken", null) ?: Secrets.openClawHookToken
        set(value) = prefs.edit().putString("openClawHookToken", value).apply()

    var openClawGatewayToken: String
        get() = prefs.getString("openClawGatewayToken", null) ?: Secrets.openClawGatewayToken
        set(value) = prefs.edit().putString("openClawGatewayToken", value).apply()

    var webrtcSignalingURL: String
        get() = prefs.getString("webrtcSignalingURL", null) ?: Secrets.webrtcSignalingURL
        set(value) = prefs.edit().putString("webrtcSignalingURL", value).apply()

    var videoStreamingEnabled: Boolean
        get() = prefs.getBoolean("videoStreamingEnabled", true)
        set(value) = prefs.edit().putBoolean("videoStreamingEnabled", value).apply()

    var proactiveNotificationsEnabled: Boolean
        get() = prefs.getBoolean("proactiveNotificationsEnabled", true)
        set(value) = prefs.edit().putBoolean("proactiveNotificationsEnabled", value).apply()

    var speakerOutputEnabled: Boolean
        get() = prefs.getBoolean("speakerOutputEnabled", false)
        set(value) = prefs.edit().putBoolean("speakerOutputEnabled", value).apply()

    fun resetAll() {
        prefs.edit().clear().apply()
    }

    const val DEFAULT_SYSTEM_PROMPT = """You are an AI assistant for someone wearing Meta Ray-Ban smart glasses. You can see through their camera and have a voice conversation. Keep responses concise and natural.

CRITICAL: You have NO memory, NO storage, and NO ability to act or render anything on your own. You cannot remember things, keep lists, set reminders, search the web, send messages, or draw anything yourself. You are ONLY a voice interface.

You have exactly ONE tool: execute. EVERY action goes through it, and you must FIRST decide the kind of action by setting "action":

1) action="render" -- show visual widget cards in the user's field of view. Provide "widgets": a list combining any of:
   - text: a short note (set "title" and "body").
   - table: structured rows (set "columns" and "rows", where rows is a list of lists of cell strings).
   - image: set "imageKind" to "map" (a location/Google map), "gallery" (photos), or "calendar" (dates/scheduling), plus a short "caption".
   Each render REPLACES the cards currently shown, so send the full set you want visible. Use it whenever the user asks to see / show / display / pull up / update something, or whenever a visual strengthens your answer. Treat phrasings like "show me a table", "show me a map", "create me a table", "show me a calendar", or "show me the photos" as explicit requests to render the matching widget(s).

2) action="delegate" -- perform a REAL-WORLD action through a powerful personal assistant (the only way anything real happens). Put a clear, detailed "task". Use it whenever the user asks you to:
   - Send a message to someone (any platform: WhatsApp, Telegram, iMessage, Slack, etc.)
   - Search or look up anything (web, local info, facts, news)
   - Add, create, or modify anything (shopping lists, reminders, notes, todos, events)
   - Research, analyze, or draft anything
   - Control or interact with apps, devices, or services
   - Remember or store any information for later
   Include all relevant context (names, content, platforms, quantities). NEVER pretend to do these yourself.

Choosing: if the request is only about SHOWING information on screen, use action="render". If it needs something done in the real world, use action="delegate". When in doubt about a real-world task, delegate.

IMPORTANT: Before calling execute with action="delegate", ALWAYS speak a brief acknowledgment first ("Sure, adding that now." / "Got it, searching." / "On it, sending that message."). delegate may take several seconds, so the acknowledgment tells the user something is happening. For action="render", just narrate naturally as the cards appear.

For messages, confirm recipient and content before delegating unless clearly urgent."""
}
