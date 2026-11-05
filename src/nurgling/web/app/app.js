/* Nurgling browser UI: draws the state a headless (or desktop) client publishes, and sends
 * back what the player does. All URLs are relative so the page works both directly on a seat
 * and behind the web host at /seat/<account>/. */
'use strict';

const KEY = (new URLSearchParams(location.hash.slice(1))).get('k') || sessionStorage.getItem('nurgling.k') || '';
if (KEY) sessionStorage.setItem('nurgling.k', KEY);
const BASE = location.pathname.replace(/[^/]*$/, '');
const TILE = 11, GRID = 100, TPX = 6;

const S = {
  ws: null, ui: new Map(), gobs: new Map(), grids: new Map(), tex: new Map(),
  me: null, cam: { x: 0, y: 0 }, scale: 1.6, follow: true, pf: false,
  mods: 0, chatsel: null, botcache: null, rid: 1, pending: new Map(),
  ctx: null, ghost: null, placeang: null, ptrtype: 'mouse', foes: new Set(), actdir: '',
  dest: null, stopped: 0, beltpage: 0,
  pointer: { x: innerWidth / 2, y: innerHeight / 2 }, wz: 100,
};

const $ = (s) => document.querySelector(s);
const el = (tag, cls, txt) => { const e = document.createElement(tag); if (cls) e.className = cls; if (txt != null) e.textContent = txt; return e; };
const resurl = (n) => BASE + 'res/img?n=' + encodeURIComponent(n);

/* ------------------------------------------------------------------ connection */

let backoff = 500;
function connect() {
  const proto = location.protocol === 'https:' ? 'wss://' : 'ws://';
  const ws = new WebSocket(proto + location.host + BASE + 'ws?k=' + encodeURIComponent(KEY));
  S.ws = ws;
  $('#connmsg').textContent = KEY ? '' : 'No access key in the address. Open this page from the web host portal.';
  ws.onopen = () => { backoff = 500; $('#conn').classList.add('hidden'); };
  ws.onmessage = (ev) => { let m; try { m = JSON.parse(ev.data); } catch (e) { return; } handle(m); };
  ws.onclose = () => {
    if (loggingout) return;
    $('#conn').classList.remove('hidden');
    $('#connmsg').textContent = 'Connection lost, retrying…';
    setTimeout(connect, backoff); backoff = Math.min(backoff * 2, 8000);
  };
}
function send(o) { if (S.ws && S.ws.readyState === 1) S.ws.send(JSON.stringify(o)); }
function op(name, args) {
  return new Promise((res) => { const rid = S.rid++; S.pending.set(rid, res); send({ t: 'op', rid, name, args: args || {} }); });
}
function takemods() { const m = S.mods; S.mods = 0; updmods(); return m; }

function handle(m) {
  switch (m.t) {
    case 'reset':
      S.ui.clear(); S.gobs.clear(); S.grids.clear(); S.me = null; S.ctx = null; S.foes = new Set();
      for (const w of wins.values()) w.root.remove(); wins.clear();
      renderhud(); break;
    case 'grid': addgrid(m); break;
    case 'griddel': for (const id of m.ids) S.grids.delete(id); break;
    case 'gobs':
      for (const g of m.up) upgob(g);
      for (const id of m.del) S.gobs.delete(id);
      break;
    case 'ui':
      for (const e of m.up) S.ui.set(e.k, e);
      for (const k of m.del) { S.ui.delete(k); dropwin(k); }
      renderui(m.up); break;
    case 'opres': { const r = S.pending.get(m.rid); if (r) { S.pending.delete(m.rid); r(m); } if (!m.ok && m.err) toast(m.err, true); break; }
    case 'toast': toast(m.text, m.err); break;
  }
}

function toast(text, err) {
  const t = el('div', 'toast' + (err ? ' err' : ''), text);
  $('#toasts').appendChild(t);
  setTimeout(() => t.remove(), 4000);
}

/* ------------------------------------------------------------------ world */

function upgob(g) {
  const now = performance.now();
  let o = S.gobs.get(g.id);
  if (!o) { o = { dx: g.x, dy: g.y }; S.gobs.set(g.id, o); }
  else if (Math.hypot(o.dx - g.x, o.dy - g.y) > 60) { o.dx = g.x; o.dy = g.y; }
  o.g = g; o.t = now;
  if (g.me) S.me = g.id;
}

function addgrid(m) {
  const tb = Uint8Array.from(atob(m.tiles), (c) => c.charCodeAt(0));
  const zb = Uint8Array.from(atob(m.z), (c) => c.charCodeAt(0));
  const cl = m.cliff ? Uint8Array.from(atob(m.cliff), (c) => c.charCodeAt(0)) : null;
  const gr = {
    id: m.id, ux: m.ux, uy: m.uy, sets: m.sets, cliff: cl,
    tiles: new Uint16Array(tb.buffer), z: new Float32Array(zb.buffer), canvas: null, dirty: true,
  };
  for (const name of Object.values(m.sets)) loadtex(name);
  S.grids.set(m.id, gr);
}

function loadtex(name) {
  if (S.tex.has(name)) return;
  const t = { ok: false, data: null, w: 0, h: 0 };
  S.tex.set(name, t);
  const img = new Image();
  img.onload = () => {
    const c = document.createElement('canvas'); c.width = img.width; c.height = img.height;
    const x = c.getContext('2d'); x.drawImage(img, 0, 0);
    t.data = x.getImageData(0, 0, img.width, img.height).data; t.w = img.width; t.h = img.height; t.ok = true;
    for (const g of S.grids.values()) if (Object.values(g.sets).includes(name)) g.dirty = true;
  };
  img.onerror = () => { t.ok = true; };
  img.src = resurl(name);
}

function rendergrid(gr) {
  const W = GRID * TPX;
  if (!gr.canvas) { gr.canvas = document.createElement('canvas'); gr.canvas.width = W; gr.canvas.height = W; }
  const ctx = gr.canvas.getContext('2d');
  const out = ctx.createImageData(W, W), d = out.data;
  for (let ty = 0; ty < GRID; ty++) {
    for (let tx = 0; tx < GRID; tx++) {
      const i = ty * GRID + tx, t = gr.tiles[i];
      const tex = S.tex.get(gr.sets[t]);
      let r = 90, g = 90, b = 90;
      if (tex && tex.data) {
        const wx = ((gr.ux + tx) % tex.w + tex.w) % tex.w, wy = ((gr.uy + ty) % tex.h + tex.h) % tex.h;
        const p = (wy * tex.w + wx) * 4; r = tex.data[p]; g = tex.data[p + 1]; b = tex.data[p + 2];
      }
      /* Hillshade from the height field: light from the north-west. */
      const zl = gr.z[tx > 0 ? i - 1 : i], zr = gr.z[tx < GRID - 1 ? i + 1 : i];
      const zu = gr.z[ty > 0 ? i - GRID : i], zd = gr.z[ty < GRID - 1 ? i + GRID : i];
      const sh = Math.max(0.6, Math.min(1.35, 1 + ((zl - zr) + (zu - zd)) * 0.025));
      const tl = tx > 0 && gr.tiles[i - 1] !== t, tu = ty > 0 && gr.tiles[i - GRID] !== t;
      /* Cliffs, as the minimap shows them: the tile black, its neighbours a shade darker. */
      const iscl = (j) => gr.cliff && (gr.cliff[j >> 3] & (1 << (j & 7)));
      if (gr.cliff) {
        if (iscl(i)) { r = 28; g = 22; b = 18; }
        else if ((tx > 0 && iscl(i - 1)) || (tx < GRID - 1 && iscl(i + 1)) || (ty > 0 && iscl(i - GRID)) || (ty < GRID - 1 && iscl(i + GRID))) { r *= 0.8; g *= 0.8; b *= 0.8; }
      }
      for (let py = 0; py < TPX; py++) {
        for (let px = 0; px < TPX; px++) {
          let f = sh;
          if ((tl && px === 0) || (tu && py === 0)) f *= 0.72;
          const o = ((ty * TPX + py) * W + tx * TPX + px) * 4;
          d[o] = r * f; d[o + 1] = g * f; d[o + 2] = b * f; d[o + 3] = 255;
        }
      }
    }
  }
  ctx.putImageData(out, 0, 0);
  gr.dirty = false;
}

const icons = new Map();
function icon(name) {
  let i = icons.get(name);
  if (!i) { i = new Image(); i.src = resurl(name); icons.set(name, i); }
  return i.complete && i.naturalWidth ? i : null;
}

function gobstyle(r, me) {
  if (r.startsWith('gfx/borka')) return me ? ['#ffd34d', '#000'] : ['#f5f5f5', '#000'];
  if (r.startsWith('gfx/kritter')) return ['rgba(224,72,72,.85)', '#300'];
  if (r.includes('/trees/')) return ['rgba(30,90,30,.75)', '#0b240b'];
  if (r.includes('/bushes/')) return ['rgba(60,130,50,.7)', '#123'];
  if (r.startsWith('gfx/terobjs/items')) return ['rgba(230,220,180,.9)', '#222'];
  if (r.startsWith('gfx/terobjs/arch') || r.includes('palisade') || r.includes('wall')) return ['rgba(120,100,80,.9)', '#211'];
  if (r.startsWith('gfx/terobjs')) return ['rgba(150,110,70,.85)', '#211'];
  return ['rgba(160,160,160,.7)', '#222'];
}

