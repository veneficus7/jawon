"""
PocketLighting.radiance, vectorised: the carriage the pocket player is lit by, for Cycles.

**A port, so it can drift.** Every constant here is PocketLighting.kt's; change one there and
change it here, or the preview lights the device with a room the app no longer has.
"""
import numpy as np
PI = np.pi
VOID = np.array([0.0038, 0.0042, 0.0058])
SOFTBOX_AZ, SOFTBOX_EL, SOFTBOX_HALF_AZ, SOFTBOX_HALF_EL, SOFTBOX_EDGE = 1.04, 0.52, 0.50, 0.30, 0.10
SOFTBOX_COLOUR = np.array([3.10, 2.62, 2.05])
SKIRT_GAIN, SKIRT_LOW_EL = 0.42, -0.20
SKIRT_COLOUR = np.array([1.0, 0.93, 0.84])
WINDOW_AZ, WINDOW_HALF_AZ, WINDOW_LOW_EL, WINDOW_HIGH_EL = -PI / 2, 1.05, -0.22, 0.55
WINDOW_COLOUR = np.array([0.24, 0.30, 0.42]); CITY_GLOW = np.array([0.20, 0.13, 0.075])
WINDOW_LIGHTS = [(-2.30, 0.06, 2.6, 1.6, 0.7), (-1.95, 0.12, 1.4, 1.3, 1.2), (-1.62, 0.03, 2.8, 1.9, 0.9),
                 (-1.20, 0.18, 1.2, 1.4, 1.9), (-0.95, 0.08, 2.4, 1.3, 0.5), (-0.72, 0.02, 1.8, 1.6, 1.1)]
WINDOW_LIGHT_SIZE = 0.035
CEILING_EL, CEILING_LAMPS, CEILING_LAMP_SIZE = 1.12, 7, 0.05
CEILING_LAMP_COLOUR = np.array([6.0, 5.0, 3.8])
MULLION_SPACING, MULLION_WIDTH = 0.62, 0.07
WINDOWS_ALL_ROUND = np.array([0.10, 0.12, 0.17]); WINDOWS_EL, WINDOWS_HALF_EL = 0.06, 0.30
BOUNCE = np.array([0.045, 0.035, 0.026])

def ss(e0, e1, x):
    t = np.clip((x - e0) / (e1 - e0), 0, 1); return t * t * (3 - 2 * t)
def ad(a, b):
    d = a - b; return (d + PI) % (2 * PI) - PI
def soft_rect(off, half, edge): return 1 - ss(half - edge / 2, half + edge / 2, np.abs(off))

def radiance(dx, dy, dz, P=None):
    """Radiance toward app-axis direction (dx, dy, dz). [P] overrides constants by name, for tuning."""
    P = P or {}
    n = np.sqrt(dx * dx + dy * dy + dz * dz)
    el = np.arcsin(np.clip(dy / n, -1, 1)); az = np.arctan2(dz, dx)
    out = np.zeros(dx.shape + (3,)) + P.get('VOID', VOID)
    sb_az, sb_el = P.get('SOFTBOX_AZ', SOFTBOX_AZ), P.get('SOFTBOX_EL', SOFTBOX_EL)
    box = soft_rect(ad(az, sb_az), SOFTBOX_HALF_AZ, SOFTBOX_EDGE) * soft_rect(el - sb_el, SOFTBOX_HALF_EL, SOFTBOX_EDGE)
    fall = 0.75 + 0.25 * (1 - np.clip(np.abs(ad(az, sb_az)) / SOFTBOX_HALF_AZ, 0, 1))
    out += P.get('SOFTBOX_COLOUR', SOFTBOX_COLOUR) * (box * fall)[..., None]
    # The softbox's skirt, below it, starting softly under its edge (PocketLighting.SKIRT_*).
    top = sb_el - SOFTBOX_HALF_EL
    across = soft_rect(ad(az, sb_az), SOFTBOX_HALF_AZ * 1.1, 0.25)
    onset = 1 - ss(top - SOFTBOX_EDGE * 0.5, top + SOFTBOX_EDGE * 0.5, el)
    left = 1 - np.clip((top - el) / (top - SKIRT_LOW_EL), 0, 1)
    skirt = P.get('SKIRT_GAIN', SKIRT_GAIN) * SOFTBOX_COLOUR[0] * across * onset * left ** 1.5
    out += SKIRT_COLOUR * skirt[..., None]
    inw = soft_rect(ad(az, WINDOW_AZ), WINDOW_HALF_AZ, 0.12) * ss(WINDOW_LOW_EL - 0.05, WINDOW_LOW_EL + 0.05, el) * \
        (1 - ss(WINDOW_HIGH_EL - 0.08, WINDOW_HIGH_EL + 0.08, el))
    glow = np.exp(-((el - 0.02) / 0.10) ** 2)
    pane = ((ad(az, WINDOW_AZ) / MULLION_SPACING) % 1 + 1) % 1
    frame = ss(MULLION_WIDTH, 0, np.minimum(pane, 1 - pane) * MULLION_SPACING)
    sill = np.exp(-((el - WINDOW_LOW_EL - 0.04) / 0.025) ** 2)
    glass = (1 - 0.92 * frame) * (1 - 0.85 * sill)
    out += (P.get('WINDOW_COLOUR', WINDOW_COLOUR) + P.get('CITY_GLOW', CITY_GLOW) * glow[..., None]) * (inw * glass)[..., None]
    for l in WINDOW_LIGHTS:
        d = np.hypot(ad(az, l[0]), el - l[1]); spot = np.exp(-(d / WINDOW_LIGHT_SIZE) ** 2) * inw
        out += np.array(l[2:]) * spot[..., None]
    for i in range(CEILING_LAMPS):
        laz = -PI + (i + 0.5) * 2 * PI / CEILING_LAMPS
        d = np.hypot(ad(az, laz) * np.cos(CEILING_EL), el - CEILING_EL)
        out += P.get('CEILING_LAMP_COLOUR', CEILING_LAMP_COLOUR) * np.exp(-(d / CEILING_LAMP_SIZE) ** 2)[..., None]
    rnd = 1 - ss(WINDOWS_HALF_EL * 0.4, WINDOWS_HALF_EL, np.abs(el - WINDOWS_EL))
    out += P.get('WINDOWS_ALL_ROUND', WINDOWS_ALL_ROUND) * (rnd * (1 - inw))[..., None]
    out += P.get('BOUNCE', BOUNCE) * ss(0.05, -0.4, el)[..., None]
    return out

def equirect_blender(w=1024, h=512, P=None):
    """RGB float image in Blender's equirect convention (row 0 = bottom), directions converted to app axes."""
    u = (np.arange(w) + 0.5) / w; v = (np.arange(h) + 0.5) / h
    U, V = np.meshgrid(u, v)
    phi = (0.5 - U) * 2 * PI; th = (V - 0.5) * PI
    bx, by, bz = np.cos(th) * np.cos(phi), np.cos(th) * np.sin(phi), np.sin(th)
    # Blender (x, y, z) -> app (x, z, -y)
    return radiance(bx, bz, -by, P)
