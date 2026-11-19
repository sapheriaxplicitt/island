// Тексты песен с нескольких источников параллельно + сверка таймкодов.
// Первый годный результат уходит сразу, итоговый выбор — когда ответили все (или через 3.5 с).
const fs = require('fs');

const UA = 'SpotifyIsland/1.0 (personal project)';
const TIMEOUT = 3000;
const HARD_CAP = 3500;

// ------------------------------------------------------------------ утилиты

async function getJson(url, headers = {}, ms = TIMEOUT) {
  const r = await fetch(url, { headers: { 'User-Agent': UA, ...headers }, signal: AbortSignal.timeout(ms) });
  if (!r.ok) throw new Error('http ' + r.status);
  return r.json();
}

function norm(s) {
  return String(s || '')
    .toLowerCase()
    .replace(/\(.*?\)|\[.*?\]/g, ' ')
    .replace(/[-–—]\s*(remaster(ed)?|live|version|mono|stereo|radio edit).*$/i, ' ')
    .replace(/[^\p{L}\p{N}]+/gu, '');
}

function similar(a, b) {
  a = norm(a);
  b = norm(b);
  if (!a || !b) return false;
  return a === b || a.includes(b) || b.includes(a);
}

function parseLrc(s) {
  const out = [];
  for (const row of String(s || '').split('\n')) {
    const m = row.match(/^((?:\[\d+:\d+(?:[.:]\d+)?\])+)(.*)$/);
    if (!m) continue;
    const text = m[2].replace(/<\d+:\d+(?:\.\d+)?>/g, '').trim();
    for (const t of m[1].matchAll(/\[(\d+):(\d+(?:[.:]\d+)?)\]/g)) {
      out.push({ ms: Math.round((Number(t[1]) * 60 + Number(t[2].replace(':', '.'))) * 1000), text });
    }
  }
  return out.sort((a, b) => a.ms - b.ms);
}

function pickMatch(list, track, title, artist, durSec) {
  const dur = track.durationMs / 1000;
  let best = null;
  let bestScore = -1;
  for (const x of list || []) {
    if (!similar(title(x), track.title)) continue;
    let s = 1;
    if (similar(artist(x), track.artist.split(',')[0])) s += 2;
    const d = durSec(x);
    if (d && dur) {
      const diff = Math.abs(d - dur);
      if (diff <= 2) s += 3;
      else if (diff <= 5) s += 1;
      else s -= 3;
    }
    if (s > bestScore) {
      bestScore = s;
      best = x;
    }
  }
  return bestScore >= 2 ? best : null;
}

// ------------------------------------------------------------------ источники

async function lrclibGet(t) {
  const q = new URLSearchParams({
    track_name: t.title,
    artist_name: t.artist.split(',')[0].trim(),
    album_name: t.album || '',
  });
  if (t.durationMs) q.set('duration', String(Math.round(t.durationMs / 1000)));
  const j = await getJson('https://lrclib.net/api/get?' + q);
  return j.syncedLyrics ? { src: 'lrclib', prior: 1.5, dur: j.duration, lines: parseLrc(j.syncedLyrics) } : null;
}

async function lrclibSearch(t) {
  const q = new URLSearchParams({ track_name: t.title, artist_name: t.artist.split(',')[0].trim() });
  const arr = await getJson('https://lrclib.net/api/search?' + q);
  const m = pickMatch(
    arr.filter((x) => x.syncedLyrics),
    t,
    (x) => x.trackName,
    (x) => x.artistName,
    (x) => x.duration
  );
  return m ? { src: 'lrclib-search', prior: 0.5, dur: m.duration, lines: parseLrc(m.syncedLyrics) } : null;
}

async function netease(t) {
  const H = { Referer: 'https://music.163.com', Cookie: 'appver=2.0.2' };
  const q = new URLSearchParams({
    s: `${t.title} ${t.artist.split(',')[0]}`,
    type: '1',
    offset: '0',
    limit: '6',
  });
  const s = await getJson('https://music.163.com/api/search/get/web?' + q, H);
  const m = pickMatch(
    (s.result && s.result.songs) || [],
    t,
    (x) => x.name,
    (x) => (x.artists || []).map((a) => a.name).join(' '),
    (x) => (x.duration || 0) / 1000
  );
  if (!m) return null;
  const l = await getJson(`https://music.163.com/api/song/lyric?id=${m.id}&lv=-1&kv=-1&tv=-1`, H);
  const lrc = l.lrc && l.lrc.lyric;
  return lrc ? { src: 'netease', prior: 0.5, dur: (m.duration || 0) / 1000, lines: parseLrc(lrc) } : null;
}

