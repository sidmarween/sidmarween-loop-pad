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
