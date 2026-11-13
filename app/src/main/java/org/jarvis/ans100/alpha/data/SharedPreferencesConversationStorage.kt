package org.jarvis.ans100.alpha.data

import android.content.Context

class SharedPreferencesConversationStorage(context: Context) : ConversationStorage {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE,
    )

    override fun read(): String? = preferences.getString(STATE_KEY, null)

    override fun write(value: String): Boolean =
        preferences.edit().putString(STATE_KEY, value).commit()

    private companion object {
        const val PREFERENCES_NAME = "jarvis_android_conversation_v1"
        const val STATE_KEY = "state"
    }
}
