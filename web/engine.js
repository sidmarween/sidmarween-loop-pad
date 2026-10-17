// ===================================================================== Effekte (Port von Fx.kt / Fx2.kt)
const PI = Math.PI;
const tanh = Math.tanh;
const fmod = (a, b) => ((a % b) + b) % b;

const FX = {
  LOWPASS: 0, HIGHPASS: 1, BANDPASS: 2, NOTCH: 3, BELL: 4, PEAK: 5, COMB: 6,
  DELAY: 7, STUTTER: 8, FLANGER: 9, CHORUS: 10, REVERB: 11, HALL: 12, VOCODER: 13, DISTORTION: 14, CRUSH: 15,
  PHASER: 16, TREMOLO: 17, VIBRATO: 18, EQ: 19, FILTER: 20, SATURATE: 21, OVERDRIVE: 22, GRAINDLY: 23,
  PITCH: 24, STRETCH: 25, SMEAR: 26, FREEZE: 27, RINGMOD: 28, FM: 29, SHUFFLE: 30, FREQSHIFT: 31,
  RESONATOR: 32, GRANULAR: 33, SEQFX: 34, MULTIFX: 35, KRISTALL: 36, COUNT: 37
};
const stages = (v) => 1 + Math.min(3, Math.floor(v * 4));

class Verb {
  constructor(sr, scale) {
    const combTun = [1116, 1188, 1277, 1356, 1422, 1491, 1557, 1617];
    const apTun = [556, 441, 341, 225];
    this.combs = combTun.map((t) => new Float32Array(Math.max(1, Math.floor(t * scale * sr / 44100))));
    this.combIdx = new Int32Array(8);
    this.combStore = new Float32Array(8);
    this.aps = apTun.map((t) => new Float32Array(Math.max(1, Math.floor(t * sr / 44100))));
    this.apIdx = new Int32Array(4);
  }
  reset() { for (const c of this.combs) c.fill(0); this.combStore.fill(0); for (const a of this.aps) a.fill(0); }
  tick(input, room, damp) {
    let acc = 0;
    for (let c = 0; c < 8; c++) {
      const cb = this.combs[c], ci = this.combIdx[c], yv = cb[ci];
      this.combStore[c] = yv * (1 - damp) + this.combStore[c] * damp;
      cb[ci] = input + this.combStore[c] * room;
      this.combIdx[c] = ci + 1 === cb.length ? 0 : ci + 1;
      acc += yv;
    }
    for (let a = 0; a < 4; a++) {
      const ab = this.aps[a], ai = this.apIdx[a], bo = ab[ai];
      const o = -acc + bo;
      ab[ai] = acc + bo * 0.5;
      this.apIdx[a] = ai + 1 === ab.length ? 0 : ai + 1;
      acc = o;
    }
    return acc;
  }
}

class Band {
  constructor() { this.a1 = 0; this.a2 = 0; this.a3 = 0; this.k = 0; this.ic1 = 0; this.ic2 = 0; }
  set(freq, q, sr) {
    const g = Math.tan(PI * Math.min(freq, 0.45 * sr) / sr);
    this.k = 1 / q; this.a1 = 1 / (1 + g * (g + this.k)); this.a2 = g * this.a1; this.a3 = g * this.a2;
  }
  bp(v0) {
    const v3 = v0 - this.ic2;
    const v1 = this.a1 * this.ic1 + this.a2 * v3;
    const v2 = this.ic2 + this.a2 * this.ic1 + this.a3 * v3;
    this.ic1 = 2 * v1 - this.ic1; this.ic2 = 2 * v2 - this.ic2;
    return v1;
  }
  reset() { this.ic1 = 0; this.ic2 = 0; }
}

function readInterp(b, size, pos) {
  let rp = pos;
  while (rp < 0) rp += size;
  const i0 = Math.floor(rp) % size;
  const i1 = (i0 + 1) % size;
  const fr = rp - Math.floor(rp);
  return b[i0] + (b[i1] - b[i0]) * fr;
}

class Ring {
  constructor(size) { this.size = size; this.a = new Float32Array(size); this.w = 0; }
  write(v) { this.a[this.w % this.size] = v; this.w++; }
  read(pos) {
    let p = pos;
    const minP = this.w - this.size + 2, maxP = this.w - 2;
    if (p < 0 || p < minP) return 0;
    if (p > maxP) p = maxP;
    const i0 = Math.floor(p), fr = p - i0;
    const x0 = this.a[i0 % this.size], x1 = this.a[(i0 + 1) % this.size];
    return x0 + (x1 - x0) * fr;
  }
  back(d) { return this.read(this.w - 1 - d); }
  clear() { this.a.fill(0); }
}

class Biquad {
  constructor() { this.b0 = 1; this.b1 = 0; this.b2 = 0; this.a1 = 0; this.a2 = 0; this.z1 = 0; this.z2 = 0; }
  tick(x) { const y = this.b0 * x + this.z1; this.z1 = this.b1 * x - this.a1 * y + this.z2; this.z2 = this.b2 * x - this.a2 * y; return y; }
  reset() { this.z1 = 0; this.z2 = 0; }
  _set(nb0, nb1, nb2, na0, na1, na2) { this.b0 = nb0 / na0; this.b1 = nb1 / na0; this.b2 = nb2 / na0; this.a1 = na1 / na0; this.a2 = na2 / na0; }
  lowShelf(f, db, sr) {
    const a = Math.pow(10, db / 40), w0 = 2 * PI * f / sr, c = Math.cos(w0), al = Math.sin(w0) / 2 * Math.SQRT2, sa = 2 * Math.sqrt(a) * al;
    this._set(a * ((a + 1) - (a - 1) * c + sa), 2 * a * ((a - 1) - (a + 1) * c), a * ((a + 1) - (a - 1) * c - sa),
      (a + 1) + (a - 1) * c + sa, -2 * ((a - 1) + (a + 1) * c), (a + 1) + (a - 1) * c - sa);
  }
  highShelf(f, db, sr) {
    const a = Math.pow(10, db / 40), w0 = 2 * PI * f / sr, c = Math.cos(w0), al = Math.sin(w0) / 2 * Math.SQRT2, sa = 2 * Math.sqrt(a) * al;
    this._set(a * ((a + 1) + (a - 1) * c + sa), -2 * a * ((a - 1) + (a + 1) * c), a * ((a + 1) + (a - 1) * c - sa),
      (a + 1) - (a - 1) * c + sa, 2 * ((a - 1) - (a + 1) * c), (a + 1) - (a - 1) * c - sa);
  }
  peak(f, db, q, sr) {
    const a = Math.pow(10, db / 40), w0 = 2 * PI * f / sr, c = Math.cos(w0), al = Math.sin(w0) / (2 * q);
    this._set(1 + al * a, -2 * c, 1 - al * a, 1 + al / a, -2 * c, 1 - al / a);
  }
}

// einfacher deterministischer Zufall (wie kotlin Random(42) – nur Charakter, nicht identisch)
class Rnd {
  constructor(seed) { this.s = seed >>> 0 || 1; }
  next() { let x = this.s; x ^= x << 13; x >>>= 0; x ^= x >>> 17; x ^= x << 5; x >>>= 0; this.s = x; return x / 4294967296; }
  int(n) { return Math.floor(this.next() * n); }
  bool() { return this.next() < 0.5; }
}

