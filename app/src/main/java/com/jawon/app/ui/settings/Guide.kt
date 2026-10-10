package com.jawon.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material.icons.rounded.TouchApp
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jawon.app.ui.theme.InterFamily
import com.jawon.app.ui.theme.LabelCream
import com.jawon.app.ui.theme.LabelDim

/**
 * **The Guide** (0.7.4) — how to use what Jawon does that no other player does, in plain words.
 *
 * Asked for by the owner the day the pocket player's sensors shipped: "now im struggling to figure
 * out what tilt & turn means". Jawon's best things are gestures — turning a disc, turning the phone,
 * swiping a bar — and a gesture nobody knows about is a feature nobody has. Each topic is a page
 * under Settings → Guide, in the same rows and cards as the rest of Settings; the pocket player's
 * own Settings has a Guide row that opens straight onto its page.
 *
 * Words only, kept short: what to do, then what happens. When a feature changes, its tip changes
 * in the same commit, the way a feature's privacy line does.
 */
internal enum class GuideTopic(
    val title: String,
    val summary: String,
    val icon: ImageVector,
    val sections: List<GuideSection>
) {
    CD(
        title = "The CD room",
        summary = "Opening records, scratching, the bar",
        icon = Icons.Rounded.Album,
        sections = listOf(
            GuideSection(
                "Records",
                listOf(
                    Tip("Open a record", "Tap a case. It comes off the shelf, opens, and the disc lifts out and starts to play. Back, or a tap above the deck, puts it away."),
                    Tip("Browse", "Swipe the wall to move along a shelf or between shelves; it settles on the nearest record with a click you can feel. Pinch to step back and see more of the wall."),
                    Tip("Find one", "FIND at the bottom of the room searches your records by title or artist and turns the wall to the one you pick."),
                    Tip("Edit or remove", "Press and hold a case. Remove takes it off the shelf — your music files are never touched — and you have a few seconds to UNDO. Edit this album changes its title, artist or cover.")
                )
            ),
            GuideSection(
                "The disc",
                listOf(
                    Tip("Scratch", "Put a finger on the spinning disc and turn it: forwards or backwards through the song, the sound following your hand, a tick at every step."),
                    Tip("Pause with a finger", "Hold the disc still and the music pauses; let go and it plays on.")
                )
            ),
            GuideSection(
                "The deck",
                listOf(
                    Tip("Make it bigger or smaller", "Drag the top of the deck up for the whole tracklist, down to watch the disc."),
                    Tip("Its three pages", "Swipe sideways on the deck's top for TRACKS, SOUND (the effects and the equaliser) and LYRICS. The moon sets a sleep timer.")
                )
            ),
            GuideSection(
                "While you browse",
                listOf(
                    Tip("The record keeps playing", "Close a case and the music goes on; its case stands out from the shelf and a bar at the bottom shows the song. Settings → Playback can make closing a case pause instead."),
                    Tip("The bar", "Tap it to open the record again. Swipe it sideways to change track. Swipe it down to stop and put the record away.")
                )
            )
        )
    ),
    POCKET(
        title = "The pocket player",
        summary = "The wheel, Tilt & Turn, Hold, Brick",
        icon = Icons.Rounded.TouchApp,
        sections = listOf(
            GuideSection(
                "The wheel",
                listOf(
                    Tip("Move", "Slide your thumb round the wheel to move up and down a list, and to change the volume on Now Playing."),
                    Tip("Choose and go back", "Press the centre button to choose. Press MENU, at the top of the wheel, to go back; hold MENU to jump to the main menu."),
                    Tip("Play", "▶‖ at the bottom plays and pauses — hold it to switch the player off. ◀◀ and ▶▶ change track; hold them to rewind or fast-forward."),
                    Tip("Now Playing", "Press the centre once to scrub through the song with the wheel, twice for the lyrics, and a third time to go back.")
                )
            ),
            GuideSection(
                "Tilt & Turn",
                listOf(
                    Tip("Turn it sideways", "Turn your phone on its side and your covers fill the whole screen as Cover Flow. Swipe through them, tap the middle one to see its songs, and tap a song to play it. Turn the phone back upright to return to the player."),
                    Tip("Lean it", "In the player's own Cover Flow, lean the phone a little to the left or right and the covers drift that way; lean further and it moves to the next record, and keeps going while you hold the lean."),
                    Tip("Switch it off", "In the player: Settings → Tilt & Turn → Off — handy if you read lying on your side.")
                )
            ),
            GuideSection(
                "Hold",
                listOf(
                    Tip("In a pocket", "When the top of the phone is covered — in a pocket, or lying face down — the wheel locks so nothing gets pressed by accident, and a padlock shows at the top of the screen. Take it out and it unlocks.")
                )
            ),
            GuideSection(
                "More on its menu",
                listOf(
                    Tip("Search", "Turn to a letter and press the centre to type it. ◀◀ deletes a letter, ▶▶ goes up to what was found, MENU comes back to the letters."),
                    Tip("Shuffle Albums", "Plays a whole record, picked at random."),
                    Tip("Extras → Brick", "The classic game. Press the centre to serve; move the paddle with the wheel, or by leaning the phone."),
                    Tip("Its screen", "It dims a little in a dark room, and the battery at the top is your phone's own."),
                    Tip("Its colour", "Settings → Colour: silver, black or red.")
                )
            )
        )
    ),
    ROOMS(
        title = "Moving between rooms",
        summary = "Rooms, shaking, the showroom",
        icon = Icons.Rounded.SwapHoriz,
        sections = listOf(
            GuideSection(
                "Rooms",
                listOf(
                    Tip("What a room is", "Each room is a different way to hold your music: a wall of CDs, a pocket player, and in time a record crate and a tape deck. Your library is the same in every one."),
                    Tip("Switch rooms", "Press ROOMS at the bottom of the CD room, or choose Rooms on the pocket player's menu, and pick one."),
                    Tip("Shake", "Shake the phone twice, quickly back and forth, to flip to the next room. It never happens in a pocket. Settings → Shake to switch rooms turns it off."),
                    Tip("The showroom", "Press Back in a room, tap the glowing JAWON sign, or pinch the pocket player, and you are in the showroom: every room on its own stand. Swipe along them and tap one to go in.")
                )
            )
        )
    ),
    MUSIC(
        title = "Your music",
        summary = "Adding, singles, keeping it safe",
        icon = Icons.Rounded.LibraryMusic,
        sections = listOf(
            GuideSection(
                "Adding music",
                listOf(
                    Tip("Scan this phone", "Finds the music already on your phone. Tick what you want on your shelves. Songs that have no album of their own are listed as Singles, under the albums."),
                    Tip("Choose a folder", "For music on a memory card or in cloud storage, which a scan cannot see."),
                    Tip("Import a CD", "With a USB CD drive plugged in, Jawon reads the disc, finds what it is, and puts it on a shelf."),
                    Tip("The record shop", "Free live recordings and releases from the Internet Archive.")
                )
            ),
            GuideSection(
                "Keeping it tidy",
                listOf(
                    Tip("Shelves", "SHELVES at the bottom of the CD room arranges your shelves. On the pocket player they are its playlists."),
                    Tip("Back it up", "Settings → Back up and restore saves your shelves, your edits and your listening to a file you keep.")
                )
            )
        )
    )
}

internal class GuideSection(val heading: String, val tips: List<Tip>)

internal class Tip(val title: String, val body: String)

/** One topic's page: its sections as Settings groups, a tip to a row. */
@Composable
internal fun ColumnScope.GuideTopicContent(topic: GuideTopic) {
    topic.sections.forEach { section ->
        SettingsGroup(header = section.heading) {
            section.tips.forEachIndexed { i, tip ->
                if (i > 0) SettingsDivider()
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = tip.title,
                        fontFamily = InterFamily,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 15.sp,
                        color = LabelCream
                    )
                    Text(
                        text = tip.body,
                        fontFamily = InterFamily,
                        fontSize = 14.sp,
                        lineHeight = 20.sp,
                        color = LabelDim
                    )
                }
            }
        }
    }
}
