package me.leaver.rexpocket.ui.guide

import androidx.annotation.StringRes
import me.leaver.rexpocket.R
import me.leaver.rexpocket.core.BarKey

// Help's one list: what a person may want to do, where it is, and the control to point at. Adding a
// feature to Help is an entry here, its four strings, and Modifier.guideTarget on the control.
// GuideRegistryTest walks every entry and fails when its control is no longer where it says.
// See docs/design/help-guide.md.

/** What Help's list groups its entries under. */
internal enum class GuideSection(@StringRes val title: Int) {
    Home(R.string.guide_section_home),
    Terminal(R.string.guide_section_terminal),
    Agents(R.string.guide_section_agents),
    Gestures(R.string.guide_section_gestures),
    Keys(R.string.guide_section_keys),
    Notifications(R.string.guide_section_notifications),
    Tailnet(R.string.guide_section_tailnet),
    Settings(R.string.guide_section_settings),
}

/**
 * Where a control lives: the screen, and the sheet on it the guide opens. [needsHost] places are a
 * host's own (its sheet, its page, its New tab); [terminal] places need a tab on screen.
 */
internal enum class GuidePlace(val terminal: Boolean = false, val needsHost: Boolean = false) {
    Home,
    HomeMenu,
    Settings,
    Notifications,
    Controls,
    Security,
    HostSheet(needsHost = true),
    HostPage(needsHost = true),
    NewTab(needsHost = true),
    Terminal(terminal = true),
    TermMenu(terminal = true),
}

/** A control Help can point at; the control carries `Modifier.guideTarget(spot)`. */
internal enum class GuideSpot {
    // The home and its ⋮.
    HomeNeedsYou, HomeActivity, HomeFilters, HomeSearch, HomeNew, HomeSettings, HomeMenu, HostActions,
    MenuAddHost, MenuImportHosts, MenuHelp,

    // Settings and the sheets it opens.
    SetTailnet, SetQuickReplies, SetFont, SetAppearance, SetBackup, SetUpdates, SetDiagnostics, SetHelp,
    AlertsSwitch, GestureTap, ArrangeKeys, AppLock, BlockScreenshots,

    // A host's sheet, its page and its New tab.
    HostEdit, HostNotify, HostTile, TemplateAdd,

    // The terminal, its key bar and its ⋮ menu.
    Pane, TermTitle, KeyReturn, KeyInsertPath, KeyCtrl,
    TermPalette, TermFind, TermShortcuts, TermSendFile, TermFullScreen, TermCompose, TermTextSize,
    TermSplit, TermCopyOutput, TermInsertPath, TermNewTab, TermMuteTab, TermHelp,
}

/** One thing a person may want to do: its words, and the [spot] in the [place] that does it. */
internal class GuideEntry(
    val id: String,
    val section: GuideSection,
    @StringRes val title: Int,
    /** One line: where it is and how, the spotlight's caption and the list row's second line. */
    @StringRes val hint: Int,
    /** Two or three sentences on the entry's page. */
    @StringRes val body: Int,
    /** Words people may search with that the title does not have, space separated. */
    @StringRes val keywords: Int,
    val place: GuidePlace,
    val spot: GuideSpot,
)

/** One step of the quick tour: a control on the home, and what to say about it. */
internal class TourStep(val spot: GuideSpot, @StringRes val title: Int, @StringRes val hint: Int)

private fun entry(id: String, section: GuideSection, title: Int, hint: Int, body: Int, keywords: Int, place: GuidePlace, spot: GuideSpot) =
    GuideEntry(id, section, title, hint, body, keywords, place, spot)

