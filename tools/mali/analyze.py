#!/usr/bin/env python3
"""
What Jawon's Filament materials cost on Arm Mali GPUs, measured with Arm's Mali Offline Compiler.

The pipeline, end to end:

  1. matinfo (Filament's own tool, run in WSL) lists the shaders inside a compiled .filamat
     and prints the GLSL ES source of the ones Jawon actually draws with.
  2. malioc (Arm Performance Studio) compiles that source for several Mali GPUs, the way the
     phone's driver would, and reports how many cycles one pixel costs on each of the GPU's
     pipelines (arithmetic, load/store, varying, texture).
  3. The numbers land in docs/mali/results/ as CSV (for tools) and Markdown (for people).

Usage, from the repo root (Windows, Python 3.10+, WSL with Ubuntu):

  python tools/mali/analyze.py materials     every shipped material, its main pixel shader
  python tools/mali/analyze.py fog-steps     fog.mat with its step count fixed at 0..24
  python tools/mali/analyze.py experiments   fog.mat with each change in EXPERIMENTS applied

Tool locations can be overridden: MATINFO and MATC (paths inside WSL), MALIOC (a Windows path).
See docs/mali/FINDINGS.md for what it found.
"""

from __future__ import annotations

import csv
import json
import os
import re
import subprocess
import sys
from dataclasses import dataclass
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
ASSETS = REPO / "app" / "src" / "main" / "assets" / "materials"  # what ships in the APK
SOURCES = REPO / "app" / "src" / "main" / "materials"  # the .mat files they come from
WORK = REPO / "build" / "mali"  # extracted shaders and raw reports; not committed
RESULTS = REPO / "docs" / "mali" / "results"  # the tables; committed

FILAMENT_BIN = "/mnt/c/Users/loh/tools/filament-1.72.1-linux/filament/bin"
MATINFO = os.environ.get("MATINFO", f"{FILAMENT_BIN}/matinfo")
MATC = os.environ.get("MATC", f"{FILAMENT_BIN}/matc")
MALIOC = os.environ.get(
    "MALIOC",
    r"C:\Users\loh\tools\arm-performance-studio-2026.6\PFiles64\Arm"
    r"\Arm Performance Studio 2026.6\mali_offline_compiler\malioc.exe",
)

# One GPU from each Mali generation, oldest first. Mali-G52 is the Redmi 9's GPU (Helio G80),
# the slowest phone Jawon is known to run on.
CORES = ["Mali-G52", "Mali-G57", "Mali-G78", "Mali-G715", "Immortalis-G925"]

# The flags Jawon's shipped materials were compiled with (matinfo prints them), minus Vulkan:
# the room renders through OpenGL ES, so only the GLSL shaders matter here.
MATC_FLAGS = ["--api", "opengl", "--platform", "mobile", "-S"]

# Filament compiles one shader per combination of features ("variants"). These are the pixel
# shaders Jawon really uses. Unlit materials have one main shader (0x00). No room has a sun:
# every scene passes `mainLightNode = null`, so DIR never applies. Lit materials are measured lit
# by spotlights (DYN, 0x02), and by spotlights that cast shadows (DYN|SRE, 0x06), as in the
# showroom.
UNLIT_VARIANTS = [0x00]
LIT_VARIANTS = [0x02, 0x06]

# The fog's step counts worth knowing: none (LOW), MEDIUM's 7, Best-looking HIGH's 9, the old
# HIGH value of 14 that made the S25 Ultra run hot, and the loop's hard cap of 24.
FOG_STEPS = [0, 1, 7, 9, 14, 24]

# Where fog.mat reads its step count from the app, and the loop that uses it: up to 24 passes,
# leaving early once `steps` is reached. A constant `steps` alone is not enough, because the
# compiler keeps the early exit and cannot bound the loop. So the loop is rewritten to run exactly
# N times, which the report can count.
FOG_STEPS_LINE = "float steps = materialParams.stepsBack.x;"
FOG_LOOP = "for (int i = 0; i < 24; i++) {\n            if (float(i) >= steps) break;"

