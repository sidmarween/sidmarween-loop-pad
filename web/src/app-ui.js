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
