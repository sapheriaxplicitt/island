// Системные события для острова: интернет, Wi-Fi, VPN, аудио-устройства, флешки, микрофон/камера.
const os = require('os');
const { execFile } = require('child_process');

const VPN_RE = /(\btun\d*\b|\btap\d*\b|wintun|wireguard|\bwg\d|openvpn|nordlynx|proton|mullvad|vpn|tailscale|zerotier|amnezia|outline|clash|sing-?box|xray|v2ray|hiddify|warp)/i;
const SKIP_RE = /(vethernet|vmware|virtualbox|loopback|bluetooth|hyper-v|wsl|docker)/i;
const WIFI_RE = /(wi-?fi|wlan|wireless|беспровод)/i;

function snapshot() {
  const out = { vpn: null, wifi: false };
  const ifs = os.networkInterfaces();
  for (const [name, list] of Object.entries(ifs)) {
    if (SKIP_RE.test(name)) continue;
    const up = (list || []).some((a) => !a.internal && a.family === 'IPv4' && !a.address.startsWith('169.254.'));
    if (!up) continue;
    if (VPN_RE.test(name)) out.vpn = out.vpn || name;
    else if (WIFI_RE.test(name)) out.wifi = true;
  }
  return out;
}

function getSsid() {
  return new Promise((resolve) => {
    execFile('netsh', ['wlan', 'show', 'interfaces'], { windowsHide: true, timeout: 4000 }, (err, stdout) => {
      if (err) return resolve(null);
      const m = String(stdout).match(/^\s*SSID\s*:\s*(.+)$/m);
      resolve(m ? m[1].trim() : null);
    });
  });
}

async function checkInternet() {
  try {
    const r = await fetch('http://www.msftconnecttest.com/connecttest.txt', {
      signal: AbortSignal.timeout(3000),
      cache: 'no-store',
    });
    return r.ok;
  } catch {
    return false;
  }
}

// «Headphones (WH-1000XM4 Hands-Free)» -> { key: 'wh1000xm4', label: 'WH-1000XM4' }
function deviceInfo(name) {
  const m = String(name).match(/\((.+)\)\s*$/);
  const inner = (m ? m[1] : name).replace(/\s*(hands-?free|stereo|a2dp|ag audio)\s*/gi, ' ').trim();
  return { key: inner.toLowerCase().replace(/[^a-z0-9а-я]/g, ''), label: inner || name };
}

function startSystem(emit, setPrivacy) {
  // --------------------------------------------------- сеть (опрос раз в 3 с)
  let prev = null;
  let ssid = null;
  let online = null;
  let fails = 0;
  let tick = 0;

  async function netTick() {
    tick++;
    const s = snapshot();
    if (s.wifi && (!prev || !prev.wifi || tick % 5 === 0)) {
      const now = await getSsid();
      if (prev && prev.wifi && now && ssid && now !== ssid) {
        emit({ title: 'Wi-Fi', text: now, icon: '📶', kind: 'ok' });
      }
      if (now) ssid = now;
    }
    if (prev) {
      if (s.vpn && !prev.vpn) emit({ title: 'VPN подключён', text: s.vpn, icon: '🔒', kind: 'ok' });
      if (!s.vpn && prev.vpn) emit({ title: 'VPN отключён', text: prev.vpn, icon: '🔓', kind: 'warn' });
      if (s.wifi && !prev.wifi) emit({ title: 'Wi-Fi подключён', text: ssid || '', icon: '📶', kind: 'ok' });
      if (!s.wifi && prev.wifi) emit({ title: 'Wi-Fi отключён', text: '', icon: '📶', kind: 'warn' });
    }
    prev = s;

    const ok = await checkInternet();
    if (ok) {
      fails = 0;
      if (online === false) emit({ title: 'Интернет', text: 'Соединение восстановлено', icon: '🌐', kind: 'ok' });
      online = true;
    } else {
      fails++;
      if (fails >= 2 && online !== false) {
        if (online === true) emit({ title: 'Нет интернета', text: 'Соединение потеряно', icon: '🌐', kind: 'bad' });
        online = false;
      }
    }
  }
  netTick();
  const netTimer = setInterval(() => netTick().catch(() => {}), 3000);

  // --------------------------------------------------- ответы PowerShell-помощника sys.ps1
  let audioPrev = null;
  let disksPrev = null;
  let priv = { mic: false, cam: false };

  function onSys(m) {
    if (m.mic !== priv.mic || m.cam !== priv.cam) {
      priv = { mic: !!m.mic, cam: !!m.cam };
      setPrivacy(priv);
    }
    if (m.audio) {
      const list = (Array.isArray(m.audio) ? m.audio : [m.audio]).map(deviceInfo);
      if (audioPrev) {
        const had = new Set(audioPrev.map((d) => d.key));
        const has = new Set(list.map((d) => d.key));
        for (const d of list) if (!had.has(d.key)) emit({ title: 'Подключено', text: d.label, icon: '🎧', kind: 'ok' });
        for (const d of audioPrev) if (!has.has(d.key)) emit({ title: 'Отключено', text: d.label, icon: '🎧', kind: 'warn' });
      }
      audioPrev = list;
    }
    if (m.disks) {
      const list = Array.isArray(m.disks) ? m.disks : [m.disks];
      if (disksPrev) {
        for (const d of list) if (!disksPrev.includes(d)) emit({ title: 'Накопитель подключён', text: d, icon: '💾', kind: 'ok' });
        for (const d of disksPrev) if (!list.includes(d)) emit({ title: 'Накопитель извлечён', text: d, icon: '💾', kind: 'warn' });
      }
      disksPrev = list;
    }
  }

  return { onSys, stop: () => clearInterval(netTimer) };
}

module.exports = { startSystem };
