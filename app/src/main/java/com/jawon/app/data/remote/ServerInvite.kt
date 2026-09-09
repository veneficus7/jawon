package com.jawon.app.data.remote

import org.json.JSONObject
import java.util.Base64

/**
 * One string that carries a whole server, so nobody has to type three fields.
 *
 * **The problem this solves.** Asking someone to enter an address, a username and a password
 * before they can hear anything is three chances to get it wrong and a reason to give up. An
 * invite is a single blob they paste once — or that is baked into the build, in which case they
 * never see it at all and the app simply has a library when it opens.
 *
 * ## This is obfuscation, not security, and the distinction matters
 *
 * The payload is Base64 of a small JSON document. Anyone who has the string, or the APK it was
 * built into, can read the credentials out of it in seconds — that is a property of shipping a
 * shared secret to strangers and no amount of encoding changes it. So:
 *
 * - The account an invite carries should be **read-only**, and should be an account whose
 *   compromise costs nothing. In Navidrome, a normal non-admin user.
 * - Rotating it is the revocation mechanism: change the password on the server, publish a new
 *   invite, and every old one stops working.
 * - Never put an admin account, or a password reused anywhere else, in one of these.
 *
 * The encoding exists so the string survives being pasted through chat apps and QR codes
 * without a stray character breaking it, and so a password is not sitting in plain sight in a
 * group chat. It is a convenience, and it is documented as one.
 */
object ServerInvite {

    /** Prefix so a pasted invite is recognisable, and so a stray URL is not mistaken for one. */
    const val SCHEME = "jawon://"

    /**
     * Packs a server into an invite string.
     *
     * URL-safe Base64 without padding, so the result survives being pasted into a chat message,
     * a URL bar or a QR code without anything escaping or truncating it.
     */
    fun encode(server: SubsonicServer): String {
        val json = JSONObject()
            .put("u", server.baseUrl)
            .put("n", server.username)
            .put("p", server.password)
        val bytes = json.toString().toByteArray(Charsets.UTF_8)
        return SCHEME + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    /**
     * Reads one back, or null if the string is not an invite.
     *
     * Deliberately forgiving about what surrounds the payload — pasted text arrives with
     * whitespace, newlines and the odd invisible character from wherever it was copied, and
     * refusing a correct invite because it had a trailing space would be a needless failure.
     * It is **not** forgiving about the contents: a payload that decodes but carries no address
     * is rejected rather than turned into a half-configured server that fails later with a
     * confusing message.
     */
    fun decode(text: String): SubsonicServer? {
        val trimmed = text.trim()
        if (!trimmed.startsWith(SCHEME, ignoreCase = true)) return null
        val payload = trimmed.substring(SCHEME.length).trim().trimEnd('/')
        if (payload.isEmpty()) return null

        return runCatching {
            val bytes = Base64.getUrlDecoder().decode(payload)
            val json = JSONObject(String(bytes, Charsets.UTF_8))
            val url = json.optString("u").trim()
            if (url.isEmpty()) return null
            SubsonicServer(
                url = url,
                username = json.optString("n").trim(),
                password = json.optString("p")
            )
        }.getOrNull()
    }

    /** `true` when this looks like an invite, for deciding whether to even try. */
    fun looksLikeInvite(text: String) = text.trim().startsWith(SCHEME, ignoreCase = true)
}
