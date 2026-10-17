// ===================================================================== Marv PAD Web – Grundlagen (Audio, Status, Dateien, Warp, Speicher)
'use strict';
const VERSION = '1.0.42-web';
const PADS = 12, STEPS = 16;
const FXN = {
  NAMES: ['LOWPASS', 'HIGHPASS', 'BANDPASS', 'NOTCH', 'BELL', 'PEAK', 'COMB', 'DELAY', 'STUTTER', 'FLANGER', 'CHORUS', 'REVERB', 'HALL', 'VOCODER', 'DISTORT', 'CRUSH',
    'PHASER', 'TREMOLO', 'VIBRATO', 'EQ', 'FILTER', 'SATURATE', 'OVERDRIVE', 'GRAIN DLY', 'PITCH', 'STRETCH', 'SMEAR', 'FREEZE', 'RINGMOD', 'FM', 'SHUFFLE', 'FREQ SHIFT', 'RESONATOR', 'GRANULAR', 'SEQ-FX', 'MULTI-FX', 'KRISTALL'],
  X: ['CUTOFF', 'CUTOFF', 'FREQUENZ', 'FREQUENZ', 'FREQUENZ', 'FREQUENZ', 'TONHÖHE', 'ZEIT', 'LÄNGE', 'SPEED', 'SPEED', 'GRÖSSE', 'LÄNGE', 'TONHÖHE', 'DRIVE', 'RATE',
    'SPEED', 'SPEED', 'SPEED', 'TIEFEN', 'CUTOFF', 'DRIVE', 'DRIVE', 'ZEIT', 'TONHÖHE', 'SPEED', 'SMEAR', 'BLUR', 'FREQUENZ', 'MOD-FREQ', 'SLICE', 'SHIFT', 'GRUNDTON', 'POSITION', 'MUSTER', 'MORPH', 'SPLICE'],
  Y: ['RESONANZ', 'RESONANZ', 'SCHÄRFE', 'SCHÄRFE', 'GAIN ±', 'RESONANZ', 'FEEDBACK ±', 'FEEDBACK', 'GATE', 'TIEFE', 'TIEFE', 'DÄMPFUNG', 'HELLIGKEIT', 'FORMANT', 'TON', 'BITS',
    'TIEFE', 'TIEFE', 'TIEFE', 'HÖHEN', 'RESONANZ', 'WÄRME', 'TON', 'FEEDBACK', 'GRÖSSE', 'GRÖSSE', 'HELLIGKEIT', 'HELLIGKEIT', 'WELLE', 'TIEFE', 'CHAOS', 'FEEDBACK', 'AUSKLANG', 'DICHTE', 'INTENSITÄT', 'ECHO', 'FEEDBACK'],
  P1: ['STEIL', 'STEIL', 'STEIL', 'STEIL', 'BREITE', 'DRY', 'MODUS', 'TON', 'PITCH', 'FEEDBK', 'STIMMEN', 'PRE', 'PRE', 'AKKORD', 'TYP', 'DRIVE',
    'FEEDBK', 'FORM', 'FORM', 'MITTEN', 'MODUS', 'BIAS', 'BASS', 'STREUUNG', 'FEEDBK', 'PITCH', 'DIFFUS', 'SHIMMER', 'LFO', 'FEEDBK', 'REVERSE', 'BEREICH', 'AKKORD', 'SPREAD', 'RATE', 'FARBE', 'PITCH'],
  P2: ['DRIVE', 'DRIVE', 'DRIVE', 'DRIVE', 'STUFEN', 'DRIVE', 'TON', 'WOBBLE', 'TON', 'BASIS', 'BASIS', 'LOWCUT', 'LOWCUT', 'MIC', 'LEVEL', 'TON',
    'STUFEN', 'SYNC', 'BASIS', 'MITT-FRQ', 'DRIVE', 'LEVEL', 'LEVEL', 'PITCH', 'STREUUNG', 'STREUUNG', 'TON', 'DIFFUS', 'LFO-TIEFE', 'TON', 'PITCH', 'TON', 'HELL', 'GRÖSSE', 'VARIATION', 'RAUM', 'REVERSE'],
  CODES: ['LP', 'HP', 'BP', 'NOT', 'BEL', 'PK', 'CMB', 'DLY', 'STU', 'FLG', 'CHO', 'REV', 'HAL', 'VOC', 'DST', 'CRU', 'PHA', 'TRM', 'VIB', 'EQ', 'FLT', 'SAT', 'OD', 'GRD', 'PIT', 'STR', 'SMR', 'FRZ', 'RNG', 'FM', 'SHF', 'FQS', 'RES', 'GRN', 'SEQ', 'MFX', 'KRI'],
  HUES: [190, 175, 205, 165, 30, 55, 100, 280, 50, 130, 160, 230, 250, 310, 15, 0, 145, 95, 120, 40, 200, 25, 10, 265, 295, 215, 240, 185, 330, 345, 60, 300, 75, 270, 85, 20, 195]
};
const DRUM_NAMES = ['BD', 'SD', 'LT', 'MT', 'HT', 'RS', 'CP', 'CH', 'OH', 'CR', 'RD'];
const DRUM_LONG = ['Bass Drum', 'Snare', 'Low Tom', 'Mid Tom', 'High Tom', 'Rimshot', 'Clap', 'Closed Hat', 'Open Hat', 'Crash', 'Ride'];
const PRESET_NAMES = ['House', 'Techno', 'Breakbeat', 'Electro'];
const LAUNCH_NAMES = ['AUS', '1/4', '1/2', '1 TAKT', '2 TAKTE', '4 TAKTE'];
const REPEAT_NAMES = ['1/4', '1/8', '1/16', '1/32', '1/8T', '1/16T'];
const NOTE_NAMES = ['C', 'C#', 'D', 'D#', 'E', 'F', 'F#', 'G', 'G#', 'A', 'A#', 'H'];

const $ = (sel, root = document) => root.querySelector(sel);
const el = (tag, cls, txt) => { const e = document.createElement(tag); if (cls) e.className = cls; if (txt != null) e.textContent = txt; return e; };
const clamp = (v, a, b) => Math.max(a, Math.min(b, v));
const fmodJ = (a, b) => ((a % b) + b) % b;

// ------------------------------------------------------------------ Einstellungen (pro Browser)
const prefs = {
  get(k, d) { try { const v = localStorage.getItem('marvpad.' + k); return v == null ? d : JSON.parse(v); } catch (e) { return d; } },
  set(k, v) { try { localStorage.setItem('marvpad.' + k, JSON.stringify(v)); } catch (e) { /* privat */ } }
};

// ------------------------------------------------------------------ Audio
const A = { ctx: null, node: null, mic: null, micStream: null, started: false, micOk: false, sr: 48000, recvAt: 0, latMs: prefs.get('latMs', 0) };
let S = null; // letzter Status der Engine
let SEQ = Array.from({ length: PADS }, () => new Array(STEPS).fill(0));
let NOTES = Array.from({ length: PADS }, () => []);
let INST = null;
let ARR = null, ARRSC = null;
const waiters = new Map();
let reqId = 0;

function send(m, tr) { if (A.node) A.node.port.postMessage(m, tr || []); }
function request(m, type) {
  return new Promise((res) => { const id = ++reqId; waiters.set(id, res); send(Object.assign({ req: id }, m)); });
}

async function startAudio() {
  if (A.started) return;
  A.started = true;
  const Ctx = window.AudioContext || window.webkitAudioContext;
  A.ctx = new Ctx({ latencyHint: 'interactive' });
  A.sr = A.ctx.sampleRate;
  try {
    await A.ctx.audioWorklet.addModule('engine.js');
  } catch (e) {
    const code = await (await fetch('engine.js')).text();
    await A.ctx.audioWorklet.addModule(URL.createObjectURL(new Blob([code], { type: 'application/javascript' })));
  }
  A.node = new AudioWorkletNode(A.ctx, 'marv-engine', { numberOfInputs: 1, numberOfOutputs: 1, outputChannelCount: [2], channelCount: 1, channelCountMode: 'explicit', channelInterpretation: 'speakers' });
  A.node.connect(A.ctx.destination);
  A.node.port.onmessage = (e) => onEngine(e.data);
  if (A.ctx.state !== 'running') await A.ctx.resume();
  applyPrefsToEngine();
  sendLatency();
  setInterval(sendLatency, 3000);
  connectMic(prefs.get('inDev', null));
  const out = prefs.get('outDev', null);
  if (out && A.ctx.setSinkId) A.ctx.setSinkId(out).catch(() => {});
}

async function connectMic(deviceId) {
  if (!navigator.mediaDevices || !navigator.mediaDevices.getUserMedia) { A.micOk = false; return; }
  try {
    if (A.micStream) A.micStream.getTracks().forEach((t) => t.stop());
    const c = { echoCancellation: false, noiseSuppression: false, autoGainControl: false };
    if (deviceId) c.deviceId = { exact: deviceId };
    A.micStream = await navigator.mediaDevices.getUserMedia({ audio: c });
    if (A.mic) A.mic.disconnect();
    A.mic = A.ctx.createMediaStreamSource(A.micStream);
    A.mic.connect(A.node);
    A.micOk = true;
    sendLatency();
  } catch (e) {
    A.micOk = false;
    toast('Mikrofon nicht verfügbar – Laden (📂), Wave-Aufnahme, ACID und DRUM funktionieren trotzdem.');
  }
}

function sendLatency() {
  if (!A.ctx) return;
  const outS = (A.ctx.outputLatency || 0) + (A.ctx.baseLatency || 0.01);
  let inS = 0.01;
  try { const tr = A.micStream && A.micStream.getAudioTracks()[0]; const st = tr && tr.getSettings && tr.getSettings(); if (st && st.latency) inS = st.latency; } catch (e) { /* */ }
  const extra = A.latMs / 1000;
  send({ t: 'lat', out: outS * A.sr, inp: (inS + extra) * A.sr });
}

function applyPrefsToEngine() {
  const src = prefs.get('padSource', null);
  if (src) src.forEach((v, p) => send({ t: 'source', p, v }));
  send({ t: 'set', k: 'liveBars', v: prefs.get('liveBars', 0) });
  send({ t: 'set', k: 'bounce', v: prefs.get('bounce', true) });
  send({ t: 'set', k: 'countIn', v: prefs.get('countIn', true) });
  send({ t: 'set', k: 'launchIdx', v: prefs.get('launchIdx', 3) });
  send({ t: 'set', k: 'tapOverdub', v: prefs.get('tapOverdub', true) });
}

function onEngine(m) {
  switch (m.t) {
    case 'status':
      S = m; A.recvAt = performance.now();
      if (m.seq) SEQ = m.seq;
      if (m.notes) NOTES = m.notes;
      if (m.inst) INST = m.inst;
      if (m.arr) { ARR = m.arr; ARRSC = m.arrScene; }
      break;
    case 'hint': toast(m.msg); break;
    case 'error': console.error(m.msg); break;
    case 'recorded': saveRecording(m.pad, m.data); break;
    case 'warpJobs': runWarpJobs(m.jobs); break;
    case 'song': onSongFinished(m.data); break;
    case 'pattern': onPatternRendered(m.data); break;
    default: if (m.req && waiters.has(m.req)) { waiters.get(m.req)(m); waiters.delete(m.req); }
  }
}

/** Aktuell hörbarer Frame – zwischen den Status-Meldungen hochgerechnet. */
function heardNow() {
  if (!S) return 0;
  return S.h + (performance.now() - A.recvAt) * S.sr / 1000;
}
function masterProgress() {
  if (!S || S.ml <= 0) return -1;
  return fmodJ(heardNow() - S.ms, S.ml) / S.ml;
}
function padProgress(p) {
  const d = S && S.pads[p];
  if (!d || !d.len || !S.play) return 0;
  const rel = heardNow() - d.st;
  return rel < 0 ? 0 : (rel % d.len) / d.len;
}
function shotProgress(p) {
  const d = S && S.pads[p];
  if (!d || d.ss == null || !d.len) return -1;
  const rel = heardNow() - d.ss, total = d.len / (d.rate || 1);
  return rel < 0 || rel >= total ? -1 : rel / total;
}
function seqStep() {
  if (!S || !S.play) return -1;
  const bar = S.ml > 0 ? S.ml : S.sr * 2, origin = S.ml > 0 ? S.ms : S.so, rel = heardNow() - origin;
  if (rel < 0) return -1;
  return fmodJ(Math.floor(rel * STEPS / bar), STEPS);
}
function rollProgress(p) {
  if (!S || !S.play) return -1;
  const bar = S.ml > 0 ? S.ml : S.sr * 2, origin = S.ml > 0 ? S.ms : S.so, loopLen = bar * (S.rollBars[p] || 1), rel = heardNow() - origin;
  if (rel < 0) return -1;
  return fmodJ(rel, loopLen) / loopLen;
}

// ------------------------------------------------------------------ Dateien
async function decodeFile(buf) {
  const ab = await A.ctx.decodeAudioData(buf.slice(0));
  const n = Math.min(ab.length, A.sr * 66), out = new Float32Array(n);
  for (let c = 0; c < ab.numberOfChannels; c++) { const d = ab.getChannelData(c); for (let i = 0; i < n; i++) out[i] += d[i] / ab.numberOfChannels; }
  return out;
}
function encodeWav(data, rate) {
  const n = data.length, buf = new ArrayBuffer(44 + n * 2), v = new DataView(buf);
  const w = (o, s) => { for (let i = 0; i < s.length; i++) v.setUint8(o + i, s.charCodeAt(i)); };
  w(0, 'RIFF'); v.setUint32(4, 36 + n * 2, true); w(8, 'WAVE'); w(12, 'fmt '); v.setUint32(16, 16, true); v.setUint16(20, 1, true); v.setUint16(22, 1, true);
  v.setUint32(24, rate, true); v.setUint32(28, rate * 2, true); v.setUint16(32, 2, true); v.setUint16(34, 16, true); w(36, 'data'); v.setUint32(40, n * 2, true);
  for (let i = 0; i < n; i++) v.setInt16(44 + i * 2, Math.max(-1, Math.min(1, data[i])) * 32767, true);
  return new Blob([buf], { type: 'audio/wav' });
}
let dlCap = null;
async function saveBlob(blob, filename) {
  try {
    if (window.claude && window.claude.use) {
      if (dlCap === null) dlCap = await window.claude.use('downloads');
      if (dlCap) { await dlCap.save({ filename, data: blob }); toast('Gespeichert: ' + filename); return; }
    }
  } catch (e) { if (e && e.code === 'declined') return; }
  try {
    const a = document.createElement('a');
    a.href = URL.createObjectURL(blob); a.download = filename;
    document.body.appendChild(a); a.click(); a.remove();
    setTimeout(() => URL.revokeObjectURL(a.href), 4000);
    toast('Download: ' + filename);
  } catch (e) { toast('Speichern ist hier nicht möglich'); }
}
function pickFile(accept) {
  return new Promise((res) => {
    const i = document.createElement('input');
    i.type = 'file'; i.accept = accept || 'audio/*,.wav,.mp3,.m4a,.ogg,.flac';
    i.onchange = () => res(i.files && i.files[0] ? i.files[0] : null);
    i.click();
  });
}

// ------------------------------------------------------------------ Warp (Port von Warp.kt)
const Warp = {
  bpmFromName(name) {
    if (!name) return null;
    const n = name.replace(/\.[^.]+$/, '');
    const pats = [/(\d{2,3}(?:[.,]\d+)?)\s*-?\s*bpm/ig, /bpm\s*-?\s*(\d{2,3}(?:[.,]\d+)?)/ig, /(?:^|[_\- ])(\d{2,3})(?=$|[_\- ])/g];
    for (const p of pats) { let m; while ((m = p.exec(n))) { const v = parseFloat(m[1].replace(',', '.')); if (v >= 60 && v <= 200) return v; } }
    return null;
  },
  analyzeBpm(x, sr) {
    const hop = Math.max(1, Math.floor(sr / 100)), nf = Math.floor(x.length / hop);
    if (nf < 120) return null;
    const e = new Float32Array(nf);
    for (let k = 0; k < nf; k++) { let s = 0; const o = k * hop; for (let i = 0; i < hop; i++) s += x[o + i] * x[o + i]; e[k] = Math.log(1 + 100 * s / hop); }
    const on = new Float32Array(nf);
    let mean = 0;
    for (let k = 1; k < nf; k++) { on[k] = Math.max(0, e[k] - e[k - 1]); mean += on[k]; }
    mean /= nf;
    if (mean < 1e-5) return null;
    for (let k = 0; k < nf; k++) on[k] -= mean;
    const r = (lag) => { if (lag >= nf) return 0; let s = 0; for (let k = 0; k < nf; k++) s += on[k] * on[(k + lag) % nf]; return s; };
    let best = -1, bs = -Infinity;
    for (let lag = 30; lag <= 100; lag++) { const s = r(lag) + 0.5 * r(2 * lag); if (s > bs) { bs = s; best = lag; } }
    let bpm = 6000 / best;
    while (bpm < 80) bpm *= 2;
    while (bpm >= 170) bpm /= 2;
    return bpm;
  },
  octErr(a, b) { let m = Infinity; for (const k of [0.5, 1, 2]) m = Math.min(m, Math.abs(Math.log(a / (b * k)))); return m; },
  analyze(x, sr, name) {
    const dur = x.length / sr, nb = this.bpmFromName(name);
    const POW2 = [1, 2, 4, 8, 16, 32], OTHER = [0.25, 0.5, 3, 6, 12, 24];
    if (nb) {
      const raw = dur * nb / 240;
      let bars = Math.round(raw * 4) / 4;
      for (const c of POW2.concat(OTHER)) if (Math.abs(raw / c - 1) < 0.03) bars = c;
      return { bpm: nb, bars: Math.max(0.25, bars), source: 'Dateiname' };
    }
    const auto = this.analyzeBpm(x, sr);
    let best = null, be = Infinity;
    for (const [set, lo, hi, pen] of [[POW2, 70, 185, 0], [OTHER, 60, 200, 0.06]]) {
      for (const bars of set) {
        const b = 240 * bars / dur;
        if (b < lo || b > hi) continue;
        const err = (auto ? this.octErr(b, auto) : 0) + pen + 0.15 * Math.abs(Math.log(b / 120));
        if (err < be) { be = err; best = [b, bars]; }
      }
    }
    if (best && (!auto || this.octErr(best[0], auto) < 0.12)) return { bpm: best[0], bars: best[1], source: auto ? 'Länge + Rhythmus' : 'Länge' };
    const bpm = auto || 120;
    return { bpm, bars: Math.max(1, Math.round(dur * bpm / 240)), source: 'Rhythmus-Analyse' };
  },
  stretch(x, outLen, sr) {
    const n = x.length;
    if (outLen <= 1 || n < 2) return new Float32Array(Math.max(1, outLen));
    if (Math.abs(n / outLen - 1) < 0.002) { const o = new Float32Array(outLen); o.set(x.subarray(0, Math.min(n, outLen))); return o; }
    const winNom = Math.max(64, Math.floor(sr * 0.04));
    if (n < winNom * 3 || outLen < winNom * 3) {
      const o = new Float32Array(outLen), st = n / outLen;
      for (let i = 0; i < outLen; i++) { const p = i * st, i0 = Math.min(n - 1, Math.floor(p)), i1 = Math.min(i0 + 1, n - 1); o[i] = x[i0] + (x[i1] - x[i0]) * (p - i0); }
      return o;
    }
    const frames = Math.max(4, Math.round(outLen / (winNom / 2))), hopOut = outLen / frames, half = Math.floor(hopOut), win = 2 * half;
    const ratio = n / outLen, tol = Math.floor(sr * 0.01);
    const xx = new Float32Array(2 * n + win + 2 * tol);
    for (let i = 0; i < xx.length; i++) xx[i] = x[i % n];
    const wnd = new Float32Array(win);
    for (let i = 0; i < win; i++) wnd[i] = 0.5 - 0.5 * Math.cos(2 * Math.PI * i / win);
    const out = new Float32Array(outLen), norm = new Float32Array(outLen);
    let prevIn = 0;
    for (let k = 0; k < frames; k++) {
      const outPos = Math.round(k * hopOut), nominal = fmodJ(Math.round(outPos * ratio), n);
      let best = nominal;
      if (k > 0) {
        const natural = fmodJ(prevIn + Math.round(hopOut), n);
        let bs = -Infinity;
        for (let d = -tol; d <= tol; d += 2) {
          const c = fmodJ(nominal + d, n);
          let s = 0;
          for (let j = 0; j < half; j += 4) s += xx[c + j] * xx[natural + j];
          if (s > bs) { bs = s; best = c; }
        }
      }
      for (let j = 0; j < win; j++) { const o = (outPos + j) % outLen; out[o] += xx[best + j] * wnd[j]; norm[o] += wnd[j]; }
      prevIn = best;
    }
    for (let i = 0; i < outLen; i++) if (norm[i] > 1e-3) out[i] /= norm[i];
    return out;
  }
};
const tick = () => new Promise((r) => setTimeout(r, 0));
async function runWarpJobs(jobs) {
  for (const j of jobs) {
    await tick();
    const data = Warp.stretch(j.src, j.target, A.sr);
    send({ t: 'warped', p: j.pad, data, gen: j.gen }, [data.buffer]);
  }
}
async function loadClip(p, data, name) {
  const info = Warp.analyze(data, A.sr, name);
  await tick();
  const bpm = S && S.ml > 0 ? S.sr * 240 / S.ml : info.bpm;
  const target = Math.max(64, Math.round(info.bars * (A.sr * 240 / bpm)));
  const stretched = Warp.stretch(data, target, A.sr);
  send({ t: 'clip', p, src: data, bpm: info.bpm, bars: info.bars, data: stretched });
  const barsTxt = info.bars >= 1 && Number.isInteger(info.bars) ? info.bars + ' Takt' + (info.bars > 1 ? 'e' : '') : info.bars + ' Takt';
  const warp = Math.abs(bpm - info.bpm) > 0.05 ? ' → gewarpt auf ' + Math.round(bpm) + ' BPM' : '';
  return barsTxt + ' · ' + info.bpm.toFixed(1).replace('.', ',') + ' BPM (' + info.source + ')' + warp;
}

