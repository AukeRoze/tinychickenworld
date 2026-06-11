#!/usr/bin/env python3
"""Procedural in-genre placeholder music: warm pastoral kids palette."""
import numpy as np, subprocess, sys
SR = 44100
DUR = 40.1

# ── instruments: damped partial stacks ────────────────────────────────
def tone(freq, partials, tau, length, attack=0.004, lowpass=None):
    n = int(length * SR)
    t = np.arange(n) / SR
    out = np.zeros(n)
    for mult, amp in partials:
        out += amp * np.sin(2*np.pi*freq*mult*t) * np.exp(-t/(tau/mult**0.5))
    a = int(attack*SR)
    if a > 0: out[:a] *= np.linspace(0,1,a)
    return out

GLOCK   = dict(partials=[(1,1.0),(2.76,0.35),(5.4,0.12)], tau=0.9, attack=0.002)
MUSICBOX= dict(partials=[(1,1.0),(3.0,0.30),(5.8,0.15)],  tau=1.6, attack=0.002)
MARIMBA = dict(partials=[(1,1.0),(3.9,0.25),(9.2,0.08)],  tau=0.35, attack=0.003)
PLUCK   = dict(partials=[(1,1.0),(2,0.45),(3,0.22),(4,0.10)], tau=0.45, attack=0.004)
CELESTA = dict(partials=[(1,1.0),(4.0,0.20)], tau=1.1, attack=0.003)
BASS    = dict(partials=[(1,1.0),(2,0.15)], tau=0.8, attack=0.008)

def pad_tone(freq, length):
    n = int(length*SR); t = np.arange(n)/SR
    out = (np.sin(2*np.pi*freq*t) + np.sin(2*np.pi*freq*1.004*t)
           + 0.4*np.sin(2*np.pi*freq*2.003*t))
    env = np.minimum(t/1.2, 1.0) * np.minimum((length-t)/1.5, 1.0).clip(0,1)
    return out * env * 0.33

def tick(length=0.05):
    n = int(length*SR)
    x = np.random.default_rng(7).standard_normal(n) * np.exp(-np.arange(n)/(0.012*SR))
    return np.diff(x, prepend=0.0) * 0.5   # crude highpass

def midi(m): return 440.0 * 2**((m-69)/12)

