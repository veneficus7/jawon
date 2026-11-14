package com.jawon.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Adjust
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material.icons.rounded.TouchApp
import androidx.compose.material.icons.rounded.Widgets
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
                    Tip("Extras → Sleep Timer", "Stops the music at the end of this record, or after a number of minutes."),
                    Tip("Its screen", "It dims a little in a dark room, and the battery at the top is your phone's own."),
                    Tip("Its colour", "Settings → Colour: silver, black or red.")
                )
            )
        )
    ),
    VINYL(
        title = "The vinyl room",
        summary = "The crate, the deck, turning a record over",
        icon = Icons.Rounded.Adjust,
        sections = listOf(
            GuideSection(
                "The crate",
                listOf(
                    Tip("Dig", "Drag up and down on the records to flip through them, as you would in a shop; you feel each one go by. Swipe sideways to jump to the next shelf — every shelf starts behind a card with its name on the tab."),
                    Tip("Put one on", "Tap the record in front. Its sleeve is laid by the deck, the record is drawn out and put on, and the needle comes down. Tap while that happens to hurry it."),
                    Tip("Back to the deck", "Tap the table at the top of the screen to go back up to the record that is on. The card at the back of the crate adds records.")
                )
            ),
            GuideSection(
                "The deck",
                listOf(
                    Tip("Scratch", "Put a finger on the record: it slows under your hand and follows it, forwards and backwards. Let go and it plays on."),
                    Tip("Choose a song", "The back of the sleeve, lying by the deck, is the tracklist. Touch a song and the needle is lifted and put down on it."),
                    Tip("Start · stop", "The button at the front left of the deck stops the record, and starts it again."),
                    Tip("Closer", "Pinch, or tap the table twice, to go from the whole deck to the record under the needle, and back.")
                )
            ),
            GuideSection(
                "Sides",
                listOf(
                    Tip("Turn it over", "At the end of a side the music stops, the needle goes home, and the sleeve says which side is next. Touch the record, draw your finger across it, or touch a song on the other side, and it is turned over and played. You can turn it over whenever you like."),
                    Tip("Let it turn itself", "Settings → Turn records over for me does it half a second after a side ends. And while Jawon is out of sight — in a pocket, behind another app — a record always plays straight through."),
                    Tip("A long album", "Your albums are cut into sides as a record would be: two, or four or six for a long one, never splitting a song. When a side ends on the first record, the next one is taken out of the sleeve for you.")
                )
            ),
            GuideSection(
                "Going back",
                listOf(
                    Tip("Back", "From the record to the whole deck, from the deck to the crate, from the crate to the showroom. ROOMS and SETTINGS are at the foot of the crate.")
                )
            )
        )
    ),
    CASSETTE(
        title = "The cassette room",
        summary = "The racks, the deck's keys, sides",
        icon = Icons.Rounded.Album,
        sections = listOf(
            GuideSection(
                "The racks",
                listOf(
                    Tip("Look along them", "Each shelf of yours is a rack of tapes on the wall, spines out. Drag up and down a rack; swipe sideways for the next shelf. You feel each tape go by."),
                    Tip("Put one in", "Tap a tape. It comes out of the rack, opens on top of the deck, and goes into the door — and PLAY goes down. Tap while that happens to hurry it."),
                    Tip("Add albums", "ADD, at the foot of the racks, beside ROOMS and SETTINGS.")
                )
            ),
            GuideSection(
                "The deck",
                listOf(
                    Tip("The keys", "EJECT · REW · PLAY · FF · STOP · PAUSE, under the door, as on a real deck. PLAY and PAUSE stay down."),
                    Tip("Skip a song", "Tap ▶▶ for the next song, or ◀◀ for the start of this one — or, in its first seconds, the one before. The tape winds there."),
                    Tip("Wind", "Hold ◀◀ or ▶▶. Playing, you hear the music going by, high and fast, and it plays on from where you let go. Stopped, it just winds."),
                    Tip("Choose a song", "The box stands open on the deck and the inside of its card is the tracklist. Touch a song and the tape winds to it."),
                    Tip("The reels and the counter", "The spools are where you are in the side — one empties as the other fills — and the counter counts the turns, as a deck's did."),
                    Tip("Closer", "Pinch, or tap the deck twice, to look at the tape up close.")
                )
            ),
            GuideSection(
                "Sides",
                listOf(
                    Tip("Auto-reverse", "With REV lit — the small button by the counter — the deck turns its head over at the end of side A and plays side B. Settings → Reverse at the end of a side is the same switch."),
                    Tip("Turn it over yourself", "With REV off the deck stops at the end of a side. Touch the tape, or press PLAY, and it is taken out, turned over and put back. EJECT, then a touch on the tape, does it any time."),
                    Tip("A long album", "An album is cut into two sides, and an album over two hours into more than one tape; the next tape goes in when a side ends.")
                )
            ),
            GuideSection(
                "Going back",
                listOf(
                    Tip("Back", "From the tape up close to the deck, from the deck to the racks, from the racks to the showroom."),
                    Tip("The sound of tape", "Settings → The sound of tape: a little hiss, wow and flutter on your music while this is your room. Turn it off for the music as it is.")
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
                    Tip("What a room is", "Each room is a different way to hold your music: a wall of CDs, a pocket player, a crate of records by a deck, and racks of tapes by a tape deck. Your library is the same in every one."),
                    Tip("Switch rooms", "Press ROOMS at the bottom of the CD room or of the record crate, or choose Rooms on the pocket player's menu, and pick one."),
                    Tip("Shake", "Shake the phone twice, quickly back and forth, to flip to the next room. It never happens in a pocket. Settings → Shake to switch rooms turns it off."),
                    Tip("The showroom", "Press Back in a room, tap the glowing JAWON sign, or pinch the pocket player, and you are in the showroom: every room on its own stand. Swipe along them and tap one to go in.")
                )
            ),
            GuideSection(
                "If it runs slowly or warm",
                listOf(
                    Tip("Graphics", "Settings → Graphics. Balanced is the normal setting. Light draws the rooms more simply and at most 30 times a second, for older phones or to save battery — and it comes on by itself when your phone's battery saver is on. Best looking is the full detail, for the newest phones."),
                    Tip("A room at rest", "Leave the wall alone for a little while and it slowly goes still, to save your battery. Touch it and it comes back to life."),
                    Tip("The intro", "Tap anywhere to skip it. Settings → Short intro opens straight into the room every time.")
                )
            )
        )
    ),
    OUTSIDE(
        title = "Without opening Jawon",
        summary = "The widget, your car, your voice",
        icon = Icons.Rounded.Widgets,
        sections = listOf(
            GuideSection(
                "On your home screen",
                listOf(
                    Tip("The widget", "Press and hold an empty part of your home screen, choose Widgets, and find Jawon. It shows the record that is on, with play, pause and next. Drag its edge to make it narrow and it becomes the case on its own."),
                    Tip("From the icon", "Press and hold Jawon's icon for three shortcuts: Resume the record you were on, play A record from your shelves, or Add music."),
                    Tip("The quick tile", "Pull down from the top of the screen twice, tap the pencil, and drag Jawon up among your tiles. One tap plays or pauses.")
                )
            ),
            GuideSection(
                "In the car, and by voice",
                listOf(
                    Tip("Android Auto", "Jawon appears in your car's music apps: your shelves, the records on them, and their songs. If it is missing, open Android Auto's settings on the phone, tap Version ten times, then choose Developer settings from the menu and turn on Unknown sources — Android Auto hides apps that were not installed from the Play Store."),
                    Tip("Ask for it", "Say “Play Loveless on Jawon” to your phone's assistant, with the name of a record, a song or an artist. Say only “Play music on Jawon” and it carries on with the record you were on.")
                )
            ),
            GuideSection(
                "Headphones",
                listOf(
                    Tip("Resume with headphones", "Off unless you turn it on in Settings → Playback. On: if your headphones cut out and come back within ten minutes, the music carries on; and pressing play on a headset, or connecting to a car that asks for music, puts your last record on even if Jawon was closed."),
                    Tip("Nothing starts by itself", "With that switch off, Jawon never plays unless you start it on the phone.")
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
                    Tip("Import a CD", "Plug in a USB CD drive and Jawon notices it. The screen draws what to connect and walks through five steps, from plugging in to the record on your shelf. Most drives need their own power lead."),
                    Tip("Open with Jawon", "In your Files app or your downloads, tap a song — or an album you bought as a ZIP — and choose Jawon. It is copied into the Music/Jawon folder on your phone, put on a shelf, and played."),
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
