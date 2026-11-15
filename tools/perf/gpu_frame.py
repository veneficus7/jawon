#!/usr/bin/env python3
"""
How long Jawon's room really takes on the GPU, frame by frame, from a system trace.

The app's own DRAWN readout counts the frames the room *asks for*. When the GPU cannot keep up,
Filament's frame skipper drops the rest without a word, so DRAWN is highest exactly when it is
wrong (ENGINEERING.md, trap 46). A system trace sees what reached the GPU and when it finished:

  - With the `gfx` category on, libgui watches the fence of every buffer an app queues. On the
    queueing thread it marks "Trace GPU completion fence N" (or "... fence N has signaled" when
    the GPU had already finished), and on a thread of its own, "GPU completion", it marks
    "waiting for GPU completion N" until that fence signals.
  - The queueing thread says whose frame it was: FEngine::loop is the room (Filament's driver
    thread), RenderThread is Compose.
  - The monitor waits on fences one at a time, in the order they were queued, so a frame is seen
    to finish no earlier than the one ahead of it. A frame's GPU time is estimated as
    completion(N) - max(queued(N), completion(N-1)). That is sound for the room's own frames when
    the GPU is the bottleneck. For a Compose frame queued behind the room it is an upper bound,
    and pre-emption cannot be seen this way at all.
  - "Frames" are buffers queued, which is as far as a frame Filament skipped never gets. Whether
    each was then shown on a refresh is SurfaceFlinger's business, not this script's.

Usage, from the repo root, with a device or an emulator on adb (pass --serial; with two devices
attached, nothing guesses which one you meant):

  python tools/perf/gpu_frame.py --serial SERIAL                  10 s while you pan by hand
  python tools/perf/gpu_frame.py --serial SERIAL --seconds 20 --json build/perf/a.json
  python tools/perf/gpu_frame.py --serial emulator-5554 --swipe   let the script pan the wall
  python tools/perf/gpu_frame.py --parse build/perf/x.trace.txt   read a saved trace again
  python tools/perf/gpu_frame.py --self-test                      check the parser offline

It installs nothing. Tracing is switched on for the recording and off again afterwards — in a
`finally`, and then checked — and otherwise it only reads: the trace, the app's version, the
thermal status and the GPU clock. `--swipe` is the one thing that touches the screen. It refuses a real device unless
`--allow-swipe-on-device` is given. Before every swipe it checks that Jawon is the app in front,
and it stops for good the first time it is not, so a phone in use mid-measurement never gets a
stray swipe.

An emulator renders on the host's GPU: use one to check the script, never for a number.
"""

from __future__ import annotations

import argparse
import json
import os
import re
import shutil
import statistics
import subprocess
import threading
from dataclasses import dataclass, field
from datetime import datetime
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
OUT = REPO / "build" / "perf"  # raw traces and JSON; build/ is not committed
PACKAGE = "com.jawon.app"
ROOM_THREAD = "FEngine::loop"
UI_THREAD = "RenderThread"
GPU_CLOCKS = [  # first readable one wins; values are Hz (Adreno, most Mali) or MHz (some Mali)
    "/sys/class/kgsl/kgsl-3d0/devfreq/cur_freq",
    "/sys/class/kgsl/kgsl-3d0/gpuclk",
    "/sys/class/misc/mali0/device/cur_freq",
    "/sys/kernel/gpu/gpu_clock",
]

# --- parsing ------------------------------------------------------------------------------------

LINE_RX = re.compile(
    r"^\s*(?P<task>.*)-(?P<tid>\d+)\s+(?:\((?P<tgid>[\s\d-]+)\)\s+)?\[(?P<cpu>\d+)\]\s+"
    r"(?:\S+\s+)?(?P<ts>\d+\.\d+):\s+(?P<fn>[\w:.]+):\s+(?P<body>.*)$"
)
QUEUED_RX = re.compile(r"GPU completion fence (\d+)( has signaled)?")
WAIT_RX = re.compile(r"waiting for GPU completion (\d+)")


@dataclass
class Slice:
    name: str
    tid: int
    pid: int
    begin: float
    end: float


@dataclass
class Frame:
    index: int  # the fence number libgui gave it
    thread: str
    queued: float
    done: float | None = None  # when its fence signalled; None if it never did inside the trace
    signaled_at_queue: bool = False

    @property
    def latency_ms(self) -> float | None:
        return None if self.done is None else (self.done - self.queued) * 1000.0


@dataclass
class Report:
    pid: int
    window: tuple[float, float]
    frames: list[Frame] = field(default_factory=list)
    notes: list[str] = field(default_factory=list)