# Changes to fog.mat, each a list of (exact text in fog.mat, replacement). They are measured at
# 9 steps. Each is checked against the current fog.mat, so an experiment that no longer applies
# fails loudly instead of silently measuring the unchanged shader.
EXPERIMENTS: dict[str, list[tuple[str, str]]] = {
    # exp(a) * exp(b) == exp(a + b): one exponential per step instead of two. Exact maths.
    "one-exp": [(
        "float strip = exp(-below / 0.06) * exp(-max(q.z + 0.04, 0.0) / 0.12);",
        "float strip = exp(-(below / 0.06 + max(q.z + 0.04, 0.0) / 0.12));",
    )],
    # Divide the dot product by the distance once, instead of dividing all three components.
    "one-divide": [(
        "float cosA = dot(toQ / dist, materialParams.spotDir);",
        "float cosA = dot(toQ, materialParams.spotDir) / dist;",
    )],
    # Correctness fix A: keep the clock and the texture coordinates built from it at 32 bits.
    # mediump (16-bit) floats step in 0.5s once the clock passes 512s, so the drift would stutter.
    "highp-time": [
        ("float fogDensity(vec3 q, float t, vec2 wind, float stir) {",
         "float fogDensity(vec3 q, highp float t, vec2 wind, float stir) {"),
        ("vec2 broadUv = q.xy * 2.2", "highp vec2 broadUv = q.xy * 2.2"),
        ("vec2 fineUv = q.xy * 5.1", "highp vec2 fineUv = q.xy * 5.1"),
        ("float t = materialParams.time;", "highp float t = materialParams.time;"),
    ],
    # Correctness fix B: the app works out the four scroll offsets itself, wrapped into 0..1 so
    # they stay small (the noise texture tiles, so wrapping is invisible), and passes them in.
    "cpu-scroll": [
        ("            name : time\n        },",
         "            name : time\n        },\n        {\n            type : float4,\n"
         "            name : scroll\n        },"),
        ("+ vec2(t * 0.011, t * 0.004)", "+ materialParams.scroll.xy"),
        ("+ vec2(-t * 0.019, t * 0.007)", "+ materialParams.scroll.zw"),
    ],
    # Two texture reads per step instead of three: the app would store the second, shifted
    # lookup in the noise texture's green channel, so one read returns both.
    "two-reads": [
        ("float broad = texture(materialParams_noise, broadUv).r;",
         "vec2 broadRg = texture(materialParams_noise, broadUv).rg;\n        float broad = broadRg.r;"),
        ("vec2(broad, texture(materialParams_noise, broadUv.yx + 0.37).r)", "vec2(broad, broadRg.g)"),
    ],
}
EXPERIMENTS["all-four"] = (EXPERIMENTS["one-exp"] + EXPERIMENTS["one-divide"]
                           + EXPERIMENTS["cpu-scroll"] + EXPERIMENTS["two-reads"])
# highp-time does what cpu-scroll does without a change on the app's side, so it replaces it.
# one-divide alone made Mali-G52 slower, so the set was measured without it as well: with it, the
# set is the cheapest on every GPU. Changes do not simply add up; the compiler schedules them
# together. `applied` is what went into fog.mat.
EXPERIMENTS["no-divide"] = EXPERIMENTS["one-exp"] + EXPERIMENTS["highp-time"] + EXPERIMENTS["two-reads"]
EXPERIMENTS["applied"] = EXPERIMENTS["no-divide"] + EXPERIMENTS["one-divide"]

# The experiments are patches to fog.mat as it was before any of them went in, read from git, so
# they can be re-run, and checked, after fog.mat itself has changed.
EXPERIMENT_BASE_REV = "c4576ed"