internal val GUIDE_ENTRIES: List<GuideEntry> = listOf(
    // Home and hosts.
    entry("add_host", GuideSection.Home, R.string.guide_add_host_title, R.string.guide_add_host_hint, R.string.guide_add_host_body, R.string.guide_add_host_keys, GuidePlace.HomeMenu, GuideSpot.MenuAddHost),
    entry("host_link", GuideSection.Home, R.string.guide_host_link_title, R.string.guide_host_link_hint, R.string.guide_host_link_body, R.string.guide_host_link_keys, GuidePlace.HomeMenu, GuideSpot.MenuImportHosts),
    entry("needs_you", GuideSection.Home, R.string.guide_needs_you_title, R.string.guide_needs_you_hint, R.string.guide_needs_you_body, R.string.guide_needs_you_keys, GuidePlace.Home, GuideSpot.HomeNeedsYou),
    entry("activity", GuideSection.Home, R.string.guide_activity_title, R.string.guide_activity_hint, R.string.guide_activity_body, R.string.guide_activity_keys, GuidePlace.Home, GuideSpot.HomeActivity),
    entry("search", GuideSection.Home, R.string.guide_search_title, R.string.guide_search_hint, R.string.guide_search_body, R.string.guide_search_keys, GuidePlace.Home, GuideSpot.HomeSearch),
    entry("new_session", GuideSection.Home, R.string.guide_new_session_title, R.string.guide_new_session_hint, R.string.guide_new_session_body, R.string.guide_new_session_keys, GuidePlace.Home, GuideSpot.HomeNew),
    entry("row_actions", GuideSection.Home, R.string.guide_row_actions_title, R.string.guide_row_actions_hint, R.string.guide_row_actions_body, R.string.guide_row_actions_keys, GuidePlace.Home, GuideSpot.HostActions),
    entry("edit_host", GuideSection.Home, R.string.guide_edit_host_title, R.string.guide_edit_host_hint, R.string.guide_edit_host_body, R.string.guide_edit_host_keys, GuidePlace.HostSheet, GuideSpot.HostEdit),
    entry("templates", GuideSection.Home, R.string.guide_templates_title, R.string.guide_templates_hint, R.string.guide_templates_body, R.string.guide_templates_keys, GuidePlace.NewTab, GuideSpot.TemplateAdd),
    entry("host_quiet", GuideSection.Home, R.string.guide_host_quiet_title, R.string.guide_host_quiet_hint, R.string.guide_host_quiet_body, R.string.guide_host_quiet_keys, GuidePlace.HostPage, GuideSpot.HostNotify),
    entry("tile", GuideSection.Home, R.string.guide_tile_title, R.string.guide_tile_hint, R.string.guide_tile_body, R.string.guide_tile_keys, GuidePlace.HostPage, GuideSpot.HostTile),

    // The terminal.
    entry("text_size", GuideSection.Terminal, R.string.guide_text_size_title, R.string.guide_text_size_hint, R.string.guide_text_size_body, R.string.guide_text_size_keys, GuidePlace.TermMenu, GuideSpot.TermTextSize),
    entry("palette", GuideSection.Terminal, R.string.guide_palette_title, R.string.guide_palette_hint, R.string.guide_palette_body, R.string.guide_palette_keys, GuidePlace.TermMenu, GuideSpot.TermPalette),
    entry("rex_actions", GuideSection.Terminal, R.string.guide_rex_actions_title, R.string.guide_rex_actions_hint, R.string.guide_rex_actions_body, R.string.guide_rex_actions_keys, GuidePlace.TermMenu, GuideSpot.TermPalette),
    entry("insert_path", GuideSection.Terminal, R.string.guide_insert_path_title, R.string.guide_insert_path_hint, R.string.guide_insert_path_body, R.string.guide_insert_path_keys, GuidePlace.TermMenu, GuideSpot.TermInsertPath),
    entry("send_file", GuideSection.Terminal, R.string.guide_send_file_title, R.string.guide_send_file_hint, R.string.guide_send_file_body, R.string.guide_send_file_keys, GuidePlace.TermMenu, GuideSpot.TermSendFile),
    entry("find", GuideSection.Terminal, R.string.guide_find_title, R.string.guide_find_hint, R.string.guide_find_body, R.string.guide_find_keys, GuidePlace.TermMenu, GuideSpot.TermFind),
    entry("split", GuideSection.Terminal, R.string.guide_split_title, R.string.guide_split_hint, R.string.guide_split_body, R.string.guide_split_keys, GuidePlace.TermMenu, GuideSpot.TermSplit),
    entry("switch_tab", GuideSection.Terminal, R.string.guide_switch_tab_title, R.string.guide_switch_tab_hint, R.string.guide_switch_tab_body, R.string.guide_switch_tab_keys, GuidePlace.Terminal, GuideSpot.TermTitle),
    entry("new_tab", GuideSection.Terminal, R.string.guide_new_tab_title, R.string.guide_new_tab_hint, R.string.guide_new_tab_body, R.string.guide_new_tab_keys, GuidePlace.TermMenu, GuideSpot.TermNewTab),
    entry("copy_output", GuideSection.Terminal, R.string.guide_copy_output_title, R.string.guide_copy_output_hint, R.string.guide_copy_output_body, R.string.guide_copy_output_keys, GuidePlace.TermMenu, GuideSpot.TermCopyOutput),
    entry("full_screen", GuideSection.Terminal, R.string.guide_full_screen_title, R.string.guide_full_screen_hint, R.string.guide_full_screen_body, R.string.guide_full_screen_keys, GuidePlace.TermMenu, GuideSpot.TermFullScreen),
    entry("shortcuts", GuideSection.Terminal, R.string.guide_shortcuts_title, R.string.guide_shortcuts_hint, R.string.guide_shortcuts_body, R.string.guide_shortcuts_keys, GuidePlace.TermMenu, GuideSpot.TermShortcuts),

    // Answering agents.
    entry("approve", GuideSection.Agents, R.string.guide_approve_title, R.string.guide_approve_hint, R.string.guide_approve_body, R.string.guide_approve_keys, GuidePlace.Home, GuideSpot.HomeNeedsYou),
    entry("composer", GuideSection.Agents, R.string.guide_composer_title, R.string.guide_composer_hint, R.string.guide_composer_body, R.string.guide_composer_keys, GuidePlace.TermMenu, GuideSpot.TermCompose),
    entry("quick_replies", GuideSection.Agents, R.string.guide_quick_replies_title, R.string.guide_quick_replies_hint, R.string.guide_quick_replies_body, R.string.guide_quick_replies_keys, GuidePlace.Settings, GuideSpot.SetQuickReplies),
    entry("mute_tab", GuideSection.Agents, R.string.guide_mute_tab_title, R.string.guide_mute_tab_hint, R.string.guide_mute_tab_body, R.string.guide_mute_tab_keys, GuidePlace.TermMenu, GuideSpot.TermMuteTab),

    // Gestures and long presses: each on the control it is made on.
    entry("pinch", GuideSection.Gestures, R.string.guide_pinch_title, R.string.guide_pinch_hint, R.string.guide_pinch_body, R.string.guide_pinch_keys, GuidePlace.Terminal, GuideSpot.Pane),
    entry("swipe_panes", GuideSection.Gestures, R.string.guide_swipe_panes_title, R.string.guide_swipe_panes_hint, R.string.guide_swipe_panes_body, R.string.guide_swipe_panes_keys, GuidePlace.Terminal, GuideSpot.Pane),
    entry("long_press_select", GuideSection.Gestures, R.string.guide_long_press_select_title, R.string.guide_long_press_select_hint, R.string.guide_long_press_select_body, R.string.guide_long_press_select_keys, GuidePlace.Terminal, GuideSpot.Pane),
    entry("double_tap", GuideSection.Gestures, R.string.guide_double_tap_title, R.string.guide_double_tap_hint, R.string.guide_double_tap_body, R.string.guide_double_tap_keys, GuidePlace.Terminal, GuideSpot.Pane),
    entry("hold_return", GuideSection.Gestures, R.string.guide_hold_return_title, R.string.guide_hold_return_hint, R.string.guide_hold_return_body, R.string.guide_hold_return_keys, GuidePlace.Terminal, GuideSpot.KeyReturn),
    entry("hold_ctrl", GuideSection.Gestures, R.string.guide_hold_ctrl_title, R.string.guide_hold_ctrl_hint, R.string.guide_hold_ctrl_body, R.string.guide_hold_ctrl_keys, GuidePlace.Terminal, GuideSpot.KeyCtrl),
    entry("gesture_settings", GuideSection.Gestures, R.string.guide_gesture_settings_title, R.string.guide_gesture_settings_hint, R.string.guide_gesture_settings_body, R.string.guide_gesture_settings_keys, GuidePlace.Controls, GuideSpot.GestureTap),

    // The key bar.
    entry("insert_path_key", GuideSection.Keys, R.string.guide_insert_path_key_title, R.string.guide_insert_path_key_hint, R.string.guide_insert_path_key_body, R.string.guide_insert_path_key_keys, GuidePlace.Terminal, GuideSpot.KeyInsertPath),
    entry("arrange_keys", GuideSection.Keys, R.string.guide_arrange_keys_title, R.string.guide_arrange_keys_hint, R.string.guide_arrange_keys_body, R.string.guide_arrange_keys_keys, GuidePlace.Controls, GuideSpot.ArrangeKeys),

    // Notifications.
    entry("alerts", GuideSection.Notifications, R.string.guide_alerts_title, R.string.guide_alerts_hint, R.string.guide_alerts_body, R.string.guide_alerts_keys, GuidePlace.Notifications, GuideSpot.AlertsSwitch),

    // The tailnet.
    entry("tailnet", GuideSection.Tailnet, R.string.guide_tailnet_title, R.string.guide_tailnet_hint, R.string.guide_tailnet_body, R.string.guide_tailnet_keys, GuidePlace.Settings, GuideSpot.SetTailnet),

    // Settings and security.
    entry("app_lock", GuideSection.Settings, R.string.guide_app_lock_title, R.string.guide_app_lock_hint, R.string.guide_app_lock_body, R.string.guide_app_lock_keys, GuidePlace.Security, GuideSpot.AppLock),
    entry("screenshots", GuideSection.Settings, R.string.guide_screenshots_title, R.string.guide_screenshots_hint, R.string.guide_screenshots_body, R.string.guide_screenshots_keys, GuidePlace.Security, GuideSpot.BlockScreenshots),
    entry("backup", GuideSection.Settings, R.string.guide_backup_title, R.string.guide_backup_hint, R.string.guide_backup_body, R.string.guide_backup_keys, GuidePlace.Settings, GuideSpot.SetBackup),
    entry("appearance", GuideSection.Settings, R.string.guide_appearance_title, R.string.guide_appearance_hint, R.string.guide_appearance_body, R.string.guide_appearance_keys, GuidePlace.Settings, GuideSpot.SetAppearance),
    entry("font", GuideSection.Settings, R.string.guide_font_title, R.string.guide_font_hint, R.string.guide_font_body, R.string.guide_font_keys, GuidePlace.Settings, GuideSpot.SetFont),
    entry("updates", GuideSection.Settings, R.string.guide_updates_title, R.string.guide_updates_hint, R.string.guide_updates_body, R.string.guide_updates_keys, GuidePlace.Settings, GuideSpot.SetUpdates),
    entry("diagnostics", GuideSection.Settings, R.string.guide_diagnostics_title, R.string.guide_diagnostics_hint, R.string.guide_diagnostics_body, R.string.guide_diagnostics_keys, GuidePlace.Settings, GuideSpot.SetDiagnostics),
)

