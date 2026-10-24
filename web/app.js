'use strict';

/* Union Craft Launcher — compact launcher UI */

const $ = (selector, root = document) => root.querySelector(selector);
const $$ = (selector, root = document) => Array.from(root.querySelectorAll(selector));

const params = new URLSearchParams(location.search);
let TOKEN = params.get('token') || sessionStorage.getItem('ucl-token') || '';
if (TOKEN) {
  sessionStorage.setItem('ucl-token', TOKEN);
  if (params.has('token')) {
    params.delete('token');
    history.replaceState(null, '', location.pathname + (params.toString() ? '?' + params : '') + location.hash);
  }
}

const state = {
  page: 'home',
  config: {},
  accounts: [],
  activeAccount: null,
  instances: [],
  java: [],
  versions: [],
  latest: {},
  versionsLoaded: false,
  versionsLoading: false,
  versionWarning: '',
  versionSource: '',
  versionSeq: 0,
  versionLimit: 240,
  versionFilter: 'release',
  versionQuery: '',
  selectedInstanceId: '',
  running: {},
  discover: { source: 'modrinth', type: 'modpack', q: '', hits: [], total: 0, loading: false, error: '', offset: 0 },
  discoverSeq: 0,
  jobs: new Map(),
  jobLogs: new Set(),
  jobCenterOpen: false,
  auth: { url: '', tab: 'microsoft', job: '', busy: false },
  project: null,
  importData: null,
  booted: false
};

const PAGES = {
  home: { title: '启动游戏', subtitle: '选择账户和版本后直接启动' },
  download: { title: '下载', subtitle: '安装新的 Minecraft 版本或快照' },
  instances: { title: '实例', subtitle: '管理已有客户端、版本隔离和游戏目录' },
  content: { title: '内容', subtitle: '模组、整合包、光影、资源包、数据包和地图' },
  settings: { title: '设置', subtitle: '下载源、Java、内存和账户' }
};