const cv = $('#map'), cx = cv.getContext('2d');
/* The canvas matches the visible viewport exactly: CSS 100vh on phones includes the area under the
 * browser's address bar, which stretched the map vertically. */
function resize() {
  const dpr = Math.min(devicePixelRatio || 1, 2);
  cv.style.width = innerWidth + 'px'; cv.style.height = innerHeight + 'px';
  cv.width = Math.round(innerWidth * dpr); cv.height = Math.round(innerHeight * dpr);
  cx.setTransform(dpr, 0, 0, dpr, 0, 0);
  document.body.classList.toggle('is-phone', innerWidth <= 700);
}
addEventListener('resize', resize);
if (window.visualViewport) visualViewport.addEventListener('resize', resize);
resize();
const isphone = () => document.body.classList.contains('is-phone');
/* Things phones hide until switched on under More; remembered per device. */
for (const f of ['belts', 'beltinv']) if (localStorage.getItem('nurgling.show.' + f) === 'on') document.body.classList.add('show-' + f);

const w2s = (x, y) => [(x - S.cam.x) * S.scale + innerWidth / 2, (y - S.cam.y) * S.scale + innerHeight / 2];
const s2w = (x, y) => [(x - innerWidth / 2) / S.scale + S.cam.x, (y - innerHeight / 2) / S.scale + S.cam.y];

let lastframe = performance.now(), sel = null, tapmark = null;
function frame(now) {
  const dt = Math.min(0.1, (now - lastframe) / 1000); lastframe = now;
  for (const o of S.gobs.values()) {
    const g = o.g, age = Math.min(0.6, (now - o.t) / 1000);
    const px = g.x + (g.vx || 0) * age, py = g.y + (g.vy || 0) * age;
    const k = Math.min(1, dt * 12);
    o.dx += (px - o.dx) * k; o.dy += (py - o.dy) * k;
  }
  const me = S.me != null ? S.gobs.get(S.me) : null;
  if (S.follow && me) { S.cam.x = me.dx; S.cam.y = me.dy; }

  cx.fillStyle = '#0b0b0b'; cx.fillRect(0, 0, innerWidth, innerHeight);
  cx.imageSmoothingEnabled = false;
  drawexplored(cx, S.scale, innerWidth, innerHeight, w2s);
  for (const gr of S.grids.values()) {
    const [sx, sy] = w2s(gr.ux * TILE, gr.uy * TILE), size = GRID * TILE * S.scale;
    if (sx > innerWidth || sy > innerHeight || sx + size < 0 || sy + size < 0) continue;
    if (gr.dirty) rendergrid(gr);
    /* Whole pixels and one pixel of overlap, so no seam shows between grids. */
    const x0 = Math.floor(sx), y0 = Math.floor(sy);
    cx.drawImage(gr.canvas, x0, y0, Math.ceil(sx + size) - x0 + 1, Math.ceil(sy + size) - y0 + 1);
  }
  /* Aggro rings under everything else: red for dangerous animals, amber for the rest. */
  for (const o of S.gobs.values()) {
    const g = o.g;
    if (!g.ar) continue;
    const [x, y] = w2s(o.dx, o.dy), r = g.ar * S.scale;
    if (x + r < 0 || y + r < 0 || x - r > innerWidth || y - r > innerHeight) continue;
    cx.beginPath(); cx.arc(x, y, r, 0, Math.PI * 2);
    cx.fillStyle = g.ad ? 'rgba(224,60,60,.13)' : 'rgba(240,178,90,.10)'; cx.fill();
    cx.strokeStyle = g.ad ? 'rgba(235,70,70,.85)' : 'rgba(240,178,90,.75)'; cx.lineWidth = 1.5; cx.stroke();
  }
  /* Objects: footprints first (biggest under), then icons and characters. */
  const list = [...S.gobs.values()];
  list.sort((a, b) => (a.g.hb ? 0 : 1) - (b.g.hb ? 0 : 1));
  for (const o of list) {
    const g = o.g, offx = o.dx - g.x, offy = o.dy - g.y;
    const [fill, stroke] = gobstyle(g.r, g.me);
    const isch = g.r.startsWith('gfx/borka') || g.r.startsWith('gfx/kritter');
    if (g.hb && !isch) {
      cx.beginPath();
      for (let i = 0; i < 8; i += 2) { const [x, y] = w2s(g.hb[i] + offx, g.hb[i + 1] + offy); i ? cx.lineTo(x, y) : cx.moveTo(x, y); }
      cx.closePath(); cx.fillStyle = fill; cx.fill(); cx.strokeStyle = stroke; cx.lineWidth = 1; cx.stroke();
    }
    const [x, y] = w2s(o.dx, o.dy);
    if (x < -40 || y < -40 || x > innerWidth + 40 || y > innerHeight + 40) continue;
    /* Characters always get the marker with its facing arrow; their map icon would hide it. */
    const isplayer = g.r.startsWith('gfx/borka');
    const ic = (g.ic && !isplayer) ? icon(g.ic) : null;
    if (ic) {
      const s = Math.max(18, Math.min(32, 2.2 * S.scale * 6));
      cx.imageSmoothingEnabled = true; cx.drawImage(ic, x - s / 2, y - s / 2, s, s); cx.imageSmoothingEnabled = false;
    } else if (isch || !g.hb) {
      const r = isch ? (g.me ? 7 : 6) : 3;
      cx.beginPath(); cx.arc(x, y, r, 0, Math.PI * 2); cx.fillStyle = fill; cx.fill();
      cx.strokeStyle = stroke; cx.lineWidth = 1.5; cx.stroke();
      if (isch) drawfacing(x, y, r, facing(g), stroke);
    }
  }
  drawwalk(now);
  for (const id of S.foes) {
    const o = S.gobs.get(id);
    if (!o) continue;
    const [x, y] = w2s(o.dx, o.dy);
    cx.strokeStyle = '#e05050'; cx.lineWidth = 2.5; cx.beginPath(); cx.arc(x, y, 11, 0, Math.PI * 2); cx.stroke();
  }
  /* Entrances: door markers on houses, a ring on cellars, stairs, ladders and mine holes. */
  for (const o of S.gobs.values()) {
    const g = o.g, offx = o.dx - g.x, offy = o.dy - g.y;
    if (g.doors) for (const d of g.doors) drawdoor(...w2s(d[0] + offx, d[1] + offy));
    if (g.portal) { const [x, y] = w2s(o.dx, o.dy); drawdoor(x, y); }
  }
  drawghost();
  if (sel) {
    const [x, y] = w2s(sel.dx, sel.dy);
    cx.strokeStyle = '#f0b25a'; cx.lineWidth = 2; cx.beginPath(); cx.arc(x, y, 12, 0, Math.PI * 2); cx.stroke();
    cx.fillStyle = '#fff'; cx.font = '12px system-ui'; cx.textAlign = 'center';
    cx.fillText(prettyres(sel.g.r), x, y - 16);
  }
  if (tapmark && now - tapmark.t < 500) {
    const [x, y] = w2s(tapmark.x, tapmark.y), a = 1 - (now - tapmark.t) / 500;
    cx.strokeStyle = 'rgba(92,204,255,' + a + ')'; cx.lineWidth = 2; cx.beginPath(); cx.arc(x, y, 6 + 10 * (1 - a), 0, Math.PI * 2); cx.stroke();
  }
  if (areadrag) {
    const [ax, ay] = w2s(areadrag.a[0], areadrag.a[1]), [bx, by] = w2s(areadrag.b[0], areadrag.b[1]);
    cx.fillStyle = 'rgba(240,178,90,.2)'; cx.strokeStyle = '#f0b25a'; cx.lineWidth = 2;
    cx.fillRect(Math.min(ax, bx), Math.min(ay, by), Math.abs(bx - ax), Math.abs(by - ay));
    cx.strokeRect(Math.min(ax, bx), Math.min(ay, by), Math.abs(bx - ax), Math.abs(by - ay));
  }
  drawminimap();
  requestAnimationFrame(frame);
}
requestAnimationFrame(frame);