@dataclass
class Shader:
    index: int  # position in the .filamat's GLSL list, which is what matinfo prints by
    stage: str  # "vs" or "fs"
    variant: int  # Filament's variant bits
    flags: str  # the same bits spelled out, e.g. "DIR|DYN|SRE"


# --- running the tools -------------------------------------------------------------------------

def run(cmd: list[str]) -> str:
    result = subprocess.run(cmd, capture_output=True, text=True, encoding="utf-8", errors="replace")
    if result.returncode != 0:
        raise RuntimeError(f"{cmd[0]} exited {result.returncode}\n{result.stdout}\n{result.stderr}")
    return result.stdout


def to_wsl(path: Path) -> str:
    """C:\\Users\\x -> /mnt/c/Users/x, so WSL tools can open Windows files."""
    path = path.resolve()
    return f"/mnt/{path.drive[0].lower()}{path.as_posix()[2:]}"


def matinfo(filamat: Path, *options: str) -> str:
    return run(["wsl.exe", "-e", MATINFO, *options, to_wsl(filamat)])


def compile_mat(mat: Path) -> Path:
    filamat = mat.with_suffix(".filamat")
    run(["wsl.exe", "-e", MATC, *MATC_FLAGS, "-o", to_wsl(filamat), to_wsl(mat)])
    return filamat


SHADER_LINE = re.compile(r"#(\d+)\s+mobile\s+(vs|fs)\s+0x([0-9a-f]+)\s*(.*)")


def list_shaders(filamat: Path) -> tuple[str, list[Shader]]:
    """The material's shading model and its GLSL shaders, as matinfo lists them."""
    info = matinfo(filamat)
    found = re.search(r"Model:\s+(\w+)", info)
    if not found:
        raise RuntimeError(f"matinfo printed no shading model for {filamat.name}")
    model = found.group(1)
    section = info.split("GLSL shaders:", 1)[1]
    section = re.split(r"\n\S[^\n]*shaders:", section, maxsplit=1)[0]  # up to the next list
    shaders = [Shader(int(m[1]), m[2], int(m[3], 16), m[4].strip() or "base")
               for m in map(SHADER_LINE.search, section.splitlines()) if m]
    return model, shaders


def extract(filamat: Path, shader: Shader, name: str) -> Path:
    """Write one shader's GLSL ES source to build/mali/shaders/<name>.frag."""
    out = WORK / "shaders" / f"{name}.frag"
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(matinfo(filamat, f"--print-glsl={shader.index}"), encoding="utf-8")
    return out


def main_fragment(filamat: Path) -> Shader:
    return next(s for s in list_shaders(filamat)[1] if s.stage == "fs" and s.variant == 0x00)


def malioc(source: Path, core: str) -> dict:
    """Compile a pixel shader for one Mali GPU and pull out the numbers that matter."""
    result = subprocess.run([MALIOC, "--core", core, "--fragment", "--format", "json", str(source)],
                            capture_output=True, text=True)
    report = json.loads(result.stdout)["shaders"][0]
    if result.returncode != 0 or not report.get("variants"):
        raise RuntimeError(f"malioc could not compile {source.name} for {core}: {report.get('errors')}")
    raw = WORK / "reports" / f"{source.stem}@{core}.json"
    raw.parent.mkdir(parents=True, exist_ok=True)
    raw.write_text(result.stdout, encoding="utf-8")

    main = report["variants"][0]  # "Main": the shader as the GPU runs it
    perf = main["performance"]
    props = {p["name"]: p["value"] for p in main["properties"]}

    def path(key: str) -> dict[str, float] | None:
        if not perf.get(key):
            return None  # e.g. no longest path for a loop the compiler cannot bound
        # A pipeline the shader never uses can come back as null rather than 0.
        return {pipe: cycles for pipe, cycles in zip(perf["pipelines"], perf[key]["cycle_count"])
                if cycles is not None}

    shortest = path("shortest_path_cycles")
    # The longest path is the worst case. Lit materials have none: Filament loops over the
    # scene's lights, a count the compiler cannot know. For those, "total" (every instruction
    # once, so the light loop once: about one light) is the figure that compares.
    longest, basis = path("longest_path_cycles"), "longest path"
    if not longest:
        longest, basis = path("total_cycles"), "every instruction once"
    assert longest, f"malioc reported no cycles for {source.name}"
    bound = max(longest, key=lambda pipe: longest[pipe])
    return {
        "core": core,
        "architecture": report["hardware"]["architecture"],
        # The busiest pipeline sets the pace: this is the shader's cost per pixel, in cycles.
        "cycles": round(longest[bound], 2),
        "basis": basis,
        "bound_by": bound,
        "shortest_cycles": round(max(shortest.values()), 2) if shortest else "",
        "texture_cycles": round(longest.get("texture", 0.0), 2),
        "all_pipelines": " ".join(f"{k}={v:.2f}" for k, v in longest.items()),
        "work_registers": props.get("work_registers_used"),
        "fp16_percent": props.get("fp16_arithmetic"),
        "spills": props.get("has_stack_spilling"),
    }


