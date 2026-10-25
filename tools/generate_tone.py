#!/usr/bin/env python3
"""Generate our own two-tone alarm, with ramps to avoid clicks. No third-party audio."""
from pathlib import Path
import math
import struct
import wave

target = Path(__file__).resolve().parents[1] / "app/src/main/res/raw/alarm_tone.wav"
target.parent.mkdir(parents=True, exist_ok=True)
rate = 22050
frames = bytearray()
for i in range(rate * 2):
    t = i / rate
    local = t % 0.5
    if local < 0.35:
        envelope = min(1, local / 0.01, (0.35 - local) / 0.01)
        frequency = 880 if int(t / 0.5) % 2 == 0 else 660
        amplitude = 0.45 * envelope * math.sin(2 * math.pi * frequency * t)
    else:
        amplitude = 0
    frames.extend(struct.pack("<h", round(amplitude * 32767)))
with wave.open(str(target), "wb") as wav:
    wav.setnchannels(1)
    wav.setsampwidth(2)
    wav.setframerate(rate)
    wav.writeframes(frames)
print(target)
