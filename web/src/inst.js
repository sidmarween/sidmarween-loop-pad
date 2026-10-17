// ===================================================================== Instrumente (Port von Instruments.kt)
const DV = 11;
function newInst() {
  return {
    acidSemi: new Array(16).fill(0), acidGate: new Array(16).fill(false), acidAcc: new Array(16).fill(false), acidSlide: new Array(16).fill(false),
    acidOn: true, acidFx: true, acidSquare: false, acidOct: 0, acidCut: 0.35, acidRes: 0.7, acidEnv: 0.6, acidDec: 0.35, acidVol: 0.75, acidCutMod: 0,
    drumSteps: Array.from({ length: DV }, () => new Array(16).fill(0)),
    drumTune: new Array(DV).fill(0.5), drumDecay: new Array(DV).fill(0.5), drumLevel: new Array(DV).fill(0.8),
    drumOn: true, drumFx: false, drumDrive: 0.15, drumVol: 0.8, drumSel: 0, drumPreset: -1
  };
}
function instHasAcid(st) { return st.acidGate.some((x) => x); }
function instHasDrum(st) { return st.drumSteps.some((r) => r.some((x) => x > 0)); }

class AcidSynth {
  constructor(sr) {
    this.sr = sr; this.queue = []; this.phase = 0; this.freq = 55; this.target = 55; this.gate = false; this.amp = 0; this.fenv = 0;
    this.accNow = false; this.y1 = 0; this.y2 = 0; this.y3 = 0; this.y4 = 0; this.accEnv = 0;
  }
  hz(semi, st) { return 440 * Math.pow(2, (36 + 12 * st.acidOct + semi - 69) / 12); }
  push(frame, on, semi, acc, slide) { this.queue.push({ frame, on, semi, acc, slide }); }
  apply(e, st) {
    if (!e.on) { this.gate = false; return; }
    this.target = this.hz(e.semi, st);
    if (!(e.slide && this.gate)) { this.freq = this.target; this.fenv = 1; this.accNow = e.acc; if (e.acc) this.accEnv = 1; }
    this.gate = true;
  }
  blep(t, dt) {
    if (t < dt) { const x = t / dt; return x + x - x * x - 1; }
    if (t > 1 - dt) { const x = (t - 1) / dt; return x * x + x + x + 1; }
    return 0;
  }
  render(out, n, w, st) {
    let evs = [];
    if (this.queue.length) {
      evs = this.queue.filter((e) => e.frame < w + n).sort((a, b) => a.frame - b.frame);
      this.queue = this.queue.filter((e) => e.frame >= w + n);
    }
    let ei = 0;
    const sr = this.sr;
    const fDec = Math.exp(-1 / ((0.04 + st.acidDec * 1.6) * sr)), accDec = Math.exp(-1 / (0.12 * sr)), glide = 1 - Math.exp(-1 / (0.05 * sr));
    const res = st.acidRes * 3.95, vol = st.acidVol * 0.9, square = st.acidSquare;
    for (let i = 0; i < n; i++) {
      while (ei < evs.length && (evs[ei].frame < 0 || evs[ei].frame <= w + i)) { this.apply(evs[ei], st); ei++; }
      if (!this.gate && this.amp < 1e-5 && this.fenv < 1e-4) continue;
      this.freq += (this.target - this.freq) * glide;
      const dt = this.freq / sr;
      this.phase += dt; if (this.phase >= 1) this.phase -= 1;
      let osc;
      if (!square) osc = 2 * this.phase - 1 - this.blep(this.phase, dt);
      else { osc = this.phase < 0.5 ? 1 : -1; osc += this.blep(this.phase, dt); osc -= this.blep((this.phase + 0.5) % 1, dt); }
      osc *= 0.9;
      this.amp += ((this.gate ? 1 : 0) - this.amp) * (this.gate ? 0.02 : 0.006);
      this.fenv *= fDec; this.accEnv *= accDec;
      const accBoost = this.accNow ? this.accEnv * st.acidEnv * 2.5 : 0;
      const oct = st.acidCut * 7 + st.acidEnv * 5 * this.fenv + accBoost * 2 + st.acidCutMod * 3;
      const fc = Math.min(0.45 * sr, 70 * Math.pow(2, oct)), g = 1 - Math.exp(-2 * Math.PI * fc / sr);
      const x = Math.tanh(osc - res * this.y4);
      this.y1 += g * (x - this.y1); this.y2 += g * (this.y1 - this.y2); this.y3 += g * (this.y2 - this.y3); this.y4 += g * (this.y3 - this.y4);
      const a = this.amp * (this.accNow ? 1 + 0.6 * this.accEnv : 1);
      out[i] += Math.tanh(this.y4 * (1 + st.acidRes * 1.5) * 1.4) * a * vol;
    }
    if (ei < evs.length) this.queue.push(...evs.slice(ei));
  }
}