// ------------------------------------------------------------------ Speicher (IndexedDB): Projekte & Aufnahmen
let dbP = null;
function db() {
  if (dbP) return dbP;
  dbP = new Promise((res, rej) => {
    try {
      const r = indexedDB.open('marvpad', 1);
      r.onupgradeneeded = () => { const d = r.result; d.createObjectStore('projects', { keyPath: 'id' }); d.createObjectStore('recordings', { keyPath: 'id' }); };
      r.onsuccess = () => res(r.result);
      r.onerror = () => rej(r.error);
    } catch (e) { rej(e); }
  });
  return dbP;
}
async function dbPut(store, obj) { const d = await db(); return new Promise((res, rej) => { const t = d.transaction(store, 'readwrite'); t.objectStore(store).put(obj); t.oncomplete = res; t.onerror = () => rej(t.error); }); }
async function dbAll(store) { const d = await db(); return new Promise((res, rej) => { const t = d.transaction(store); const r = t.objectStore(store).getAll(); r.onsuccess = () => res(r.result); r.onerror = () => rej(r.error); }); }
async function dbDel(store, id) { const d = await db(); return new Promise((res) => { const t = d.transaction(store, 'readwrite'); t.objectStore(store).delete(id); t.oncomplete = res; }); }
async function saveRecording(pad, data) {
  try { await dbPut('recordings', { id: Date.now() + '_' + pad, pad, date: Date.now(), rate: A.sr, data }); } catch (e) { /* Speicher nicht verfügbar */ }
}
// ===================================================================== Marv PAD Web – Oberfläche
const F = { NONE: 0, MUTE: 1, SOLO: 2, ERASE: 3, DUPL: 4 };
const FUNC_NAMES = ['SZENE', 'MUTE', 'SOLO', 'ERASE', 'DUPL', 'REPEAT'];
const FUNC_COL = ['var(--accent)', '#5aa0ff', '#ffc828', '#ff3246', 'var(--accent)', '#3ce6a0'];
const UI = {
  xyMode: 0, func: F.NONE, sceneView: false, xyHold: false, duplSrc: -1, sceneDupSrc: -1, infoMode: prefs.get('info', false),
  statusColors: prefs.get('statusColors', true), design: prefs.get('design', 'noir'), xyX: 0.5, xyY: 0.5, trail: [], drumAcc: false,
  kbPad: -1, songView: false, edPad: -1, time: 0
};
const R = {};
const DESIGNS = [['noir', 'NOIR'], ['neon', 'NEON'], ['studio', 'STUDIO'], ['glow', 'GLOW'], ['hud', 'HUD'], ['retro', 'RETRO'], ['comic', 'COMIC'], ['glas', 'GLAS'], ['pastell', 'PASTELL'], ['holz', 'HOLZ'], ['creme', 'CREME'], ['grau', 'GRAU']];

function cssVar(n) { return getComputedStyle(document.body).getPropertyValue(n).trim() || '#ff6a00'; }
let toastT = 0;
function toast(msg) {
  const t = $('#toast');
  t.textContent = msg; t.classList.add('show');
  clearTimeout(toastT); toastT = setTimeout(() => t.classList.remove('show'), 2600);
}
function haptic() { try { navigator.vibrate && navigator.vibrate(8); } catch (e) { /* */ } }

// ------------------------------------------------------------------ Aufbau
function buildUI() {
  document.body.dataset.design = UI.design;
  const app = $('#app');
  app.innerHTML = '';
  // Kopf
  const top = el('div', 'top');
  const logo = el('img', 'logo'); logo.src = 'logo.png'; logo.alt = 'Marv PAD'; logo.dataset.info = 'logo';
  const lcd = el('div', 'lcd'); lcd.dataset.info = 'lcd';
  lcd.innerHTML = '<div class="l1"><span id="lcdA"></span><span class="bpm" id="lcdBpm"></span></div><div class="l2" id="lcdName"></div><div class="l3" id="lcdXY"></div><div class="bar"><i id="lcdBar"></i></div>';
  lcd.onclick = (e) => { const r = lcd.getBoundingClientRect(); if (e.clientX > r.left + r.width * 0.62) showTempo(); else showFxList(); };
  const menu = el('button', 'iconbtn', '☰'); menu.id = 'menuBtn'; menu.onclick = showMainMenu; menu.setAttribute('aria-label', 'Menü');
  top.append(logo, lcd, menu);
  // Regler
  const knobs = el('div', 'knobs');
  R.knobs = [];
  for (let k = 0; k < 5; k++) {
    const kn = el('div', 'knob'); kn.dataset.info = 'knob' + k;
    const c = el('canvas'); c.width = 108; c.height = 108;
    const l = el('div', 'kl');
    kn.append(c, l); knobs.append(kn);
    R.knobs.push({ el: kn, c, l, last: '' });
    knobDrag(kn, k);
  }
  // Funktionsleiste
  const fr = el('div', 'funcrow');
  R.func = [];
  const mk = (b) => { const x = el('button', 'fbtn'); x.dataset.info = 'func' + b; x.style.setProperty('--fc', FUNC_COL[b]); funcHandlers(x, b); R.func[b] = x; return x; };
  const fx = el('button', 'fxbtn'); fx.dataset.info = 'fxbtn'; R.fxbtn = fx; fxButton(fx);
  fr.append(mk(0), mk(1), mk(2), fx, mk(3), mk(4), mk(5));
  // Tabs
  const tabs = el('div', 'tabs');
  R.tabs = ['🎛 EFFEKTE', '🔊 ACID 303', '🥁 DRUM 909'].map((n, i) => {
    const t = el('button', 'tab'); t.innerHTML = n + '<span class="dot" hidden></span>'; t.dataset.info = 'tab' + i;
    t.onclick = () => { if (UI.xyMode !== i) { instRelease(); UI.xyMode = i; } haptic(); };
    tabs.append(t); return t;
  });
  // Bühne (XY / Instrumente)
  const stage = el('div', 'stage'); stage.dataset.info = 'stage';
  const sc = el('canvas'); stage.append(sc); R.stage = stage; R.stageC = sc;
  stageHandlers(stage);
  // Pads
  const pw = el('div', 'pads-wrap');
  const pads = el('div', 'pads');
  R.pads = [];
  for (let p = 0; p < PADS; p++) {
    const cell = el('div', 'cell');
    const pad = el('div', 'pad'); pad.dataset.info = 'pad' + p;
    pad.innerHTML = '<div class="pempty"><b>+</b><span></span></div><div class="sct"></div><div class="plabel"></div><button class="pimport" aria-label="Laden">📂</button><div class="pfx">FX<i></i><i></i><i></i></div>';
    const under = el('div', 'under');
    const more = el('button', '', '•••'); more.dataset.info = 'more' + p; more.onclick = () => showPadMenu(p);
    const key = el('button', '', '🎹'); key.dataset.info = 'key' + p; key.onclick = () => { if (S && S.pads[p].has) openRoll(p); else toast('Pad ' + (p + 1) + ' ist leer'); };
    under.append(more, key);
    cell.append(pad, under); pads.append(cell);
    const o = { el: pad, empty: pad.querySelector('.pempty'), emptyT: pad.querySelector('.pempty span'), sct: pad.querySelector('.sct'), label: pad.querySelector('.plabel'), imp: pad.querySelector('.pimport'), fx: pad.querySelector('.pfx'), dots: pad.querySelectorAll('.pfx i'), last: {} };
    o.imp.addEventListener('pointerdown', (e) => { e.stopPropagation(); }); o.imp.onclick = (e) => { e.stopPropagation(); showImportMenu(p); };
    o.fx.dataset.info = 'padfx' + p;
    padFxButton(o.fx, p);
    padHandlers(pad, p);
    R.pads.push(o);
  }
  const pfx = el('canvas'); pfx.id = 'padfx';
  pw.append(pads, pfx); R.padC = pfx; R.padsWrap = pw;
  // Sequenzer
  const seq = el('div', 'seq'); seq.dataset.info = 'seq';
  const steps = el('div', 'steps');
  R.steps = [];
  for (let k = 0; k < STEPS; k++) { const s = el('div', 'step'); s.innerHTML = '<div class="fill"></div><div class="nd" hidden></div><div class="dots"></div>'; steps.append(s); R.steps.push({ el: s, fill: s.firstChild, nd: s.children[1], dots: s.children[2] }); }
  stepHandlers(steps);
  const tr = el('div', 'transport');
  const rec = el('button', 'tbtn rec', '●'), play = el('button', 'tbtn play', '▶'), stop = el('button', 'tbtn', '■');
  const undo = el('button', 'tbtn small'), redo = el('button', 'tbtn small');
  undo.innerHTML = '↶<small>UNDO</small>'; redo.innerHTML = '↷<small>REDO</small>';
  rec.dataset.info = 'rec'; play.dataset.info = 'play'; stop.dataset.info = 'stop'; undo.dataset.info = 'undo'; redo.dataset.info = 'redo';
  longPress(rec, () => recPressed(), () => showRecMenu());
  play.onclick = () => send({ t: 'play' });
  longPress(stop, () => stopPressed(), () => { send({ t: 'seqClear' }); toast('Pattern geleert – ↶ UNDO holt es zurück'); }, 600);
  undo.onclick = () => send({ t: 'undo' }); redo.onclick = () => send({ t: 'redo' });
  const info = el('div', 'seqinfo');
  info.innerHTML = '<b id="sqHead"></b><span id="sqL2"></span><span id="sqL3"></span>';
  tr.append(rec, play, stop, undo, redo, info);
  Object.assign(R, { rec, play, stop, undo, redo });
  seq.append(steps, tr);
  // Fuß
  const foot = el('div', 'foot');
  const pills = el('div', 'pills');
  R.pillSrc = el('button', 'pill'); R.pillMode = el('button', 'pill'); R.pillQ = el('button', 'pill');
  R.pillSrc.dataset.info = 'pillSrc'; R.pillMode.dataset.info = 'pillMode'; R.pillQ.dataset.info = 'pillQ';
  R.pillSrc.onclick = () => { const all = S ? S.pads.every((d) => d.src === 1) : false; const v = all ? 0 : 1; send({ t: 'allSources', v }); prefs.set('padSource', new Array(PADS).fill(v)); toast(v ? 'Eingang aller Pads: Wave (App-Audio inkl. Effekte)' : 'Eingang aller Pads: Mikrofon'); };
  R.pillMode.onclick = () => {
    const cur = S ? S.liveBars : 0, nx = { 0: -1, '-1': 1, 1: 2, 2: 4, 4: 8, 8: 16 }[cur] ?? 0;
    send({ t: 'set', k: 'liveBars', v: nx }); prefs.set('liveBars', nx);
    toast(nx < 0 ? 'TIPPEN: 1. Tipp startet, 2. Tipp beendet – Loop rastet auf 1–32 Takte ein' : nx > 0 ? 'LIVE: leeres Pad antippen, Aufnahme ab nächstem Takt (' + nx + ' Takt)' : 'HALTEN: Aufnahme solange der Finger liegt');
  };
  R.pillQ.onclick = () => { const v = ((S ? S.launchIdx : 3) + 1) % 6; send({ t: 'set', k: 'launchIdx', v }); prefs.set('launchIdx', v); toast(v === 0 ? 'Start-Raster AUS: Loops starten/stoppen sofort' : 'Start-Raster ' + LAUNCH_NAMES[v] + ': angetippte Loops starten/stoppen auf dem nächsten Rasterpunkt'); };
  pills.append(R.pillSrc, R.pillMode, R.pillQ);
  R.modeText = el('div', 'modetext'); R.modeText.hidden = true;
  R.credit = el('div', 'credit', '© marween · Made with Claude');
  foot.append(pills, R.modeText, R.credit);
  app.append(top, knobs, fr, tabs, stage, pw, seq, foot);
  window.addEventListener('resize', layoutCanvases);
  layoutCanvases();
}

function layoutCanvases() {
  const dpr = Math.min(2, window.devicePixelRatio || 1);
  for (const c of [R.stageC, R.padC]) {
    if (!c) continue;
    const r = c.getBoundingClientRect();
    c.width = Math.max(1, Math.round(r.width * dpr)); c.height = Math.max(1, Math.round(r.height * dpr));
    c._dpr = dpr;
  }
}

// ------------------------------------------------------------------ Hilfen für Gesten
function longPress(btn, onTap, onLong, ms = 500) {
  let timer = 0, fired = false;
  btn.addEventListener('pointerdown', (e) => { fired = false; clearTimeout(timer); timer = setTimeout(() => { fired = true; haptic(); onLong(); }, ms); });
  const end = () => { clearTimeout(timer); };
  btn.addEventListener('pointerup', () => { end(); if (!fired) onTap(); });
  btn.addEventListener('pointerleave', end);
  btn.addEventListener('pointercancel', end);
}

// ------------------------------------------------------------------ Regler
function knobLabel(k) {
  if (UI.xyMode === 1) return ['CUTOFF', 'RESO', 'ENV MOD', 'DECAY', 'LEVEL'][k];
  if (UI.xyMode === 2) { const n = INST ? DRUM_NAMES[INST.drumSel] : ''; return k < 3 ? ['TUNE', 'DECAY', 'LEVEL'][k] + ' ' + n : ['DRIVE', 'VOL'][k - 3]; }
  const t = S ? S.slotType[S.editSlot] : 0;
  return ['MIX', FXN.P1[t], FXN.P2[t], 'VOL', 'SWING'][k];
}
function knobValue(k) {
  if (!S) return 0.5;
  if (UI.xyMode === 1 && INST) return [INST.acidCut, INST.acidRes, INST.acidEnv, INST.acidDec, INST.acidVol][k];
  if (UI.xyMode === 2 && INST) { const v = INST.drumSel; return [INST.drumTune[v], INST.drumDecay[v], INST.drumLevel[v], INST.drumDrive, INST.drumVol][k]; }
  if (k < 3) return S.knobs[S.editSlot][k];
  return k === 3 ? S.vol : S.swing;
}
function setKnobValue(k, v) {
  if (!S) return;
  if (UI.xyMode === 1 && INST) { INST[['acidCut', 'acidRes', 'acidEnv', 'acidDec', 'acidVol'][k]] = v; sendInst(); return; }
  if (UI.xyMode === 2 && INST) {
    const s = INST.drumSel;
    if (k === 0) INST.drumTune[s] = v; else if (k === 1) INST.drumDecay[s] = v; else if (k === 2) INST.drumLevel[s] = v; else if (k === 3) INST.drumDrive = v; else INST.drumVol = v;
    sendInst(); return;
  }
  if (k < 3) { S.knobs[S.editSlot][k] = v; send({ t: 'knob', k, v }); }
  else if (k === 3) { S.vol = v; send({ t: 'knob', k: 3, v }); }
  else { S.swing = v; send({ t: 'swing', v }); }
}
function knobDrag(kn, k) {
  let sy = 0, sv = 0, id = null;
  kn.addEventListener('pointerdown', (e) => { id = e.pointerId; kn.setPointerCapture(id); sy = e.clientY; sv = knobValue(k); });
  kn.addEventListener('pointermove', (e) => { if (e.pointerId !== id) return; setKnobValue(k, clamp(sv + (sy - e.clientY) / 180, 0, 1)); });
  const up = (e) => { if (e.pointerId === id) id = null; };
  kn.addEventListener('pointerup', up); kn.addEventListener('pointercancel', up);
}
function drawKnob(o, v, label) {
  const key = v.toFixed(3) + label + UI.design;
  if (o.last === key) return;
  o.last = key;
  o.l.textContent = label;
  const c = o.c, g = c.getContext('2d'), w = c.width, cx = w / 2, cy = w / 2, r = w * 0.38;
  g.clearRect(0, 0, w, w);
  const a0 = Math.PI * 0.75, a1 = a0 + Math.PI * 1.5 * v;
  g.lineCap = 'round';
  g.lineWidth = w * 0.07; g.strokeStyle = 'rgba(128,128,128,.28)';
  g.beginPath(); g.arc(cx, cy, r, a0, a0 + Math.PI * 1.5); g.stroke();
  g.strokeStyle = cssVar('--accent');
  g.beginPath(); g.arc(cx, cy, r, a0, a1); g.stroke();
  const grd = g.createRadialGradient(cx - r * 0.3, cy - r * 0.3, 2, cx, cy, r * 0.8);
  grd.addColorStop(0, cssVar('--panel2')); grd.addColorStop(1, cssVar('--panel'));
  g.fillStyle = grd; g.beginPath(); g.arc(cx, cy, r * 0.72, 0, Math.PI * 2); g.fill();
  g.strokeStyle = cssVar('--line'); g.lineWidth = 2; g.stroke();
  g.strokeStyle = cssVar('--ink'); g.lineWidth = w * 0.05;
  g.beginPath(); g.moveTo(cx + Math.cos(a1) * r * 0.25, cy + Math.sin(a1) * r * 0.25); g.lineTo(cx + Math.cos(a1) * r * 0.62, cy + Math.sin(a1) * r * 0.62); g.stroke();
  g.fillStyle = cssVar('--muted'); g.font = '600 ' + Math.round(w * 0.14) + 'px "JetBrains Mono", monospace'; g.textAlign = 'center';
  g.fillText(Math.round(v * 100) + '', cx, cy + r * 0.95 + w * 0.06);
}

