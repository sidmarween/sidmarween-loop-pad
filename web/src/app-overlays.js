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