class DrumMachine {
  constructor(sr) {
    this.sr = sr;
    this.voices = Array.from({ length: DV }, () => ({ active: false, delay: 0, t: 0, acc: 1, ph: new Float64Array(6), p1: 0, hp: 0, bp1: 0 }));
    this.seed = 0x1234567; this.metal = [205.3, 304.4, 369.6, 522.7, 540.0, 800.0];
  }
  noise() { let s = this.seed; s ^= s << 13; s ^= s >>> 17; s ^= s << 5; this.seed = s | 0; return (s & 0xffff) / 32768 - 1; }
  trigger(v, delay, accent) {
    if (v < 0 || v >= DV) return;
    const vc = this.voices[v];
    vc.active = true; vc.delay = Math.max(0, delay | 0); vc.t = 0; vc.acc = accent ? 1.45 : 1; vc.p1 = 0; vc.ph.fill(0);
    if (v === 7) this.voices[8].active = false;
  }
  metallic(vc, mul) {
    let s = 0;
    for (let k = 0; k < 6; k++) { vc.ph[k] += this.metal[k] * mul / this.sr; if (vc.ph[k] >= 1) vc.ph[k] -= 1; s += vc.ph[k] < 0.5 ? 1 : -1; }
    return s / 6;
  }
  render(out, n, st) {
    const sr = this.sr, drive = 1 + st.drumDrive * 6, norm = 1 / Math.tanh(drive), TWO = 2 * Math.PI, ex = Math.exp, sin = Math.sin;
    for (let v = 0; v < DV; v++) {
      const vc = this.voices[v];
      if (!vc.active) continue;
      const tune = st.drumTune[v], dec = st.drumDecay[v], lvl = st.drumLevel[v] * st.drumVol * vc.acc;
      for (let i = 0; i < n; i++) {
        if (vc.delay > 0) { vc.delay--; continue; }
        const t = vc.t / sr;
        let s, env;
        switch (v) {
          case 0: {
            const base = 42 * Math.pow(2, (tune - 0.5) * 1.2), f = base * (1 + 3.2 * ex(-t / 0.028));
            vc.p1 += f / sr; env = ex(-t / (0.12 + dec * 0.9));
            s = Math.tanh((sin(TWO * vc.p1) + this.noise() * ex(-t / 0.0015) * 0.35) * 1.6); break;
          }
          case 1: {
            const f = 175 * Math.pow(2, tune - 0.5);
            vc.p1 += f * (1 + 0.4 * ex(-t / 0.02)) / sr;
            const tone = (sin(TWO * vc.p1) + 0.5 * sin(TWO * vc.p1 * 1.87)) * ex(-t / 0.06), nz = this.noise();
            vc.hp += (nz - vc.hp) * 0.25; env = 1;
            s = tone * 0.7 + (nz - vc.hp) * ex(-t / (0.07 + dec * 0.25)) * 0.9; break;
          }
          case 2: case 3: case 4: {
            const baseF = (v === 2 ? 90 : v === 3 ? 130 : 185) * Math.pow(2, tune - 0.5);
            vc.p1 += baseF * (1 + 0.6 * ex(-t / 0.05)) / sr; env = ex(-t / (0.12 + dec * 0.6));
            s = sin(TWO * vc.p1) + this.noise() * ex(-t / 0.004) * 0.15; break;
          }
          case 5: vc.p1 += 1700 * Math.pow(2, tune - 0.5) / sr; env = ex(-t / (0.01 + dec * 0.02)); s = sin(TWO * vc.p1) + 0.6 * sin(TWO * vc.p1 * 0.3); break;
          case 6: {
            const nz = this.noise();
            vc.bp1 += (nz - vc.bp1) * 0.35; vc.hp += (vc.bp1 - vc.hp) * 0.06;
            const band = vc.bp1 - vc.hp;
            let e = 0;
            for (let d = 0; d <= 3; d++) { const td = t - d * 0.011; if (td >= 0) e += ex(-td / 0.006); }
            if (t > 0.034) e += ex(-(t - 0.034) / (0.1 + dec * 0.4)) * 0.8;
            env = 1; s = band * e * 2.2; break;
          }
          case 7: case 8: {
            const m = this.metallic(vc, 1.6 * Math.pow(2, tune - 0.5)) + this.noise() * 0.25;
            vc.hp += (m - vc.hp) * 0.55;
            env = v === 7 ? ex(-t / (0.02 + dec * 0.08)) : ex(-t / (0.12 + dec * 0.7)); s = (m - vc.hp) * 1.8; break;
          }
          case 9: {
            const m = this.metallic(vc, 2.4 * Math.pow(2, tune - 0.5)) * 0.6 + this.noise() * 0.6;
            vc.hp += (m - vc.hp) * 0.45; env = ex(-t / (0.6 + dec * 2.2)); s = (m - vc.hp) * 1.4; break;
          }
          default: {
            const m = this.metallic(vc, 3.3 * Math.pow(2, tune - 0.5)) * 0.8 + this.noise() * 0.15;
            vc.hp += (m - vc.hp) * 0.5; vc.p1 += 3100 / sr; env = ex(-t / (0.8 + dec * 2.2));
            s = (m - vc.hp) * 1.1 + sin(TWO * vc.p1) * 0.25 * ex(-t / 0.4);
          }
        }
        out[i] += Math.tanh(s * env * lvl * drive) * norm * 0.8;
        vc.t++;
        if (vc.t > sr * 4 || (env < 1e-4 && vc.t > sr / 20)) { vc.active = false; break; }
      }
    }
  }
}
