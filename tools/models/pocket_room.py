"""
The pocket player's room: a walnut desk at night, under a desk lamp.

Shared by `build_models.py` (which bakes the desk's lighting into `pocket_desk.glb`) and
`tools/pocket-preview/` (which renders the whole frame without a phone). The app's own copy
of the room's light is `PocketLighting.kt`: **every constant here that it also has must agree
with it**, or the device is lit by one room and sits on a desk lit by another.

Everything is in the **device's frame**, in app axes: +X right, +Y up the device, +Z out of its
face — which, with the device lying on the desk, is *up off the desk*. The desk, the cable and
the lamp are all children of the device's node in the app, so they share its pose.
"""

import math

import numpy as np

# ---------------------------------------------------------------------------
# The lamp. Up and to the left of the device, a hand-span above the desk, aimed up-left of it,
# so its hot spot is on the wood *beside* the device and the device sits in its falloff —
# about two thirds of the light at its top, a third at the wheel, an eighth at its foot.
#
# **The device has to be in the falloff, not the pool.** Its wheel is black plastic at 7%
# albedo and walnut is 6%: any light that makes the wood a readable brown makes the wheel the
# same grey, and in the pool's centre it read lighter than the anodised body — the "white wheel
# on a graphite device" the owner turned down on 2026-09-22. In the falloff, the face takes its
# gradient from the ceiling instead, which a metal face reflects twelve times as strongly as the
# black wheel diffuses it.
# ---------------------------------------------------------------------------
LAMP_POSITION = (-0.15, 0.20, 0.34)     # metres
LAMP_AIM = (-0.11, 0.14, -0.005)        # where its axis meets the desk
LAMP_CANDELA = 15000.0                  # at SceneView's sunny-16 exposure
LAMP_CONE_DEG = 64.0                    # full angle
LAMP_BLEND = 1.0                        # Blender's spot_blend: the whole cone is soft edge
# Filament's spot falls off as the square of a linear ramp, not Cycles' smoothstep, so the app's
# lamp is a different cone with the same light on the device: 13 and 35 degrees, inner and outer
# half-angles, fitted across it (PocketLighting.LAMP_CONE_*_RAD). The desk keeps Cycles' shape —
# it is baked.
LAMP_COLOUR = (1.0, 0.82, 0.60)
LAMP_RADIUS = 0.018                     # the diffuser: what makes its shadows soft

# The desk's top is a hair under the device's back.
DESK_Z = -0.00525 - 0.00003
# The baked plate: big enough for any phone's frame round a life-size device, with margin.
DESK_W, DESK_H = 0.160, 0.240

# ---------------------------------------------------------------------------
# The room as light: what the device reflects. Directions in the device frame.
# ---------------------------------------------------------------------------
VOID = (0.0055, 0.0048, 0.0042)
# The desk, as the device's polished sides see it at a grazing angle: warm, brighter toward
# the lamp.
DESK_SEEN = (0.62, 0.37, 0.22)
# The lamp itself: its shade's mouth, and the glow round it.
LAMP_DISC = 60.0
LAMP_DISC_RAD = 0.045
LAMP_GLOW = 1.2
LAMP_GLOW_RAD = 0.30
# **The ceiling, lit from the lamp's open top.** The face reflects a patch 11-23 degrees from
# straight up, off to the right (measured: `tools/pocket-preview/frame.py --probe`), so this
# glow — brightest over the lamp, falling away to the right — is what fills the face where the
# lamp has fallen away, and what keeps it a gradient rather than a cliff.
CEILING = (2.5, 2.0, 1.5)
CEILING_DIR = (-0.19, 0.24, 0.95)
CEILING_RAD = 0.55
# A window across the room: a cool band low on the far wall, for the polished edges.
WINDOW = (0.10, 0.14, 0.22)
WINDOW_AZ = -2.3
WINDOW_HALF_AZ = 0.5
WINDOW_Z = 0.10
WINDOW_HALF_Z = 0.18


def _unit(v):
    v = np.asarray(v, dtype=np.float64)
    return v / np.linalg.norm(v)