/* ---- the explored map beyond the loaded grids, from the client's map store ---- */
const mtiles = new Map();
let mtinflight = 0;
function mtile(seg, l, x, y) {
  const key = seg + '/' + l + '/' + x + '/' + y;
  let t = mtiles.get(key);
  const now = performance.now();
  if (t) { t.used = now; if (t.img || (t.missing && now - t.at < 60000) || t.loading) return t.img || null; }
  if (mtinflight >= 6) return null;
  t = { used: now, at: now, loading: true, img: null, missing: false };
  mtiles.set(key, t);
  mtinflight++;
  const im = new Image();
  im.onload = () => { mtinflight--; t.loading = false; t.img = im; };
  im.onerror = () => { mtinflight--; t.loading = false; t.missing = true; t.at = performance.now(); };
  im.src = BASE + 'map/grid?s=' + seg + '&l=' + l + '&x=' + x + '&y=' + y;
  if (mtiles.size > 900) {
    const old = [...mtiles.entries()].sort((a, b) => a[1].used - b[1].used).slice(0, 200);
    for (const [k] of old) mtiles.delete(k);
  }
  return null;
}
/* Draws explored grids under the live ones, at a level of detail that keeps each image >= ~90 px. */
function drawexplored(c, scale, W, H, tos) {
  const sg = S.ui.get('seg');
  if (!sg) return;
  const gpx = GRID * TILE * scale;
  const l = Math.max(0, Math.min(6, Math.ceil(Math.log2(90 / gpx))));
  const step = 1 << l;
  const [wx0, wy0] = (tos === w2s) ? s2w(0, 0) : [S.cam.x - (W / 2) / scale, S.cam.y - (H / 2) / scale];
  const [wx1, wy1] = (tos === w2s) ? s2w(W, H) : [S.cam.x + (W / 2) / scale, S.cam.y + (H / 2) / scale];
  /* session world -> segment grid: tiles + offset, then / 100 */
  const g0x = Math.floor((Math.floor(wx0 / TILE) + sg.ox) / GRID / step) * step, g0y = Math.floor((Math.floor(wy0 / TILE) + sg.oy) / GRID / step) * step;
  const g1x = Math.floor((Math.floor(wx1 / TILE) + sg.ox) / GRID), g1y = Math.floor((Math.floor(wy1 / TILE) + sg.oy) / GRID);
  c.imageSmoothingEnabled = l > 0;
  for (let gy = g0y; gy <= g1y; gy += step) {
    for (let gx = g0x; gx <= g1x; gx += step) {
      const img = mtile(sg.id, l, gx, gy);
      if (!img) continue;
      const [sx, sy] = tos((gx * GRID - sg.ox) * TILE, (gy * GRID - sg.oy) * TILE);
      const size = step * GRID * TILE * scale;
      const x0 = Math.floor(sx), y0 = Math.floor(sy);
      c.drawImage(img, x0, y0, Math.ceil(sx + size) - x0 + 1, Math.ceil(sy + size) - y0 + 1);
    }
  }
  c.imageSmoothingEnabled = false;
}

/* ---- minimap: every loaded grid, small, around the view's centre ---- */
const mm = $('#minimap'), mmx = mm.getContext('2d');
const MMS = 0.09; /* minimap pixels per world unit */
let mmon = localStorage.getItem('nurgling.mm') ? localStorage.getItem('nurgling.mm') !== 'off' : !isphone();
function mmtoggle() { mmon = !mmon; localStorage.setItem('nurgling.mm', mmon ? 'on' : 'off'); $('#mmwrap').classList.toggle('off', !mmon); updshows(); }
$('#mmwrap').classList.toggle('off', !mmon);
$('#mmtoggle').onclick = mmtoggle;
function mm2w(px, py) { const r = mm.getBoundingClientRect(), k = mm.width / r.width; return [(px * k - mm.width / 2) / MMS + S.cam.x, (py * k - mm.height / 2) / MMS + S.cam.y]; }
function drawminimap() {
  if (!mmon) return;
  const W = mm.width, H = mm.height;
  mmx.fillStyle = '#000'; mmx.fillRect(0, 0, W, H);
  mmx.imageSmoothingEnabled = true;
  const m2 = (x, y) => [(x - S.cam.x) * MMS + W / 2, (y - S.cam.y) * MMS + H / 2];
  drawexplored(mmx, MMS, W, H, m2);
  for (const gr of S.grids.values()) {
    if (!gr.canvas) continue;
    const [x, y] = m2(gr.ux * TILE, gr.uy * TILE), size = GRID * TILE * MMS;
    if (x > W || y > H || x + size < 0 || y + size < 0) continue;
    mmx.drawImage(gr.canvas, x, y, size, size);
  }
  for (const o of S.gobs.values()) {
    const g = o.g;
    const [x, y] = m2(o.dx, o.dy);
    if (x < 0 || y < 0 || x > W || y > H) continue;
    const ic = g.ic ? icon(g.ic) : null;
    if (ic) mmx.drawImage(ic, x - 7, y - 7, 14, 14);
    else if (g.r.startsWith('gfx/borka') && !g.me) { mmx.fillStyle = '#fff'; mmx.fillRect(x - 2, y - 2, 4, 4); }
    else if (g.ar && g.ad) { mmx.fillStyle = '#e04848'; mmx.fillRect(x - 2, y - 2, 4, 4); }
  }
  /* The main view's extent. */
  const [vx0, vy0] = s2w(0, 0), [vx1, vy1] = s2w(innerWidth, innerHeight);
  const [ax, ay] = m2(vx0, vy0), [bx, by] = m2(vx1, vy1);
  mmx.strokeStyle = 'rgba(255,255,255,.5)'; mmx.lineWidth = 1; mmx.strokeRect(ax, ay, bx - ax, by - ay);
  const me = S.me != null ? S.gobs.get(S.me) : null;
  if (me) { const [x, y] = m2(me.dx, me.dy); mmx.beginPath(); mmx.arc(x, y, 4, 0, Math.PI * 2); mmx.fillStyle = '#ffd34d'; mmx.fill(); mmx.strokeStyle = '#000'; mmx.stroke(); }
}
/* Tap the minimap to walk there; with ◎ off, it moves the view instead. */
mm.addEventListener('pointerup', (e) => {
  const r = mm.getBoundingClientRect(), [wx, wy] = mm2w(e.clientX - r.left, e.clientY - r.top);
  if (!S.follow) { S.cam.x = wx; S.cam.y = wy; return; }
  S.dest = { x: wx, y: wy };
  if (S.pf) op('walk', { x: wx, y: wy }); else send({ t: 'click', x: wx, y: wy, btn: 1, mods: 0 });
});

/* Facing: the walk direction while moving, else the object's angle. */
function facing(g) { return (g.vx || g.vy) ? Math.atan2(g.vy, g.vx) : g.a; }
function drawfacing(x, y, r, a, stroke) {
  const tip = r + 7, back = r - 1, w = 4.5;
  const c = Math.cos(a), s = Math.sin(a);
  cx.beginPath();
  cx.moveTo(x + c * tip, y + s * tip);
  cx.lineTo(x + c * back - s * w, y + s * back + c * w);
  cx.lineTo(x + c * back + s * w, y + s * back - c * w);
  cx.closePath(); cx.fillStyle = '#f0b25a'; cx.fill(); cx.strokeStyle = stroke; cx.lineWidth = 1; cx.stroke();
}

/* Where the character is walking: the server's end point when known, else where we tapped.
 * Shown while moving, as the game's path line; forgotten after a moment standing still. */
function drawwalk(now) {
  const o = S.me != null ? S.gobs.get(S.me) : null;
  if (!o) return;
  const g = o.g, moving = !!(g.vx || g.vy);
  if (!moving) { if (S.dest && now - S.stopped > 400 && S.stopped) S.dest = null; if (!S.stopped) S.stopped = now; return; }
  S.stopped = 0;
  const t = (g.tx != null) ? { x: g.tx, y: g.ty } : S.dest;
  if (!t) return;
  const [ax, ay] = w2s(o.dx, o.dy), [bx, by] = w2s(t.x, t.y);
  cx.lineCap = 'round';
  cx.strokeStyle = '#000'; cx.lineWidth = 5; cx.beginPath(); cx.moveTo(ax, ay); cx.lineTo(bx, by); cx.stroke();
  cx.strokeStyle = '#ffd34d'; cx.lineWidth = 3; cx.beginPath(); cx.moveTo(ax, ay); cx.lineTo(bx, by); cx.stroke();
  cx.lineCap = 'butt';
  cx.fillStyle = '#ffd34d'; cx.beginPath(); cx.arc(bx, by, 4, 0, Math.PI * 2); cx.fill();
}

function drawdoor(x, y) {
  if (x < -20 || y < -20 || x > innerWidth + 20 || y > innerHeight + 20) return;
  cx.fillStyle = '#5a3a1c'; cx.strokeStyle = '#f0b25a'; cx.lineWidth = 2;
  cx.beginPath(); cx.moveTo(x - 6, y + 8); cx.lineTo(x - 6, y - 4); cx.arc(x, y - 4, 6, Math.PI, 0); cx.lineTo(x + 6, y + 8); cx.closePath();
  cx.fill(); cx.stroke();
  cx.fillStyle = '#f0b25a'; cx.beginPath(); cx.arc(x + 3, y + 2, 1.3, 0, Math.PI * 2); cx.fill();
}

