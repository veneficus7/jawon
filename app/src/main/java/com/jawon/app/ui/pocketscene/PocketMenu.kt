package com.jawon.app.ui.pocketscene

/**
 * The device's menus: what each screen lists, and where the highlight is on it.
 *
 * Pure, like [ClickWheel]. A level of the menu is stored as *what it is* — [MenuKey] — and never
 * as the rows it showed when it was opened, so a list stays right while the library changes
 * under it: an album imported with the Albums screen open appears in it.
 *
 * The shape is the classic's, cut to what Jawon is. There is no Songs list across the whole
 * library and no shuffle of everything, because the album is the unit here (PLAN §2) — a song
 * list is only ever the inside of one album, and the classic's Shuffle Songs is **Shuffle
 * Albums**: a whole record, chosen at random. **Shelves are the playlists**, in the order they
 * are arranged, so the curation done on the wall survives in a pocket.
 */
sealed interface MenuKey {
    data object Main : MenuKey
    /** The albums as covers, turned through in perspective — the classic's Cover Flow. */
    data object CoverFlow : MenuKey
    /** One cover in Cover Flow turned over, its tracks listed on the back. */
    data class Flipped(val albumId: String) : MenuKey
    data object Albums : MenuKey
    data object Shelves : MenuKey
    data object Artists : MenuKey
    data class Shelf(val shelfId: String) : MenuKey
    data class Artist(val name: String) : MenuKey
    data class Album(val albumId: String) : MenuKey
    data object NowPlaying : MenuKey
    /** The device's own Settings — the classic's, cut to what Jawon has behind it. */
    data object Settings : MenuKey
    data object About : MenuKey
    /** One setting's choices, the current one ticked. */
    data class Choice(val setting: PocketSetting) : MenuKey
    /** The rack, one effect a row — see [PocketSound]. */
    data object Sound : MenuKey
    /** One effect's slider, which the wheel turns. */
    data class Adjust(val control: SoundControl) : MenuKey
    /** The classic's Extras: here, its game. */
    data object Extras : MenuKey
    /** Brick — see [BrickGame]. The wheel moves the paddle; so does leaning the phone. */
    data object Brick : MenuKey
}

/** The settings the device's own menu can change. */
enum class PocketSetting(val title: String) {
    EQ("EQ"),
    SOUND_CHECK("Sound Check"),
    BACKLIGHT("Backlight"),
    CLICKER("Clicker"),
    REVERB("Reverb"),
    FINISH("Colour"),
    /** Turning the phone sideways for Cover Flow, and leaning it to browse the covers. */
    MOTION("Tilt & Turn")
}

/** What choosing a row does. */
sealed interface RowAction {
    data class Open(val key: MenuKey) : RowAction
    /** Play [albumId] from its [index]th track. */
    data class PlayTrack(val albumId: String, val index: Int) : RowAction
    data object AddAlbums : RowAction
    /** The room switcher: the other formats, a tap away (0.7.2). */
    data object OpenRooms : RowAction
    /** The phone app's own Settings, everything the device's menu does not carry. */
    data object OpenSettings : RowAction
    /** Pick option [option] of [setting]. */
    data class Choose(val setting: PocketSetting, val option: Int) : RowAction
    /** A line of information that goes nowhere — About's rows. */
    data object Nothing : RowAction
    /** Every effect back to where it does nothing. */
    data object FlatSound : RowAction
    /** A whole record, chosen at random — the classic's Shuffle Songs, with the album as the unit. */
    data object ShuffleAlbums : RowAction
}

data class MenuRow(
    val label: String,
    val action: RowAction,
    /** A current value shown at the right of the row, as the classic's Settings did. */
    val value: String? = null,
    /** The ticked choice in a list of choices. */
    val checked: Boolean = false
) {
    /** Whether the row leads to another list — the chevron. A track plays instead. */
    val leadsOn: Boolean
        get() = action is RowAction.Open || action == RowAction.AddAlbums ||
            action == RowAction.OpenSettings || action == RowAction.OpenRooms
}

/**
 * What the Settings menu shows: every current value, and the choices each setting offers.
 * Built by the room from the preferences and the player; kept here so the menus stay pure.
 */
