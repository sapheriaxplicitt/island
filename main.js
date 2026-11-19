const { app, BrowserWindow, Tray, Menu, ipcMain, screen, nativeImage, powerMonitor } = require('electron');
const path = require('path');
const fs = require('fs');
const http = require('http');
const crypto = require('crypto');
const { spawn } = require('child_process');
const { getLyrics, Cache } = require('./lyrics');
const { startSystem } = require('./system');

const WIN_W = 460;
const WIN_H = 330;
const NOTIFY_PORT = 8765;
const IS_WIN = process.platform === 'win32';

let win = null;
let tray = null;
let lyricsCache = null;
let lyricsFor = null;
let sysWatcher = null;
const helpers = [];

if (!app.requestSingleInstanceLock()) app.quit();

const send = (ch, data) => {
  if (win && !win.isDestroyed()) win.webContents.send(ch, data);
};
const toast = (n) => send('notify', n);

// ------------------------------------------------------------------ окно

function createWindow() {
  const { workArea } = screen.getPrimaryDisplay();
  win = new BrowserWindow({
    width: WIN_W,
    height: WIN_H,
    x: Math.round(workArea.x + (workArea.width - WIN_W) / 2),
    y: workArea.y,
    frame: false,
    transparent: true,
    resizable: false,
    movable: false,
    hasShadow: false,
    alwaysOnTop: true,
    skipTaskbar: true,
    show: false,
    webPreferences: {
      preload: path.join(__dirname, 'preload.js'),
      contextIsolation: true,
      nodeIntegration: false,
    },
  });
  win.setAlwaysOnTop(true, 'screen-saver');
  win.setIgnoreMouseEvents(true, { forward: true });
  win.loadFile(path.join(__dirname, 'renderer', 'index.html'));
  win.once('ready-to-show', () => win.showInactive());
}

ipcMain.on('mouse', (_e, over) => {
  if (win && !win.isDestroyed()) win.setIgnoreMouseEvents(!over, { forward: true });
});

// ------------------------------------------------------------------ PowerShell-помощники

function runScript(name, onMsg) {
  if (!IS_WIN) return null;
  // скрипты кладём во временную папку: из asar PowerShell их не запустит
  const dir = path.join(app.getPath('userData'), 'scripts');
  fs.mkdirSync(dir, { recursive: true });
  const file = path.join(dir, name);
  fs.writeFileSync(file, fs.readFileSync(path.join(__dirname, name)));

  const h = { proc: null, stopped: false, restarts: 0 };
  const start = () => {
    const p = spawn(
      'powershell.exe',
      ['-NoProfile', '-NonInteractive', '-ExecutionPolicy', 'Bypass', '-File', file],
      { windowsHide: true, stdio: ['pipe', 'pipe', 'ignore'] }
    );
    h.proc = p;
    let buf = '';
    p.stdout.setEncoding('utf8');
    p.stdout.on('data', (d) => {
      buf += d;
      let i;
      while ((i = buf.indexOf('\n')) >= 0) {
        const line = buf.slice(0, i).trim();
        buf = buf.slice(i + 1);
        if (!line) continue;
        try {
          onMsg(JSON.parse(line));
        } catch {}
      }
    });
    p.stdin.on('error', () => {});
    p.on('exit', () => {
      if (h.stopped || h.restarts >= 5) return;
      h.restarts++;
      setTimeout(start, 3000);
    });
  };
  start();
  h.write = (line) => {
    try {
      h.proc.stdin.write(line + '\n');
    } catch {}
  };
  h.stop = () => {
    h.stopped = true;
    try {
      h.proc.kill();
    } catch {}
  };
  helpers.push(h);
  return h;
}

// ------------------------------------------------------------------ музыка (Windows SMTC)

let media = null;
let artKey = null;
let artData = null;
let est = null;

const friendly = (id) => {
  const s = String(id || '');
  if (/spotify/i.test(s)) return 'Spotify';
  if (/msedge|edge/i.test(s)) return 'Edge';
  if (/chrome/i.test(s)) return 'Chrome';
  if (/firefox/i.test(s)) return 'Firefox';
  if (/opera/i.test(s)) return 'Opera';
  if (/brave/i.test(s)) return 'Brave';
  if (/yandex/i.test(s)) return 'Яндекс';
  return s.split(/[!\\.]/)[0] || 'Музыка';
};

