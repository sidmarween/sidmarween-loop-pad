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
