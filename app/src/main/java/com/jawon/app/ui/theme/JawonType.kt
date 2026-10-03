package com.jawon.app.ui.theme

import androidx.compose.ui.unit.sp

/**
 * The type scale every flat screen draws from — DESIGN.md §3's "intentional type scale with real
 * jumps", as named sizes rather than numbers chosen at each call site.
 *
 * **Why this exists.** Before it, the 2D screens used fifteen sizes between them — 8, 9, 9.5, 10,
 * 11, 12, 13, 14, 15, 17, 18, 20, 22, 44, 52 — each locally reasonable, and together the reason
 * one screen's label never quite matched the next one's. Steps a single sp apart are not a scale;
 * they are the same decision made twice.
 *
 * Two faces, two ramps. The mono steps are small and close because the mono is the equipment
 * label — uppercase, letter-spaced, read at a glance — and its four steps are four distinct roles.
 * The Archivo steps jump, because a sleeve's lettering is meant to be seen across a room.
 *
 * Sizes only: weight, tracking and colour stay with the call site, where they carry meaning.
 * The in-room deck is not on this scale — each [DeckFinish] sets its own period lettering.
 */
object JawonType {
    // Mono — JetBrains Mono, the hi-fi display language.

    /** Tags, field labels, status lines and footnotes: the smallest thing still meant to be read. */
    val Fine = 9.sp
    /** Captions, secondary lines, section labels. The commonest size in the app. */
    val Caption = 10.sp
    /** Row text and values. */
    val Body = 11.sp
    /** A screen's name in its bar, text being typed, and sentences meant to be read through. */
    val Title = 13.sp

    // Display — Archivo Expanded, the sleeve.

    /** An album's title in a list. */
    val Row = 15.sp
    /** A field's text, and the choices beneath a screen's main one. */
    val Subhead = 18.sp
    /** The one large name on a screen. */
    val Heading = 22.sp

    /** A single number that is the whole point of the screen it is on. */
    val Figure = 34.sp
    /** The name of the app, where it appears as a masthead. */
    val Masthead = 48.sp
}
