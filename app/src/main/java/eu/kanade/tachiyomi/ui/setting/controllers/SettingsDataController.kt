package eu.kanade.tachiyomi.ui.setting.controllers

import eu.kanade.tachiyomi.ui.setting.SettingsComposeController
import karasu.presentation.settings.ComposableSettings
import karasu.presentation.settings.screen.SettingsDataScreen

class SettingsDataController : SettingsComposeController() {
    override fun getComposableSettings(): ComposableSettings = SettingsDataScreen
}