def parse_slices(text: str) -> tuple[list[Slice], dict[int, str], dict[int, int]]:
    """Every begin/end pair from tracing_mark_write, with the name and process of each thread."""
    stacks: dict[int, list[tuple[str, float, int]]] = {}
    names: dict[int, str] = {}
    tid_pid: dict[int, int] = {}
    slices: list[Slice] = []
    for line in text.splitlines():
        m = LINE_RX.match(line)
        if not m or m["fn"] != "tracing_mark_write":
            continue
        tid, ts, body = int(m["tid"]), float(m["ts"]), m["body"].strip()
        names.setdefault(tid, m["task"].strip())
        if body.startswith("B|"):
            parts = body.split("|", 2)
            pid = int(parts[1]) if parts[1].strip().isdigit() else -1
            tid_pid.setdefault(tid, pid)
            stacks.setdefault(tid, []).append((parts[2] if len(parts) > 2 else "", ts, pid))
        elif body == "E" or body.startswith("E|"):
            stack = stacks.get(tid)
            if stack:
                name, begin, pid = stack.pop()
                slices.append(Slice(name, tid, pid, begin, ts))
    return slices, names, tid_pid


def find_room_pid(slices: list[Slice], names: dict[int, str]) -> int | None:
    """The process whose FEngine::loop queued the most buffers: Jawon's room."""
    counts: dict[int, int] = {}
    for s in slices:
        if names.get(s.tid, "").startswith(ROOM_THREAD) and QUEUED_RX.search(s.name):
            counts[s.pid] = counts.get(s.pid, 0) + 1
    return max(counts, key=lambda p: counts[p]) if counts else None


def build_report(text: str, pid: int | None = None) -> Report:
    slices, names, _ = parse_slices(text)
    if not slices:
        raise SystemExit("no tracing_mark_write slices in the trace: was the gfx category on?")
    pid = pid if pid is not None else find_room_pid(slices, names)
    if pid is None:
        raise SystemExit(
            "no frames from FEngine::loop in the trace: was Jawon's room on screen and drawing?"
        )
    window = (min(s.begin for s in slices), max(s.end for s in slices))
    report = Report(pid, window)
    mine = [s for s in slices if s.pid == pid]

    frames: dict[int, Frame] = {}
    for s in mine:
        q = QUEUED_RX.search(s.name)
        if q and not names.get(s.tid, "").startswith("GPU completion"):
            n = int(q.group(1))
            frames[n] = Frame(n, names.get(s.tid, f"tid {s.tid}"), s.begin,
                              done=s.begin if q.group(2) else None,
                              signaled_at_queue=bool(q.group(2)))
    waits = {}
    for s in mine:
        w = WAIT_RX.search(s.name)
        if w:
            waits[int(w.group(1))] = s
    for n, wait in waits.items():
        if n in frames:
            frames[n].done = wait.end
    report.frames = [frames[n] for n in sorted(frames)]
    unmatched = sum(1 for n in waits if n not in frames)
    if unmatched:
        report.notes.append(f"{unmatched} completion slices had no queued fence in the window "
                            "(fences queued just before the trace started)")
    if not report.frames:
        report.notes.append("no 'GPU completion fence' markers found: this Android build does not "
                            "write them, so nothing can be paired")
    return report


# --- numbers ------------------------------------------------------------------------------------

def gpu_times(frames: list[Frame]) -> dict[int, float]:
    """Per fence, an estimate of the GPU time it took: from when the GPU could start it to when
    it finished. Fences signal in queue order, across every thread of the process."""
    out: dict[int, float] = {}
    previous_done = None
    for f in frames:  # already in fence order
        if f.done is None:
            continue
        start = f.queued if previous_done is None else max(f.queued, previous_done)
        out[f.index] = max(0.0, (f.done - start) * 1000.0)
        previous_done = f.done
    return out


def summary(values: list[float]) -> dict[str, float] | None:
    if not values:
        return None
    v = sorted(values)
    pick = lambda q: v[min(len(v) - 1, int(round(q * (len(v) - 1))))]
    return {"n": len(v), "median": round(statistics.median(v), 2), "p95": round(pick(0.95), 2),
            "max": round(v[-1], 2)}


