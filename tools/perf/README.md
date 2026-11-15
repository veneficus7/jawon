# Measuring the room's frame time

`gpu_frame.py` records a system trace and reports what the room really sent to the screen: how
many frames it queued, about how long the GPU spent on each, and at most how long Compose's own
frames waited behind it. It exists because the app's DRAWN readout counts the frames the room *asks for*, and
under GPU load Filament drops the rest without a word (`ENGINEERING.md`, trap 46).

```bash
python tools/perf/gpu_frame.py --self-test                       # the parser, offline
python tools/perf/gpu_frame.py --serial SERIAL                   # 10 s; pan the wall by hand
python tools/perf/gpu_frame.py --serial SERIAL --label "CD wall, Balanced, scale 0.60" --json build/perf/before.json
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
driver thread) and `RenderThread` is Compose. The monitor waits on fences one at a time, in the
order they were queued, so a frame is *seen* to finish no earlier than the one ahead of it.

| Line | Meaning |
|---|---|
| **frames queued, a second** | buffers the room really queued — what DRAWN overcounts. A frame Filament skipped never gets this far; whether each one was then shown on a refresh is SurfaceFlinger's business |
| **GPU time per frame** | an estimate: from when the GPU could start a frame (queued, and the one ahead seen finished) to when it was seen finished. Sound for the room's frames while the GPU is the bottleneck |
| **queued → GPU finished** | latency, including the wait behind earlier frames. For Compose it is **at most**: a Compose frame that finished early is still seen after the room frame ahead of it, so this cannot show pre-emption |
| **between frames** | spacing of the room's frames; the p95 and the max are the stutter |
| **finished before they were queued** | the GPU was idle and waiting on the CPU — the opposite bottleneck |

It also prints the thermal status (trap 12: a hot phone throttles its GPU) and, where the kernel
exposes it, the GPU clock.

## Rules

- **Pass `--serial`.** With a phone and an emulator both attached, nothing guesses.
- **It leaves the device as it found it.** Tracing is switched off again in a `finally`, and the
  trace tags are checked afterwards; if they are still on, it says so and how to switch them off.
- **Label every baseline** with `--label`: the room, the Graphics choice and the render scale
  from Settings → HOW IT IS RUNNING, which the trace cannot see. The app's version is recorded
  on its own.
- **Numbers come from a phone.** An emulator renders on the host's GPU, and also writes a trace
  marker for every GL call. Use one to check the script, never for a number.
- **`--swipe` refuses a real device** unless `--allow-swipe-on-device` is given. Before every
  swipe it checks that Jawon is in front and the screen is on, and it stops for good the first
  time either is not. Without `--swipe`, nothing but reads reaches the device.
- **Compare back to back**, in one sitting, at thermal status 0 — absolutes move with heat
  (trap 13).
- **It works on the release build**: atrace's `gfx` markers come from the system's own libraries,
  and the manifest is `profileable` besides.