class Fx2 {
  constructor(sr) {
    this.sr = sr;
    this.engage = 0; this.wasTouching = false;
    this.rnd = new Rnd(42);
    this.dry = new Float32Array(8192);
    this.hist = new Ring(sr * 6);
    this.dly = new Ring(sr * 4);
    this.grains = Array.from({ length: 40 }, () => ({ src: null, pos: 0, rate: 1, len: 1, age: 0, gain: 1, active: false }));
    this.apS = new Float32Array(12); this.phLfo = 0; this.phFb = 0; this.lfoPh = 0;
    this.vb = new Ring(Math.floor(sr / 10));
    this.eqL = new Biquad(); this.eqM = new Biquad(); this.eqH = new Biquad();
    this.ic1 = 0; this.ic2 = 0; this.lp1 = 0; this.hp1 = 0;
    this.gCount = 0; this.ptPh = 0; this.ptG = sr * 0.06; this.stRp = 0;
    this.fftN = 1024; this.hop = 256;
    const N = this.fftN;
    this.win = new Float32Array(N); for (let i = 0; i < N; i++) this.win[i] = Math.sqrt(0.5 - 0.5 * Math.cos(2 * PI * i / N));
    this.inFifo = new Float32Array(N); this.inPos = 0; this.outAcc = new Float32Array(N); this.outPos = 0; this.hopCount = 0;
    this.re = new Float32Array(N); this.im = new Float32Array(N);
    this.mag = new Float32Array(N / 2 + 1); this.held = new Float32Array(N / 2 + 1); this.phs = new Float32Array(N / 2 + 1);
    this.frozen = false;
    this.twC = new Float32Array(N / 2); this.twS = new Float32Array(N / 2);
    for (let i = 0; i < N / 2; i++) { this.twC[i] = Math.cos(2 * PI * i / N); this.twS[i] = Math.sin(2 * PI * i / N); }
    this.rmPh = 0; this.rmLfo = 0; this.fmBuf = new Ring(Math.floor(sr / 20)); this.fmFb = 0;
    this.hA = new Float32Array(16); this.hB = new Float32Array(16); this.hDelay = 0; this.fsPh = 0; this.fsFb = 0;
    this.rs1 = new Float32Array(6); this.rs2 = new Float32Array(6);
    this.shSlice = null; this.shBack = 0; this.shRev = false; this.shRate = 1;
    this.sqAction = 0; this.sqHold = 0; this.sqHoldN = 0; this.mfxLp = 0;
    this.hCoefA = [0.6923878, 0.9360654, 0.9882295, 0.9987488];
    this.hCoefB = [0.4021921, 0.8561711, 0.9722910, 0.9952885];
    this.chords = [[0, 12, 19, 24, 28, 31], [0, 7, 12, 19, 24, 31], [0, 4, 7, 12, 16, 19], [0, 3, 7, 12, 15, 19], [0, 5, 7, 12, 17, 19], [0, 4, 7, 10, 12, 16]];
    this.patterns = [
      [0, 0, 0, 0, 1, 0, 0, 0, 0, 0, 0, 0, 1, 1, 0, 0], [0, 3, 0, 3, 0, 3, 0, 3, 0, 3, 0, 3, 0, 3, 3, 3],
      [0, 0, 2, 0, 0, 0, 2, 2, 0, 0, 2, 0, 0, 2, 0, 2], [4, 4, 4, 4, 0, 0, 0, 0, 4, 4, 4, 4, 0, 0, 0, 0],
      [0, 0, 5, 5, 0, 0, 5, 5, 0, 0, 1, 1, 0, 0, 5, 5], [6, 0, 6, 0, 6, 6, 0, 0, 6, 0, 6, 0, 6, 6, 6, 6],
      [1, 1, 1, 1, 2, 2, 2, 2, 3, 0, 3, 0, 5, 5, 5, 5], [0, 1, 2, 3, 4, 5, 6, 0, 0, 6, 5, 4, 3, 2, 1, 0]
    ];
    this.rsA1 = new Float32Array(6); this.rsA2 = new Float32Array(6); this.rsA3 = new Float32Array(6); this.rsGn = new Float32Array(6);
  }
  reset() {
    this.hist.clear(); this.dly.clear(); this.vb.clear(); this.fmBuf.clear();
    for (const g of this.grains) g.active = false;
    this.apS.fill(0); this.phFb = 0; this.eqL.reset(); this.eqM.reset(); this.eqH.reset();
    this.ic1 = 0; this.ic2 = 0; this.lp1 = 0; this.hp1 = 0;
    this.inFifo.fill(0); this.outAcc.fill(0); this.held.fill(0); this.frozen = false;
    this.hA.fill(0); this.hB.fill(0); this.fsFb = 0; this.fmFb = 0; this.rs1.fill(0); this.rs2.fill(0);
    this.shSlice = null; this.mfxLp = 0;
  }
  onePole(fc) { return 1 - Math.exp(-2 * PI * fc / this.sr); }
  step(target, rate) { if (this.engage < target) this.engage = Math.min(target, this.engage + rate); else if (this.engage > target) this.engage = Math.max(target, this.engage - rate); }
  shape(ph, form) { const s = Math.sin(2 * PI * ph); const k = 1 + form * 20; return tanh(s * k) / tanh(k); }
  semis(v) { return Math.round((v - 0.5) * 24); }
  spawn(src, pos, rate, len, gain) {
    for (const g of this.grains) if (!g.active) { g.src = src; g.pos = pos; g.rate = rate; g.len = Math.max(8, len | 0); g.age = 0; g.gain = gain; g.active = true; return; }
  }
  grainSum() {
    let s = 0;
    for (const g of this.grains) {
      if (!g.active) continue;
      const w = 0.5 - 0.5 * Math.cos(2 * PI * g.age / g.len);
      s += (g.src ? g.src.read(g.pos) : 0) * w * g.gain;
      g.pos += g.rate; g.age++;
      if (g.age >= g.len) g.active = false;
    }
    return s;
  }
  process(buf, n, frame0, type, sx, sy, touching, mix, p1, p2, bar, ms) {
    const start = touching && !this.wasTouching;
    this.wasTouching = touching;
    const target = touching ? 1 : 0;
    const eRate = 1 / (0.008 * this.sr);
    const tails = type === FX.GRAINDLY || type === FX.RESONATOR || type === FX.MULTIFX || type === FX.KRISTALL;
    const selfHist = type === FX.PITCH || type === FX.STRETCH || type === FX.GRANULAR || type === FX.SHUFFLE || type === FX.SEQFX;
    if (!tails && !touching && this.engage < 1e-4 && type !== FX.FREEZE) {
      this.engage = 0;
      for (let i = 0; i < n; i++) this.hist.write(buf[i]);
      return;
    }
    this.dry.set(buf.subarray(0, Math.min(n, this.dry.length)));
    if (start) { this.stRp = this.hist.w - this.sr * 0.05; this.frozen = false; this.shSlice = null; }
    switch (type) {
      case FX.PHASER: this.phaser(buf, n, target, eRate, mix, p1, p2, sx, sy); break;
      case FX.TREMOLO: this.tremolo(buf, n, frame0, target, eRate, mix, p1, p2, sx, sy, bar, ms); break;
      case FX.VIBRATO: this.vibrato(buf, n, target, eRate, mix, p1, p2, sx, sy); break;
      case FX.EQ: this.eq(buf, n, target, eRate, mix, p1, p2, sx, sy); break;
      case FX.FILTER: this.filter(buf, n, target, eRate, mix, p1, p2, sx, sy); break;
      case FX.SATURATE: this.saturate(buf, n, target, eRate, mix, p1, p2, sx, sy); break;
      case FX.OVERDRIVE: this.overdrive(buf, n, target, eRate, mix, p1, p2, sx, sy); break;
      case FX.GRAINDLY: this.grainDelay(buf, n, target, eRate, mix, p1, p2, sx, sy, bar); break;
      case FX.PITCH: this.pitch(buf, n, target, eRate, mix, p1, p2, sx, sy); break;
      case FX.STRETCH: this.stretch(buf, n, target, eRate, mix, p1, p2, sx, sy); break;
      case FX.SMEAR: case FX.FREEZE: this.spectral(buf, n, target, eRate, mix, p1, p2, sx, sy, type === FX.FREEZE, touching, start); break;
      case FX.RINGMOD: this.ringmod(buf, n, target, eRate, mix, p1, p2, sx, sy); break;
      case FX.FM: this.fm(buf, n, target, eRate, mix, p1, p2, sx, sy); break;
      case FX.SHUFFLE: this.shuffle(buf, n, frame0, target, eRate, mix, p1, p2, sx, sy, bar, ms); break;
      case FX.FREQSHIFT: this.freqShift(buf, n, target, eRate, mix, p1, p2, sx, sy); break;
      case FX.RESONATOR: this.resonator(buf, n, target, eRate, mix, p1, p2, sx, sy); break;
      case FX.GRANULAR: this.granular(buf, n, target, eRate, mix, p1, p2, sx, sy); break;
      case FX.SEQFX: this.seqFx(buf, n, frame0, target, eRate, mix, p1, p2, sx, sy, bar, ms); break;
      case FX.MULTIFX: this.multiFx(buf, n, target, eRate, mix, p1, p2, sx, sy, bar); break;
      case FX.KRISTALL: this.kristall(buf, n, target, eRate, mix, p1, p2, sx, sy); break;
    }
    if (!selfHist) for (let i = 0; i < n; i++) this.hist.write(this.dry[i]);
  }
  phaser(buf, n, target, eRate, mix, fb, stagesK, sx, sy) {
    const sr = this.sr, rate = 0.05 * Math.pow(100, sx), st = 2 + 2 * Math.round(stagesK * 5);
    let coef = this._phCoef || 0;
    for (let i = 0; i < n; i++) {
      this.step(target, eRate);
      if (i % 16 === 0) {
        this.phLfo += rate * 16 / sr; if (this.phLfo > 1) this.phLfo -= 1;
        const l = 0.5 + 0.5 * Math.sin(2 * PI * this.phLfo);
        const fc = Math.min(0.45 * sr, 200 * Math.pow(20, 0.3 + (l - 0.3) * sy));
        const t = Math.tan(PI * fc / sr); coef = (t - 1) / (t + 1);
      }
      const d = buf[i];
      let x = d + this.phFb * fb * 0.9;
      for (let s = 0; s < st; s++) { const y = coef * x + this.apS[s]; this.apS[s] = x - coef * y; x = y; }
      this.phFb = x;
      const wet = 0.5 * (d + x);
      buf[i] = d + (wet - d) * this.engage * mix;
    }
    this._phCoef = coef;
  }
  tremolo(buf, n, frame0, target, eRate, mix, form, sync, sx, sy, bar, ms) {
    const divs = [1 / 2, 1 / 4, 1 / 8, 1 / 16, 1 / 32];
    const synced = sync >= 0.5, period = bar * divs[Math.min(4, Math.floor(sx * 5))], rate = 0.5 * Math.pow(30, sx);
    for (let i = 0; i < n; i++) {
      this.step(target, eRate);
      let ph;
      if (synced) { const x = frame0 + i - ms; ph = fmod(x, period) / period; }
      else { this.lfoPh += rate / this.sr; if (this.lfoPh > 1) this.lfoPh -= 1; ph = this.lfoPh; }
      const l = this.shape(ph, form), g = 1 - sy * (0.5 - 0.5 * l), d = buf[i];
      buf[i] = d + (d * g - d) * this.engage * mix;
    }
  }
  vibrato(buf, n, target, eRate, mix, form, base, sx, sy) {
    const sr = this.sr, rate = 0.3 * Math.pow(40, sx), depth = sy * 0.006 * sr, b = (0.002 + base * 0.01) * sr;
    for (let i = 0; i < n; i++) {
      this.step(target, eRate);
      this.lfoPh += rate / sr; if (this.lfoPh > 1) this.lfoPh -= 1;
      const d = buf[i]; this.vb.write(d);
      const wet = this.vb.back(b + depth * (0.5 + 0.5 * this.shape(this.lfoPh, form)));
      buf[i] = d + (wet - d) * this.engage * mix;
    }
  }
  eq(buf, n, target, eRate, mix, mid, midF, sx, sy) {
    const sr = this.sr;
    this.eqL.lowShelf(120, (sx - 0.5) * 30, sr); this.eqH.highShelf(6000, (sy - 0.5) * 30, sr);
    this.eqM.peak(200 * Math.pow(25, midF), (mid - 0.5) * 30, 0.8, sr);
    for (let i = 0; i < n; i++) {
      this.step(target, eRate);
      const d = buf[i], wet = this.eqH.tick(this.eqM.tick(this.eqL.tick(d)));
      buf[i] = d + (wet - d) * this.engage * mix;
    }
  }
  filter(buf, n, target, eRate, mix, mode, drive, sx, sy) {
    const sr = this.sr, fc = Math.min(40 * Math.pow(18000 / 40, sx), 0.45 * sr), g = Math.tan(PI * fc / sr), k = 2 - 1.93 * sy;
    const a1 = 1 / (1 + g * (g + k)), a2 = g * a1, a3 = g * a2, gain = 1 + drive * 7, norm = 1 / tanh(gain);
    for (let i = 0; i < n; i++) {
      this.step(target, eRate);
      const d = buf[i], v0 = drive < 0.01 ? d : tanh(d * gain) * norm;
      const v3 = v0 - this.ic2, v1 = a1 * this.ic1 + a2 * v3, v2 = this.ic2 + a2 * this.ic1 + a3 * v3;
      this.ic1 = 2 * v1 - this.ic1; this.ic2 = 2 * v2 - this.ic2;
      const hp = v0 - k * v1 - v2;
      const wet = mode < 0.5 ? v2 + (v1 - v2) * mode * 2 : v1 + (hp - v1) * (mode - 0.5) * 2;
      buf[i] = d + (wet - d) * this.engage * mix;
    }
  }
  saturate(buf, n, target, eRate, mix, biasK, level, sx, sy) {
    const drive = 1 + sx * 15, bias = (biasK - 0.5) * 0.6, off = tanh(drive * bias), norm = 1 / Math.max(0.2, tanh(drive));
    const a = this.onePole(18000 * Math.pow(0.12, sy)), out = 0.4 + level * 0.9;
    for (let i = 0; i < n; i++) {
      this.step(target, eRate);
      const d = buf[i], y = (tanh(drive * (d + bias)) - off) * norm;
      this.lp1 += (y - this.lp1) * a;
      buf[i] = d + (this.lp1 * out - d) * this.engage * mix;
    }
  }
  overdrive(buf, n, target, eRate, mix, bass, level, sx, sy) {
    const drive = 1 + sx * 40, hpA = this.onePole(400 * Math.pow(0.1, bass)), lpA = this.onePole(800 * Math.pow(20, sy)), out = 0.3 + level * 0.8;
    for (let i = 0; i < n; i++) {
      this.step(target, eRate);
      const d = buf[i];
      this.hp1 += (d - this.hp1) * hpA;
      const v = (d - this.hp1) * drive;
      const y = v >= 0 ? 1 - Math.exp(-v) : -0.8 * (1 - Math.exp(1.25 * v));
      this.lp1 += (y - this.lp1) * lpA;
      buf[i] = d + (this.lp1 * out - d) * this.engage * mix;
    }
  }
  grainDelay(buf, n, target, eRate, mix, spray, pitchK, sx, sy, bar) {
    const sr = this.sr, fr = [1 / 16, 1 / 8, 3 / 16, 1 / 4, 3 / 8, 1 / 2], gl = Math.floor(0.09 * sr);
    let d = bar * fr[Math.min(fr.length - 1, Math.floor(sx * fr.length))];
    while (d > this.dly.size - gl * 3) d /= 2;
    const rate = Math.pow(2, this.semis(pitchK) / 12), interval = Math.floor(0.02 * sr);
    for (let i = 0; i < n; i++) {
      this.step(target, eRate);
      if (++this.gCount >= interval) {
        this.gCount = 0;
        const jitter = (this.rnd.next() * 2 - 1) * spray * d * 0.3;
        this.spawn(this.dly, this.dly.w - d - gl * rate + jitter, rate, gl, 0.45);
      }
      const g = this.grainSum(), dr = buf[i];
      this.dly.write(dr * this.engage + g * sy * 0.9);
      buf[i] = dr + g * mix;
    }
  }
  pitch(buf, n, target, eRate, mix, fb, spray, sx, sy) {
    const sr = this.sr, r = Math.pow(2, this.semis(sx) / 12), base = (0.03 + sy * 0.12) * sr;
    for (let i = 0; i < n; i++) {
      this.step(target, eRate);
      this.ptPh += (1 - r) / this.ptG;
      if (this.ptPh >= 1 || this.ptPh < 0) { this.ptPh = fmod(this.ptPh, 1); this.ptG = base * (1 + spray * (this.rnd.next() - 0.5)); }
      const ph2 = (this.ptPh + 0.5) % 1;
      const w1 = 1 - Math.abs(2 * this.ptPh - 1), w2 = 1 - Math.abs(2 * ph2 - 1);
      const o = this.hist.back(2 + this.ptPh * this.ptG) * w1 + this.hist.back(2 + ph2 * this.ptG) * w2;
      const d = buf[i];
      this.hist.write(d + o * fb * 0.8);
      buf[i] = d + (o - d) * this.engage * mix;
    }
  }
  stretch(buf, n, target, eRate, mix, pitchK, spray, sx, sy) {
    const sr = this.sr, speed = 0.1 + 0.9 * sx, g = Math.floor((0.04 + sy * 0.25) * sr), hopN = Math.max(16, Math.floor(g / 4));
    const rate = Math.pow(2, this.semis(pitchK) / 12);
    for (let i = 0; i < n; i++) {
      this.step(target, eRate);
      const d = buf[i]; this.hist.write(d);
      this.stRp += speed;
      if (this.hist.w - this.stRp > this.hist.size * 0.8) this.stRp = this.hist.w - sr * 0.05;
      if (this.stRp > this.hist.w - 2 - g * rate) this.stRp = this.hist.w - 2 - g * rate;
      if (++this.gCount >= hopN) { this.gCount = 0; this.spawn(this.hist, this.stRp + (this.rnd.next() - 0.5) * spray * g, rate, g, 0.5); }
      const o = this.grainSum();
      buf[i] = d + (o - d) * this.engage * mix;
    }
  }
  granular(buf, n, target, eRate, mix, spread, sizeK, sx, sy) {
    const sr = this.sr, look = (0.05 + sx * 1.95) * sr, density = 4 + sy * 76, size = Math.floor((0.02 + sizeK * 0.28) * sr);
    const overlap = density * size / sr, gain = 0.7 / Math.sqrt(Math.max(1, overlap)), prob = density / sr, steps = [-12, -5, 0, 0, 7, 12];
    for (let i = 0; i < n; i++) {
      this.step(target, eRate);
      const d = buf[i]; this.hist.write(d);
      if (this.engage > 0.01 && this.rnd.next() < prob) {
        const semi = this.rnd.next() < spread ? steps[this.rnd.int(steps.length)] : 0, rate = Math.pow(2, semi / 12);
        const pos = this.hist.w - look - this.rnd.next() * look * 0.5 - size * rate;
        this.spawn(this.hist, pos, rate, size, gain);
      }
      const o = this.grainSum();
      buf[i] = d + (o - d) * this.engage * mix;
    }
  }
  kristall(buf, n, target, eRate, mix, pitchK, revK, sx, sy) {
    const sr = this.sr, l = Math.floor((0.04 + sx * 0.46) * sr), r = Math.pow(2, this.semis(pitchK) / 12), interval = Math.max(32, Math.floor(l / 2));
    for (let i = 0; i < n; i++) {
      this.step(target, eRate);
      if (++this.gCount >= interval) {
        this.gCount = 0;
        if (this.rnd.next() < revK) this.spawn(this.dly, this.dly.w - 3, -r, l, 0.6);
        else this.spawn(this.dly, this.dly.w - l * r - 3, r, l, 0.6);
      }
      const g = this.grainSum(), d = buf[i];
      this.dly.write(d * this.engage + g * sy * 0.85);
      buf[i] = d + g * mix;
    }
  }
  fft(inverse) {
    const n = this.fftN, re = this.re, im = this.im;
    let j = 0;
    for (let i = 1; i < n; i++) {
      let bit = n >> 1;
      while (j & bit) { j ^= bit; bit >>= 1; }
      j ^= bit;
      if (i < j) { let t = re[i]; re[i] = re[j]; re[j] = t; t = im[i]; im[i] = im[j]; im[j] = t; }
    }
    for (let len = 2; len <= n; len <<= 1) {
      const half = len >> 1, step = n / len;
      for (let i = 0; i < n; i += len) {
        for (let k = 0; k < half; k++) {
          const wr = this.twC[k * step], wi = inverse ? this.twS[k * step] : -this.twS[k * step];
          const a = i + k, b = a + half;
          const xr = re[b] * wr - im[b] * wi, xi = re[b] * wi + im[b] * wr;
          re[b] = re[a] - xr; im[b] = im[a] - xi; re[a] += xr; im[a] += xi;
        }
      }
    }
  }
  spectralFrame(freeze, touching, start, sx, sy, p1, p2) {
    const N = this.fftN, re = this.re, im = this.im;
    for (let k = 0; k < N; k++) { re[k] = this.inFifo[(this.inPos + k) % N] * this.win[k]; im[k] = 0; }
    this.fft(false);
    const half = N / 2, tilt = (sy - 0.5) * 2;
    for (let k = 0; k <= half; k++) { this.mag[k] = Math.sqrt(re[k] * re[k] + im[k] * im[k]); if (!freeze) this.phs[k] = Math.atan2(im[k], re[k]); }
    if (freeze) {
      if (start || !this.frozen) { this.held.set(this.mag); this.frozen = true; }
      else if (touching) { const a = sx * 0.15; for (let k = 0; k <= half; k++) this.held[k] = this.held[k] * (1 - a) + this.mag[k] * a; }
    } else { const a = 0.5 + 0.49 * sx; for (let k = 0; k <= half; k++) this.held[k] = this.held[k] * a + this.mag[k] * (1 - a); }
    for (let k = 0; k <= half; k++) {
      let m = this.held[k] * Math.pow((k + 1) / (half / 8), tilt * 0.6);
      if (freeze && p1 > 0.01 && k >= 2) m += this.held[k >> 1] * p1 * 0.7;
      const ph = freeze ? (this.rnd.next() * 2 * PI) * (0.3 + 0.7 * p2) + this.phs[k] : this.phs[k] + p1 * PI * (this.rnd.next() * 2 - 1);
      if (freeze) this.phs[k] = ph;
      re[k] = m * Math.cos(ph); im[k] = m * Math.sin(ph);
      if (k >= 1 && k < half) { re[N - k] = re[k]; im[N - k] = -im[k]; }
    }
    this.fft(true);
    const scale = 0.5 / N;
    for (let k = 0; k < N; k++) this.outAcc[(this.outPos + k) % N] += re[k] * this.win[k] * scale;
  }
  spectral(buf, n, target, eRate, mix, p1, p2, sx, sy, freeze, touching, start) {
    let first = start;
    const N = this.fftN;
    for (let i = 0; i < n; i++) {
      this.step(target, eRate);
      const d = buf[i];
      this.inFifo[this.inPos] = d; this.inPos = (this.inPos + 1) % N;
      const y = this.outAcc[this.outPos]; this.outAcc[this.outPos] = 0; this.outPos = (this.outPos + 1) % N;
      if (++this.hopCount >= this.hop) { this.hopCount = 0; this.spectralFrame(freeze, touching, first, sx, sy, p1, p2); first = false; }
      buf[i] = d + (y - d) * this.engage * mix;
    }
    if (!touching && this.engage < 1e-3) this.frozen = false;
  }
  ringmod(buf, n, target, eRate, mix, lfoK, lfoDepth, sx, sy) {
    const sr = this.sr, fc = 20 * Math.pow(100, sx), lr = 0.1 * Math.pow(100, lfoK);
    for (let i = 0; i < n; i++) {
      this.step(target, eRate);
      this.rmLfo += lr / sr; if (this.rmLfo > 1) this.rmLfo -= 1;
      const f = fc * Math.pow(2, lfoDepth * Math.sin(2 * PI * this.rmLfo));
      this.rmPh += f / sr; if (this.rmPh > 1) this.rmPh -= 1;
      const s = Math.sin(2 * PI * this.rmPh), car = s + ((s >= 0 ? 1 : -1) - s) * sy, d = buf[i];
      buf[i] = d + (d * car - d) * this.engage * mix;
    }
  }
  fm(buf, n, target, eRate, mix, fb, tone, sx, sy) {
    const sr = this.sr, f = 20 * Math.pow(100, sx), depth = sy * 0.002 * sr, a = this.onePole(600 * Math.pow(30, tone));
    for (let i = 0; i < n; i++) {
      this.step(target, eRate);
      this.rmPh += f / sr; if (this.rmPh > 1) this.rmPh -= 1;
      const d = buf[i];
      this.fmBuf.write(d + this.fmFb * fb * 0.7);
      const o = this.fmBuf.back(2 + depth * (1 + Math.sin(2 * PI * this.rmPh)));
      this.fmFb = o; this.lp1 += (o - this.lp1) * a;
      buf[i] = d + (this.lp1 - d) * this.engage * mix;
    }
  }
  apChain(x, c, st) {
    let v = x;
    for (let s = 0; s < 4; s++) {
      const o = s * 4;
      const y = c[s] * c[s] * (v + st[o + 3]) - st[o + 1];
      st[o + 1] = st[o]; st[o] = v; st[o + 3] = st[o + 2]; st[o + 2] = y;
      v = y;
    }
    return v;
  }
  freqShift(buf, n, target, eRate, mix, range, tone, sx, sy) {
    const sr = this.sr, rng = 50 * Math.pow(40, range), shift = (sx - 0.5) * 2 * rng, a = this.onePole(800 * Math.pow(25, tone));
    for (let i = 0; i < n; i++) {
      this.step(target, eRate);
      const d = buf[i], x = d + this.fsFb * sy * 0.8;
      const iA = this.apChain(x, this.hCoefA, this.hA), q = this.apChain(x, this.hCoefB, this.hB);
      const iD = this.hDelay; this.hDelay = iA;
      this.fsPh += shift / sr; if (this.fsPh > 1) this.fsPh -= 1; if (this.fsPh < 0) this.fsPh += 1;
      const y = iD * Math.cos(2 * PI * this.fsPh) - q * Math.sin(2 * PI * this.fsPh);
      this.fsFb = y; this.lp1 += (y - this.lp1) * a;
      buf[i] = d + (this.lp1 - d) * this.engage * mix;
    }
  }
  resonator(buf, n, target, eRate, mix, chordK, bright, sx, sy) {
    const sr = this.sr, root = 36 + Math.round(sx * 36), ch = this.chords[Math.min(this.chords.length - 1, Math.floor(chordK * this.chords.length))];
    const q = 5 + sy * 300, k = 1 / q, a1 = this.rsA1, a2 = this.rsA2, a3 = this.rsA3, gn = this.rsGn;
    for (let j = 0; j < 6; j++) {
      const f = Math.min(0.45 * sr, 440 * Math.pow(2, (root + ch[j] - 69) / 12)), g = Math.tan(PI * f / sr);
      a1[j] = 1 / (1 + g * (g + k)); a2[j] = g * a1[j]; a3[j] = g * a2[j]; gn[j] = j < 3 ? 1 : bright * 1.5;
    }
    for (let i = 0; i < n; i++) {
      this.step(target, eRate);
      const d = buf[i], x = d * this.engage;
      let o = 0;
      for (let j = 0; j < 6; j++) {
        const v3 = x - this.rs2[j], v1 = a1[j] * this.rs1[j] + a2[j] * v3, v2 = this.rs2[j] + a2[j] * this.rs1[j] + a3[j] * v3;
        this.rs1[j] = 2 * v1 - this.rs1[j]; this.rs2[j] = 2 * v2 - this.rs2[j];
        o += v1 * k * gn[j] * 3;
      }
      buf[i] = d + tanh(o) * mix;
    }
  }
  shuffle(buf, n, frame0, target, eRate, mix, revK, pitchK, sx, sy, bar, ms) {
    const divs = [1 / 4, 1 / 8, 1 / 16, 1 / 32], slice = Math.max(256, Math.floor(bar * divs[Math.min(3, Math.floor(sx * 4))]));
    const maxBack = Math.max(1, Math.min(8, Math.floor(bar * 2 / slice)));
    for (let i = 0; i < n; i++) {
      this.step(target, eRate);
      const f = frame0 + i, d = buf[i];
      this.hist.write(d);
      const rel = f - ms, k = Math.floor(rel / slice), off = rel - k * slice;
      if (k !== this.shSlice) {
        this.shSlice = k;
        if (this.rnd.next() < sy) {
          this.shBack = 1 + this.rnd.int(maxBack); this.shRev = this.rnd.next() < revK;
          this.shRate = this.rnd.next() < pitchK ? (this.rnd.bool() ? 0.5 : 2) : 1;
        } else this.shBack = 0;
      }
      let wet = d;
      if (this.shBack > 0) {
        const srcStart = (k - this.shBack) * slice;
        const srcRel = this.shRev ? srcStart + slice - 1 - off * this.shRate : srcStart + off * this.shRate;
        const back = rel - srcRel;
        if (back >= 1) { const env = Math.min(1, Math.min(off / 96, (slice - off) / 96)); wet = d + (this.hist.back(back) - d) * env; }
      }
      buf[i] = d + (wet - d) * this.engage * mix;
    }
  }
  seqFx(buf, n, frame0, target, eRate, mix, rateK, variation, sx, sy, bar, ms) {
    const div = rateK < 0.33 ? 8 : rateK < 0.66 ? 16 : 32, step = Math.max(128, Math.floor(bar / div));
    const pat = this.patterns[Math.min(this.patterns.length - 1, Math.floor(sx * this.patterns.length))];
    for (let i = 0; i < n; i++) {
      this.step(target, eRate);
      const f = frame0 + i, d = buf[i];
      this.hist.write(d);
      const rel = f - ms, k = Math.floor(rel / step), off = rel - k * step;
      if (k !== this.shSlice) {
        this.shSlice = k;
        this.sqAction = pat[fmod(k, 16)];
        if (this.rnd.next() < variation) this.sqAction = this.rnd.int(7);
      }
      const env = Math.min(1, Math.min(off / 64, (step - off) / 64));
      let wetRaw;
      switch (this.sqAction) {
        case 1: wetRaw = this.hist.back(step); break;
        case 2: wetRaw = this.hist.back(2 * off + 1); break;
        case 3: wetRaw = 0; break;
        case 4: { const c = 8000 * Math.pow(300 / 8000, off / step); this.lp1 += (d - this.lp1) * this.onePole(c); wetRaw = this.lp1; break; }
        case 5: wetRaw = this.hist.back(step + off * 0.5); break;
        case 6: if (++this.sqHoldN >= 6) { this.sqHoldN = 0; this.sqHold = Math.round(d * 16) / 16; } wetRaw = this.sqHold; break;
        default: wetRaw = d;
      }
      const wet = d + (wetRaw - d) * env * Math.max(0.15, sy);
      buf[i] = d + (wet - d) * this.engage * mix;
    }
  }
  multiFx(buf, n, target, eRate, mix, color, room, sx, sy, bar) {
    const sr = this.sr, lpMode = sx < 0.5, amt = Math.abs(sx - 0.5) * 2;
    const fc = lpMode ? 18000 * Math.pow(0.02, amt) : 30 * Math.pow(200, amt);
    const g = Math.tan(PI * Math.min(fc, 0.45 * sr) / sr), kq = 2 - 1.8 * color;
    const a1 = 1 / (1 + g * (g + kq)), a2 = g * a1, a3 = g * a2, drive = 1 + amt * 6;
    let d = bar * 3 / 16; while (d > this.dly.size - 64) d /= 2;
    const fb = sy * 0.7, toneA = this.onePole(1500 + 12000 * (1 - room));
    for (let i = 0; i < n; i++) {
      this.step(target, eRate);
      const x = buf[i];
      const v3 = x - this.ic2, v1 = a1 * this.ic1 + a2 * v3, v2 = this.ic2 + a2 * this.ic1 + a3 * v3;
      this.ic1 = 2 * v1 - this.ic1; this.ic2 = 2 * v2 - this.ic2;
      const f = lpMode ? v2 : x - kq * v1 - v2;
      const sat = tanh(f * drive) / tanh(drive);
      this.lfoPh += 0.4 / sr; if (this.lfoPh > 1) this.lfoPh -= 1;
      const e = this.dly.back(d + sy * 0.004 * sr * Math.sin(2 * PI * this.lfoPh));
      this.mfxLp += (e - this.mfxLp) * toneA;
      this.dly.write(sat * this.engage + this.mfxLp * (fb + room * 0.2));
      const wet = sat + this.mfxLp * sy;
      buf[i] = x + (wet - x) * this.engage * mix + (this.engage < 0.01 ? this.mfxLp * sy * mix : 0);
    }
  }
}