data class PocketSettings(
    /** "Off", then the phone's own equaliser presets. */
    val eqOptions: List<String> = listOf("Off"),
    /** Which of [eqOptions] is on, or −1 when the bands have been set by hand in the rack. */
    val eqChosen: Int = 0,
    /** Index into [SOUND_CHECK_OPTIONS]. */
    val soundCheck: Int = 0,
    val backlightMs: Long = 10_000L,
    val clicker: Boolean = true,
    val albums: Int = 0,
    val songs: Int = 0,
    val shelves: Int = 0,
    val version: String = "",
    /** Where each of the rack's faders sits. */
    val sound: Map<SoundControl, Float> = emptyMap(),
    /** Reverb's choices ("Off" first) and which is on. */
    val reverbOptions: List<String> = listOf("Off"),
    val reverb: Int = 0,
    /** Which of `PocketFinish`'s colours the device is — which one you bought. */
    val finish: Int = 0,
    /** Tilt & Turn: sideways Cover Flow, and leaning through the covers. */
    val motion: Boolean = true
) {
    companion object {
        /** Sound Check's choices — Jawon's ReplayGain modes, in `ReplayGain.Mode` order of meaning. */
        val SOUND_CHECK_OPTIONS = listOf("Off", "Album", "Track")

        /** The classic's backlight timer, with 0 standing for Always On. */
        val BACKLIGHT_OPTIONS_MS = listOf(2_000L, 5_000L, 10_000L, 15_000L, 20_000L, 30_000L, 0L)

        fun backlightLabel(ms: Long): String = if (ms <= 0L) "Always On" else "${ms / 1000} Seconds"
    }
}

/** The library as the menus see it — built from the wall's layout, which is already loaded. */
data class PocketLibrary(
    /** In the order they stand on the wall, which is the order the owner arranged. */
    val shelves: List<Shelf> = emptyList(),
    val albums: List<Album> = emptyList()
) {
    data class Shelf(val id: String, val name: String, val albumIds: List<String>)
    data class Album(val id: String, val title: String, val artist: String, val coverPath: String?)

    private val byId: Map<String, Album> by lazy { albums.associateBy { it.id } }

    fun album(id: String): Album? = byId[id]
}

object PocketMenus {

    /** Rows on screen at once: 421px under the title bar at 50px a row. */
    const val VISIBLE_ROWS = 8

    /** The device's own name on its main menu — the owner is choosing a real one. */
    const val DEVICE_NAME = "Jawon"

    fun title(key: MenuKey, library: PocketLibrary): String = when (key) {
        MenuKey.Main -> DEVICE_NAME
        MenuKey.CoverFlow -> "Cover Flow"
        is MenuKey.Flipped -> library.album(key.albumId)?.title ?: "Album"
        MenuKey.Albums -> "Albums"
        MenuKey.Shelves -> "Shelves"
        MenuKey.Artists -> "Artists"
        is MenuKey.Shelf -> library.shelves.firstOrNull { it.id == key.shelfId }?.name ?: "Shelf"
        is MenuKey.Artist -> key.name
        is MenuKey.Album -> library.album(key.albumId)?.title ?: "Album"
        MenuKey.NowPlaying -> "Now Playing"
        MenuKey.Settings -> "Settings"
        MenuKey.About -> "About"
        MenuKey.Sound -> "Sound"
        MenuKey.Extras -> "Extras"
        MenuKey.Brick -> "Brick"
        is MenuKey.Adjust -> key.control.title
        is MenuKey.Choice -> key.setting.title
    }