// ------------------------------------------------------------------ Funktionsleiste
function funcMode(b) { return [F.NONE, F.MUTE, F.SOLO, F.ERASE, F.DUPL, F.NONE][b]; }
function funcOn(b) { return b === 0 ? UI.sceneView : b === 5 ? !!(S && S.rep) : UI.func === funcMode(b); }
let funcState = null;
function funcHandlers(btn, b) {
  btn.addEventListener('pointerdown', (e) => {
    const wasOn = funcOn(b);
    funcState = { b, wasOn, used: false, long: false, id: e.pointerId };
    if (b === 0) { if (!UI.sceneView) { UI.sceneView = true; UI.sceneDupSrc = -1; } }
    else if (b !== 5 && UI.func !== funcMode(b)) { UI.func = funcMode(b); UI.duplSrc = -1; }
    haptic();
    funcState.timer = setTimeout(() => {
      if (!funcState || funcState.used) return;
      funcState.long = true; haptic();
      if (b === 0) { send({ t: 'set', k: 'sceneChain', v: !(S && S.chainS) }); toast(!(S && S.chainS) ? '⛓ Kette an: die Szenen laufen nacheinander (Länge: Szene lange drücken)' : '⛓ Kette aus: die Szene läuft in Schleife'); }
      else if (b === 1) { send({ t: 'muteAll' }); toast('Alle Loops stumm / an'); }
      else if (b === 2) { send({ t: 'clearSolo' }); toast('Alle SOLO aus'); }
      else if (b === 5) { send({ t: 'cycleRepeat' }); setTimeout(() => toast('Note Repeat ' + REPEAT_NAMES[S ? S.repIdx : 2]), 80); }
      if (b >= 1 && b <= 4 && !funcState.wasOn) UI.func = F.NONE;
    }, 500);
  });
  btn.addEventListener('pointerup', () => {
    const st = funcState;
    if (!st || st.b !== b) return;
    clearTimeout(st.timer); funcState = null;
    if (st.long) return;
    if (b === 5) {
      const on = !(S && S.rep);
      send({ t: 'set', k: 'repeatOn', v: on }); if (!on) send({ t: 'repeatAllUp' });
      toast(on ? 'Note Repeat ' + REPEAT_NAMES[S ? S.repIdx : 2] + ': ⚡-Pad oder Drum-Pad halten (Rate: REPEAT lange drücken)' : 'Note Repeat aus');
      return;
    }
    if (st.used) { if (!st.wasOn) { if (b === 0) UI.sceneView = false; else UI.func = F.NONE; } return; }
    if (st.wasOn) { if (b === 0) UI.sceneView = false; else UI.func = F.NONE; UI.duplSrc = -1; return; }
    toast(['SZENE: Pads = Szenen 1–12. Antippen startet die Szene zur nächsten Eins · lange drücken = Länge/Kette',
      'MUTE: Pads antippen schaltet sie stumm/an · MUTE lange drücken = alle', 'SOLO: Pads antippen – nur Solo-Pads sind zu hören · lange = alle aus',
      'ERASE: Pad antippen löscht sein Pattern, nochmal das Sample · Schritte antippen leert das Pattern',
      UI.sceneView ? 'DUPL: Quell-Szene, dann Ziel-Szene antippen' : 'DUPL: Quell-Pad, dann Ziel-Pad antippen'][b]);
  });
  btn.addEventListener('pointercancel', () => { if (funcState) clearTimeout(funcState.timer); funcState = null; });
}
function markFuncUsed() { if (funcState) funcState.used = true; }
function fxButton(btn) {
  let t = 0, long = false, x0 = 0;
  btn.addEventListener('pointerdown', (e) => { long = false; x0 = e.clientX; const r = btn.getBoundingClientRect(); x0 = e.clientX < r.left + r.width / 2 ? -1 : 1; t = setTimeout(() => { long = true; haptic(); showFxList(); }, 450); });
  btn.addEventListener('pointerup', () => { clearTimeout(t); if (!long && S) { send({ t: 'selectFx', type: S.slotType[S.editSlot] + x0 }); S.slotType[S.editSlot] = fmodJ(S.slotType[S.editSlot] + x0, 37); haptic(); } });
  btn.addEventListener('pointercancel', () => clearTimeout(t));
}

// ------------------------------------------------------------------ Pads
const padPtr = new Map(); // pointerId -> {p, x, y, gest, vol0, shot}
let twoFinger = null;
function padHandlers(pad, p) {
  pad.addEventListener('pointerdown', (e) => {
    if (!A.started) return;
    pad.setPointerCapture(e.pointerId);
    haptic();
    if (UI.sceneView) { sceneDown(p, e); return; }
    if (UI.func !== F.NONE) { funcPad(p); return; }
    const existing = [...padPtr.entries()].find(([, v]) => v.p === p);
    if (existing) {
      if (!existing[1].gest && !twoFinger) {
        existing[1].gest = 3;
        send({ t: 'abort', p }); send({ t: 'padUp', p, noTap: true });
        twoFinger = { id: e.pointerId, p, long: false, timer: setTimeout(() => { twoFinger.long = true; send({ t: 'solo', p }); haptic(); toast('Pad ' + (p + 1) + ' SOLO an/aus'); }, 500) };
      }
      return;
    }
    padPtr.set(e.pointerId, { p, x: e.clientX, y: e.clientY, gest: 0, vol0: S ? S.pads[p].g : 1 });
    send({ t: 'padDown', p, hold: true });
    UI.flash[p] = 1;
  });
  pad.addEventListener('pointermove', (e) => {
    const st = padPtr.get(e.pointerId);
    if (!st || st.gest === 3 || st.gest === 2) return;
    const dx = e.clientX - st.x, dy = e.clientY - st.y, d = S && S.pads[p];
    if (!st.gest) {
      if (dx < -40 && Math.abs(dx) > 2 * Math.abs(dy)) {
        st.gest = 2;
        if (d && d.has) { send({ t: 'abort', p }); send({ t: 'clear', p }); toast('Pad ' + (p + 1) + ' gelöscht – ↶ UNDO holt es zurück'); haptic(); }
        else if (d && d.s === 1) { send({ t: 'abort', p }); toast('Aufnahme verworfen'); }
        return;
      }
      if (Math.abs(dy) > 18 && Math.abs(dy) > 1.5 * Math.abs(dx) && d && d.has && d.s !== 1) { st.gest = 1; send({ t: 'abort', p }); st.vol0 = d.g; }
    }
    if (st.gest === 1) { const v = clamp(st.vol0 + (st.y - e.clientY) / 160, 0, 1.5); send({ t: 'gain', p, v }); UI.vol = { p, v, t: 1 }; }
  });
  const up = (e) => {
    if (twoFinger && twoFinger.id === e.pointerId) {
      clearTimeout(twoFinger.timer);
      if (!twoFinger.long) { send({ t: 'mute', p: twoFinger.p }); toast('Pad ' + (twoFinger.p + 1) + ' stumm / an'); }
      twoFinger = null; return;
    }
    const st = padPtr.get(e.pointerId);
    if (!st) return;
    padPtr.delete(e.pointerId);
    if (st.gest) { if (st.gest === 3) send({ t: 'repeatAllUp' }); return; }
    send({ t: 'padUp', p });
  };
  pad.addEventListener('pointerup', up); pad.addEventListener('pointercancel', up);
}
UI.flash = new Float32Array(PADS);
function funcPad(p) {
  markFuncUsed();
  const d = S && S.pads[p];
  if (UI.func === F.MUTE) { if (d && d.has) send({ t: 'mute', p }); }
  else if (UI.func === F.SOLO) send({ t: 'solo', p });
  else if (UI.func === F.ERASE) send({ t: 'erase', p });
  else if (UI.func === F.DUPL) {
    if (UI.duplSrc < 0) { if (d && d.has) { UI.duplSrc = p; toast('Pad ' + (p + 1) + ' kopieren → jetzt das Ziel-Pad antippen'); } else toast('Zuerst ein Pad mit Inhalt antippen'); }
    else if (UI.duplSrc === p) UI.duplSrc = -1;
    else { send({ t: 'dupl', src: UI.duplSrc, dst: p }); UI.duplSrc = -1; }
  }
}
function sceneDown(p, e) {
  markFuncUsed();
  if (UI.func === F.ERASE) { send({ t: 'sceneDelete', i: p }); return; }
  if (UI.func === F.DUPL) {
    if (UI.sceneDupSrc < 0) { if (S && S.ex[p]) { UI.sceneDupSrc = p; toast('Szene ' + (p + 1) + ' kopieren → Ziel-Szene antippen'); } }
    else { send({ t: 'sceneCopy', a: UI.sceneDupSrc, b: p }); UI.sceneDupSrc = -1; }
    return;
  }
  const pad = R.pads[p].el;
  let fired = false;
  const t = setTimeout(() => { fired = true; haptic(); showSceneMenu(p); }, 500);
  const up = () => { clearTimeout(t); pad.removeEventListener('pointerup', up); pad.removeEventListener('pointercancel', cancel); if (!fired) send({ t: 'scene', i: p }); };
  const cancel = () => { clearTimeout(t); pad.removeEventListener('pointerup', up); pad.removeEventListener('pointercancel', cancel); };
  pad.addEventListener('pointerup', up); pad.addEventListener('pointercancel', cancel);
}
function padFxButton(b, p) {
  let t = 0, long = false;
  b.addEventListener('pointerdown', (e) => { e.stopPropagation(); long = false; t = setTimeout(() => { long = true; haptic(); showPadFxEditor(p); }, 450); });
  b.addEventListener('pointerup', (e) => {
    e.stopPropagation(); clearTimeout(t);
    if (long || !S) return;
    const d = S.pads[p];
    if (!d.pft.some((x) => x >= 0)) { showPadFxEditor(p); return; }
    send({ t: 'padChain', p, v: !d.chain }); d.chain = !d.chain;
    toast('Pad ' + (p + 1) + ': Effekte ' + (d.chain ? 'an' : 'aus'));
  });
  b.addEventListener('pointercancel', () => clearTimeout(t));
}

// ------------------------------------------------------------------ Sequenzer
function stepHandlers(steps) {
  let st = null;
  steps.addEventListener('pointerdown', (e) => {
    const k = R.steps.findIndex((s) => s.el.contains(e.target));
    if (k < 0 || !S) return;
    steps.setPointerCapture(e.pointerId);
    if (UI.func === F.ERASE) { markFuncUsed(); send({ t: 'seqClear' }); toast('Pattern geleert – ↶ UNDO holt es zurück'); return; }
    const sel = S.sel;
    if (sel < 0) { toast('Erst ein ⚡-Pad antippen – seine Schritte erscheinen hier'); return; }
    st = { k, y: e.clientY, v0: SEQ[sel][k], drag: false, sel };
  });
  steps.addEventListener('pointermove', (e) => {
    if (!st) return;
    const dy = st.y - e.clientY;
    if (st.drag || Math.abs(dy) > 8) {
      const v = clamp((st.v0 > 0 ? st.v0 : 1) + dy / 90, 0.05, 1);
      send({ t: 'stepVel', p: st.sel, k: st.k, v, undo: !st.drag }); SEQ[st.sel][st.k] = v; st.drag = true;
    }
  });
  const up = () => { if (st && !st.drag) { send({ t: 'step', p: st.sel, k: st.k }); SEQ[st.sel][st.k] = SEQ[st.sel][st.k] > 0 ? 0 : 1; haptic(); } st = null; };
  steps.addEventListener('pointerup', up); steps.addEventListener('pointercancel', () => { st = null; });
}
function recPressed() {
  if (!S) return;
  if (S.song) { send({ t: 'songStop' }); return; }
  if (S.rec) { send({ t: 'rec' }); toast('Aufnahme beendet – das Pattern läuft weiter (↶ UNDO verwirft sie)'); return; }
  const wasPlaying = S.play;
  send({ t: 'rec' });
  toast(!wasPlaying && S.countIn ? '◔ Ein Takt Einzählen, dann läuft die Pattern-Aufnahme (REC lang = Song-Aufnahme)' : '● Pattern-Aufnahme läuft – nochmal REC beendet sie');
}
function stopPressed() {
  if (!S) return;
  const wasRec = S.rec;
  send({ t: 'stop' });
  if (S.song) send({ t: 'songStop' });
  else if (wasRec) setTimeout(finishPattern, 150);
}

// ------------------------------------------------------------------ Bühne: XY / ACID / DRUM
let stagePtr = null, instPtr = null;
const SR = {}; // Rechtecke der Bühne (in CSS-Pixeln)
function rect(x, y, w, h) { return { x, y, w, h, c: (px, py) => px >= x && px < x + w && py >= y && py < y + h }; }
function stageLayout() {
  const r = R.stage.getBoundingClientRect(), W = r.width, H = r.height;
  SR.W = W; SR.H = H;
  const narrow = W < 330;
  const hw = narrow ? (W - 24) * 0.42 : 70, mw = narrow ? (W - 24) * 0.58 : 84;
  const top = narrow ? 44 : 8;
  SR.hold = rect(W - 8 - hw, top, hw, 30);
  SR.mot = rect(W - 16 - hw - mw, top, mw, 30);
  const sw = narrow ? (W - 26) / 3 : Math.max(38, Math.min(54, (W - 190) / 3 - 5));
  SR.slots = [0, 1, 2].map((s) => rect(8 + s * (sw + 5), 8, sw, 30));
  // ACID
  const hdr = (n) => { const w = (W - 12 - (n - 1) * 5) / n; return [...Array(n).keys()].map((i) => rect(6 + i * (w + 5), 6, w, 26)); };
  SR.acidHdr = hdr(7); SR.drumHdr = hdr(5);
  const top2 = 38, rest = H - 6 - top2, rowH = 14, laneH = Math.max(34, (rest - 2 * rowH - 10) * 0.48);
  SR.lane = rect(6, top2, W - 12, laneH);
  SR.acc = rect(6, top2 + laneH + 3, W - 12, rowH);
  SR.sli = rect(6, SR.acc.y + rowH + 2, W - 12, rowH);
  SR.play = rect(6, SR.sli.y + rowH + 6, W - 12, H - 6 - (SR.sli.y + rowH + 6));
  // DRUM
  const vw = (W - 12 - 10 * 3) / 11;
  SR.voices = [...Array(11).keys()].map((v) => rect(6 + v * (vw + 3), top2, vw, 22));
  SR.dstep = rect(6, top2 + 27, W - 12, 24);
  const pTop = SR.dstep.y + 30, pw = (W - 12 - 5 * 3) / 6, ph = (H - 6 - pTop - 3) / 2;
  SR.dpads = [...Array(12).keys()].map((i) => rect(6 + (i % 6) * (pw + 3), pTop + Math.floor(i / 6) * (ph + 3), pw, ph));
}
function stageHandlers(stage) {
  stage.addEventListener('pointerdown', (e) => {
    if (!A.started) return;
    stageLayout();
    const r = stage.getBoundingClientRect(), x = e.clientX - r.left, y = e.clientY - r.top;
    stage.setPointerCapture(e.pointerId);
    if (UI.xyMode !== 0) { instDown(e.pointerId, x, y); return; }
    for (let s = 0; s < 3; s++) if (SR.slots[s].c(x, y)) { send({ t: 'selectSlot', s }); haptic(); return; }
    if (SR.hold.c(x, y)) { UI.xyHold = !UI.xyHold; send({ t: 'xy', x: UI.xyX, y: UI.xyY, touch: UI.xyHold || !!stagePtr }); haptic(); return; }
    if (SR.mot.c(x, y)) { send({ t: 'motion' }); haptic(); return; }
    if (stagePtr != null) return;
    stagePtr = e.pointerId;
    setXy(x, y, true);
  });
  stage.addEventListener('pointermove', (e) => {
    const r = stage.getBoundingClientRect(), x = e.clientX - r.left, y = e.clientY - r.top;
    if (e.pointerId === stagePtr) setXy(x, y, true);
    if (instPtr && e.pointerId === instPtr.id) instMove(x, y);
  });
  const up = (e) => {
    if (e.pointerId === stagePtr) { stagePtr = null; send({ t: 'xy', x: UI.xyX, y: UI.xyY, touch: UI.xyHold, live: false }); }
    if (instPtr && e.pointerId === instPtr.id) instUp();
  };
  stage.addEventListener('pointerup', up); stage.addEventListener('pointercancel', up);
}
function setXy(x, y, live) {
  UI.xyX = clamp(x / SR.W, 0, 1); UI.xyY = clamp(1 - y / SR.H, 0, 1);
  UI.trail.push([UI.xyX, UI.xyY, 1]); if (UI.trail.length > 40) UI.trail.shift();
  send({ t: 'xy', x: UI.xyX, y: UI.xyY, touch: true, live });
}
function sendInst() { if (INST) send({ t: 'inst', st: INST }); }
function instDown(id, x, y) {
  if (!INST) return;
  const st = INST;
  haptic();
  if (UI.xyMode === 1) {
    for (let i = 0; i < 7; i++) if (SR.acidHdr[i].c(x, y)) {
      if (i === 0) st.acidOn = !st.acidOn; else if (i === 1) st.acidSquare = !st.acidSquare; else if (i === 2) st.acidFx = !st.acidFx;
      else if (i === 3) st.acidOct = Math.max(-1, st.acidOct - 1); else if (i === 4) st.acidOct = Math.min(2, st.acidOct + 1);
      else if (i === 5) { send({ t: 'instUndo', label: 'ACID Zufall' }); const sc = [0, 0, 3, 5, 7, 10, 12, 12, 15, 0, 7]; for (let k = 0; k < 16; k++) { st.acidGate[k] = Math.random() < 0.72; st.acidSemi[k] = sc[Math.floor(Math.random() * sc.length)] + (Math.random() < 0.15 ? -12 : 0); st.acidAcc[k] = Math.random() < 0.25; st.acidSlide[k] = Math.random() < 0.22; } st.acidGate[0] = true; }
      else { send({ t: 'instUndo', label: 'ACID leeren' }); st.acidGate.fill(false); st.acidAcc.fill(false); st.acidSlide.fill(false); st.acidSemi.fill(0); }
      sendInst(); return;
    }
    const cw = SR.lane.w / 16;
    if (SR.lane.c(x, y)) {
      const k = clamp(Math.floor((x - SR.lane.x) / cw), 0, 15);
      send({ t: 'instUndo', label: 'ACID Schritt ' + (k + 1) });
      instPtr = { id, kind: 2, k, wasOn: st.acidGate[k], drag: false, y0: y, voice: false };
      if (!st.acidGate[k]) { st.acidGate[k] = true; sendInst(); }
      return;
    }
    if (SR.acc.c(x, y) || SR.sli.c(x, y)) {
      const k = clamp(Math.floor((x - SR.lane.x) / cw), 0, 15);
      send({ t: 'instUndo', label: 'ACID Accent/Slide' });
      if (SR.acc.c(x, y)) st.acidAcc[k] = !st.acidAcc[k]; else st.acidSlide[k] = !st.acidSlide[k];
      sendInst(); return;
    }
    if (SR.play.c(x, y)) {
      const sm = clamp(Math.floor((x - SR.play.x) / SR.play.w * 25), 0, 24);
      instPtr = { id, kind: 1, semi: sm };
      st.acidCutMod = (1 - (y - SR.play.y) / SR.play.h) * 2 - 1; sendInst();
      send({ t: 'acidOn', semi: sm, slide: false });
    }
    return;
  }
  for (let i = 0; i < 5; i++) if (SR.drumHdr[i].c(x, y)) {
    if (i === 0) st.drumOn = !st.drumOn; else if (i === 1) st.drumFx = !st.drumFx;
    else if (i === 2) { send({ t: 'instUndo', label: 'DRUM Preset' }); applyDrumPreset(st, st.drumPreset + 1); }
    else if (i === 3) { send({ t: S && S.play ? 'stop' : 'play' }); return; }
    else { send({ t: 'instUndo', label: 'DRUM leeren' }); for (const r of st.drumSteps) r.fill(0); }
    sendInst(); return;
  }
  for (let v = 0; v < 11; v++) if (SR.voices[v].c(x, y)) { st.drumSel = v; sendInst(); send({ t: 'drumTrig', v }); return; }
  if (SR.dstep.c(x, y)) {
    const k = clamp(Math.floor((x - SR.dstep.x) / SR.dstep.w * 16), 0, 15), row = st.drumSteps[st.drumSel];
    send({ t: 'instUndo', label: 'DRUM Schritt ' + (k + 1) });
    row[k] = (row[k] + 1) % 3; sendInst(); return;
  }
  for (let i = 0; i < 12; i++) if (SR.dpads[i].c(x, y)) {
    if (i === 11) { UI.drumAcc = !UI.drumAcc; return; }
    st.drumSel = i; UI.drumHit = UI.drumHit || new Float32Array(11); UI.drumHit[i] = 1;
    send({ t: 'drumHit', v: i, acc: UI.drumAcc, hold: true });
    instPtr = { id, kind: 3, v: i };
    return;
  }
}
function instMove(x, y) {
  const st = INST;
  if (!instPtr || !st) return;
  if (instPtr.kind === 1) {
    const sm = clamp(Math.floor((x - SR.play.x) / SR.play.w * 25), 0, 24);
    st.acidCutMod = clamp((1 - (y - SR.play.y) / SR.play.h) * 2 - 1, -1, 1); sendInst();
    if (sm !== instPtr.semi) { instPtr.semi = sm; send({ t: 'acidOn', semi: sm, slide: true }); }
  } else if (instPtr.kind === 2) {
    if (instPtr.drag || Math.abs(y - instPtr.y0) > 6) {
      instPtr.drag = true;
      const v = clamp(1 - (y - SR.lane.y) / SR.lane.h, 0, 1), sm = Math.floor(v * 36) - 12;
      if (sm !== st.acidSemi[instPtr.k]) { st.acidSemi[instPtr.k] = sm; sendInst(); send({ t: 'acidOn', semi: sm, slide: instPtr.voice }); instPtr.voice = true; }
    }
  }
}
function instUp() {
  const st = INST;
  if (!instPtr) return;
  if (instPtr.kind === 1) { send({ t: 'acidOff' }); if (st) { st.acidCutMod = 0; sendInst(); } }
  else if (instPtr.kind === 2) { if (!instPtr.drag && instPtr.wasOn && st) { st.acidGate[instPtr.k] = false; sendInst(); } if (instPtr.voice) send({ t: 'acidOff' }); }
  else if (instPtr.kind === 3) send({ t: 'drumUp', v: instPtr.v });
  instPtr = null;
}
function instRelease() { if (instPtr) instUp(); }
function applyDrumPreset(st, i) {
  for (const r of st.drumSteps) r.fill(0);
  const set = (v, ...s) => s.forEach((k) => (st.drumSteps[v][k] = 1)), acc = (v, ...s) => s.forEach((k) => (st.drumSteps[v][k] = 2));
  switch (fmodJ(i, 4)) {
    case 0: acc(0, 0, 4, 8, 12); set(6, 4, 12); set(8, 2, 6, 10, 14); set(7, 0, 4, 8, 12); set(5, 7, 15); break;
    case 1: acc(0, 0, 4, 8, 12); set(7, 2, 6, 10, 14); set(7, 0, 1, 3, 4, 5, 7, 8, 9, 11, 12, 13, 15); set(8, 2, 10); set(6, 4, 12); set(10, 0, 4, 8, 12); set(5, 3, 11, 14); break;
    case 2: acc(0, 0); set(0, 10); set(0, 3); acc(1, 4, 12); set(1, 7, 9, 15); set(7, 0, 2, 4, 6, 8, 10, 12, 14); set(8, 14); break;
    default: acc(0, 0); set(0, 6, 10); acc(1, 4, 12); set(6, 4, 12); set(7, 0, 2, 4, 6, 8, 10, 12, 14); set(2, 13); set(3, 14); set(4, 15);
  }
  st.drumPreset = fmodJ(i, 4);
}