class Fx {
  constructor(sr) {
    this.sr = sr;
    this.engage = 0; this.fx2 = new Fx2(sr); this.sx = 0.5; this.sy = 0.5; this.lastType = -1; this.wasTouching = false;
    this.histSize = sr * 8; this.hist = new Float32Array(this.histSize);
    this.sic1 = new Float32Array(4); this.sic2 = new Float32Array(4); this.bz1 = new Float32Array(3); this.bz2 = new Float32Array(3);
    this.cbSize = Math.floor(sr / 20) + 4; this.cb = new Float32Array(this.cbSize); this.cbW = 0; this.cbDelay = sr / 200; this.cbLp = 0;
    this.dlSize = sr * 4; this.dl = new Float32Array(this.dlSize); this.dlW = 0; this.dlTime = sr * 0.25; this.dlLp = 0; this.lfo = 0;
    this.anchor = 0; this.gateEnv = 0; this.rpLp = 0;
    this.holdCount = 0; this.heldV = 0; this.crLp = 0;
    this.mdSize = Math.floor(sr / 10); this.md = new Float32Array(this.mdSize); this.mdW = 0; this.mdLfo = 0;
    this.room = new Verb(sr, 1); this.hall = new Verb(sr, 1.7);
    this.preSize = Math.floor(sr / 2) + 1; this.pre = new Float32Array(this.preSize); this.preW = 0; this.hpState = 0; this.verbLp = 0;
    this.bandCount = 16; this.modBands = Array.from({ length: 16 }, () => new Band()); this.carBands = Array.from({ length: 16 }, () => new Band());
    this.env = new Float32Array(16); this.oscPh = new Float64Array(3); this.vocFormant = -1;
    this.dsLp = 0; this.dsHp = 0;
    this.delayFractions = [1 / 32, 1 / 16, 3 / 32, 1 / 8, 3 / 16, 1 / 4, 3 / 8, 1 / 2];
    this.stutterFractions = [1 / 4, 1 / 8, 1 / 16, 1 / 32, 1 / 64];
  }
  reset() {
    this.sic1.fill(0); this.sic2.fill(0); this.bz1.fill(0); this.bz2.fill(0); this.cb.fill(0); this.cbLp = 0;
    this.dl.fill(0); this.dlLp = 0; this.gateEnv = 0; this.rpLp = 0; this.holdCount = 0; this.heldV = 0; this.crLp = 0;
    this.md.fill(0); this.room.reset(); this.hall.reset(); this.pre.fill(0); this.hpState = 0; this.verbLp = 0;
    for (const b of this.modBands) b.reset(); for (const b of this.carBands) b.reset(); this.env.fill(0); this.vocFormant = -1;
    this.dsLp = 0; this.dsHp = 0; this.fx2.reset();
  }
  onePole(c) { return 1 - Math.exp(-2 * PI * c / this.sr); }
  step(target, rate) { if (this.engage < target) this.engage = Math.min(target, this.engage + rate); else if (this.engage > target) this.engage = Math.max(target, this.engage - rate); }
  cutoff() { return Math.min(40 * Math.pow(18000 / 40, this.sx), 0.45 * this.sr); }
  stutterLen(bar) {
    const idx = Math.min(this.stutterFractions.length - 1, Math.floor(this.sx * this.stutterFractions.length));
    let l = Math.floor(bar * this.stutterFractions[idx]);
    while (l > this.histSize / 2) l = Math.floor(l / 2);
    return Math.max(l, Math.floor(this.sr / 200));
  }
  process(buf, n, frame0, type, x, y, touching, mix, p1, p2, masterLen, masterStart, mic) {
    if (type !== this.lastType) { this.reset(); this.lastType = type; }
    for (let i = 0; i < n; i++) this.hist[fmod(frame0 + i, this.histSize)] = buf[i];
    const sr = this.sr;
    const sm = 1 - Math.exp(-n / (0.03 * sr));
    this.sx += (x - this.sx) * sm; this.sy += (y - this.sy) * sm;
    const bar = masterLen > 0 ? masterLen : sr * 2;
    const target = touching ? 1 : 0, eRate = 1 / (0.008 * sr);
    if (touching && !this.wasTouching && type === FX.STUTTER) {
      const l = this.stutterLen(bar);
      this.anchor = masterLen > 0 ? masterStart + Math.floor((frame0 - masterStart) / l) * l : frame0;
      if (frame0 - this.anchor >= this.histSize / 2) this.anchor = frame0;
    }
    this.wasTouching = touching;
    if (type >= FX.PHASER) { this.fx2.process(buf, n, frame0, type, this.sx, this.sy, touching, mix, p1, p2, bar, masterLen > 0 ? masterStart : 0); return; }
    const tails = type === FX.DELAY || type === FX.REVERB || type === FX.HALL;
    if (!tails && !touching && this.engage < 1e-4) { this.engage = 0; return; }
    switch (type) {
      case FX.LOWPASS: case FX.HIGHPASS: case FX.BANDPASS: case FX.NOTCH: this.svf(buf, n, target, eRate, mix, p1, p2, type); break;
      case FX.BELL: this.bell(buf, n, target, eRate, mix, p1, p2); break;
      case FX.PEAK: this.peak(buf, n, target, eRate, mix, p1, p2); break;
      case FX.COMB: this.comb(buf, n, target, eRate, mix, p1, p2); break;
      case FX.DELAY: this.delay(buf, n, target, eRate, mix, p1, p2, bar); break;
      case FX.STUTTER: this.stutter(buf, n, frame0, target, eRate, mix, p1, p2, bar); break;
      case FX.FLANGER: this.flanger(buf, n, target, eRate, mix, p1, p2); break;
      case FX.CHORUS: this.chorus(buf, n, target, eRate, mix, p1, p2); break;
      case FX.REVERB: this.reverb(buf, n, target, eRate, mix, p1, p2, this.room, 0.7 + this.sx * 0.28, this.sy * 0.7); break;
      case FX.HALL: this.reverb(buf, n, target, eRate, mix, p1, p2, this.hall, 0.88 + this.sx * 0.115, 0.6 - this.sy * 0.55); break;
      case FX.VOCODER: this.vocoder(buf, n, target, eRate, mix, p1, p2, mic); break;
      case FX.DISTORTION: this.distortion(buf, n, target, eRate, mix, p1, p2); break;
      case FX.CRUSH: this.crush(buf, n, target, eRate, mix, p1, p2); break;
    }
  }
  svf(buf, n, target, eRate, mix, steep, drive, kind) {
    const sr = this.sr, st = stages(steep), g = Math.tan(PI * this.cutoff() / sr), kRes = 2 - 1.93 * this.sy;
    const kBut = kind === FX.LOWPASS || kind === FX.HIGHPASS ? 1.414 : kRes;
    const rA1 = 1 / (1 + g * (g + kRes)), rA2 = g * rA1, rA3 = g * rA2, bA1 = 1 / (1 + g * (g + kBut)), bA2 = g * bA1, bA3 = g * bA2;
    const gain = 1 + drive * 7, norm = 1 / tanh(gain);
    for (let i = 0; i < n; i++) {
      this.step(target, eRate);
      const dry = buf[i];
      let v = drive < 0.01 ? dry : tanh(dry * gain) * norm;
      for (let s = 0; s < st; s++) {
        const last = s === st - 1, k = last ? kRes : kBut, a1 = last ? rA1 : bA1, a2 = last ? rA2 : bA2, a3 = last ? rA3 : bA3;
        const v0 = v, v3 = v0 - this.sic2[s], v1 = a1 * this.sic1[s] + a2 * v3, v2 = this.sic2[s] + a2 * this.sic1[s] + a3 * v3;
        this.sic1[s] = 2 * v1 - this.sic1[s]; this.sic2[s] = 2 * v2 - this.sic2[s];
        v = kind === FX.LOWPASS ? v2 : kind === FX.HIGHPASS ? v0 - k * v1 - v2 : kind === FX.BANDPASS ? k * v1 : v0 - k * v1;
      }
      buf[i] = dry + (v - dry) * this.engage * mix;
    }
  }
  bell(buf, n, target, eRate, mix, width, steps) {
    const sr = this.sr, st = Math.min(3, stages(steps)), f = this.cutoff(), gainDb = (this.sy * 2 - 1) * 18;
    const q = 0.3 * Math.pow(12 / 0.3, 1 - width), aa = Math.pow(10, gainDb / 40), w0 = 2 * PI * f / sr, alpha = Math.sin(w0) / (2 * q), cw = Math.cos(w0);
    const a0 = 1 + alpha / aa, b0 = (1 + alpha * aa) / a0, b1 = -2 * cw / a0, b2 = (1 - alpha * aa) / a0, a1 = -2 * cw / a0, a2 = (1 - alpha / aa) / a0;
    for (let i = 0; i < n; i++) {
      this.step(target, eRate);
      const dry = buf[i];
      let v = dry;
      for (let s = 0; s < st; s++) { const y = b0 * v + this.bz1[s]; this.bz1[s] = b1 * v - a1 * y + this.bz2[s]; this.bz2[s] = b2 * v - a2 * y; v = y; }
      buf[i] = dry + (v - dry) * this.engage * mix;
    }
  }
  peak(buf, n, target, eRate, mix, dryAmt, drive) {
    const g = Math.tan(PI * this.cutoff() / this.sr), k = 1 / (0.7 + this.sy * 35), a1 = 1 / (1 + g * (g + k)), a2 = g * a1, a3 = g * a2;
    const boost = 1.5 + 4 * this.sy, gain = 1 + drive * 7, norm = 1 / tanh(gain);
    for (let i = 0; i < n; i++) {
      this.step(target, eRate);
      const dry = buf[i], v0 = drive < 0.01 ? dry : tanh(dry * gain) * norm;
      const v3 = v0 - this.sic2[0], v1 = a1 * this.sic1[0] + a2 * v3, v2 = this.sic2[0] + a2 * this.sic1[0] + a3 * v3;
      this.sic1[0] = 2 * v1 - this.sic1[0]; this.sic2[0] = 2 * v2 - this.sic2[0];
      const wet = v0 * dryAmt + k * v1 * boost;
      buf[i] = dry + (wet - dry) * this.engage * mix;
    }
  }
  comb(buf, n, target, eRate, mix, mode, tone) {
    const freq = 30 * Math.pow(3000 / 30, this.sx), d = Math.min(this.cbSize - 3, this.sr / freq), fb = (this.sy * 2 - 1) * 0.97;
    const ff = mode >= 0.5, lpA = this.onePole(800 * Math.pow(20000 / 800, tone));
    for (let i = 0; i < n; i++) {
      this.step(target, eRate);
      this.cbDelay += (d - this.cbDelay) * 0.002;
      const delayed = readInterp(this.cb, this.cbSize, this.cbW - this.cbDelay);
      this.cbLp += (delayed - this.cbLp) * lpA;
      const dry = buf[i];
      let wet;
      if (ff) { this.cb[this.cbW] = dry; wet = (dry + fb * this.cbLp) * 0.6; }
      else { const y = dry + fb * this.cbLp; this.cb[this.cbW] = y; wet = y * 0.5; }
      if (++this.cbW === this.cbSize) this.cbW = 0;
      buf[i] = dry + (wet - dry) * this.engage * mix;
    }
  }
  delay(buf, n, target, eRate, mix, tone, wobble, bar) {
    const sr = this.sr, idx = Math.min(this.delayFractions.length - 1, Math.floor(this.sx * this.delayFractions.length));
    let t = bar * this.delayFractions[idx];
    while (t > this.dlSize - 4) t /= 2;
    t = Math.max(t, sr * 0.01);
    const fb = this.sy * 0.95, lpA = this.onePole(600 * Math.pow(16000 / 600, tone)), lfoInc = 2 * PI * 0.6 / sr, wob = wobble * 0.004 * sr;
    for (let i = 0; i < n; i++) {
      this.step(target, eRate);
      this.dlTime += (t - this.dlTime) * 0.0006;
      this.lfo += lfoInc; if (this.lfo > 2 * PI) this.lfo -= 2 * PI;
      const out = readInterp(this.dl, this.dlSize, this.dlW - (this.dlTime + wob * Math.sin(this.lfo)));
      this.dlLp += (out - this.dlLp) * lpA;
      const dry = buf[i];
      this.dl[this.dlW] = dry * this.engage + this.dlLp * fb;
      if (++this.dlW === this.dlSize) this.dlW = 0;
      buf[i] = dry + out * mix;
    }
  }
  stutter(buf, n, frame0, target, eRate, mix, pitch, tone, bar) {
    const l = this.stutterLen(bar), speed = Math.pow(2, (pitch - 0.5) * 2), duty = 1 - 0.85 * this.sy;
    const lpA = this.onePole(400 * Math.pow(20000 / 400, tone)), gA = this.onePole(400);
    for (let i = 0; i < n; i++) {
      this.step(target, eRate);
      const t = frame0 + i, rel = t - this.anchor, dry = buf[i];
      let wet = dry;
      if (rel >= 0) {
        const inCycle = Math.floor((rel * speed) % l), src = this.anchor + inCycle;
        if (src < t && t - src < this.histSize) wet = this.hist[fmod(src, this.histSize)];
        const phase = (rel % l) / l, gate = phase < duty ? 1 : 0;
        this.gateEnv += (gate - this.gateEnv) * gA;
        wet *= this.gateEnv;
      }
      this.rpLp += (wet - this.rpLp) * lpA;
      buf[i] = dry + (this.rpLp - dry) * this.engage * mix;
    }
  }
  flanger(buf, n, target, eRate, mix, feedback, base) {
    const sr = this.sr, inc = 2 * PI * 0.05 * Math.pow(100, this.sx) / sr, baseS = (0.0005 + base * 0.008) * sr, depthS = this.sy * 0.005 * sr, fb = feedback * 0.9;
    for (let i = 0; i < n; i++) {
      this.step(target, eRate);
      this.mdLfo += inc; if (this.mdLfo > 2 * PI) this.mdLfo -= 2 * PI;
      const d = Math.min(this.mdSize - 3, baseS + depthS * (0.5 + 0.5 * Math.sin(this.mdLfo)));
      const delayed = readInterp(this.md, this.mdSize, this.mdW - d), dry = buf[i];
      this.md[this.mdW] = dry + delayed * fb;
      if (++this.mdW === this.mdSize) this.mdW = 0;
      const wet = 0.7 * (dry + delayed);
      buf[i] = dry + (wet - dry) * this.engage * mix;
    }
  }
  chorus(buf, n, target, eRate, mix, voices, base) {
    const sr = this.sr, inc = 2 * PI * 0.1 * Math.pow(40, this.sx) / sr, baseS = (0.008 + base * 0.022) * sr, depthS = (0.0005 + this.sy * 0.006) * sr;
    for (let i = 0; i < n; i++) {
      this.step(target, eRate);
      this.mdLfo += inc; if (this.mdLfo > 2 * PI) this.mdLfo -= 2 * PI;
      const dry = buf[i];
      this.md[this.mdW] = dry;
      const w1 = readInterp(this.md, this.mdSize, this.mdW - (baseS + depthS * Math.sin(this.mdLfo)));
      const w2 = readInterp(this.md, this.mdSize, this.mdW - Math.min(this.mdSize - 3, baseS * 1.3 + depthS * Math.sin(this.mdLfo + 2.094)));
      const w3 = readInterp(this.md, this.mdSize, this.mdW - (baseS * 0.7 + depthS * Math.sin(this.mdLfo + 4.189)));
      if (++this.mdW === this.mdSize) this.mdW = 0;
      const wet = (dry + w1 + (w2 + w3) * voices) / (2 + 2 * voices) * 1.6;
      buf[i] = dry + (wet - dry) * this.engage * mix;
    }
  }
  reverb(buf, n, target, eRate, mix, preDelay, lowCut, verb, roomFb, damp) {
    const preS = Math.min(this.preSize - 1, Math.floor(preDelay * 0.5 * this.sr)), hpA = this.onePole(20 * Math.pow(40, lowCut));
    for (let i = 0; i < n; i++) {
      this.step(target, eRate);
      const dry = buf[i];
      this.pre[this.preW] = dry * this.engage;
      let r = this.preW - preS; if (r < 0) r += this.preSize;
      const di = this.pre[r];
      if (++this.preW === this.preSize) this.preW = 0;
      this.hpState += (di - this.hpState) * hpA;
      const out = verb.tick((di - this.hpState) * 0.015, roomFb, damp);
      buf[i] = dry + out * mix * 2.5;
    }
  }
  vocoder(buf, n, target, eRate, mix, chord, micAmt, mic) {
    const sr = this.sr, semis = Math.round(this.sx * 48), f0 = 55 * Math.pow(2, semis / 12);
    const incs = [f0 / sr, f0 * 1.4983 / sr, f0 * 2 / sr];
    const v2 = Math.max(0, Math.min(1, chord * 2)), v3 = Math.max(0, Math.min(1, chord * 2 - 1)), formant = 0.5 * Math.pow(4, this.sy);
    if (Math.abs(formant - this.vocFormant) > 0.01) {
      this.vocFormant = formant;
      for (let b = 0; b < 16; b++) { const f = 120 * Math.pow(8000 / 120, b / 15); this.modBands[b].set(f, 7, sr); this.carBands[b].set(f * formant, 7, sr); }
    }
    const att = this.onePole(200), rel = this.onePole(25);
    for (let i = 0; i < n; i++) {
      this.step(target, eRate);
      const dry = buf[i];
      for (let o = 0; o < 3; o++) { this.oscPh[o] += incs[o]; if (this.oscPh[o] >= 1) this.oscPh[o] -= 1; }
      const car = (2 * this.oscPh[0] - 1) + (2 * this.oscPh[1] - 1) * v2 + (2 * this.oscPh[2] - 1) * v3;
      const mod = dry + (mic && i < mic.length ? mic[i] : 0) * micAmt * 2;
      let wet = 0;
      for (let b = 0; b < 16; b++) {
        const m = Math.abs(this.modBands[b].bp(mod)), e = this.env[b];
        this.env[b] = e + (m - e) * (m > e ? att : rel);
        wet += this.carBands[b].bp(car) * this.env[b];
      }
      wet *= 6;
      buf[i] = dry + (wet - dry) * this.engage * mix;
    }
  }
  distortion(buf, n, target, eRate, mix, kind, level) {
    const gain = 1 + this.sx * this.sx * 60, lpA = this.onePole(700 * Math.pow(14000 / 700, this.sy)), hpA = this.onePole(60), outGain = 0.3 + level * 0.9;
    for (let i = 0; i < n; i++) {
      this.step(target, eRate);
      const dry = buf[i];
      this.dsHp += (dry - this.dsHp) * hpA;
      const v = (dry - this.dsHp) * gain, soft = tanh(v), hard = Math.max(-1, Math.min(1, v)), fold = Math.sin(v * 1.5);
      const shaped = kind < 0.5 ? soft + (hard - soft) * kind * 2 : hard + (fold - hard) * (kind - 0.5) * 2;
      this.dsLp += (shaped - this.dsLp) * lpA;
      buf[i] = dry + (this.dsLp * outGain - dry) * this.engage * mix;
    }
  }
  crush(buf, n, target, eRate, mix, drive, tone) {
    const hold = 1 + this.sx * this.sx * 40, bits = 16 - this.sy * 14, steps = Math.pow(2, bits - 1), gain = 1 + drive * 10, norm = 1 / tanh(gain);
    const lpA = this.onePole(500 * Math.pow(20000 / 500, tone));
    for (let i = 0; i < n; i++) {
      this.step(target, eRate);
      const dry = buf[i];
      this.holdCount += 1;
      if (this.holdCount >= hold) { this.holdCount -= hold; this.heldV = tanh(dry * gain) * norm; }
      const q = Math.round(this.heldV * steps) / steps;
      this.crLp += (q - this.crLp) * lpA;
      buf[i] = dry + (this.crLp - dry) * this.engage * mix;
    }
  }
}
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
// ===================================================================== Audio-Engine (Port von Engine.kt) – läuft im AudioWorklet
const PADS = 12, STEPS = 16, MAXS = 66, SLOTS = 3, SCENES = 12, ARR_ROWS = 14, ARR_MAX = 64, BLOCK = 128;
const P1_DEFAULT = [0.3, 0.3, 0, 0.3, 0.4, 0.6, 0.25, 0.6, 0.5, 0.5, 0.6, 0.15, 0.4, 0.3, 0.2, 0.2, 0.5, 0, 0, 0.5, 0, 0.5, 0.5, 0.3, 0, 0.5, 0.2, 0, 0.2, 0.2, 0.3, 0.3, 0.4, 0.3, 0.5, 0.4, 1];
const P2_DEFAULT = [0, 0, 0, 0, 0, 0, 0.7, 0.2, 1, 0.3, 0.4, 0.2, 0.3, 0, 0.6, 1, 0.4, 0, 0.3, 0.5, 0, 0.6, 0.6, 0.5, 0.2, 0.2, 0.6, 0.3, 0, 0.7, 0.2, 0.7, 0.5, 0.4, 0.2, 0.4, 0.5];
const ST = { EMPTY: 0, RECORDING: 1, PLAYING: 2, MUTED: 3, OVERDUB: 4, ONESHOT: 5 };
const LAUNCH_Q = [0, 0.25, 0.5, 1, 2, 4];
const REPEAT_DIVS = [4, 8, 16, 32, 12, 24];
const FADE_MS = 4;