async function kugou(t) {
  const q = new URLSearchParams({
    format: 'json',
    keyword: `${t.artist.split(',')[0]} - ${t.title}`,
    page: '1',
    pagesize: '6',
  });
  const s = await getJson('https://mobileservice.kugou.com/api/v3/search/song?' + q);
  const m = pickMatch(
    (s.data && s.data.info) || [],
    t,
    (x) => x.songname,
    (x) => x.singername,
    (x) => x.duration
  );
  if (!m) return null;
  const c = await getJson(
    'https://lyrics.kugou.com/search?' +
      new URLSearchParams({ ver: '1', man: 'yes', client: 'pc', hash: m.hash, duration: String((m.duration || 0) * 1000) })
  );
  const cand = c.candidates && c.candidates[0];
  if (!cand) return null;
  const d = await getJson(
    'https://lyrics.kugou.com/download?' +
      new URLSearchParams({ ver: '1', client: 'pc', id: String(cand.id), accesskey: cand.accesskey, fmt: 'lrc', charset: 'utf8' })
  );
  if (!d.content) return null;
  const lrc = Buffer.from(d.content, 'base64').toString('utf8');
  return { src: 'kugou', prior: 0.5, dur: m.duration, lines: parseLrc(lrc) };
}

const SOURCES = [lrclibGet, lrclibSearch, netease, kugou];

// ------------------------------------------------------------------ проверка и сверка

function quality(c, track) {
  const dur = track.durationMs / 1000;
  const last = c.lines.length ? c.lines[c.lines.length - 1].ms / 1000 : 0;
  let s = c.prior;
  if (c.dur && dur) {
    const d = Math.abs(c.dur - dur);
    s += d <= 2 ? 3 : d <= 5 ? 1 : -5;
  }
  if (dur) s += last <= dur + 3 ? 1 : -5; // последняя строка не позже конца трека
  if (c.lines.length >= 10) s += 1;
  if (dur && c.lines.length && c.lines[0].ms > dur * 1000 * 0.6) s -= 3;
  return s;
}

/** Медианный сдвиг (мс) между двумя версиями по совпавшим строкам; null если мало совпадений. */
function offsetBetween(a, b) {
  const map = new Map();
  for (const l of b.lines) {
    const k = norm(l.text);
    if (k.length < 3) continue;
    if (!map.has(k)) map.set(k, []);
    map.get(k).push(l.ms);
  }
  const diffs = [];
  for (const l of a.lines) {
    const k = norm(l.text);
    const arr = map.get(k);
    if (!arr) continue;
    let best = null;
    for (const ms of arr) if (best === null || Math.abs(ms - l.ms) < Math.abs(best - l.ms)) best = ms;
    if (Math.abs(best - l.ms) < 15000) diffs.push(best - l.ms);
  }
  if (diffs.length < 3) return null;
  diffs.sort((x, y) => x - y);
  return diffs[Math.floor(diffs.length / 2)];
}

function choose(cands, track) {
  const list = cands.map((c) => ({ ...c, q: quality(c, track), agree: 0, shifts: [] }));
  for (let i = 0; i < list.length; i++) {
    for (let j = i + 1; j < list.length; j++) {
      const off = offsetBetween(list[i], list[j]);
      if (off === null) continue;
      if (Math.abs(off) <= 700) {
        list[i].agree++;
        list[j].agree++;
      } else {
        list[i].shifts.push(off);
        list[j].shifts.push(-off);
      }
    }
  }
  list.forEach((c) => (c.score = c.q + 3 * c.agree - (c.shifts.length ? 1 : 0)));
  list.sort((a, b) => b.score - a.score);
  const best = list[0];
  if (!best || best.q < 0) return null;
  return { lines: best.lines, src: best.src, agree: best.agree, sources: list.length };
}

// ------------------------------------------------------------------ кэш

class Cache {
  constructor(file) {
    this.file = file;
    this.map = new Map();
    try {
      const j = JSON.parse(fs.readFileSync(file, 'utf8'));
      for (const [k, v] of Object.entries(j)) this.map.set(k, v);
    } catch {}
  }
  get(k) {
    return this.map.get(k);
  }
  set(k, v) {
    this.map.delete(k);
    this.map.set(k, v);
    while (this.map.size > 200) this.map.delete(this.map.keys().next().value);
    clearTimeout(this.t);
    this.t = setTimeout(() => {
      try {
        fs.writeFileSync(this.file, JSON.stringify(Object.fromEntries(this.map)));
      } catch {}
    }, 2000);
  }
}

// ------------------------------------------------------------------ вход

/**
 * onUpdate(result|null) может вызываться дважды: сразу с первым годным результатом
 * и повторно, если после сверки выбран другой вариант.
 */
async function getLyrics(track, cache, onUpdate) {
  const cached = cache.get(track.id);
  if (cached) return onUpdate(cached.none ? null : cached);

  const cands = [];
  let emitted = null;

  const emit = (res) => {
    if (!res) return;
    if (emitted && emitted.src === res.src && emitted.lines.length === res.lines.length) return;
    emitted = res;
    onUpdate(res);
  };

  const runs = SOURCES.map((fn) =>
    fn(track)
      .then((c) => {
        if (!c || c.lines.length < 3) return;
        cands.push(c);
        if (!emitted) emit(choose(cands, track)); // первый годный — сразу на экран
      })
      .catch(() => {})
  );

  await Promise.race([Promise.allSettled(runs), new Promise((r) => setTimeout(r, HARD_CAP))]);

  const final = choose(cands, track);
  if (final) {
    emit(final);
    cache.set(track.id, final);
  } else if (!emitted) {
    onUpdate(null);
  }
}

module.exports = { getLyrics, Cache, _test: { choose, parseLrc } };
