"""Prüft die Spleeter-ONNX-Modelle und rechnet eine Referenz für den Kotlin-Test (gleicher Algorithmus wie SpleeterCore.kt)."""
import sys, os, time, numpy as np, onnx, onnxruntime as ort

d = sys.argv[1]
SR, N, HOP, F, T = 44100, 4096, 1024, 1024, 512
PAD = N - HOP
sess = []
for s in ["vocals", "drums", "bass", "other"]:
    p = os.path.join(d, f"{s}.fp16.onnx")
    m = onnx.load(p)
    print(s, "meta:", {x.key: x.value for x in m.metadata_props})
    so = ort.SessionOptions()
    se = ort.InferenceSession(p, so, providers=["CPUExecutionProvider"])
    print(" in:", [(i.name, i.shape, i.type) for i in se.get_inputs()], "out:", [(o.name, o.shape, o.type) for o in se.get_outputs()])
    sess.append(se)

# Testsignal (deterministisch): Kick, Hi-Hat, Bass, „Stimme“ (Mitte, Vibrato), Flächen-Synth (Seiten)
rng = np.random.default_rng(7)
n = SR * 25
t = np.arange(n) / SR
beat = 0.5
ph = (t % beat)
kick = np.sin(2 * np.pi * (50 + 120 * np.exp(-ph * 30)) * ph) * np.exp(-ph * 9)
hp = (t + beat / 2) % beat
hat = rng.standard_normal(n) * np.exp(-hp * 60) * 0.25
hat = np.diff(np.concatenate([[0], hat]))
notes = np.array([55, 55, 65.4, 49])
bf = notes[(t // 2).astype(int) % 4]
bass = 0.35 * np.sin(2 * np.pi * np.cumsum(bf) / SR)
vf = 220 * 2 ** (np.array([0, 3, 5, 7, 5, 3])[(t // 1.5).astype(int) % 6] / 12) * (1 + 0.012 * np.sin(2 * np.pi * 5.5 * t))
vph = 2 * np.pi * np.cumsum(vf) / SR
voice = sum((0.3 / k) * np.sin(k * vph) * (1.0 if k in (2, 3, 4) else 0.5) for k in range(1, 12)) * (0.5 + 0.5 * (np.sin(2 * np.pi * 0.25 * t) > -0.3))
pad = 0.12 * (np.sin(2 * np.pi * 440 * t) + np.sin(2 * np.pi * 554.4 * t) + np.sin(2 * np.pi * 659.3 * t))
L = 0.6 * kick + hat + bass + 0.5 * voice + pad
R = 0.6 * kick + hat + bass + 0.5 * voice - 0.6 * pad
sig = np.stack([L, R]).astype(np.float32) * 0.5
sig.tofile("sig.bin")

win = (0.5 - 0.5 * np.cos(2 * np.pi * np.arange(N) / N)).astype(np.float32)
total = (n + PAD + HOP - 1) // HOP + 1
splits = (total + T - 1) // T
padded = np.concatenate([np.zeros((2, PAD), np.float32), sig, np.zeros((2, (splits * T + 4) * HOP + N), np.float32)], axis=1)
ref = np.zeros((total, 3, F // 2 + 1), np.float32)
EPS = 1e-10
tm = 0
for s in range(splits):
    fr = np.stack([[padded[c, (s * T + i) * HOP:(s * T + i) * HOP + N] * win for i in range(T)] for c in range(2)])
    X = np.fft.rfft(fr, axis=-1)[..., :F]
    pw = (np.abs(X) ** 2).astype(np.float32)
    x = np.sqrt(pw).astype(np.float32)[:, None, :, :]
    t0 = time.time()
    ys = [se.run(None, {se.get_inputs()[0].name: x})[0][:, 0] for se in sess]
    tm += time.time() - t0
    a = [y.astype(np.float32) ** 2 for y in ys]
    den = a[0] + a[1] + a[2] + a[3] + EPS
    w = pw + 1e-12
    for si, st in enumerate([0, 1, 2]):
        m = (a[st] + EPS / 4) / den * w  # [2,T,F]
        m = m.reshape(2, T, F // 2, 2).sum(axis=(0, 3)) / w.reshape(2, T, F // 2, 2).sum(axis=(0, 3))
        out = np.concatenate([m, m.mean(axis=1, keepdims=True)], axis=1)
        e = min(T, total - s * T)
        ref[s * T:s * T + e, si] = out[:e]
print(f"Inferenz Python: {tm:.2f} s für {n / SR:.0f} s Audio")
ref.tofile("ref.bin")
# Plausibilität: Anteile je Frequenzbereich
for nm, si in (("vocals", 0), ("drums", 1), ("bass", 2)):
    lo = ref[:, si, 1:6].mean(); mid = ref[:, si, 10:120].mean(); hi = ref[:, si, 200:512].mean()
    print(f"{nm:7s}  <110 Hz {lo:.2f}   200-2600 Hz {mid:.2f}   >4300 Hz {hi:.2f}")
assert np.isfinite(ref).all()
assert ref.sum(axis=1).max() <= 1.0001
