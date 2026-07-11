package com.baita.renaplay.browse

data class SettingsAction(val id: String, val label: String)

object SettingsActionIds {
    const val OPEN_SETTINGS = "open_settings"
    const val CHANGE_SERVER = "change_server"
}