function rr(g, x, y, w, h, r) { g.beginPath(); g.roundRect ? g.roundRect(x, y, w, h, r) : g.rect(x, y, w, h); }
function chip(g, r, label, on, acc, ink, panel) {
  g.fillStyle = on ? acc : panel; rr(g, r.x, r.y, r.w, r.h, 7); g.fill();
  g.strokeStyle = on ? '#fff' : 'rgba(128,128,128,.45)'; g.lineWidth = 1; g.stroke();
  g.fillStyle = on ? '#fff' : ink;
  const fs = Math.min(11, r.w / Math.max(3, label.length * 0.62));
  g.font = '600 ' + fs + 'px "Chakra Petch", "Segoe UI", sans-serif'; g.textAlign = 'center'; g.textBaseline = 'middle';
  g.fillText(label, r.x + r.w / 2, r.y + r.h / 2 + 0.5);
}
function drawStage() {
  const c = R.stageC, g = c.getContext('2d'), dpr = c._dpr || 1;
  stageLayout();
  g.setTransform(dpr, 0, 0, dpr, 0, 0);
  const W = SR.W, H = SR.H, acc = cssVar('--accent'), ink = cssVar('--ink'), panel = cssVar('--panel2');
  g.clearRect(0, 0, W, H);
  if (UI.xyMode === 0) drawXy(g, W, H, acc, ink, panel);
  else if (UI.xyMode === 1) drawAcid(g, W, H, acc, ink, panel);
  else drawDrum(g, W, H, acc, ink, panel);
}
function drawXy(g, W, H, acc, ink, panel) {
  const t = S ? S.slotType[S.editSlot] : 0, hue = FXN.HUES[t];
  const col = 'hsl(' + hue + ',100%,60%)';
  g.fillStyle = cssVar('--panel'); g.fillRect(0, 0, W, H);
  g.strokeStyle = 'rgba(128,128,128,.18)'; g.lineWidth = 1;
  for (let i = 1; i < 8; i++) { g.beginPath(); g.moveTo(W * i / 8, 0); g.lineTo(W * i / 8, H); g.stroke(); g.beginPath(); g.moveTo(0, H * i / 8); g.lineTo(W, H * i / 8); g.stroke(); }
  const touching = stagePtr != null || UI.xyHold || (S && S.mot === 3 && S.motT);
  let px = UI.xyX, py = UI.xyY;
  if (S && S.mot === 3 && stagePtr == null) { px = S.motX; py = S.motY; }
  for (let i = 0; i < UI.trail.length; i++) {
    const tr = UI.trail[i]; tr[2] *= 0.94;
    g.fillStyle = 'hsla(' + hue + ',100%,60%,' + (tr[2] * 0.35) + ')';
    g.beginPath(); g.arc(tr[0] * W, (1 - tr[1]) * H, 6 + 10 * (i / UI.trail.length), 0, Math.PI * 2); g.fill();
  }
  const x = px * W, y = (1 - py) * H;
  g.strokeStyle = touching ? col : 'rgba(128,128,128,.5)'; g.lineWidth = 1;
  g.beginPath(); g.moveTo(x, 0); g.lineTo(x, H); g.moveTo(0, y); g.lineTo(W, y); g.stroke();
  const grd = g.createRadialGradient(x, y, 0, x, y, touching ? 46 : 26);
  grd.addColorStop(0, touching ? col : 'rgba(160,160,160,.6)'); grd.addColorStop(1, 'transparent');
  g.fillStyle = grd; g.beginPath(); g.arc(x, y, touching ? 46 : 26, 0, Math.PI * 2); g.fill();
  g.fillStyle = '#fff'; g.beginPath(); g.arc(x, y, 5, 0, Math.PI * 2); g.fill();
  g.fillStyle = ink; g.globalAlpha = 0.55; g.font = '600 10px "Chakra Petch", "Segoe UI", sans-serif'; g.textAlign = 'left'; g.textBaseline = 'alphabetic';
  g.fillText('X · ' + FXN.X[t], 8, H - 8); g.save(); g.translate(14, H - 26); g.rotate(-Math.PI / 2); g.fillText('Y · ' + FXN.Y[t], 0, 0); g.restore(); g.globalAlpha = 1;
  if (S) {
    for (let s = 0; s < 3; s++) {
      const r = SR.slots[s], on = S.slotOn[s], sel = S.editSlot === s;
      g.fillStyle = on ? acc : panel; rr(g, r.x, r.y, r.w, r.h, 7); g.fill();
      g.strokeStyle = sel ? (on ? '#fff' : acc) : 'rgba(128,128,128,.4)'; g.lineWidth = sel ? 2.5 : 1; g.stroke();
      g.fillStyle = on ? '#fff' : ink; g.font = '600 8px "Chakra Petch", "Segoe UI", sans-serif'; g.textAlign = 'left'; g.fillText(String(s + 1), r.x + 4, r.y + 10);
      g.font = '700 11px "Chakra Petch", "Segoe UI", sans-serif'; g.textAlign = 'center'; g.fillText(on ? FXN.CODES[S.slotType[s]] : 'AUS', r.x + r.w / 2 + 3, r.y + r.h / 2 + 4);
    }
    chip(g, SR.hold, (UI.xyHold ? '🔒' : '🔓') + ' HOLD', UI.xyHold, acc, ink, panel);
    const ms = S.mot, red = '#e6283a';
    const lab = ms === 1 ? '● BEREIT' : ms === 2 ? '● ' + S.motSec.toFixed(1).replace('.', ',') + 's' : ms === 3 ? '▶ AUTO' : '● REC';
    chip(g, SR.mot, lab, ms > 0, ms === 3 ? acc : red, ink, panel);
    if (ms === 3 && S.motProg >= 0) { g.fillStyle = 'rgba(255,255,255,.25)'; rr(g, SR.mot.x, SR.mot.y, SR.mot.w * S.motProg, SR.mot.h, 7); g.fill(); }
    if (ms === 1) { g.fillStyle = ink; g.globalAlpha = 0.75; g.font = '500 10px "Chakra Petch", "Segoe UI", sans-serif'; g.textAlign = 'center'; g.fillText('Fläche berühren = Aufnahme startet', W / 2, SR.mot.y + 46); g.globalAlpha = 1; }
  }
}
function drawAcid(g, W, H, acc, ink, panel) {
  g.fillStyle = '#101116'; g.fillRect(0, 0, W, H);
  if (!INST) return;
  const st = INST;
  const labels = [st.acidOn ? '● AN' : '○ AUS', st.acidSquare ? '⊓ SQR' : '⩘ SAW', 'FX', 'OKT−', 'OKT+', '🎲 RND', 'LEEREN'];
  const ons = [st.acidOn, false, st.acidFx, false, false, false, false];
  for (let i = 0; i < 7; i++) chip(g, SR.acidHdr[i], labels[i], ons[i], acc, '#fff', '#22232b');
  const cur = seqStep(), cw = SR.lane.w / 16;
  for (let k = 0; k < 16; k++) {
    const x0 = SR.lane.x + k * cw;
    g.fillStyle = k === cur ? '#30303a' : k % 4 === 0 ? '#1e1f26' : '#18191f'; rr(g, x0 + 1, SR.lane.y, cw - 2, SR.lane.h, 3); g.fill();
    if (st.acidGate[k]) {
      const v = clamp((st.acidSemi[k] + 12) / 36, 0.05, 1), h = SR.lane.h * v;
      g.fillStyle = st.acidAcc[k] ? '#ffd0a0' : acc; rr(g, x0 + 1, SR.lane.y + SR.lane.h - h, cw - 2, h, 3); g.fill();
      if (cw > 17) { const sm = st.acidSemi[k]; g.fillStyle = '#fff'; g.font = '600 8.5px "Chakra Petch", "Segoe UI", sans-serif'; g.textAlign = 'center'; g.fillText(NOTE_NAMES[fmodJ(sm, 12)] + (2 + st.acidOct + Math.floor(sm / 12)), x0 + cw / 2, SR.lane.y + 10); }
    }
    if (k === cur) { g.strokeStyle = '#fff'; g.lineWidth = 1.5; rr(g, x0 + 1, SR.lane.y, cw - 2, SR.lane.h, 3); g.stroke(); }
    for (const [row, on, lab, c] of [[SR.acc, st.acidAcc[k], 'A', '#ffaa28'], [SR.sli, st.acidSlide[k], 'S', '#50b4ff']]) {
      g.fillStyle = on ? c : '#1c1d24'; rr(g, x0 + 1, row.y, cw - 2, row.h, 2); g.fill();
      if (k === 0) { g.fillStyle = on ? '#000' : 'rgba(255,255,255,.5)'; g.font = '600 8px "Chakra Petch", "Segoe UI", sans-serif'; g.textAlign = 'center'; g.fillText(lab, x0 + cw / 2, row.y + row.h / 2 + 3); }
    }
  }
  const pr = SR.play, nw = pr.w / 25;
  for (let k = 0; k < 25; k++) {
    const n = k % 12, black = [1, 3, 6, 8, 10].includes(n);
    g.fillStyle = instPtr && instPtr.kind === 1 && instPtr.semi === k ? acc : black ? '#14141a' : '#24252e';
    g.fillRect(pr.x + k * nw, pr.y, nw, pr.h);
    if (n === 0) { g.fillStyle = 'rgba(255,255,255,.6)'; g.font = '600 9px "Chakra Petch", "Segoe UI", sans-serif'; g.textAlign = 'center'; g.fillText('C' + (2 + st.acidOct + k / 12), pr.x + k * nw + nw / 2, pr.y + pr.h - 5); }
  }
  g.strokeStyle = acc; g.globalAlpha = 0.6; g.strokeRect(pr.x, pr.y, pr.w, pr.h); g.globalAlpha = 1;
  g.fillStyle = 'rgba(255,255,255,.55)'; g.font = '500 9px "Chakra Petch", "Segoe UI", sans-serif'; g.textAlign = 'left'; g.fillText('↑ FILTER · X = TON (Gleiten = Slide)', pr.x + 5, pr.y + 11);
  if (S && S.rec) { g.fillStyle = '#ff505a'; g.textAlign = 'right'; g.fillText('● REC → Schritte', pr.x + pr.w - 5, pr.y + 11); }
}
function drawDrum(g, W, H, acc, ink, panel) {
  g.fillStyle = '#101116'; g.fillRect(0, 0, W, H);
  if (!INST) return;
  const st = INST, playing = S && S.play;
  const labels = [st.drumOn ? '● AN' : '○ AUS', 'FX', st.drumPreset >= 0 ? '▦ ' + PRESET_NAMES[st.drumPreset] : '▦ PRESET', playing ? '■ STOP' : '▶ PLAY', 'LEEREN'];
  const ons = [st.drumOn, st.drumFx, false, playing, false];
  for (let i = 0; i < 5; i++) chip(g, SR.drumHdr[i], labels[i], ons[i], acc, '#fff', '#22232b');
  for (let v = 0; v < 11; v++) {
    chip(g, SR.voices[v], DRUM_NAMES[v], v === st.drumSel, acc, '#fff', '#22232b');
    if (v !== st.drumSel && st.drumSteps[v].some((x) => x > 0)) { g.fillStyle = acc; g.beginPath(); g.arc(SR.voices[v].x + SR.voices[v].w - 4, SR.voices[v].y + 4, 2, 0, 7); g.fill(); }
  }
  const cur = seqStep(), cw = SR.dstep.w / 16, row = st.drumSteps[st.drumSel];
  for (let k = 0; k < 16; k++) {
    const x = SR.dstep.x + k * cw;
    g.fillStyle = row[k] === 2 ? '#ffd9b0' : row[k] === 1 ? acc : k % 4 === 0 ? '#22232c' : '#1a1b22';
    rr(g, x + 1, SR.dstep.y, cw - 2, SR.dstep.h, 3); g.fill();
    if (row[k] === 2) { g.fillStyle = '#000'; g.font = '700 8px "Chakra Petch", "Segoe UI", sans-serif'; g.textAlign = 'center'; g.fillText('A', x + cw / 2, SR.dstep.y + SR.dstep.h / 2 + 3); }
    if (k === cur) { g.strokeStyle = '#fff'; g.lineWidth = 1.5; rr(g, x + 1, SR.dstep.y, cw - 2, SR.dstep.h, 3); g.stroke(); }
  }
  UI.drumHit = UI.drumHit || new Float32Array(11);
  for (let i = 0; i < 12; i++) {
    const r = SR.dpads[i];
    if (i === 11) { chip(g, r, UI.drumAcc ? 'ACC ✓' : 'ACCENT', UI.drumAcc, acc, '#fff', '#22232b'); continue; }
    UI.drumHit[i] = Math.max(0, UI.drumHit[i] - 0.06);
    const h = UI.drumHit[i];
    g.fillStyle = h > 0 ? acc : '#24252e'; g.globalAlpha = h > 0 ? 0.35 + 0.65 * h : 1; rr(g, r.x, r.y, r.w, r.h, 8); g.fill(); g.globalAlpha = 1;
    g.strokeStyle = i === st.drumSel ? '#fff' : acc; g.globalAlpha = i === st.drumSel ? 1 : 0.5; g.lineWidth = i === st.drumSel ? 2 : 1; g.stroke(); g.globalAlpha = 1;
    g.fillStyle = '#fff'; g.font = '700 13px "Chakra Petch", "Segoe UI", sans-serif'; g.textAlign = 'center'; g.fillText(DRUM_NAMES[i], r.x + r.w / 2, r.y + r.h / 2 + 2);
    g.globalAlpha = 0.6; g.font = '500 8px "Chakra Petch", "Segoe UI", sans-serif'; g.fillText(DRUM_LONG[i], r.x + r.w / 2, r.y + r.h / 2 + 13); g.globalAlpha = 1;
  }
}