/* A door marker under a screen point, if any: {gob, mesh} for houses, {gob} for portals. */
function hitdoor(sx, sy) {
  let best = null, bestd = 16;
  for (const o of S.gobs.values()) {
    const g = o.g, offx = o.dx - g.x, offy = o.dy - g.y;
    if (g.doors) for (const d of g.doors) {
      const [x, y] = w2s(d[0] + offx, d[1] + offy), dd = Math.hypot(x - sx, y - sy);
      if (dd < bestd) { bestd = dd; best = { gob: g.id, mesh: d[2] }; }
    }
    if (g.portal) {
      const [x, y] = w2s(o.dx, o.dy), dd = Math.hypot(x - sx, y - sy);
      if (dd < bestd) { bestd = dd; best = { gob: g.id, x: g.x, y: g.y }; }
    }
  }
  return best;
}

function prettyres(r) {
  const b = r.split('/').pop().replace(/[-_]/g, ' ');
  return b.charAt(0).toUpperCase() + b.slice(1);
}

function hitgob(wx, wy) {
  let best = null, bestd = Infinity;
  const tol = 8 / S.scale;
  for (const o of S.gobs.values()) {
    const g = o.g;
    if (g.me) continue;
    let d = Math.hypot(o.dx - wx, o.dy - wy);
    if (g.hb && inpoly(g.hb, wx - (o.dx - g.x), wy - (o.dy - g.y))) d = Math.min(d, polyarea(g.hb) / 1000);
    else if (d > tol + 3) continue;
    if (d < bestd) { bestd = d; best = o; }
  }
  return best;
}
function inpoly(p, x, y) {
  let c = false;
  for (let i = 0, j = 6; i < 8; j = i, i += 2) {
    if (((p[i + 1] > y) !== (p[j + 1] > y)) && (x < (p[j] - p[i]) * (y - p[i + 1]) / (p[j + 1] - p[i + 1]) + p[i])) c = !c;
  }
  return c;
}
function polyarea(p) { let a = 0; for (let i = 0, j = 6; i < 8; j = i, i += 2) a += (p[j] + p[i]) * (p[j + 1] - p[i + 1]); return Math.abs(a / 2); }

/* ------------------------------------------------------------------ map input */

const ptrs = new Map();
let press = null, pinch = null, areadrag = null;
const holding = () => S.ui.has('hand');
const areamode = () => { const m = S.ui.get('mode'); return !!(m && m.areasel); };

cv.addEventListener('contextmenu', (e) => e.preventDefault());
cv.addEventListener('pointerdown', (e) => {
  cv.setPointerCapture(e.pointerId);
  S.ptrtype = e.pointerType;
  ptrs.set(e.pointerId, { x: e.clientX, y: e.clientY });
  closeflower();
  if (ptrs.size === 2) {
    if (press) clearTimeout(press.timer);
    press = null;
    const [a, b] = [...ptrs.values()];
    pinch = { d: Math.hypot(a.x - b.x, a.y - b.y), scale: S.scale, mx: (a.x + b.x) / 2, my: (a.y + b.y) / 2, cam: { ...S.cam } };
    return;
  }
  if (areamode()) { const w = s2w(e.clientX, e.clientY); areadrag = { a: w, b: w }; return; }
  const btn = e.button === 2 ? 3 : 1;
  press = { x: e.clientX, y: e.clientY, cam: { ...S.cam }, moved: false, btn, long: false };
  if (btn === 1 && e.pointerType !== 'mouse') {
    press.timer = setTimeout(() => { if (press && !press.moved) { press.long = true; mapclick(press.x, press.y, 3); } }, 450);
  }
});
cv.addEventListener('pointermove', (e) => {
  S.pointer = { x: e.clientX, y: e.clientY }; movehand();
  if (!ptrs.has(e.pointerId)) return;
  ptrs.set(e.pointerId, { x: e.clientX, y: e.clientY });
  if (pinch && ptrs.size >= 2) {
    const [a, b] = [...ptrs.values()];
    const d = Math.hypot(a.x - b.x, a.y - b.y);
    S.scale = clampscale(pinch.scale * d / pinch.d);
    const mx = (a.x + b.x) / 2, my = (a.y + b.y) / 2;
    if (!S.follow) { S.cam.x = pinch.cam.x - (mx - pinch.mx) / S.scale; S.cam.y = pinch.cam.y - (my - pinch.my) / S.scale; }
    return;
  }
  if (areadrag) { areadrag.b = s2w(e.clientX, e.clientY); return; }
  if (press) {
    const dx = e.clientX - press.x, dy = e.clientY - press.y;
    if (!press.moved && Math.hypot(dx, dy) > 8) { press.moved = true; clearTimeout(press.timer); }
    /* While following, the view stays on the character; turn ◎ off to look around. */
    if (press.moved && !S.follow) { S.cam.x = press.cam.x - dx / S.scale; S.cam.y = press.cam.y - dy / S.scale; }
  }
});
function endptr(e) {
  ptrs.delete(e.pointerId);
  if (ptrs.size < 2) pinch = null;
  if (areadrag) {
    const a = areadrag.a, b = areadrag.b; areadrag = null;
    send({ t: 'areasel', ax: Math.floor(a[0] / TILE), ay: Math.floor(a[1] / TILE), bx: Math.floor(b[0] / TILE), by: Math.floor(b[1] / TILE) });
    return;
  }
  if (!press) return;
  clearTimeout(press.timer);
  if (!press.moved && !press.long && e.type === 'pointerup') mapclick(press.x, press.y, press.btn);
  press = null;
}
cv.addEventListener('pointerup', endptr);
cv.addEventListener('pointercancel', endptr);
cv.addEventListener('wheel', (e) => {
  e.preventDefault();
  const before = s2w(e.clientX, e.clientY);
  S.scale = clampscale(S.scale * Math.pow(1.0015, -e.deltaY));
  if (!S.follow) { const after = s2w(e.clientX, e.clientY); S.cam.x += before[0] - after[0]; S.cam.y += before[1] - after[1]; }
}, { passive: false });
const clampscale = (s) => Math.max(0.03, Math.min(8, s));

function mapclick(sx, sy, btn) {
  const [wx, wy] = s2w(sx, sy);
  const g = hitgob(wx, wy);
  const mods = takemods();
  tapmark = { x: wx, y: wy, t: performance.now() };
  sel = g;
  S.flowerat = { x: sx, y: sy };
  if (placing()) {
    S.ghost = snap(wx, wy, mods);
    /* With a mouse the click places, as in the game; on touch a tap moves the ghost and "Place" confirms. */
    if (S.ptrtype === 'mouse') { if (btn === 3) cancelplace(); else doplace(mods); }
    return;
  }
  const door = (!holding() && !(mods & 2)) ? hitdoor(sx, sy) : null;
  if (door) {
    /* A tap on an entrance goes in, like right-clicking the door in the game. */
    if (door.mesh) send({ t: 'door', gob: door.gob, mesh: door.mesh });
    else send({ t: 'click', x: door.x, y: door.y, btn: 3, mods, gob: door.gob });
    return;
  }
  if (holding()) {
    if (btn === 3 || g) send({ t: 'itemact', x: wx, y: wy, mods, gob: g ? g.g.id : undefined });
    else send({ t: 'drop', x: wx, y: wy, mods });
    return;
  }
  if (btn === 3) {
    const args = g ? { gob: g.g.id } : { x: wx, y: wy };
    const click = () => send({ t: 'click', x: wx, y: wy, btn, mods, gob: g ? g.g.id : undefined });
    if (mods & 2) {
      /* Ctrl+right-click is nurgling's menu, as on the desktop; the game gets it only when nurgling has nothing. */
      op('ctx.list', args).then((r) => { if (r.ok && r.data.items.length) showctx(args, r.data.items); else click(); });
      return;
    }
    click();
    return;
  }
  if (btn === 1 && !g) S.dest = { x: wx, y: wy };
  if (btn === 1 && !g && S.pf) { op('walk', { x: wx, y: wy }); return; }
  send({ t: 'click', x: wx, y: wy, btn, mods, gob: g ? g.g.id : undefined });
}

/* ---- building placement ---- */