    /**
     * What a level lists.
     *
     * [tracks] holds the titles of the albums whose tracklists have been read — they are not
     * part of the wall's layout, so the room loads one when its album is opened and the level
     * is empty until it arrives. [hasNowPlaying] adds the main menu's Now Playing row, which on
     * the classic appeared only once there was something to go back to.
     */
    fun rows(
        key: MenuKey,
        library: PocketLibrary,
        tracks: Map<String, List<String>>,
        hasNowPlaying: Boolean,
        settings: PocketSettings = PocketSettings()
    ): List<MenuRow> = when (key) {
        MenuKey.Main -> buildList {
            add(MenuRow("Cover Flow", RowAction.Open(MenuKey.CoverFlow)))
            add(MenuRow("Albums", RowAction.Open(MenuKey.Albums)))
            add(MenuRow("Shelves", RowAction.Open(MenuKey.Shelves)))
            add(MenuRow("Artists", RowAction.Open(MenuKey.Artists)))
            if (hasNowPlaying) add(MenuRow("Now Playing", RowAction.Open(MenuKey.NowPlaying)))
            if (library.albums.isNotEmpty()) add(MenuRow("Shuffle Albums", RowAction.ShuffleAlbums))
            add(MenuRow("Extras", RowAction.Open(MenuKey.Extras)))
            add(MenuRow("Add Albums", RowAction.AddAlbums))
            add(MenuRow("Rooms", RowAction.OpenRooms))
            add(MenuRow("Settings", RowAction.Open(MenuKey.Settings)))
        }
        MenuKey.Settings -> listOf(
            MenuRow("About", RowAction.Open(MenuKey.About)),
            MenuRow(
                "EQ",
                RowAction.Open(MenuKey.Choice(PocketSetting.EQ)),
                value = settings.eqOptions.getOrNull(settings.eqChosen) ?: "Custom"
            ),
            MenuRow(
                "Sound Check",
                RowAction.Open(MenuKey.Choice(PocketSetting.SOUND_CHECK)),
                value = PocketSettings.SOUND_CHECK_OPTIONS.getOrNull(settings.soundCheck)
            ),
            MenuRow("Sound", RowAction.Open(MenuKey.Sound)),
            MenuRow(
                "Backlight",
                RowAction.Open(MenuKey.Choice(PocketSetting.BACKLIGHT)),
                value = PocketSettings.backlightLabel(settings.backlightMs)
            ),
            MenuRow(
                "Clicker",
                RowAction.Open(MenuKey.Choice(PocketSetting.CLICKER)),
                value = if (settings.clicker) "On" else "Off"
            ),
            MenuRow(
                "Colour",
                RowAction.Open(MenuKey.Choice(PocketSetting.FINISH)),
                value = PocketFinish.entries.getOrNull(settings.finish)?.title
            ),
            MenuRow(
                "Tilt & Turn",
                RowAction.Open(MenuKey.Choice(PocketSetting.MOTION)),
                value = if (settings.motion) "On" else "Off"
            ),
            MenuRow("All Settings", RowAction.OpenSettings)
        )
        MenuKey.About -> listOf(
            MenuRow("Albums", RowAction.Nothing, value = "${settings.albums}"),
            MenuRow("Songs", RowAction.Nothing, value = "${settings.songs}"),
            MenuRow("Shelves", RowAction.Nothing, value = "${settings.shelves}"),
            MenuRow("Version", RowAction.Nothing, value = settings.version)
        )
        is MenuKey.Choice -> choices(key.setting, settings)
        MenuKey.Sound -> buildList {
            for (control in SoundControl.entries) {
                add(
                    MenuRow(
                        control.title,
                        RowAction.Open(MenuKey.Adjust(control)),
                        value = PocketSound.label(control, settings.sound[control] ?: 0f)
                    )
                )
            }
            add(
                MenuRow(
                    "Reverb",
                    RowAction.Open(MenuKey.Choice(PocketSetting.REVERB)),
                    value = settings.reverbOptions.getOrNull(settings.reverb)
                )
            )
            add(MenuRow("Flat", RowAction.FlatSound))
        }
        // A slider has no rows: the wheel moves its value, and MENU or the centre goes back.
        is MenuKey.Adjust -> emptyList()
        MenuKey.Extras -> listOf(MenuRow("Brick", RowAction.Open(MenuKey.Brick)))
        // The game is the whole screen; the wheel is its paddle.
        MenuKey.Brick -> emptyList()
        MenuKey.Albums -> albumsInOrder(library)
            .map { MenuRow(it.title, RowAction.Open(MenuKey.Album(it.id))) }
        // The same order as Albums, so a record is in the same place in both.
        MenuKey.CoverFlow -> albumsInOrder(library)
            .map { MenuRow(it.title, RowAction.Open(MenuKey.Flipped(it.id))) }
        is MenuKey.Flipped -> tracks[key.albumId].orEmpty()
            .mapIndexed { index, title -> MenuRow(title, RowAction.PlayTrack(key.albumId, index)) }
        MenuKey.Shelves -> library.shelves
            .map { MenuRow(it.name, RowAction.Open(MenuKey.Shelf(it.id))) }
        MenuKey.Artists -> library.albums
            .map { it.artist }
            .distinctBy { it.lowercase() }
            .sortedBy { sortKey(it) }
            .map { MenuRow(it, RowAction.Open(MenuKey.Artist(it))) }
        is MenuKey.Shelf -> library.shelves.firstOrNull { it.id == key.shelfId }
            ?.albumIds.orEmpty()
            .mapNotNull { library.album(it) }
            .map { MenuRow(it.title, RowAction.Open(MenuKey.Album(it.id))) }
        is MenuKey.Artist -> library.albums
            .filter { it.artist.equals(key.name, ignoreCase = true) }
            .sortedBy { sortKey(it.title) }
            .map { MenuRow(it.title, RowAction.Open(MenuKey.Album(it.id))) }
        is MenuKey.Album -> tracks[key.albumId].orEmpty()
            .mapIndexed { index, title -> MenuRow(title, RowAction.PlayTrack(key.albumId, index)) }
        MenuKey.NowPlaying -> emptyList()
    }

