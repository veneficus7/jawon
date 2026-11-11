# Jawon's shaders on Arm Mali GPUs

**2026-11-12 · static analysis with Arm's Mali Offline Compiler, before any phone measurement**

The ray-marched fog costs about as much per pixel as the room's shadowed vinyl, and it covers
the whole view. Its cost is set by how many steps it takes, far more than by how its code is
written. A combined rewrite of its loop cut **6.8% to 8.4% of its cycles per pixel** and **a third
of its texture reads** on five Mali GPUs. The quality tiers' step counts remain the real lever.

## Method

1. `matinfo` (Filament 1.72.1, in WSL) printed the GLSL ES of the main pixel shader of each of
   Jawon's five custom materials, in the variants Jawon actually draws with. Everything else in
   the rooms uses SceneView's stock materials and was not measured.
2. `malioc` (Mali Offline Compiler 2026.5, from Arm Performance Studio 2026.6) compiled each one
   for five GPUs, one per Mali generation:
   - **Mali-G52** (Bifrost; the Redmi 9 that Jawon runs on);
   - **G57** and **G78** (Valhall);
   - **G715** (4th-generation Valhall);
   - **Immortalis-G925** (5th generation).
3. The measure is the **longest path in cycles per pixel on the busiest pipeline**: the worst
   case for one pixel, on one shader core.
4. Every run first rebuilds the shipped `fog.mat` with the Linux `matc` and checks that the shader
   is byte-identical to the one in the APK. It always was.

Everything is reproduced by `python tools/mali/analyze.py materials | fog-steps | experiments`,
and every table is in [results/](results/).

## 1. The fog: as costly per pixel as a shadowed material, over the whole view

| Shader, Mali-G52 | Cycles per pixel |
| --- | ---: |
| Fog at 9 steps (HIGH, Best looking), before the changes | 24.33 |
| Vinyl, spotlights with shadows (one light counted) | 23.00 |
| Steel, spotlights with shadows (one light counted) | 19.50 |
| Dust mote | 2.37 |
| Neon sign | 0.50 |

No room has a sun: every scene passes `mainLightNode = null`. So the lit materials were measured
with spotlights, and with spotlights that cast shadows. A vinyl record covers a record. The fog
covers the view (`QualityTier.fogSteps`: "it is every pixel").

Before the changes, each fog step cost **2.48 cycles per pixel on Mali-G52**, 2.13 on G57 and
G78, 0.72 on G715 and 0.67 on Immortalis-G925. The cost is linear in the step count, from 4.13
cycles at 1 step to 61.17 at the loop's cap of 24 (Mali-G52).

Dropping **one** step saves more than all four code changes below put together (2.48 cycles
against 1.66). That supports the tiers as built: LOW, which a 4 GB phone such as the Redmi 9 gets,
draws no fog at all.

## 2. What the code changes bought

At 9 steps, measured on `fog.mat` as it was ([fog_experiments.md](results/fog_experiments.md)):

| Change | G52 | G78 | Immortalis-G925 |
| --- | ---: | ---: | ---: |
| Before | 24.33 | 20.88 | 6.45 |
| One `exp()` instead of two per step | 24.33 | 20.25 | 6.32 |
| Divide the dot product once, not the vector | **24.67** | 20.25 | 6.33 |
| Two texture reads per step instead of three | 23.83 | 20.25 | 6.26 |
| `highp` clock (a correctness fix, below) | 24.33 | 20.88 | 6.45 |
| **All four, applied to fog.mat** | **22.67** | **19.12** | **5.97** |
| Saving | 6.8% | 8.4% | 7.4% |

- **The bottleneck is arithmetic on all five GPUs.** On Mali-G52 the texture pipeline sits at 13.5
  cycles, under arithmetic's 24.3. So a third fewer reads (13.5 → 9.0 texture cycles; 27 → 18
  reads per pixel at 9 steps) barely moves the total. It does cut memory traffic.
- **Changes interact.** Dividing once made Mali-G52 *slower* on its own (24.33 → 24.67), yet the
  set is cheapest with it in, on every GPU. Measuring the set without it (`no-divide`: 23.83 on
  G52) showed this. The compiler schedules the shader as a whole, so combinations have to be
  measured, not added up.
- **Open question:** one `exp()` instead of two moved Mali-G52's share of 16-bit arithmetic from
  12% to 58% with no change in cycles. Why the compiler changed precision there is not explained
  by anything measured here.

## 3. A precision risk, fixed at no cost on Mali

Filament's pixel shaders default to `mediump`, which a GPU may run as 16-bit floats. The fog's
clock only grows while the room is in use. As a 16-bit float it would step in 0.25 s past 256 s
and 0.5 s past 512 s, and the fog's drift would move in visible jumps.

Declaring the clock and the texture coordinates built from it `highp` gave **identical reports
from Arm's compiler on all five GPUs**: same cycles, registers and 16-bit share. That suggests
Arm's compiler already kept these values at 32 bits, and on Mali the fix costs nothing. Whether
any phone ever showed the stutter (the S25 Ultra's Adreno, for instance) is not something static
analysis can answer.

## Limits

This is a per-pixel estimate for one shader core. It does not see overdraw, how much of the screen
each material covers, memory bandwidth, clocks or heat. Those come from measuring on a real Mali
phone with Streamline (project 1b).

Lit materials have no longest path, because Filament loops over the scene's lights. Their figures
count the light loop once.

## What changed in the app (branch `mali-shader-analysis`, not yet on a phone)

- `app/src/main/materials/fog.mat`: the four changes, recompiled to `fog.filamat` with the
  documented `matc` flags.
- `RoomAir.fogNoise()`: green now holds red transposed and shifted by 95 texels, the read the
  shader used to make itself.
  - SceneView uploads data textures as RGBA8, so green survives.
  - It reproduces the old read exactly if the bitmap's rows reach the GPU in order. SceneView
    hands the bitmap to Filament's `TextureHelper.setBitmap`; if the rows were flipped, the fog
    would show the same noise in a different arrangement.
  - A unit test pins the index arithmetic.
- **Before merging:** check on a phone that the fog looks as it did.