const placing = () => S.ui.get('place');
function snap(x, y, mods) {
  if ((mods | S.mods) & 1) return { x, y };
  return { x: Math.floor(x / TILE) * TILE + TILE / 2, y: Math.floor(y / TILE) * TILE + TILE / 2 };
}
function placeangle(gx, gy) {
  if (S.placeang != null) return S.placeang;
  const me = S.me != null ? S.gobs.get(S.me) : null;
  if (!me) return 0;
  return Math.round(Math.atan2(me.dy - gy, me.dx - gx) / (Math.PI / 2)) * (Math.PI / 2);
}
function ghostpos() {
  if (S.ptrtype === 'mouse' || !S.ghost) { const [x, y] = s2w(S.pointer.x, S.pointer.y); return snap(x, y, 0); }
  return S.ghost;
}
function doplace(mods) {
  const g = ghostpos();
  send({ t: 'place', x: g.x, y: g.y, a: placeangle(g.x, g.y), btn: 1, mods: mods || 0 });
}
function cancelplace() {
  const g = ghostpos();
  send({ t: 'place', x: g.x, y: g.y, a: 0, btn: 3, mods: 0 });
}
document.querySelectorAll('[data-place]').forEach((b) => {
  b.onclick = () => {
    const g = ghostpos(), a = placeangle(g.x, g.y);
    switch (b.dataset.place) {
      case 'rl': S.placeang = a - Math.PI / 2; break;
      case 'rr': S.placeang = a + Math.PI / 2; break;
      case 'ok': doplace(takemods()); break;
      case 'no': cancelplace(); break;
    }
  };
});
function drawghost() {
  const p = placing();
  if (!p) return;
  const g = ghostpos(), a = placeangle(g.x, g.y);
  const hb = p.hb || [-5.5, -5.5, 5.5, 5.5];
  const pts = [[hb[0], hb[1]], [hb[2], hb[1]], [hb[2], hb[3]], [hb[0], hb[3]]].map(([x, y]) =>
    w2s(g.x + x * Math.cos(a) - y * Math.sin(a), g.y + x * Math.sin(a) + y * Math.cos(a)));
  cx.beginPath(); pts.forEach(([x, y], i) => (i ? cx.lineTo(x, y) : cx.moveTo(x, y))); cx.closePath();
  cx.fillStyle = 'rgba(240,178,90,.35)'; cx.fill();
  cx.setLineDash([5, 4]); cx.strokeStyle = '#f0b25a'; cx.lineWidth = 2; cx.stroke(); cx.setLineDash([]);
}

/* ------------------------------------------------------------------ windows */

const wins = new Map();
const winpos = JSON.parse(sessionStorage.getItem('nurgling.winpos') || '{}');

function renderui(ups) {
  for (const e of ups) {
    if (e.t === 'wnd') drawwin(e);
  }
  renderhud();
}

function dropwin(k) { const w = wins.get(k); if (w) { w.root.remove(); wins.delete(k); fronts(); } }

/* Phones show one window at a time: the front one, with tabs to switch. */
function raise(w) { w.root.style.zIndex = ++S.wz; fronts(); }
function fronts() {
  let top = null;
  for (const w of wins.values()) if (!top || +w.root.style.zIndex > +top.root.style.zIndex) top = w;
  for (const w of wins.values()) w.root.classList.toggle('front', w === top);
  $('#wintabs').replaceChildren(...[...wins.values()].map((w) => {
    const b = el('button', w === top ? 'on' : '', w.title.textContent || '…'); b.onclick = () => raise(w); return b;
  }));
}
function fitwin(w) {
  if (innerWidth <= 700) return;
  const r = w.root;
  r.style.left = Math.max(0, Math.min(r.offsetLeft, innerWidth - r.offsetWidth)) + 'px';
  r.style.top = Math.max(0, Math.min(r.offsetTop, innerHeight - 60 - r.offsetHeight)) + 'px';
}
addEventListener('resize', () => { for (const w of wins.values()) fitwin(w); });

function drawwin(e) {
  let w = wins.get(e.k);
  if (!w) {
    const root = el('div', 'win'), cap = el('div', 'cap'), title = el('span'), close = el('button', null, '×'), body = el('div', 'body'), inner = el('div', 'inner');
    cap.append(title, close); body.appendChild(inner); root.append(cap, body);
    close.onclick = () => send({ t: 'wclose', k: e.k });
    root.addEventListener('pointerdown', () => { const w = wins.get(e.k); if (w) raise(w); });
    dragger(cap, root, e.k);
    $('#windows').appendChild(root);
    w = { root, title, body, inner, close, els: new Map() };
    wins.set(e.k, w);
    const p = winpos[e.k] || { x: e.x, y: e.y };
    root.style.left = p.x + 'px';
    root.style.top = p.y + 'px';
    w.fresh = true;
    raise(w);
  }
  w.title.textContent = e.cap || '';
  w.close.style.visibility = e.close ? '' : 'hidden';
  /* The body is sized from the content itself (see fitbody); an empty window keeps the game's size. */
  let bw = e.els.length ? 0 : e.w, bh = e.els.length ? 0 : e.h;
  const seen = new Set();
  for (const it of e.els) {
    seen.add(it.k);
    let n = w.els.get(it.k);
    if (!n || n.dataset.e !== it.e) { if (n) n.remove(); n = mkel(it); w.els.set(it.k, n); w.inner.appendChild(n); }
    n.style.left = (it.x - e.ox + PAD) + 'px'; n.style.top = (it.y - e.oy + PAD) + 'px';
    n.style.width = it.w + 'px'; n.style.height = it.h + 'px';
    upel(n, it);
  }
  for (const [k, n] of w.els) if (!seen.has(k)) { n.remove(); w.els.delete(k); }
  fitbody(w, bw, bh);
  if (w.fresh) { w.fresh = false; fitwin(w); }
  if (w.title.textContent !== w.lasttitle) { w.lasttitle = w.title.textContent; fronts(); }
}

/* The window fits its contents: an element whose content is wider or taller than the game's size
 * for it (browser fonts and buttons differ a little) grows to its content, and the body grows to
 * hold every element, so no window ever needs scrollbars. Lists and inventories keep their size. */
const PAD = 6; /* a window's inner margin */
function fitbody(w, bw, bh) {
  for (const n of w.els.values()) {
    const e = n.dataset.e;
    if (e === 'list' || e === 'inv' || e === 'equ') continue;
    if (n.scrollWidth > n.clientWidth + 1) n.style.width = n.scrollWidth + 'px';
    if (n.scrollHeight > n.clientHeight + 1) n.style.height = n.scrollHeight + 'px';
  }
  for (const n of w.els.values()) {
    bw = Math.max(bw, n.offsetLeft + n.offsetWidth + PAD);
    bh = Math.max(bh, n.offsetTop + n.offsetHeight + PAD);
  }
  bw = Math.max(bw, 120);
  w.inner.style.width = bw + 'px'; w.inner.style.height = bh + 'px';
  /* Phones: a window wider than the screen is scaled down to fit instead of scrolling sideways. */
  const s = isphone() ? Math.min(1, (innerWidth - 12) / bw) : 1;
  w.inner.style.transform = (s < 1) ? 'scale(' + s + ')' : '';
  w.body.style.width = Math.ceil(bw * s) + 'px'; w.body.style.height = Math.ceil(bh * s) + 'px';
}

function dragger(handle, root, k) {
  let st = null;
  handle.addEventListener('pointerdown', (e) => {
    if (e.target.tagName === 'BUTTON' || innerWidth <= 700) return;
    st = { x: e.clientX, y: e.clientY, l: root.offsetLeft, t: root.offsetTop }; handle.setPointerCapture(e.pointerId);
  });
  handle.addEventListener('pointermove', (e) => {
    if (!st) return;
    root.style.left = (st.l + e.clientX - st.x) + 'px'; root.style.top = Math.max(0, st.t + e.clientY - st.y) + 'px';
  });
  handle.addEventListener('pointerup', () => {
    if (!st) return; st = null;
    winpos[k] = { x: root.offsetLeft, y: root.offsetTop }; sessionStorage.setItem('nurgling.winpos', JSON.stringify(winpos));
  });
}

function mkel(it) {
  const n = el('div', 'el ' + it.e); n.dataset.e = it.e; n.dataset.k = it.k;
  switch (it.e) {
    case 'btn': { const b = el('button'); b.onclick = () => send({ t: 'btn', k: it.k }); n.appendChild(b); break; }
    case 'chk': {
      const c = el('input'); c.type = 'checkbox'; const l = el('span');
      c.onclick = (ev) => { ev.preventDefault(); send({ t: 'chk', k: it.k }); };
      n.append(c, l); break;
    }
    case 'text': {
      const i = el('input');
      i.addEventListener('change', () => send({ t: 'text', k: it.k, v: i.value }));
      i.addEventListener('keydown', (ev) => { if (ev.key === 'Enter') send({ t: 'text', k: it.k, v: i.value, go: true }); });
      n.appendChild(i); break;
    }
    case 'inv': n.classList.add('inv'); n.addEventListener('click', (ev) => invclick(ev, n)); break;
    case 'list': n.addEventListener('click', (ev) => { const i = ev.target.dataset.i; if (i != null) send({ t: 'list', k: it.k, i: +i }); }); break;
    case 'isbox': {
      /* Icon, count, an amount (kept while the window is open), Take and Put that many. */
      const ic = el('img'), lb = el('span'), amt = el('input'), take = el('button', null, 'Take'), put = el('button', null, 'Put');
      amt.type = 'number'; amt.min = '1'; amt.value = '1'; amt.className = 'amt'; amt.title = 'How many';
      const nval = () => Math.max(1, parseInt(amt.value, 10) || 1);
      take.onclick = () => send({ t: 'isbox', k: it.k, a: 'taken', n: nval() });
      put.onclick = () => send({ t: 'isbox', k: it.k, a: 'putn', n: nval() });
      n.classList.add('isbox'); n.append(ic, lb, amt, take, put); break;
    }
  }
  return n;
}