function softClip(x) {
  const a = Math.abs(x);
  if (a <= 0.9) return x;
  const y = 0.9 + 0.1 * Math.tanh((a - 0.9) / 0.1);
  return x < 0 ? -y : y;
}
const floorDiv = (a, b) => Math.floor(a / b);
const ceilDiv = (a, b) => -Math.floor(-a / b);

class Loop {
  constructor(data) {
    this.data = data; this.start = 0; this.muted = false; this.oneShot = false; this.shotStart = null; this.shotGain = 1;
    this.fade = 1; this.semi = 0; this.rate = 1; this.slices = null; this.bars = 1;
    this.src = null; this.srcBpm = 0; this.warpBars = 0; this.gain = 1; this.pendAt = null; this.pendOn = false;
  }
  clone(data) {
    const c = new Loop(data);
    Object.assign(c, { start: this.start, muted: this.muted, oneShot: this.oneShot, semi: this.semi, rate: this.rate,
      slices: this.slices ? this.slices.slice() : null, bars: this.bars, src: this.src, srcBpm: this.srcBpm, warpBars: this.warpBars, gain: this.gain });
    return c;
  }
}

class Engine {
  constructor(sr, port) {
    this.sr = sr; this.port = port;
    this.w = 0; this.outLat = Math.round(sr * 0.03); this.inLat = Math.round(sr * 0.02);
    this.ringSize = sr * (MAXS + 4); this.inRing = new Float32Array(this.ringSize); this.outRing = new Float32Array(this.ringSize);
    this.loops = new Array(PADS).fill(null); this.takes = new Array(PADS).fill(null);
    this.padOneShot = new Array(PADS).fill(false); this.padSemi = new Array(PADS).fill(0); this.padSource = new Array(PADS).fill(0);
    this.padFx = new Array(PADS).fill(true); this.solo = new Array(PADS).fill(false); this.soloAny = false; this.levels = new Float32Array(PADS);
    this.inputLevel = 0; this.outputLevel = 0;
    this.seq = Array.from({ length: PADS }, () => new Float32Array(STEPS));
    this.seqPlaying = false; this.seqRecording = false; this.seqSelected = -1; this.seqOrigin = 0;
    this.masterLen = 0; this.masterStart = 0; this.fixedBpm = 0; this.metronome = false; this.swing = 0;
    this.countIn = true; this.countFrom = null; this.countTo = null; this.countClick = null; this.countHigh = false;
    this.clickStart = null; this.clickHigh = false;
    this.notes = Array.from({ length: PADS }, () => []); this.rollBars = new Array(PADS).fill(1); this.rollQuant = 16;
    this.voices = Array.from({ length: 16 }, () => ({ data: null, pos: 0, rate: 1, loop: false, gain: 1, releasing: false, active: false, pad: -1, id: 0, vel: 1, wait: 0, offFrame: Infinity }));
    this.voiceIds = 0;
    this.fxs = [new Fx(sr), new Fx(sr), new Fx(sr)];
    this.slotType = [0, FX.DELAY, FX.REVERB]; this.slotOn = [true, false, false];
    this.slotKnobs = this.slotType.map((t) => [1, P1_DEFAULT[t], P2_DEFAULT[t]]); this.editSlot = 0; this.vol = 0.8;
    this.fxX = 0.5; this.fxY = 0.5; this.fxTouch = false; this.liveTouch = false;
    this.mot = { state: 0, x: new Float32Array(40000), y: new Float32Array(40000), t: new Uint8Array(40000), count: 0, start: 0, len: 1, stopReq: false, curX: 0.5, curY: 0.5, curT: false };
    this.effX = 0.5; this.effY = 0.5; this.effTouch = false;
    this.st = newInst(); this.acid = new AcidSynth(sr); this.drum = new DrumMachine(sr);
    this.liveBars = 0; this.bounce = true; this.tapOverdub = true; this.launchIdx = 3;
    this.songRec = false; this.songDry = false; this.songChunks = []; this.songFrames = 0;
    this.repeatOn = false; this.repeatIdx = 2; this.repPad = new Array(PADS).fill(false); this.repPadFrom = new Array(PADS).fill(0);
    this.repDrum = new Array(DV).fill(0); this.repDrumFrom = new Array(DV).fill(0); this.repOrigin = 0; this.repAny = false;
    this.undoStack = []; this.redoStack = [];
    this.scenes = new Array(SCENES).fill(null); this.curScene = 0; this.queuedScene = -1; this.sceneChain = false; this.sceneBarCount = 0;
    this.arr = Array.from({ length: ARR_ROWS }, () => new Array(ARR_MAX).fill(false)); this.arrScene = new Array(ARR_MAX).fill(-1);
    this.songMode = false; this.songBars = 16; this.arrBar = 0; this.arrApplyNow = false;
    this.padChainOn = new Array(PADS).fill(false);
    this.pfxType = Array.from({ length: PADS }, () => [-1, -1, -1]);
    this.pfxPar = Array.from({ length: PADS }, () => [0, 1, 2].map(() => [0.5, 0.5, 1, 0.5, 0.5]));
    this.pfxInst = Array.from({ length: PADS }, () => [null, null, null]);
    this.out = new Float32Array(BLOCK); this.bus = new Float32Array(BLOCK); this.dryBuf = new Float32Array(BLOCK);
    this.mic = new Float32Array(BLOCK); this.scratch = new Float32Array(BLOCK);
    this.padBufs = Array.from({ length: PADS }, () => new Float32Array(BLOCK)); this.chainNow = new Array(PADS).fill(false);
    this.dirty = { seq: true, notes: true, inst: true, scenes: true, arr: true };
    this.blockCount = 0;
    this.scope = new Float32Array(2048); this.scopeW = 0;
    port.onmessage = (e) => { try { this.handle(e.data); } catch (err) { this.post({ t: 'error', msg: String(err && err.stack || err) }); } };
  }

  post(m, tr) { this.port.postMessage(m, tr || []); }
  hint(msg) { this.post({ t: 'hint', msg }); }
  heard() { return this.w - this.outLat; }
  L() { return this.outLat + this.inLat; }
  seqBar() { return this.masterLen > 0 ? this.masterLen : this.sr * 2; }
  seqStart() { return this.masterLen > 0 ? this.masterStart : this.seqOrigin; }
  currentBpm() { return this.masterLen > 0 ? this.sr * 240 / this.masterLen : 0; }
  swingOffset(bar) { return Math.floor(this.swing * 0.5 * bar / STEPS); }
  stepFrame(origin, bar, n) { return origin + Math.floor(n * bar / STEPS); }
  anyLooping() { return this.loops.some((l) => l && !l.oneShot); }
  releaseMaster() { if (this.fixedBpm <= 0) this.masterLen = 0; }
  audible(p) { return !this.soloAny || this.solo[p]; }

  // ------------------------------------------------------------------ Transport
  startTransportAt(start) {
    this.seqOrigin = start;
    if (this.masterLen > 0) this.masterStart = start;
    for (const l of this.loops) if (l && !l.oneShot) l.start = start;
    this.sceneBarCount = 0; this.arrBar = 0; this.arrApplyNow = true;
    this.seqPlaying = true;
  }
  transportPlay(withCount) {
    if (this.seqPlaying) return;
    const bar = this.seqBar(), start = this.w + Math.floor(this.sr / 50) + (withCount ? bar : 0);
    if (withCount) { this.countFrom = start - bar; this.countTo = start; } else { this.countFrom = null; this.countTo = null; }
    this.countClick = null;
    this.startTransportAt(start);
  }
  transportStop() {
    for (let p = 0; p < PADS; p++) {
      const t = this.takes[p];
      if (t && t.overdub && !t.released) this.releaseTake(t, this.heard());
      if (this.loops[p]) this.loops[p].pendAt = null;
    }
    this.seqPlaying = false; this.seqRecording = false; this.countFrom = null; this.countTo = null;
    this.acid.push(-1, false, 0, false, false);
  }
  countInBeat() {
    if (this.countTo == null || !this.seqPlaying) return 0;
    const h = this.heard();
    if (h >= this.countTo) return 0;
    const bar = this.countTo - this.countFrom;
    if (h < this.countFrom || bar <= 0) return 1;
    return Math.min(4, Math.max(1, Math.floor((h - this.countFrom) * 4 / bar) + 1));
  }
  recLive() {
    if (!this.seqRecording || !this.seqPlaying) return false;
    return this.countTo == null || this.heard() >= this.countTo - this.seqBar() / 32;
  }
  toggleRec() {
    if (this.seqRecording) { this.seqRecording = false; return; }
    this.pushPatternUndo('Pattern-Aufnahme');
    if (!this.seqPlaying) this.transportPlay(this.countIn);
    this.seqRecording = true;
  }
  setBpm(bpm) {
    const b = Math.max(40, Math.min(240, bpm));
    this.fixedBpm = b;
    const old = this.masterLen, nl = Math.round(this.sr * 240 / b);
    if (old <= 0) this.masterStart = this.w;
    else if (nl !== old) {
      const h = this.heard(), frac = fmod(h - this.masterStart, old) / old;
      this.masterStart = h - Math.floor(frac * nl);
    }
    this.masterLen = nl;
    if (nl !== old && old > 0) this.requestRewarp();
  }
  setAutoTempo() { this.fixedBpm = 0; if (!this.anyLooping()) this.masterLen = 0; }