// ------------------------------------------------------------------ Pads: Status-Ringe (Overlay-Canvas)
function drawPadOverlay() {
  const c = R.padC, g = c.getContext('2d'), dpr = c._dpr || 1;
  const base = R.padsWrap.getBoundingClientRect();
  g.setTransform(dpr, 0, 0, dpr, 0, 0);
  g.clearRect(0, 0, c.width, c.height);
  if (!S || UI.sceneView) return;
  const green = '#28e66e', red = '#ff2d41', yellow = '#ffbe1e', blinkOn = Math.sin(UI.time * 12) > 0, pulse = 0.5 + 0.5 * Math.sin(UI.time * 7);
  for (let p = 0; p < PADS; p++) {
    const d = S.pads[p], er = R.pads[p].el.getBoundingClientRect();
    const x = er.left - base.left, y = er.top - base.top, w = er.width, h = er.height, rad = parseFloat(getComputedStyle(R.pads[p].el).borderRadius) || 12;
    UI.flash[p] = Math.max(0, UI.flash[p] - 0.04);
    let col = null, ring = 0, tint = 0, arc = -1;
    if (d.ar) { col = red; ring = blinkOn ? 1 : 0.25; tint = 0.14 * ring; }
    else if (d.s === 1) { col = red; ring = 0.7 + 0.3 * pulse; tint = 0.16 + 0.14 * pulse; const mp = masterProgress(); arc = mp >= 0 ? mp : d.rs % 1; }
    else if (d.s === 4 || d.la) { col = yellow; ring = 0.95; tint = 0.18 + 0.12 * pulse; arc = padProgress(p); }
    else if (d.q === 1) { col = green; ring = blinkOn ? 1 : 0.25; tint = 0.14 * ring; }
    else if (d.q === 0) { col = blinkOn ? red : green; ring = 1; tint = 0.06; }
    else if (d.s === 2) { col = green; ring = S.play ? 0.8 : 0.35; tint = 0.07; arc = S.play ? padProgress(p) : -1; }
    else if (d.s === 5) { const sp = shotProgress(p); if (sp >= 0) { col = '#ff9620'; ring = 0.9; arc = sp; } }
    else if (d.s === 3) { col = '#969ba5'; ring = 0.45; }
    if (!UI.statusColors && d.s !== 1) { col = col && d.s === 0 ? col : (d.s === 2 || d.s === 5) ? cssVar('--accent') : col; tint = 0; }
    const lvl = Math.min(1, d.lv * 2.2);
    if (UI.flash[p] > 0 || lvl > 0.05) {
      const gl = Math.min(1, UI.flash[p] * 0.7 + lvl * 0.5), cc = col || cssVar('--accent');
      g.save(); g.shadowColor = cc; g.shadowBlur = 18 * gl; g.strokeStyle = cc; g.globalAlpha = gl; g.lineWidth = 2; rr(g, x, y, w, h, rad); g.stroke(); g.restore();
    }
    if (!col) continue;
    if (tint > 0) { g.fillStyle = col; g.globalAlpha = tint; rr(g, x, y, w, h, rad); g.fill(); g.globalAlpha = 1; }
    g.strokeStyle = col; g.globalAlpha = ring; g.lineWidth = 2.5; rr(g, x + 1.5, y + 1.5, w - 3, h - 3, Math.max(0, rad - 1.5)); g.stroke(); g.globalAlpha = 1;
    if (arc >= 0) {
      // rotierender Lichtring entlang des Pad-Randes
      const per = 2 * (w + h) - 8 * rad + 2 * Math.PI * rad, seg = per * 0.16, end = arc * per;
      g.save(); g.lineCap = 'round'; g.lineWidth = 5; g.strokeStyle = '#fff'; g.globalAlpha = 0.9; g.shadowColor = col; g.shadowBlur = 10;
      g.setLineDash([seg, per - seg]); g.lineDashOffset = -(end - seg);
      rr(g, x + 1.5, y + 1.5, w - 3, h - 3, Math.max(0, rad - 1.5)); g.stroke(); g.restore();
    }
  }
  if (UI.vol && UI.vol.t > 0) {
    const p = UI.vol.p, er = R.pads[p].el.getBoundingClientRect(), x = er.left - base.left, y = er.top - base.top;
    g.globalAlpha = UI.vol.t; g.fillStyle = 'rgba(0,0,0,.55)'; rr(g, x + er.width * 0.2, y + er.height * 0.36, er.width * 0.6, er.height * 0.26, 6); g.fill();
    g.fillStyle = '#fff'; g.font = '700 ' + Math.round(er.width * 0.17) + 'px "JetBrains Mono", monospace'; g.textAlign = 'center'; g.textBaseline = 'middle';
    g.fillText(Math.round(UI.vol.v * 100) + '%', x + er.width / 2, y + er.height * 0.49); g.globalAlpha = 1; g.textBaseline = 'alphabetic';
    if (![...padPtr.values()].some((v) => v.gest === 1)) UI.vol.t -= 0.02;
  }
}

// ------------------------------------------------------------------ Laufende Aktualisierung
function fmtBars(b) { return b + ' Takt' + (b > 1 ? 'e' : ''); }
function update() {
  UI.time += 1 / 60;
  if (S) {
    // Kopf-Display
    const t = S.slotType[S.editSlot], touching = stagePtr != null || UI.xyHold;
    let on = 0, last = -1;
    S.pads.forEach((d, i) => { if (d.fx) { on++; last = i; } });
    const tgt = on === PADS ? '→ALLE' : on === 0 ? '→AUS' : on === 1 ? '→P' + (last + 1) : '→' + on + 'P';
    $('#lcdA').textContent = (S.editSlot + 1) + '·FX ' + String(t + 1).padStart(2, '0') + '/37 ' + (touching ? '●' : '○') + ' ' + tgt;
    $('#lcdBpm').textContent = (S.met ? '♪ ' : '') + (S.bpm > 0 ? Math.round(S.bpm) + ' BPM' : '– BPM');
    $('#lcdName').textContent = UI.xyMode === 1 ? 'ACID 303' : UI.xyMode === 2 ? 'DRUM 909' : FXN.NAMES[t];
    $('#lcdXY').textContent = 'X ' + FXN.X[t] + ' ' + Math.round(UI.xyX * 100) + '  Y ' + FXN.Y[t] + ' ' + Math.round(UI.xyY * 100) + '   TEMPO ▸';
    const mp = masterProgress();
    $('#lcdBar').style.width = (mp < 0 ? 0 : mp * 100) + '%';
    for (let k = 0; k < 5; k++) drawKnob(R.knobs[k], knobValue(k), knobLabel(k));
    // Funktionsleiste
    for (let b = 0; b < 6; b++) {
      const sub = b === 0 ? String(S.cur + 1) + (S.qs >= 0 ? '→' + (S.qs + 1) : '') + (S.chainS ? ' ⛓' : '') : b === 2 && S.soloAny ? '● aktiv' : b === 5 ? REPEAT_NAMES[S.repIdx] : b === 4 && UI.duplSrc >= 0 ? 'P' + (UI.duplSrc + 1) + '→?' : '';
      const html = FUNC_NAMES[b] + (sub ? '<small>' + sub + '</small>' : '');
      if (R.func[b]._h !== html) { R.func[b].innerHTML = html; R.func[b]._h = html; }
      R.func[b].classList.toggle('on', funcOn(b));
    }
    const fh = '◀ ▶<b>' + FXN.CODES[t] + '</b>' + (S.editSlot + 1) + '/3';
    if (R.fxbtn._h !== fh) { R.fxbtn.innerHTML = fh; R.fxbtn._h = fh; }
    R.fxbtn.style.borderColor = 'hsl(' + FXN.HUES[t] + ',100%,60%)';
    R.tabs.forEach((tb, i) => {
      tb.classList.toggle('on', UI.xyMode === i);
      const run = S.play && ((i === 1 && S.acidOn && INST && INST.acidGate.some((x) => x)) || (i === 2 && S.drumOn && INST && INST.drumSteps.some((r) => r.some((x) => x > 0))));
      tb.querySelector('.dot').hidden = !run;
    });
    // Pads
    for (let p = 0; p < PADS; p++) updatePad(p);
    // Sequenzer
    const cur = seqStep(), sel = S.sel;
    for (let k = 0; k < STEPS; k++) {
      const s = R.steps[k], v = sel >= 0 ? SEQ[sel][k] : 0;
      s.fill.style.height = (v > 0 ? Math.max(8, v * 100) : 0) + '%';
      s.el.classList.toggle('cur', k === cur);
      let cnt = 0; for (let p = 0; p < PADS; p++) if (p !== sel && SEQ[p][k] > 0) cnt++;
      const dots = '•'.repeat(Math.min(3, cnt));
      if (s.dots.textContent !== dots) s.dots.textContent = dots;
      s.nd.hidden = !(sel >= 0 && NOTES[sel] && NOTES[sel].some((e) => Math.floor(e[0] * 16) % 16 === k));
    }
    R.rec.classList.toggle('on', S.rec || S.song);
    R.play.classList.toggle('on', S.play);
    R.undo.classList.toggle('off', !S.undo); R.redo.classList.toggle('off', !S.redo);
    const head = S.song ? '● SONG ' + Math.floor(S.songSec / 60) + ':' + String(Math.floor(S.songSec % 60)).padStart(2, '0') : S.cin > 0 ? '◔ ZÄHLT ' + S.cin : S.rec ? '● REC' : S.play ? '▶ PLAY' : '■ STOP';
    const hEl = $('#sqHead'); hEl.textContent = head; hEl.classList.toggle('red', S.song || S.rec || S.cin > 0);
    $('#sqL2').textContent = (S.songMode ? '🎬 SONG T' + (S.arrBar + 1) + ' · ' : '') + 'SZENE ' + (S.cur + 1) + (S.qs >= 0 ? '→' + (S.qs + 1) : '') + (S.chainS ? ' ⛓' : '');
    $('#sqL3').textContent = S.rep ? 'RPT ' + REPEAT_NAMES[S.repIdx] : sel >= 0 ? 'Pad ' + (sel + 1) : '⚡-Pad tippen';
    // Fuß
    const allW = S.pads.every((d) => d.src === 1), anyW = S.pads.some((d) => d.src === 1);
    R.pillSrc.textContent = allW ? '🔁 ALLE WAVE' : anyW ? '🎙/🔁 GEMISCHT' : '🎙 ALLE MIKRO';
    R.pillSrc.classList.toggle('on', anyW);
    R.pillMode.textContent = S.liveBars < 0 ? '👆 TIPPEN' : S.liveBars > 0 ? '⚡ LIVE ' + fmtBars(S.liveBars).toUpperCase() : '✋ HALTEN';
    R.pillMode.classList.toggle('on', S.liveBars !== 0);
    R.pillQ.textContent = '⏱ ' + LAUNCH_NAMES[S.launchIdx];
    R.pillQ.classList.toggle('on', S.launchIdx > 0);
    const mt = UI.sceneView && UI.func === F.ERASE ? 'ERASE: Szene antippen = löschen / leeren' : UI.sceneView && UI.func === F.DUPL ? (UI.sceneDupSrc < 0 ? 'DUPL: Quell-Szene antippen' : 'DUPL: Ziel-Szene antippen')
      : UI.sceneView ? 'SZENEN: antippen = zur nächsten Eins starten · lange = Länge/Kette' : UI.func === F.MUTE ? 'MUTE: Pads antippen = stumm / an' : UI.func === F.SOLO ? 'SOLO: Pads antippen = nur diese hören'
        : UI.func === F.ERASE ? 'ERASE: Pad = Pattern löschen, nochmal = Sample' : UI.func === F.DUPL ? (UI.duplSrc < 0 ? 'DUPL: Quell-Pad antippen' : 'DUPL: Ziel-Pad für Pad ' + (UI.duplSrc + 1) + ' antippen') : null;
    R.modeText.hidden = !mt; R.modeText.textContent = mt || ''; R.modeText.style.color = UI.func === F.ERASE ? 'var(--red)' : 'var(--accent)';
    R.credit.textContent = UI.infoMode ? 'ℹ INFO-MODUS · Element antippen · ☰ zum Beenden' : '© marween · Made with Claude';
  }
  // Canvas-Größen nachführen (Layout, Schriften, Drehen)
  for (const c of [R.stageC, R.padC]) { if (c) { const r = c.getBoundingClientRect(), dpr = c._dpr || 1; if (Math.abs(c.width - r.width * dpr) > 1 || Math.abs(c.height - r.height * dpr) > 1) { layoutCanvases(); break; } } }
  if (R.stageC) drawStage();
  if (R.padC) drawPadOverlay();
  if (UI.kbPad >= 0) drawRoll();
  if (UI.edPad >= 0) drawEditor();
  document.body.style.setProperty('--hue', (UI.time * 60 % 360) + 'deg');
  requestAnimationFrame(update);
}
function updatePad(p) {
  const d = S.pads[p], o = R.pads[p], pad = o.el;
  if (UI.sceneView) {
    const ex = S.ex[p], cur = S.cur === p, q = S.qs === p;
    pad.classList.add('scene'); pad.classList.toggle('ex', ex && !cur); pad.classList.toggle('cur', cur);
    pad.style.opacity = q && Math.sin(UI.time * 10) > 0 ? 0.6 : 1;
    const html = '<div><small>SZENE</small><b>' + (p + 1) + '</b><small>' + (q ? '▶ zur Eins' : ex ? fmtBars(S.sbars[p]) : '+ neu') + '</small></div>';
    if (o.sct._h !== html) { o.sct.innerHTML = html; o.sct._h = html; }
    o.sct.hidden = false; o.empty.hidden = true; o.label.hidden = true; o.imp.hidden = true; o.fx.hidden = true;
    pad.classList.remove('dim');
    return;
  }
  pad.classList.remove('scene', 'ex', 'cur'); pad.style.opacity = 1;
  o.sct.hidden = true; o.fx.hidden = false; o.label.hidden = false;
  const empty = d.s === 0 && !d.ar;
  o.empty.hidden = !empty; o.imp.hidden = !empty;
  if (empty) { const t = d.src === 1 ? '〰 WAVE' : '🎙 MIC'; if (o.emptyT.textContent !== t) o.emptyT.textContent = t; }
  let label = '';
  if (d.ar) label = 'BEREIT';
  else if (d.s === 1) label = (d.src === 1 ? 'WAVE ' : 'REC ') + d.rs.toFixed(1).replace('.', ',');
  else if (d.s === 4) label = 'DUB';
  else if (d.s === 5) label = '⚡ ' + (d.len / S.sr).toFixed(1).replace('.', ',') + 's';
  else if (d.s === 2 || d.s === 3) label = (d.s === 3 ? (d.q === 1 ? '▶ ' : 'AUS ') : '∞ ') + (S.ml > 0 ? (Math.round(d.len / S.ml * 4) / 4).toString().replace('.', ',') + ' T' : (d.len / S.sr).toFixed(1).replace('.', ',') + 's');
  else if (d.os) label = '⚡';
  if (o.label.textContent !== label) o.label.textContent = label;
  pad.classList.toggle('dim', S.soloAny && !d.so && d.has);
  // FX-Knopf mit drei Plätzen
  const chainOn = d.chain && d.pft.some((x) => x >= 0);
  o.fx.classList.toggle('on', chainOn);
  d.pft.forEach((tp, k) => o.dots[k].classList.toggle('f', tp >= 0));
  // Kennzeichen S / M
  let badges = '';
  if (d.so) badges += '<span class="pbadge" style="right:5px;background:#ffc828">S</span>';
  if (d.has && d.os && d.mu) badges += '<span class="pbadge" style="right:' + (d.so ? 25 : 5) + 'px;background:#5aa0ff">M</span>';
  if (o.last.badges !== badges) { pad.querySelectorAll('.pbadge').forEach((x) => x.remove()); pad.insertAdjacentHTML('beforeend', badges); o.last.badges = badges; }
  const ring = UI.func === F.MUTE ? '#5aa0ff' : UI.func === F.SOLO ? '#ffc828' : UI.func === F.DUPL ? cssVar('--accent') : UI.func === F.ERASE && d.has ? '#ff3246' : '';
  pad.style.outline = ring ? (UI.duplSrc === p ? '4px' : '2px') + ' dashed ' + ring : '';
  pad.style.outlineOffset = '3px';
}
// ===================================================================== Marv PAD Web – Menüs, Dialoge, Piano-Roll, Song, Wave-Editor, Info
let sheetEl = null;
function closeSheet() { if (sheetEl) { sheetEl.remove(); sheetEl = null; } }
function sheet(title, build) {
  closeSheet();
  const scrim = el('div', 'scrim');
  const sh = el('div', 'sheet');
  sh.append(el('h3', '', title));
  build(sh);
  const close = el('button', 'btn', 'Schließen'); close.onclick = closeSheet;
  sh.append(close);
  scrim.append(sh);
  scrim.addEventListener('pointerdown', (e) => { if (e.target === scrim) closeSheet(); });
  document.body.append(scrim);
  sheetEl = scrim;
  return sh;
}
function menu(title, items, note) {
  sheet(title, (sh) => {
    if (note) sh.append(el('div', 'note', note));
    for (const [label, fn] of items) {
      const b = el('button', 'item', label);
      b.onclick = () => { closeSheet(); fn(); };
      sh.append(b);
    }
  });
}
function chk(label, val, on) {
  const l = el('label', 'chk'); const c = el('input'); c.type = 'checkbox'; c.checked = val; c.onchange = () => on(c.checked);
  l.append(c, document.createTextNode(label)); return l;
}
function slider(min, max, val, on, step) {
  const s = el('input'); s.type = 'range'; s.min = min; s.max = max; s.step = step || 1; s.value = val; s.oninput = () => on(parseFloat(s.value)); return s;
}

// ------------------------------------------------------------------ Hauptmenü
function showMainMenu() {
  if (UI.infoMode) { UI.infoMode = false; prefs.set('info', false); hideInfo(); toast('Info-Modus aus'); return; }
  menu('Marv PAD', [
    ['💾 Projekt speichern', saveProjectDialog],
    ['📂 Projekt laden', showProjects],
    ['✨ Neues Projekt', () => confirmSheet('Neues Projekt?', 'Alle Pads, das Pattern und das Tempo werden geleert. Nicht gespeicherte Änderungen gehen verloren.', () => { send({ t: 'newProject' }); toast('Neues Projekt'); })],
    ['🎙 Meine Aufnahmen', () => showRecordings(-1)],
    [(S && S.bounce ? '🔁 Bounce: AN (Quellen nach Wave-Aufnahme stumm)' : '🔁 Bounce: AUS'), () => { const v = !(S && S.bounce); send({ t: 'set', k: 'bounce', v }); prefs.set('bounce', v); toast(v ? 'Bounce an' : 'Bounce aus'); }],
    [(UI.infoMode ? '☑' : '☐') + ' ℹ Info-Modus (Funktionen erklären)', () => { UI.infoMode = true; prefs.set('info', true); toast('Info-Modus an: tippe auf ein Element, um es erklärt zu bekommen'); }],
    ['🔌 Audio-Geräte (USB-Interface, Kopfhörer …)', showDevices],
    ['🎨 Design wählen', showDesigns],
    [(S && S.tapOverdub ? '👆 Tipp auf laufenden Loop: ● OVERDUB (→ auf Stopp)' : '👆 Tipp auf laufenden Loop: ■ STOPP (→ auf Overdub)'), () => { const v = !(S && S.tapOverdub); send({ t: 'set', k: 'tapOverdub', v }); prefs.set('tapOverdub', v); toast(v ? 'Tipp auf laufenden Loop = Overdub (gelb), nochmal tippen beendet. Stoppen: zwei Finger' : 'Tipp auf laufenden Loop = stoppen (im Start-Raster)'); }],
    [(UI.statusColors ? '☑' : '☐') + ' 🚦 Status-Farben (Rot Aufnahme · Grün Play · Gelb Overdub)', () => { UI.statusColors = !UI.statusColors; prefs.set('statusColors', UI.statusColors); }],
    ['🎬 Piano-Roll & Song-Matrix öffnen', () => { const p = S ? S.pads.findIndex((d) => d.has) : -1; openRoll(Math.max(0, p), true); }]
  ], 'Version ' + VERSION + (A.micOk ? '' : ' · Mikrofon nicht verbunden'));
}
function confirmSheet(title, text, yes) {
  sheet(title, (sh) => {
    sh.append(el('div', 'note', text));
    const b = el('button', 'btn pri', 'Ja'); b.onclick = () => { closeSheet(); yes(); };
    sh.append(b);
  });
}
function showDesigns() {
  menu('Design wählen', DESIGNS.map(([k, n]) => [(UI.design === k ? '● ' : '') + n, () => { UI.design = k; prefs.set('design', k); document.body.dataset.design = k; R.knobs.forEach((o) => (o.last = '')); }]));
}
async function showDevices() {
  let devs = [];
  try { devs = await navigator.mediaDevices.enumerateDevices(); } catch (e) { /* */ }
  const ins = devs.filter((d) => d.kind === 'audioinput'), outs = devs.filter((d) => d.kind === 'audiooutput');
  sheet('Audio-Geräte', (sh) => {
    sh.append(el('div', 'note', 'USB-Interfaces und Kopfhörer erscheinen hier, sobald der Browser sie kennt. Die Ausgabe-Wahl braucht einen Browser, der setSinkId unterstützt (z. B. Chrome).'));
    sh.append(el('div', 'lab', '🎙 Eingang'));
    const mk = (d, input) => { const b = el('button', 'item', (d.label || (input ? 'Eingang' : 'Ausgang')) + ((input ? prefs.get('inDev', null) : prefs.get('outDev', null)) === d.deviceId ? '  ✓' : '')); b.onclick = () => { closeSheet(); if (input) { prefs.set('inDev', d.deviceId); connectMic(d.deviceId); } else { prefs.set('outDev', d.deviceId); if (A.ctx.setSinkId) A.ctx.setSinkId(d.deviceId).then(() => toast('Ausgabe: ' + (d.label || 'Gerät'))).catch(() => toast('Ausgabe konnte nicht umgestellt werden')); else toast('Dieser Browser kann die Ausgabe nicht umstellen'); } }; return b; };
    if (!ins.length) sh.append(el('div', 'note', 'Kein Eingang verfügbar (Mikrofon nicht erlaubt?).'));
    ins.forEach((d) => sh.append(mk(d, true)));
    sh.append(el('div', 'lab', '🔊 Ausgang'));
    outs.forEach((d) => sh.append(mk(d, false)));
    sh.append(el('div', 'lab', 'Latenz-Ausgleich für Aufnahmen: ' + A.latMs + ' ms'));
    const lab = sh.lastChild;
    sh.append(slider(-100, 200, A.latMs, (v) => { A.latMs = v; prefs.set('latMs', v); lab.textContent = 'Latenz-Ausgleich für Aufnahmen: ' + v + ' ms'; sendLatency(); }));
  });
}

