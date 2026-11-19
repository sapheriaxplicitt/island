const { contextBridge, ipcRenderer } = require('electron');

const on = (ch) => (cb) => ipcRenderer.on(ch, (_e, data) => cb(data));

contextBridge.exposeInMainWorld('island', {
  onState: on('state'),
  onLyrics: on('lyrics'),
  onNotify: on('notify'),
  onPrivacy: on('privacy'),
  onPower: on('power'),
  control: (cmd, arg) => ipcRenderer.invoke('ctl', cmd, arg),
  mouse: (over) => ipcRenderer.send('mouse', over),
});