def radiance(d):
    """The room's radiance toward unit directions [d] (..., 3), device frame."""
    x, y, z = d[..., 0], d[..., 1], d[..., 2]
    out = np.zeros(d.shape) + np.array(VOID)
    lamp = _unit(LAMP_POSITION)
    # The desk, below.
    below = np.clip(-z * 4.0, 0.0, 1.0)
    toward = np.clip(0.5 + 0.5 * (d[..., :2] @ _unit(lamp[:2])), 0.0, 1.0)
    out += np.array(DESK_SEEN) * (below * (0.35 + 0.65 * toward))[..., None]
    # The lamp.
    a = np.arccos(np.clip(d @ lamp, -1.0, 1.0))
    out += np.array(LAMP_COLOUR) * LAMP_DISC * np.exp(-(a / LAMP_DISC_RAD) ** 2)[..., None]
    out += np.array(LAMP_COLOUR) * LAMP_GLOW * np.exp(-(a / LAMP_GLOW_RAD) ** 2)[..., None]
    # The ceiling it lights.
    c = np.arccos(np.clip(d @ _unit(CEILING_DIR), -1.0, 1.0))
    out += np.array(CEILING) * (np.exp(-(c / CEILING_RAD) ** 2) * np.clip(z * 3.0, 0.0, 1.0))[..., None]
    # The window.
    band = np.clip(1.0 - np.abs(z - WINDOW_Z) / WINDOW_HALF_Z, 0.0, 1.0)
    az = np.arctan2(y, x)
    across = np.clip(1.0 - np.abs(az - WINDOW_AZ) / WINDOW_HALF_AZ, 0.0, 1.0)
    out += np.array(WINDOW) * (band * across)[..., None]
    return out


def equirect_blender(w, h, to_device=None):
    """
    The room as an equirect image in Blender's convention (row 0 at the bottom), for a world
    texture. [to_device] (3x3, Blender axes) takes a world direction into the device's frame;
    None when the scene is built in the device's frame already.
    """
    u = (np.arange(w) + 0.5) / w
    v = (np.arange(h) + 0.5) / h
    uu, vv = np.meshgrid(u, v)
    phi = (0.5 - uu) * 2.0 * math.pi
    th = (vv - 0.5) * math.pi
    d = np.stack([np.cos(th) * np.cos(phi), np.cos(th) * np.sin(phi), np.sin(th)], axis=-1)
    if to_device is not None:
        d = d @ np.asarray(to_device).T
    # Blender (x, y, z) -> app (x, z, -y)
    return radiance(np.stack([d[..., 0], d[..., 2], -d[..., 1]], axis=-1))


# ---------------------------------------------------------------------------
# The desk: oiled black walnut, flat-sawn. Generated, from fixed seeds.
# ---------------------------------------------------------------------------

def _hash(ix, iy, seed):
    h = (ix.astype(np.int64) * 374761393 + iy.astype(np.int64) * 668265263 + seed * 2246822519) & 0xFFFFFFFF
    h = ((h ^ (h >> 13)) * 1274126177) & 0xFFFFFFFF
    return ((h ^ (h >> 16)) & 0xFFFFFF) / float(0xFFFFFF)


def _noise(u, v, seed):
    iu = np.floor(u)
    iv = np.floor(v)
    fu = u - iu
    fv = v - iv
    fu = fu * fu * (3 - 2 * fu)
    fv = fv * fv * (3 - 2 * fv)
    a = _hash(iu, iv, seed)
    b = _hash(iu + 1, iv, seed)
    c = _hash(iu, iv + 1, seed)
    d = _hash(iu + 1, iv + 1, seed)
    top = a + (b - a) * fu
    return top + ((c + (d - c) * fu) - top) * fv


def _fbm(u, v, octaves, seed):
    acc = np.zeros_like(u)
    amp, tot = 1.0, 0.0
    for o in range(octaves):
        acc += amp * _noise(u, v, seed + 17 * o)
        tot += amp
        u = u * 2.03 + 11.7
        v = v * 2.03 + 5.3
        amp *= 0.5
    return acc / tot