function upel(n, it) {
  switch (it.e) {
    case 'btn': n.firstChild.textContent = it.text; break;
    case 'lbl': n.textContent = it.text; break;
    case 'chk': n.firstChild.checked = it.on; n.lastChild.textContent = it.text || ''; break;
    case 'text': { const i = n.firstChild; if (document.activeElement !== i) i.value = it.value; break; }
    case 'box': n.textContent = it.type; break;
    case 'inv': {
      const cell = (it.w - 1) / it.cw;
      n.dataset.cell = cell; n.dataset.cw = it.cw; n.dataset.chh = it.ch; n.style.setProperty('--cell', cell + 'px');
      n.replaceChildren(...it.items.map((x) => itemnode(x, x.cx * cell + 1, x.cy * cell + 1, x.cw * cell - 1, x.chh * cell - 1)));
      break;
    }
    case 'equ': {
      const kids = [];
      it.slots.forEach((s, i) => {
        const sl = el('div', 'slot'); sl.style.left = s[0] + 'px'; sl.style.top = s[1] + 'px';
        sl.style.width = it.cell + 'px'; sl.style.height = it.cell + 'px';
        sl.onclick = () => { if (holding()) send({ t: 'equdrop', k: it.k, slot: i }); };
        kids.push(sl);
      });
      for (const x of it.items) {
        const s = it.slots[x.slot];
        if (s) kids.push(itemnode(x, s[0] + 1, s[1] + 1, it.cell - 1, it.cell - 1));
      }
      n.replaceChildren(...kids); break;
    }
    case 'isbox': {
      const [ic, lb] = n.children;
      if (it.r && ic.dataset.r !== it.r) { ic.src = resurl(it.r); ic.dataset.r = it.r; }
      lb.textContent = it.cap >= 0 ? it.n + '/' + it.cap : (it.n >= 0 ? it.n : '');
      break;
    }
    case 'item': n.replaceChildren(itemnode(it.item, 0, 0, it.w, it.h)); break;
    case 'list':
      n.replaceChildren(...it.items.map((t, i) => { const d = el('div', i === it.sel ? 'sel' : '', t || '—'); d.dataset.i = i; return d; }));
      break;
    case 'make': {
      const specs = (l) => { const d = el('div', 'specs'); for (const x of l) { const c = el('div', 'spec'); c.title = x.n || (x.r ? prettyres(x.r) : ''); if (x.r) { const i = el('img'); i.src = resurl(x.r); c.appendChild(i); } if (x.c > 0) c.appendChild(el('span', null, x.c)); d.appendChild(c); } return d; };
      const row = el('div', 'specs'); row.append(specs(it.in), el('span', null, '→'), specs(it.out));
      n.replaceChildren(el('div', 'rcp', it.rcp), row);
      break;
    }
  }
}

function itemnode(x, l, t, w, h) {
  const n = el('div', 'item');
  n.style.left = l + 'px'; n.style.top = t + 'px'; n.style.width = w + 'px'; n.style.height = h + 'px';
  n.title = (x.n || (x.r ? prettyres(x.r) : 'item')) + (x.q != null ? ' Q' + x.q : '');
  if (x.r) { const i = el('img'); i.src = resurl(x.r); n.appendChild(i); }
  else n.appendChild(el('div', 'nm', x.n || '…'));
  if (x.q != null) n.appendChild(el('span', 'q', Math.round(x.q)));
  if (x.num != null) n.appendChild(el('span', 'n', x.num));
  if (x.m) { const m = el('i', 'm'); m.style.width = Math.round(x.m * (w - 2) / 100) + 'px'; n.appendChild(m); }
  longpress(n, () => send({ t: 'item', k: x.k, a: 'iact', mods: takemods() }), (ev) => {
    ev.stopPropagation();
    const mods = takemods();
    if (mods & 1) send({ t: 'item', k: x.k, a: 'transfer', all: !!(mods & 2) });
    else if (mods & 2) send({ t: 'item', k: x.k, a: 'drop', all: !!(mods & 4) });
    else if (holding()) send({ t: 'item', k: x.k, a: 'itemact', mods });
    else send({ t: 'item', k: x.k, a: 'take' });
  });
  return n;
}

function invclick(ev, n) {
  if (!holding()) return;
  const r = n.getBoundingClientRect(), cw = +n.dataset.cw, chh = +n.dataset.chh;
  send({ t: 'invdrop', k: n.dataset.k, x: Math.min(cw - 1, Math.floor((ev.clientX - r.left) / (r.width / cw))), y: Math.min(chh - 1, Math.floor((ev.clientY - r.top) / (r.height / chh))) });
}

/* Tap and long-press (or right-click) on one element. */
function longpress(n, onlong, ontap) {
  let timer = null, fired = false, start = null;
  n.addEventListener('contextmenu', (e) => { e.preventDefault(); e.stopPropagation(); onlong(); });
  n.addEventListener('pointerdown', (e) => {
    if (e.button !== 0) return;
    fired = false; start = { x: e.clientX, y: e.clientY };
    if (e.pointerType !== 'mouse') timer = setTimeout(() => { fired = true; onlong(); }, 450);
  });
  n.addEventListener('pointermove', (e) => { if (start && Math.hypot(e.clientX - start.x, e.clientY - start.y) > 8) clearTimeout(timer); });
  n.addEventListener('pointerup', () => clearTimeout(timer));
  n.addEventListener('click', (e) => { clearTimeout(timer); if (!fired) ontap(e); });
}

/* ------------------------------------------------------------------ HUD */

function renderhud() {
  const meters = [], buffs = [], chats = [];
  let flower = null, charlist = null, prog = null, hand = null, mode = null, toggles = null, bots = null, fight = null, attrs = null, place = null,
    cal = null, speed = null, belt = null, beltinv = null;
  for (const e of S.ui.values()) {
    switch (e.t) {
      case 'meter': meters.push(e); break;
      case 'buff': buffs.push(e); break;
      case 'chat': chats.push(e); break;
      case 'flower': flower = e; break;
      case 'charlist': charlist = e; break;
      case 'prog': prog = e; break;
      case 'hand': hand = e; break;
      case 'mode': mode = e; break;
      case 'toggles': toggles = e; break;
      case 'bots': bots = e; break;
      case 'fight': fight = e; break;
      case 'attrs': attrs = e; break;
      case 'place': place = e; break;
      case 'cal': cal = e; break;
      case 'speed': speed = e; break;
      case 'belt': belt = e; break;
      case 'beltinv': beltinv = e; break;
    }
  }
  drawcal(cal); drawspeed(speed); drawbelt(belt); drawbeltinv(beltinv);
  drawfight(fight);
  drawattrs(attrs);
  $('#placebar').classList.toggle('hidden', !place);
  if (place) $('#placename').textContent = place.r ? prettyres(place.r) : 'Place';
  else { S.placeang = null; S.ghost = null; }
  $('#meters').replaceChildren(...meters.map((m) => {
    const row = el('div', 'meter'); row.title = m.tip || m.name;
    if (m.icon) { const i = el('img'); i.src = resurl(m.icon); row.appendChild(i); }
    const bar = el('div', 'bar'); let left = 0;
    for (const s of m.m) { const f = el('i'); f.style.left = left * 100 + '%'; f.style.width = Math.max(0, s.a) * 100 + '%'; f.style.background = s.c; bar.appendChild(f); }
    row.appendChild(bar); return row;
  }));
  $('#buffs').replaceChildren(...buffs.map((b) => {
    const n = el('div', 'buff'); if (b.r) { const i = el('img'); i.src = resurl(b.r); n.appendChild(i); n.title = prettyres(b.r); }
    if (b.a != null) { const a = el('i'); a.style.width = b.a * 100 + '%'; n.appendChild(a); }
    return n;
  }));
  $('#prog').classList.toggle('hidden', !prog);
  if (prog) $('#prog').firstChild.style.width = prog.v * 100 + '%';
  const banner = $('#banner');
  banner.classList.toggle('hidden', !(mode && mode.areasel));
  banner.textContent = 'Drag a rectangle on the map to select the area';
  drawflower(flower);
  const cl = $('#charlist');
  cl.classList.toggle('hidden', !charlist);
  if (charlist) $('#chars').replaceChildren(...charlist.chars.map((c) => { const b = el('button', null, c); b.onclick = () => send({ t: 'play', k: charlist.k, name: c }); return b; }));
  const hd = $('#hand');
  hd.classList.toggle('hidden', !hand);
  if (hand && hand.item.r && hd.dataset.r !== hand.item.r) { const i = el('img'); i.src = resurl(hand.item.r); hd.replaceChildren(i); hd.dataset.r = hand.item.r; }
  movehand();
  if (toggles) for (const w of ['inv', 'equ']) seton(w, !!toggles[w]);
  seton('chr', !$('#chr').classList.contains('hidden'));
  seton('menu', !$('#actions').classList.contains('hidden'));
  seton('more', !$('#more').classList.contains('hidden'));
  drawchat(chats);
  drawruns(bots);
}

