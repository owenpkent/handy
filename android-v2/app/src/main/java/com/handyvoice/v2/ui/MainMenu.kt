package com.handyvoice.v2.ui

import android.app.Activity
import android.content.Intent
import android.view.View
import android.widget.PopupMenu
import com.handyvoice.v2.R

/**
 * Shared overflow menu — jumps between the main Control screen (which now
 * hosts Voice + Manual as tabs) and Settings. The current screen's own
 * entry is hidden, and leaving a non-Control screen via this menu finishes
 * the source activity so the back stack stays a single hop deep.
 */
private const val MENU_VOICE = 1
private const val MENU_SETTINGS = 3

fun Activity.showMainMenu(anchor: View) {
    val menu = PopupMenu(this, anchor)
    val current = this::class.java
    var order = 0
    if (current != ControlActivity::class.java) {
        menu.menu.add(0, MENU_VOICE, order++, R.string.menu_voice)
    }
    if (current != SettingsActivity::class.java) {
        menu.menu.add(0, MENU_SETTINGS, order++, R.string.menu_settings)
    }
    menu.setOnMenuItemClickListener { item ->
        val target: Class<*> = when (item.itemId) {
            MENU_VOICE -> ControlActivity::class.java
            MENU_SETTINGS -> SettingsActivity::class.java
            else -> return@setOnMenuItemClickListener false
        }
        val intent = Intent(this, target)
        if (target == ControlActivity::class.java) {
            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        startActivity(intent)
        if (current != ControlActivity::class.java) finish()
        true
    }
    menu.show()
}
