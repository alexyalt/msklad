package ru.msklad.scanner.data

import android.content.Context

data class Settings(
    val login: String,
    val password: String,
    val moveInput: String,
    val printServer: String = "",
    val supplyInput: String = "",
    val lossInput: String = "",
)

class SettingsStore(context: Context) {
    private val prefs = context.applicationContext
        .getSharedPreferences("settings", Context.MODE_PRIVATE)

    fun load() = Settings(
        login = prefs.getString(KEY_LOGIN, "").orEmpty(),
        password = prefs.getString(KEY_PASSWORD, "").orEmpty(),
        moveInput = prefs.getString(KEY_MOVE, "").orEmpty(),
        printServer = prefs.getString(KEY_PRINT_SERVER, "").orEmpty(),
        supplyInput = prefs.getString(KEY_SUPPLY, "").orEmpty(),
        lossInput = prefs.getString(KEY_LOSS, "").orEmpty(),
    )

    fun save(settings: Settings) {
        prefs.edit()
            .putString(KEY_LOGIN, settings.login)
            .putString(KEY_PASSWORD, settings.password)
            .putString(KEY_MOVE, settings.moveInput)
            .putString(KEY_PRINT_SERVER, settings.printServer)
            .putString(KEY_SUPPLY, settings.supplyInput)
            .putString(KEY_LOSS, settings.lossInput)
            .apply()
    }

    private companion object {
        const val KEY_LOGIN = "login"
        const val KEY_PASSWORD = "password"
        const val KEY_MOVE = "move"
        const val KEY_PRINT_SERVER = "print_server"
        const val KEY_SUPPLY = "supply"
        const val KEY_LOSS = "loss"
    }
}