# --- output ------------------------------------------------------------------------------------

def write_results(name: str, rows: list[dict], pivot_key: str, title: str, note: str) -> None:
    """rows -> results/<name>.csv (every column) and results/<name>.md (cycles by GPU)."""
    RESULTS.mkdir(parents=True, exist_ok=True)
    with open(RESULTS / f"{name}.csv", "w", newline="", encoding="utf-8") as f:
        writer = csv.DictWriter(f, fieldnames=list(rows[0]))
        writer.writeheader()
        writer.writerows(rows)

    keys = list(dict.fromkeys(r[pivot_key] for r in rows))
    lines = [f"# {title}", "", note, "",
             f"| {pivot_key} | " + " | ".join(CORES) + f" | 16-bit maths ({CORES[0]}) |",
             "| --- | " + " | ".join("---:" for _ in CORES) + " | ---: |"]
    for key in keys:
        cells = {r["core"]: r for r in rows if r[pivot_key] == key}
        fp16 = cells[CORES[0]]["fp16_percent"]
        fp16 = "–" if fp16 is None else f"{fp16}%"  # no arithmetic at all, e.g. fog at 0 steps
        mark = "" if cells[CORES[0]]["basis"] == "longest path" else "*"
        lines.append(f"| {key} | " + " | ".join(f"{cells[c]['cycles']}{mark}" for c in CORES)
                     + f" | {fp16} |")
    if any(r["basis"] != "longest path" for r in rows):
        lines += ["", "\\* No longest path (Filament loops over the scene's lights): every "
                  "instruction counted once, which is about one light."]
    (RESULTS / f"{name}.md").write_text("\n".join(lines) + "\n", encoding="utf-8")
    print("\n".join(lines))


# --- the three studies -------------------------------------------------------------------------

def study_materials() -> None:
    rows = []
    for filamat in sorted(ASSETS.glob("*.filamat")):
        model, shaders = list_shaders(filamat)
        wanted = UNLIT_VARIANTS if model == "unlit" else LIT_VARIANTS
        for shader in (s for s in shaders if s.stage == "fs" and s.variant in wanted):
            source = extract(filamat, shader, f"{filamat.stem}_{shader.variant:02x}")
            label = filamat.stem if model == "unlit" else f"{filamat.stem} ({shader.flags})"
            for core in CORES:
                rows.append({"material": label, "shading": model, **malioc(source, core)})
            print(f"measured {label}", file=sys.stderr)
    write_results("materials", rows, "material", "Cycles per pixel, shipped materials",
                  "Longest path through each material's main pixel shader, in cycles of its busiest "
                  "pipeline. Fog's loop is counted at its cap of 24 steps; see fog_steps.md for the "
                  "step counts the app really uses.")