  // ------------------------------------------------------------------ Sequenzer
  instStep(step, f, w, stepLen) {
    const st = this.st;
    if (st.acidOn && st.acidGate[step]) {
      const prev = fmod(step - 1, STEPS), next = (step + 1) % STEPS;
      const slideIn = st.acidSlide[prev] && st.acidGate[prev];
      this.acid.push(f, true, st.acidSemi[step], st.acidAcc[step], slideIn);
      if (!(st.acidSlide[step] && st.acidGate[next])) this.acid.push(f + stepLen / 2, false, 0, false, false);
    }
    if (st.drumOn) for (let v = 0; v < DV; v++) { const x = st.drumSteps[v][step]; if (x > 0) this.drum.trigger(v, f - w, x === 2); }
  }
  runSequencer(w, block) {
    if (!this.seqPlaying) return;
    const bar = this.seqBar();
    this.runRoll(w, block, bar);
    const origin = this.seqStart(), sw = this.swingOffset(bar);
    let n = ceilDiv((w - origin) * STEPS, bar) - 1;
    for (;;) {
      const base = this.stepFrame(origin, bar, n);
      if (base >= w + block) break;
      const f = fmod(n, 2) === 1 ? base + sw : base;
      if (f >= w && f < w + block && base >= origin) {
        const step = fmod(n, STEPS);
        this.instStep(step, f, w, bar / STEPS);
        for (let p = 0; p < PADS; p++) {
          const vel = this.seq[p][step];
          if (vel <= 0) continue;
          const l = this.loops[p];
          if (l && l.oneShot && !l.muted && this.audible(p)) { l.shotGain = vel; l.shotStart = f; }
        }
      }
      n++;
    }
  }
  liveStep() {
    const bar = this.seqBar(), origin = this.seqStart(), h = this.heard();
    const n = Math.floor(((h - origin) * STEPS + bar / 2) / bar);
    const f = this.stepFrame(origin, bar, n);
    return [fmod(n, STEPS), f >= this.w];
  }

  // ------------------------------------------------------------------ Matrix / Stimmen
  startVoice(data, pad, rate, loop) {
    if (!data || data.length < 2) return -1;
    let v = this.voices.find((x) => !x.active);
    if (!v) v = this.voices.reduce((a, b) => (a.id < b.id ? a : b));
    Object.assign(v, { data, pos: 0, rate, loop, gain: 1, releasing: false, pad, vel: 1, wait: 0, offFrame: Infinity, id: ++this.voiceIds, active: true });
    return v.id;
  }
  noteOn(p, semi, id) {
    const l = this.loops[p];
    if (!l) return;
    const vid = this.startVoice(l.data, p, Math.pow(2, semi / 12), !l.oneShot);
    this.kbVoices = this.kbVoices || new Map();
    this.kbVoices.set(id, vid);
    if (this.recLive()) {
      const ev = this.recordNoteOn(p, semi);
      if (ev) { this.kbEvents = this.kbEvents || new Map(); this.kbEvents.set(id, ev); this.seqSelected = p; }
    }
  }
  noteOff(id) {
    const vid = this.kbVoices && this.kbVoices.get(id);
    if (vid != null) { for (const v of this.voices) if (v.active && v.id === vid) v.releasing = true; this.kbVoices.delete(id); }
    const ev = this.kbEvents && this.kbEvents.get(id);
    if (ev) { this.recordNoteOff(ev); this.kbEvents.delete(id); }
  }
  qPos(v, q) { return q <= 0 ? v : Math.round(v * q) / q; }
  qLen(v, q) { return q <= 0 ? Math.max(1 / 64, v) : Math.max(1 / q, Math.round(v * q) / q); }
  recordNoteOn(p, semi) {
    const bar = this.seqBar(), origin = this.seqStart(), bars = this.rollBars[p], loopLen = bar * bars, h = this.heard();
    let pos = fmod(h - origin, loopLen) / bar;
    pos = this.qPos(pos, this.rollQuant);
    if (pos >= bars) pos -= bars;
    const ev = { pos, len: 1 / Math.max(4, this.rollQuant), semi, vel: 1, validFrom: h + loopLen / 2, startHeard: h };
    this.notes[p] = this.notes[p].filter((x) => !(Math.abs(x.pos - pos) < 1e-6 && x.semi === semi));
    this.notes[p].push(ev);
    this.dirty.notes = true;
    return ev;
  }
  recordNoteOff(ev) {
    const raw = Math.max(1, this.heard() - ev.startHeard) / this.seqBar();
    ev.len = this.qLen(raw, this.rollQuant);
    this.dirty.notes = true;
  }
  runRoll(w, block, bar) {
    const origin = this.seqStart(), end = w + block;
    for (let p = 0; p < PADS; p++) {
      const list = this.notes[p];
      if (!list.length) continue;
      const l = this.loops[p];
      if (!l || l.muted || !this.audible(p)) continue;
      const loopLen = bar * this.rollBars[p];
      for (const ev of list) {
        const u = (ev.pos * STEPS) % 2;
        const off = Math.floor(ev.pos * bar) + (Math.abs(u - 1) < 0.25 ? this.swingOffset(bar) : 0);
        const k = ceilDiv(w - origin - off, loopLen);
        const f = origin + k * loopLen + off;
        if (f >= end || f < w || f < origin) continue;
        if (ev.validFrom != null && f < ev.validFrom) continue;
        const id = this.startVoice(l.data, p, Math.pow(2, ev.semi / 12), !l.oneShot);
        if (id < 0) continue;
        for (const v of this.voices) if (v.id === id) { v.wait = Math.max(0, f - w); v.vel = ev.vel; v.offFrame = f + Math.max(64, Math.floor(ev.len * bar)); }
      }
    }
  }
  renderVoices(out, bus, block, w) {
    const relStep = 1 / (0.12 * this.sr);
    for (const v of this.voices) {
      if (!v.active) continue;
      const d = v.data, len = d.length;
      const pad = v.pad;
      const dst = pad >= 0 && this.chainNow[pad] ? this.padBufs[pad] : pad >= 0 && this.padFx[pad] ? bus : out;
      const pg = pad >= 0 && this.loops[pad] ? this.loops[pad].gain : 1;
      let peak = 0;
      for (let i = 0; i < block; i++) {
        if (v.wait > 0) { v.wait--; continue; }
        if (!v.releasing && w + i >= v.offFrame) v.releasing = true;
        let i0 = Math.floor(v.pos);
        if (i0 >= len - 1) {
          if (v.loop) { v.pos -= len - 1; i0 = Math.max(0, Math.min(len - 2, Math.floor(v.pos))); }
          else { v.active = false; break; }
        }
        const fr = v.pos - i0, sm = (d[i0] + (d[i0 + 1] - d[i0]) * fr) * v.gain * v.vel * pg;
        dst[i] += sm;
        const a = Math.abs(sm); if (a > peak) peak = a;
        if (v.releasing) { v.gain -= relStep; if (v.gain <= 0) { v.active = false; break; } }
        v.pos += v.rate;
      }
      if (pad >= 0 && pad < PADS) this.levels[pad] = Math.max(this.levels[pad], peak);
    }
  }

  // ------------------------------------------------------------------ Aufnahme
  quantizeBars(held) {
    const m = this.masterLen, maxF = this.sr * MAXS, h = Math.max(1, held);
    let best = m, err = Infinity;
    for (const b of [1, 2, 4, 8, 16, 32]) {
      const l = m * b;
      if (l > maxF && b > 1) break;
      const e = Math.abs(Math.log(h / l));
      if (e < err) { err = e; best = l; }
    }
    return best;
  }
  quantizeLen(held) {
    const m = this.masterLen, maxF = this.sr * MAXS, h = Math.max(1, held);
    let best = m, err = Infinity;
    for (let k = -3; k <= 3; k++) {
      const l = k >= 0 ? m * Math.pow(2, k) : m / Math.pow(2, -k);
      if (l < this.sr / 20 || l > maxF) continue;
      const e = Math.abs(Math.log(h / l));
      if (e < err) { err = e; best = l; }
    }
    return Math.round(best);
  }
  newTake(overdub, p) {
    return { overdub, oneShot: this.padOneShot[p], down: this.heard(), downW: this.w, released: false, armed: false, latched: false, live: false,
      src: 0, qOut: null, from: null, to: null, cursor: null, bounceSrc: null, before: null };
  }
  padDown(p, repeatHold) {
    const existing = this.takes[p];
    if (existing) {
      if (existing.overdub && existing.latched) { if (!existing.released) this.releaseTake(existing, this.heard()); return; }
      if (existing.live) { if (!existing.released) this.releaseTake(existing, this.heard()); else this.takes[p] = null; }
      return;
    }
    const loop = this.loops[p];
    if (loop && loop.oneShot) {
      this.seqSelected = p;
      if (repeatHold && this.repeatOn) this.repeatPadDown(p);
      if (this.recLive()) {
        const [step, ahead] = this.liveStep();
        this.seq[p][step] = 1; this.dirty.seq = true;
        if (ahead) return;
      }
      loop.shotGain = 1; loop.shotStart = this.w;
      return;
    }
    if (!loop && (this.liveBars !== 0 || this.padSource[p] === 1)) { this.startNewTake(p); return; }
    const t = this.newTake(!!loop, p);
    t.cursor = t.down + this.L();
    this.takes[p] = t;
  }
  padUp(p) {
    this.repeatPadUp(p);
    const take = this.takes[p];
    const l = this.loops[p];
    if (!take) return;
    if (take.live || take.released || take.latched) return;
    const held = this.w - take.downW;
    if (held < 0.18 * this.sr) {
      if (take.overdub && this.tapOverdub && l && !l.oneShot && !l.muted && this.seqPlaying && l.pendAt == null) {
        take.latched = true;
        this.hint('● Overdub auf Pad ' + (p + 1) + ' – nochmal tippen beendet ihn');
        return;
      }
      this.takes[p] = null;
      if (l) this.launchToggle(p);
      return;
    }
    this.releaseTake(take, this.heard());
  }
  startNewTake(p) {
    const t = this.newTake(false, p);
    t.src = this.padSource[p]; t.live = this.liveBars !== 0;
    if (t.src === 1) {
      t.bounceSrc = [];
      for (let q = 0; q < PADS; q++) { const l = this.loops[q]; if (l && q !== p && !l.oneShot && !l.muted) t.bounceSrc.push(q); }
    }
    const h = this.heard(), m = this.masterLen;
    if (!this.seqPlaying && m > 0 && !t.oneShot && (t.src === 1 || this.liveBars > 0)) this.startTransportAt(h);
    if (this.liveBars > 0 && m > 0) {
      let len = m * this.liveBars;
      const maxF = this.sr * MAXS;
      while (len > maxF && len > m) len -= m;
      len = Math.min(len, maxF);
      const prev = this.masterStart + floorDiv(h - this.masterStart, m) * m;
      const start = h - prev < m / 16 ? prev : prev + m;
      t.qOut = start; t.from = start; t.to = start + len; t.cursor = start + (t.src === 1 ? 0 : this.L());
      t.armed = true; t.released = true;
    } else {
      t.from = h; t.cursor = h + this.L();
    }
    this.takes[p] = t;
  }
  releaseTake(t, up) {
    if (t.released) return;
    t.up = up;
    const m = this.masterLen;
    if (t.overdub) { t.to = up; t.released = true; return; }
    const from = t.from != null ? t.from : t.down;
    const raw = Math.max(1, up - from);
    if (m <= 0 || t.oneShot) { t.from = from; t.to = from + raw; t.released = true; return; }
    const len = this.quantizeBars(raw), unit = Math.min(len, m), ms = this.masterStart;
    const q = ms + floorDiv(from - ms + unit / 2, unit) * unit;
    t.qOut = q; t.from = q; t.to = q + len; t.released = true;
  }
  isArmed(p) {
    const t = this.takes[p];
    return !!(t && t.live && t.qOut != null && this.heard() < t.qOut);
  }
  processTakes() {
    const now = this.w, L = this.L(), maxF = this.sr * MAXS;
    for (let p = 0; p < PADS; p++) {
      const t = this.takes[p];
      if (!t) continue;
      if (!t.armed && now - t.downW >= 0.18 * this.sr) {
        if (!this.seqPlaying && this.masterLen > 0 && !t.oneShot) this.startTransportAt(t.down);
        if (t.overdub) {
          const l0 = this.loops[p];
          if (l0) { const copy = l0.data.slice(); t.before = copy; this.pushEntry({ label: 'Overdub Pad ' + (p + 1), pads: [this.snapPad(p, copy)], pat: null }); }
          if (l0) l0.muted = false;
        }
        t.armed = true;
      }
      if (t.overdub) {
        if (!t.armed) continue;
        const loop = this.loops[p];
        if (!loop) { this.takes[p] = null; continue; }
        const limit = Math.min(now, t.released ? t.to + L : Infinity);
        if (t.cursor < now - this.ringSize + this.sr) t.cursor = now - this.ringSize + this.sr;
        const d = loop.data, len = d.length;
        while (t.cursor < limit) {
          const pos = fmod(t.cursor - L - loop.start, len);
          d[pos] += this.inRing[fmod(t.cursor, this.ringSize)];
          t.cursor++;
        }
        if (t.released && t.cursor >= t.to + L) { this.takes[p] = null; loop.src = loop.data; loop.srcBpm = this.currentBpm(); }
        continue;
      }
      if (t.src === 1) {
        if (!t.released && now - t.from >= maxF) { t.to = t.from + maxF; t.released = true; }
        if (t.released && now >= t.to) { this.takes[p] = null; this.finishResample(p, t); }
        continue;
      }
      if (!t.released && now - (t.from != null ? t.from : t.down) - L >= maxF) this.releaseTake(t, (t.from != null ? t.from : t.down) + maxF);
      if (t.released && now >= t.to + L) { this.takes[p] = null; this.finishRecording(p, t); }
    }
  }
  fades(data) {
    const len = data.length, fade = Math.min(Math.floor(len / 4), Math.floor(this.sr * FADE_MS / 1000));
    for (let i = 0; i < fade; i++) { const g = i / fade; data[i] *= g; data[len - 1 - i] *= g; }
  }
  finishRecording(p, t) {
    const len = Math.floor(t.to - t.from), L = this.L();
    if (len < 0.18 * this.sr) return;
    const data = new Float32Array(len), oldest = this.w - this.ringSize;
    for (let i = 0; i < len; i++) { const f = t.from + L + i; if (f >= oldest && f >= 0 && f < this.w) data[i] = this.inRing[fmod(f, this.ringSize)]; }
    this.fades(data);
    const loop = new Loop(data);
    loop.start = t.qOut != null ? t.qOut : t.up;
    this.pushPadUndo('Aufnahme Pad ' + (p + 1), [p]);
    if (t.oneShot) loop.oneShot = true;
    else if (this.masterLen === 0) {
      this.masterStart = loop.start; this.masterLen = len;
      if (!this.seqPlaying) this.startTransportAt(loop.start);
    }
    this.padSemi[p] = 0;
    this.setWarpSource(loop);
    this.loops[p] = loop;
    this.post({ t: 'recorded', pad: p, data: data.slice() });
  }
  finishResample(p, t) {
    const len = Math.floor(t.to - t.from);
    if (len < 0.18 * this.sr) return;
    const data = new Float32Array(len);
    for (let i = 0; i < len; i++) data[i] = this.outRing[fmod(t.from + i, this.ringSize)];
    this.fades(data);
    const loop = new Loop(data);
    loop.start = t.from;
    if (t.oneShot) loop.oneShot = true;
    else if (this.masterLen === 0) { this.masterStart = loop.start; this.masterLen = len; }
    this.padSemi[p] = 0;
    if (this.loops[p]) return;
    const src = this.bounce && !t.oneShot && t.bounceSrc ? t.bounceSrc : [];
    this.pushPadUndo('Wave-Aufnahme Pad ' + (p + 1), [p, ...src]);
    if (!t.oneShot && !this.seqPlaying && this.masterLen > 0) this.seqPlaying = true;
    this.setWarpSource(loop);
    this.loops[p] = loop;
    const muted = [];
    for (const q of src) { const l = this.loops[q]; if (l && !l.muted) { l.muted = true; muted.push(q + 1); } }
    if (muted.length) this.hint('Bounce → Pad ' + (p + 1) + ' · stumm: ' + muted.map((x) => 'Pad ' + x).join(', '));
    this.post({ t: 'recorded', pad: p, data: data.slice() });
  }
  abortTake(p) {
    const t = this.takes[p];
    if (!t || (t.released && t.latched)) return;
    this.takes[p] = null;
    const l = this.loops[p];
    if (t.overdub && t.before && l && t.before.length === l.data.length) l.data.set(t.before);
  }