/** The quick tour, on the home: a step whose control is not there is skipped. */
internal val TOUR_STEPS: List<TourStep> = listOf(
    TourStep(GuideSpot.HomeFilters, R.string.guide_tour_filters_title, R.string.guide_tour_filters_hint),
    TourStep(GuideSpot.HostActions, R.string.guide_tour_rows_title, R.string.guide_tour_rows_hint),
    TourStep(GuideSpot.HomeNew, R.string.guide_tour_new_title, R.string.guide_tour_new_hint),
    TourStep(GuideSpot.HomeSettings, R.string.guide_tour_settings_title, R.string.guide_tour_settings_hint),
    TourStep(GuideSpot.HomeMenu, R.string.guide_tour_help_title, R.string.guide_tour_help_hint),
)

/** The rows that open Help itself: tagged, though no entry points at them, for a test to find. */
internal val GUIDE_ENTRY_POINTS: Set<GuideSpot> = setOf(GuideSpot.MenuHelp, GuideSpot.SetHelp, GuideSpot.TermHelp)

internal fun guideEntry(id: String): GuideEntry? = GUIDE_ENTRIES.firstOrNull { it.id == id }

/** The spot of a key of the key bar, for the entries about holding one. */
internal fun guideSpotOf(key: BarKey): GuideSpot? = when (key) {
    BarKey.Enter -> GuideSpot.KeyReturn
    BarKey.InsertPath -> GuideSpot.KeyInsertPath
    BarKey.Ctrl -> GuideSpot.KeyCtrl
    else -> null
}