def fog_source(rev: str | None = None) -> str:
    """fog.mat as it is now, or as it was at a git revision."""
    if rev is None:
        return (SOURCES / "fog.mat").read_text(encoding="utf-8")
    text = run(["git", "-C", str(REPO), "show", f"{rev}:app/src/main/materials/fog.mat"])
    return text.replace("\r\n", "\n")


def fog_with(changes: list[tuple[str, str]], steps: int, name: str, rev: str | None = None) -> Path:
    """fog.mat with the given text changes and a fixed step count, compiled; returns its shader."""
    text = fog_source(rev)
    fixed =[(FOG_STEPS_LINE, f"float steps = {steps}.0;  // fixed for analysis"),
             (FOG_LOOP, f"for (int i = 0; i < {steps}; i++) {{")]
    for old, new in changes + fixed:
        if old not in text:
            raise ValueError(f"{name}: fog.mat no longer contains {old!r}")
        text = text.replace(old, new)
    mat = WORK / "fog" / f"{name}.mat"
    mat.parent.mkdir(parents=True, exist_ok=True)
    mat.write_text(text, encoding="utf-8")
    filamat = compile_mat(mat)
    return extract(filamat, main_fragment(filamat), name)


def check_reproducible() -> None:
    """The Linux matc must build the same fog shader the app ships, or nothing here applies."""
    mat = WORK / "fog" / "fog_as_shipped.mat"
    mat.parent.mkdir(parents=True, exist_ok=True)
    mat.write_text((SOURCES / "fog.mat").read_text(encoding="utf-8"), encoding="utf-8")
    ours = extract(compile_mat(mat), main_fragment(mat.with_suffix(".filamat")), "fog_rebuilt")
    shipped_filamat = ASSETS / "fog.filamat"
    shipped = extract(shipped_filamat, main_fragment(shipped_filamat), "fog_shipped")
    same = ours.read_text(encoding="utf-8") == shipped.read_text(encoding="utf-8")
    print(f"rebuilt fog shader identical to the shipped one: {same}", file=sys.stderr)
    if not same:
        raise SystemExit("matc output differs from the shipped .filamat; check the Filament version")


def study_fog_steps() -> None:
    check_reproducible()
    rows = []
    for steps in FOG_STEPS:
        source = fog_with([], steps, f"fog_steps_{steps:02d}")
        for core in CORES:
            rows.append({"steps": steps, **malioc(source, core)})
        print(f"measured fog at {steps} steps", file=sys.stderr)
    write_results("fog_steps", rows, "steps", "Fog: cycles per pixel by step count",
                  "fog.mat compiled with its step count fixed. LOW uses 0 steps, MEDIUM and HIGH 7, "
                  "HIGH with Best looking 9.")


def study_experiments() -> None:
    check_reproducible()
    rows = []
    for name, changes in {"baseline": [], **EXPERIMENTS}.items():
        source = fog_with(changes, 9, f"fog_exp_{name}", rev=EXPERIMENT_BASE_REV)
        for core in CORES:
            rows.append({"experiment": name, **malioc(source, core)})
        print(f"measured experiment {name}", file=sys.stderr)
    write_results("fog_experiments", rows, "experiment", "Fog experiments at 9 steps",
                  f"Each row is fog.mat as of {EXPERIMENT_BASE_REV} with one change (see EXPERIMENTS "
                  "in tools/mali/analyze.py), compiled at 9 steps, the count used by HIGH with Best "
                  "looking. `applied` is what went into fog.mat.")


if __name__ == "__main__":
    studies = {"materials": study_materials, "fog-steps": study_fog_steps,
               "experiments": study_experiments}
    if len(sys.argv) != 2 or sys.argv[1] not in studies:
        raise SystemExit(f"usage: python tools/mali/analyze.py [{' | '.join(studies)}]")
    studies[sys.argv[1]]()