// ------------------------------------------------------------------ Effekte
function showFxList() {
  const s = S ? S.editSlot : 0;
  menu('Effekt für Platz ' + (s + 1), FXN.NAMES.map((n, i) => [(S && S.slotType[s] === i ? '● ' : '') + (i + 1) + '  ' + n + '  ·  X ' + FXN.X[i] + ' / Y ' + FXN.Y[i], () => { send({ t: 'selectFx', type: i }); }]).concat([['🎯 Pads wählen, auf die der XY-Effekt wirkt …', showFxRouting]]));
}
function showFxRouting() {
  sheet('XY-Effekt wirkt auf …', (sh) => {
    const g = el('div', 'grid2');
    for (let p = 0; p < PADS; p++) g.append(chk('Pad ' + (p + 1), S ? S.pads[p].fx : true, (v) => send({ t: 'padFx', p, v })));
    sh.append(g);
    const all = el('button', 'btn', 'Alle an'); all.onclick = () => { send({ t: 'allFx', v: true }); closeSheet(); };
    sh.append(all);
  });
}
function showPadFxEditor(p) {
  if (!S) return;
  const d = S.pads[p];
  sheet('✨ Effekte Pad ' + (p + 1), (sh) => {
    sh.append(chk('Effekte für Pad ' + (p + 1) + ' an', d.chain, (v) => { send({ t: 'padChain', p, v }); d.chain = v; }));
    sh.append(el('div', 'note', 'Bis zu 3 Effekte laufen nacheinander (1 › 2 › 3) nur auf diesem Pad – auch bei Noten aus der Piano-Roll. Der kleine FX-Knopf am Pad schaltet sie an/aus.'));
    const par = (UI.pfxPar = UI.pfxPar || {});
    par[p] = par[p] || [0, 1, 2].map(() => [0.5, 0.5, 1, 0.5, 0.5]);
    for (let k = 0; k < 3; k++) {
      const box = el('div', 'slot');
      const build = () => {
        box.innerHTML = '';
        box.append(el('h4', '', 'Platz ' + (k + 1)));
        const t = d.pft[k];
        const b = el('button', 'btn', (t >= 0 ? FXN.NAMES[t] : '— leer —') + ' ▾');
        b.onclick = () => {
          const list = el('div', 'grid2');
          const opts = [['— leer —', -1]].concat(FXN.NAMES.map((n, i) => [n, i]));
          for (const [n, i] of opts) { const o = el('button', 'btn', n); o.onclick = () => { send({ t: 'pfxType', p, s: k, type: i }); d.pft[k] = i; if (i >= 0) { send({ t: 'padChain', p, v: true }); d.chain = true; par[p][k][3] = 0.5; par[p][k][4] = 0.5; } build(); }; list.append(o); }
          box.innerHTML = ''; box.append(el('h4', '', 'Platz ' + (k + 1) + ' – Effekt wählen'), list);
        };
        box.append(b);
        if (t < 0) return;
        const labels = ['X · ' + FXN.X[t], 'Y · ' + FXN.Y[t], 'MIX', FXN.P1[t], FXN.P2[t]];
        for (let i = 0; i < 5; i++) {
          const lab = el('div', 'lab', labels[i] + ': ' + Math.round(par[p][k][i] * 100) + '%');
          box.append(lab, slider(0, 1000, par[p][k][i] * 1000, (v) => { par[p][k][i] = v / 1000; lab.textContent = labels[i] + ': ' + Math.round(v / 10) + '%'; send({ t: 'pfxPar', p, s: k, i, v: v / 1000 }); }));
        }
      };
      build();
      sh.append(box);
    }
  });
}

// ------------------------------------------------------------------ Tempo
function showTempo() {
  if (!S) return;
  let bpm = clamp(S.bpm > 0 ? S.bpm : 120, 60, 200);
  const taps = [];
  sheet('Master-Tempo', (sh) => {
    const big = el('div', 'big', Math.round(bpm) + ' BPM');
    const info = el('div', 'note', S.fixed > 0 ? 'Festes Tempo · 4/4' : 'Automatisch: erster Loop bestimmt das Tempo');
    const set = (v) => { bpm = clamp(v, 60, 200); send({ t: 'bpm', v: bpm }); big.textContent = Math.round(bpm) + ' BPM'; sl.value = Math.round(bpm); info.textContent = 'Festes Tempo · 4/4 – alle Loops werden mitgedehnt (Tonhöhe bleibt)'; };
    const sl = slider(60, 200, Math.round(bpm), set);
    const row = el('div', 'row');
    const b1 = el('button', 'btn', '−'), tap = el('button', 'btn pri', 'TAP'), b2 = el('button', 'btn', '+');
    b1.onclick = () => set(bpm - 1); b2.onclick = () => set(bpm + 1);
    tap.onclick = () => { const now = performance.now(); if (taps.length && now - taps[taps.length - 1] > 2000) taps.length = 0; taps.push(now); while (taps.length > 6) taps.shift(); if (taps.length >= 2) set(60000 / ((taps[taps.length - 1] - taps[0]) / (taps.length - 1))); };
    row.append(b1, tap, b2);
    sh.append(big, info, sl, row,
      chk('Metronom', S.met, (v) => send({ t: 'set', k: 'metronome', v })),
      chk('Einzähler (1 Takt vor der Pattern-Aufnahme)', S.countIn, (v) => { send({ t: 'set', k: 'countIn', v }); prefs.set('countIn', v); }));
    const auto = el('button', 'btn', 'Automatisch'); auto.onclick = () => { send({ t: 'autoTempo' }); closeSheet(); };
    sh.append(auto);
  });
}

// ------------------------------------------------------------------ Aufnahme-Menü / Song / Pattern
function showRecMenu() {
  menu('Aufnehmen', [
    [S && S.rec ? '■ Pattern-Aufnahme beenden' : '● Pattern aufnehmen (REC kurz antippen)', () => send({ t: 'rec' })],
    ['● Song aufnehmen – mit Effekten (wie du es hörst)', () => { send({ t: 'songStart', dry: false }); toast('Song-Aufnahme läuft – REC oder STOP beendet sie'); }],
    ['● Song aufnehmen – ohne Effekte (trocken)', () => { send({ t: 'songStart', dry: true }); toast('Song-Aufnahme ohne Effekte – REC oder STOP beendet sie'); }],
    ['🎧 Pattern als Audio speichern (WAV)', () => send({ t: 'renderPattern' })],
    ['💾 Projekt speichern (inkl. Szenen)', saveProjectDialog],
    [(S && S.countIn ? '☑' : '☐') + ' Einzähler: 1 Takt vor der Pattern-Aufnahme', () => { const v = !(S && S.countIn); send({ t: 'set', k: 'countIn', v }); prefs.set('countIn', v); }]
  ]);
}
let pendingSong = null;
function onSongFinished(data) {
  if (!data || !data.length) return;
  pendingSong = data;
  const secs = Math.floor(data.length / A.sr);
  menu('Song aufgenommen (' + Math.floor(secs / 60) + ':' + String(secs % 60).padStart(2, '0') + ')', [
    ['🎧 Als Audio speichern (WAV)', () => saveBlob(encodeWav(data, A.sr), 'MarvPAD_Song_' + stamp() + '.wav')],
    ['💾 Als Projekt speichern (inkl. Song)', () => saveProjectDialog(data)],
    ['🗑 Verwerfen', () => { pendingSong = null; }]
  ]);
}
function onPatternRendered(d) {
  if (!d) { toast('Das Pattern spielt keine ⚡-Samples'); return; }
  saveBlob(encodeWav(d, A.sr), 'MarvPAD_Pattern_' + stamp() + '.wav');
}
function finishPattern() {
  const any = SEQ.some((r) => r.some((x) => x > 0)) || NOTES.some((l) => l.length);
  if (!any) return;
  menu('Pattern aufgenommen', [
    ['🎧 Als Audio speichern (WAV)', () => send({ t: 'renderPattern' })],
    ['💾 Als Projekt speichern', saveProjectDialog],
    ['▶ Weiter', () => {}]
  ]);
}
function stamp() { const d = new Date(); return d.getFullYear() + String(d.getMonth() + 1).padStart(2, '0') + String(d.getDate()).padStart(2, '0') + '_' + String(d.getHours()).padStart(2, '0') + String(d.getMinutes()).padStart(2, '0'); }

// ------------------------------------------------------------------ Projekte
function saveProjectDialog(song) {
  sheet('Projekt speichern', (sh) => {
    const inp = el('input'); inp.type = 'text'; inp.value = 'Projekt ' + stamp(); inp.id = 'projname';
    inp.style.cssText = 'padding:10px;border-radius:10px;border:1px solid var(--line);background:var(--panel2);color:var(--ink);font:inherit';
    const b = el('button', 'btn pri', 'Speichern');
    b.onclick = async () => {
      const r = await request({ t: 'snapshot' });
      try {
        await dbPut('projects', { id: 'p_' + Date.now(), name: inp.value || 'Projekt', date: Date.now(), snap: r.snap, song: song instanceof Float32Array ? song : null, design: UI.design, padFxPar: UI.pfxPar || null });
        closeSheet(); toast('Projekt gespeichert');
      } catch (e) { toast('Speichern nicht möglich: ' + (e && e.message || e)); }
    };
    sh.append(inp, b);
    sh.append(el('div', 'note', 'Projekte bleiben in diesem Browser gespeichert. Für andere Geräte: im Projekt-Menü als Datei exportieren.'));
  });
}
async function showProjects() {
  let list = [];
  try { list = (await dbAll('projects')).sort((a, b) => b.date - a.date); } catch (e) { toast('Projekt-Speicher nicht verfügbar'); return; }
  const items = list.map((pr) => [(pr.song ? '🎧 ' : '📁 ') + pr.name + ' · ' + new Date(pr.date).toLocaleString('de-DE'), () => projectActions(pr)]);
  items.push(['📥 Projektdatei importieren (.marvpad)', importProjectFile]);
  menu('Projekte', items, list.length ? '' : 'Noch keine Projekte gespeichert.');
}
function projectActions(pr) {
  menu(pr.name, [
    ['▶ Laden', () => { send({ t: 'restore', snap: pr.snap }); if (pr.padFxPar) UI.pfxPar = pr.padFxPar; toast('Projekt geladen – ▶ startet es'); }],
    ...(pr.song ? [['🎧 Song als WAV speichern', () => saveBlob(encodeWav(pr.song, A.sr), pr.name + '.wav')]] : []),
    ['📤 Als Datei exportieren', () => exportProject(pr)],
    ['🗑 Löschen', () => confirmSheet('Projekt löschen?', pr.name, async () => { await dbDel('projects', pr.id); toast('Gelöscht'); })]
  ]);
}
function exportProject(pr) {
  // Typed Arrays als Base64, damit die Datei ein normales JSON ist
  const enc = (fa) => { const u = new Uint8Array(fa.buffer, fa.byteOffset, fa.byteLength); let s = ''; for (let i = 0; i < u.length; i += 32768) s += String.fromCharCode.apply(null, u.subarray(i, i + 32768)); return btoa(s); };
  const snap = JSON.parse(JSON.stringify(pr.snap, (k, v) => (v instanceof Float32Array ? { __f32: enc(v) } : v)));
  pr.snap.pads.forEach((pd, i) => { if (pd.data) snap.pads[i].data = { __f32: enc(pd.data) }; });
  saveBlob(new Blob([JSON.stringify({ marvpad: 1, name: pr.name, snap })], { type: 'application/json' }), pr.name.replace(/[^\w\- ]/g, '_') + '.marvpad');
}
async function importProjectFile() {
  const f = await pickFile('.marvpad,application/json');
  if (!f) return;
  try {
    const j = JSON.parse(await f.text());
    const dec = (o) => { const b = atob(o.__f32), u = new Uint8Array(b.length); for (let i = 0; i < b.length; i++) u[i] = b.charCodeAt(i); return new Float32Array(u.buffer); };
    const fix = (v) => (v && typeof v === 'object' ? (v.__f32 ? dec(v) : (Array.isArray(v) ? v.map(fix) : Object.fromEntries(Object.entries(v).map(([k, x]) => [k, fix(x)])))) : v);
    const snap = fix(j.snap);
    await dbPut('projects', { id: 'p_' + Date.now(), name: j.name || f.name, date: Date.now(), snap });
    send({ t: 'restore', snap });
    toast('Projekt importiert und geladen');
  } catch (e) { toast('Datei konnte nicht gelesen werden'); }
}

// ------------------------------------------------------------------ Pads: Menüs, Import, Bibliothek
function showPadMenu(p) {
  if (!S) return;
  const d = S.pads[p], wave = d.src === 1;
  const items = [
    [wave ? 'Eingang: 〰 Wave (App-Audio) → auf 🎙 Mikrofon' : 'Eingang: 🎙 Mikrofon → auf 〰 Wave (App-Audio)', () => { send({ t: 'source', p, v: wave ? 0 : 1 }); const a = S.pads.map((x) => x.src); a[p] = wave ? 0 : 1; prefs.set('padSource', a); }],
    [d.os ? 'Modus: ⚡ ONE SHOT → auf ∞ LOOP' : 'Modus: ∞ LOOP → auf ⚡ ONE SHOT', () => { if (d.has) send({ t: 'mode', p, oneShot: !d.os }); else send({ t: 'padOneShot', p, v: !d.os }); }],
    ['📚 Aus Bibliothek laden', () => showLibrary(p)],
    ['📂 Audio importieren (WAV / MP3)', () => importAudio(p)]
  ];
  if (d.has) items.push(['〰 Wave-Editor (schneiden, Slices)', () => openEditor(p)], ['🎹 Piano-Roll', () => openRoll(p)]);
  items.push(['✨ Eigene Effekte des Pads (bis zu 3) …', () => showPadFxEditor(p)]);
  items.push([d.fx ? '🎛 XY-Effekt: AN → Pad ohne XY-Effekt' : '🎛 XY-Effekt: AUS → Pad durch den XY-Effekt', () => send({ t: 'padFx', p, v: !d.fx })]);
  items.push(['🎛 XY-Effekt nur auf dieses Pad', () => { send({ t: 'allFx', v: false }); send({ t: 'padFx', p, v: true }); }]);
  if (d.has) items.push(['💾 Als WAV exportieren', async () => { const r = await request({ t: 'getPad', p }); if (r.data) saveBlob(encodeWav(r.data, A.sr), 'MarvPAD_Pad' + (p + 1) + '_' + stamp() + '.wav'); }], ['🗑 Löschen', () => send({ t: 'clear', p })]);
  menu('Pad ' + (p + 1), items);
}
function showImportMenu(p) {
  const d = S && S.pads[p];
  menu('Pad ' + (p + 1) + ' laden', [
    ['📂 Datei (WAV / MP3) – Tempo wird erkannt und gewarpt', () => importAudio(p)],
    ['📚 Bibliothek & eigene Aufnahmen', () => showLibrary(p)],
    [d && d.os ? 'Modus: ⚡ ONE SHOT → auf ∞ LOOP' : 'Modus: ∞ LOOP → auf ⚡ ONE SHOT', () => { send({ t: 'padOneShot', p, v: !(d && d.os) }); setTimeout(() => showImportMenu(p), 120); }]
  ]);
}
async function importAudio(p) {
  const f = await pickFile();
  if (!f) return;
  toast('Importiere …');
  try {
    const data = await decodeFile(await f.arrayBuffer());
    await loadInto(p, data, f.name);
  } catch (e) { toast('Import fehlgeschlagen: Format nicht lesbar'); }
}
async function loadInto(p, data, name, oneShot) {
  const os = oneShot != null ? oneShot : S && S.pads[p].os;
  if (oneShot != null) send({ t: 'padOneShot', p, v: oneShot });
  if (os) { send({ t: 'import', p, data }); toast('Pad ' + (p + 1) + ': ' + (name || 'geladen')); return; }
  const desc = await loadClip(p, data, name);
  toast('Pad ' + (p + 1) + ': ' + (name || '') + ' · ' + desc + ' · antippen startet ihn auf der Eins');
}
let LIB = null;
async function showLibrary(p) {
  if (!LIB) { try { LIB = await (await fetch('library.json')).json(); } catch (e) { toast('Bibliothek nicht erreichbar'); return; } }
  let recs = [];
  try { recs = await dbAll('recordings'); } catch (e) { /* */ }
  const items = [];
  if (recs.length) items.push(['🎙 Meine Aufnahmen (' + recs.length + ')', () => showRecordings(p)]);
  for (const c of LIB.categories) {
    const list = LIB.samples.filter((s) => s.c === c);
    items.push([c + ' (' + list.length + ')', () => menu(c, list.map((s) => [s.t, () => loadLibrary(p, s)]))]);
  }
  menu('Bibliothek → Pad ' + (p + 1), items, 'Mitgeliefert: selbst synthetisierte Sounds und die Versilian Community Sample Library (VCSL, CC0).');
}
async function loadLibrary(p, s) {
  try {
    toast('Lade ' + s.t + ' …');
    const data = await decodeFile(await (await fetch('samples/' + s.f)).arrayBuffer());
    await loadInto(p, data, s.t + ' ' + s.f, s.o);
  } catch (e) { toast('Laden fehlgeschlagen'); }
}
async function showRecordings(p) {
  let recs = [];
  try { recs = (await dbAll('recordings')).sort((a, b) => b.date - a.date); } catch (e) { toast('Speicher nicht verfügbar'); return; }
  if (!recs.length) { toast('Noch keine Aufnahmen – Mikrofon-Loops werden automatisch gesichert'); return; }
  menu('Meine Aufnahmen', recs.map((r) => ['🎙 ' + new Date(r.date).toLocaleString('de-DE') + ' · Pad ' + (r.pad + 1) + ' · ' + (r.data.length / r.rate).toFixed(1).replace('.', ',') + 's', () => {
    if (p >= 0) loadInto(p, r.data, 'Aufnahme');
    else menu('Aufnahme', [
      ['Auf ein Pad laden …', () => menu('Auf welches Pad?', [...Array(PADS).keys()].map((q) => ['Pad ' + (q + 1), () => loadInto(q, r.data, 'Aufnahme')]))],
      ['💾 Als WAV speichern', () => saveBlob(encodeWav(r.data, r.rate), 'Aufnahme_' + r.id + '.wav')],
      ['🗑 Löschen', async () => { await dbDel('recordings', r.id); toast('Gelöscht'); }]
    ]);
  }]));
}
function showSceneMenu(i) {
  if (!S) return;
  const items = [];
  if (i !== S.cur) items.push([S.ex[i] ? '▶ Szene ' + (i + 1) + ' starten (nächste Eins)' : '＋ Szene ' + (i + 1) + ' anlegen (Kopie der aktuellen)', () => send({ t: 'scene', i })]);
  items.push(['⏱ Länge in der Kette: ' + fmtBars(S.sbars[i]), () => menu('Szene ' + (i + 1) + ': Länge', [1, 2, 4, 8, 16, 32].map((b) => [fmtBars(b), () => { if (!S.ex[i]) send({ t: 'scene', i }); setTimeout(() => send({ t: 'sceneBars', i, v: b }), 60); }]))]);
  items.push([S.chainS ? '⛓ Kette (Song-Modus): AN → ausschalten' : '⛓ Kette (Song-Modus): AUS → einschalten', () => send({ t: 'set', k: 'sceneChain', v: !S.chainS })]);
  if (S.ex[i]) items.push([i === S.cur ? '🗑 Szene leeren (Pattern & Instrumente)' : '🗑 Szene löschen', () => send({ t: 'sceneDelete', i })]);
  menu('Szene ' + (i + 1) + (i === S.cur ? ' (läuft)' : ''), items);
}