  // ------------------------------------------------------------------ Pads
  importRaw(p, src) {
    this.padSemi[p] = 0;
    if (this.padOneShot[p]) {
      const fade = Math.min(Math.floor(src.length / 4), Math.floor(this.sr * FADE_MS / 1000));
      for (let i = 0; i < fade; i++) src[src.length - 1 - i] *= i / fade;
      const s = new Loop(src); s.oneShot = true;
      this.takes[p] = null; this.loops[p] = s;
      if (!this.anyLooping()) this.releaseMaster();
      return;
    }
    const m = this.masterLen;
    let data, start;
    if (m > 0) {
      const len = this.quantizeLen(src.length);
      data = new Float32Array(len); data.set(src.subarray(0, Math.min(len, src.length)));
      const h = this.heard();
      start = this.masterStart + floorDiv(h - this.masterStart, len) * len;
    } else { data = src; start = this.w; }
    this.fades(data);
    const loop = new Loop(data); loop.start = start;
    this.takes[p] = null;
    if (m <= 0) { this.masterStart = start; this.masterLen = data.length; }
    this.setWarpSource(loop);
    this.loops[p] = loop;
  }
  importLoop(p, src) {
    this.pushPadUndo('Laden Pad ' + (p + 1), [p]);
    this.importRaw(p, src);
    if (!this.padOneShot[p] && !this.seqPlaying && this.masterLen > 0) this.startTransportAt(this.heard());
  }
  importClip(p, src, bpm, bars, data) {
    this.pushPadUndo('Laden Pad ' + (p + 1), [p]);
    if (this.masterLen <= 0) { this.masterLen = Math.round(this.sr * 240 / bpm); this.masterStart = this.w; }
    const target = Math.max(64, Math.round(bars * this.masterLen));
    let d = data;
    if (!d || d.length !== target) {
      // Fallback, falls das Tempo sich zwischendurch geändert hat: Länge anpassen, der nächste Re-Warp richtet es
      const nd = new Float32Array(target); nd.set((d || src).subarray(0, Math.min(target, (d || src).length))); d = nd;
      this.requestRewarp();
    }
    this.fades(d);
    const loop = new Loop(d);
    loop.src = src; loop.srcBpm = bpm; loop.warpBars = bars; loop.start = this.masterStart; loop.muted = true;
    this.takes[p] = null; this.padSemi[p] = 0; this.loops[p] = loop;
  }
  setWarpSource(l) {
    const m = this.masterLen;
    if (l.oneShot || l.slices || m <= 0) return;
    l.src = l.data; l.srcBpm = this.sr * 240 / m; l.warpBars = l.data.length / m;
  }
  requestRewarp() {
    this.warpGen = (this.warpGen || 0) + 1;
    this.rewarpAt = this.w + Math.floor(this.sr * 0.2);
  }
  checkRewarp() {
    if (this.rewarpAt == null || this.w < this.rewarpAt) return;
    this.rewarpAt = null;
    const m = this.masterLen;
    if (m <= 0) return;
    const jobs = [];
    for (let p = 0; p < PADS; p++) {
      const l = this.loops[p];
      if (!l || !l.src || l.oneShot || l.slices || l.warpBars <= 0 || this.takes[p]) continue;
      const target = Math.max(64, Math.round(l.warpBars * m));
      if (target === l.data.length) continue;
      jobs.push({ pad: p, src: l.src.slice(), target, gen: this.warpGen });
    }
    if (jobs.length) this.post({ t: 'warpJobs', jobs });
  }
  applyWarp(p, data, gen) {
    if (gen !== this.warpGen) return;
    const l = this.loops[p];
    if (!l || !l.src) return;
    const m = this.masterLen;
    if (data.length !== Math.max(64, Math.round(l.warpBars * m))) return;
    const nl = l.clone(data);
    nl.muted = l.muted; nl.pendAt = l.pendAt; nl.pendOn = l.pendOn;
    const h = this.heard(), ms = this.masterStart, oldLen = l.data.length;
    const f = fmod(h - l.start, oldLen) / oldLen, nb = l.warpBars;
    const barIdx = nb >= 1 ? Math.max(0, Math.min(Math.floor(nb) - 1, Math.floor(f * nb))) : 0;
    nl.start = ms + floorDiv(h - ms, m) * m - barIdx * m;
    this.loops[p] = nl;
  }
  setPadMode(p, oneShot) {
    if (this.loops[p]) this.pushPadUndo('Modus Pad ' + (p + 1), [p]);
    this.padOneShot[p] = oneShot;
    const l = this.loops[p];
    if (!l) return;
    if (oneShot) {
      const c = l.clone(l.data.slice()); c.oneShot = true; c.muted = false; c.shotStart = null;
      this.loops[p] = c;
      if (!this.anyLooping()) this.releaseMaster();
    } else if (l.oneShot) this.importRaw(p, l.data.slice());
  }
  setPadPitch(p, semi) {
    this.padSemi[p] = semi;
    const l = this.loops[p];
    if (l) { l.semi = semi; l.rate = Math.pow(2, semi / 12); }
  }
  clear(p) {
    if (this.loops[p]) this.pushUndo('Pad ' + (p + 1) + ' löschen', [p], this.padHasEvents(p));
    this.takes[p] = null; this.loops[p] = null;
    this.seq[p].fill(0); this.notes[p] = []; this.dirty.seq = true; this.dirty.notes = true;
    if (!this.loops.some((l) => l && !l.oneShot) && !this.takes.some((t) => t)) this.releaseMaster();
  }
  toggleMuteAll() {
    const anyOn = this.loops.some((l) => l && !l.muted && !l.oneShot);
    for (const l of this.loops) if (l && !l.oneShot) l.muted = anyOn;
  }
  launchToggle(p) {
    const l = this.loops[p];
    if (!l) return;
    if (l.oneShot) { l.muted = !l.muted; return; }
    if (l.pendAt != null) { l.pendAt = null; return; }
    const turnOn = l.muted;
    if (!this.seqPlaying) { if (turnOn) { l.muted = false; this.transportPlay(false); } else l.muted = true; return; }
    const q = LAUNCH_Q[this.launchIdx], m = this.masterLen;
    if (q <= 0 || m <= 0) { l.muted = !turnOn; return; }
    const unit = Math.max(1, Math.floor(m * q)), h = this.heard(), ms = this.masterStart;
    let at = ms + ceilDiv(h - ms, unit) * unit;
    if (at < this.w + 64) at += unit;
    l.pendOn = turnOn; l.pendAt = at;
  }
  applyEdit(p, start, end, slices, bars) {
    const l = this.loops[p];
    if (!l) return;
    this.pushPadUndo('Wave-Editor Pad ' + (p + 1), [p]);
    const s0 = Math.max(0, Math.min(l.data.length - 2, start)), e0 = Math.max(s0 + 2, Math.min(l.data.length, end));
    const data = l.data.slice(s0, e0);
    if (l.oneShot || slices <= 1) {
      this.padOneShot[p] = l.oneShot;
      const semi = l.semi;
      this.importRaw(p, data);
      if (l.oneShot) this.setPadPitch(p, semi);
      return;
    }
    this.fades(data);
    const loop = new Loop(data), len = data.length;
    loop.slices = []; for (let k = 0; k <= slices; k++) loop.slices.push(Math.floor(k * len / slices));
    if (this.masterLen <= 0) { this.masterLen = len; this.masterStart = this.w; loop.bars = 1; } else loop.bars = bars;
    const tl = Math.max(1, Math.floor(this.masterLen * loop.bars)), h = this.heard();
    loop.start = this.masterStart + floorDiv(h - this.masterStart, tl) * tl;
    loop.muted = l.muted;
    this.takes[p] = null; this.loops[p] = loop;
  }
  distributeSlices(p, start, end, n) {
    const l = this.loops[p];
    if (!l) return 0;
    const s0 = Math.max(0, Math.min(l.data.length - 2, start)), e0 = Math.max(s0 + 2, Math.min(l.data.length, end)), len = e0 - s0;
    this.pushPadUndo('Slices auf Pads', [...Array(PADS).keys()]);
    let q = 0, count = 0;
    for (let k = 0; k < n; k++) {
      while (q < PADS && (q === p || this.loops[q])) q++;
      if (q >= PADS) break;
      const a = s0 + Math.floor(k * len / n), b = s0 + Math.floor((k + 1) * len / n);
      if (b - a < 2) continue;
      this.padOneShot[q] = true;
      this.importRaw(q, l.data.slice(a, b));
      count++; q++;
    }
    return count;
  }

  // ------------------------------------------------------------------ Mute/Solo, Repeat
  toggleSolo(p) { this.solo[p] = !this.solo[p]; this.soloAny = this.solo.some((x) => x); }
  clearSolo() { this.solo.fill(false); this.soloAny = false; }
  repInterval() { return Math.max(1, this.seqBar() / REPEAT_DIVS[this.repeatIdx]); }
  repUpdate() { this.repAny = this.repPad.some((x) => x) || this.repDrum.some((x) => x > 0); }
  repeatPadDown(p) { if (!this.repAny && !this.seqPlaying) this.repOrigin = this.w; this.repPadFrom[p] = this.w + this.repInterval() / 2; this.repPad[p] = true; this.repAny = true; }
  repeatPadUp(p) { if (p >= 0 && p < PADS) { this.repPad[p] = false; this.repUpdate(); } }
  repeatDrumDown(v, acc) { if (!this.repAny && !this.seqPlaying) this.repOrigin = this.w; this.repDrumFrom[v] = this.w + this.repInterval() / 2; this.repDrum[v] = acc ? 2 : 1; this.repAny = true; }
  repeatDrumUp(v) { if (v >= 0 && v < DV) { this.repDrum[v] = 0; this.repUpdate(); } }
  repeatAllUp() { this.repPad.fill(false); this.repDrum.fill(0); this.repAny = false; }
  runRepeat(w, block) {
    if (!this.repAny) return;
    const bar = this.seqBar(), div = REPEAT_DIVS[this.repeatIdx], origin = this.seqPlaying ? this.seqStart() : this.repOrigin;
    const sw = div === 16 ? this.swingOffset(bar) : 0, rec = this.recLive() && STEPS % div === 0;
    let n = ceilDiv((w - origin) * div, bar) - 1;
    for (;;) {
      const base = origin + Math.floor(n * bar / div);
      if (base >= w + block) break;
      const f = sw > 0 && fmod(n, 2) === 1 ? base + sw : base;
      if (f >= w && f < w + block) {
        const step = rec ? fmod(n * (STEPS / div), STEPS) : -1;
        for (let p = 0; p < PADS; p++) {
          if (!this.repPad[p] || f < this.repPadFrom[p]) continue;
          const l = this.loops[p];
          if (!l || !l.oneShot) continue;
          l.shotGain = 1; l.shotStart = f;
          if (step >= 0) { this.seq[p][step] = 1; this.dirty.seq = true; }
        }
        for (let v = 0; v < DV; v++) {
          const a = this.repDrum[v];
          if (!a || f < this.repDrumFrom[v]) continue;
          this.drum.trigger(v, f - w, a === 2);
          if (step >= 0) { this.st.drumSteps[v][step] = a; this.dirty.inst = true; }
        }
      }
      n++;
    }
  }
  drumHit(v, acc, hold) {
    if (this.recLive()) {
      const [step, ahead] = this.liveStep();
      this.st.drumSteps[v][step] = acc ? 2 : 1; this.dirty.inst = true;
      if (hold && this.repeatOn) this.repeatDrumDown(v, acc);
      if (ahead) return;
    } else if (hold && this.repeatOn) this.repeatDrumDown(v, acc);
    this.drum.trigger(v, 0, acc);
  }
  acidLiveOn(semi, slide) {
    if (this.recLive() && !slide) {
      const [step, ahead] = this.liveStep();
      this.st.acidGate[step] = true; this.st.acidSemi[step] = semi; this.dirty.inst = true;
      if (ahead) return;
    }
    this.acid.push(-1, true, semi, false, slide);
  }

  // ------------------------------------------------------------------ Undo
  snapPad(p, copy) {
    const l = this.loops[p];
    const keep = copy && l ? l.clone(copy) : l;
    return { p, loop: keep, muted: l ? l.muted : false, oneShot: this.padOneShot[p], semi: this.padSemi[p] };
  }
  snapPattern() {
    return { seq: this.seq.map((r) => Array.from(r)), notes: this.notes.map((l) => l.map((e) => ({ pos: e.pos, len: e.len, semi: e.semi, vel: e.vel }))),
      rollBars: this.rollBars.slice(), inst: this.instSnap() };
  }
  instSnap() {
    const s = this.st;
    return { semi: s.acidSemi.slice(), gate: s.acidGate.slice(), acc: s.acidAcc.slice(), slide: s.acidSlide.slice(), drums: s.drumSteps.map((r) => r.slice()) };
  }
  instRestore(i) {
    const s = this.st;
    s.acidSemi = i.semi.slice(); s.acidGate = i.gate.slice(); s.acidAcc = i.acc.slice(); s.acidSlide = i.slide.slice(); s.drumSteps = i.drums.map((r) => r.slice());
    this.dirty.inst = true;
  }
  applyPattern(s) {
    for (let p = 0; p < PADS; p++) {
      this.seq[p].set(s.seq[p].slice(0, STEPS)); this.rollBars[p] = s.rollBars[p];
      this.notes[p] = s.notes[p].map((e) => ({ pos: e.pos, len: e.len, semi: e.semi, vel: e.vel }));
    }
    this.instRestore(s.inst);
    this.dirty.seq = true; this.dirty.notes = true;
  }
  entryBytes(e) { return e.pads.reduce((a, s) => a + (s.loop ? s.loop.data.length * 4 : 0), 0); }
  pushEntry(e) {
    this.undoStack.push(e); this.redoStack = [];
    let total = this.undoStack.reduce((a, u) => a + this.entryBytes(u), 0);
    while (this.undoStack.length > 40 || (total > 120 * 1024 * 1024 && this.undoStack.length > 1)) total -= this.entryBytes(this.undoStack.shift());
  }
  pushPadUndo(label, pads) { this.pushEntry({ label, pads: [...new Set(pads)].filter((p) => p >= 0 && p < PADS).map((p) => this.snapPad(p)), pat: null }); }
  pushPatternUndo(label) { this.pushEntry({ label, pads: [], pat: this.snapPattern() }); }
  pushUndo(label, pads, pattern) { this.pushEntry({ label, pads: pads.map((p) => this.snapPad(p)), pat: pattern ? this.snapPattern() : null }); }
  applyEntry(e) {
    const back = { label: e.label, pads: e.pads.map((s) => this.snapPad(s.p)), pat: e.pat ? this.snapPattern() : null };
    for (const s of e.pads) {
      this.takes[s.p] = null;
      const l = s.loop, live = this.loops[s.p];
      this.padOneShot[s.p] = s.oneShot; this.padSemi[s.p] = s.semi;
      if (l) {
        l.muted = s.muted; l.shotStart = null;
        if (l.oneShot) { l.semi = s.semi; l.rate = Math.pow(2, s.semi / 12); }
        else {
          if (this.masterLen <= 0) { this.masterLen = l.data.length; this.masterStart = this.w; }
          if (l !== live) l.start = live && !live.oneShot && live.data.length === l.data.length ? live.start : this.masterStart;
        }
      }
      this.loops[s.p] = l;
    }
    if (e.pads.length && !this.anyLooping()) this.releaseMaster();
    if (e.pat) this.applyPattern(e.pat);
    return back;
  }
  undo(redo) {
    const from = redo ? this.redoStack : this.undoStack, to = redo ? this.undoStack : this.redoStack;
    const e = from.pop();
    if (!e) { this.hint(redo ? 'Nichts zum Wiederherstellen' : 'Nichts rückgängig zu machen'); return; }
    to.push(this.applyEntry(e));
    this.hint((redo ? '↷ Wiederhergestellt: ' : '↶ Rückgängig: ') + e.label);
  }
  dropPatternUndo() { this.undoStack = this.undoStack.filter((e) => !e.pat); this.redoStack = this.redoStack.filter((e) => !e.pat); }
  padHasEvents(p) { return this.seq[p].some((x) => x > 0) || this.notes[p].length > 0; }
  erasePadEvents(p) {
    if (!this.padHasEvents(p)) return false;
    this.pushPatternUndo('Pattern Pad ' + (p + 1) + ' löschen');
    this.seq[p].fill(0); this.notes[p] = []; this.dirty.seq = true; this.dirty.notes = true;
    return true;
  }
  duplicatePad(src, dst) {
    const l = this.loops[src];
    if (src === dst || !l) return false;
    this.pushUndo('Pad ' + (src + 1) + ' → Pad ' + (dst + 1), [dst], true);
    const c = l.clone(l.data.slice()); c.muted = false; c.shotStart = null;
    this.takes[dst] = null; this.padOneShot[dst] = l.oneShot; this.padSemi[dst] = this.padSemi[src];
    this.seq[dst].set(this.seq[src]); this.rollBars[dst] = this.rollBars[src];
    this.notes[dst] = this.notes[src].map((e) => ({ pos: e.pos, len: e.len, semi: e.semi, vel: e.vel }));
    this.padFx[dst] = this.padFx[src]; this.padSource[dst] = this.padSource[src];
    this.padChainOn[dst] = this.padChainOn[src];
    for (let k = 0; k < 3; k++) { this.setPadFxType(dst, k, this.pfxType[src][k]); this.pfxPar[dst][k] = this.pfxPar[src][k].slice(); }
    this.loops[dst] = c;
    this.dirty.seq = true; this.dirty.notes = true;
    return true;
  }
  seqClear() {
    this.pushPatternUndo('Pattern leeren');
    for (const r of this.seq) r.fill(0);
    for (let p = 0; p < PADS; p++) this.notes[p] = [];
    this.dirty.seq = true; this.dirty.notes = true;
  }