PENTA = [0,2,4,7,9]
def deg(d, base=72):            # degree -> midi (C5 base), supports octaves
    return base + 12*(d//5) + PENTA[d%5]

CHORDS = {"C":[48,55,60,64], "Am":[45,52,57,60], "F":[41,53,57,60], "G":[43,55,59,62]}

# ── track definitions: (mood, bpm, beats/bar, progression, melody) ────
# melody = list of (degree or None, beats). Hand-written, 8 bars, looped.
R=None
TRACKS = {
 "rolling_hills_romp": ("energetic",126,4,["C","C","F","C","Am","F","G","C"],
   [(0,.5),(2,.5),(4,.5),(5,.5),(4,1),(2,1),(0,.5),(2,.5),(4,1),(2,1),(R,1),
    (4,.5),(5,.5),(7,.5),(5,.5),(4,1),(2,1),(4,.5),(2,.5),(1,.5),(2,.5),(0,2),(R,2),
    (2,.5),(4,.5),(5,.5),(7,.5),(9,1),(7,1),(5,.5),(4,.5),(2,1),(4,2),
    (0,.5),(2,.5),(4,.5),(2,.5),(1,1),(2,1),(0,3),(R,1)]),
 "bumblebee_boogie": ("energetic",120,4,["Am","Am","F","G","Am","F","G","Am"],
   [(4,.5),(R,.5),(4,.5),(5,.5),(4,.5),(2,.5),(R,1),(2,.5),(4,.5),(5,1),(4,1),(R,1),
    (7,.5),(R,.5),(7,.5),(9,.5),(7,.5),(5,.5),(4,1),(5,.5),(4,.5),(2,1),(R,2),
    (4,.5),(4,.5),(5,.5),(7,.5),(5,.5),(4,.5),(2,.5),(1,.5),(2,2),(R,2),
    (1,.5),(2,.5),(4,.5),(5,.5),(4,1),(2,1),(1,.5),(2,.5),(4,2),(R,1)]),
 "puddle_jump_parade": ("energetic",116,4,["C","F","C","G","C","F","G","C"],
   [(0,1),(2,.5),(4,.5),(4,1),(5,1),(4,.5),(2,.5),(4,.5),(5,.5),(7,2),
    (5,1),(4,.5),(2,.5),(4,2),(2,1),(0,1),(R,2),
    (7,1),(5,.5),(4,.5),(5,2),(4,1),(2,.5),(4,.5),(5,1),(4,1),(2,2),
    (0,1),(2,.5),(4,.5),(2,1),(1,1),(0,3),(R,1)]),
 "tiny_mystery": ("thoughtful",92,4,["Am","F","Am","G","Am","F","G","Am"],
   [(4,1),(R,.5),(5,.5),(4,1),(2,1),(R,2),(1,1),(2,1),(4,2),(R,2),
    (5,1),(R,.5),(7,.5),(5,1),(4,1),(R,1),(2,1),(4,2),(R,2),
    (7,1),(R,1),(9,1),(7,1),(5,2),(4,1),(2,1),(R,2),
    (2,1),(4,1),(2,1),(1,1),(0,2),(R,4)]),
 "cloud_watching": ("thoughtful",84,4,["C","Am","F","C","C","Am","F","G"],
   [(4,2),(5,2),(7,3),(R,1),(5,2),(4,2),(2,3),(R,1),
    (4,2),(7,2),(9,2),(7,2),(5,2),(4,2),(2,2),(R,2),
    (2,2),(4,2),(5,3),(R,1),(4,2),(2,2),(0,4)]),
 "what_is_that_glow": ("thoughtful",88,4,["F","G","C","Am","F","G","C","C"],
   [(0,1),(2,1),(4,2),(2,1),(4,1),(5,2),(4,1),(5,1),(7,2),(R,2),
    (5,1),(7,1),(9,2),(7,2),(5,2),(4,1),(5,1),(4,2),(2,2),(R,2),
    (0,1),(2,1),(4,1),(5,1),(7,4),(5,2),(4,2),(2,2),(0,2)]),
 "starlight_nest": ("calm",76,3,["C","Am","F","G","C","Am","F","C"],
   [(4,1.5),(2,1.5),(0,3),(2,1.5),(4,1.5),(5,3),(4,1.5),(2,1.5),(1,3),(2,3),(R,3),
    (4,1.5),(5,1.5),(7,3),(5,1.5),(4,1.5),(2,3),(0,1.5),(2,1.5),(1,3),(0,3),(R,3)]),
 "warm_straw_sunset": ("calm",72,3,["C","F","C","G","Am","F","G","C"],
   [(2,1.5),(4,1.5),(5,3),(4,3),(2,1.5),(4,1.5),(2,3),(0,3),(R,3),
    (4,1.5),(5,1.5),(7,3),(5,3),(4,1.5),(2,1.5),(4,3),(2,3),(0,3),(R,1.5)]),
 "drowsy_dandelions": ("calm",69,3,["Am","F","C","G","Am","F","G","Am"],
   [(4,3),(2,1.5),(4,1.5),(5,3),(4,3),(2,3),(1,1.5),(2,1.5),(0,6),(R,3),
    (2,3),(4,1.5),(5,1.5),(4,3),(2,3),(1,3),(2,3),(4,6)]),
}

def add(buf, start, sig, gain=1.0):
    i = int(start*SR)
    j = min(i+len(sig), len(buf))
    if i < len(buf): buf[i:j] += sig[:j-i] * gain

def render(name):
    mood, bpm, bpb, prog, melody = TRACKS[name]
    beat = 60.0/bpm
    bar = bpb*beat
    L = np.zeros(int(DUR*SR)+SR); Rb = np.zeros_like(L)
    rng = np.random.default_rng(abs(hash(name)) % 2**32)

    # melody loop
    inst = MARIMBA if mood=="energetic" else (CELESTA if mood=="thoughtful" else MUSICBOX)
    t = 0.0; mi = 0
    while t < DUR:
        d, beats = melody[mi % len(melody)]; mi += 1
        if d is not None:
            sig = tone(midi(deg(d)), length=min(beats*beat*1.6,3.0), **inst)
            g = 0.16 if mood!="calm" else 0.14
            add(L, t, sig, g*1.0); add(Rb, t, sig, g*0.85)
        t += beats*beat

    # accompaniment per bar
    t = 0.0; bi = 0
    while t < DUR:
        ch = CHORDS[prog[bi % len(prog)]]; bi += 1
        add(L, t, tone(midi(ch[0]), length=min(bar,2.5), **BASS), 0.13)
        add(Rb, t, tone(midi(ch[0]), length=min(bar,2.5), **BASS), 0.13)
        if mood == "energetic":
            for b in range(bpb):                       # pluck chord on beats
                note = ch[1 + b % (len(ch)-1)]
                s = tone(midi(note), length=0.7, **PLUCK)
                add(L, t+b*beat, s, 0.085); add(Rb, t+b*beat, s, 0.10)
                if b % 2 == 1:                          # offbeat tick
                    tk = tick()
                    add(L, t+b*beat, tk, 0.05); add(Rb, t+b*beat, tk, 0.04)
        elif mood == "thoughtful":
            p = pad_tone(midi(ch[1]), bar)
            add(L, t, p, 0.10); add(Rb, t+0.012, p, 0.10)  # haas width
            s = tone(midi(ch[1]+12), length=0.8, **PLUCK)   # sparse pluck beat 1
            add(L, t, s, 0.07); add(Rb, t, s, 0.06)
        else:                                           # calm: broken chord
            p = pad_tone(midi(ch[1]), bar)
            add(L, t, p, 0.09); add(Rb, t+0.012, p, 0.09)
            arp = [ch[1], ch[2], ch[3] if len(ch)>3 else ch[1]+12]
            for k in range(bpb):
                s = tone(midi(arp[k % len(arp)]+12), length=1.2, **MUSICBOX)
                add(L, t+k*beat, s, 0.05); add(Rb, t+k*beat, s, 0.06)
        t += bar

    mix = np.stack([L[:int(DUR*SR)], Rb[:int(DUR*SR)]], axis=1)
    # gentle master: normalize peak, fades
    peak = np.abs(mix).max()
    if peak > 0: mix *= 0.5/peak
    n = len(mix)
    fi, fo = int(1.5*SR), int(3.0*SR)
    mix[:fi] *= np.linspace(0,1,fi)[:,None]
    mix[-fo:] *= np.linspace(1,0,fo)[:,None]
    raw = (mix*32767).astype(np.int16).tobytes()
    out = f"/tmp/music/{name}.mp3"
    subprocess.run(["ffmpeg","-v","error","-f","s16le","-ar",str(SR),"-ac","2",
                    "-i","pipe:0","-codec:a","libmp3lame","-b:a","192k","-y",out],
                   input=raw, check=True)
    print(f"  {name} [{mood}] -> {out}")

for nm in TRACKS: render(nm)
print("done")