let lastErrorShown = 0;
function onMedia(m) {
  if (m.t === 'error') {
    if (Date.now() - lastErrorShown > 60000) {
      lastErrorShown = Date.now();
      toast({ title: 'Нет доступа к медиа Windows', text: String(m.msg || '').slice(0, 60), icon: '!', kind: 'bad', ms: 6000 });
    }
    return;
  }
  if (m.none || (!m.title && !m.artist)) {
    send('state', { idle: true });
    return;
  }
  const now = Date.now();
  const id = crypto.createHash('md5').update(m.title + '|' + m.artist).digest('hex').slice(0, 16);
  if (m.art) {
    artKey = m.key;
    artData = m.art;
  }
  const playing = m.status === 'Playing';
  let pos = m.pos || 0;
  const dur = m.dur || 0;

  if (!dur) {
    // у сайта нет таймлайна — считаем время сами с начала трека
    if (!est || est.id !== id) est = { id, base: 0, since: now, playing };
    else if (est.playing !== playing) {
      if (est.playing) est.base += now - est.since;
      est.since = now;
      est.playing = playing;
    }
    pos = est.base + (playing ? now - est.since : 0);
  }

  const state = {
    id,
    title: m.title,
    artist: m.artist,
    album: m.album || '',
    art: artKey === m.key ? artData : null,
    source: friendly(m.app),
    durationMs: dur,
    progressMs: pos,
    isPlaying: playing,
    fetchedAt: now,
  };
  send('state', state);

  if (lyricsFor !== id) {
    lyricsFor = id;
    getLyrics(state, lyricsCache, (res) =>
      send('lyrics', { id, lines: res ? res.lines : null, src: res ? res.src : null })
    ).catch(() => send('lyrics', { id, lines: null }));
  }
}

ipcMain.handle('ctl', (_e, cmd, arg) => {
  if (!media) return 0;
  if (cmd === 'seek') media.write('seek ' + Math.round(arg));
  else media.write(cmd);
  return 204;
});

// ------------------------------------------------------------------ свои уведомления (HTTP)

function startNotifyServer() {
  const server = http.createServer((req, res) => {
    const u = new URL(req.url, `http://127.0.0.1:${NOTIFY_PORT}`);
    if (u.pathname !== '/notify') {
      res.writeHead(404);
      return res.end();
    }
    const done = (data) => {
      toast({
        title: String(data.title || '').slice(0, 80),
        text: String(data.text || '').slice(0, 160),
        right: String(data.right || '').slice(0, 16),
        kind: ['ok', 'warn', 'bad', 'info'].includes(data.kind) ? data.kind : 'info',
        icon: data.icon ? String(data.icon).slice(0, 200000) : '',
        ms: Math.min(Number(data.ms) || 4000, 15000),
      });
      res.writeHead(200, { 'Content-Type': 'application/json' });
      res.end('{"ok":true}');
    };
    if (req.method === 'POST') {
      let body = '';
      req.on('data', (c) => {
        body += c;
        if (body.length > 300000) req.destroy();
      });
      req.on('end', () => {
        try {
          done(JSON.parse(body || '{}'));
        } catch {
          res.writeHead(400);
          res.end();
        }
      });
    } else {
      done(Object.fromEntries(u.searchParams));
    }
  });
  server.on('error', () => {});
  server.listen(NOTIFY_PORT, '127.0.0.1');
}

// ------------------------------------------------------------------ трей

function createTray() {
  const icon = nativeImage.createFromPath(path.join(__dirname, 'icon.png')).resize({ width: 16, height: 16 });
  tray = new Tray(icon);
  tray.setToolTip('Spotify Island');
  const toggle = () => (win.isVisible() ? win.hide() : win.showInactive());
  const build = () =>
    Menu.buildFromTemplate([
      { label: 'Показать / скрыть', click: toggle },
      {
        label: 'Запускать с Windows',
        type: 'checkbox',
        checked: app.getLoginItemSettings().openAtLogin,
        click: (item) => app.setLoginItemSettings({ openAtLogin: item.checked }),
      },
      { type: 'separator' },
      { label: 'Закрыть', click: () => app.quit() },
    ]);
  tray.on('click', toggle);
  tray.on('right-click', () => tray.popUpContextMenu(build()));
  tray.setContextMenu(build());
}

// ------------------------------------------------------------------ запуск

app.whenReady().then(() => {
  lyricsCache = new Cache(path.join(app.getPath('userData'), 'lyrics-cache.json'));
  createWindow();
  createTray();
  startNotifyServer();

  media = runScript('smtc.ps1', onMedia);

  let sysHelper = null;
  sysWatcher = startSystem(toast, (p) => send('privacy', p));
  sysHelper = runScript('sys.ps1', (m) => m.t === 'sys' && sysWatcher.onSys(m));
  void sysHelper;

  powerMonitor.on('on-ac', () => send('power', { ac: true }));
  powerMonitor.on('on-battery', () => send('power', { ac: false }));

  screen.on('display-metrics-changed', () => {
    const { workArea } = screen.getPrimaryDisplay();
    if (win) win.setPosition(Math.round(workArea.x + (workArea.width - WIN_W) / 2), workArea.y);
  });
});

app.on('before-quit', () => {
  helpers.forEach((h) => h.stop());
  if (sysWatcher) sysWatcher.stop();
});
app.on('window-all-closed', () => app.quit());