// ------------------------------------------------------------------ Piano-Roll (Klaviatur links, Matrix rechts) & Song-Matrix
const RL = { oct: 0, take: false, rows: 24, page: 0, held: new Map(), paint: null, noteId: 1000 };
let rollBox = null;
function openRoll(p, song) {
  UI.kbPad = p; UI.songView = !!song;
  if (rollBox) rollBox.remove();
  rollBox = el('div', 'full');
  const box = el('div', 'box'); const c = el('canvas'); box.append(c); rollBox.append(box);
  rollBox.addEventListener('pointerdown', (e) => { if (e.target === rollBox) closeRoll(); });
  document.body.append(rollBox);
  RL.c = c; RL.box = box;
  const fit = () => { const r = c.getBoundingClientRect(), dpr = Math.min(2, devicePixelRatio || 1); c.width = r.width * dpr; c.height = r.height * dpr; c._dpr = dpr; };
  fit(); RL.fit = fit;
  box.addEventListener('pointerdown', rollDown);
  box.addEventListener('pointermove', rollMove);
  box.addEventListener('pointerup', rollUp); box.addEventListener('pointercancel', rollUp);
}
function closeRoll() {
  for (const id of RL.held.keys()) send({ t: 'noteOff', id });
  RL.held.clear();
  if (rollBox) rollBox.remove();
  rollBox = null; UI.kbPad = -1;
}
function rollLayout() {
  const r = RL.c.getBoundingClientRect(), W = r.width, H = r.height, L = {};
  L.W = W; L.H = H;
  const hh = 34;
  L.close = rect(W - 8 - hh, 8, hh, hh); L.up = rect(L.close.x - 6 - hh, 8, hh, hh); L.down = rect(L.up.x - 4 - hh, 8, hh, hh);
  L.view = rect(L.down.x - 8 - hh * 2.4, 8, hh * 2.4, hh);
  const toolY = 8 + hh + 8, n = 4, gap = 6, bw = (W - 16 - (n - 1) * gap) / n;
  L.tools = [0, 1, 2, 3].map((i) => rect(8 + i * (bw + gap), toolY, bw, 30));
  const bh = 36, by = H - 8 - bh, tb = 44;
  L.take = rect(8, by, Math.min(170, W * 0.42), bh);
  L.stop = rect(W - 8 - tb, by, tb, bh); L.play = rect(L.stop.x - 6 - tb, by, tb, bh); L.rec = rect(L.play.x - 6 - tb, by, tb, bh);
  const top = toolY + 38, mh = by - 8 - top, keyW = Math.max(52, (W - 16) * 0.17);
  L.keys = rect(8, top, keyW, mh); L.grid = rect(8 + keyW + 3, top, W - 16 - keyW - 3, mh);
  RL.rows = clamp(Math.floor(mh / 15), 12, 36);
  return L;
}
function topSemi() { return -12 + RL.oct * 12 + RL.rows - 1; }
const SONG_COLS = 16;
function rollDown(e) {
  const r = RL.c.getBoundingClientRect(), x = e.clientX - r.left, y = e.clientY - r.top, L = rollLayout(), p = UI.kbPad;
  RL.box.setPointerCapture(e.pointerId);
  haptic();
  if (L.close.c(x, y)) { closeRoll(); return; }
  if (L.view.c(x, y)) { UI.songView = !UI.songView; return; }
  if (L.down.c(x, y)) { if (UI.songView) RL.page = Math.max(0, RL.page - 1); else RL.oct = Math.max(-3, RL.oct - 1); return; }
  if (L.up.c(x, y)) { if (UI.songView) RL.page = Math.min(Math.ceil((S ? S.songBars : 16) / SONG_COLS) - 1, RL.page + 1); else RL.oct = Math.min(3, RL.oct + 1); return; }
  if (L.rec.c(x, y)) { recPressed(); return; }
  if (L.play.c(x, y)) { send({ t: 'play' }); return; }
  if (L.stop.c(x, y)) { stopPressed(); return; }
  if (UI.songView) {
    if (L.take.c(x, y)) { send({ t: 'set', k: 'songMode', v: !S.songMode }); toast(!S.songMode ? 'Song-Modus an: ▶ spielt den Song ab Takt 1 – die Matrix schaltet die Spuren' : 'Song-Modus aus'); return; }
    const t = L.tools.findIndex((q) => q.c(x, y));
    if (t === 0) { const o = [4, 8, 16, 32, 64]; send({ t: 'set', k: 'songBars', v: o[(o.indexOf(S.songBars) + 1) % o.length] }); RL.page = 0; return; }
    if (t === 1) { const b = S.songMode && S.play ? S.arrBar : RL.page * SONG_COLS; send({ t: 'arrCapture', b }); toast('Takt ' + (b + 1) + ': aktuelle Spuren übernommen'); return; }
    if (t === 2) { send({ t: 'arrScenes' }); toast('Song aus den Szenen gebaut'); return; }
    if (t === 3) { send({ t: 'arrClear' }); return; }
    if (L.keys.c(x, y)) {
      const row = clamp(Math.floor((y - L.keys.y) / (L.keys.h / 14)), 0, 13);
      if (row < PADS) { if (S.pads[row].has) send({ t: 'mute', p: row }); }
      else if (INST) { if (row === 12) INST.acidOn = !INST.acidOn; else INST.drumOn = !INST.drumOn; sendInst(); }
      return;
    }
    const cell = songCell(L, x, y);
    if (cell && ARR) { const v = !ARR[cell[0]][cell[1]]; ARR[cell[0]][cell[1]] = v; send({ t: 'arrCell', r: cell[0], b: cell[1], v }); RL.paint = { id: e.pointerId, v }; }
    return;
  }
  const t = L.tools.findIndex((q) => q.c(x, y));
  if (t === 0) { const nb = { 1: 2, 2: 4, 4: 8 }[S.rollBars[p]] || 1; send({ t: 'rollBars', p, v: nb }); return; }
  if (t === 1) { const steps = [0, 4, 8, 16, 32], i = Math.max(0, steps.indexOf(S.rollQuant)); send({ t: 'rollQuant', v: steps[(i + 1) % steps.length] }); return; }
  if (t === 2) { send({ t: 'quantize', p, q: S.rollQuant > 0 ? S.rollQuant : 16 }); toast('Quantisiert'); return; }
  if (t === 3) { send({ t: 'clearNotes', p }); return; }
  if (L.take.c(x, y)) { RL.take = !RL.take; if (RL.take && RL.last != null) send({ t: 'pitch', p, semi: RL.last }); return; }
  if (L.keys.c(x, y)) { const semi = topSemi() - clamp(Math.floor((y - L.keys.y) / (L.keys.h / RL.rows)), 0, RL.rows - 1); keyOn(e.pointerId, semi); return; }
  if (L.grid.c(x, y)) {
    const bars = S.rollBars[p], q = S.rollQuant > 0 ? S.rollQuant : 16;
    const posRaw = clamp((x - L.grid.x) / L.grid.w, 0, 0.9999) * bars, pos = Math.floor(posRaw * q) / q;
    const semi = topSemi() - clamp(Math.floor((y - L.grid.y) / (L.grid.h / RL.rows)), 0, RL.rows - 1);
    send({ t: 'toggleNote', p, posRaw, pos, semi, len: 1 / q });
    send({ t: 'preview', p, semi });
  }
}
function keyOn(id, semi) {
  const nid = ++RL.noteId;
  RL.held.set(id, { semi, nid });
  send({ t: 'noteOn', p: UI.kbPad, semi, id: nid });
  RL.last = semi;
  if (RL.take) send({ t: 'pitch', p: UI.kbPad, semi });
}
function rollMove(e) {
  const r = RL.c.getBoundingClientRect(), x = e.clientX - r.left, y = e.clientY - r.top;
  const L = rollLayout();
  if (RL.paint && RL.paint.id === e.pointerId) { const cell = songCell(L, x, y); if (cell && ARR && ARR[cell[0]][cell[1]] !== RL.paint.v) { ARR[cell[0]][cell[1]] = RL.paint.v; send({ t: 'arrCell', r: cell[0], b: cell[1], v: RL.paint.v }); } return; }
  const h = RL.held.get(e.pointerId);
  if (h && L.keys.c(x, y)) {
    const semi = topSemi() - clamp(Math.floor((y - L.keys.y) / (L.keys.h / RL.rows)), 0, RL.rows - 1);
    if (semi !== h.semi) { send({ t: 'noteOff', id: h.nid }); RL.held.delete(e.pointerId); keyOn(e.pointerId, semi); }
  }
}
function rollUp(e) {
  if (RL.paint && RL.paint.id === e.pointerId) RL.paint = null;
  const h = RL.held.get(e.pointerId);
  if (h) { send({ t: 'noteOff', id: h.nid }); RL.held.delete(e.pointerId); }
}
function songCell(L, x, y) {
  const g = L.grid;
  if (!g.c(x, y)) return null;
  const r = clamp(Math.floor((y - g.y) / (g.h / 14)), 0, 13), c = clamp(Math.floor((x - g.x) / (g.w / SONG_COLS)), 0, SONG_COLS - 1), bar = RL.page * SONG_COLS + c;
  return bar < (S ? S.songBars : 16) ? [r, bar] : null;
}
function btnC(g, r, label, on, acc, ink, panel, onCol) {
  g.fillStyle = on ? (onCol || acc) : panel; rr(g, r.x, r.y, r.w, r.h, 8); g.fill();
  g.strokeStyle = 'rgba(128,128,128,.5)'; g.lineWidth = 1.2; g.stroke();
  g.fillStyle = on ? '#fff' : ink; const fs = Math.min(12, (r.w - 8) / Math.max(2, label.length * 0.62));
  g.font = '600 ' + fs + 'px "Chakra Petch", "Segoe UI", sans-serif'; g.textAlign = 'center'; g.textBaseline = 'middle'; g.fillText(label, r.x + r.w / 2, r.y + r.h / 2 + 1); g.textBaseline = 'alphabetic';
}
function isBlack(semi) { return [1, 3, 6, 8, 10].includes(fmodJ(semi, 12)); }
function drawRoll() {
  if (!RL.c || !S) return;
  const c = RL.c, g = c.getContext('2d'), dpr = c._dpr || 1;
  if (Math.abs(c.width - c.getBoundingClientRect().width * dpr) > 2) RL.fit();
  const L = rollLayout(), p = UI.kbPad, acc = cssVar('--accent'), ink = cssVar('--ink'), panel = cssVar('--panel2');
  g.setTransform(dpr, 0, 0, dpr, 0, 0);
  g.clearRect(0, 0, L.W, L.H);
  g.fillStyle = ink; g.textAlign = 'left'; g.font = '700 14px "Chakra Petch", "Segoe UI", sans-serif';
  g.fillText(UI.songView ? '🎬 Song' : '🎹 Pad ' + (p + 1), 12, 26);
  g.font = '500 10px "Chakra Petch", "Segoe UI", sans-serif'; g.globalAlpha = 0.7;
  g.fillText(UI.songView ? 'Seite ' + (RL.page + 1) + '/' + Math.ceil(S.songBars / SONG_COLS) + ' · ' + S.songBars + ' Takte' : 'Ton ' + (S.pads[p].semi > 0 ? '+' : '') + S.pads[p].semi + ' · Okt ' + RL.oct, 12, 40);
  g.globalAlpha = 1;
  btnC(g, L.view, UI.songView ? '🎹 NOTEN' : '🎬 SONG', false, acc, ink, panel);
  btnC(g, L.down, UI.songView ? '◀' : '−', false, acc, ink, panel); btnC(g, L.up, UI.songView ? '▶' : '+', false, acc, ink, panel); btnC(g, L.close, '✕', false, acc, ink, panel);
  btnC(g, L.rec, S.song ? '● S' : S.rec ? '● P' : '●', S.rec || S.song, acc, ink, panel, '#e1283a');
  btnC(g, L.play, '▶', S.play, acc, ink, panel); btnC(g, L.stop, '■', false, acc, ink, panel);
  if (UI.songView) { drawSongGrid(g, L, acc, ink, panel); return; }
  btnC(g, L.take, RL.take ? '📌 Ton fürs Pad: AN' : '📌 Ton fürs Pad', RL.take, acc, ink, panel);
  const bars = S.rollBars[p] || 1, q = S.rollQuant;
  ['' + fmtBars(bars).toUpperCase(), '▦ ' + (q > 0 ? '1/' + q : 'frei'), 'QUANT.', 'LEEREN'].forEach((t, i) => btnC(g, L.tools[i], t, i === 1 && q > 0, acc, ink, panel));
  // Klaviatur
  const K = L.keys, G = L.grid, rows = RL.rows, rh = K.h / rows, top = topSemi();
  const held = new Set([...RL.held.values()].map((h) => h.semi));
  g.fillStyle = '#f8f6f0'; g.fillRect(K.x, K.y, K.w, K.h);
  for (let row = 0; row < rows; row++) {
    const semi = top - row, y0 = K.y + row * rh, hd = held.has(semi), n = fmodJ(semi, 12);
    if (isBlack(semi)) { g.fillStyle = hd ? acc : '#1c1c20'; g.fillRect(K.x, y0, K.w * 0.62, rh); }
    else if (hd) { g.fillStyle = acc; g.fillRect(K.x, y0, K.w, rh); }
    if (n === 0 || n === 5) { g.strokeStyle = 'rgba(0,0,0,.45)'; g.lineWidth = 1; g.beginPath(); g.moveTo(K.x, y0 + rh); g.lineTo(K.x + K.w, y0 + rh); g.stroke(); }
    if (n === 0) { g.fillStyle = hd ? '#fff' : '#505050'; g.font = '600 ' + Math.min(10, rh * 0.9) + 'px "Chakra Petch", "Segoe UI", sans-serif'; g.textAlign = 'right'; g.fillText('C' + (4 + Math.floor(semi / 12)), K.x + K.w - 4, y0 + rh * 0.8); }
    if (semi === S.pads[p].semi) { g.fillStyle = acc; g.beginPath(); g.arc(K.x + K.w - 28, y0 + rh / 2, 2.5, 0, 7); g.fill(); }
  }
  // Matrix
  g.fillStyle = '#1e1f25'; g.fillRect(G.x, G.y, G.w, G.h);
  for (let row = 0; row < rows; row++) {
    const semi = top - row, y0 = G.y + row * rh;
    if (isBlack(semi)) { g.fillStyle = '#16171c'; g.fillRect(G.x, y0, G.w, rh); }
    g.strokeStyle = fmodJ(semi, 12) === 0 ? 'rgba(255,255,255,.3)' : 'rgba(255,255,255,.08)'; g.lineWidth = fmodJ(semi, 12) === 0 ? 1 : 0.5;
    g.beginPath(); g.moveTo(G.x, y0 + rh); g.lineTo(G.x + G.w, y0 + rh); g.stroke();
  }
  const total = bars * 16, cw = G.w / total;
  for (let k = 0; k <= total; k++) {
    const a = k % 16 === 0 ? 0.6 : k % 4 === 0 ? 0.3 : q > 0 && (k * q) % 16 === 0 ? 0.18 : cw > 5 ? 0.08 : 0;
    if (!a) continue;
    g.strokeStyle = 'rgba(255,255,255,' + a + ')'; g.lineWidth = k % 16 === 0 ? 1.6 : 0.8;
    g.beginPath(); g.moveTo(G.x + k * cw, G.y); g.lineTo(G.x + k * cw, G.y + G.h); g.stroke();
  }
  g.fillStyle = 'rgba(255,255,255,.55)'; g.font = '500 8.5px "Chakra Petch", "Segoe UI", sans-serif'; g.textAlign = 'left';
  for (let b = 0; b < bars; b++) g.fillText(String(b + 1), G.x + b * 16 * cw + 2, G.y + 9);
  for (const ev of NOTES[p] || []) {
    const [pos, len, semi, vel] = ev, row = top - semi, x0 = G.x + pos / bars * G.w, x1 = Math.min(G.x + G.w, G.x + (pos + len) / bars * G.w);
    if (row < 0 || row >= rows) { g.fillStyle = acc; g.font = '600 9px "Chakra Petch", "Segoe UI", sans-serif'; g.fillText(row < 0 ? '▲' : '▼', x0 + 2, row < 0 ? G.y + 9 : G.y + G.h - 2); continue; }
    g.fillStyle = acc; g.globalAlpha = 0.35 + 0.65 * vel; rr(g, x0 + 0.5, G.y + row * rh + 0.5, Math.max(3, x1 - x0 - 1), rh - 1, 2); g.fill(); g.globalAlpha = 1;
    g.strokeStyle = '#fff'; g.lineWidth = 1; g.stroke();
  }
  const pr = rollProgress(p);
  if (pr >= 0) { g.strokeStyle = S.rec ? '#e6283a' : '#fff'; g.lineWidth = 2; g.beginPath(); g.moveTo(G.x + G.w * pr, G.y); g.lineTo(G.x + G.w * pr, G.y + G.h); g.stroke(); }
  g.strokeStyle = 'rgba(128,128,128,.5)'; g.lineWidth = 1; g.strokeRect(G.x, G.y, G.w, G.h); g.strokeRect(K.x, K.y, K.w, K.h);
  if (held.size) { g.fillStyle = acc; g.font = '700 11px "Chakra Petch", "Segoe UI", sans-serif'; g.textAlign = 'left'; g.fillText([...held].sort((a, b) => a - b).map((s) => NOTE_NAMES[fmodJ(s, 12)] + (4 + Math.floor(s / 12))).join(' '), L.take.x + L.take.w + 8, L.take.y + L.take.h / 2 + 4); }
}
function drawSongGrid(g, L, acc, ink, panel) {
  btnC(g, L.take, S.songMode ? '▶ SONG-MODUS AN' : 'SONG-MODUS AUS', S.songMode, acc, ink, panel);
  [S.songBars + ' TAKTE', '＋ LIVE', 'AUS SZENEN', 'LEEREN'].forEach((t, i) => btnC(g, L.tools[i], t, false, acc, ink, panel));
  const K = L.keys, G = L.grid, rows = 14, rh = G.h / rows, cw = G.w / SONG_COLS;
  for (let r = 0; r < rows; r++) {
    const y0 = K.y + r * rh;
    const on = r < PADS ? S.pads[r].has && !S.pads[r].mu : r === 12 ? S.acidOn : S.drumOn;
    g.fillStyle = on ? acc : panel; g.globalAlpha = on ? 0.55 : 1; rr(g, K.x, y0 + 1, K.w, rh - 2, 4); g.fill(); g.globalAlpha = 1;
    const d = S.pads[r];
    const name = r < PADS ? 'P' + (r + 1) + (d && d.has ? (d.os ? ' ⚡' : ' ∞') : '') : r === 12 ? 'ACID' : 'DRUM';
    g.fillStyle = on ? '#fff' : ink; g.font = '600 ' + Math.min(10.5, rh * 0.6) + 'px "Chakra Petch", "Segoe UI", sans-serif'; g.textAlign = 'left'; g.fillText(name, K.x + 5, y0 + rh / 2 + 4);
  }
  g.fillStyle = '#1b1c22'; g.fillRect(G.x, G.y, G.w, G.h);
  const cur = S.songMode && S.play ? S.arrBar : -1;
  for (let c = 0; c < SONG_COLS; c++) {
    const bar = RL.page * SONG_COLS + c, x0 = G.x + c * cw;
    if (bar >= S.songBars) { g.fillStyle = 'rgba(0,0,0,.55)'; g.fillRect(x0, G.y, cw, G.h); continue; }
    if (bar === cur) { g.fillStyle = 'rgba(255,255,255,.22)'; g.fillRect(x0, G.y, cw, G.h); }
    for (let r = 0; r < rows; r++) {
      if (!ARR || !ARR[r][bar]) continue;
      g.fillStyle = r >= PADS ? '#5ab4ff' : acc; rr(g, x0 + 1.5, G.y + r * rh + 1.5, cw - 3, rh - 3, 3); g.fill();
    }
    g.strokeStyle = 'rgba(255,255,255,' + (bar % 4 === 0 ? 0.45 : 0.15) + ')'; g.lineWidth = bar % 4 === 0 ? 1.2 : 0.5;
    g.beginPath(); g.moveTo(x0, G.y); g.lineTo(x0, G.y + G.h); g.stroke();
    if (bar % 4 === 0 || cw > 18) { g.fillStyle = ink; g.globalAlpha = 0.6; g.font = '500 8.5px "Chakra Petch", "Segoe UI", sans-serif'; g.textAlign = 'left'; g.fillText(String(bar + 1), x0 + 2, G.y - 2); g.globalAlpha = 1; }
    if (ARRSC && ARRSC[bar] >= 0) { g.fillStyle = '#fff'; g.font = '700 8px "Chakra Petch", "Segoe UI", sans-serif'; g.fillText('S' + (ARRSC[bar] + 1), x0 + 2, G.y + G.h - 3); }
  }
  for (let r = 0; r <= rows; r++) { g.strokeStyle = 'rgba(255,255,255,.1)'; g.lineWidth = 0.5; g.beginPath(); g.moveTo(G.x, G.y + r * rh); g.lineTo(G.x + G.w, G.y + r * rh); g.stroke(); }
  g.strokeStyle = 'rgba(128,128,128,.5)'; g.strokeRect(G.x, G.y, G.w, G.h);
}