    private fun choices(setting: PocketSetting, settings: PocketSettings): List<MenuRow> {
        val labels: List<String> = when (setting) {
            PocketSetting.EQ -> settings.eqOptions
            PocketSetting.SOUND_CHECK -> PocketSettings.SOUND_CHECK_OPTIONS
            PocketSetting.BACKLIGHT -> PocketSettings.BACKLIGHT_OPTIONS_MS.map { PocketSettings.backlightLabel(it) }
            PocketSetting.CLICKER -> listOf("On", "Off")
            PocketSetting.REVERB -> settings.reverbOptions
            PocketSetting.FINISH -> PocketFinish.entries.map { it.title }
            PocketSetting.MOTION -> listOf("On", "Off")
        }
        val current = when (setting) {
            PocketSetting.EQ -> settings.eqChosen
            PocketSetting.SOUND_CHECK -> settings.soundCheck
            PocketSetting.BACKLIGHT -> PocketSettings.BACKLIGHT_OPTIONS_MS.indexOf(settings.backlightMs)
            PocketSetting.CLICKER -> if (settings.clicker) 0 else 1
            PocketSetting.REVERB -> settings.reverb
            PocketSetting.FINISH -> settings.finish
            PocketSetting.MOTION -> if (settings.motion) 0 else 1
        }
        return labels.mapIndexed { i, label ->
            MenuRow(label, RowAction.Choose(setting, i), checked = i == current)
        }
    }

    /** Every album, filed as the Albums list files them. */
    fun albumsInOrder(library: PocketLibrary): List<PocketLibrary.Album> =
        library.albums.sortedWith(
            compareBy<PocketLibrary.Album> { sortKey(it.title) }.thenBy { sortKey(it.artist) }
        )

    /**
     * How the classic filed a name: case ignored, and a leading "The" or "A" set aside, so
     * The Beatles sit under B. Only when something follows it — a band called "The" is a T.
     */
    fun sortKey(name: String): String {
        val lower = name.trim().lowercase()
        for (article in ARTICLES) {
            if (lower.startsWith(article) && lower.length > article.length) {
                return lower.substring(article.length).trimStart()
            }
        }
        return lower
    }

    private val ARTICLES = listOf("the ", "a ", "an ")
}

/**
 * Where the device is in its menus: a stack of levels, each remembering its highlight.
 *
 * Going back returns to the row you left — the classic did, and a menu that forgets its place
 * makes every trip into an album a walk back from the top.
 */
data class PocketNav(val stack: List<Level> = listOf(Level(MenuKey.Main))) {

    data class Level(val key: MenuKey, val selected: Int = 0, val top: Int = 0)

    val current: Level get() = stack.last()

    /**
     * Which page is up, for the screen's slide between pages: the path of menus, and how deep it
     * is. Deeper slides in from the right, as the classic's did, and back slides out to it; the
     * same page with its highlight moved is the same page, and does not slide.
     *
     * A turned-over cover counts as Cover Flow's own page — it turns over, it does not slide.
     */
    val page: Page
        get() {
            val path = stack.map { it.key }.filterNot { it is MenuKey.Flipped }
            return Page(path, path.size)
        }

    data class Page(val path: List<MenuKey>, val depth: Int)

    /**
     * Moves the highlight [items] rows down (negative is up), stopping at either end — the
     * classic does not wrap round — and scrolls the window only as far as keeps it in view.
     */
    fun move(items: Int, rowCount: Int): PocketNav {
        if (rowCount <= 0) return this
        val level = current
        val selected = (level.selected + items).coerceIn(0, rowCount - 1)
        if (selected == level.selected) return this
        return replaceTop(level.copy(selected = selected, top = windowTop(selected, level.top, rowCount)))
    }

