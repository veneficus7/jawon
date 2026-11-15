# Measuring the room's frame time

`gpu_frame.py` records a system trace and reports what actually reached the screen: how many
frames the room drew, how long the GPU spent on each, and how long Compose's own frames waited
behind it. It exists because the app's DRAWN readout counts the frames the room *asks for*, and
under GPU load Filament drops the rest without a word (`ENGINEERING.md`, trap 46).

```bash
python tools/perf/gpu_frame.py --self-test                       # the parser, offline
python tools/perf/gpu_frame.py --serial SERIAL                   # 10 s; pan the wall by hand
python tools/perf/gpu_frame.py --serial SERIAL --json build/perf/before.json
python tools/perf/gpu_frame.py --serial emulator-5554 --swipe    # the script pans
python tools/perf/gpu_frame.py --parse build/perf/<trace>.trace.txt
```

Every run saves its raw trace under `build/perf/`, so it can be read again later.

## What it reads

With the `gfx` category on, libgui marks every buffer an app queues as "Trace GPU completion
fence N" on the thread that queued it. On a thread of its own, "GPU completion", it marks
"waiting for GPU completion N" until that buffer's fence signals. If the GPU had already finished
by then, it writes "GPU completion fence N has signaled" instead. The fence numbers pair the two
up exactly. The queueing thread says whose frame it was: `FEngine::loop` is the room (Filament's
driver thread) and `RenderThread` is Compose.

| Line | Meaning |
|---|---|
| **frames, a second on the glass** | buffers the room really queued — the honest frame rate |
| **GPU time per frame** | from when the GPU could start a frame (queued, and the one ahead finished) to when it was done |
| **queued → GPU finished** | latency, including the wait behind earlier frames; for Compose, this is "stuck behind the room" |
| **between frames** | spacing of the room's frames; the p95 and the max are the stutter |
| **finished before they were queued** | the GPU was idle and waiting on the CPU — the opposite bottleneck |

It also prints the thermal status (trap 12: a hot phone throttles its GPU) and, where the kernel
exposes it, the GPU clock.

## Rules

- **Pass `--serial`.** With a phone and an emulator both attached, nothing guesses.
- **Numbers come from a phone.** An emulator renders on the host's GPU, and also writes a trace
  marker for every GL call. Use one to check the script, never for a number.
- **`--swipe` refuses a real device** unless `--allow-swipe-on-device` is given. Before every
  swipe it checks that Jawon is in front and the screen is on, and it stops for good the first
  time either is not. Without `--swipe`, nothing but reads reaches the device.
- **Compare back to back**, in one sitting, at thermal status 0 — absolutes move with heat
  (trap 13).
- **It works on the release build**: atrace's `gfx` markers come from the system's own libraries,
  and the manifest is `profileable` besides.