function movehand() {
  const hd = $('#hand');
  if (hd.classList.contains('hidden')) return;
  hd.style.left = (S.pointer.x + 8) + 'px'; hd.style.top = (S.pointer.y + 8) + 'px';
}

let flowerk = null;
function showctx(args, items) { S.ctx = { args, items }; drawflower(curflower()); }
const curflower = () => [...S.ui.values()].find((e) => e.t === 'flower');
function drawflower(f) {
  const box = $('#flower');
  if (!f && !S.ctx) { box.classList.add('hidden'); flowerk = null; return; }
  const key = (f ? f.k + JSON.stringify(f.opts) : '') + '|' + (S.ctx ? JSON.stringify(S.ctx) : '');
  if (flowerk === key) return;
  flowerk = key;
  const kids = [];
  if (f) f.opts.forEach((o, i) => { const b = el('button', null, o); b.onclick = () => { send({ t: 'flower', k: f.k, i }); S.ctx = null; box.classList.add('hidden'); }; kids.push(b); });
  if (S.ctx) {
    const c = S.ctx;
    kids.push(el('div', 'sep', 'Nurgling'));
    c.items.forEach((o, i) => {
      const b = el('button', 'nur', o);
      b.onclick = () => {
        op('ctx.run', Object.assign({ i }, c.args));
        const sf = curflower(); if (sf) send({ t: 'flower', k: sf.k, i: -1 });
        S.ctx = null; box.classList.add('hidden'); flowerk = null;
      };
      kids.push(b);
    });
  }
  box.replaceChildren(...kids);
  box.classList.remove('hidden');
  const at = S.flowerat || { x: innerWidth / 2, y: innerHeight / 2 };
  const r = box.getBoundingClientRect();
  box.style.left = Math.max(4, Math.min(at.x + 12, innerWidth - r.width - 4)) + 'px';
  box.style.top = Math.max(4, Math.min(at.y - r.height / 2, innerHeight - r.height - 60)) + 'px';
}
function closeflower() {
  const f = curflower();
  if (f) send({ t: 'flower', k: f.k, i: -1 });
  if (S.ctx) { S.ctx = null; drawflower(null); }
}

/* ------------------------------------------------------------------ calendar, speed, belts */

function drawcal(c) {
  const box = $('#cal');
  box.classList.toggle('hidden', !c);
  if (!c) return;
  const hh = String(c.hh).padStart(2, '0'), mm = String(c.mm).padStart(2, '0');
  box.innerHTML = '';
  box.append(el('b', null, c.season + ' ' + c.sday + '/' + c.slen), document.createTextNode('  ' + (c.night ? '☾ ' : '☀ ') + hh + ':' + mm));
  box.title = c.moon + ' · ' + c.left + ' days left in ' + c.season;
  $('#morecal').textContent = c.season + ' ' + c.sday + '/' + c.slen + ' · ' + (c.night ? '☾ ' : '☀ ') + hh + ':' + mm + ' · ' + c.moon + ' · ' + c.left + ' days left';
}

const SPEEDS = ['Crawl', 'Walk', 'Run', 'Sprint'];
function drawspeed(sp) {
  const box = $('#speed');
  box.classList.toggle('hidden', !sp);
  if (!sp) return;
  const key = sp.cur + '/' + sp.max;
  if (box.dataset.k === key) return;
  box.dataset.k = key;
  const mk = () => {
    const kids = [];
    for (let i = 0; i <= Math.min(sp.max, SPEEDS.length - 1); i++) {
      const b = el('button', i === sp.cur ? 'on' : '', SPEEDS[i]); b.onclick = () => send({ t: 'speed', v: i }); kids.push(b);
    }
    return kids;
  };
  box.replaceChildren(...mk());
  $('#morespeed').replaceChildren(...mk());
}

function drawbelt(b) {
  const box = $('#belts');
  box.classList.toggle('hidden', !b);
  if (!b) return;
  S.beltpage = Math.max(0, Math.min(S.beltpage, b.n - 1));
  $('#beltpage').textContent = b.n > 1 ? (S.beltpage + 1) + '/' + b.n : '';
  const kids = [];
  for (let j = 0; j < 12; j++) {
    const i = S.beltpage * 12 + j, r = b.slots[i];
    const s = el('button', 'bslot'); s.title = r ? prettyres(r) : 'empty';
    if (r) { const im = el('img'); im.src = resurl(r); s.appendChild(im); }
    s.appendChild(el('span', null, 'F' + (j + 1)));
    longpress(s, () => { if (r && confirm('Clear this slot?')) send({ t: 'belt', i, a: 'clear' }); },
      () => send({ t: 'belt', i, a: holding() ? 'set' : 'use', mods: takemods() }));
    kids.push(s);
  }
  $('#beltslots').replaceChildren(...kids);
}
$('#beltprev').onclick = () => { S.beltpage--; renderhud(); };
$('#beltnext').onclick = () => { S.beltpage++; renderhud(); };

/* The equipped belt's contents, as a small inventory. */
function drawbeltinv(b) {
  const box = $('#beltinv');
  box.classList.toggle('hidden', !b);
  if (!b) return;
  const cell = 30;
  const inv = el('div', 'inv');
  inv.dataset.k = b.inv; inv.dataset.cell = cell; inv.dataset.cw = b.cw; inv.dataset.chh = b.ch;
  inv.style.setProperty('--cell', cell + 'px');
  inv.style.width = (b.cw * cell + 1) + 'px'; inv.style.height = (b.ch * cell + 1) + 'px';
  inv.append(...b.items.map((x) => itemnode(x, x.cx * cell + 1, x.cy * cell + 1, x.cw * cell - 1, x.chh * cell - 1)));
  inv.addEventListener('click', (ev) => invclick(ev, inv));
  box.replaceChildren(inv);
}

/* ------------------------------------------------------------------ combat */

function drawfight(f) {
  const box = $('#fight');
  box.classList.toggle('hidden', !f);
  S.foes = new Set(f ? f.rels.map((r) => r.gob) : []);
  if (!f) return;
  $('#fightcd').firstChild.style.width = (f.cd ? Math.min(1, f.cd / f.cdt) * 100 : 0) + '%';
  $('#fightacts').replaceChildren(...(f.acts || []).map((a, n) => {
    const b = el('button', 'fact'); b.title = a.r ? prettyres(a.r) : '';
    if (a.r) { const i = el('img'); i.src = resurl(a.r); b.appendChild(i); }
    b.appendChild(el('span', null, String(n + 1)));
    if (a.cd) { const c = el('i'); c.style.height = Math.min(1, a.cd / a.cdt) * 100 + '%'; b.appendChild(c); }
    b.onclick = () => send({ t: 'fight', a: 'use', i: a.i, mods: takemods() });
    return b;
  }));
  $('#fightrels').replaceChildren(...f.rels.map((r) => {
    const d = el('div', 'frel' + (r.cur ? ' cur' : ''));
    const g = S.gobs.get(r.gob);
    const t = el('button', null, r.cur ? '⚔' : 'Target'); t.onclick = () => send({ t: 'fight', a: 'target', gob: r.gob });
    d.append(t, el('span', null, (g ? prettyres(g.g.r) : 'foe') + '  IP ' + r.ip + ' / ' + r.oip));
    for (const b of r.buffs.concat(r.relbuffs)) if (b.r) { const i = el('img'); i.src = resurl(b.r); i.title = prettyres(b.r) + (b.a != null ? ' ' + Math.round(b.a * 100) + '%' : ''); d.appendChild(i); }
    return d;
  }));
}

/* ------------------------------------------------------------------ character */

function drawattrs(a) {
  if (!a || $('#chr').classList.contains('hidden')) return;
  const base = a.a.slice(0, 9), skills = a.a.slice(9);
  const rows = (list) => list.map((x) => { const r = el('div', 'row'); r.append(el('span', null, x.n)); const v = el('b', null, x.c !== x.b ? x.b + ' (' + x.c + ')' : String(x.b)); r.appendChild(v); return r; });
  $('#chrbody').replaceChildren(el('h4', null, 'Base attributes'), ...rows(base), el('h4', null, 'Abilities'), ...rows(skills));
}

/* ------------------------------------------------------------------ action menu */

async function loadmenu(key) {
  const r = await op('menu.list', { key });
  if (!r.ok) return;
  S.actdir = key;
  $('#actpath').replaceChildren(...[{ key: '', name: 'Actions' }].concat(r.data.path).map((p) => { const b = el('button', null, p.name); b.onclick = () => loadmenu(p.key); return b; }));
  const items = r.data.items.slice().sort((a, b) => (b.dir - a.dir) || a.name.localeCompare(b.name));
  $('#actgrid').replaceChildren(...items.map((it) => {
    const b = el('button', 'act' + (it.dir ? ' dir' : '')), i = el('img');
    i.src = resurl(it.key); b.append(i, el('span', null, it.name));
    b.onclick = () => {
      if (it.dir) loadmenu(it.key);
      else op('menu.use', { key: it.key }).then((x) => { if (x.ok) { $('#actions').classList.add('hidden'); renderhud(); } });
    };
    return b;
  }));
}