  // ------------------------------------------------------------------ Szenen
  captureScene(bars) { return { pat: this.snapPattern(), mutes: this.loops.map((l) => (l ? l.muted : false)), bars }; }
  copyScene(s) { return JSON.parse(JSON.stringify(s)); }
  storeCurrentScene() { this.scenes[this.curScene] = this.captureScene(this.scenes[this.curScene] ? this.scenes[this.curScene].bars : 4); }
  sceneExists(i) { return i === this.curScene || !!this.scenes[i]; }
  switchScene(i) {
    this.storeCurrentScene();
    const s = this.scenes[i];
    if (!s) return;
    this.applyPattern(this.copyScene(s.pat));
    for (let p = 0; p < PADS; p++) if (this.loops[p]) this.loops[p].muted = s.mutes[p];
    this.curScene = i; this.queuedScene = -1; this.sceneBarCount = 0;
    this.dropPatternUndo(); this.dirty.scenes = true;
  }
  launchScene(i) {
    let created = false;
    if (!this.scenes[i] && i !== this.curScene) { this.storeCurrentScene(); this.scenes[i] = this.copyScene(this.scenes[this.curScene]); created = true; }
    if (i === this.curScene) { this.queuedScene = -1; this.dirty.scenes = true; return; }
    const from = this.curScene;
    if (!this.seqPlaying) this.switchScene(i); else this.queuedScene = i;
    this.dirty.scenes = true;
    if (created) this.hint('Neue Szene ' + (i + 1) + ' = Kopie von Szene ' + (from + 1));
    else if (this.seqPlaying) this.hint('Szene ' + (i + 1) + ' startet auf der nächsten Eins');
  }
  deleteScene(i) {
    if (i === this.curScene) {
      this.pushPatternUndo('Szene leeren');
      for (const r of this.seq) r.fill(0);
      for (let p = 0; p < PADS; p++) this.notes[p] = [];
      this.st.acidGate.fill(false); this.st.acidAcc.fill(false); this.st.acidSlide.fill(false); this.st.acidSemi.fill(0);
      for (const r of this.st.drumSteps) r.fill(0);
      this.dirty.seq = this.dirty.notes = this.dirty.inst = true;
      this.hint('Szene ' + (i + 1) + ' geleert');
    } else if (this.scenes[i]) { this.scenes[i] = null; if (this.queuedScene === i) this.queuedScene = -1; this.hint('Szene ' + (i + 1) + ' gelöscht'); }
    this.dirty.scenes = true;
  }
  copySceneTo(a, b) {
    if (a === b) return;
    if (a === this.curScene) this.storeCurrentScene();
    const s = this.scenes[a];
    if (!s) return;
    this.scenes[b] = this.copyScene(s);
    if (b === this.curScene) { this.pushPatternUndo('Szene kopieren'); this.applyPattern(this.copyScene(s.pat)); for (let p = 0; p < PADS; p++) if (this.loops[p]) this.loops[p].muted = s.mutes[p]; }
    this.dirty.scenes = true;
    this.hint('Szene ' + (a + 1) + ' → Szene ' + (b + 1) + ' kopiert');
  }
  nextScene() { for (let k = 1; k <= SCENES; k++) { const j = (this.curScene + k) % SCENES; if (this.scenes[j]) return j; } return this.curScene; }
  sceneTick(w, block) {
    if (!this.seqPlaying) return;
    if (this.arrApplyNow) { this.arrApplyNow = false; if (this.songMode) this.applyArr(0); }
    const bar = this.seqBar(), origin = this.seqStart();
    const k = ceilDiv(w - origin, bar);
    if (k <= 0) return;
    const f = origin + k * bar;
    if (f >= w + block) return;
    this.arrBar = (this.arrBar + 1) % Math.max(1, this.songBars);
    if (this.songMode) { this.applyArr(this.arrBar); return; }
    this.sceneBarCount++;
    if (this.queuedScene < 0 && this.sceneChain) {
      const s = this.scenes[this.curScene];
      if (s && this.sceneBarCount >= s.bars) { const nx = this.nextScene(); if (nx !== this.curScene) this.queuedScene = nx; else this.sceneBarCount = 0; }
    }
    if (this.queuedScene >= 0) this.switchScene(this.queuedScene);
  }

  // ------------------------------------------------------------------ Song-Arrangement
  applyArr(bar) {
    const sc = this.arrScene[bar];
    if (sc >= 0 && sc !== this.curScene && this.scenes[sc]) this.switchScene(sc);
    for (let r = 0; r < PADS; r++) if (this.loops[r]) this.loops[r].muted = !this.arr[r][bar];
    this.st.acidOn = this.arr[12][bar]; this.st.drumOn = this.arr[13][bar]; this.dirty.inst = true;
  }
  arrCaptureBar(bar) {
    for (let r = 0; r < PADS; r++) { const l = this.loops[r]; this.arr[r][bar] = !!l && !l.muted; }
    this.arr[12][bar] = this.st.acidOn && instHasAcid(this.st); this.arr[13][bar] = this.st.drumOn && instHasDrum(this.st);
    this.dirty.arr = true;
  }
  arrFillFromScenes() {
    this.storeCurrentScene();
    this.arrClear();
    let bar = 0;
    for (let i = 0; i < SCENES; i++) {
      const s = this.scenes[i];
      if (!s || bar >= ARR_MAX) continue;
      this.arrScene[bar] = i;
      const acid = s.pat.inst.gate.some((x) => x), drums = s.pat.inst.drums.some((r) => r.some((x) => x > 0));
      for (let b = bar; b < Math.min(ARR_MAX, bar + s.bars); b++) {
        for (let r = 0; r < PADS; r++) this.arr[r][b] = !!this.loops[r] && !s.mutes[r];
        this.arr[12][b] = acid; this.arr[13][b] = drums;
      }
      bar += s.bars;
    }
    if (bar > 0) this.songBars = [4, 8, 16, 32, 64].find((x) => x >= bar) || 64;
    this.dirty.arr = true;
  }
  arrClear() { for (const r of this.arr) r.fill(false); this.arrScene.fill(-1); this.dirty.arr = true; }

  // ------------------------------------------------------------------ Effekte je Pad
  setPadFxType(p, slot, type) {
    if (type < 0) { this.pfxType[p][slot] = -1; this.pfxInst[p][slot] = null; return; }
    if (!this.pfxInst[p][slot]) this.pfxInst[p][slot] = new Fx(this.sr);
    this.pfxPar[p][slot][3] = P1_DEFAULT[type]; this.pfxPar[p][slot][4] = P2_DEFAULT[type];
    this.pfxType[p][slot] = type;
  }
  padChainActive(p) { return this.padChainOn[p] && this.pfxType[p].some((t) => t >= 0); }

  // ------------------------------------------------------------------ Bewegungs-Aufnahme
  motionButton() {
    const m = this.mot;
    if (m.state === 0) m.state = 1; else if (m.state === 1) m.state = 0; else if (m.state === 2) m.stopReq = true; else { m.state = 0; m.count = 0; }
  }
  finishMotion() {
    const m = this.mot, n = m.count;
    if (n < 4) { m.state = 0; m.count = 0; return; }
    let len = n * BLOCK;
    const bar = this.masterLen;
    if (bar > 0) {
      const beat = Math.max(1, bar / 4);
      len = Math.max(beat, Math.round(len / beat) * beat);
      m.start = this.masterStart + Math.floor((m.start - this.masterStart + beat / 2) / beat) * beat;
    }
    m.len = len; m.state = 3;
  }
  motionTick(w) {
    const m = this.mot;
    this.effX = this.fxX; this.effY = this.fxY; this.effTouch = this.fxTouch;
    if (m.state === 1 && this.liveTouch) { m.count = 0; m.start = w; m.state = 2; }
    else if (m.state === 2) {
      if (m.count < m.x.length) { m.x[m.count] = this.fxX; m.y[m.count] = this.fxY; m.t[m.count] = this.liveTouch ? 1 : 0; m.count++; }
      if (m.stopReq || m.count >= m.x.length) { m.stopReq = false; this.finishMotion(); }
    } else if (m.state === 3 && m.count > 0 && m.len > 0) {
      const rel = fmod(w - m.start, m.len), idx = Math.max(0, Math.min(m.count - 1, Math.floor(rel * m.count / m.len)));
      m.curX = m.x[idx]; m.curY = m.y[idx]; m.curT = m.t[idx] === 1;
      if (!this.liveTouch) { this.effX = m.curX; this.effY = m.curY; this.effTouch = m.curT || this.fxTouch; }
    }
    if (m.stopReq && m.state !== 2) m.stopReq = false;
  }

  // ------------------------------------------------------------------ Klicks
  addClicks(out, w, block) {
    const bar = this.masterLen;
    if (bar <= 0) return;
    const ms = this.masterStart;
    let n = ceilDiv((w - ms) * 4, bar);
    for (;;) {
      const f = ms + Math.floor(n * bar / 4);
      if (f >= w + block) break;
      if (f >= w) { this.clickStart = f; this.clickHigh = fmod(n, 4) === 0; }
      n++;
    }
    this.renderClick(out, w, block, this.clickStart, this.clickHigh, 0.35);
  }
  countClicks(out, w, block) {
    const from = this.countFrom, to = this.countTo;
    const bar = to - from;
    if (bar > 0 && w < to) {
      let n = Math.max(0, ceilDiv((w - from) * 4, bar));
      while (n < 4) {
        const f = from + Math.floor(n * bar / 4);
        if (f >= w + block) break;
        if (f >= w) { this.countClick = f; this.countHigh = n === 0; }
        n++;
      }
    }
    this.renderClick(out, w, block, this.countClick, this.countHigh, 0.4);
  }
  renderClick(out, w, block, start, high, amp) {
    if (start == null) return;
    const dur = this.sr / 25, freq = high ? 1600 : 1000;
    for (let i = 0; i < block; i++) {
      const t = w + i - start;
      if (t < 0 || t >= dur) continue;
      out[i] += Math.sin(2 * Math.PI * freq * t / this.sr) * Math.exp(-t / (0.012 * this.sr)) * amp;
    }
  }

  // ------------------------------------------------------------------ Mischer
  process(inputs, outputs) {
    const outCh = outputs[0];
    const input = inputs[0] && inputs[0][0];
    const block = outCh && outCh[0] ? outCh[0].length : BLOCK;
    const w = this.w;
    // Mikrofon in den Ringpuffer
    let ip = 0;
    for (let i = 0; i < block; i++) {
      const v = input ? input[i] : 0;
      this.inRing[(w + i) % this.ringSize] = v;
      const a = Math.abs(v); if (a > ip) ip = a;
      this.mic[i] = v;
    }
    this.inputLevel = Math.max(ip, this.inputLevel * 0.85);
    const out = this.out, bus = this.bus;
    out.fill(0); bus.fill(0);
    this.sceneTick(w, block);
    this.runSequencer(w, block);
    this.runRepeat(w, block);
    const mlen = this.masterLen, transport = this.seqPlaying;
    for (let p = 0; p < PADS; p++) { this.chainNow[p] = this.padChainActive(p); if (this.chainNow[p]) this.padBufs[p].fill(0); }
    const fadeStep = 1 / (0.006 * this.sr);
    for (let p = 0; p < PADS; p++) {
      const loop = this.loops[p];
      if (!loop) { this.levels[p] *= 0.9; continue; }
      const dst = this.chainNow[p] ? this.padBufs[p] : this.padFx[p] ? bus : out;
      if (loop.pendAt != null && loop.pendAt < w + block) {
        if (loop.pendOn) { loop.start = loop.pendAt; loop.fade = 0; loop.muted = false; } else loop.muted = true;
        loop.pendAt = null;
      }
      const gn = loop.gain, d = loop.data, len = d.length;
      let peak = 0;
      if (loop.oneShot) {
        const ss = loop.shotStart;
        if (ss != null && len > 1) {
          const rate = loop.rate, sg = loop.shotGain, rel = w - ss, total = len / rate;
          if (rel + block > 0 && rel < total) {
            for (let i = rel < 0 ? -rel : 0; i < block; i++) {
              const sp = (rel + i) * rate, i0 = Math.floor(sp);
              if (i0 >= len - 1) break;
              const v = (d[i0] + (d[i0 + 1] - d[i0]) * (sp - i0)) * sg * gn;
              const a = Math.abs(v); if (a > peak) peak = a;
              dst[i] += v;
            }
          }
        }
        this.levels[p] = Math.max(peak, this.levels[p] * 0.88);
        continue;
      }
      const rel = w - loop.start, mute = loop.muted || !transport || !this.audible(p), target = mute ? 0 : 1;
      let fg = loop.fade;
      const sl = loop.slices;
      if (sl && mlen > 0 && len > 0) {
        const n = sl.length - 1, tl = Math.max(1, Math.floor(mlen * loop.bars)), fadeN = 96;
        for (let i = 0; i < block; i++) {
          const r = rel + i;
          if (r < 0) continue;
          const pos = fmod(r, tl), k = Math.max(0, Math.min(n - 1, Math.floor(pos * n / tl)));
          const slotStart = Math.floor(k * tl / n), slotEnd = Math.floor((k + 1) * tl / n), off = pos - slotStart, sLen = sl[k + 1] - sl[k];
          if (off >= sLen) continue;
          let v = d[sl[k] + off];
          if (off < fadeN) v *= off / fadeN;
          const endRem = Math.min(sLen - off, slotEnd - pos);
          if (endRem < fadeN) v *= endRem / fadeN;
          const a = Math.abs(v); if (a > peak) peak = a;
          fg = fg < target ? Math.min(target, fg + fadeStep) : Math.max(target, fg - fadeStep);
          dst[i] += v * fg * gn;
        }
      } else if (rel + block > 0 && len > 0) {
        let i, idx;
        if (rel < 0) { i = -rel; idx = 0; } else { i = 0; idx = rel % len; }
        for (; i < block; i++) {
          const v = d[idx];
          const a = Math.abs(v); if (a > peak) peak = a;
          fg = fg < target ? Math.min(target, fg + fadeStep) : Math.max(target, fg - fadeStep);
          dst[i] += v * fg * gn;
          if (++idx === len) idx = 0;
        }
      }
      loop.fade = fg;
      this.levels[p] = Math.max(mute ? peak * 0.25 : peak, this.levels[p] * 0.88);
    }
    this.renderVoices(out, bus, block, w);
    for (let p = 0; p < PADS; p++) {
      if (!this.chainNow[p]) continue;
      const pb = this.padBufs[p];
      for (let s = 0; s < 3; s++) {
        const t = this.pfxType[p][s], fx = this.pfxInst[p][s];
        if (t < 0 || !fx) continue;
        const pr = this.pfxPar[p][s];
        fx.process(pb, block, w, t, pr[0], pr[1], true, pr[2], pr[3], pr[4], this.masterLen, this.masterStart, this.mic);
      }
      const dst = this.padFx[p] ? bus : out;
      for (let i = 0; i < block; i++) dst[i] += pb[i];
    }
    const soloed = this.soloAny, sc = this.scratch;
    if (soloed) sc.fill(0);
    this.acid.render(soloed ? sc : this.st.acidFx ? bus : out, block, w, this.st);
    this.drum.render(soloed ? sc : this.st.drumFx ? bus : out, block, this.st);
    if (this.songRec && this.songDry) for (let i = 0; i < block; i++) this.dryBuf[i] = out[i] + bus[i];
    this.motionTick(w);
    for (let s = 0; s < SLOTS; s++) {
      if (!this.slotOn[s]) continue;
      const kn = this.slotKnobs[s];
      this.fxs[s].process(bus, block, w, this.slotType[s], this.effX, this.effY, this.effTouch, kn[0], kn[1], kn[2], this.masterLen, this.masterStart, this.mic);
    }
    for (let i = 0; i < block; i++) { out[i] += bus[i]; this.outRing[(w + i) % this.ringSize] = out[i]; }
    this.processTakes();
    const vol = this.vol * 1.25;
    let mp = 0;
    for (let i = 0; i < block; i++) { const y = softClip(out[i] * vol); out[i] = y; const a = Math.abs(y); if (a > mp) mp = a; }
    this.outputLevel = Math.max(mp, this.outputLevel * 0.9);
    if (this.songRec) this.songAppend(this.songDry ? this.dryBuf.map((x) => softClip(x * vol)) : out, block);
    for (let i = 0; i < block; i += 4) { this.scope[this.scopeW & 2047] = out[i]; this.scopeW++; }
    if (this.metronome && transport && (this.countTo == null || w >= this.countTo)) this.addClicks(out, w, block);
    if (this.countTo != null) this.countClicks(out, w, block);
    if (outCh) for (let c = 0; c < outCh.length; c++) outCh[c].set(out.subarray(0, block));
    this.w = w + block;
    this.checkRewarp();
    if (++this.blockCount % 12 === 0) this.postStatus();
    return true;
  }
  songAppend(buf, n) {
    const max = this.sr * 60 * 15;
    if (this.songFrames + n > max) return;
    this.songChunks.push(Float32Array.from(buf.subarray ? buf.subarray(0, n) : buf.slice(0, n)));
    this.songFrames += n;
  }

  // ------------------------------------------------------------------ Status an die Oberfläche
  state(p) {
    const t = this.takes[p], l = this.loops[p];
    if (t) {
      if (!t.overdub) return ST.RECORDING;
      if (!t.released && t.armed) return ST.OVERDUB;
    }
    if (!l) return ST.EMPTY;
    if (l.oneShot) return ST.ONESHOT;
    return l.muted ? ST.MUTED : ST.PLAYING;
  }
  postStatus() {
    const h = this.heard();
    const pads = [];
    for (let p = 0; p < PADS; p++) {
      const l = this.loops[p], t = this.takes[p];
      pads.push({
        s: this.state(p), has: !!l, os: l ? l.oneShot : this.padOneShot[p], mu: l ? l.muted : false, st: l ? l.start : 0, len: l ? l.data.length : 0,
        rate: l ? l.rate : 1, ss: l && l.shotStart != null ? l.shotStart : null, q: l && l.pendAt != null ? (l.pendOn ? 1 : 0) : -1,
        ar: this.isArmed(p), la: !!(t && t.latched && !t.released), rs: t && !t.overdub ? Math.max(0, (h - (t.qOut != null ? t.qOut : t.down)) / this.sr) : 0,
        lv: this.levels[p], g: l ? l.gain : 1, so: this.solo[p], src: this.padSource[p], fx: this.padFx[p], ev: this.padHasEvents(p),
        sl: l && l.slices ? l.slices.length - 1 : 0, semi: this.padSemi[p], bars: l ? l.bars : 1,
        chain: this.padChainOn[p], pft: this.pfxType[p].slice()
      });
    }
    const m = {
      t: 'status', h, sr: this.sr, ml: this.masterLen, ms: this.masterStart, so: this.seqOrigin, play: this.seqPlaying, rec: this.seqRecording,
      cin: this.countInBeat(), song: this.songRec, songSec: this.songFrames / this.sr, sel: this.seqSelected, swing: this.swing,
      bpm: this.currentBpm(), fixed: this.fixedBpm, met: this.metronome, pads, inL: this.inputLevel, outL: this.outputLevel,
      cur: this.curScene, qs: this.queuedScene, chainS: this.sceneChain, sceneBar: this.sceneBarCount, ex: this.scenes.map((s, i) => !!s || i === this.curScene),
      sbars: this.scenes.map((s) => (s ? s.bars : 4)), undo: this.undoStack.length, redo: this.redoStack.length,
      rep: this.repeatOn, repIdx: this.repeatIdx, soloAny: this.soloAny, mot: this.mot.state, motX: this.mot.curX, motY: this.mot.curY, motT: this.mot.curT,
      motProg: this.mot.state === 3 && this.mot.len > 0 ? fmod(h - this.mot.start, this.mot.len) / this.mot.len : -1, motSec: this.mot.state === 2 ? this.mot.count * BLOCK / this.sr : 0,
      songMode: this.songMode, songBars: this.songBars, arrBar: this.arrBar, rollQuant: this.rollQuant, rollBars: this.rollBars.slice(),
      slotType: this.slotType.slice(), slotOn: this.slotOn.slice(), editSlot: this.editSlot, knobs: this.slotKnobs.map((k) => k.slice()), vol: this.vol,
      liveBars: this.liveBars, launchIdx: this.launchIdx, tapOverdub: this.tapOverdub, countIn: this.countIn, bounce: this.bounce,
      acidOn: this.st.acidOn, drumOn: this.st.drumOn
    };
    if (this.dirty.seq) { m.seq = this.seq.map((r) => Array.from(r)); this.dirty.seq = false; }
    if (this.dirty.notes) { m.notes = this.notes.map((l) => l.map((e) => [e.pos, e.len, e.semi, e.vel])); this.dirty.notes = false; }
    if (this.dirty.inst) { m.inst = this.st; this.dirty.inst = false; }
    if (this.dirty.arr) { m.arr = this.arr.map((r) => r.slice()); m.arrScene = this.arrScene.slice(); this.dirty.arr = false; }
    this.post(m);
  }

