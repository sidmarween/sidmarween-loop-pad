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
