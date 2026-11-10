#!/usr/bin/env python3
"""Synthesises all game sounds and the background music from scratch (original, royalty-free).

Requires numpy and ffmpeg (libvorbis). Output: app/src/main/res/raw/*.ogg
"""
import numpy as np, os, subprocess, tempfile, wave

SR = 22050
OUT = os.path.join(os.path.dirname(__file__), "..", "app", "src", "main", "res", "raw")

def env(n, a=0.005, r=0.3):
    t = np.arange(n) / SR
    e = np.minimum(1, t / max(a, 1e-4)) * np.exp(-t / r)
    return e

def tone(f, dur, kind="sine", a=0.005, r=0.3, vib=0.0):
    n = int(SR * dur)
    t = np.arange(n) / SR
    ph = 2 * np.pi * f * t + vib * np.sin(2 * np.pi * 5 * t)
    if kind == "sine": w = np.sin(ph)
    elif kind == "pluck":  # koto-like: decaying harmonics
        w = sum(np.sin(ph * k) * (0.6 ** (k - 1)) * np.exp(-t * (2 + k * 2.5)) for k in range(1, 6))
    elif kind == "bell":
        w = np.sin(ph) + 0.5 * np.sin(ph * 2.76) * np.exp(-t * 6) + 0.25 * np.sin(ph * 5.4) * np.exp(-t * 10)
    elif kind == "soft":
        w = np.sin(ph) + 0.2 * np.sin(2 * ph)
    else: w = np.sin(ph)
    return w * env(n, a, r)

def noise(dur, r=0.2, lp=0.3):
    n = int(SR * dur)
    x = np.random.default_rng(1).standard_normal(n)
    y = np.zeros(n); acc = 0
    for i in range(n):
        acc += lp * (x[i] - acc); y[i] = acc
    return y * env(n, 0.01, r)

def mix(*parts):
    n = max(len(p[1]) + int(p[0] * SR) for p in parts)
    out = np.zeros(n)
    for start, sig in parts:
        s = int(start * SR); out[s:s + len(sig)] += sig
    return out

def save(name, sig, vol=0.8):
    sig = sig / (np.max(np.abs(sig)) + 1e-9) * vol
    pcm = (sig * 32767).astype(np.int16)
    with tempfile.NamedTemporaryFile(suffix=".wav", delete=False) as tmp:
        with wave.open(tmp.name, "wb") as w:
            w.setnchannels(1); w.setsampwidth(2); w.setframerate(SR); w.writeframes(pcm.tobytes())
        subprocess.run(["ffmpeg", "-y", "-loglevel", "error", "-i", tmp.name, "-c:a", "libvorbis", "-q:a", "3",
                        os.path.join(OUT, name + ".ogg")], check=True)
        os.unlink(tmp.name)

# Pentatonic (D major pentatonic) frequencies
def note(semi): return 293.66 * 2 ** (semi / 12)
PENTA = [0, 2, 4, 7, 9, 12, 14, 16, 19, 21, 24]

os.makedirs(OUT, exist_ok=True)
save("sfx_match", mix((0, tone(note(12), 0.25, "pluck", r=0.12))), 0.6)
save("sfx_cascade", mix((0, tone(note(16), 0.22, "pluck", r=0.1)), (0.05, tone(note(19), 0.22, "pluck", r=0.1))), 0.6)
save("sfx_special", mix(*[(i * 0.045, tone(note(12 + s), 0.35, "bell", r=0.18)) for i, s in enumerate([0, 4, 7, 12])]), 0.6)
sweep_t = np.arange(int(SR * 0.4)) / SR
save("sfx_rocket", noise(0.4, 0.15, 0.5) * 0.7 + np.sin(2 * np.pi * (300 + 1800 * sweep_t) * sweep_t) * env(len(sweep_t), 0.01, 0.12) * 0.3, 0.7)
save("sfx_bomb", mix((0, noise(0.6, 0.25, 0.08)), (0, tone(70, 0.5, "sine", r=0.2))), 0.8)
save("sfx_rainbow", mix(*[(i * 0.06, tone(note(s), 0.5, "bell", r=0.25)) for i, s in enumerate(PENTA[:9])]), 0.6)
save("sfx_booster", mix((0, tone(note(7), 0.15, "soft", r=0.06)), (0.06, tone(note(14), 0.2, "soft", r=0.08))), 0.6)
save("sfx_box_open", mix((0, noise(0.5, 0.2, 0.2) * 0.5), *[(0.15 + i * 0.07, tone(note(s), 0.6, "bell", r=0.3)) for i, s in enumerate([7, 12, 16, 19, 24])]), 0.7)
save("sfx_rare", mix(*[(0, tone(note(s), 1.4, "bell", r=0.7)) for s in [12, 16, 19, 24]], (0.25, tone(note(28), 1.2, "bell", r=0.6))), 0.7)
save("sfx_win", mix(*[(i * 0.12, tone(note(s), 0.7, "pluck", r=0.35)) for i, s in enumerate([0, 4, 7, 12, 16, 19])], (0.75, tone(note(24), 1.2, "bell", r=0.6))), 0.7)
save("sfx_lose", mix(*[(i * 0.18, tone(note(s), 0.6, "soft", r=0.3)) for i, s in enumerate([12, 9, 7, 4])]), 0.5)
save("sfx_swish", noise(0.25, 0.08, 0.35), 0.4)
save("sfx_tap", tone(note(19), 0.08, "soft", r=0.03), 0.4)
save("sfx_coin", mix((0, tone(note(24), 0.15, "bell", r=0.07)), (0.07, tone(note(31), 0.25, "bell", r=0.12))), 0.5)
save("sfx_invalid", mix((0, tone(note(-5), 0.12, "soft", r=0.05)), (0.08, tone(note(-7), 0.15, "soft", r=0.06))), 0.4)

# Background music: gentle 16-bar loop, pads + koto plucks + soft bass, 72 BPM
bpm = 72; beat = 60 / bpm; bars = 16; total = bars * 4 * beat
n = int(SR * total); music = np.zeros(n)
rng = np.random.default_rng(7)
chords = [[0, 4, 7], [-3, 0, 4], [2, 5, 9], [-5, -1, 2]]  # I vi ii V-ish in D
for bar in range(bars):
    ch = chords[bar % 4]
    start = bar * 4 * beat
    for s in ch:  # pad
        sig = tone(note(s - 12), 4 * beat + 0.5, "soft", a=0.6, r=3.0) * 0.12
        i = int(start * SR); music[i:i + len(sig)] += sig[: max(0, min(len(sig), n - i))]
    bass = tone(note(ch[0] - 24), 2 * beat, "sine", a=0.02, r=1.0) * 0.25
    for b in (0, 2):
        i = int((start + b * beat) * SR); music[i:i + len(bass)] += bass[: max(0, min(len(bass), n - i))]
    # melody: pentatonic plucks on eighth notes with rests
    for e in range(8):
        if rng.random() < 0.55:
            s = PENTA[rng.integers(3, 9)]
            sig = tone(note(s), 1.2, "pluck", r=0.5) * 0.22
            i = int((start + e * beat / 2) * SR); music[i:i + len(sig)] += sig[: max(0, min(len(sig), n - i))]
# simple feedback echo
d = int(SR * beat * 0.75)
for i in range(d, n): music[i] += 0.28 * music[i - d]
# crossfade loop tail into head for a seamless loop
fade = int(SR * 0.5)
music[:fade] = music[:fade] * np.linspace(0, 1, fade) + music[-fade:] * np.linspace(1, 0, fade)
save("music_journey", music[:-fade], 0.55)
print(sorted(os.listdir(OUT)))