// ------------------------------------------------------------------ Wave-Editor
const ED = { data: null, s: 0, e: 0, slices: 0, bars: 1, drag: null, peaks: null };
let edBox = null;
async function openEditor(p) {
  const r = await request({ t: 'getPad', p });
  if (!r.data) return;
  UI.edPad = p;
  Object.assign(ED, { data: r.data, s: 0, e: r.data.length, slices: S.pads[p].sl, bars: S.pads[p].bars || 1, oneShot: r.oneShot, peaks: null });
  if (!ED.slices && S.ml > 0) { const opts = [0.25, 0.5, 1, 2, 4, 8]; let best = 1, er = Infinity; for (const o of opts) { const x = Math.abs(Math.log(r.data.length / (S.ml * o))); if (x < er) { er = x; best = o; } } ED.bars = best; }
  edBox = el('div', 'full');
  const box = el('div', 'box'); const c = el('canvas'); box.append(c); edBox.append(box);
  edBox.addEventListener('pointerdown', (e) => { if (e.target === edBox) closeEditor(); });
  document.body.append(edBox);
  ED.c = c; ED.box = box;
  const fit = () => { const rr2 = c.getBoundingClientRect(), dpr = Math.min(2, devicePixelRatio || 1); c.width = rr2.width * dpr; c.height = rr2.height * dpr; c._dpr = dpr; ED.peaks = null; };
  fit(); ED.fit = fit;
  box.addEventListener('pointerdown', edDown); box.addEventListener('pointermove', edMove);
  box.addEventListener('pointerup', () => { ED.drag = null; }); box.addEventListener('pointercancel', () => { ED.drag = null; });
}
function closeEditor() { if (edBox) edBox.remove(); edBox = null; UI.edPad = -1; }
function edLayout() {
  const r = ED.c.getBoundingClientRect(), W = r.width, H = r.height, L = { W, H };
  L.close = rect(W - 46, 8, 38, 38);
  L.wave = rect(10, 56, W - 20, Math.max(80, H * 0.36));
  const ky = L.wave.y + L.wave.h + 34, kw = (W - 30) / 2;
  L.kSl = rect(10, ky, kw, 70); L.kBars = rect(20 + kw, ky, kw, 70);
  const by = ky + 82, bw = (W - 50) / 4;
  L.btns = [0, 1, 2, 3].map((i) => rect(10 + i * (bw + 10), by, bw, 40));
  return L;
}
function edDown(e) {
  const r = ED.c.getBoundingClientRect(), x = e.clientX - r.left, y = e.clientY - r.top, L = edLayout(), p = UI.edPad;
  ED.box.setPointerCapture(e.pointerId);
  if (L.close.c(x, y)) { closeEditor(); return; }
  if (L.wave.c(x, y) || (y > L.wave.y + L.wave.h && y < L.wave.y + L.wave.h + 30)) {
    const f = Math.round((x - L.wave.x) / L.wave.w * ED.data.length);
    ED.drag = Math.abs(f - ED.s) < Math.abs(f - ED.e) ? 's' : 'e';
    edMove(e); return;
  }
  if (L.kSl.c(x, y)) { ED.drag = { k: 'sl', y: e.clientY, v: ED.slices }; return; }
  if (L.kBars.c(x, y)) { ED.drag = { k: 'bars', y: e.clientY, v: ED.bars }; return; }
  const b = L.btns.findIndex((q) => q.c(x, y));
  if (b === 0) send({ t: 'previewData', data: ED.data.slice(ED.s, ED.e) });
  if (b === 1) { send({ t: 'edit', p, start: ED.s, end: ED.e, slices: 0, bars: ED.bars }); toast('Zugeschnitten'); setTimeout(() => { closeEditor(); openEditor(p); }, 200); }
  if (b === 2) { if (ED.slices < 2) { toast('Erst SLICES einstellen'); return; } send({ t: 'edit', p, start: ED.s, end: ED.e, slices: ED.slices, bars: ED.bars }); toast(ED.slices + ' Slices · folgen dem Tempo'); }
  if (b === 3) { send({ t: 'distribute', p, start: ED.s, end: ED.e, n: Math.max(2, ED.slices || 4) }); }
}
function edMove(e) {
  if (!ED.drag) return;
  const r = ED.c.getBoundingClientRect(), x = e.clientX - r.left, L = edLayout();
  if (ED.drag === 's' || ED.drag === 'e') {
    const f = clamp(Math.round((x - L.wave.x) / L.wave.w * ED.data.length), 0, ED.data.length);
    if (ED.drag === 's') ED.s = Math.min(f, ED.e - 64); else ED.e = Math.max(f, ED.s + 64);
    return;
  }
  const dy = (ED.drag.y - e.clientY) / 30;
  if (ED.drag.k === 'sl') { const o = [0, 2, 4, 8, 16, 32]; const i = clamp(o.indexOf(ED.drag.v) + Math.round(dy), 0, o.length - 1); ED.slices = o[i]; }
  else { const o = [0.25, 0.5, 1, 2, 4, 8]; const i = clamp(o.indexOf(ED.drag.v) + Math.round(dy), 0, o.length - 1); ED.bars = o[i]; }
}
function drawEditor() {
  if (!ED.c) return;
  const c = ED.c, g = c.getContext('2d'), dpr = c._dpr || 1, L = edLayout(), acc = cssVar('--accent'), ink = cssVar('--ink'), panel = cssVar('--panel2');
  g.setTransform(dpr, 0, 0, dpr, 0, 0); g.clearRect(0, 0, L.W, L.H);
  g.fillStyle = ink; g.font = '700 15px "Chakra Petch", "Segoe UI", sans-serif'; g.textAlign = 'left'; g.fillText('〰 Wave-Editor · Pad ' + (UI.edPad + 1), 12, 30);
  btnC(g, L.close, '✕', false, acc, ink, panel);
  const Wv = L.wave, n = ED.data.length;
  if (!ED.peaks) { const cols = Math.floor(Wv.w); ED.peaks = new Float32Array(cols); for (let i = 0; i < cols; i++) { let m = 0; const a = Math.floor(i * n / cols), b = Math.floor((i + 1) * n / cols); for (let j = a; j < b; j++) m = Math.max(m, Math.abs(ED.data[j])); ED.peaks[i] = m; } }
  g.fillStyle = '#121318'; g.fillRect(Wv.x, Wv.y, Wv.w, Wv.h);
  const sx = Wv.x + ED.s / n * Wv.w, ex = Wv.x + ED.e / n * Wv.w;
  for (let i = 0; i < ED.peaks.length; i++) {
    const x = Wv.x + i, h = ED.peaks[i] * Wv.h * 0.48, inSel = x >= sx && x <= ex;
    g.fillStyle = inSel ? acc : 'rgba(255,255,255,.25)'; g.fillRect(x, Wv.y + Wv.h / 2 - h, 1, h * 2 + 1);
  }
  if (ED.slices > 1) { g.strokeStyle = 'rgba(255,255,255,.8)'; g.lineWidth = 1; for (let k = 1; k < ED.slices; k++) { const x = sx + (ex - sx) * k / ED.slices; g.beginPath(); g.moveTo(x, Wv.y); g.lineTo(x, Wv.y + Wv.h); g.stroke(); } }
  for (const [x, lab] of [[sx, 'S'], [ex, 'E']]) { g.strokeStyle = '#fff'; g.lineWidth = 2; g.beginPath(); g.moveTo(x, Wv.y); g.lineTo(x, Wv.y + Wv.h + 8); g.stroke(); g.fillStyle = acc; g.beginPath(); g.arc(x, Wv.y + Wv.h + 16, 10, 0, 7); g.fill(); g.fillStyle = '#fff'; g.font = '700 11px "Chakra Petch", "Segoe UI", sans-serif'; g.textAlign = 'center'; g.fillText(lab, x, Wv.y + Wv.h + 20); }
  g.fillStyle = ink; g.globalAlpha = 0.7; g.font = '500 10px "JetBrains Mono", monospace'; g.textAlign = 'left';
  g.fillText(((ED.e - ED.s) / A.sr).toFixed(2).replace('.', ',') + ' s', Wv.x, Wv.y - 6); g.globalAlpha = 1;
  const knob = (r, label, val) => { btnC(g, r, '', false, acc, ink, panel); g.fillStyle = ink; g.textAlign = 'center'; g.font = '600 10px "Chakra Petch", "Segoe UI", sans-serif'; g.fillText(label + ' (↕ ziehen)', r.x + r.w / 2, r.y + 20); g.fillStyle = acc; g.font = '700 22px "JetBrains Mono", monospace'; g.fillText(val, r.x + r.w / 2, r.y + 52); };
  knob(L.kSl, 'SLICES', ED.slices > 1 ? String(ED.slices) : 'AUS');
  knob(L.kBars, 'TAKTE', String(ED.bars).replace('.', ','));
  ['▶ HÖREN', '✂ SCHNEIDEN', '▦ SLICEN', '→ PADS'].forEach((t, i) => btnC(g, L.btns[i], t, false, acc, ink, panel));
}

// ------------------------------------------------------------------ Info-Modus
const INFO = {
  logo: ['Marv PAD', 'Live-Looper mit 12 Pads, XY-Effektfläche, Effekt-Kette, Sequenzer, Piano-Roll, Song-Matrix, ACID 303 und DRUM 909. © marween'],
  lcd: ['Display', 'Zeigt Kettenplatz, Effekt, ob er aktiv ist (●), auf welche Pads er wirkt, X/Y-Werte und den Takt. Links antippen: Effektliste · rechts: Tempo, Metronom, Einzähler.'],
  knob0: ['Regler 1', 'EFFEKTE: MIX des Effekts · ACID: CUTOFF · DRUM: TUNE des gewählten Instruments. Hoch/runter ziehen.'],
  knob1: ['Regler 2', 'Erster Parameter des Effekts · ACID: RESO · DRUM: DECAY.'],
  knob2: ['Regler 3', 'Zweiter Parameter des Effekts · ACID: ENV MOD · DRUM: LEVEL.'],
  knob3: ['Regler 4', 'Gesamtlautstärke · ACID: DECAY · DRUM: DRIVE.'],
  knob4: ['Regler 5', 'SWING: verschiebt jede zweite 1/16 nach hinten · ACID: LEVEL · DRUM: VOL.'],
  func0: ['SZENE', 'Die Pads werden zu 12 Szenen (Schritte, Noten, ACID/DRUM-Pattern, Stummschaltungen). Antippen startet zur nächsten Eins, leere Szene = Kopie. Lange drücken: Kette (Song-Modus).'],
  func1: ['MUTE', 'Pads antippen = stumm/an. Halten + Pad = nur kurz. Lange drücken = alle Loops stumm/an.'],
  func2: ['SOLO', 'Nur Solo-Pads sind zu hören. Lange drücken = alle Solos aus.'],
  func3: ['ERASE', 'Pad antippen: erst sein Pattern, dann das Sample löschen. Schritte antippen: ganzes Pattern leeren. Mit SZENE: Szene löschen. Alles per ↶ UNDO zurückholbar.'],
  func4: ['DUPLICATE', 'Quell-Pad, dann Ziel-Pad antippen – kopiert Sample, Modus, Schritte, Noten und Effekte. Mit SZENE: Szenen kopieren.'],
  func5: ['NOTE REPEAT', 'Gehaltenes ⚡-Pad oder Drum-Pad wiederholt im Raster. Lange drücken wechselt die Rate.'],
  fxbtn: ['Effekt-Knopf', 'Links tippen = vorheriger, rechts = nächster Effekt für den gewählten Kettenplatz. Lange drücken = Liste aller 37 Effekte.'],
  tab0: ['🎛 EFFEKTE', 'XY-Effektfläche mit 3er-Kette, HOLD und Bewegungs-Aufnahme.'],
  tab1: ['🔊 ACID 303', 'Bass-Sequenzer mit Ton, Accent und Slide, live auf der Spielfläche spielbar.'],
  tab2: ['🥁 DRUM 909', '11 synthetisierte Drums, 16 Schritte je Instrument, Presets und Finger-Pads.'],
  stage: ['XY-Fläche / Instrument', 'Finger drauf = Effekt an (X/Y steuern ihn). Chips 1–3 = Effekt-Kette, HOLD hält ihn, ● REC speichert deine Bewegung. In ACID/DRUM: das Instrument.'],
  seq: ['Sequenzer', '16 Schritte für das zuletzt gespielte ⚡-Pad. Antippen = an/aus, hoch/runter ziehen = Lautstärke.'],
  rec: ['● REC', 'Antippen: Pattern-Aufnahme (mit Einzähler, wenn alles steht). Nochmal = beenden, alles läuft weiter. Lange drücken: Song-Aufnahme, Pattern als WAV, Einzähler.'],
  play: ['▶ Play', 'Startet alles gemeinsam auf der Eins.'],
  stop: ['■ Stop', 'Hält alles an. Lange halten leert das Pattern.'],
  undo: ['↶ UNDO', 'Macht den letzten Schritt rückgängig.'], redo: ['↷ REDO', 'Stellt einen rückgängig gemachten Schritt wieder her.'],
  pillSrc: ['Eingang aller Pads', '🎙 Mikrofon oder 〰 Wave (nimmt auf, was die App ausgibt – inkl. Effekte).'],
  pillMode: ['Aufnahme-Modus', '✋ HALTEN, 👆 TIPPEN oder ⚡ LIVE mit fester Länge. Loops rasten immer auf 1–32 Takte ein.'],
  pillQ: ['⏱ Start-Raster', 'Wann ein angetippter Loop startet/stoppt: sofort, 1/4, 1/2, 1, 2 oder 4 Takte (wie in Ableton Live).']
};
function infoFor(key) {
  if (INFO[key]) return INFO[key];
  const m = /^(pad|more|key|padfx)(\d+)$/.exec(key);
  if (!m) return null;
  const p = +m[2];
  if (m[1] === 'more') return ['••• Pad-Menü', 'Eingang, Modus, Bibliothek, Import, Wave-Editor, Piano-Roll, eigene Effekte, XY-Effekt, Export und Löschen für Pad ' + (p + 1) + '.'];
  if (m[1] === 'key') return ['🎹 Piano-Roll', 'Klaviatur links, Matrix rechts – wie in Ableton. Oben auf SONG schalten: Song-Matrix für ganze Songs.'];
  if (m[1] === 'padfx') return ['FX des Pads', 'Eigene Kette mit bis zu 3 Effekten (Punkte = belegte Plätze). Antippen = an/aus, lange drücken = Effekte wählen.'];
  return ['Pad ' + (p + 1), 'Farben: Rot = Aufnahme, Grün = spielt, Gelb = Overdub, Blinken = wartet auf das Start-Raster, Grau ▶ = bereit. Leeres Pad: halten/tippen nimmt auf, 📂 lädt eine Datei. Gesten: ← wischen = löschen, ↕ = Lautstärke, zwei Finger = stumm (lang: Solo).'];
}
let infoEl = null, infoHi = null;
function hideInfo() { if (infoEl) infoEl.remove(); infoEl = null; if (infoHi) infoHi.classList.remove('infohi'); infoHi = null; }
document.addEventListener('pointerdown', (e) => {
  if (!UI.infoMode) return;
  if (e.target.closest('#menuBtn') || e.target.closest('.scrim') || e.target.closest('.full')) return;
  if (infoEl && infoEl.contains(e.target)) { hideInfo(); e.stopPropagation(); e.preventDefault(); return; }
  const t = e.target.closest('[data-info]');
  hideInfo();
  e.stopPropagation(); e.preventDefault();
  if (!t) return;
  const inf = infoFor(t.dataset.info);
  if (!inf) return;
  infoHi = t; t.classList.add('infohi');
  infoEl = el('div'); infoEl.id = 'info';
  infoEl.innerHTML = '<h4></h4><div></div>';
  infoEl.firstChild.textContent = 'ℹ ' + inf[0]; infoEl.lastChild.textContent = inf[1];
  const r = t.getBoundingClientRect();
  infoEl.style.top = (r.top < innerHeight / 2 ? Math.min(innerHeight - 160, r.bottom + 12) : Math.max(8, r.top - 150)) + 'px';
  document.body.append(infoEl);
}, true);

// ------------------------------------------------------------------ Start
function boot() {
  buildUI();
  $('#splashVer').textContent = 'Version ' + VERSION;
  const sp = $('#splash'), go = $('#splashGo');
  let ready = false, done = false;
  const finish = async () => {
    if (done || !ready) return;
    done = true;
    try { await startAudio(); } catch (e) { toast('Audio konnte nicht gestartet werden: ' + (e && e.message || e)); }
    sp.style.opacity = 0; setTimeout(() => sp.remove(), 380);
  };
  setTimeout(() => { ready = true; go.style.opacity = 1; }, 4000);
  sp.addEventListener('click', () => { if (ready) finish(); });
  requestAnimationFrame(update);
}
boot();