    /** Keeps the highlight on a real row when the list under it shrinks. */
    fun clamp(rowCount: Int): PocketNav {
        val level = current
        val selected = level.selected.coerceIn(0, (rowCount - 1).coerceAtLeast(0))
        val top = windowTop(selected, level.top, rowCount)
        if (selected == level.selected && top == level.top) return this
        return replaceTop(level.copy(selected = selected, top = top))
    }

    fun open(key: MenuKey): PocketNav = PocketNav(stack + Level(key))

    /** One level up; the main menu is as far as it goes. */
    fun back(): PocketNav = if (stack.size > 1) PocketNav(stack.dropLast(1)) else this

    /** Straight to the main menu, keeping its highlight. */
    fun main(): PocketNav = PocketNav(listOf(stack.first()))

    private fun replaceTop(level: Level) = PocketNav(stack.dropLast(1) + level)

    companion object {
        /**
         * Kept across leaving the room — for Settings, or to add albums — as a list of plain
         * strings, one per level. The classic remembered where you were when you put it down.
         */
        val Saver: androidx.compose.runtime.saveable.Saver<PocketNav, Any> =
            androidx.compose.runtime.saveable.Saver(
                save = { nav -> ArrayList(nav.stack.map { encode(it) }) },
                restore = { saved ->
                    val levels = (saved as? List<*>)?.mapNotNull { (it as? String)?.let(::decode) }.orEmpty()
                    if (levels.isEmpty() || levels.first().key != MenuKey.Main) PocketNav() else PocketNav(levels)
                }
            )

        /** One level as `selected|top|kind|argument`. The argument is last, so it may hold a `|`. */
        fun encode(level: Level): String {
            val (kind, arg) = when (val k = level.key) {
                MenuKey.Main -> "main" to ""
                MenuKey.CoverFlow -> "coverflow" to ""
                is MenuKey.Flipped -> "flipped" to k.albumId
                MenuKey.Albums -> "albums" to ""
                MenuKey.Shelves -> "shelves" to ""
                MenuKey.Artists -> "artists" to ""
                MenuKey.NowPlaying -> "nowplaying" to ""
                MenuKey.Settings -> "settings" to ""
                MenuKey.About -> "about" to ""
                is MenuKey.Choice -> "choice" to k.setting.name
                MenuKey.Sound -> "sound" to ""
                is MenuKey.Adjust -> "adjust" to k.control.name
                MenuKey.Extras -> "extras" to ""
                MenuKey.Brick -> "brick" to ""
                is MenuKey.Shelf -> "shelf" to k.shelfId
                is MenuKey.Artist -> "artist" to k.name
                is MenuKey.Album -> "album" to k.albumId
            }
            return "${level.selected}|${level.top}|$kind|$arg"
        }

        fun decode(text: String): Level? {
            val parts = text.split('|', limit = 4)
            if (parts.size != 4) return null
            val selected = parts[0].toIntOrNull() ?: return null
            val top = parts[1].toIntOrNull() ?: return null
            val arg = parts[3]
            val key = when (parts[2]) {
                "main" -> MenuKey.Main
                "coverflow" -> MenuKey.CoverFlow
                "flipped" -> MenuKey.Flipped(arg)
                "albums" -> MenuKey.Albums
                "shelves" -> MenuKey.Shelves
                "artists" -> MenuKey.Artists
                "nowplaying" -> MenuKey.NowPlaying
                "settings" -> MenuKey.Settings
                "about" -> MenuKey.About
                "choice" -> PocketSetting.entries.firstOrNull { it.name == arg }?.let { MenuKey.Choice(it) }
                    ?: return null
                "sound" -> MenuKey.Sound
                "adjust" -> SoundControl.entries.firstOrNull { it.name == arg }?.let { MenuKey.Adjust(it) }
                    ?: return null
                "extras" -> MenuKey.Extras
                "brick" -> MenuKey.Brick
                "shelf" -> MenuKey.Shelf(arg)
                "artist" -> MenuKey.Artist(arg)
                "album" -> MenuKey.Album(arg)
                else -> return null
            }
            return Level(key, selected, top)
        }

        /** The first visible row, moved as little as keeps [selected] on screen. */
        fun windowTop(selected: Int, top: Int, rowCount: Int): Int {
            val visible = PocketMenus.VISIBLE_ROWS
            var t = top
            if (selected < t) t = selected
            if (selected >= t + visible) t = selected - visible + 1
            return t.coerceIn(0, (rowCount - visible).coerceAtLeast(0))
        }
    }
}
