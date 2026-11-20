package ru.msklad.scanner.data

import android.content.Context

data class Settings(val login: String, val password: String, val moveInput: String)

class SettingsStore(context: Context) {
    private val prefs = context.applicationContext
        .getSharedPreferences("settings", Context.MODE_PRIVATE)

    fun load() = Settings(
        login = prefs.getString(KEY_LOGIN, "").orEmpty(),
        password = prefs.getString(KEY_PASSWORD, "").orEmpty(),
        moveInput = prefs.getString(KEY_MOVE, "").orEmpty(),
    )

    fun save(settings: Settings) {
        prefs.edit()
            .putString(KEY_LOGIN, settings.login)
            .putString(KEY_PASSWORD, settings.password)
            .putString(KEY_MOVE, settings.moveInput)
            .apply()
    }

    private companion object {
        const val KEY_LOGIN = "login"
        const val KEY_PASSWORD = "password"
        const val KEY_MOVE = "move"
    }
}