const esc = (v) => String(v == null ? '' : v)
  .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
  .replace(/"/g, '&quot;').replace(/'/g, '&#39;');
const attr = esc;
const icon = (name) => `<span data-icon="${esc(name)}"></span>`;
function stripText(v) {
  return String(v || '').replace(/<[^>]+>/g, ' ').replace(/&nbsp;/g, ' ').replace(/&amp;/g, '&')
    .replace(/&lt;/g, '<').replace(/&gt;/g, '>').replace(/\s+/g, ' ').trim();
}
function fmtNumber(n) {
  n = Number(n || 0);
  if (n >= 1e9) return (n / 1e9).toFixed(1).replace('.0', '') + 'B';
  if (n >= 1e6) return (n / 1e6).toFixed(1).replace('.0', '') + 'M';
  if (n >= 1e3) return (n / 1e3).toFixed(1).replace('.0', '') + 'K';
  return String(Math.round(n));
}
function fmtBytes(n) {
  n = Number(n || 0);
  if (n < 1024) return n + ' B';
  const units = ['KB', 'MB', 'GB', 'TB'];
  for (const u of units) { n /= 1024; if (n < 1024) return n.toFixed(n < 10 ? 1 : 0) + ' ' + u; }
  return n.toFixed(1) + ' PB';
}
function fmtDate(v) {
  if (!v) return '—';
  const d = new Date(v);
  if (Number.isNaN(d.getTime())) return '—';
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`;
}
function timeAgo(ms) {
  if (!ms) return '从未启动';
  const diff = Date.now() - Number(ms);
  if (diff < 60e3) return '刚刚';
  if (diff < 3600e3) return Math.floor(diff / 60e3) + ' 分钟前';
  if (diff < 86400e3) return Math.floor(diff / 3600e3) + ' 小时前';
  if (diff < 7 * 86400e3) return Math.floor(diff / 86400e3) + ' 天前';
  return fmtDate(ms);
}
const loaderName = (l) => ({ vanilla: 'Vanilla', fabric: 'Fabric', quilt: 'Quilt', forge: 'Forge', neoforge: 'NeoForge', custom: '本地版本' })[l] || (l || 'Vanilla');
const instanceLoader = (i) => i.loaderLabel || loaderName(i.detectedLoader || i.loader);
const accountTypeName = (t) => ({ microsoft: '正版账户', offline: '离线账户', thirdparty: '第三方验证' })[t] || '账户';
const typeName = (t) => ({ modpack: '整合包', mod: '模组', shader: '光影', resourcepack: '资源包', datapack: '数据包', world: '地图' })[t] || '内容';
const typeIcon = (t) => ({ modpack: 'box', mod: 'package', shader: 'sparkles', resourcepack: 'palette', datapack: 'database', world: 'map' })[t] || 'package';

const prefersReduced = () => state.config.reduceMotion || window.matchMedia('(prefers-reduced-motion: reduce)').matches;
function applyTheme() {
  let theme = state.config.theme || 'light';
  if (theme === 'system') theme = window.matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'light';
  const accent = ['blue', 'mint', 'violet', 'sunset', 'gradient'].includes(state.config.accent)
    ? state.config.accent : 'blue';
  document.documentElement.dataset.theme = theme;
  document.documentElement.dataset.accent = accent;
  document.body.classList.toggle('reduce-motion', !!state.config.reduceMotion);
  try {
    localStorage.setItem('ucl.theme', state.config.theme || 'light');
    localStorage.setItem('ucl.accent', accent);
  } catch (ignored) { }
  api('/api/window/theme', { method: 'POST', body: { dark: theme === 'dark' } }).catch(() => {});
}
window.matchMedia('(prefers-color-scheme: dark)').addEventListener?.('change', () => {
  if ((state.config.theme || 'light') === 'system') applyTheme();
});
window.addEventListener('beforeunload', () => {
  if (!state.booted) return;
  try {
    fetch('/api/config', {
      method: 'POST',
      headers: { 'X-UCL-Token': TOKEN, 'Content-Type': 'application/json' },
      body: JSON.stringify({ theme: state.config.theme || 'light', accent: state.config.accent || 'blue' }),
      keepalive: true
    }).catch(() => {});
  } catch (ignored) { }
});

const ICONS = {
  play: '<path d="M8 5.5v13L19 12 8 5.5Z" fill="currentColor" stroke="none"/>',
  stop: '<rect x="7" y="7" width="10" height="10" rx="2" fill="currentColor" stroke="none"/>',
  download: '<path d="M12 4v11m-4.5-4.5L12 15l4.5-4.5M5 19.5h14"/>',
  layers: '<path d="m12 3.5 8 4.3-8 4.3-8-4.3 8-4.3Z"/><path d="m4 12 8 4.3 8-4.3M4 16.2l8 4.3 8-4.3"/>',
  compass: '<circle cx="12" cy="12" r="8.5"/><path d="m15.5 8.5-2 5-5 2 2-5 5-2Z"/>',
  settings: '<circle cx="12" cy="12" r="3"/><path d="M12 3.4v2.2M12 18.4v2.2M3.4 12h2.2M18.4 12h2.2M6.3 6.3l1.6 1.6M16.1 16.1l1.6 1.6M17.7 6.3l-1.6 1.6M7.9 16.1l-1.6 1.6"/>',
  more: '<circle cx="5.5" cy="12" r="1.2" fill="currentColor" stroke="none"/><circle cx="12" cy="12" r="1.2" fill="currentColor" stroke="none"/><circle cx="18.5" cy="12" r="1.2" fill="currentColor" stroke="none"/>',
  refresh: '<path d="M20 8.5A8.5 8.5 0 0 0 5.4 6.6L3.4 8.7M3.4 4.7v4h4M4 15.5a8.5 8.5 0 0 0 14.6 1.9l2-2.1M20.6 19.3v-4h-4"/>',
  plus: '<path d="M12 5v14M5 12h14"/>',
  folder: '<path d="M3.5 7h5.6l2 2h9.4v9.5h-17V7Z"/><path d="M3.5 7V4.8h5.8l2 2.2"/>',
  edit: '<path d="M4 20h4.2L19 9.2a2.3 2.3 0 0 0-3.3-3.3L5 16.8V20Z"/><path d="m13.9 7.7 3.3 3.3"/>',
  trash: '<path d="M5 7h14M9.5 7V4.5h5V7M7 7l.7 13h8.6L17 7M10.2 10.5v6M13.8 10.5v6"/>',
  search: '<circle cx="10.8" cy="10.8" r="6.3"/><path d="m15.5 15.5 4.5 4.5"/>',
  x: '<path d="m6 6 12 12M18 6 6 18"/>',
  check: '<path d="m5 12.5 4.5 4.5L19 7.5"/>',
  alert: '<path d="M12 4 21 20H3L12 4Z"/><path d="M12 9.5v4.2M12 17h.01"/>',
  info: '<circle cx="12" cy="12" r="8.5"/><path d="M12 11v5M12 8h.01"/>',
  archive: '<rect x="3.5" y="5" width="17" height="4" rx="1"/><path d="M5 9v10.5h14V9M9.5 12.5h5"/>',
  loader: '<path d="M12 3.5a8.5 8.5 0 1 1-8.5 8.5"/><path d="M12 3.5v4M12 20.5v-4M3.5 12h4M20.5 12h-4"/>',
  cpu: '<rect x="6.5" y="6.5" width="11" height="11" rx="2"/><path d="M10 2.8v3.7M14 2.8v3.7M10 17.5v3.7M14 17.5v3.7M2.8 10h3.7M2.8 14h3.7M17.5 10h3.7M17.5 14h3.7"/>',
  memory: '<rect x="3.5" y="7" width="17" height="8.5" rx="2"/><path d="M7 9.5h3v3.5H7zM12 9.5h5v3.5h-5zM7 19v-2M11 19v-2M15 19v-2M19 19v-2"/>',
  globe: '<circle cx="12" cy="12" r="8.5"/><path d="M3.5 12h17M12 3.5c2.4 2.4 3.6 5.4 3.6 8.5S14.4 18.1 12 20.5c-2.4-2.4-3.6-5.4-3.6-8.5S9.6 5.9 12 3.5Z"/>',
  link: '<path d="m9.8 14.2 4.4-4.4"/><path d="m8.2 16.8-1.5 1.5a3.7 3.7 0 0 1-5.2-5.2l3-3a3.7 3.7 0 0 1 5.2 0"/><path d="m15.8 7.2 1.5-1.5a3.7 3.7 0 1 1 5.2 5.2l-3 3a3.7 3.7 0 0 1-5.2 0"/>',
  shield: '<path d="M12 3.5 19 6v5.3c0 4.2-3 7.4-7 9.2-4-1.8-7-5-7-9.2V6l7-2.5Z"/><path d="m9 11.5 2 2 4-4"/>',
  chevron: '<path d="m9 5 7 7-7 7"/>',
  user: '<circle cx="12" cy="8" r="3.5"/><path d="M5 20c.4-3.5 3.2-5.4 7-5.4s6.6 1.9 7 5.4"/>',
  terminal: '<rect x="3.5" y="4.5" width="17" height="15" rx="2"/><path d="m7 9 3 3-3 3M12.5 15h4"/>',
  map: '<path d="m3.5 6.5 5.5-2 6 2 5.5-2v13.5l-5.5 2-6-2-5.5 2V6.5Z"/><path d="M9 4.5v13.5M15 6.5v13.5"/>',
  package: '<path d="M4.5 7.5 12 3.5l7.5 4v9L12 20.5l-7.5-4v-9Z"/><path d="m4.5 7.5 7.5 4 7.5-4M12 11.5v9"/>',
  palette: '<path d="M12 3.5a8.5 8.5 0 1 0 0 17h1a1.8 1.8 0 0 0 1.1-3.2 1.8 1.8 0 0 1 1.1-3.2h1.7a3.6 3.6 0 0 0 3.6-3.6c0-4-3.8-7-8.5-7Z"/><circle cx="7.5" cy="10" r=".9" fill="currentColor" stroke="none"/><circle cx="10.2" cy="7" r=".9" fill="currentColor" stroke="none"/><circle cx="14" cy="7.5" r=".9" fill="currentColor" stroke="none"/>',
  sparkles: '<path d="m12 3 1.5 4.7 4.7 1.5-4.7 1.5L12 15.4l-1.5-4.7L5.8 9.2l4.7-1.5L12 3Z"/><path d="m18.5 15 .8 2.3 2.2.7-2.2.8-.8 2.2-.8-2.2-2.2-.8 2.2-.7.8-2.3Z"/>',
  database: '<ellipse cx="12" cy="5.5" rx="7" ry="2.5"/><path d="M5 5.5v6c0 1.4 3.1 2.5 7 2.5s7-1.1 7-2.5v-6"/><path d="M5 11.5v6c0 1.4 3.1 2.5 7 2.5s7-1.1 7-2.5v-6"/>',
  box: '<path d="m12 3.5 7.5 4v9L12 20.5l-7.5-4v-9l7.5-4Z"/><path d="m4.5 7.5 7.5 4 7.5-4M12 11.5v9"/>',
  key: '<circle cx="8" cy="15.5" r="4"/><path d="m11 12.5 8-8M16 7.5l2 2M18.5 5l2 2"/>',
  save: '<path d="M5 4h11l4 4v12H4V4h1Z"/><path d="M8 4v6h8V4M8 20v-6h8v6"/>',
  logout: '<path d="M14 4.5H5v15h9M18.5 12H9.5M15.2 8.7 18.5 12l-3.3 3.3"/>',
  external: '<path d="M14 4h6v6M20 4l-8.5 8.5M18 14v5.5H4.5v-14H10"/>',
  copy: '<rect x="8" y="8" width="11.5" height="11.5" rx="2"/><path d="M15.5 8V6a1.8 1.8 0 0 0-1.8-1.8H6A1.8 1.8 0 0 0 4.2 6v7.7A1.8 1.8 0 0 0 6 15.5h2"/>',
  clipboard: '<rect x="6" y="4.8" width="12" height="15.7" rx="2"/><path d="M9.2 4.8V3.2h5.6v1.6M9.2 9.2h5.6M9.2 13h5.6M9.2 16.8h3.6"/>',
  lock: '<rect x="5" y="10" width="14" height="10.5" rx="2"/><path d="M8.2 10V7.6a3.8 3.8 0 0 1 7.6 0V10"/>',
  arrowRight: '<path d="M4 12h15M14 7l5 5-5 5"/>',
  clock: '<circle cx="12" cy="12" r="8.5"/><path d="M12 7.5V12l3.2 2"/>',
  server: '<rect x="3.5" y="4" width="17" height="6" rx="1.5"/><rect x="3.5" y="14" width="17" height="6" rx="1.5"/><path d="M6.5 7h.01M6.5 17h.01M10 7h4M10 17h4"/>',
  wifiOff: '<path d="m3 3 18 18M8.5 8.5a10 10 0 0 1 11 1.5M5 10a10 10 0 0 1 1.7-1.3M8.2 13.5a6 6 0 0 1 7.6.8M11 17h.01"/>',
  star: '<path d="m12 3 2.7 5.5 6.1.9-4.4 4.3 1 6.1L12 16.9 6.6 19.8l1-6.1L3.2 9.4l6.1-.9L12 3Z"/>',
  heart: '<path d="M20.8 8.7c0 5-8.8 10.1-8.8 10.1S3.2 13.7 3.2 8.7A4.4 4.4 0 0 1 12 7.3a4.4 4.4 0 0 1 8.8 1.4Z"/>'
};
function svgIcon(name) {
  return `<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">${ICONS[name] || ICONS.box}</svg>`;
}
function hydrateIcons(root = document) {
  $$('[data-icon]', root).forEach((el) => {
    const name = el.getAttribute('data-icon');
    if (!name || el.dataset.hydrated === name) return;
    el.innerHTML = svgIcon(name);
    el.dataset.hydrated = name;
  });
}

/* ---------- styled select ---------- */
let niceMenu = null;
let niceOwner = null;
function ensureNiceMenu() {
  if (niceMenu) return niceMenu;
  niceMenu = document.createElement('div');
  niceMenu.className = 'nice-menu';
  niceMenu.setAttribute('role', 'listbox');
  document.body.appendChild(niceMenu);
  return niceMenu;
}
function closeNiceMenu() {
  if (niceMenu) niceMenu.classList.remove('open');
  if (niceOwner) {
    const trigger = niceOwner.parentElement && niceOwner.parentElement.querySelector('.nice-trigger');
    if (trigger) trigger.classList.remove('open');
  }
  niceOwner = null;
}
function enhanceSelects(root = document) {
  $$('select.select', root).forEach((select) => {
    if (select.dataset.nice || select.multiple) return;
    select.dataset.nice = '1';
    const width = select.style.width || '';
    const wrapper = document.createElement('div');
    wrapper.className = 'nice-select';
    if (width) wrapper.style.width = width;
    select.parentNode.insertBefore(wrapper, select);
    wrapper.appendChild(select);
    select.classList.add('nice-native');
    select.tabIndex = -1;
    const trigger = document.createElement('button');
    trigger.type = 'button';
    trigger.className = 'nice-trigger';
    trigger.innerHTML = `<span class="nice-label"></span>${icon('chevron')}`;
    wrapper.appendChild(trigger);
    const sync = () => {
      const option = select.options[select.selectedIndex];
      trigger.querySelector('.nice-label').textContent = option ? option.textContent : '';
      trigger.disabled = select.disabled;
    };
    sync();
    select.addEventListener('change', sync);
    trigger.addEventListener('click', (event) => {
      event.stopPropagation();
      if (niceOwner === select) { closeNiceMenu(); return; }
      openNiceSelect(select, trigger);
    });
  });
}
function openNiceSelect(select, trigger) {
  closeNiceMenu();
  const menu = ensureNiceMenu();
  menu.innerHTML = '';
  const options = Array.from(select.options);
  options.forEach((option, index) => {
    const item = document.createElement('button');
    item.type = 'button';
    item.className = 'nice-option' + (index === select.selectedIndex ? ' active' : '');
    const text = document.createElement('span');
    text.textContent = option.textContent;
    item.appendChild(text);
    if (index === select.selectedIndex) {
      const check = document.createElement('span');
      check.className = 'nice-check';
      check.innerHTML = icon('check');
      item.appendChild(check);
    }
    item.addEventListener('click', () => {
      select.selectedIndex = index;
      select.dispatchEvent(new Event('change', { bubbles: true }));
      closeNiceMenu();
    });
    menu.appendChild(item);
  });
  const rect = trigger.getBoundingClientRect();
  const width = Math.max(rect.width, select.id === 'setting-java' ? 360 : 190);
  menu.style.width = width + 'px';
  menu.classList.add('open');
  const height = menu.offsetHeight;
  let top = rect.bottom + 6;
  if (top + height > window.innerHeight - 8 && rect.top - height - 6 > 8) top = rect.top - height - 6;
  menu.style.top = top + 'px';
  menu.style.left = Math.max(8, Math.min(rect.left, window.innerWidth - width - 8)) + 'px';
  niceOwner = select;
  trigger.classList.add('open');
}
document.addEventListener('pointerdown', (event) => {
  if (!event.target.closest('.nice-select') && !event.target.closest('.nice-menu')) closeNiceMenu();
  if (state.jobCenterOpen && !event.target.closest('#job-dock') && !event.target.closest('#job-toggle')) {
    state.jobCenterOpen = false;
    renderJobs();
  }
});
window.addEventListener('resize', closeNiceMenu);
window.addEventListener('scroll', (event) => {
  if (event.target instanceof Element && event.target.closest('.nice-menu, .nice-select')) return;
  closeNiceMenu();
}, true);
document.addEventListener('keydown', (event) => { if (event.key === 'Escape') closeNiceMenu(); });
function offlineAvatar(account) {
  let hash = 0;
  const seed = String((account && (account.id || account.name)) || 'steve');
  for (let i = 0; i < seed.length; i++) hash = (hash * 31 + seed.charCodeAt(i)) | 0;
  return Math.abs(hash) % 2 === 0 ? '/assets/steve.png' : '/assets/alex.png';
}
function avatarImageSrc(account) {
  if (!account) return '/assets/steve.png';
  if ((account.type === 'microsoft' || account.type === 'thirdparty') && account.id) {
    return `https://minotar.net/helm/${encodeURIComponent(account.id)}/160.png`;
  }
  return offlineAvatar(account);
}
function avatarHtml(account, cls = '') {
  if (!account) return `<span class="avatar ${cls} avatar-empty">${icon('user')}</span>`;
  return `<span class="avatar ${cls}"><img data-avatar src="${attr(avatarImageSrc(account))}" alt=""></span>`;
}
document.addEventListener('error', (event) => {
  const img = event.target;
  if (img instanceof HTMLImageElement && img.dataset.avatar) {
    delete img.dataset.avatar;
    img.src = '/assets/steve.png';
  }
}, true);

async function api(path, options = {}) {
  const headers = { 'X-UCL-Token': TOKEN, 'Accept': 'application/json' };
  if (options.body !== undefined) headers['Content-Type'] = 'application/json';
  const res = await fetch(path, {
    method: options.method || 'GET',
    headers,
    body: options.body === undefined ? undefined : JSON.stringify(options.body),
    keepalive: (options.method || 'GET') !== 'GET'
  });
  let data = null;
  try { data = await res.json(); } catch { }
  if (!res.ok) throw new Error((data && data.error) || `HTTP ${res.status}`);
  return data;
}

function toast(message, kind = 'ok', title = '') {
  const root = $('#toast-root');
  const el = document.createElement('div');
  el.className = 'toast ' + (kind === 'error' ? 'error' : '');
  el.innerHTML = `${icon(kind === 'error' ? 'alert' : 'check')}<div>${title ? `<b>${esc(title)}</b> · ` : ''}${esc(message)}</div>`;
  root.appendChild(el);
  hydrateIcons(el);
  setTimeout(() => { el.classList.add('out'); setTimeout(() => el.remove(), 200); }, kind === 'error' ? 6000 : 3000);
}

/* ---------- init ---------- */
async function init() {
  try {
    const data = await api('/api/bootstrap');
    state.config = data.config || {};
    state.accounts = (data.accounts && data.accounts.accounts) || [];
    state.activeAccount = state.accounts.find((a) => a.active) || null;
    state.instances = data.instances || [];
    state.java = data.java || [];
    state.latest = data.latest || {};
    state.running = data.running || {};
    state.selectedInstanceId = state.config.lastInstance || (state.instances[0] && state.instances[0].id) || '';
    if (!['blue', 'mint', 'violet', 'sunset', 'gradient'].includes(state.config.accent)) state.config.accent = 'blue';
    applyTheme();
    const page = (location.hash || '#home').replace('#', '');
    state.page = PAGES[page] ? page : 'home';
    state.booted = true;
    renderShell();
    renderView();
    loadVersions();
    resumeJobs();
    pollRunning();
    setInterval(() => api('/api/heartbeat', { method: 'POST' }).catch(() => {}), 20000);
    setInterval(pollRunning, 5000);
  } catch (error) {
    $('#view').innerHTML = `<div class="empty-state"><span data-icon="alert"></span><b>启动器核心没有响应</b><p>${esc(error.message)}</p><button class="btn btn-primary" data-action="reload-app">重试</button></div>`;
    hydrateIcons($('#view'));
  }
}

function renderShell() {
  $$('.nav-item').forEach((el) => el.classList.toggle('active', el.dataset.nav === state.page));
  const meta = PAGES[state.page];
  $('#page-title').textContent = meta.title;
  $('#page-subtitle').textContent = meta.subtitle;
  $('#instance-badge').textContent = String(state.instances.length);
  updateAccountMini();
}
function updateAccountMini() {
  const a = state.activeAccount;
  const av = $('#mini-avatar');
  if (av) av.innerHTML = a ? `<img data-avatar src="${attr(avatarImageSrc(a))}" alt="">` : icon('user');
  $('#mini-name').textContent = a ? a.name : '未登录';
  $('#mini-type').textContent = a ? accountTypeName(a.type) : '点击添加账户';
  hydrateIcons($('#account-mini'));
}
function pageTransition(fn) {
  if (document.startViewTransition && !prefersReduced()) {
    const t = document.startViewTransition(() => { fn(); hydrateIcons($('#view')); });
    t.finished.catch(() => {});
  } else {
    fn();
    hydrateIcons($('#view'));
    const view = $('#view');
    view.classList.remove('page-enter');
    void view.offsetWidth;
    view.classList.add('page-enter');
  }
  $('#view').scrollTop = 0;
}
function navigate(page, push = true) {
  if (!PAGES[page]) page = 'home';
  const changed = state.page !== page;
  state.page = page;
  if (push && location.hash !== '#' + page) history.pushState({ page }, '', '#' + page);
  renderShell();
  if (changed || !state.booted) pageTransition(renderView);
  if (page === 'home') updateAccountMini();
  if (page === 'download' && !state.versions.length) loadVersions();
}
function renderView() {
  closeNiceMenu();
  const view = $('#view');
  if (!state.booted) { view.innerHTML = `<div class="loading-page"><span class="spinner"></span>正在读取本地配置</div>`; return; }
  switch (state.page) {
    case 'download': view.innerHTML = downloadHTML(); break;
    case 'instances': view.innerHTML = instancesHTML(); break;
    case 'content': view.innerHTML = contentHTML(); if (!state.discover.hits.length) loadContent(true); break;
    case 'settings': view.innerHTML = settingsHTML(); break;
    default: view.innerHTML = homeHTML(); break;
  }
  hydrateIcons(view);
  enhanceSelects(view);
  if (state.page === 'download') updateVersionList();
}

async function loadVersions(force = false) {
  if (state.versionsLoading && !force) return;
  const seq = ++state.versionSeq;
  state.versionsLoading = true;
  if (state.page === 'download') updateVersionList();
  try {
    const data = await api('/api/versions?snapshots=true', { method: 'GET' });
    if (seq !== state.versionSeq) return;
    state.versions = data.versions || [];
    state.latest = data.latest || state.latest;
    state.versionWarning = data.warning || '';
    state.versionSource = data.source || '';
    state.versionsLoaded = true;
  } catch (error) {
    if (seq !== state.versionSeq) return;
    state.versionWarning = error.message;
    state.versionsLoaded = true;
  } finally {
    if (seq === state.versionSeq) {
      state.versionsLoading = false;
      if (state.page === 'download') updateVersionList();
      const count = $('#version-count');
      if (count) count.textContent = state.versions.length + ' 个';
      if (state.page === 'home') {
        const detail = $('#home-detail');
        if (detail) { detail.outerHTML = homeDetailHTML(); hydrateIcons($('#view')); }
      }
    }
  }
}

/* ---------- home ---------- */
function selectedInstance() {
  return state.instances.find((i) => i.id === state.selectedInstanceId)
    || state.instances.find((i) => i.id === state.config.lastInstance)
    || state.instances[0] || null;
}
function homeHTML() {
  const inst = selectedInstance();
  const a = state.activeAccount;
  const running = inst && !!state.running[inst.id];
  return `
    <div class="panel launch-card">
      <button class="launch-part" data-action="accounts">
        ${avatarHtml(a)}
        <span class="launch-text"><small>当前账户</small><b>${a ? esc(a.name) : '未登录'}</b></span>
        <span class="badge ${a ? (a.type === 'microsoft' ? 'green' : a.type === 'thirdparty' ? 'amber' : '') : 'red'}">${a ? accountTypeName(a.type) : '点击登录'}</span>
      </button>
      <div class="launch-part launch-select">
        <label>游戏版本</label>
        <div class="dropdown" id="launch-dropdown">
          <button class="dropdown-toggle" data-action="toggle-launch-menu" ${state.instances.length ? '' : 'disabled'}>
            <span>${inst ? esc(inst.name) : '暂无版本'}</span>${icon('chevron')}
          </button>
          <div class="dropdown-menu">
            <div class="dropdown-head"><span>选择实例</span><small>${state.instances.length} 个</small></div>
            ${state.instances.length ? state.instances.map((i) => `<button class="dropdown-item ${inst && inst.id === i.id ? 'active' : ''}" data-action="pick-instance" data-id="${attr(i.id)}"><span class="dropdown-item-text"><b>${esc(i.name)}</b><small>${esc(i.version)} · ${instanceLoader(i)}</small></span><span class="dropdown-check">${inst && inst.id === i.id ? icon('check') : ''}</span></button>`).join('') : '<div class="dropdown-empty">没有游戏版本</div>'}
          </div>
        </div>
      </div>
      <button class="launch-start ${running ? 'running' : ''}" data-action="${running ? 'stop' : 'launch'}" ${inst ? `data-id="${attr(inst.id)}"` : 'disabled'}>
        ${icon(running ? 'stop' : 'play')}<span>${running ? '停止游戏' : '启动游戏'}</span>
      </button>
    </div>
    <div class="home-meta">
      <span>${icon('cpu')}${state.java.length ? 'Java ' + state.java[state.java.length - 1].major : 'Java 自动'}</span>
      <span>${icon('globe')}${esc(state.versionSource || '版本清单')}</span>
      <span>${icon('layers')}${state.instances.length} 个实例</span>
      ${inst ? `<span>${icon('folder')}${esc(inst.gameDir)}</span>` : '<span>还没有实例，先在“下载”或“实例”中添加</span>'}
    </div>
    <div class="home-columns">
      <div class="panel list-panel">
        <div class="panel-head"><h2>已有版本</h2><button class="btn btn-ghost btn-sm" data-action="download-page">${icon('plus')} 新建</button></div>
        <div class="instance-list">
          ${state.instances.length ? state.instances.map(instanceRowHTML).join('') : `
            <div class="empty"><span data-icon="layers"></span><b>没有游戏版本</b>从“下载”安装版本，或添加已有客户端目录
              <div style="margin-top:10px"><button class="btn btn-primary btn-sm" data-action="download-page">${icon('download')} 下载游戏</button>
              <button class="btn btn-sm" data-action="import-client">${icon('folder')} 添加目录</button></div>
            </div>`}
        </div>
      </div>
      <div id="home-detail">${homeDetailHTML()}</div>
    </div>
    <div class="quick-grid">
      <button class="quick-btn" data-action="create-instance">${icon('plus')}<span><b>新建实例</b><small>选择版本和加载器</small></span></button>
      <button class="quick-btn" data-action="import-client">${icon('folder')}<span><b>添加客户端目录</b><small>导入已有的 .minecraft</small></span></button>
      <button class="quick-btn" data-action="download-page">${icon('download')}<span><b>下载游戏</b><small>BMCLAPI 或官方源</small></span></button>
      <button class="quick-btn" data-action="nav-settings">${icon('settings')}<span><b>启动器设置</b><small>Java、内存、下载源</small></span></button>
    </div>`;
}
function instanceRowHTML(inst) {
  const active = selectedInstance() && selectedInstance().id === inst.id;
  const running = !!state.running[inst.id];
  return `<button class="instance-row ${active ? 'active' : ''}" data-action="select-instance" data-id="${attr(inst.id)}">
    <span class="avatar">${inst.icon ? `<img src="/api/instances/${encodeURIComponent(inst.id)}/icon?token=${encodeURIComponent(TOKEN)}" alt="">` : icon('box')}</span>
    <span class="instance-row-text"><b>${esc(inst.name)}</b><small>${esc(inst.version)} · ${instanceLoader(inst)} · ${timeAgo(inst.lastPlayed)}</small></span>
    <span class="instance-row-status ${running ? 'on' : ''}" title="${running ? '运行中' : ''}"></span>
  </button>`;
}
function homeDetailHTML() {
  const inst = selectedInstance();
  if (!inst) {
    return `<div class="panel detail-panel"><div class="empty"><span data-icon="box"></span><b>选择或创建一个版本</b>这里会显示游戏目录、加载器和模组数量</div></div>`;
  }
  const running = !!state.running[inst.id];
  return `<div class="panel detail-panel">
    <div class="detail-title">
      <span class="avatar">${inst.icon ? `<img src="/api/instances/${encodeURIComponent(inst.id)}/icon?token=${encodeURIComponent(TOKEN)}" alt="">` : icon('box')}</span>
      <div><h2>${esc(inst.name)}</h2><p>${esc(inst.version)} · ${instanceLoader(inst)}${inst.loaderVersion ? ' ' + esc(inst.loaderVersion) : ''}</p></div>
    </div>
    <div class="kv">
      <div><span>账户</span><b>${state.activeAccount ? esc(state.activeAccount.name) : '未登录'}</b></div>
      <div><span>游戏目录</span><b>${esc(inst.gameDir)}</b></div>
      <div><span>版本隔离</span><b>${inst.isolated ? '已隔离' : '共享目录'}</b></div>
      <div><span>模组</span><b>${inst.modsCount || 0} 个</b></div>
      <div><span>上次启动</span><b>${timeAgo(inst.lastPlayed)}</b></div>
      <div><span>状态</span><b>${running ? '运行中' : '未运行'}</b></div>
    </div>
    <div class="detail-actions">
      <button class="btn btn-sm" data-action="edit-instance" data-id="${attr(inst.id)}">${icon('edit')} 编辑</button>
      <button class="btn btn-sm" data-action="open-instance-folder" data-id="${attr(inst.id)}">${icon('folder')} 目录</button>
      <button class="btn btn-sm" data-action="instance-detail" data-id="${attr(inst.id)}">${icon('archive')} 文件</button>
      <button class="btn btn-sm" data-action="launch" data-id="${attr(inst.id)}" ${running ? 'disabled' : ''}>${icon('play')} 启动</button>
    </div>
  </div>`;
}

/* ---------- download ---------- */
function downloadHTML() {
  return `
    <div class="toolbar">
      <label class="search-box">${icon('search')}<input id="version-search" value="${attr(state.versionQuery)}" placeholder="搜索版本，例如 1.21.8" autocomplete="off"></label>
      <div class="segmented" id="version-filter">
        <button data-action="version-filter" data-filter="release" class="${state.versionFilter === 'release' ? 'active' : ''}">正式版</button>
        <button data-action="version-filter" data-filter="snapshot" class="${state.versionFilter === 'snapshot' ? 'active' : ''}">快照</button>
        <button data-action="version-filter" data-filter="all" class="${state.versionFilter === 'all' ? 'active' : ''}">全部</button>
      </div>
      <button class="btn btn-sm" data-action="refresh-versions">${icon('refresh')} 刷新清单</button>
    </div>
    ${state.versionWarning ? `<div class="warning">${icon('alert')}<div><b>版本清单读取不完整</b><br>${esc(state.versionWarning)}<br><button class="btn btn-sm" style="margin-top:6px" data-action="set-bmcl">切换到 BMCLAPI 镜像</button> <button class="btn btn-sm" style="margin-top:6px" data-action="refresh-versions">重试</button></div></div>` : ''}
    <div class="source-line ${state.versionWarning ? 'bad' : ''}"><i></i>下载源：${esc(state.versionSource || '读取中')} · ${state.versions.length} 个版本${state.versionsLoading ? ' · 正在刷新' : ''}</div>
    <div class="panel version-table" id="version-table">${versionRowsHTML()}</div>
    <div class="load-more"><button class="btn btn-sm" data-action="download-more" id="download-more" style="display:none">加载更多</button></div>`;
}
function filteredVersions() {
  const q = state.versionQuery.trim().toLowerCase();
  return state.versions.filter((v) => {
    if (state.versionFilter === 'release' && v.type !== 'release' && !v.installed) return false;
    if (state.versionFilter === 'snapshot' && v.type !== 'snapshot' && !v.installed) return false;
    return !q || String(v.id).toLowerCase().includes(q);
  });
}
function versionRowsHTML() {
  if (state.versionsLoading && !state.versions.length) return `<div class="loading-line"><span class="spinner"></span>正在读取版本清单</div>`;
  if (!state.versionsLoaded) return `<div class="loading-line"><span class="spinner"></span>正在读取版本清单</div>`;
  const list = filteredVersions();
  if (!list.length) return `<div class="empty"><span data-icon="search"></span><b>没有匹配的版本</b>${state.versionWarning ? '可以切换下载源后重试' : '换一个关键词试试'}</div>`;
  return list.slice(0, state.versionLimit).map((v) => {
    const installed = !!v.installed;
    const loader = v.loader || 'vanilla';
    return `<div class="version-row">
      <div class="version-id">${esc(v.id)} ${installed ? '<span class="badge green">已安装</span>' : ''}${v.local && !installed ? '<span class="badge">本地</span>' : ''}</div>
      <div class="version-type">${v.type === 'snapshot' ? '快照' : v.type === 'old_beta' ? '旧 Beta' : v.type === 'old_alpha' ? '旧 Alpha' : '正式版'}</div>
      <div class="version-date">${fmtDate(v.releaseTime)}</div>
      <div class="version-action"><button class="btn btn-sm ${installed ? '' : 'btn-primary'}" data-action="install-version" data-id="${attr(v.id)}" data-loader="${attr(loader)}">${installed ? '新建实例' : '安装'}</button></div>
    </div>`;
  }).join('');
}
function updateVersionList() {
  const table = $('#version-table');
  if (table) { table.innerHTML = versionRowsHTML(); hydrateIcons(table); }
  const source = $('.source-line');
  if (source) source.innerHTML = `<i></i>下载源：${esc(state.versionSource || '读取中')} · ${state.versions.length} 个版本${state.versionsLoading ? ' · 正在刷新' : ''}`;
  const more = $('#download-more');
  if (more) more.style.display = filteredVersions().length > state.versionLimit ? '' : 'none';
}

/* ---------- instances ---------- */
function instancesHTML() {
  return `
    <div class="page-toolbar">
      <h2>${state.instances.length} 个实例</h2>
      <button class="btn btn-sm" data-action="import-client">${icon('folder')} 添加客户端目录</button>
      <button class="btn btn-primary btn-sm" data-action="create-instance">${icon('plus')} 新建实例</button>
    </div>
    ${state.instances.length ? `<div class="panel instances-table">${state.instances.map(instanceItemHTML).join('')}</div>` : `
      <div class="empty-state"><span data-icon="layers"></span><b>还没有实例</b>
        <p>可以安装一个新版本，也可以直接导入 PCL2 / 官方启动器已有的 .minecraft 客户端目录。</p>
        <button class="btn btn-primary btn-sm" data-action="create-instance">${icon('plus')} 新建实例</button>
        <button class="btn btn-sm" data-action="import-client">${icon('folder')} 添加已有客户端</button>
      </div>`}`;
}
function instanceItemHTML(inst) {
  const running = !!state.running[inst.id];
  return `<div class="instance-item">
    <span class="instance-avatar">${inst.icon ? `<img src="/api/instances/${encodeURIComponent(inst.id)}/icon?token=${encodeURIComponent(TOKEN)}" alt="">` : icon('box')}</span>
    <div class="instance-main">
      <div class="instance-name">${esc(inst.name)} ${running ? '<span class="badge green">运行中</span>' : ''}</div>
      <div class="instance-path">${esc(inst.gameDir)}</div>
      <div class="instance-badges">
        <span class="badge">${esc(inst.version)}</span>
        <span class="badge">${instanceLoader(inst)}${inst.loaderVersion ? ' ' + esc(inst.loaderVersion) : ''}</span>
        <span class="badge ${inst.isolated ? 'blue' : ''}">${inst.isolated ? '版本隔离' : '共享目录'}</span>
        <span class="badge">${inst.modsCount || 0} 模组</span>
        <span class="badge">${timeAgo(inst.lastPlayed)}</span>
      </div>
    </div>
    <div class="instance-actions">
      <button class="btn btn-sm ${running ? '' : 'btn-primary'}" data-action="${running ? 'stop' : 'launch'}" data-id="${attr(inst.id)}">${icon(running ? 'stop' : 'play')} ${running ? '停止' : '启动'}</button>
      <button class="icon-btn" data-action="instance-detail" data-id="${attr(inst.id)}" title="文件与日志">${icon('layers')}</button>
      <button class="icon-btn" data-action="edit-instance" data-id="${attr(inst.id)}" title="编辑">${icon('edit')}</button>
      <button class="icon-btn" data-action="open-instance-folder" data-id="${attr(inst.id)}" title="打开目录">${icon('folder')}</button>
      <button class="icon-btn" data-action="delete-instance" data-id="${attr(inst.id)}" title="删除">${icon('trash')}</button>
    </div>
  </div>`;
}

/* ---------- modal primitives ---------- */
let activeOverlay = null;
function openModal(content, cls = '') {
  closeModal(true);
  const overlay = document.createElement('div');
  overlay.className = 'modal-overlay';
  overlay.innerHTML = `<div class="modal ${cls}" role="dialog" aria-modal="true">${content}</div>`;
  document.body.appendChild(overlay);
  activeOverlay = overlay;
  hydrateIcons(overlay);
  enhanceSelects(overlay);
  overlay.addEventListener('click', (e) => { if (e.target === overlay) closeModal(); });
  return overlay;
}
function closeModal(immediate = false) {
  closeNiceMenu();
  if (!activeOverlay) return;
  const overlay = activeOverlay;
  activeOverlay = null;
  if (immediate) { overlay.remove(); return; }
  overlay.classList.add('closing');
  const modal = overlay.querySelector('.modal');
  if (modal) modal.style.animation = 'modalOut .14s ease both';
  setTimeout(() => overlay.remove(), 150);
}
function modalHead(title, subtitle = '') {
  return `<div class="modal-head"><div><h2>${esc(title)}</h2>${subtitle ? `<p>${esc(subtitle)}</p>` : ''}</div><button class="modal-close" data-action="close-modal">${icon('x')}</button></div>`;
}
function loadingModal(text = '正在读取') {
  return `${modalHead('请稍候')}<div class="modal-body"><div class="loading-line"><span class="spinner"></span>${esc(text)}</div></div>`;
}

/* ---------- create instance ---------- */
function openCreateInstance(prefillVersion = '', prefillLoader = 'vanilla') {
  if (!state.versions.length) loadVersions();
  const releases = state.versions.filter((v) => v.type === 'release').slice(0, 120);
  const version = prefillVersion || state.latest.release || (releases[0] && releases[0].id) || '';
  let optionVersions = state.versions.slice(0, 160);
  if (version && !optionVersions.some((v) => v.id === version)) {
    optionVersions = [{ id: version, type: 'release' }, ...optionVersions];
  }
  const options = optionVersions.map((v) =>
    `<option value="${attr(v.id)}" ${v.id === version ? 'selected' : ''}>${esc(v.id)}${v.installed ? ' · 已安装' : ''}</option>`
  ).join('');
  openModal(`${modalHead('新建实例', '创建后首次启动会自动补齐客户端、依赖库和资源')}
    <form id="create-instance-form">
      <div class="modal-body">
        <div class="field"><label>实例名称</label><input class="input" id="create-name" value="${attr(version ? 'Minecraft ' + version : '新的实例')}" maxlength="60" autocomplete="off"></div>
        <div class="form-grid">
          <div class="field"><label>Minecraft 版本</label><select class="select" id="create-version">${options || `<option value="${attr(version)}">${esc(version || '暂无版本')}</option>`}</select></div>
          <div class="field"><label>加载器</label><select class="select" id="create-loader">
            <option value="vanilla" ${prefillLoader === 'vanilla' ? 'selected' : ''}>Vanilla</option><option value="fabric" ${prefillLoader === 'fabric' ? 'selected' : ''}>Fabric</option><option value="quilt" ${prefillLoader === 'quilt' ? 'selected' : ''}>Quilt</option><option value="forge" ${prefillLoader === 'forge' ? 'selected' : ''}>Forge（自动安装）</option>
          </select></div>
        </div>
        <label class="checkline" id="fabric-api-row" style="margin-top:9px;${prefillLoader === 'fabric' ? '' : 'display:none'}"><input type="checkbox" id="create-fabric-api" checked> 同时安装 Fabric API（推荐）</label>
        <div class="field" style="margin-top:10px"><label>描述（可选）</label><input class="input" id="create-desc" placeholder="这个世界的一句话" maxlength="120" autocomplete="off"></div>
        <div class="note">${icon('info')}<div>新实例使用独立游戏目录（版本隔离），不会影响其他版本。Forge 首次启动时会运行官方安装器。</div></div>
      </div>
      <div class="modal-foot"><button type="button" class="btn" data-action="close-modal">取消</button><button type="submit" class="btn btn-primary">${icon('plus')} 创建</button></div>
    </form>`, 'narrow');
}
async function submitCreateInstance(e) {
  e.preventDefault();
  const name = $('#create-name').value.trim() || '新的实例';
  const version = $('#create-version').value;
  const loader = $('#create-loader').value;
  const description = $('#create-desc').value.trim();
  const installFabricApi = loader === 'fabric' && $('#create-fabric-api') && $('#create-fabric-api').checked;
  if (!version) { toast('没有可用的版本清单，请先在设置中切换下载源', 'error'); return; }
  const btn = e.target.querySelector('button[type="submit"]');
  btn.disabled = true;
  try {
    const data = await api('/api/instances', { method: 'POST', body: { name, version, loader, description } });
    closeModal();
    await refreshInstances();
    state.selectedInstanceId = data.instance.id;
    await api('/api/config', { method: 'POST', body: { lastInstance: data.instance.id } }).catch(() => {});
    navigate('home');
    toast('实例已创建，开始准备游戏文件', 'ok');
    const prep = await api(`/api/instances/${encodeURIComponent(data.instance.id)}/prepare`, { method: 'POST' });
    trackJob(prep.job, { install: true });
    if (installFabricApi) {
      const fapi = await api('/api/install/fabric-api', { method: 'POST', body: { instanceId: data.instance.id } });
      trackJob(fapi.job, { install: true });
    }
  } catch (error) {
    toast(error.message, 'error');
    btn.disabled = false;
  }
}

/* ---------- import existing client ---------- */
function openImportClient() {
  state.importData = { path: '', versions: [], selected: [], isolated: true, loading: false };
  openModal(`${modalHead('添加客户端目录', '导入已有的 .minecraft / PCL2 客户端')}
    <div class="modal-body">
      <div class="field"><label>客户端目录</label>
        <div class="row gap-6"><input class="input" id="import-path" placeholder="例如 C:\\Users\\你\\AppData\\Roaming\\.minecraft" style="flex:1" autocomplete="off"><button class="btn" data-action="browse-folder">${icon('folder')} 浏览</button><button class="btn" data-action="scan-folder">${icon('refresh')} 扫描</button></div>
      </div>
      <div class="field"><label>实例名称</label><input class="input" id="import-name" placeholder="留空则使用版本名称；多选时会追加版本号" autocomplete="off"></div>
      <div id="import-scan-result"></div>
      <label class="checkline" style="margin-top:9px"><input type="checkbox" id="import-isolated" checked> 版本隔离（使用 versions/版本名 作为该实例的游戏目录）</label>
      <div class="note" style="margin-top:9px">${icon('info')}<div>默认把游戏数据放在该版本的 versions 文件夹里，模组、存档、配置与其他版本互不影响，不会额外套一层 instances。取消勾选才使用原 .minecraft 共享目录。</div></div>
    </div>
    <div class="modal-foot"><button class="btn" data-action="close-modal">取消</button><button class="btn btn-primary" data-action="import-submit" id="import-submit" disabled>${icon('download')} 导入</button></div>`, 'narrow');
}
async function scanFolder() {
  const path = ($('#import-path') && $('#import-path').value.trim()) || '';
  if (!path) { toast('请输入客户端目录', 'error'); return; }
  const box = $('#import-scan-result');
  if (box) box.innerHTML = `<div class="loading-line"><span class="spinner"></span>正在扫描 versions 文件夹</div>`;
  try {
    const data = await api('/api/scan-root', { method: 'POST', body: { path } });
    state.importData = { ...state.importData, path: data.path, versions: data.versions || [], selected: (data.versions[0] ? [data.versions[0].id] : []), loading: false };
    paintImportVersions();
    const submit = $('#import-submit');
    if (submit) submit.disabled = !state.importData.selected.length;
  } catch (error) {
    if (box) box.innerHTML = `<div class="warning">${icon('alert')}<div>${esc(error.message)}</div></div>`;
    const submit = $('#import-submit');
    if (submit) submit.disabled = true;
  }
}
function paintImportVersions() {
  const box = $('#import-scan-result');
  if (!box) return;
  if (!state.importData.versions.length) {
    box.innerHTML = `<div class="warning">${icon('alert')}<div>没有找到可导入的版本。</div></div>`;
    return;
  }
  const selected = new Set(state.importData.selected);
  box.innerHTML = `<div class="import-head"><span>选择要导入的版本</span><button class="btn btn-sm" data-action="import-select-all">${selected.size === state.importData.versions.length ? '取消全选' : '全选'}</button></div>
    <div class="import-versions">${state.importData.versions.map((v) => `<button class="import-version ${selected.has(v.id) ? 'active' : ''}" data-action="import-select" data-id="${attr(v.id)}"><span class="check-dot">${selected.has(v.id) ? icon('check') : ''}</span><span class="import-version-name">${esc(v.id)}</span><small>${loaderName(v.loader)}${v.type === 'snapshot' ? ' · 快照' : ''}</small></button>`).join('')}</div>`;
  hydrateIcons(box);
  const submit = $('#import-submit');
  if (submit) submit.disabled = selected.size === 0;
}
async function submitImport() {
  const path = ($('#import-path') && $('#import-path').value.trim()) || state.importData.path;
  const customName = ($('#import-name') && $('#import-name').value.trim()) || '';
  const isolated = $('#import-isolated') ? $('#import-isolated').checked : true;
  const chosen = state.importData.versions.filter((v) => state.importData.selected.includes(v.id));
  if (!path || !chosen.length) { toast('请选择目录并至少勾选一个版本', 'error'); return; }
  const btn = $('#import-submit');
  if (btn) btn.disabled = true;
  let last = null;
  try {
    for (const version of chosen) {
      const name = customName ? (chosen.length > 1 ? `${customName} - ${version.id}` : customName) : version.id;
      const data = await api('/api/instances/import', { method: 'POST', body: { name, path, version: version.id, loader: version.loader, isolated } });
      last = data.instance;
    }
    closeModal();
    await refreshInstances();
    if (last) {
      state.selectedInstanceId = last.id;
      await api('/api/config', { method: 'POST', body: { lastInstance: last.id } }).catch(() => {});
    }
    navigate('home');
    toast(`已导入 ${chosen.length} 个客户端`, 'ok');
  } catch (error) {
    toast(error.message, 'error');
    if (btn) btn.disabled = false;
  }
}

/* ---------- edit instance ---------- */
function openEditInstance(id) {
  const inst = state.instances.find((i) => i.id === id);
  if (!inst) return;
  const javaOptions = `<option value="">自动选择</option>` + state.java.map((j) =>
    `<option value="${attr(j.path)}" ${inst.javaPath === j.path ? 'selected' : ''}>Java ${j.major} · ${esc(j.path)}</option>`
  ).join('');
  const memory = Number(inst.memoryMb) > 0 ? Math.round(Number(inst.memoryMb) / 1024) : 0;
  openModal(`${modalHead('编辑实例', inst.version + ' · ' + instanceLoader(inst))}
    <form id="edit-instance-form" data-id="${attr(inst.id)}">
      <div class="modal-body">
        <div class="field"><label>实例名称</label><input class="input" id="edit-name" value="${attr(inst.name)}" maxlength="60" autocomplete="off"></div>
        <div class="field"><label>描述</label><input class="input" id="edit-desc" value="${attr(inst.description || '')}" maxlength="120" autocomplete="off"></div>
        <div class="form-grid">
          <div class="field"><label>Java（留空自动）</label><select class="select" id="edit-java">${javaOptions}</select></div>
          <div class="field"><label>内存（0 使用全局）</label><select class="select" id="edit-memory">
            ${[0, 2, 4, 6, 8, 12, 16, 24, 32].map((g) => `<option value="${g}" ${g === memory ? 'selected' : ''}>${g === 0 ? '使用全局设置' : g + ' GB'}</option>`).join('')}
          </select></div>
        </div>
        <div class="kv" style="margin-top:12px">
          <div><span>游戏目录</span><b>${esc(inst.gameDir)}</b></div>
          <div><span>版本根目录</span><b>${esc(inst.root)}</b></div>
          <div><span>版本隔离</span><b>${inst.isolated ? '开启' : '关闭'}</b></div>
          <div><span>加载器</span><b>${instanceLoader(inst)}${inst.loaderVersion ? ' ' + esc(inst.loaderVersion) : ''}</b></div>
        </div>
        <div class="note" style="margin-top:10px">${icon('info')}<div>版本与隔离设置在导入 / 创建时确定，修改版本请新建实例，避免存档与模组错位。</div></div>
      </div>
      <div class="modal-foot"><button type="button" class="btn" data-action="close-modal">取消</button><button type="submit" class="btn btn-primary">${icon('save')} 保存</button></div>
    </form>`, 'narrow');
}
async function submitEditInstance(e) {
  e.preventDefault();
  const id = e.target.dataset.id;
  const body = {
    name: $('#edit-name').value.trim() || '未命名实例',
    description: $('#edit-desc').value.trim(),
    javaPath: $('#edit-java').value,
    memoryMb: Number($('#edit-memory').value) * 1024
  };
  try {
    await api('/api/instances/' + encodeURIComponent(id), { method: 'PATCH', body });
    closeModal();
    await refreshInstances();
    renderView();
    toast('实例设置已保存', 'ok');
  } catch (error) { toast(error.message, 'error'); }
}

/* ---------- instance detail ---------- */
const DETAIL_TABS = [
  { id: 'mods', label: '模组', type: 'mod' },
  { id: 'resourcepacks', label: '资源包', type: 'resourcepack' },
  { id: 'shaderpacks', label: '光影', type: 'shader' },
  { id: 'datapacks', label: '数据包', type: 'datapack' },
  { id: 'logs', label: '日志', type: 'log' }
];
async function openInstanceDetail(id, tab = 'mods') {
  state.detail = { id, tab, instance: null, files: [], log: '' };
  openModal(loadingModal('正在打开实例'), 'wide');
  try {
    const data = await api('/api/instances/' + encodeURIComponent(id));
    state.detail.instance = data.instance;
    await loadDetailTab(tab, true);
  } catch (error) {
    openModal(`${modalHead('无法打开实例')}<div class="modal-body"><div class="empty">${esc(error.message)}</div></div>`, 'narrow');
  }
}
async function loadDetailTab(tab, initial = false) {
  const d = state.detail;
  if (!d.instance) return;
  d.tab = tab;
  const meta = DETAIL_TABS.find((t) => t.id === tab) || DETAIL_TABS[0];
  try {
    if (meta.type === 'log') {
      const data = await api('/api/instances/' + encodeURIComponent(d.id) + '/log');
      d.log = data.log || '暂无日志';
    } else {
      const data = await api('/api/instances/' + encodeURIComponent(d.id) + '/content?type=' + encodeURIComponent(meta.type));
      d.files = data.files || [];
    }
  } catch (error) {
    if (meta.type === 'log') d.log = error.message; else d.files = [];
  }
  renderDetailModal();
}
function renderDetailModal() {
  if (!activeOverlay || !state.detail.instance) return;
  activeOverlay.querySelector('.modal').innerHTML = detailModalHTML();
  hydrateIcons(activeOverlay);
}
function detailModalHTML() {
  const d = state.detail, inst = d.instance;
  const meta = DETAIL_TABS.find((t) => t.id === d.tab) || DETAIL_TABS[0];
  return `${modalHead(inst.name, inst.version + ' · ' + instanceLoader(inst))}
    <div class="modal-body">
      <div class="detail-hero">
        <span class="instance-avatar">${inst.icon ? `<img src="/api/instances/${encodeURIComponent(inst.id)}/icon?token=${encodeURIComponent(TOKEN)}" alt="">` : icon('box')}</span>
        <div class="detail-hero-text"><h2>${esc(inst.name)}</h2><p>${esc(inst.gameDir)}</p></div>
        <button class="btn btn-sm" data-action="open-instance-folder" data-id="${attr(inst.id)}">${icon('folder')} 打开</button>
        <button class="btn btn-sm" data-action="edit-instance" data-id="${attr(inst.id)}">${icon('edit')} 编辑</button>
      </div>
      <div class="tabs">${DETAIL_TABS.map((t) => `<button class="tab ${d.tab === t.id ? 'active' : ''}" data-action="detail-tab" data-tab="${t.id}">${t.label}</button>`).join('')}</div>
      <div>${meta.type === 'log' ? `<div class="log-box">${esc(d.log || '暂无日志')}</div>` : detailFilesHTML(meta)}</div>
    </div>
    <div class="modal-foot"><span class="left">${inst.isolated ? '版本隔离已开启' : '共享游戏目录'}</span><button class="btn" data-action="close-modal">关闭</button></div>`;
}
function detailFilesHTML(meta) {
  const d = state.detail;
  if (!d.files.length) {
    return `<div class="empty"><span data-icon="${meta.type === 'mod' ? 'package' : meta.type === 'shader' ? 'sparkles' : meta.type === 'resourcepack' ? 'palette' : 'database'}"></span><b>还没有${meta.label}</b>可以从“内容”页安装到该实例</div>`;
  }
  return `<div class="file-list">${d.files.map((f) => `<div class="file-row">
    <span class="file-name"><b>${esc(f.displayName || f.name)}</b><span>${fmtBytes(f.size)} · ${f.enabled ? '已启用' : '已禁用'}</span></span>
    <span class="file-actions">
      <button class="icon-btn" data-action="detail-toggle" data-type="${meta.type}" data-name="${attr(f.name)}" title="${f.enabled ? '禁用' : '启用'}">${icon(f.enabled ? 'check' : 'lock')}</button>
      <button class="icon-btn" data-action="detail-remove" data-type="${meta.type}" data-name="${attr(f.name)}" title="删除">${icon('trash')}</button>
    </span>
  </div>`).join('')}</div>`;
}

/* ---------- content ---------- */
const CONTENT_TYPES = [
  { id: 'modpack', label: '整合包', icon: 'box' },
  { id: 'mod', label: '模组', icon: 'package' },
  { id: 'shader', label: '光影', icon: 'sparkles' },
  { id: 'resourcepack', label: '资源包', icon: 'palette' },
  { id: 'datapack', label: '数据包', icon: 'database' },
  { id: 'world', label: '地图', icon: 'map' }
];
function contentHTML() {
  const d = state.discover;
  return `
    <div class="content-tabs" id="content-tabs">
      ${CONTENT_TYPES.map((t) => `<button class="content-tab ${d.type === t.id ? 'active' : ''}" data-action="content-type" data-type="${t.id}">${icon(t.icon)}${t.label}</button>`).join('')}
      <div class="source-toggle">
        <button data-action="content-source" data-source="modrinth" class="${d.source === 'modrinth' ? 'active' : ''}">Modrinth</button>
        <button data-action="content-source" data-source="curseforge" class="${d.source === 'curseforge' ? 'active' : ''}">CurseForge</button>
      </div>
    </div>
    <div class="toolbar">
      <label class="search-box">${icon('search')}<input id="content-search" value="${attr(d.q)}" placeholder="搜索${typeName(d.type)}、作者或关键词" autocomplete="off"></label>
      <button class="btn btn-sm" data-action="content-refresh">${icon('refresh')} 刷新</button>
    </div>
    <div class="source-line" id="content-source-line"><i></i>${d.loading ? '正在搜索内容' : `找到 ${fmtNumber(d.total)} 个${typeName(d.type)}`} · ${d.source === 'curseforge' ? 'CurseForge' : 'Modrinth'}</div>
    <div class="card-grid" id="content-grid">${contentGridHTML()}</div>
    <div class="load-more"><button class="btn btn-sm" data-action="content-more" style="${d.hits.length < d.total ? '' : 'display:none'}">加载更多</button></div>`;
}
function contentGridHTML() {
  const d = state.discover;
  if (d.loading && !d.hits.length) return Array.from({ length: 6 }).map(() => `<div class="panel" style="height:190px"></div>`).join('');
  if (d.error && !d.hits.length) return `<div class="empty" style="grid-column:1/-1"><span data-icon="alert"></span><b>内容源连接失败</b>${esc(d.error)}<div style="margin-top:9px"><button class="btn btn-sm" data-action="content-refresh">重试</button></div></div>`;
  if (!d.hits.length) return `<div class="empty" style="grid-column:1/-1"><span data-icon="search"></span><b>没有找到内容</b>换一个关键词试试</div>`;
  return d.hits.map(contentCardHTML).join('');
}
function contentCardHTML(p) {
  const source = p.source === 'curseforge' ? 'curseforge' : 'modrinth';
  return `<article class="content-card" data-project-id="${attr(p.id)}" data-project-source="${source}" data-project-type="${attr(p.type || state.discover.type)}">
    <div class="card-cover">${(p.cover || p.icon) ? `<img src="${attr(p.cover || p.icon)}" alt="" loading="lazy">` : ''}
      <span class="card-source ${source === 'curseforge' ? 'cf' : ''}"><i></i>${source === 'curseforge' ? 'CURSEFORGE' : 'MODRINTH'}</span>
      <div class="card-icon">${p.icon ? `<img src="${attr(p.icon)}" alt="" loading="lazy">` : ''}</div>
    </div>
    <div class="card-body">
      <h3>${esc(p.title)}</h3>
      <p>${esc(stripText(p.description).slice(0, 140))}</p>
      <div class="card-meta"><span>${icon('download')}${fmtNumber(p.downloads)}</span><span class="author">${esc(p.author || '')}</span></div>
    </div>
  </article>`;
}
async function loadContent(reset = false) {
  const d = state.discover;
  if (reset) { d.offset = 0; d.hits = []; d.total = 0; d.error = ''; }
  d.loading = true;
  const seq = ++state.discoverSeq;
  paintContentGrid();
  const q = new URLSearchParams({
    source: d.source, type: d.type, q: d.q, offset: String(d.offset), limit: '18', sort: 'downloads'
  });
  try {
    const data = await api('/api/discover?' + q);
    if (seq !== state.discoverSeq) return;
    d.hits = reset ? (data.hits || []) : d.hits.concat(data.hits || []);
    d.total = Number(data.total || 0);
    d.error = '';
  } catch (error) {
    if (seq === state.discoverSeq) d.error = error.message;
  } finally {
    if (seq === state.discoverSeq) { d.loading = false; paintContentGrid(); }
  }
}
function paintContentGrid() {
  const grid = $('#content-grid');
  if (grid) { grid.innerHTML = contentGridHTML(); hydrateIcons(grid); }
  const line = $('#content-source-line');
  if (line) line.innerHTML = `<i></i>${state.discover.loading ? '正在搜索内容' : `找到 ${fmtNumber(state.discover.total)} 个${typeName(state.discover.type)}`} · ${state.discover.source === 'curseforge' ? 'CurseForge' : 'Modrinth'}`;
  const more = $('[data-action="content-more"]');
  if (more) more.style.display = state.discover.hits.length < state.discover.total ? '' : 'none';
}
function normalizeProject(source, raw) {
  if (source === 'curseforge') {
    return {
      source, title: raw.name || '未命名', description: stripText(raw.summary || ''),
      icon: (raw.logo && raw.logo.thumbnailUrl) || '', cover: '',
      downloads: raw.downloadCount || 0, follows: raw.thumbsUpCount || 0,
      categories: (raw.categories || []).map((c) => c.name).filter(Boolean),
      author: (raw.authors && raw.authors[0] && raw.authors[0].name) || 'CurseForge',
      url: (raw.links && raw.links.websiteUrl) || ''
    };
  }
  return {
    source, title: raw.title || '未命名', description: stripText(raw.description || ''),
    icon: raw.icon_url || '', cover: raw.featured_gallery || (Array.isArray(raw.gallery) && raw.gallery[0]) || '',
    downloads: raw.downloads || 0, follows: raw.followers || 0,
    categories: raw.categories || [], author: raw.author || 'Modrinth',
    url: raw.url || ''
  };
}
async function openProject(source, id, type) {
  openModal(loadingModal('正在读取项目'), 'medium');
  try {
    const instance = selectedInstance();
    let versionsUrl = `/api/project/versions?source=${encodeURIComponent(source)}&id=${encodeURIComponent(id)}`;
    if (type !== 'modpack' && instance) versionsUrl += `&gameVersion=${encodeURIComponent(instance.version)}&loader=${encodeURIComponent(instance.loader)}`;
    const [raw, versionData] = await Promise.all([
      api(`/api/project?source=${encodeURIComponent(source)}&id=${encodeURIComponent(id)}`),
      api(versionsUrl)
    ]);
    let versions = versionData.versions || [];
    if (!versions.length && type !== 'modpack' && instance) {
      const fallback = await api(`/api/project/versions?source=${encodeURIComponent(source)}&id=${encodeURIComponent(id)}`);
      versions = fallback.versions || [];
    }
    state.project = {
      source, id, type, project: normalizeProject(source, raw),
      versions, selected: (versions[0] && versions[0].id) || '',
      instanceId: instance ? instance.id : ''
    };
    renderProjectModal();
  } catch (error) {
    openModal(`${modalHead('读取失败')}<div class="modal-body"><div class="warning">${icon('alert')}<div>${esc(error.message)}</div></div></div>`, 'narrow');
  }
}
function projectVersionOptions() {
  const p = state.project;
  if (!p || !p.versions.length) return `<option value="">没有可用版本</option>`;
  return p.versions.slice(0, 60).map((v) =>
    `<option value="${attr(v.id)}" ${p.selected === v.id ? 'selected' : ''}>${esc(v.versionNumber || v.name)}${v.gameVersions && v.gameVersions.length ? ' · ' + esc(v.gameVersions.slice(0, 2).join(', ')) : ''}</option>`
  ).join('');
}
function projectModalHTML() {
  const p = state.project;
  if (!p) return '';
  const project = p.project;
  const isPack = p.type === 'modpack';
  const instanceOptions = state.instances.map((i) => `<option value="${attr(i.id)}" ${p.instanceId === i.id ? 'selected' : ''}>${esc(i.name)} · ${esc(i.version)} / ${loaderName(i.loader)}</option>`).join('');
  const selected = p.versions.find((v) => v.id === p.selected);
  return `${modalHead(isPack ? '安装整合包' : '安装' + typeName(p.type), p.source === 'curseforge' ? 'CurseForge' : 'Modrinth')}
    <div class="modal-body">
      <div class="project-head">
        <div class="project-icon">${project.icon ? `<img src="${attr(project.icon)}" alt="">` : ''}</div>
        <div class="project-title"><h2>${esc(project.title)}</h2>
          <div class="row gap-6"><span class="badge blue">${p.source === 'curseforge' ? 'CURSEFORGE' : 'MODRINTH'}</span><span class="badge">${typeName(p.type)}</span></div>
          <div class="project-desc" style="margin-bottom:0">${esc(project.description || '没有简介')}</div>
        </div>
      </div>
      <div class="project-stats">
        <div class="project-stat"><b>${fmtNumber(project.downloads)}</b><span>下载</span></div>
        <div class="project-stat"><b>${fmtNumber(project.follows)}</b><span>收藏</span></div>
        <div class="project-stat"><b>${p.versions.length}</b><span>可用版本</span></div>
      </div>
      <div class="install-box">
        ${isPack ? `<div class="field" style="margin-bottom:8px"><label>整合包版本</label><select class="select" id="project-version">${projectVersionOptions()}</select></div>
          <div class="note">${icon('info')}<div>会创建独立实例。Forge 整合包首次启动时自动安装 Forge；NeoForge 暂不支持。</div></div>`
        : state.instances.length ? `<div class="form-grid"><div class="field"><label>安装到实例</label><select class="select" id="project-instance">${instanceOptions}</select></div><div class="field"><label>文件版本</label><select class="select" id="project-version">${projectVersionOptions()}</select></div></div>
          <div class="note" style="margin-top:8px">${icon('info')}<div>${selected ? esc(selected.fileName || '') + ' · ' + fmtBytes(selected.fileSize || 0) : '选择版本后安装'}</div></div>`
        : `<div class="empty" style="padding:18px 0"><b>没有可安装的实例</b>先创建一个实例，再安装${typeName(p.type)}<div style="margin-top:8px"><button class="btn btn-sm" data-action="create-instance">${icon('plus')} 新建实例</button></div></div>`}
      </div>
      ${project.url ? `<div style="margin-top:8px"><button class="btn btn-ghost btn-sm" data-action="open-url" data-url="${attr(project.url)}">${icon('external')} 打开项目页面</button></div>` : ''}
    </div>
    <div class="modal-foot"><button class="btn" data-action="close-modal">取消</button><button class="btn btn-primary" data-action="install-project" ${(!isPack && !state.instances.length) ? 'disabled' : ''}>${icon('download')} ${isPack ? '安装整合包' : '安装'}</button></div>`;
}
function renderProjectModal() {
  if (!activeOverlay || !state.project) return;
  activeOverlay.querySelector('.modal').innerHTML = projectModalHTML();
  hydrateIcons(activeOverlay);
}
async function installProject() {
  const p = state.project;
  if (!p) return;
  const versionId = $('#project-version') ? $('#project-version').value : p.selected;
  if (!versionId) { toast('没有可用版本', 'error'); return; }
  const btn = $('[data-action="install-project"]');
  if (btn) btn.disabled = true;
  try {
    let data;
    if (p.type === 'modpack') {
      data = await api('/api/install/modpack', { method: 'POST', body: { source: p.source, projectId: p.id, versionId } });
    } else {
      const instanceId = $('#project-instance') ? $('#project-instance').value : p.instanceId;
      if (!instanceId) throw new Error('没有可安装的实例');
      data = await api('/api/install', { method: 'POST', body: { source: p.source, type: p.type, projectId: p.id, versionId, fileId: versionId, instanceId } });
    }
    closeModal();
    trackJob(data.job, { install: true });
    toast('安装任务已开始', 'ok', '下载');
  } catch (error) {
    toast(error.message, 'error');
    if (btn) btn.disabled = false;
  }
}

/* ---------- settings ---------- */
function settingsHTML() {
  const c = state.config;
  const a = state.activeAccount;
  const memory = Math.round((c.memoryMb || 4096) / 1024);
  const javaOptions = `<option value="">自动选择</option>` + state.java.map((j) =>
    `<option value="${attr(j.path)}" ${c.javaPath === j.path ? 'selected' : ''}>Java ${j.major} · ${esc(j.vendor)}</option>`
  ).join('');
  return `
    <div class="settings-grid">
      <div>
        <section class="panel settings-section">
          <h3>${icon('user')} 账户</h3>
          <p>正版、离线或第三方验证账户都可以用于启动。</p>
          <div class="account-box">
            ${avatarHtml(a)}
            <div class="account-box-text"><b>${a ? esc(a.name) : '未登录'}</b><span>${a ? accountTypeName(a.type) + (a.server ? ' · ' + esc(a.server) : '') : '点击右侧按钮添加账户'}</span></div>
            <button class="btn btn-sm btn-primary" data-action="accounts">${icon('user')} ${a ? '管理账户' : '添加账户'}</button>
          </div>
        </section>

        <section class="panel settings-section">
          <h3>${icon('download')} 下载</h3>
          <p>版本清单、客户端、依赖库和资源文件都使用此下载渠道。</p>
          <div class="setting-row">
            <div class="setting-info"><b>下载源</b><span>国内网络建议使用 BMCLAPI；自动会优先 BMCLAPI，失败时回退官方。</span></div>
            <select class="select" id="setting-source" style="width:165px">
              <option value="auto" ${c.downloadSource === 'auto' || !c.downloadSource ? 'selected' : ''}>自动（BMCLAPI 优先）</option>
              <option value="bmclapi" ${c.downloadSource === 'bmclapi' ? 'selected' : ''}>BMCLAPI 镜像</option>
              <option value="official" ${c.downloadSource === 'official' ? 'selected' : ''}>Mojang 官方源</option>
            </select>
          </div>
          <div class="setting-row">
            <div class="setting-info"><b>下载并发</b><span>同时下载的文件数量。</span></div>
            <select class="select" id="setting-concurrency" style="width:100px">
              ${[8, 16, 32, 48, 64].map((n) => `<option value="${n}" ${Number(c.downloadConcurrency) === n ? 'selected' : ''}>${n} 线程</option>`).join('')}
            </select>
          </div>
          <div class="setting-row">
            <div class="setting-info"><b>显示快照版本</b><span>下载页与版本列表包含快照。</span></div>
            <button class="toggle ${c.showSnapshots ? 'on' : ''}" data-toggle="showSnapshots"></button>
          </div>
          <div class="setting-row">
            <div class="setting-info"><b>CurseForge API Key</b><span>${c.curseforgeKeySet ? '已配置' : '未配置'}</span></div>
            <input class="input wide" id="setting-curseforge" type="password" placeholder="留空保持不变" autocomplete="off">
          </div>
        </section>

        <section class="panel settings-section">
          <h3>${icon('palette')} 主题与颜色</h3>
          <p>下拉选择明暗模式，强调色会应用到按钮、导航高亮和进度条。</p>
          <div class="setting-row">
            <div class="setting-info"><b>颜色模式</b><span>跟随系统会随 Windows 明暗模式切换。</span></div>
            <select class="select" id="setting-theme" style="width:120px">
              <option value="light" ${c.theme === 'light' || !c.theme ? 'selected' : ''}>浅色</option>
              <option value="dark" ${c.theme === 'dark' ? 'selected' : ''}>深色</option>
              <option value="system" ${c.theme === 'system' ? 'selected' : ''}>跟随系统</option>
            </select>
          </div>
          <div class="setting-row">
            <div class="setting-info"><b>强调色</b><span>渐变会应用到主要按钮和品牌标识。</span></div>
            <div class="accent-options">
              ${['blue', 'mint', 'violet', 'sunset', 'gradient'].map((a) => `<button class="accent-swatch ${(c.accent || 'blue') === a ? 'active' : ''}" data-action="set-accent" data-accent="${a}" title="${a}"></button>`).join('')}
            </div>
          </div>
        </section>

        <section class="panel settings-section">
          <h3>${icon('cpu')} Java 与内存</h3>
          <p>启动器会按 Minecraft 版本自动选择 Java，缺少时下载 Mojang 官方运行时。</p>
          <div class="setting-row">
            <div class="setting-info"><b>默认 Java</b><span>实例可以单独覆盖。</span></div>
            <div class="setting-control"><select class="select" id="setting-java" style="width:210px">${javaOptions}</select><button class="icon-btn" data-action="scan-java" title="重新扫描">${icon('refresh')}</button></div>
          </div>
          <div class="setting-row">
            <div class="setting-info"><b>默认内存</b><span>大型整合包建议 6–8 GB。</span></div>
            <div class="setting-control"><input class="range" id="memory-range" type="range" min="2" max="32" value="${memory}" style="--fill:${((memory - 2) / 30) * 100}%"><span class="memory-value" id="memory-value">${memory} GB</span></div>
          </div>
          <div class="setting-row">
            <div class="setting-info"><b>高性能 GPU</b><span>为 Java 设置 Windows“高性能”显卡偏好，使用独立显卡启动游戏。</span></div>
            <button class="toggle ${c.highPerformanceGpu !== false ? 'on' : ''}" data-toggle="highPerformanceGpu"></button>
          </div>
          <div class="setting-row">
            <div class="setting-info"><b>启动后关闭启动器</b><span>游戏启动后退出界面，游戏进程继续运行。</span></div>
            <button class="toggle ${c.closeOnLaunch ? 'on' : ''}" data-toggle="closeOnLaunch"></button>
          </div>
        </section>

        <section class="panel settings-section">
          <h3>${icon('folder')} 目录与外观</h3>
          <p>默认游戏根目录，实例和版本隔离目录都会在这里创建。</p>
          <div class="setting-row">
            <div class="setting-info"><b>游戏根目录</b><span>${esc(c.minecraftDir || '')}</span></div>
            <button class="btn btn-sm" data-action="open-data">${icon('folder')} 打开</button>
          </div>
          <div class="setting-row">
            <div class="setting-info"><b>减少动态效果</b><span>关闭页面和背景动画。</span></div>
            <button class="toggle ${c.reduceMotion ? 'on' : ''}" data-toggle="reduceMotion"></button>
          </div>
          <div class="setting-row">
            <div class="setting-info"><b>退出启动器</b><span>正在运行的游戏不会被结束。</span></div>
            <button class="btn btn-sm btn-danger" data-action="quit">${icon('logout')} 退出</button>
          </div>
        </section>
        <div class="row" style="justify-content:flex-end;margin-top:4px"><button class="btn btn-primary" data-action="save-settings">${icon('save')} 保存设置</button></div>
      </div>
      <aside>
        <div class="panel settings-section quick-panel">
          <h3>${icon('sparkles')} 快捷操作</h3>
          <button class="quick-link" data-action="open-instance-folder">${icon('folder')}<span><b>打开当前实例目录</b><small>mods、存档和配置</small></span></button>
          <button class="quick-link" data-action="open-launcher-data">${icon('box')}<span><b>打开启动器目录</b><small>日志、缓存和账户数据</small></span></button>
          <button class="quick-link" data-action="refresh-page">${icon('refresh')}<span><b>重新读取版本</b><small>刷新版本清单</small></span></button>
          <button class="quick-link" data-action="quit">${icon('logout')}<span><b>退出启动器</b><small>不会结束正在运行的游戏</small></span></button>
        </div>
        <div class="panel settings-section tip-panel" style="margin-top:10px">
          <h3>${icon('shield')} 提示</h3>
          <p style="margin-bottom:0">正版验证会在系统默认浏览器中打开，完成后把回调地址粘贴回来。离线账户使用 Steve / Alex 头像。</p>
        </div>
      </aside>
    </div>`;
}
function syncSettingsState() {
  const src = $('#setting-source');
  if (src) state.config.downloadSource = src.value;
  const mem = $('#memory-range');
  if (mem) state.config.memoryMb = Number(mem.value) * 1024;
  const java = $('#setting-java');
  if (java) state.config.javaPath = java.value;
  const con = $('#setting-concurrency');
  if (con) state.config.downloadConcurrency = Number(con.value);
  const theme = $('#setting-theme');
  if (theme) state.config.theme = theme.value;
  const key = $('#setting-curseforge');
  if (key && key.value.trim()) state.config._newKey = key.value.trim();
}
async function saveSettings(silent = false) {
  syncSettingsState();
  const payload = {
    downloadSource: state.config.downloadSource || 'auto',
    memoryMb: state.config.memoryMb || 4096,
    javaPath: state.config.javaPath || '',
    downloadConcurrency: state.config.downloadConcurrency || 8,
    showSnapshots: !!state.config.showSnapshots,
    closeOnLaunch: !!state.config.closeOnLaunch,
    highPerformanceGpu: state.config.highPerformanceGpu !== false,
    reduceMotion: !!state.config.reduceMotion,
    theme: state.config.theme || 'light',
    accent: state.config.accent || 'blue'
  };
  if (state.config._newKey) payload.curseforgeKey = state.config._newKey;
  try {
    state.config = await api('/api/config', { method: 'POST', body: payload });
    state.config._newKey = '';
    applyTheme();
    if (!silent) toast('设置已保存', 'ok');
    if (state.page === 'settings' && !silent) renderView();
    if (payload.downloadSource) loadVersions(true);
  } catch (error) { toast(error.message, 'error'); }
}

/* ---------- accounts ---------- */
function openAccountsModal(tab = 'microsoft') {
  state.auth.tab = tab;
  const a = state.activeAccount;
  const accounts = state.accounts.map((acc) => `<div class="account-row ${acc.active ? 'active' : ''}">
    ${avatarHtml(acc)}
    <div class="account-row-text"><b>${esc(acc.name)}</b><span>${accountTypeName(acc.type)}${acc.server ? ' · ' + esc(acc.server) : ''}</span></div>
    ${acc.active ? '<span class="badge green">使用中</span>' : `<button class="btn btn-sm" data-action="account-select" data-id="${attr(acc.id)}">切换</button>`}
    <button class="icon-btn" data-action="account-remove" data-id="${attr(acc.id)}" title="移除">${icon('trash')}</button>
  </div>`).join('');
  let form = '';
  if (tab === 'microsoft') {
    if (state.auth.url) {
      form = `<div class="auth-steps"><div class="auth-step"><b>1</b><div>在打开的微软页面登录并授权 Minecraft。</div></div><div class="auth-step"><b>2</b><div>浏览器最终停留在 login.live.com/oauth20_desktop.srf#code=… 的空白页。</div></div><div class="auth-step"><b>3</b><div>复制完整地址，粘贴到下面。</div></div></div>
        <div class="auth-url"><span>${esc(state.auth.url)}</span><button class="icon-btn" data-action="copy-auth-url">${icon('copy')}</button></div>
        <div class="row gap-6" style="margin-bottom:8px"><button class="btn btn-sm" data-action="ms-open">${icon('external')} 重新打开登录页</button><button class="btn btn-sm" data-action="ms-paste">${icon('clipboard')} 从剪贴板粘贴</button></div>
        <form id="ms-complete-form"><div class="field"><label>回调地址</label><textarea id="ms-callback" placeholder="https://login.live.com/oauth20_desktop.srf#code=…"></textarea></div><button type="submit" class="btn btn-primary" style="width:100%">${icon('shield')} 完成正版验证</button><div class="error-text" id="ms-error"></div></form>`;
    } else {
      form = `<div class="auth-steps"><div class="auth-step"><b>1</b><div>打开微软官方登录页，使用已购买 Minecraft: Java Edition 的账户。</div></div><div class="auth-step"><b>2</b><div>授权后复制浏览器最终地址，粘贴回启动器。</div></div></div>
        <button class="btn btn-primary" style="width:100%" data-action="ms-start">${icon('external')} 打开微软登录页</button>`;
    }
  } else if (tab === 'offline') {
    form = `<form id="offline-form"><div class="field"><label>离线用户名</label><input class="input" id="offline-name" placeholder="1-16 位字母、数字或下划线" autocomplete="off"></div>
      <div class="note">${icon('info')}<div>离线账户不连接任何验证服务器，只用于本地游戏或允许离线登录的服务器。UUID 由用户名生成。</div></div>
      <button type="submit" class="btn btn-primary" style="width:100%;margin-top:10px">${icon('user')} 使用离线账户</button><div class="error-text" id="offline-error"></div></form>`;
  } else {
    form = `<form id="thirdparty-form"><div class="field"><label>验证服务器地址</label><input class="input" id="tp-url" placeholder="https://example.com/api/yggdrasil" autocomplete="off"></div>
      <div class="form-grid"><div class="field"><label>用户名</label><input class="input" id="tp-user" autocomplete="off"></div><div class="field"><label>密码</label><input class="input" id="tp-pass" type="password" autocomplete="off"></div></div>
      <div class="note" style="margin-top:10px">${icon('server')}<div>使用 authlib-injector / Yggdrasil 认证。密码只用于本次验证，不会保存。启动时会自动注入 authlib-injector。</div></div>
      <button type="submit" class="btn btn-primary" style="width:100%;margin-top:10px">${icon('globe')} 验证并登录</button><div class="error-text" id="tp-error"></div></form>`;
  }
  openModal(`${modalHead('账户', a ? '当前：' + a.name + ' · ' + accountTypeName(a.type) : '选择验证方式')}
    <div class="modal-body">
      ${accounts ? `<div class="account-list">${accounts}</div>` : ''}
      <div class="account-tabs">
        <button class="account-tab ${tab === 'microsoft' ? 'active' : ''}" data-action="account-tab" data-tab="microsoft">正版验证</button>
        <button class="account-tab ${tab === 'offline' ? 'active' : ''}" data-action="account-tab" data-tab="offline">离线验证</button>
        <button class="account-tab ${tab === 'thirdparty' ? 'active' : ''}" data-action="account-tab" data-tab="thirdparty">第三方验证</button>
      </div>
      ${form}
    </div>`, 'narrow');
}
async function startMicrosoft() {
  try {
    const data = await api('/api/auth/start', { method: 'POST' });
    state.auth.url = data.url;
    const opened = await api('/api/auth/open', { method: 'POST', body: { url: data.url } });
    if (!opened.ok) toast('无法唤起默认浏览器，请复制登录地址手动打开', 'error');
    openAccountsModal('microsoft');
  } catch (error) {
    toast(error.message, 'error');
  }
}
async function completeMicrosoft(e) {
  e.preventDefault();
  const url = $('#ms-callback').value.trim();
  const err = $('#ms-error');
  if (!url.includes('code=')) { err.textContent = '地址中找不到 code 参数'; return; }
  const btn = e.target.querySelector('button[type="submit"]');
  btn.disabled = true;
  try {
    const data = await api('/api/auth/complete', { method: 'POST', body: { url } });
    trackJob(data.job, { auth: true });
    err.textContent = '正在验证，请稍候…';
  } catch (error) { err.textContent = error.message; btn.disabled = false; }
}
async function offlineLogin(e) {
  e.preventDefault();
  const name = $('#offline-name').value.trim();
  const err = $('#offline-error');
  try {
    await api('/api/auth/offline', { method: 'POST', body: { name } });
    await refreshAccounts();
    closeModal();
    renderView();
    toast('已切换到离线账户', 'ok');
  } catch (error) { err.textContent = error.message; }
}
async function thirdPartyLogin(e) {
  e.preventDefault();
  const err = $('#tp-error');
  const btn = e.target.querySelector('button[type="submit"]');
  btn.disabled = true;
  try {
    const data = await api('/api/auth/thirdparty', { method: 'POST', body: { url: $('#tp-url').value.trim(), username: $('#tp-user').value.trim(), password: $('#tp-pass').value } });
    trackJob(data.job, { auth: true });
    err.textContent = '正在连接验证服务器…';
  } catch (error) { err.textContent = error.message; btn.disabled = false; }
}
async function refreshAccounts() {
  try {
    const data = await api('/api/accounts');
    state.accounts = data.accounts || [];
    state.activeAccount = state.accounts.find((a) => a.active) || null;
    updateAccountMini();
    if (state.page === 'settings' && !activeOverlay) renderView();
  } catch (error) { console.error(error); }
}

/* ---------- jobs ---------- */
function trackJob(id, options = {}) {
  if (!id) return;
  const existing = state.jobs.get(id);
  if (existing && existing.timer) return;
  const record = existing || { id, options };
  record.options = Object.assign({}, record.options || {}, options);
  state.jobs.set(id, record);
  const poll = async () => {
    record.timer = null;
    try {
      const job = await api('/api/jobs/' + encodeURIComponent(id));
      record.job = job;
      renderJobs();
      if (job.status === 'running') record.timer = setTimeout(poll, 650);
      else {
        if (!record.handled) { record.handled = true; handleJobDone(job, record); }
        if (job.status === 'done') setTimeout(() => { state.jobs.delete(id); renderJobs(); }, 4500);
        else setTimeout(() => { state.jobs.delete(id); renderJobs(); }, 15000);
      }
    } catch (error) {
      record.error = error.message;
      renderJobs();
      record.timer = setTimeout(poll, 1600);
    }
  };
  poll();
}
async function handleJobDone(job, record) {
  const options = record.options || {};
  if (job.status === 'error') {
    toast(String(job.error || '任务失败').slice(0, 220), 'error', job.label || '失败');
    if (options.auth) {
      const err = $('#ms-error') || $('#tp-error');
      if (err) err.textContent = job.error || '验证失败';
    }
    return;
  }
  if (job.kind === 'auth' || options.auth) {
    await refreshAccounts();
    state.auth.url = '';
    state.auth.busy = false;
    closeModal();
    toast('账户已连接 · ' + (state.activeAccount ? state.activeAccount.name : ''), 'ok');
    renderView();
    return;
  }
  if (job.kind === 'launch' || options.launch) {
    if (job.result && job.result.instance) state.running[job.result.instance] = job.result.pid || 1;
    toast('游戏已启动', 'ok', 'PID ' + (job.result ? job.result.pid : ''));
    await refreshInstances(false);
    renderView();
    if (state.config.closeOnLaunch) setTimeout(() => shutdownLauncher(), 900);
    return;
  }
  if (job.kind === 'modpack' || (job.result && job.result.instance)) {
    await refreshInstances();
    toast('整合包安装完成', 'ok');
    if (job.result && job.result.instance) {
      state.selectedInstanceId = job.result.instance;
      await api('/api/config', { method: 'POST', body: { lastInstance: job.result.instance } }).catch(() => {});
    }
    navigate('home');
    return;
  }
  if (job.kind === 'install') {
    await refreshInstances();
    toast('内容安装完成', 'ok');
    if (state.detail.instance) loadDetailTab(state.detail.tab);
    else renderView();
    return;
  }
  await refreshInstances();
}
function fmtCount(n) {
  return Number(n || 0).toLocaleString('zh-CN');
}
function fmtEta(seconds) {
  if (!Number.isFinite(seconds) || seconds <= 0) return '—';
  if (seconds < 60) return Math.ceil(seconds) + ' 秒';
  const min = Math.floor(seconds / 60);
  const sec = Math.ceil(seconds % 60);
  if (min < 60) return min + ' 分 ' + sec + ' 秒';
  return Math.floor(min / 60) + ' 小时 ' + (min % 60) + ' 分';
}
function renderJobs() {
  const dock = $('#job-dock');
  if (!dock) return;
  const records = Array.from(state.jobs.values()).filter((r) => r.job || r.error);
  const active = records.find((r) => r.job && r.job.status === 'running') || records[records.length - 1];
  const toggle = $('#job-toggle');
  const badge = $('#job-badge');
  const fill = $('#job-topbar-fill');
  if (toggle) toggle.classList.toggle('hidden', !active);
  if (!active) {
    dock.innerHTML = '';
    if (badge) badge.textContent = '0';
    if (fill) fill.style.width = '0%';
    return;
  }
  const activeJob = active.job;
  const pct = activeJob ? Math.max(0, Math.min(100, (activeJob.progress || 0) * 100)) : 0;
  if (badge) badge.textContent = activeJob && activeJob.status === 'running' ? pct.toFixed(0) + '%' : (activeJob && activeJob.status === 'error' ? '失败' : '完成');
  if (fill) fill.style.width = (activeJob && activeJob.status === 'error' ? 100 : pct) + '%';
  const label = $('#job-toggle-label');
  if (label) label.textContent = activeJob ? ({ install: '下载', launch: '启动', auth: '验证' })[activeJob.kind] || '任务' : '任务';
  if (toggle) toggle.title = activeJob ? `${activeJob.label || '任务'} · ${activeJob.message || activeJob.stage || ''}` : '任务';
  if (!state.jobCenterOpen) { dock.innerHTML = ''; return; }
  if (!activeJob) {
    dock.innerHTML = `<div class="job-card"><div class="job-top"><span class="job-spinner"></span><div class="job-text"><b>正在连接任务</b><span>${esc(active.error || '')}</span></div></div></div>`;
    hydrateIcons(dock);
    return;
  }
  const open = state.jobLogs.has(active.id);
  const filesTotal = Number(activeJob.filesTotal || 0);
  const filesDone = Number(activeJob.filesDone || 0);
  const bytesTotal = Number(activeJob.bytesTotal || 0);
  const bytesDone = Number(activeJob.bytesDone || 0);
  const speed = Number(activeJob.bytesPerSecond || 0);
  const remaining = filesTotal > 0 ? Math.max(0, filesTotal - filesDone) : 0;
  const eta = speed > 0 && bytesTotal > bytesDone ? (bytesTotal - bytesDone) / speed : 0;
  const statusText = activeJob.status === 'done' ? '完成' : activeJob.status === 'error' ? '失败' : pct.toFixed(1) + '%';
  const kind = ({ install: '下载 / 安装', launch: '启动', auth: '账户验证' })[activeJob.kind] || '任务';
  dock.innerHTML = `<div class="job-card ${activeJob.status}">
      <div class="job-top">
        <span class="job-spinner"></span>
        <div class="job-heading"><b>${esc(activeJob.label || '任务')}</b><small>${esc(kind)} · ${esc(activeJob.stage || '')}</small></div>
        <div class="job-percent">${statusText}</div>
        <button class="job-close" data-action="job-close" data-id="${attr(active.id)}">${icon('x')}</button>
      </div>
      <div class="job-bar"><i style="width:${activeJob.status === 'error' ? 100 : pct}%"></i></div>
      <div class="job-stats">
        <div><small>文件</small><b>${filesTotal > 0 ? fmtCount(filesDone) + ' / ' + fmtCount(filesTotal) : '—'}</b></div>
        <div><small>剩余文件</small><b>${filesTotal > 0 ? fmtCount(remaining) + ' 个' : '—'}</b></div>
        <div><small>网络下载</small><b>${bytesTotal > 0 ? fmtBytes(Math.min(bytesDone, bytesTotal)) + ' / ' + fmtBytes(bytesTotal) : '—'}</b></div>
        <div><small>速度 / 剩余</small><b>${speed > 0 ? fmtBytes(speed) + '/s · ' + fmtEta(eta) : '—'}</b></div>
      </div>
      <div class="job-current">${esc(activeJob.message || activeJob.stage || '正在准备…')}</div>
      ${activeJob.error ? `<div class="job-error">${esc(activeJob.error)}</div>` : ''}
      ${activeJob.logs && activeJob.logs.length ? `<button class="job-logs-btn" data-action="job-logs" data-id="${attr(active.id)}">${open ? '收起日志' : '查看日志'}</button>` : ''}
      ${open ? `<div class="job-logs">${esc((activeJob.logs || []).slice(-40).join('\n'))}</div>` : ''}
    </div>`;
  hydrateIcons(dock);
}
async function resumeJobs() {
  try {
    const data = await api('/api/jobs');
    for (const job of data.jobs || []) trackJob(job.id);
  } catch { }
}
async function pollRunning() {
  try {
    const data = await api('/api/running');
    const next = data.running || {};
    const changed = JSON.stringify(next) !== JSON.stringify(state.running);
    state.running = next;
    if (changed && (state.page === 'home' || state.page === 'instances')) renderView();
  } catch { }
}
async function refreshInstances(render = true) {
  try {
    const data = await api('/api/instances');
    state.instances = data.instances || [];
    $('#instance-badge').textContent = String(state.instances.length);
    if (state.selectedInstanceId && !state.instances.some((i) => i.id === state.selectedInstanceId)) {
      state.selectedInstanceId = (state.instances[0] && state.instances[0].id) || '';
    }
    if (render && (state.page === 'home' || state.page === 'instances')) renderView();
  } catch (error) { console.error(error); }
}
async function launchInstance(id) {
  if (!state.activeAccount) { openAccountsModal('microsoft'); toast('请先添加账户', 'error'); return; }
  try {
    const data = await api('/api/instances/' + encodeURIComponent(id) + '/launch', { method: 'POST' });
    toast('正在准备游戏文件', 'ok', '启动');
    trackJob(data.job, { launch: true });
  } catch (error) { toast(error.message, 'error'); }
}
async function stopInstance(id) {
  try {
    await api('/api/instances/' + encodeURIComponent(id) + '/stop', { method: 'POST' });
    delete state.running[id];
    toast('已请求停止游戏', 'ok');
    renderView();
  } catch (error) { toast(error.message, 'error'); }
}
async function shutdownLauncher() {
  try { await api('/api/app/quit', { method: 'POST' }); } catch { }
  document.body.innerHTML = `<div class="empty" style="height:100vh;display:flex;flex-direction:column;justify-content:center"><span data-icon="box"></span><b>启动器已退出</b>可以关闭此窗口，游戏进程不受影响。</div>`;
  hydrateIcons(document.body);
  setTimeout(() => window.close(), 400);
}

/* ---------- actions / events ---------- */
async function handleAction(action, el) {
  const id = el.dataset.id || '';
  switch (action) {
    case 'accounts': openAccountsModal(state.auth.tab || 'microsoft'); return;
    case 'account-tab': state.auth.tab = el.dataset.tab; if (state.auth.tab !== 'microsoft') state.auth.url = ''; openAccountsModal(el.dataset.tab); return;
    case 'ms-start': await startMicrosoft(); return;
    case 'copy-auth-url': try { await navigator.clipboard.writeText(state.auth.url); toast('链接已复制', 'ok'); } catch { toast('无法访问剪贴板', 'error'); } return;
    case 'ms-open':
      if (state.auth.url) {
        const opened = await api('/api/auth/open', { method: 'POST', body: { url: state.auth.url } });
        if (!opened.ok) toast('无法唤起默认浏览器，请复制链接', 'error');
      }
      return;
    case 'ms-paste': try { const text = await navigator.clipboard.readText(); const box = $('#ms-callback'); if (box) { box.value = text; box.focus(); toast('已粘贴', 'ok'); } } catch { toast('请按 Ctrl+V 粘贴', 'error'); } return;
    case 'account-select': try { await api('/api/auth/select', { method: 'POST', body: { id } }); await refreshAccounts(); closeModal(); renderView(); toast('已切换账户', 'ok'); } catch (e) { toast(e.message, 'error'); } return;
    case 'account-remove': try { await api('/api/auth/logout', { method: 'POST', body: { id } }); await refreshAccounts(); openAccountsModal(state.auth.tab); } catch (e) { toast(e.message, 'error'); } return;
    case 'toggle-launch-menu': {
      const dropdown = el.closest('.dropdown');
      if (dropdown) dropdown.classList.toggle('open');
      return;
    }
    case 'pick-instance':
      state.selectedInstanceId = id;
      api('/api/config', { method: 'POST', body: { lastInstance: id } }).catch(() => {});
      renderView();
      return;
    case 'select-instance': state.selectedInstanceId = id; api('/api/config', { method: 'POST', body: { lastInstance: id } }).catch(() => {}); renderView(); return;
    case 'launch': await launchInstance(id || (selectedInstance() && selectedInstance().id)); return;
    case 'stop': await stopInstance(id); return;
    case 'download-page': navigate('download'); return;
    case 'nav-settings': navigate('settings'); return;
    case 'refresh-page': await refreshAll(); return;
    case 'reload-app': location.reload(); return;
    case 'refresh-versions': await loadVersions(true); return;
    case 'version-filter': state.versionFilter = el.dataset.filter; state.versionLimit = 240; renderView(); return;
    case 'download-more': state.versionLimit += 240; updateVersionList(); return;
    case 'install-version': openCreateInstance(id, el.dataset.loader || 'vanilla'); return;
    case 'set-bmcl': state.config.downloadSource = 'bmclapi'; await saveSettings(true); toast('已切换 BMCLAPI 镜像', 'ok'); return;
    case 'create-instance': openCreateInstance(); return;
    case 'import-client': openImportClient(); return;
    case 'browse-folder': try { const data = await api('/api/pick-folder', { method: 'POST' }); const input = $('#import-path'); if (input && data.path) { input.value = data.path; await scanFolder(); } } catch (e) { toast(e.message, 'error'); } return;
    case 'scan-folder': await scanFolder(); return;
    case 'import-select': {
      const selected = new Set(state.importData.selected);
      if (selected.has(id)) selected.delete(id); else selected.add(id);
      state.importData.selected = Array.from(selected);
      paintImportVersions();
      return;
    }
    case 'import-select-all':
      state.importData.selected = state.importData.selected.length === state.importData.versions.length
        ? [] : state.importData.versions.map((v) => v.id);
      paintImportVersions();
      return;
    case 'import-submit': await submitImport(); return;
    case 'edit-instance': closeModal(); setTimeout(() => openEditInstance(id), 40); return;
    case 'instance-detail': closeModal(); setTimeout(() => openInstanceDetail(id), 40); return;
    case 'detail-tab': await loadDetailTab(el.dataset.tab); return;
    case 'detail-toggle': try { await api(`/api/instances/${encodeURIComponent(state.detail.id)}/content/toggle`, { method: 'POST', body: { type: el.dataset.type, name: el.dataset.name } }); await loadDetailTab(state.detail.tab); } catch (e) { toast(e.message, 'error'); } return;
    case 'detail-remove': {
      const detailId = state.detail.id;
      const detailTab = state.detail.tab;
      openConfirm('删除文件', `确定删除「${el.dataset.name}」？`, async () => {
        try {
          await api(`/api/instances/${encodeURIComponent(detailId)}/content?type=${encodeURIComponent(el.dataset.type)}&name=${encodeURIComponent(el.dataset.name)}`, { method: 'DELETE' });
          await openInstanceDetail(detailId, detailTab);
        } catch (e) { toast(e.message, 'error'); }
      });
      return;
    }
    case 'open-instance-folder': try { await api(`/api/instances/${encodeURIComponent(id)}/open`, { method: 'POST' }); } catch (e) { toast(e.message, 'error'); } return;
    case 'delete-instance': {
      const inst = state.instances.find((i) => i.id === id);
      const deleteHint = inst && inst.gameDirMode === 'version'
        ? '只删除实例记录，versions 里的版本和游戏数据会保留。'
        : (inst && inst.isolated ? '独立游戏目录会被删除。' : '共享目录中的原文件不会被删除。');
      openConfirm('删除实例', `确定删除「${inst ? inst.name : id}」？${deleteHint}`, async () => {
        try { await api('/api/instances/' + encodeURIComponent(id), { method: 'DELETE' }); await refreshInstances(); navigate('instances'); toast('实例已删除', 'ok'); } catch (e) { toast(e.message, 'error'); }
      });
      return;
    }
    case 'open-data': try { await api('/api/open-folder', { method: 'POST', body: { target: 'minecraft' } }); } catch (e) { toast(e.message, 'error'); } return;
    case 'open-instance-folder':
      if (!state.selectedInstanceId) { toast('还没有选择实例', 'error'); return; }
      try { await api('/api/open-folder', { method: 'POST', body: { target: 'game', instanceId: state.selectedInstanceId } }); }
      catch (e) { toast(e.message, 'error'); }
      return;
    case 'open-launcher-data': try { await api('/api/open-folder', { method: 'POST', body: { target: 'data' } }); } catch (e) { toast(e.message, 'error'); } return;
    case 'save-settings': await saveSettings(false); return;
    case 'set-accent':
      state.config.accent = el.dataset.accent;
      applyTheme();
      $$('.accent-swatch').forEach((s) => s.classList.toggle('active', s.dataset.accent === state.config.accent));
      saveSettings(true);
      return;
    case 'scan-java': try { const data = await api('/api/java/scan', { method: 'POST' }); state.java = data.runtimes || []; renderView(); toast('已重新扫描 Java', 'ok'); } catch (e) { toast(e.message, 'error'); } return;
    case 'quit': openConfirm('退出启动器', '本地服务将关闭，正在运行的游戏不会被结束。', () => shutdownLauncher(), false); return;
    case 'close-modal': closeModal(); return;
    case 'job-close': state.jobCenterOpen = false; renderJobs(); return;
    case 'toggle-job-center': state.jobCenterOpen = !state.jobCenterOpen; renderJobs(); return;
    case 'job-logs': if (state.jobLogs.has(id)) state.jobLogs.delete(id); else state.jobLogs.add(id); renderJobs(); return;
    case 'content-type': state.discover.type = el.dataset.type; state.discover.hits = []; state.discover.total = 0; renderView(); return;
    case 'content-source': state.discover.source = el.dataset.source; state.discover.hits = []; state.discover.total = 0; renderView(); return;
    case 'content-refresh': loadContent(true); return;
    case 'content-more': state.discover.offset = state.discover.hits.length; loadContent(false); return;
    case 'install-project': await installProject(); return;
    case 'open-url': if (/^https?:\/\//i.test(el.dataset.url)) window.open(el.dataset.url, '_blank', 'noopener,noreferrer'); return;
    case 'confirm-yes': { const fn = state.confirmAction; state.confirmAction = null; closeModal(); if (fn) await fn(); return; }
    case 'confirm-cancel': state.confirmAction = null; closeModal(); return;
    default: return;
  }
}
function openConfirm(title, text, fn, danger = true) {
  state.confirmAction = fn;
  openModal(`${modalHead(title)}<div class="modal-body"><p style="font-size:11px;line-height:1.7;color:var(--muted)">${esc(text)}</p></div><div class="modal-foot"><button class="btn" data-action="confirm-cancel">取消</button><button class="btn ${danger ? 'btn-danger' : 'btn-primary'}" data-action="confirm-yes">确定</button></div>`, 'narrow');
}
async function refreshAll() {
  try {
    const data = await api('/api/bootstrap');
    state.config = data.config || state.config;
    state.accounts = (data.accounts && data.accounts.accounts) || [];
    state.activeAccount = state.accounts.find((a) => a.active) || null;
    state.instances = data.instances || [];
    state.java = data.java || [];
    state.running = data.running || {};
    renderShell();
    renderView();
    loadVersions();
    toast('已刷新', 'ok');
  } catch (error) { toast(error.message, 'error'); }
}

document.addEventListener('click', async (e) => {
  const nav = e.target.closest('[data-nav]');
  if (nav) { navigate(nav.dataset.nav); return; }
  const action = e.target.closest('[data-action]');
  if (action) { e.preventDefault(); e.stopPropagation(); await handleAction(action.dataset.action, action); return; }
  const card = e.target.closest('.content-card');
  if (card) { await openProject(card.dataset.projectSource, card.dataset.projectId, card.dataset.projectType); return; }
  const row = e.target.closest('.instance-row');
  if (row) { state.selectedInstanceId = row.dataset.id; renderView(); return; }
  if (!e.target.closest('.dropdown')) $$('.dropdown.open').forEach((d) => d.classList.remove('open'));
});
document.addEventListener('input', (e) => {
  if (e.target.id === 'version-search') {
    state.versionQuery = e.target.value;
    state.versionLimit = 240;
    clearTimeout(window.__vTimer);
    window.__vTimer = setTimeout(updateVersionList, 160);
  }
  if (e.target.id === 'content-search') {
    state.discover.q = e.target.value;
    clearTimeout(window.__cTimer);
    window.__cTimer = setTimeout(() => loadContent(true), 320);
  }
  if (e.target.id === 'memory-range') {
    const v = Number(e.target.value);
    const label = $('#memory-value');
    if (label) label.textContent = v + ' GB';
    e.target.style.setProperty('--fill', ((v - 2) / 30) * 100 + '%');
  }
});
document.addEventListener('change', async (e) => {
  if (e.target.id === 'launch-version') {
    state.selectedInstanceId = e.target.value;
    await api('/api/config', { method: 'POST', body: { lastInstance: e.target.value } }).catch(() => {});
    renderView();
  }
  if (e.target.id === 'setting-source') {
    state.config.downloadSource = e.target.value;
    await saveSettings(true);
    toast('下载源已切换', 'ok');
  }
  if (e.target.id === 'setting-theme') {
    state.config.theme = e.target.value;
    applyTheme();
    await saveSettings(true);
  }
  if (e.target.id === 'setting-java' || e.target.id === 'setting-concurrency') await saveSettings(true);
  if (e.target.id === 'create-loader') {
    const row = $('#fabric-api-row');
    if (row) row.style.display = e.target.value === 'fabric' ? 'flex' : 'none';
  }
  if (e.target.matches('[data-toggle]')) {
    const key = e.target.dataset.toggle;
    state.config[key] = !state.config[key];
    e.target.classList.toggle('on', !!state.config[key]);
    if (key === 'reduceMotion') document.body.classList.toggle('reduce-motion', !!state.config[key]);
    saveSettings(true);
  }
});
document.addEventListener('submit', async (e) => {
  if (e.target.id === 'create-instance-form') await submitCreateInstance(e);
  if (e.target.id === 'edit-instance-form') await submitEditInstance(e);
  if (e.target.id === 'offline-form') await offlineLogin(e);
  if (e.target.id === 'thirdparty-form') await thirdPartyLogin(e);
  if (e.target.id === 'ms-complete-form') await completeMicrosoft(e);
});
document.addEventListener('keydown', (e) => { if (e.key === 'Escape' && activeOverlay) closeModal(); });
window.addEventListener('popstate', () => {
  const page = (location.hash || '#home').replace('#', '');
  state.page = PAGES[page] ? page : 'home';
  renderShell();
  renderView();
});
document.addEventListener('error', (e) => {
  if (e.target && e.target.tagName === 'IMG') e.target.style.display = 'none';
}, true);

hydrateIcons();
init();