def walnut(px_per_mm=10.0, grain_deg=72.0, seed=21):
    """
    Albedo (linear RGB) and roughness over the desk plate, row 0 at the top.

    Flat-sawn: the board's face cuts through a log's growth rings at a depth that wanders along
    its length, which is what draws the cathedral arches and the uneven bands; the grain itself
    wanders, so no two lines run parallel for long. Pores are short dark dashes running with the
    grain. The first attempt drew straight parallel rings and read as laminate.
    """
    w_mm, h_mm = DESK_W * 1000.0, DESK_H * 1000.0
    W, H = int(round(w_mm * px_per_mm)), int(round(h_mm * px_per_mm))
    yy, xx = np.mgrid[0:H, 0:W].astype(np.float64)
    x = (xx + 0.5) / px_per_mm - w_mm / 2
    y = (H - 0.5 - yy) / px_per_mm - h_mm / 2
    a = math.radians(grain_deg)
    s = x * math.cos(a) + y * math.sin(a)
    t = -x * math.sin(a) + y * math.cos(a)
    t = t + 5.0 * (_fbm(s / 55.0, t / 40.0, 3, seed + 8) - 0.5) + 2.0 * (_fbm(s / 14.0, t / 9.0, 2, seed + 9) - 0.5)
    depth = 70.0 + 30.0 * np.sin(s / 210.0 + 0.8) + 22.0 * (_fbm(s / 160.0, 0.0 * s + 3.1, 3, seed) - 0.5)
    r = np.sqrt((t - 55.0 + 9.0 * (_fbm(s / 300.0, t / 300.0, 2, seed + 1) - 0.5)) ** 2 + depth ** 2)
    years = r / 3.4 + 3.0 * (_fbm(r / 11.0, s / 500.0, 3, seed + 2) - 0.5)
    f = years - np.floor(years)
    late = np.clip((f - 0.50) / 0.22, 0, 1) * np.clip((1.0 - f) / 0.20, 0, 1)
    late = late * late * (3 - 2 * late)
    streak = _fbm(s / 120.0, t / 2.8, 4, seed + 3)
    patch = _fbm(s / 70.0, t / 45.0, 3, seed + 4)
    early = np.array([0.074, 0.041, 0.025])
    latec = np.array([0.037, 0.020, 0.0135])
    grey = np.array([0.050, 0.034, 0.030])
    col = early * (1 - 0.68 * late[..., None]) + latec * (0.68 * late[..., None])
    sk = np.clip((streak - 0.55) * 2.5, 0, 1)[..., None]
    col = col * (1 - 0.45 * sk) + grey * (0.45 * sk)
    col *= (0.85 + 0.30 * patch[..., None])
    fibre = _fbm(s / 26.0, t / 0.35, 2, seed + 7)
    col *= (0.86 + 0.28 * fibre[..., None])
    pn = _noise(s / 4.5, t / 0.22, seed + 5) * 0.75 + _noise(s / 1.6, t / 0.15, seed + 6) * 0.25
    pores = np.clip((pn - 0.72) / 0.10, 0, 1) * (0.45 + 0.55 * (1 - late))
    col *= (1 - 0.30 * pores[..., None])
    rough = 0.42 + 0.05 * (streak - 0.5) + 0.18 * pores - 0.04 * late
    return col.astype(np.float32), np.clip(rough, 0.05, 1).astype(np.float32)


# ---------------------------------------------------------------------------
# The headphone cable: out of the jack on the top edge, a short straight plug, then arcing
# left across the strip above the device and away into the lamp's light. Millimetres.
# ---------------------------------------------------------------------------
JACK_X, JACK_Y = 15.5, 51.75
PLUG_LEN = 11.0
CABLE_R = 1.05
CABLE_PATH = [
    (JACK_X - 0.3, JACK_Y + PLUG_LEN + 7.5, -0.4),
    (JACK_X - 3.0, JACK_Y + PLUG_LEN + 16.0, -2.4),
    (JACK_X - 14.0, JACK_Y + PLUG_LEN + 21.0, -4.15),
    (JACK_X - 36.0, JACK_Y + PLUG_LEN + 20.0, -4.15),
    (JACK_X - 62.0, JACK_Y + PLUG_LEN + 15.0, -4.15),
    (JACK_X - 100.0, JACK_Y + PLUG_LEN + 19.0, -4.15),
]
