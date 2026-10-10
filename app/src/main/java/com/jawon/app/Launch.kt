package com.jawon.app

import android.net.Uri

/**
 * The ways into Jawon that are not its icon (0.7.6): a shortcut held out of the icon, the widget,
 * the tile, a file another app hands over, a request spoken to the assistant.
 *
 * All of them arrive at `MainActivity` as an intent with one of these actions. The ones that only
 * start music are done there and then, through the player; the ones that need a screen become a
 * [LaunchRequest] for the stage.
 */
object Launch {
    /** Put the last record back on, where it stopped — or carry on with the one that is loaded. */
    const val ACTION_RESUME = "com.jawon.app.action.RESUME"

    /** A whole record, chosen for you. */
    const val ACTION_PLAY_SOMETHING = "com.jawon.app.action.PLAY_SOMETHING"

    /** Straight to Add music. */
    const val ACTION_ADD_MUSIC = "com.jawon.app.action.ADD_MUSIC"
}

/** What Jawon was opened to do that needs a screen to do it on. */
sealed interface LaunchRequest {
    data object AddMusic : LaunchRequest

    /** Music handed over by another app — "Open with Jawon", or shared to it. */
    data class Open(val uris: List<Uri>) : LaunchRequest
}