def analyse(report: Report) -> dict:
    gpu = gpu_times(report.frames)
    t0, t1 = report.window
    seconds = max(1e-6, t1 - t0)
    out: dict = {"pid": report.pid, "seconds": round(seconds, 2), "notes": list(report.notes)}
    for label, prefix in (("room", ROOM_THREAD), ("ui", UI_THREAD)):
        frames = [f for f in report.frames if f.thread.startswith(prefix)]
        queued = [f.queued for f in frames]
        intervals = [(b - a) * 1000.0 for a, b in zip(queued, queued[1:])]
        out[label] = {
            "frames": len(frames),
            "fps": round(len(frames) / seconds, 1),
            "gpu_ms": summary([gpu[f.index] for f in frames if f.index in gpu]),
            "latency_ms": summary([f.latency_ms for f in frames if f.latency_ms is not None]),
            "interval_ms": summary(intervals),
            "already_done_when_queued": sum(1 for f in frames if f.signaled_at_queue),
        }
    others = {f.thread for f in report.frames} - {
        f.thread for f in report.frames if f.thread.startswith((ROOM_THREAD, UI_THREAD))}
    if others:
        out["notes"].append(f"frames also queued by: {', '.join(sorted(others))}")
    return out


def print_result(result: dict, meta: dict) -> None:
    def line(label: str, s: dict | None, unit: str = "ms") -> str:
        if not s:
            return f"  {label:<34} -"
        return (f"  {label:<34} median {s['median']:>7.2f}{unit}   p95 {s['p95']:>7.2f}{unit}   "
                f"max {s['max']:>7.2f}{unit}   (n={s['n']})")

    for key, value in meta.items():
        print(f"{key:>14}: {value}")
    print(f"{'window':>14}: {result['seconds']} s, process {result['pid']}")
    for label, title in (("room", "The room (FEngine::loop)"), ("ui", "Compose (RenderThread)")):
        r = result[label]
        print(f"\n{title}: {r['frames']} frames queued, {r['fps']} a second")
        print(line("GPU time per frame", r["gpu_ms"]))
        print(line("queued -> GPU finished" + (" (at most)" if label == "ui" else ""),
                   r["latency_ms"]))
        print(line("between frames", r["interval_ms"]))
        if r["already_done_when_queued"]:
            print(f"  {r['already_done_when_queued']} frames had finished on the GPU before they "
                  "were queued (the GPU was waiting on the CPU, not the other way round)")
    for note in result["notes"]:
        print(f"\nnote: {note}")


# --- the device ---------------------------------------------------------------------------------

def find_adb() -> str:
    if os.environ.get("ADB"):
        return os.environ["ADB"]
    found = shutil.which("adb")
    if found:
        return found
    exe = "adb.exe" if os.name == "nt" else "adb"
    for root in (os.environ.get("ANDROID_HOME"), os.environ.get("ANDROID_SDK_ROOT"),
                 os.path.join(os.environ.get("LOCALAPPDATA", ""), "Android", "Sdk"),
                 os.path.expanduser("~/Library/Android/sdk"), os.path.expanduser("~/Android/Sdk")):
        if root and (Path(root) / "platform-tools" / exe).exists():
            return str(Path(root) / "platform-tools" / exe)
    raise SystemExit("adb not found: put platform-tools on PATH or set ADB")


class Device:
    def __init__(self, adb: str, serial: str):
        self.adb, self.serial = adb, serial

    def shell(self, command: str, timeout: float = 30) -> str:
        return subprocess.run([self.adb, "-s", self.serial, "shell", command], capture_output=True,
                              text=True, encoding="utf-8", errors="replace",
                              timeout=timeout).stdout

    def jawon_in_front(self) -> bool:
        top = self.shell("dumpsys activity activities | grep -m1 topResumedActivity")
        awake = "Awake" in self.shell("dumpsys power | grep -m1 mWakefulness=")
        return awake and f"{PACKAGE}/" in top

    def gpu_clock_path(self) -> str | None:
        for path in GPU_CLOCKS:
            if self.shell(f"cat {path} 2>/dev/null").strip().isdigit():
                return path
        return None


def app_version(device: Device) -> str:
    m = re.search(r"versionName=(.+)", device.shell(f"dumpsys package {PACKAGE} | grep -m1 versionName"))
    return m.group(1).strip() if m else "-"


def stop_tracing(device: Device) -> bool:
    """Make sure the device is left as it was found: tracing off, no trace tags enabled.
    True if it checked out clean."""
    device.shell("atrace --async_stop > /dev/null 2>&1", timeout=60)
    flags = device.shell("getprop debug.atrace.tags.enableflags").strip()
    return flags in ("", "0", "0x0")


def thermal_status(device: Device) -> int | None:
    m = re.search(r"Thermal Status:\s*(\d)", device.shell("dumpsys thermalservice"))
    return int(m.group(1)) if m else None


def sample_clock(device: Device, path: str, seconds: float, out: list[int]) -> None:
    n = max(1, int(seconds / 0.25))
    text = device.shell(f"i=0; while [ $i -lt {n} ]; do cat {path}; sleep 0.25; i=$((i+1)); done",
                        timeout=seconds + 30)
    out.extend(int(v) for v in text.split() if v.isdigit())


