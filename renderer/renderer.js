(() => {
  const api = window.island;
  const $ = (s) => document.querySelector(s);
  const island = $('#island');
  const el = {
    cover: $('#cover'), miniCover: $('#miniCover'), miniTitle: $('#miniTitle'),
    title: $('#title'), artist: $('#artist'), tl: $('#tl'), tr: $('#tr'),
    dots: $('#dots'), lyrics: $('#lyrics'), clock: $('#clock'), source: $('#source'),
    badgeSvg: document.querySelector('.badge svg'),
    icPause: $('#icPause'), icPlay: $('#icPlay'),
    nIcon: $('#nIcon'), nTitle: $('#nTitle'), nText: $('#nText'), nRight: $('#nRight'),
  };

  // ------------------------------------------------------------ состояние
  let st = null;            // последнее состояние плеера
  let lyr = null;           // [{ms,text}] | null
  let hover = false;
  let peekUntil = 0;
  let notifyUntil = 0;
  let firstState = true;
  let lastId = null;
  let lastArt = null;
  let lyrIdx = -2;
  let level = 0;            // плавная «громкость» визуализатора
  let modeTimer = null;
  const queue = [];         // очередь уведомлений

  const DOTS = 44;
  for (let i = 0; i < DOTS; i++) el.dots.appendChild(document.createElement('i'));
  const dotEls = [...el.dots.children];
  let litDots = -1;

  // ------------------------------------------------------------ режимы
  function computeMode() {
    const now = Date.now();
    if (now < notifyUntil) return 'notify';
    const has = st && st.id;
    if (has && (hover || now < peekUntil)) return 'full';
    if (has && st.isPlaying) return 'mini';
    return 'clock';
  }

  function update() {
    const now = Date.now();
    if (now >= notifyUntil && queue.length) {
      showNotify(queue.shift());
      return;
    }
    const m = computeMode();
    if (island.dataset.mode !== m) island.dataset.mode = m;
    island.dataset.lyr = lyr && lyr.length ? '1' : '0';
    clearTimeout(modeTimer);
    const next = Math.min(
      notifyUntil > now ? notifyUntil : Infinity,
      peekUntil > now ? peekUntil : Infinity
    );
    if (next !== Infinity) modeTimer = setTimeout(update, next - now + 40);
  }

  let hoverTimer = null;
  island.addEventListener('mouseenter', () => {
    api.mouse(true);
    clearTimeout(hoverTimer);
    hoverTimer = setTimeout(() => { hover = true; update(); }, 70);
  });
  island.addEventListener('mouseleave', () => {
    api.mouse(false);
    clearTimeout(hoverTimer);
    hoverTimer = setTimeout(() => { hover = false; update(); }, 450);
  });

  // ------------------------------------------------------------ часы
  function tickClock() {
    el.clock.textContent = new Date().toLocaleTimeString('ru-RU', { hour: '2-digit', minute: '2-digit' });
  }
  tickClock();
  setInterval(tickClock, 1000);

  // ------------------------------------------------------------ цвет обложки
  function extractAccent(img) {
    try {
      const c = document.createElement('canvas');
      c.width = c.height = 24;
      const x = c.getContext('2d', { willReadFrequently: true });
      x.drawImage(img, 0, 0, 24, 24);
      const d = x.getImageData(0, 0, 24, 24).data;
      const buckets = Array.from({ length: 18 }, () => ({ w: 0, r: 0, g: 0, b: 0 }));
      for (let i = 0; i < d.length; i += 4) {
        const r = d[i] / 255, g = d[i + 1] / 255, b = d[i + 2] / 255;
        const mx = Math.max(r, g, b), mn = Math.min(r, g, b), df = mx - mn;
        const s = mx ? df / mx : 0;
        if (s < 0.22 || mx < 0.25) continue;
        let h = 0;
        if (df) {
          if (mx === r) h = ((g - b) / df) % 6;
          else if (mx === g) h = (b - r) / df + 2;
          else h = (r - g) / df + 4;
        }
        h = (h * 60 + 360) % 360;
        const w = s * mx;
        const k = buckets[Math.floor(h / 20) % 18];
        k.w += w; k.r += d[i] * w; k.g += d[i + 1] * w; k.b += d[i + 2] * w;
      }
      const best = buckets.reduce((a, b) => (b.w > a.w ? b : a));
      if (best.w < 0.5) return '#e4e4e4';
      return boost(best.r / best.w, best.g / best.w, best.b / best.w);
    } catch {
      return '#ff8fa3';
    }
  }

  function boost(r, g, b) {
    r /= 255; g /= 255; b /= 255;
    const mx = Math.max(r, g, b), mn = Math.min(r, g, b);
    let h = 0, s = 0;
    const l = (mx + mn) / 2;
    const d = mx - mn;
    if (d) {
      s = d / (1 - Math.abs(2 * l - 1));
      if (mx === r) h = ((g - b) / d) % 6;
      else if (mx === g) h = (b - r) / d + 2;
      else h = (r - g) / d + 4;
      h *= 60;
      if (h < 0) h += 360;
    }
    s = Math.max(0.6, Math.min(0.95, s));
    return `hsl(${h.toFixed(0)} ${(s * 100).toFixed(0)}% 68%)`;
  }

  function setArt(url) {
    if (url === lastArt) return;
    lastArt = url;
    if (!url) {
      el.cover.removeAttribute('src');
      el.miniCover.removeAttribute('src');
      return;
    }
    const img = new Image();
    img.onload = () => {
      el.cover.src = url;
      el.miniCover.src = url;
      el.cover.animate([{ opacity: 0, transform: 'scale(1.08)' }, { opacity: 1, transform: 'none' }], { duration: 450, easing: 'ease-out' });
      island.style.setProperty('--accent', extractAccent(img));
    };
    img.onerror = () => { el.cover.src = url; el.miniCover.src = url; };
    img.src = url;
  }

  // ------------------------------------------------------------ данные от main
  api.onState((s) => {
    if (s.idle) {
      st = null;
      update();
      return;
    }
    const isNew = s.id !== lastId;
    st = s;
    if (isNew) {
      if (!firstState) peekUntil = Date.now() + 4600;
      lastId = s.id;
      lyr = null;
      lyrIdx = -2;
      litDots = -1;
      el.title.textContent = s.title;
      el.artist.textContent = s.artist;
      el.miniTitle.textContent = s.title;
      for (const n of [el.title, el.artist]) {
        n.animate([{ opacity: 0, transform: 'translateY(6px)' }, { opacity: 1, transform: 'none' }], { duration: 380, easing: 'ease-out' });
      }
    }
    el.source.textContent = s.source || 'Музыка';
    el.badgeSvg.style.display = s.source === 'Spotify' ? '' : 'none';
    setArt(s.art);
    firstState = false;
    setPlayIcon(s.isPlaying);
    update();
  });

  api.onLyrics((m) => {
    if (!st || m.id !== st.id) return;
    lyr = m.lines && m.lines.length ? m.lines : null;
    lyrIdx = -2;
    update();
  });

  // ------------------------------------------------------------ уведомления
  function notify(n) {
    queue.push(n);
    update();
  }
  api.onNotify(notify);

  function showNotify(n) {
    const ic = String(n.icon || '');
    el.nIcon.textContent = '';
    if (/^(data:image|https:)/.test(ic)) {
      const im = new Image();
      im.src = ic;
      el.nIcon.appendChild(im);
    } else {
      el.nIcon.textContent = ic || (n.title || '•').trim().charAt(0).toUpperCase() || '•';
    }
    el.nTitle.textContent = n.title || '';
    el.nText.textContent = n.text || '';
    el.nText.style.display = n.text ? '' : 'none';
    el.nRight.textContent = n.right || '';
    island.dataset.kind = n.kind || 'info';
    notifyUntil = Date.now() + (n.ms || 4000);
    update();
  }

  // индикаторы микрофона/камеры
  api.onPrivacy((p) => {
    island.dataset.mic = p.mic ? '1' : '0';
    island.dataset.cam = p.cam ? '1' : '0';
  });

  // батарея
  let battery = null;
  const pct = () => (battery ? Math.round(battery.level * 100) + '%' : '');
  api.onPower((p) => {
    notify(
      p.ac
        ? { title: 'Зарядка', text: 'Подключено к питанию', icon: '🔌', kind: 'ok', right: pct() }
        : { title: 'Работа от батареи', text: 'Питание отключено', icon: '🔋', kind: 'info', right: pct() }
    );
  });
  if (navigator.getBattery) {
    navigator.getBattery().then((b) => {
      battery = b;
      const warned = new Set();
      let fullShown = b.level >= 1;
      b.addEventListener('levelchange', () => {
        const p = Math.round(b.level * 100);
        if (b.charging) {
          warned.clear();
          if (p >= 100 && !fullShown) {
            fullShown = true;
            notify({ title: 'Заряжено', text: 'Батарея полностью заряжена', icon: '🔋', kind: 'ok', right: '100%' });
          }
          if (p < 100) fullShown = false;
          return;
        }
        for (const t of [5, 10, 20]) {
          if (p <= t) {
            if (!warned.has(t)) {
              [5, 10, 20].filter((x) => x >= t).forEach((x) => warned.add(x));
              notify({ title: 'Низкий заряд', text: 'Подключите зарядку', icon: '🪫', kind: p <= 10 ? 'bad' : 'warn', right: p + '%' });
            }
            break;
          }
        }
      });
    }).catch(() => {});
  }

  // ------------------------------------------------------------ прогресс и текст
  const pos = () => {
    if (!st) return 0;
    const p = st.progressMs + (st.isPlaying ? Date.now() - st.fetchedAt : 0);
    return Math.max(0, st.durationMs ? Math.min(st.durationMs, p) : p);
  };
  const fmt = (ms) => {
    const s = Math.max(0, Math.floor(ms / 1000));
    return `${Math.floor(s / 60)}:${String(s % 60).padStart(2, '0')}`;
  };

  function updateProgress() {
    if (!st) return;
    const p = pos();
    const lit = st.durationMs ? Math.round((p / st.durationMs) * DOTS) : 0;
    if (lit !== litDots) {
      for (let i = 0; i < DOTS; i++) dotEls[i].classList.toggle('on', i < lit);
      litDots = lit;
    }
    el.tl.textContent = fmt(p);
    el.tr.textContent = st.durationMs ? '-' + fmt(st.durationMs - p) : '';
  }

  function updateLyrics() {
    if (!lyr || !st) {
      if (el.lyrics.firstChild) el.lyrics.textContent = '';
      return;
    }
    const p = pos() + 250;
    let idx = -1;
    for (let i = 0; i < lyr.length; i++) {
      if (lyr[i].ms <= p) idx = i;
      else break;
    }
    if (idx === lyrIdx) return;
    const first = lyrIdx === -2;
    lyrIdx = idx;
    const cur = idx >= 0 ? lyr[idx].text || '♪' : '♪';
    let nxt = lyr[idx + 1] ? lyr[idx + 1].text : '';
    if (lyr[idx + 1] && !nxt) nxt = '♪';
    el.lyrics.innerHTML = '';
    const a = document.createElement('div');
    a.className = 'ln cur';
    a.textContent = cur;
    const b = document.createElement('div');
    b.className = 'ln nxt';
    b.textContent = nxt;
    el.lyrics.append(a, b);
    if (!first) {
      el.lyrics.animate(
        [{ transform: 'translateY(26px)', opacity: 0.35 }, { transform: 'none', opacity: 1 }],
        { duration: 420, easing: 'cubic-bezier(.2,.8,.2,1)' }
      );
    }
  }

  // ------------------------------------------------------------ управление
  function setPlayIcon(playing) {
    el.icPause.style.display = playing ? '' : 'none';
    el.icPlay.style.display = playing ? 'none' : '';
  }

  $('#bPlay').addEventListener('click', () => {
    if (!st) return;
    const playing = !st.isPlaying;
    st.progressMs = pos();
    st.fetchedAt = Date.now();
    st.isPlaying = playing;
    setPlayIcon(playing);
    api.control(playing ? 'play' : 'pause');
  });
  $('#bNext').addEventListener('click', () => api.control('next'));
  $('#bPrev').addEventListener('click', () => {
    // как в плеерах: если трек идёт больше 3.5 с — сначала в начало
    if (st && pos() > 3500) {
      st.progressMs = 0;
      st.fetchedAt = Date.now();
      lyrIdx = -2;
      api.control('seek', 0);
    } else api.control('prev');
  });
  el.dots.addEventListener('click', (e) => {
    if (!st || !st.durationMs) return;
    const r = el.dots.getBoundingClientRect();
    const f = Math.max(0, Math.min(1, (e.clientX - r.left) / r.width));
    st.progressMs = Math.round(f * st.durationMs);
    st.fetchedAt = Date.now();
    lyrIdx = -2;
    api.control('seek', st.progressMs);
  });

  // ------------------------------------------------------------ визуализатор (точечная матрица)
  const dpr = Math.max(1, window.devicePixelRatio || 1);
  function prep(c) {
    const w = c.width, h = c.height;
    c.width = w * dpr;
    c.height = h * dpr;
    c.style.width = w + 'px';
    c.style.height = h + 'px';
    const x = c.getContext('2d');
    x.scale(dpr, dpr);
    return { x, w, h };
  }
  const vTop = { ...prep($('#vizTop')), cols: 8, rows: 6 };
  const vBot = { ...prep($('#vizBottom')), cols: 50, rows: 4 };
  const vMini = { ...prep($('#vizMini')), cols: 6, rows: 3 };

  function amp(c, t) {
    const a = 0.5 + 0.5 * Math.sin(t * 2.3 + c * 0.9);
    const b = 0.5 + 0.5 * Math.sin(t * 3.7 + c * 1.7 + 2.0);
    const beat = Math.pow(0.5 + 0.5 * Math.sin(t * 13.2 + c * 0.2), 3);
    return Math.min(1, 0.45 * a + 0.35 * b + 0.3 * beat);
  }

  function drawViz(v, t, accent) {
    const { x, w, h, cols, rows } = v;
    x.clearRect(0, 0, w, h);
    const cw = w / cols, ch = h / rows;
    const r = Math.min(cw, ch) * 0.3;
    for (let c = 0; c < cols; c++) {
      const a = amp(c, t) * level;
      for (let i = 0; i < rows; i++) {
        const k = Math.max(0, Math.min(1, (a - i / rows) * rows));
        if (k > 0.02) {
          x.globalAlpha = 0.1 + k * (1 - 0.5 * (i / rows)) * 0.9;
          x.fillStyle = accent;
        } else {
          x.globalAlpha = 0.09;
          x.fillStyle = '#ffffff';
        }
        x.beginPath();
        x.arc(c * cw + cw / 2, h - (i * ch + ch / 2), r, 0, 6.2832);
        x.fill();
      }
    }
    x.globalAlpha = 1;
  }

  const cs = getComputedStyle(island);
  function frame(ts) {
    const t = ts / 1000;
    const playing = !!(st && st.isPlaying);
    level += ((playing ? 1 : 0) - level) * 0.05;
    const mode = island.dataset.mode;
    if (mode === 'full') {
      const accent = cs.getPropertyValue('--accent').trim() || '#ff8fa3';
      updateProgress();
      updateLyrics();
      drawViz(vTop, t, accent);
      drawViz(vBot, t, accent);
    } else if (mode === 'mini') {
      drawViz(vMini, t, cs.getPropertyValue('--accent').trim() || '#ff8fa3');
    }
    requestAnimationFrame(frame);
  }
  requestAnimationFrame(frame);

  update();
})();