/* ------------------------------------------------------------------ chat */

function drawchat(chats) {
  if (!S.chatsel || !chats.find((c) => c.k === S.chatsel)) {
    const pref = chats.find((c) => /area|village/i.test(c.name) && c.send) || chats.find((c) => c.send) || chats[0];
    S.chatsel = pref ? pref.k : null;
  }
  $('#chattabs').replaceChildren(...chats.map((c) => {
    const b = el('button', c.k === S.chatsel ? 'on' : '', c.name); b.onclick = () => { S.chatsel = c.k; renderhud(); }; return b;
  }));
  const cur = chats.find((c) => c.k === S.chatsel);
  const log = $('#chatlog');
  const atbottom = log.scrollTop + log.clientHeight >= log.scrollHeight - 4;
  log.replaceChildren(...(cur ? cur.lines : []).map((l) => el('div', null, l)));
  if (atbottom) log.scrollTop = log.scrollHeight;
  $('#chatform').classList.toggle('hidden', !(cur && cur.send));
}
$('#chatform').addEventListener('submit', (e) => {
  e.preventDefault();
  const i = $('#chatin');
  if (S.chatsel && i.value.trim()) send({ t: 'chat', k: S.chatsel, text: i.value });
  i.value = '';
});

/* ------------------------------------------------------------------ bots */

function drawruns(bots) {
  const runs = bots ? bots.runs : [];
  $('#botruns').replaceChildren(...runs.map((r) => {
    const row = el('div', 'run'), stop = el('button', null, 'Stop');
    stop.onclick = () => op('bots.stop', { id: r.id });
    row.append(el('span', null, '▶ ' + r.name), stop); return row;
  }));
  seton('bots', runs.length > 0 || !$('#bots').classList.contains('hidden'));
}
async function openbots() {
  if (!S.botcache) {
    const r = await op('bots.list');
    if (!r.ok) return;
    S.botcache = r.data;
  }
  drawbots();
}
function drawbots() {
  const f = $('#botfilter').value.toLowerCase(), groups = new Map();
  for (const b of S.botcache || []) {
    if (f && !(b.name.toLowerCase().includes(f) || b.desc.toLowerCase().includes(f))) continue;
    const g = b.group || 'Other';
    if (!groups.has(g)) groups.set(g, []);
    groups.get(g).push(b);
  }
  const kids = [];
  for (const [g, list] of groups) {
    kids.push(el('h4', null, g.replace(/^.*\./, '')));
    for (const b of list) {
      const row = el('div', 'bot'), go = el('button', null, 'Start'), nm = el('span', null, b.name);
      nm.title = b.desc; go.onclick = () => op('bots.start', { id: b.id }).then((r) => { if (r.ok) toast(b.name + ' started'); });
      row.append(nm, go); kids.push(row);
    }
  }
  $('#botlist').replaceChildren(...kids);
}
$('#botfilter').addEventListener('input', drawbots);

/* ------------------------------------------------------------------ bar */

function seton(act, on) { document.querySelectorAll('[data-act="' + act + '"]').forEach((b) => b.classList.toggle('on', on)); }
function updmods() { document.querySelectorAll('[data-mod]').forEach((b) => b.classList.toggle('on', !!(S.mods & +b.dataset.mod))); }
function updfollow() { seton('follow', S.follow); }
function updshows() {
  document.querySelectorAll('[data-show]').forEach((b) => b.classList.toggle('on', b.dataset.show === 'mm' ? mmon : document.body.classList.contains('show-' + b.dataset.show)));
}
document.querySelectorAll('[data-mod]').forEach((b) => { b.onclick = () => { S.mods ^= +b.dataset.mod; updmods(); }; });
document.querySelectorAll('[data-show]').forEach((b) => {
  b.onclick = () => {
    const f = b.dataset.show;
    if (f === 'mm') { mmtoggle(); return; }
    const on = document.body.classList.toggle('show-' + f);
    localStorage.setItem('nurgling.show.' + f, on ? 'on' : 'off');
    updshows();
  };
});
updshows();
document.querySelectorAll('[data-act]').forEach((b) => {
  b.onclick = () => {
    const a = b.dataset.act;
    /* From the More sheet, anything that opens something closes the sheet first. */
    if (b.closest('#more') && !['pf', 'follow'].includes(a)) $('#more').classList.add('hidden');
    if (a === 'more') { $('#more').classList.toggle('hidden'); renderhud(); }
    else if (a === 'inv' || a === 'equ') send({ t: 'toggle', w: a });
    else if (a === 'game') { $('#game').classList.toggle('hidden'); }
    else if (a === 'chr') { $('#chr').classList.toggle('hidden'); renderhud(); }
    else if (a === 'menu') { $('#actions').classList.toggle('hidden'); if (!$('#actions').classList.contains('hidden')) loadmenu(S.actdir); renderhud(); }
    else if (a === 'chat') $('#chat').classList.toggle('hidden');
    else if (a === 'bots') { $('#bots').classList.toggle('hidden'); if (!$('#bots').classList.contains('hidden')) openbots(); renderhud(); }
    else if (a === 'pf') { S.pf = !S.pf; seton('pf', S.pf); }
    else if (a === 'follow') { S.follow = !S.follow; updfollow(); }
  };
});
/* Behind the web host the page lives at /seat/<account>/; logging out ends the seat, so go back to the portal. */
const PORTAL = /^\/seat\/[^/]+\/$/.test(BASE) ? '/' : null;
let loggingout = false;
document.querySelectorAll('[data-game]').forEach((b) => {
  b.onclick = () => {
    const a = b.dataset.game;
    if (a === 'logout' && !confirm('Log out of this character? Bots it runs will stop.')) return;
    send({ t: 'game', a });
    $('#game').classList.add('hidden');
    if (a === 'logout') {
      loggingout = true;
      $('#conn').classList.remove('hidden'); $('#connmsg').textContent = 'Logged out.';
      if (PORTAL) setTimeout(() => { location.href = PORTAL; }, 1500);
    }
  };
});
/* ---- sessions: the other characters running on this host, one tap away ---- */
const MYSEAT = PORTAL ? decodeURIComponent(BASE.split('/')[2]) : null;
async function hostpost(path, body) {
  const r = await fetch(path, { method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(Object.assign({ pin: localStorage.getItem('nurgling.hostpin') || '' }, body)) });
  return r.json();
}
async function drawsessions() {
  if (!PORTAL || !localStorage.getItem('nurgling.hostpin')) return;
  let r;
  try { r = await hostpost('/api/accounts', {}); } catch (e) { return; }
  if (!r.ok) return;
  const kids = r.accounts.filter((a) => a.running).map((a) => {
    const me = a.name.toLowerCase().replace(/[^a-z0-9_\-]/g, '_') === MYSEAT;
    const b = el('button', me ? 'on' : '', a.name);
    if (!me) b.onclick = async () => {
      const x = await hostpost('/api/login', { acct: a.name, pass: '' });
      if (x.ok) location.href = x.url; else toast(x.err, true);
    };
    return b;
  });
  const add = el('button', null, '+'); add.title = 'Start another character'; add.onclick = () => { location.href = PORTAL; };
  kids.push(add);
  $('#sessions').replaceChildren(...kids);
  $('#sessions').classList.remove('hidden');
  $('#moresessions').replaceChildren(...kids.map((b) => { const c = b.cloneNode(true); c.onclick = b.onclick; return c; }));
}
drawsessions(); setInterval(drawsessions, 10000);

document.querySelectorAll('[data-close]').forEach((b) => { b.onclick = () => { $('#' + b.dataset.close).classList.add('hidden'); renderhud(); }; });
addEventListener('keydown', (e) => {
  if (e.target.tagName === 'INPUT') return;
  const f = S.ui.get('fight');
  if (f && /^[1-9]$/.test(e.key) && f.acts && f.acts[+e.key - 1]) { send({ t: 'fight', a: 'use', i: +e.key - 1, mods: 0 }); return; }
  const fk = /^F([1-9]|1[0-2])$/.exec(e.key);
  if (fk) { e.preventDefault(); send({ t: 'belt', i: S.beltpage * 12 + (+fk[1] - 1), a: 'use', mods: 0 }); return; }
  if (e.key === 'Tab') { e.preventDefault(); send({ t: 'toggle', w: 'inv' }); }
  else if (e.key === 'Escape') { if (placing()) cancelplace(); else closeflower(); }
  else if (e.key === 'Enter') { $('#chat').classList.remove('hidden'); $('#chatin').focus(); e.preventDefault(); }
});
addEventListener('pointermove', (e) => { S.pointer = { x: e.clientX, y: e.clientY }; movehand(); });
setInterval(() => send({ t: 'ping', ts: Date.now() }), 20000);

connect();