def swipe_until(device: Device, stop: threading.Event, refused: list[str]) -> None:
    size = re.search(r"(\d+)x(\d+)", device.shell("wm size").splitlines()[-1])
    width, height = (int(size.group(1)), int(size.group(2))) if size else (1080, 2400)
    y, left, right = int(height * 0.42), int(width * 0.2), int(width * 0.8)
    forward = True
    while not stop.is_set():
        if not device.jawon_in_front():
            refused.append("swiping stopped: Jawon was not the app in front (or the screen was "
                           "off), so the rest of the trace is not a pan")
            return
        a, b = (right, left) if forward else (left, right)
        device.shell(f"input swipe {a} {y} {b} {y} 700", timeout=10)
        forward = not forward


def record(args: argparse.Namespace) -> None:
    # Refused before anything reaches the device.
    if args.swipe and not args.serial.startswith("emulator-") and not args.allow_swipe_on_device:
        raise SystemExit("--swipe on a real device needs --allow-swipe-on-device: someone may be "
                         "using it")
    device = Device(find_adb(), args.serial)
    if device.shell("echo ok").strip() != "ok":
        raise SystemExit(f"cannot reach {args.serial} over adb")
    pid_text = device.shell(f"pidof {PACKAGE}").split()
    if not pid_text:
        raise SystemExit("Jawon is not running: open the room first")
    if not device.jawon_in_front():
        print("warning: Jawon is not the app in front, or the screen is off")
    model = device.shell("getprop ro.product.model").strip()
    version = app_version(device)
    thermal = thermal_status(device)
    if thermal:
        print(f"warning: thermal status {thermal} - a hot phone throttles its GPU (trap 12)")
    clock_path = device.gpu_clock_path()

    clocks: list[int] = []
    sampler = threading.Thread(target=sample_clock, args=(device, clock_path, args.seconds, clocks),
                               daemon=True) if clock_path else None
    stop, refused = threading.Event(), []
    swiper = threading.Thread(target=swipe_until, args=(device, stop, refused), daemon=True) \
        if args.swipe else None
    print(f"recording {args.seconds} s on {model or args.serial}"
          + (" while swiping" if swiper else " - pan the wall now"), flush=True)
    for t in (sampler, swiper):
        if t:
            t.start()
    # `gfx` alone: every marker read here comes from libgui, and each extra category is tracing
    # overhead inside the measurement. (An emulator still writes one marker per GL call.)
    try:
        trace = subprocess.run([device.adb, "-s", args.serial, "exec-out", "atrace", "-b", "32768",
                                "-t", str(args.seconds), "gfx"], capture_output=True,
                               timeout=args.seconds + 120).stdout.decode("utf-8", "replace")
    finally:
        stop.set()
        for t in (sampler, swiper):
            if t:
                t.join(timeout=30)
        if not stop_tracing(device):
            print(f"WARNING: trace tags are still on - run: adb -s {args.serial} shell atrace "
                  "--async_stop")

    OUT.mkdir(parents=True, exist_ok=True)
    stamp = datetime.now().strftime("%Y%m%d-%H%M%S")
    raw = OUT / f"{stamp}-{args.serial.replace(':', '_')}.trace.txt"
    raw.write_text(trace, encoding="utf-8")

    report = build_report(trace, int(pid_text[0]))
    result = analyse(report)
    result["notes"].extend(refused)
    meta = {"device": model or args.serial, "Jawon": version, "label": args.label or "-",
            "thermal": thermal if thermal is not None else "-",
            "trace": raw.relative_to(REPO).as_posix()}
    if clocks:
        mhz = sorted(c / 1e6 if c > 10_000 else c for c in clocks)
        result["gpu_clock_mhz"] = {"median": round(statistics.median(mhz)), "min": round(mhz[0]),
                                   "max": round(mhz[-1])}
        meta["GPU clock"] = (f"{result['gpu_clock_mhz']['median']} MHz median "
                             f"({result['gpu_clock_mhz']['min']}-{result['gpu_clock_mhz']['max']})")
    result.update({"device": meta["device"], "app_version": version, "label": args.label,
                   "thermal": thermal, "serial": args.serial, "recorded": stamp,
                   "swiped": bool(swiper)})
    print_result(result, meta)
    if args.json:
        Path(args.json).parent.mkdir(parents=True, exist_ok=True)
        Path(args.json).write_text(json.dumps(result, indent=2), encoding="utf-8")


# --- self-test ----------------------------------------------------------------------------------

