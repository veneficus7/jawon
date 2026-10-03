"""
The light the pocket player's face is seen by — a room around a phone held in the hand.

Shared by `tools/pocket-preview/` (which renders the face without a phone) and mirrored in the
app's `PocketLighting.kt`: **every constant here that it also has must agree with it**, and
`PocketLightingTest` pins the app's copy to numbers this one produces.

Directions are in the **face's frame**, app axes: +X right, +Y up the phone, +Z out of the screen
toward whoever holds it. Seen straight on, a flat face reflects only a narrow cone round +Z — the
holder's own direction, ±14° up and down the phone at the life-size camera — so most of what is
placed here is placed for what the face *cannot* show directly but its rough anodising, its
bevels and the wheel's curved coat can:

- **behind you, dark** — your own shadow, which is what a glass you look into straight on shows,
  and why the screen's cover never carries a glare across the picture;
- **a ceiling light above and behind you**, whose lower edge stops above the band the glass
  reflects: the top of the face catches its glow and fades down the phone, as a product photograph
  does, and the screen stays clean;
- **a lamp low on the left** and **a window on the right**: what the wheel's coat and the plate's
  bevels find, the warm and cool lines along the edges that say *machined*;
- **the zenith**: overhead, for every edge that faces up — the window's lower lip, the recess
  rim's lower arc, the top of the wheel's own edge.
"""

import math

import numpy as np

AMBIENT_INTENSITY = 2600.0                  # the app's IndirectLight intensity
EXPOSURE = 1.0 / (1.2 * 32000.0)            # SceneView's sunny-16 camera

VOID = (0.006, 0.0058, 0.0056)

# The ceiling light: a soft rectangle in elevation and azimuth (degrees), above and behind you.
SOFTBOX = (8.0, 8.0, 8.1)
SOFTBOX_EL = (15.0, 62.0)
SOFTBOX_AZ = (-48.0, 48.0)
SOFTBOX_EDGE = 3.0

# Overhead.
ZENITH = (16.0, 16.0, 16.2)
ZENITH_FROM_EL = 68.0
ZENITH_EDGE = 8.0

# A lamp low on the left, warm.
LAMP = (5.0, 3.4, 2.0)
LAMP_AZ = -62.0
LAMP_EL = -12.0
LAMP_RAD = 22.0

# A window on the right, cool.
WINDOW = (1.4, 1.7, 2.3)
WINDOW_AZ = (62.0, 98.0)
WINDOW_EL = (-18.0, 48.0)
WINDOW_EDGE = 5.0

# The floor and your lap, warm and dim.
FLOOR = (0.35, 0.30, 0.24)

# **You, lit by the room** — a dim band straight behind the face, from well below its middle up to
# just under the elevation where the screen's glass starts to look. What the dark finishes need:
# a black face is a near-mirror, and with nothing behind you it showed exactly that, a void below
# the screen. The glass cannot see it — its picture reflects from 3.3° up — so it never glares.
VIEWER = (1.1, 1.05, 1.0)
VIEWER_EL = (-34.0, -3.0)
VIEWER_AZ = (-55.0, 55.0)
VIEWER_EDGE = 6.0

# The key: a punctual spot above and behind you, for what a metal face does not do — the wheel and
# the button are plastic and need light *on* them, not only reflected in them. Face frame, metres.
KEY_POSITION = (-0.12, 0.40, 0.40)
KEY_AIM = (0.0, 0.05, 0.0)
KEY_CANDELA = 3200.0
KEY_CONE_DEG = 70.0                          # full angle
KEY_COLOUR = (1.0, 0.97, 0.93)


def _soft(x, lo, hi, edge):
    """1 inside [lo, hi], falling to 0 over [edge] outside it, smoothly."""
    a = np.clip((x - (lo - edge)) / edge, 0.0, 1.0)
    b = np.clip(((hi + edge) - x) / edge, 0.0, 1.0)
    a = a * a * (3 - 2 * a)
    b = b * b * (3 - 2 * b)
    return a * b


def radiance(d):
    """The room's radiance toward unit directions [d] (..., 3), face frame."""
    x, y, z = d[..., 0], d[..., 1], d[..., 2]
    el = np.degrees(np.arcsin(np.clip(y, -1.0, 1.0)))
    az = np.degrees(np.arctan2(x, z))
    out = np.zeros(d.shape) + np.array(VOID)
    out += np.array(SOFTBOX) * (_soft(el, *SOFTBOX_EL, SOFTBOX_EDGE) * _soft(az, *SOFTBOX_AZ, SOFTBOX_EDGE))[..., None]
    zen = np.clip((el - ZENITH_FROM_EL) / ZENITH_EDGE, 0.0, 1.0)
    out += np.array(ZENITH) * (zen * zen * (3 - 2 * zen))[..., None]
    lamp_dir = np.array([
        math.cos(math.radians(LAMP_EL)) * math.sin(math.radians(LAMP_AZ)),
        math.sin(math.radians(LAMP_EL)),
        math.cos(math.radians(LAMP_EL)) * math.cos(math.radians(LAMP_AZ)),
    ])
    a = np.degrees(np.arccos(np.clip(d @ lamp_dir, -1.0, 1.0)))
    out += np.array(LAMP) * np.exp(-(a / LAMP_RAD) ** 2)[..., None]
    out += np.array(WINDOW) * (_soft(az, *WINDOW_AZ, WINDOW_EDGE) * _soft(el, *WINDOW_EL, WINDOW_EDGE))[..., None]
    out += np.array(VIEWER) * (_soft(el, *VIEWER_EL, VIEWER_EDGE) * _soft(az, *VIEWER_AZ, VIEWER_EDGE))[..., None]
    below = np.clip((-el - 20.0) / 25.0, 0.0, 1.0)
    out += np.array(FLOOR) * below[..., None]
    return out


def equirect_blender(w, h):
    """The room as an equirect image in Blender's convention (row 0 at the bottom), face frame."""
    u = (np.arange(w) + 0.5) / w
    v = (np.arange(h) + 0.5) / h
    uu, vv = np.meshgrid(u, v)
    phi = (0.5 - uu) * 2.0 * math.pi
    th = (vv - 0.5) * math.pi
    d = np.stack([np.cos(th) * np.cos(phi), np.cos(th) * np.sin(phi), np.sin(th)], axis=-1)
    # Blender (x, y, z) -> app (x, z, -y)
    return radiance(np.stack([d[..., 0], d[..., 2], -d[..., 1]], axis=-1))
