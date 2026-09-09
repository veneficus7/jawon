#!/usr/bin/env python3
"""
Print a Jawon invite link for a server, without needing the app to make one.

The app can produce a link once it is already configured — Settings, SHARE A LINK. This
is for the other direction: you have just created a guest account on the server and you
want the link to hand out, or to bake into a build, before anyone has typed anything.

    python3 tools/make_invite.py https://music.example.net guest 'the-password'

The format is fixed by `data/remote/ServerInvite.kt`, and `ServerInviteTest` pins the
exact bytes so this file and that one cannot drift apart.

**What comes out is a password in a thin disguise.** Base64 is not encryption; anyone
holding the link reads the credentials back out in seconds. Use it only for an account
that is read-only and exists for nothing else, and revoke by changing that password on
the server and issuing a new link.
"""
import argparse
import base64
import json
import sys


def make_invite(url, username, password):
    payload = json.dumps(
        {"u": url.strip().rstrip("/"), "n": username, "p": password},
        separators=(",", ":"),
    )
    encoded = base64.urlsafe_b64encode(payload.encode("utf-8")).decode("ascii")
    # Unpadded, matching the Kotlin encoder: '=' is the character most likely to be
    # escaped or truncated on the way through a chat app or a QR encoder.
    return "jawon://" + encoded.rstrip("=")


def main():
    ap = argparse.ArgumentParser(description="Make a Jawon invite link.")
    ap.add_argument("url", help="e.g. https://music.example.net")
    ap.add_argument("username")
    ap.add_argument("password")
    args = ap.parse_args()

    if not args.url.startswith(("http://", "https://")):
        sys.exit("The address needs to start with http:// or https://")
    if args.url.startswith("http://"):
        sys.stderr.write(
            "warning: a plain http:// address sends this password unencrypted every\n"
            "         time the app talks to the server. Fine on a home network, wrong\n"
            "         for anything reachable from the internet.\n\n"
        )

    link = make_invite(args.url, args.username, args.password)
    print(link)
    print()
    print("Paste it into Jawon:  Settings -> A music server of your own -> PASTE A LINK")
    print("Or ship a build already pointed at it:")
    print("  ./gradlew assembleRelease -Pjawon.serverInvite=%s" % link)


if __name__ == "__main__":
    main()