def self_test() -> None:
    """A synthetic trace with a GPU-bound room, two Compose frames stuck behind it, a frame the
    GPU had already finished, and another process's markers that must be ignored."""
    def mark(task, tid, pid, ts, body):
        return f"  {task}-{tid}  ({pid:>5}) [002] ...1 {ts:.6f}: tracing_mark_write: {body}"

    lines = ["# tracer: nop"]
    room, ui, mon, other = 101, 102, 103, 900
    t = 100.0
    # Three room frames queued every 16 ms; the GPU needs 40 ms each.
    for n in range(3):
        q = t + n * 0.016
        lines += [mark("FEngine::loop", room, 50, q, "B|50|queueBuffer"),
                  mark("FEngine::loop", room, 50, q + 0.0001, f"B|50|Trace GPU completion fence {n}"),
                  mark("FEngine::loop", room, 50, q + 0.0002, "E|50"),
                  mark("FEngine::loop", room, 50, q + 0.0003, "E|50")]
    # A Compose frame queued after the room's third: it waits behind all three.
    lines += [mark("RenderThread", ui, 50, 100.040, "B|50|Trace GPU completion fence 3"),
              mark("RenderThread", ui, 50, 100.0401, "E|50")]
    # A Compose frame whose GPU work was already done when queued.
    lines += [mark("RenderThread", ui, 50, 100.200, "B|50|GPU completion fence 4 has signaled"),
              mark("RenderThread", ui, 50, 100.2001, "E|50")]
    # The monitor thread: fences signal 40 ms apart, from 100.040; Compose's 2 ms later.
    ends = [100.040, 100.080, 100.120, 100.122]
    begin = 100.0002
    for n, end in enumerate(ends):
        lines += [mark("GPU completion", mon, 50, begin, f"B|50|waiting for GPU completion {n}"),
                  mark("GPU completion", mon, 50, end, "E|50")]
        begin = end
    # Another app's room-like markers, which must not be counted.
    lines += [mark("FEngine::loop", other, 77, 100.01, "B|77|Trace GPU completion fence 9"),
              mark("FEngine::loop", other, 77, 100.011, "E|77")]
    report = build_report("\n".join(lines))
    assert report.pid == 50, report.pid
    result = analyse(report)
    room_r, ui_r = result["room"], result["ui"]
    assert room_r["frames"] == 3 and ui_r["frames"] == 2, (room_r, ui_r)
    # Frame 0: queued 100.0001, done 100.040 -> ~40 ms. Frames 1-2: start at the previous end.
    assert abs(room_r["gpu_ms"]["median"] - 40.0) < 0.05, room_r["gpu_ms"]
    # The first Compose frame waited from 100.040 to 100.122: 82 ms queued -> finished.
    assert abs(ui_r["latency_ms"]["max"] - 82.0) < 0.05, ui_r["latency_ms"]
    assert ui_r["gpu_ms"]["median"] <= 2.0 + 0.05, ui_r["gpu_ms"]  # 2 ms of its own, and 0
    assert ui_r["already_done_when_queued"] == 1
    assert room_r["interval_ms"]["median"] == 16.0, room_r["interval_ms"]
    print("self-test passed: pairing, GPU time, the wait behind the room, the other process "
          "ignored")


def main() -> None:
    ap = argparse.ArgumentParser(description=(__doc__ or "").strip().split("\n\n")[0])
    ap.add_argument("--serial", help="adb serial of the device to record (required to record)")
    ap.add_argument("--seconds", type=int, default=10)
    ap.add_argument("--swipe", action="store_true", help="pan the wall with adb swipes")
    ap.add_argument("--allow-swipe-on-device", action="store_true")
    ap.add_argument("--json", help="also write the numbers here")
    ap.add_argument("--label", help="what was measured, e.g. 'CD wall, Balanced, scale 0.60', so "
                                    "a before and an after can be matched")
    ap.add_argument("--parse", help="read a saved trace instead of recording one")
    ap.add_argument("--pid", type=int, help="with --parse: the process to read (else: the one "
                                            "whose FEngine::loop queued the most frames)")
    ap.add_argument("--self-test", action="store_true")
    args = ap.parse_args()
    if args.self_test:
        self_test()
    elif args.parse:
        result = analyse(build_report(Path(args.parse).read_text(encoding="utf-8",
                                                                 errors="replace"), args.pid))
        print_result(result, {"trace": args.parse})
        if args.json:
            Path(args.json).write_text(json.dumps(result, indent=2), encoding="utf-8")
    elif args.serial:
        record(args)
    else:
        ap.error("--serial is required to record (or use --parse / --self-test)")


if __name__ == "__main__":
    main()