  // ------------------------------------------------------------------ Befehle
  handle(m) {
    const p = m.p;
    switch (m.t) {
      case 'lat': this.outLat = Math.max(0, Math.round(m.out)); this.inLat = Math.max(0, Math.round(m.inp)); break;
      case 'padDown': this.padDown(p, m.hold); break;
      case 'padUp': this.padUp(p); break;
      case 'abort': this.abortTake(p); break;
      case 'gain': if (this.loops[p]) this.loops[p].gain = Math.max(0, Math.min(1.5, m.v)); break;
      case 'mute': if (this.loops[p]) this.loops[p].muted = !this.loops[p].muted; break;
      case 'muteAll': this.toggleMuteAll(); break;
      case 'solo': this.toggleSolo(p); break;
      case 'clearSolo': this.clearSolo(); break;
      case 'clear': this.clear(p); break;
      case 'erase': if (!this.erasePadEvents(p) && this.loops[p]) { this.clear(p); this.hint('Pad ' + (p + 1) + ' gelöscht – ↶ UNDO holt es zurück'); } else if (this.loops[p]) this.hint('Pad ' + (p + 1) + ': Pattern gelöscht – nochmal antippen löscht das Sample'); break;
      case 'dupl': if (this.duplicatePad(m.src, m.dst)) this.hint('Pad ' + (m.src + 1) + ' → Pad ' + (m.dst + 1) + ' kopiert'); break;
      case 'mode': this.setPadMode(p, m.oneShot); break;
      case 'padOneShot': this.padOneShot[p] = m.v; break;
      case 'source': this.padSource[p] = m.v; break;
      case 'allSources': this.padSource.fill(m.v); break;
      case 'padFx': this.padFx[p] = m.v; break;
      case 'allFx': this.padFx.fill(m.v); break;
      case 'pitch': this.setPadPitch(p, m.semi); break;
      case 'import': this.importLoop(p, m.data); break;
      case 'clip': this.importClip(p, m.src, m.bpm, m.bars, m.data); break;
      case 'warped': this.applyWarp(p, m.data, m.gen); break;
      case 'edit': this.applyEdit(p, m.start, m.end, m.slices, m.bars); break;
      case 'distribute': this.hint(this.distributeSlices(p, m.start, m.end, m.n) + ' Slices auf freie Pads verteilt'); break;
      case 'getPad': { const l = this.loops[p]; this.post({ t: 'padData', p, req: m.req, data: l ? l.data.slice() : null, oneShot: l ? l.oneShot : false, rate: this.sr }); break; }
      case 'play': this.transportPlay(false); break;
      case 'stop': this.transportStop(); break;
      case 'rec': this.toggleRec(); break;
      case 'seqClear': this.seqClear(); break;
      case 'step': this.pushPatternUndo('Schritt ' + (m.k + 1)); this.seq[p][m.k] = this.seq[p][m.k] > 0 ? 0 : 1; this.dirty.seq = true; break;
      case 'stepVel': if (m.undo) this.pushPatternUndo('Lautstärke'); this.seq[p][m.k] = Math.max(0, Math.min(1, m.v)); this.dirty.seq = true; break;
      case 'select': this.seqSelected = p; break;
      case 'noteOn': this.noteOn(p, m.semi, m.id); break;
      case 'noteOff': this.noteOff(m.id); break;
      case 'preview': { const l = this.loops[p]; if (l) { const id = this.startVoice(l.data, p, Math.pow(2, m.semi / 12), false); for (const v of this.voices) if (v.id === id) v.offFrame = this.w + this.sr * 0.18; } break; }
      case 'previewData': this.startVoice(m.data, -1, 1, false); break;
      case 'stopVoices': for (const v of this.voices) if (v.active) v.releasing = true; break;
      case 'toggleNote': {
        this.pushPatternUndo('Note');
        const list = this.notes[p], hit = list.find((e) => e.semi === m.semi && m.posRaw >= e.pos - 1e-6 && m.posRaw < e.pos + e.len - 1e-6);
        if (hit) list.splice(list.indexOf(hit), 1); else list.push({ pos: m.pos, len: m.len, semi: m.semi, vel: 1 });
        this.dirty.notes = true; break;
      }
      case 'quantize': {
        this.pushPatternUndo('Quantisieren');
        const q = m.q, bars = this.rollBars[p];
        for (const e of this.notes[p]) { e.pos = this.qPos(e.pos, q); if (e.pos >= bars) e.pos -= bars; e.len = this.qLen(e.len, q); }
        const seen = new Set();
        this.notes[p] = this.notes[p].filter((e) => { const k = e.semi + '@' + Math.round(e.pos * 1024); if (seen.has(k)) return false; seen.add(k); return true; });
        this.dirty.notes = true; break;
      }
      case 'rollBars': this.pushPatternUndo('Matrix-Länge'); this.rollBars[p] = m.v; this.notes[p] = this.notes[p].filter((e) => e.pos < m.v); this.dirty.notes = true; break;
      case 'rollQuant': this.rollQuant = m.v; break;
      case 'clearNotes': this.pushPatternUndo('Matrix leeren'); this.notes[p] = []; this.dirty.notes = true; break;
      case 'xy': this.fxX = m.x; this.fxY = m.y; if (m.touch != null) { this.fxTouch = m.touch; } if (m.live != null) this.liveTouch = m.live; break;
      case 'selectFx': { const t = fmod(m.type, FX.COUNT); const s = this.editSlot; this.slotType[s] = t; this.slotOn[s] = true; this.slotKnobs[s][1] = P1_DEFAULT[t]; this.slotKnobs[s][2] = P2_DEFAULT[t]; break; }
      case 'selectSlot': if (m.s === this.editSlot) this.slotOn[m.s] = !this.slotOn[m.s]; else this.editSlot = m.s; break;
      case 'knob': if (m.k < 3) this.slotKnobs[this.editSlot][m.k] = m.v; else this.vol = m.v; break;
      case 'swing': this.swing = m.v; break;
      case 'motion': this.motionButton(); break;
      case 'inst': { const keepOn = this.songMode; Object.assign(this.st, m.st); if (keepOn) {} break; }
      case 'instUndo': this.pushPatternUndo(m.label); break;
      case 'acidOn': this.acidLiveOn(m.semi, m.slide); break;
      case 'acidOff': this.acid.push(-1, false, 0, false, false); break;
      case 'drumHit': this.drumHit(m.v, m.acc, m.hold); break;
      case 'drumUp': this.repeatDrumUp(m.v); break;
      case 'drumTrig': this.drum.trigger(m.v, 0, false); break;
      case 'bpm': this.setBpm(m.v); break;
      case 'autoTempo': this.setAutoTempo(); break;
      case 'set': this[m.k] = m.v; break;
      case 'repeatAllUp': this.repeatAllUp(); break;
      case 'cycleRepeat': this.repeatIdx = (this.repeatIdx + 1) % REPEAT_DIVS.length; this.repeatOn = true; break;
      case 'undo': this.undo(false); break;
      case 'redo': this.undo(true); break;
      case 'scene': this.launchScene(m.i); break;
      case 'sceneDelete': this.deleteScene(m.i); break;
      case 'sceneCopy': this.copySceneTo(m.a, m.b); break;
      case 'sceneBars': if (!this.scenes[m.i] && m.i === this.curScene) this.storeCurrentScene(); if (this.scenes[m.i]) this.scenes[m.i].bars = m.v; break;
      case 'arrCell': this.arr[m.r][m.b] = m.v; this.dirty.arr = true; break;
      case 'arrCapture': this.arrCaptureBar(m.b); break;
      case 'arrScenes': this.arrFillFromScenes(); break;
      case 'arrClear': this.arrClear(); break;
      case 'padChain': this.padChainOn[p] = m.v; break;
      case 'pfxType': this.setPadFxType(p, m.s, m.type); break;
      case 'pfxPar': this.pfxPar[p][m.s][m.i] = m.v; break;
      case 'songStart': this.songDry = !!m.dry; this.songChunks = []; this.songFrames = 0; this.songRec = true; break;
      case 'songStop': {
        if (!this.songRec) break;
        this.songRec = false;
        const outA = new Float32Array(this.songFrames);
        let o = 0;
        for (const c of this.songChunks) { outA.set(c, o); o += c.length; }
        this.songChunks = []; this.songFrames = 0;
        this.post({ t: 'song', data: outA }, [outA.buffer]);
        break;
      }
      case 'renderPattern': { const d = this.renderPattern(); this.post({ t: 'pattern', data: d }); break; }
      case 'snapshot': this.post({ t: 'snapshot', req: m.req, snap: this.snapshot() }); break;
      case 'restore': this.restore(m.snap); break;
      case 'newProject': this.restore(null); break;
    }
    this.dirty.seq = this.dirty.seq || ['step', 'stepVel', 'seqClear', 'undo', 'redo', 'erase', 'dupl', 'clear', 'scene', 'sceneDelete', 'sceneCopy', 'restore', 'newProject'].includes(m.t);
    if (['undo', 'redo', 'scene', 'sceneDelete', 'sceneCopy', 'restore', 'newProject', 'inst', 'arrScenes'].includes(m.t)) { this.dirty.inst = true; this.dirty.notes = true; this.dirty.arr = true; }
  }
  renderPattern() {
    const bar1 = Math.floor(this.seqBar());
    let nb = 1;
    for (let p = 0; p < PADS; p++) if (this.notes[p].length) nb = Math.max(nb, this.rollBars[p]);
    const bar = bar1 * nb, out = new Float32Array(bar);
    let any = false;
    for (let p = 0; p < PADS; p++) {
      const l = this.loops[p];
      if (!l || !l.oneShot) continue;
      for (let k = 0; k < STEPS; k++) {
        const vel = this.seq[p][k];
        if (vel <= 0) continue;
        any = true;
        for (let b = 0; b < nb; b++) {
          const st = b * bar1 + Math.floor(k * bar1 / STEPS) + (k % 2 === 1 ? this.swingOffset(bar1) : 0);
          for (let i = 0; i < l.data.length; i++) out[(st + i) % bar] += l.data[i] * vel * l.gain;
        }
      }
    }
    for (let p = 0; p < PADS; p++) {
      const l = this.loops[p];
      if (!l || l.data.length < 2) continue;
      const d = l.data, rb = this.rollBars[p];
      for (const ev of this.notes[p]) {
        any = true;
        const rate = Math.pow(2, ev.semi / 12), lenF = Math.max(64, Math.floor(ev.len * bar1)), rel = Math.floor(this.sr * 0.03);
        for (let rep = 0; rep < nb / rb; rep++) {
          const st = Math.floor((rep * rb + ev.pos) * bar1);
          let pos = 0;
          for (let i = 0; i < lenF + rel; i++) {
            let i0 = Math.floor(pos);
            if (i0 >= d.length - 1) { if (l.oneShot) break; pos -= d.length - 1; i0 = Math.max(0, Math.min(d.length - 2, Math.floor(pos))); }
            let v = (d[i0] + (d[i0 + 1] - d[i0]) * (pos - i0)) * ev.vel;
            if (i >= lenF) v *= 1 - (i - lenF) / rel;
            out[(st + i) % bar] += v;
            pos += rate;
          }
        }
      }
    }
    if (!any) return null;
    for (let i = 0; i < out.length; i++) out[i] = softClip(out[i]);
    return out;
  }
  snapshot() {
    this.storeCurrentScene();
    return {
      sr: this.sr, masterLen: this.masterLen, bpm: this.fixedBpm, swing: this.swing, metronome: this.metronome,
      pads: this.loops.map((l, p) => ({ data: l ? l.data.slice() : null, oneShot: l ? l.oneShot : this.padOneShot[p], muted: l ? l.muted : false,
        slices: l && l.slices ? l.slices.length - 1 : 0, bars: l ? l.bars : 1, semi: l ? l.semi : this.padSemi[p], gain: l ? l.gain : 1,
        src: this.padSource[p], fx: this.padFx[p], rollBars: this.rollBars[p], notes: this.notes[p].map((e) => [e.pos, e.len, e.semi, e.vel]),
        warpBpm: l ? l.srcBpm : 0, warpBars: l ? l.warpBars : 0 })),
      seq: this.seq.map((r) => Array.from(r)), inst: this.st,
      chain: [0, 1, 2].map((s) => ({ type: this.slotType[s], on: this.slotOn[s], k: this.slotKnobs[s].slice() })), vol: this.vol,
      scenes: this.scenes, cur: this.curScene, sceneChain: this.sceneChain,
      arr: this.arr, arrScene: this.arrScene, songMode: this.songMode, songBars: this.songBars,
      padChain: this.padChainOn, pfxType: this.pfxType, pfxPar: this.pfxPar
    };
  }
  restore(s) {
    this.transportStop();
    this.undoStack = []; this.redoStack = []; this.clearSolo(); this.repeatAllUp();
    this.scenes = new Array(SCENES).fill(null); this.curScene = 0; this.queuedScene = -1; this.sceneChain = false;
    this.arrClear(); this.songMode = false; this.songBars = 16;
    for (let p = 0; p < PADS; p++) {
      this.takes[p] = null; this.loops[p] = null; this.seq[p].fill(0); this.notes[p] = []; this.padOneShot[p] = false; this.rollBars[p] = 1; this.padSemi[p] = 0;
      this.padChainOn[p] = false; for (let k = 0; k < 3; k++) this.setPadFxType(p, k, -1);
    }
    this.st = newInst();
    if (!s) { this.fixedBpm = 0; this.masterLen = 0; this.dirty = { seq: true, notes: true, inst: true, scenes: true, arr: true }; return; }
    this.fixedBpm = s.bpm || 0; this.masterLen = s.masterLen || 0; this.masterStart = this.w; this.swing = s.swing || 0; this.metronome = !!s.metronome;
    s.pads.forEach((sv, p) => {
      if (p >= PADS) return;
      this.padOneShot[p] = !!sv.oneShot; this.padSource[p] = sv.src || 0; this.padFx[p] = sv.fx !== false; this.rollBars[p] = sv.rollBars || 1;
      this.notes[p] = (sv.notes || []).map((a) => ({ pos: a[0], len: a[1], semi: a[2], vel: a[3] }));
      if (!sv.data || !sv.data.length) return;
      const l = new Loop(sv.data);
      l.oneShot = !!sv.oneShot; l.muted = !!sv.muted; l.start = this.masterStart; l.gain = sv.gain == null ? 1 : sv.gain;
      this.padSemi[p] = sv.semi || 0; l.semi = sv.semi || 0; l.rate = Math.pow(2, l.semi / 12);
      if (sv.slices > 1 && !sv.oneShot) { l.slices = []; for (let k = 0; k <= sv.slices; k++) l.slices.push(Math.floor(k * sv.data.length / sv.slices)); l.bars = sv.bars; }
      this.setWarpSource(l);
      this.loops[p] = l;
    });
    (s.seq || []).forEach((r, p) => { if (p < PADS) this.seq[p].set(r.slice(0, STEPS)); });
    if (s.inst) Object.assign(this.st, s.inst);
    (s.chain || []).forEach((c, i) => { this.slotType[i] = c.type; this.slotOn[i] = c.on; this.slotKnobs[i] = c.k.slice(); });
    if (s.vol != null) this.vol = s.vol;
    if (s.scenes) { this.scenes = s.scenes.slice(0, SCENES); while (this.scenes.length < SCENES) this.scenes.push(null); this.curScene = s.cur || 0; this.sceneChain = !!s.sceneChain; }
    if (s.arr) { this.arr = s.arr.map((r) => r.slice()); this.arrScene = (s.arrScene || []).slice(); this.songMode = !!s.songMode; this.songBars = s.songBars || 16; }
    if (s.padChain) for (let p = 0; p < PADS; p++) {
      this.padChainOn[p] = !!s.padChain[p];
      for (let k = 0; k < 3; k++) { this.setPadFxType(p, k, s.pfxType[p][k]); this.pfxPar[p][k] = s.pfxPar[p][k].slice(); }
    }
    this.dirty = { seq: true, notes: true, inst: true, scenes: true, arr: true };
  }
}

class MarvProcessor extends AudioWorkletProcessor {
  constructor() { super(); this.e = new Engine(sampleRate, this.port); }
  process(inputs, outputs) { return this.e.process(inputs, outputs); }
}
registerProcessor('marv-engine', MarvProcessor);
