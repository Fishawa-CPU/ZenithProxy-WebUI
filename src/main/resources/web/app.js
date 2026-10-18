// ZenithProxy Web UI JavaScript

let currentUsername = 'root';

// Auth check
(async function checkAuth() {
    try {
        const response = await fetch('api/auth/status', { credentials: 'include' });
        const data = await response.json();
        if (!data.authenticated) {
            window.location.href = 'login.html';
            return;
        }
        currentUsername = data.username || 'root';
        // Show user info
        const userInfo = document.getElementById('userInfo');
        if (userInfo) {
            userInfo.style.display = 'block';
        }
        // Update terminal username
        const terminalUsername = document.getElementById('terminalUsername');
        const terminalPromptUser = document.getElementById('terminalPromptUser');
        if (terminalUsername) terminalUsername.textContent = currentUsername;
        if (terminalPromptUser) terminalPromptUser.textContent = currentUsername;
    } catch (e) {
        window.location.href = 'login.html';
    }
})();

async function logout() {
    try {
        await fetch('api/auth/logout', {
            method: 'POST',
            credentials: 'include'
        });
    } catch (e) {
        // ignore
    }
    window.location.href = 'login.html';
}

let logs = [];
let commandHistory = [];
let historyIndex = -1;
// 相对路径：直接访问时等价于 /api，反代到 /bot/<name>/ 下时自动变为 /bot/<name>/api
const API_BASE = new URL('api', document.baseURI).href.replace(/\/$/, '');

let autocompleteIndex = -1;
let autocompleteSuggestions = [];

let cpuChart = null, memoryChart = null, networkChart = null, latencyChart = null;
const MAX_DATA_POINTS = 60;
const MAX_TERMINAL_LINES = 500;

let terminalScrollLocked = true;
let currentPage = 'dashboard';
let chartsInitialized = false;

// Log SSE stream
let logEventSource = null;
let logReconnectDelay = 1000;
const MAX_RECONNECT_DELAY = 30000;

// Terminal command tracking
let terminalCmdCount = 0;
let terminalLogCount = 0;

// Server state for queue display
let currentConnected = false;
let queueInitialized = false; // true after first status check
let queueConnectedTime = 0;
const QUEUE_LOADING_DELAY = 5000; // 5 seconds loading delay (only on initial page load)

const KNOWN_COMMANDS = [
    'help', 'connect', 'disconnect', 'reconnect', 'cancel',
    'auth', 'whitelist', 'friend', 'ignore', 'kick',
    'server', 'server switch', 'transfer',
    'status', 'debug', 'plugins', 'update',
    'autoReconnect', 'autoDisconnect', 'autoReply', 'autoRespawn',
    'autoEat', 'autoFish', 'autoMend', 'autoTotem', 'autoDrop', 'autoArmor', 'autoOmen',
    'antiAFK', 'antiKick', 'antiLeak',
    'chatRelay', 'chatHistory', 'extraChat', 'chatSchema',
    'commandConfig', 'database', 'theme',
    'inventory', 'click', 'respawn',
    'killAura', 'raycast',
    'pathfinder', 'tasks', 'waypoints',
    'map', 'displayCoords', 'coordinateObfuscation',
    'queueStatus', 'queueWarning', 'prio', 'activeHours',
    'connectionTest', 'clientConnection', 'serverConnection',
    'packetLog', 'schematic', 'replay',
    'stats', 'playtime', 'sessionTimeLimit', 'seen',
    'skin', 'tablist', 'spectator', 'spook',
    'stalk', 'spawnPatrol', 'visualRange',
    'spammer', 'sendMessage', 'rateLimiter',
    'tickRate', 'releaseChannel', 'viaVersion',
    'multi', 'terminal', 'shutdown',
    'jvmArgs', 'unsupported',
    'modulePriority', 'actionLimiter',
    'chatColor', 'chatFormat',
    'discordManage', 'discordNotifications',
    'license',
];

// Convert legacy string array to object array
function normalizeCommands(cmds) {
    if (!cmds || !cmds.length) return [];
    if (typeof cmds[0] === 'string') return cmds.map(name => ({ name }));
    return cmds;
}

let serverCommands = normalizeCommands(KNOWN_COMMANDS);
// Quick lookup: alias -> name
let aliasMap = {};

// ── Init ──────────────────────────────────────
/** 逐个执行初始化步骤：任何一步失败都不影响后面，避免整页卡死。 */
function safeInit(name, fn) {
    try {
        fn();
    } catch (e) {
        console.error('[WebUI] 初始化失败: ' + name, e);
    }
}

document.addEventListener('DOMContentLoaded', async () => {
    safeInit('导航', initNavigation);
    safeInit('终端', initTerminal);

    // Whitelist add input Enter key
    safeInit('白名单输入框', () => {
        const wlInput = document.getElementById('whitelistAddInput');
        if (wlInput) {
            wlInput.addEventListener('keydown', (e) => {
                if (e.key === 'Enter') { e.preventDefault(); addWhitelistPlayer(); }
            });
        }
    });

    safeInit('仪表板', loadDashboard);
    safeInit('图表', initCharts);
    safeInit('系统轮询', startSystemPolling);
    safeInit('命令列表', loadCommands);
    safeInit('日志轮询', startLogPolling);
});

// ── Navigation ────────────────────────────────
function initNavigation() {
    document.querySelectorAll('.nav-item').forEach(item => {
        item.addEventListener('click', () => navigateTo(item.dataset.page));
    });
}

function navigateTo(page) {
    currentPage = page;
    document.querySelectorAll('.nav-item').forEach(item => item.classList.toggle('active', item.dataset.page === page));
    document.querySelectorAll('.page').forEach(p => p.classList.remove('active'));
    document.getElementById(page).classList.add('active');

    switch (page) {
        case 'dashboard': loadDashboard(); initCharts(); startSystemPolling(); break;

        case 'terminal': refreshTerminalLogs(); break;
        case 'players': loadWhitelist(); break;
        case 'config': cfgEnter(); break;
    }
    if (page !== 'dashboard') stopSystemPolling();
}

function refreshTerminalLogs() {
    const output = document.getElementById('terminalOutput');
    if (!output) return;
    // Clear existing logs
    output.innerHTML = '';
    // Re-add all buffered logs
    for (const log of logs) {
        const entry = document.createElement('div');
        entry.className = 'terminal-line terminal-line-log';
        const ts = new Date(log.timestamp).toLocaleTimeString();
        entry.innerHTML = `<span class="log-ts">${escapeHtml(ts)}</span><span class="log-lv ${escapeHtml(log.level.toLowerCase())}">${escapeHtml(log.level)}</span><span class="log-msg">[${escapeHtml(log.logger)}] ${escapeHtml(log.message)}</span>`;
        output.appendChild(entry);
    }
    output.scrollTop = output.scrollHeight;
}

// ── API ───────────────────────────────────────
async function apiCall(endpoint, method = 'GET', body = null) {
    try {
        const opts = {
            method,
            headers: { 'Content-Type': 'application/json' },
            credentials: 'include'
        };
        if (body) opts.body = JSON.stringify(body);
        const r = await fetch(API_BASE + endpoint, opts);
        if (r.status === 401) {
            window.location.href = 'login.html';
            return null;
        }
        return await r.json();
    } catch (e) { return null; }
}

// ── Log SSE Stream ────────────────────────────
function startLogPolling() {
    if (logEventSource) return;
    logEventSource = new EventSource('api/logs/stream');
    logEventSource.onmessage = (event) => {
        try {
            const log = JSON.parse(event.data);
            addLogEntry(log);
        } catch (e) {}
    };
    logEventSource.onopen = () => {
        logReconnectDelay = 1000; // reset on successful connection
    };
    logEventSource.onerror = () => {
        logEventSource.close();
        logEventSource = null;
        setTimeout(startLogPolling, logReconnectDelay);
        logReconnectDelay = Math.min(logReconnectDelay * 2, MAX_RECONNECT_DELAY);
    };
}

// ── Dashboard ─────────────────────────────────
async function loadDashboard() {
    const d = await apiCall('/dashboard');
    if (!d) return;

    document.getElementById('proxyStatus').textContent = d.connected ? '已连接' : '未连接';
    document.getElementById('proxyStatus').style.color = d.connected ? 'var(--success-color)' : 'var(--error-color)';
    document.getElementById('currentUsername').textContent = d.username || '-';
    document.getElementById('proxyAddress').textContent = d.proxyAddress || '-';
    const mcEl = document.getElementById('mcVersion');
    if (mcEl) {
        // Preserve viaVersion <small> element inside mcVersion
        const viaEl = document.getElementById('viaVersion');
        const viaText = d.viaVersion || '';
        if (viaEl) {
            mcEl.textContent = '';
            mcEl.appendChild(document.createTextNode(d.version || '-'));
            mcEl.appendChild(viaEl);
            viaEl.textContent = viaText;
        } else {
            mcEl.textContent = d.version || '-';
        }
    }
    document.getElementById('version').textContent = 'v' + (d.version || '?');

    // Connected state for queue loading
    const wasConnected = currentConnected;
    currentConnected = d.connected || false;
    if (currentConnected && !wasConnected && queueInitialized) queueConnectedTime = Date.now();
    if (!currentConnected) { queueConnectedTime = 0; queueInitialized = true; }
    else queueInitialized = true;

    // Queue
    const q = { inQueue: d.inQueue, position: d.queuePosition, onlinePlayers: d.queueOnlinePlayers };
    renderQueue(q);

    // Uptime
    if (d.uptime) {
        const h = Math.floor(d.uptime / 3600000), m = Math.floor((d.uptime % 3600000) / 60000), s = Math.floor((d.uptime % 60000) / 1000);
        document.getElementById('uptime').textContent = `${h}时 ${m}分 ${s}秒`;
    }

    // Player stats
    if (d.player && d.player.connected && d.player.health !== null) {
        const pl = d.player;
        document.getElementById('playerStats').innerHTML = `<span style="color:${pl.health>10?'var(--success-color)':pl.health>5?'var(--warning-color)':'var(--error-color)'}">hp ${pl.health}/20</span> &nbsp; <span style="color:${pl.food>10?'var(--success-color)':pl.food>5?'var(--warning-color)':'var(--error-color)'}">hu ${pl.food}/20</span>`;
    } else {
        document.getElementById('playerStats').textContent = '-';
        document.getElementById('playerStats').style.color = 'var(--ctp-overlay1)';
    }

    // Player connection - simple status
    const pc = document.getElementById('playerConnection');
    const hasPlayers = d.latency && d.latency.players && d.latency.players.length > 0;
    if (pc) {
        pc.textContent = hasPlayers ? '已连接' : '无连接';
        pc.style.color = hasPlayers ? 'var(--success-color)' : 'var(--ctp-overlay1)';
    }
}
function renderQueue(q) {
    const posEl = document.getElementById('queuePosText');
    const onlineEl = document.getElementById('queueOnlineText');
    if (!posEl) return;

    if (!currentConnected) {
        posEl.textContent = '-';
        posEl.style.color = 'var(--ctp-overlay1)';
        if (onlineEl) onlineEl.textContent = '';
        return;
    }

    // Only show loading on initial page load transition, not on queue-complete transition
    const wasInQueue = q && q.inQueue && q.position > 0;
    if (!wasInQueue && queueConnectedTime > 0) {
        const elapsed = Date.now() - queueConnectedTime;
        if (elapsed < QUEUE_LOADING_DELAY) {
            posEl.textContent = '正在加载...';
            posEl.style.color = 'var(--ctp-overlay0)';
            if (onlineEl) onlineEl.textContent = '';
            return;
        }
        queueConnectedTime = 0;
    }

    if (wasInQueue) {
        posEl.textContent = `队列位置: ${q.position}`;
        posEl.style.color = 'var(--ctp-yellow)';
        if (onlineEl) {
            onlineEl.textContent = q.onlinePlayers > 0 ? `在线人数: ${q.onlinePlayers}` : '';
        }
        // Reset loading timer while in queue
        queueConnectedTime = 0;
    } else {
        posEl.textContent = '已进入';
        posEl.style.color = 'var(--ctp-green)';
        if (onlineEl) {
            onlineEl.textContent = q && q.onlinePlayers > 0 ? `在线人数: ${q.onlinePlayers}` : '';
        }
        queueConnectedTime = 0;
    }
}

// ── System Charts ─────────────────────────────
const chartColors = {
    cpu: 'rgba(198, 160, 246, 1)', cpuBg: 'rgba(198, 160, 246, 0.15)',
    memory: 'rgba(166, 218, 149, 1)', memoryBg: 'rgba(166, 218, 149, 0.15)',
    netIn: 'rgba(138, 173, 244, 1)', netInBg: 'rgba(138, 173, 244, 0.15)',
    netOut: 'rgba(245, 169, 127, 1)', netOutBg: 'rgba(245, 169, 127, 0.15)',
    grid: 'rgba(110, 115, 141, 0.15)', text: '#8087a2',
};

function pushChartData(chart, value, label) {
    chart.data.datasets[0].data.push(value);
    chart.data.labels.push(label);
    if (chart.data.datasets[0].data.length > MAX_DATA_POINTS) {
        chart.data.datasets[0].data.shift();
        chart.data.labels.shift();
    }
    autoScaleChart(chart);
    chart.update('none');
}

function pushChartDataMulti(chart, values, label) {
    for (let i = 0; i < values.length; i++) {
        chart.data.datasets[i].data.push(values[i]);
    }
    chart.data.labels.push(label);
    if (chart.data.datasets[0].data.length > MAX_DATA_POINTS) {
        for (let i = 0; i < chart.data.datasets.length; i++) {
            chart.data.datasets[i].data.shift();
        }
        chart.data.labels.shift();
    }
    autoScaleChart(chart);
    chart.update('none');
}

function autoScaleChart(chart) {
    let maxVal = 0;
    for (const ds of chart.data.datasets) {
        for (const v of ds.data) {
            if (v > maxVal) maxVal = v;
        }
    }
    // Add 20% headroom, minimum of 10
    const newMax = Math.max(maxVal * 1.2, 10);
    // Only update if significant change (>10%)
    const currentMax = chart.options.scales.y.max;
    if (currentMax === undefined || currentMax === null || Math.abs(newMax - currentMax) / currentMax > 0.1) {
        chart.options.scales.y.max = Math.ceil(newMax);
    }
}

function makeTimeLabels(count) {
    const labels = [], now = new Date();
    for (let i = count - 1; i >= 0; i--) { const t = new Date(now - i * 5000); labels.push(t.toLocaleTimeString('zh-CN', { hour12: false, hour: '2-digit', minute: '2-digit', second: '2-digit' })); }
    return labels;
}

function makeChartOptions(unit, max) {
    const yScale = { display: true, min: 0, grid: { color: chartColors.grid }, ticks: { color: chartColors.text, font: { size: 10 }, maxTicksLimit: 5, callback: v => v + (unit || '') } };
    if (max !== undefined && max !== null) yScale.max = max;
    return {
        responsive: true, maintainAspectRatio: false, animation: { duration: 0 },
        interaction: { intersect: false, mode: 'index' },
        plugins: { legend: { display: false }, tooltip: { backgroundColor: 'rgba(30,32,48,0.95)', titleColor: '#cad3f5', bodyColor: '#b8c0e0', borderColor: 'rgba(91,96,120,0.5)', borderWidth: 1, padding: 8 } },
        scales: {
            x: { display: true, grid: { color: chartColors.grid }, ticks: { color: chartColors.text, font: { size: 10 }, maxTicksLimit: 6 } },
            y: yScale
        }
    };
}

function makeDS(label, color, bg) {
    return { label, data: [], borderColor: color, backgroundColor: bg, borderWidth: 1.5, pointRadius: 0, pointHoverRadius: 0, pointHitRadius: 0, fill: true, tension: 0.3 };
}

function initCharts() {
    if (chartsInitialized) return;
    // Chart.js 未加载成功时直接跳过：不能让图表异常打断后面的仪表板/日志初始化
    if (typeof Chart === 'undefined') {
        console.warn('[WebUI] Chart.js 未加载，图表不可用（其余功能正常）');
        return;
    }
    chartsInitialized = true;
    const tl = makeTimeLabels(MAX_DATA_POINTS);
    cpuChart = new Chart(document.getElementById('cpuChart'), { type: 'line', data: { labels: tl, datasets: [makeDS('CPU', chartColors.cpu, chartColors.cpuBg)] }, options: makeChartOptions('%', 100) });
    memoryChart = new Chart(document.getElementById('memoryChart'), { type: 'line', data: { labels: tl, datasets: [makeDS('Mem', chartColors.memory, chartColors.memoryBg)] }, options: makeChartOptions('%', 100) });
    networkChart = new Chart(document.getElementById('networkChart'), { type: 'line', data: { labels: tl, datasets: [makeDS('In', chartColors.netIn, chartColors.netInBg), makeDS('Out', chartColors.netOut, chartColors.netOutBg)] }, options: { ...makeChartOptions('', undefined), plugins: { ...makeChartOptions('').plugins, legend: { display: true, position: 'top', labels: { color: chartColors.text, boxWidth: 12, padding: 10, font: { size: 11 } } } } } });
    latencyChart = new Chart(document.getElementById('latencyChart'), { type: 'line', data: { labels: tl, datasets: [
        makeDS('Bot→Server', 'rgba(238,212,159,1)', 'rgba(238,212,159,0.15)'),
        makeDS('Player→Bot', 'rgba(138,173,244,1)', 'rgba(138,173,244,0.15)')
    ] }, options: { ...makeChartOptions('ms', 200), plugins: { ...makeChartOptions('ms', 200).plugins, legend: { display: true, position: 'top', labels: { color: chartColors.text, boxWidth: 12, padding: 10, font: { size: 11 } } } } } });
}

let dashboardEventSource = null;
let dashboardReconnectDelay = 1000;

function startSystemPolling() {
    if (dashboardEventSource) return;
    dashboardEventSource = new EventSource('api/dashboard/stream');
    dashboardEventSource.onmessage = (event) => {
        if (currentPage !== 'dashboard') return;
        try {
            const d = JSON.parse(event.data);
            processDashboardData(d);
        } catch (e) {}
    };
    dashboardEventSource.onopen = () => {
        dashboardReconnectDelay = 1000; // reset on successful connection
    };
    dashboardEventSource.onerror = () => {
        dashboardEventSource.close();
        dashboardEventSource = null;
        setTimeout(startSystemPolling, dashboardReconnectDelay);
        dashboardReconnectDelay = Math.min(dashboardReconnectDelay * 2, MAX_RECONNECT_DELAY);
    };
    // Fetch once immediately via HTTP while SSE connects
    apiCall('/dashboard').then(d => { if (d && currentPage === 'dashboard') processDashboardData(d); });
}

function stopSystemPolling() {
    if (dashboardEventSource) {
        dashboardEventSource.close();
        dashboardEventSource = null;
    }
}

function processDashboardData(d) {
    if (!d) return;
    const now = new Date().toLocaleTimeString('zh-CN', { hour12: false, hour: '2-digit', minute: '2-digit', second: '2-digit' });

    // Proxy status
    const statusEl = document.getElementById('proxyStatus');
    if (statusEl) {
        statusEl.textContent = d.connected ? '已连接' : '未连接';
        statusEl.style.color = d.connected ? 'var(--success-color)' : 'var(--error-color)';
    }

    // Version & Via
    if (d.version) {
        const mcEl = document.getElementById('mcVersion');
        if (mcEl) {
            const viaEl = document.getElementById('viaVersion');
            const viaText = d.viaVersion || '';
            if (viaEl) {
                mcEl.textContent = '';
                mcEl.appendChild(document.createTextNode(d.version));
                mcEl.appendChild(viaEl);
                viaEl.textContent = viaText;
            } else {
                mcEl.textContent = d.version;
            }
        }
    }

    // Player connection - simple status
    const pc = document.getElementById('playerConnection');
    const hasPlayers = d.latency && d.latency.players && d.latency.players.length > 0;
    if (pc) {
        pc.textContent = hasPlayers ? '已连接' : '无连接';
        pc.style.color = hasPlayers ? 'var(--success-color)' : 'var(--ctp-overlay1)';
    }

    // Update connected state
    const wasConnected = currentConnected;
    currentConnected = d.connected || false;
    if (currentConnected && !wasConnected && queueInitialized) queueConnectedTime = Date.now();
    if (!currentConnected) { queueConnectedTime = 0; queueInitialized = true; }
    else queueInitialized = true;

    // Queue
    const q = { inQueue: d.inQueue, position: d.queuePosition, onlinePlayers: d.queueOnlinePlayers };
    renderQueue(q);

    // Uptime
    if (d.uptime) {
        const el = document.getElementById('uptime');
        if (el) {
            const h = Math.floor(d.uptime / 3600000), m = Math.floor((d.uptime % 3600000) / 60000), s = Math.floor((d.uptime % 60000) / 1000);
            el.textContent = `${h}时 ${m}分 ${s}秒`;
        }
    }

    // Health/food
    const pl = d.player;
    if (pl) {
        const statsEl = document.getElementById('playerStats');
        if (statsEl) {
            if (pl.connected && pl.health !== null) {
                statsEl.innerHTML = `<span style="color:${pl.health>10?'var(--success-color)':pl.health>5?'var(--warning-color)':'var(--error-color)'}">hp ${pl.health}/20</span> &nbsp; <span style="color:${pl.food>10?'var(--success-color)':pl.food>5?'var(--warning-color)':'var(--error-color)'}">hu ${pl.food}/20</span>`;
            } else {
                statsEl.textContent = '-';
                statsEl.style.color = 'var(--ctp-overlay1)';
            }
        }
    }

    // Charts
    const sys = d.system;
    if (sys && cpuChart) { pushChartData(cpuChart, Math.max(0, sys.cpuLoad || 0), now); }
    if (sys && memoryChart) { pushChartData(memoryChart, sys.memoryPercent || 0, now); }
    if (sys && networkChart) {
        pushChartDataMulti(networkChart, [
            Math.round((sys.netBytesPerSecIn || 0) / 1024 * 10) / 10,
            Math.round((sys.netBytesPerSecOut || 0) / 1024 * 10) / 10
        ], now);
    }

    // Latency
    const lat = d.latency;
    if (lat && latencyChart) {
        const botLat = d.connected ? (lat.botLatency || 0) : 0;
        let avgPlayerPing = 0;
        if (lat.players && lat.players.length > 0) {
            let sum = 0;
            for (const p of lat.players) sum += (p.ping || 0);
            avgPlayerPing = Math.round(sum / lat.players.length);
        }
        pushChartDataMulti(latencyChart, [botLat, avgPlayerPing], now);

        const detail = document.getElementById('latencyDetail');
        if (detail) {
            const parts = [`<div class="latency-item"><span class="player-name">Bot→Server</span><span class="ping-value ${getPingClass(lat.botLatency)}">${lat.botLatency}ms</span></div>`];
            if (lat.players && lat.players.length > 0) {
                for (const p of lat.players) parts.push(`<div class="latency-item"><span class="player-name">${escapeHtml(p.name)}</span><span class="ping-value ${getPingClass(p.ping)}">${escapeHtml(String(p.ping))}ms</span></div>`);
            }
            detail.innerHTML = parts.join('');
        }
    }

}

// ── Commands from server ──────────────────────
async function loadCommands() {
    try {
        const r = await fetch(API_BASE + '/commands', { credentials: 'include' });
        if (r.status === 401) { window.location.href = 'login.html'; return; }
        const d = await r.json();
        if (Array.isArray(d) && d.length > 0) {
            serverCommands = normalizeCommands(d);
            serverCommands.sort((a, b) => a.name.localeCompare(b.name));
            // Build alias lookup
            aliasMap = {};
            for (const cmd of serverCommands) {
                if (cmd.aliases && Array.isArray(cmd.aliases)) {
                    for (const alias of cmd.aliases) {
                        aliasMap[alias.toLowerCase()] = cmd.name;
                    }
                }
            }
        }
    } catch (e) {}
}

// ── Terminal ──────────────────────────────────
function initTerminal() {
    const input = document.getElementById('terminalInput');
    const dropdown = document.getElementById('autocompleteDropdown');
    const output = document.getElementById('terminalOutput');
    output.tabIndex = 0;

    // Set last login time
    const lastLoginEl = document.getElementById('lastLogin');
    if (lastLoginEl) {
        lastLoginEl.textContent = new Date().toLocaleString('zh-CN', {
            year: 'numeric', month: '2-digit', day: '2-digit',
            hour: '2-digit', minute: '2-digit', second: '2-digit',
            hour12: false
        });
    }

    output.addEventListener('wheel', () => { setTimeout(() => { terminalScrollLocked = output.scrollHeight - output.scrollTop - output.clientHeight < 60; }, 0); }, { passive: true });
    output.addEventListener('keydown', (e) => {
        if (e.key === 'PageUp') { e.preventDefault(); output.scrollTop -= output.clientHeight - 40; terminalScrollLocked = false; }
        else if (e.key === 'PageDown') { e.preventDefault(); output.scrollTop += output.clientHeight - 40; if (output.scrollHeight - output.scrollTop - output.clientHeight < 60) terminalScrollLocked = true; }
        else if (e.key === 'End') { e.preventDefault(); output.scrollTop = output.scrollHeight; terminalScrollLocked = true; }
    });

    input.addEventListener('keydown', (e) => {
        if (dropdown.classList.contains('visible')) {
            if (e.key === 'ArrowDown') { e.preventDefault(); navigateAutocomplete(1); return; }
            if (e.key === 'ArrowUp') { e.preventDefault(); navigateAutocomplete(-1); return; }
            if (e.key === 'Tab') { e.preventDefault(); selectAutocomplete(); return; }
            if (e.key === 'Escape') { hideAutocomplete(); return; }
            if (e.key === 'Enter' && autocompleteIndex >= 0) { e.preventDefault(); selectAutocomplete(); return; }
        }
        if (e.key === 'Enter') {
            e.preventDefault();
            const cmd = input.value;
            input.value = '';
            hideAutocomplete();
            sendTerminalCommand(cmd);
        }
        else if (e.key === 'ArrowUp') { e.preventDefault(); if (historyIndex < commandHistory.length - 1) { historyIndex++; input.value = commandHistory[commandHistory.length - 1 - historyIndex]; } }
        else if (e.key === 'ArrowDown') { e.preventDefault(); if (historyIndex > 0) { historyIndex--; input.value = commandHistory[commandHistory.length - 1 - historyIndex]; } else { historyIndex = -1; input.value = ''; } }
        else if (e.key === 'Tab') { e.preventDefault(); showAutocomplete(input.value); }
        // Ctrl+L to clear
        else if (e.key === 'l' && e.ctrlKey) { e.preventDefault(); clearTerminal(); }
        // Ctrl+C to cancel
        else if (e.key === 'c' && e.ctrlKey && !window.getSelection().toString()) { e.preventDefault(); appendTerminalLine('^C', 'terminal-line-info'); input.value = ''; }
    });
    input.addEventListener('input', () => {
        const dropdown = document.getElementById('autocompleteDropdown');
        if (dropdown.classList.contains('visible')) {
            showAutocomplete(input.value);
        }
    });
    document.addEventListener('click', (e) => { if (!e.target.closest('.terminal-input-wrapper')) hideAutocomplete(); });

    updateTerminalStatus();
}

// Execute command via HTTP POST
async function sendTerminalCommand(command) {
    if (!command.trim()) return;
    commandHistory.push(command);
    historyIndex = -1;
    terminalCmdCount++;
    updateTerminalCounts();
    appendTerminalLine(`${currentUsername}@zenith:~$ ${command}`, 'terminal-line-command');

    const result = await apiCall('/commands', 'POST', { command });
    if (result) {
        if (result.output && Array.isArray(result.output) && result.output.length > 0) {
            result.output.forEach(line => appendTerminalLine(line, 'terminal-line-output'));
        } else if (result.success) {
            // empty success — no output needed
        } else {
            appendTerminalLine('Error: ' + (result.error || 'Unknown'), 'terminal-line-error');
        }
    } else {
        appendTerminalLine('请求失败 / Request failed', 'terminal-line-error');
    }
}

// ── Autocomplete ──────────────────────────────
const MAX_SUGGESTIONS = 20;

// Score a command against the user query — higher is better
function scoreCommand(cmd, q) {
    const name = cmd.name.toLowerCase();
    // Also check aliases
    const aliases = (cmd.aliases || []).map(a => a.toLowerCase());
    const targets = [name, ...aliases];

    let best = -1;
    let bestAlias = null;
    for (let i = 0; i < targets.length; i++) {
        const target = targets[i];
        let score = -1;

        // 1. Exact match
        if (target === q) { score = 1000; }

        // 2. Prefix match (e.g., "conn" → "connect")
        else if (target.startsWith(q)) { score = 800 - target.length; }

        // 3. Word-boundary prefix match (e.g., "fish" → "autoFish")
        else { score = wordBoundaryMatch(target, q); }

        // 4. Substring match (e.g., "stat" → "queueStatus")
        if (score < 0 && target.includes(q)) { score = 300; }

        // 5. Fuzzy match (characters in order with gaps)
        if (score < 0) { score = fuzzyMatch(target, q); }

        // 6. Multi-word match (e.g., "server sw" → "server switch")
        if (score < 0) { score = multiWordMatch(target, q); }

        if (score > best) {
            best = score;
            bestAlias = i > 0 ? cmd.aliases[i - 1] : null;
        }
    }

    if (best >= 0 && bestAlias) {
        cmd._aliasMatch = bestAlias;
    }
    return best;
}

// Match camelCase or word boundaries: "aR" → "autoReconnect", "cc" → "chatColor"
function wordBoundaryMatch(target, q) {
    // Find positions where a lowercase→uppercase transition occurs (camelCase)
    const boundaries = [0];
    for (let i = 1; i < target.length; i++) {
        if (target[i] !== target[i].toLowerCase() && target[i - 1] === target[i - 1].toLowerCase()) {
            boundaries.push(i);
        }
        // Also treat space/hyphen/underscore boundaries
        if (target[i - 1] === ' ' || target[i - 1] === '-' || target[i - 1] === '_') {
            boundaries.push(i);
        }
    }
    // Also treat boundaries after digits
    for (let i = 1; i < target.length; i++) {
        if (/\d/.test(target[i]) && !/\d/.test(target[i - 1])) boundaries.push(i);
        if (!/\d/.test(target[i]) && /\d/.test(target[i - 1])) boundaries.push(i);
    }

    for (const b of boundaries) {
        if (target.startsWith(q, b)) {
            return 600 - target.length + b; // earlier boundary is slightly better
        }
    }
    return -1;
}

// Fuzzy match: all characters of q appear in target in order (gaps allowed)
function fuzzyMatch(target, q) {
    let ti = 0, qi = 0;
    let gaps = 0;
    while (ti < target.length && qi < q.length) {
        if (target[ti] === q[qi]) { ti++; qi++; }
        else { ti++; gaps++; }
    }
    if (qi === q.length) return 150 - gaps;
    return -1;
}

// Multi-word match: "server sw" → "server switch"
function multiWordMatch(target, q) {
    const qWords = q.split(/\s+/).filter(Boolean);
    const tWords = target.split(/\s+/).filter(Boolean);
    if (qWords.length <= 1) return -1;
    if (qWords.length > tWords.length) return -1;

    let score = 700;
    for (let i = 0; i < qWords.length - 1; i++) {
        if (tWords[i] !== qWords[i]) return -1; // all but last word must be exact
    }
    const lastQ = qWords[qWords.length - 1];
    const lastT = tWords[qWords.length - 1];
    if (lastT.startsWith(lastQ)) { score += 100 - lastT.length; }
    else if (lastT.includes(lastQ)) { score += 20; }
    else return -1;
    return score;
}

// Build highlighted HTML for a matched command name
function highlightMatch(name, q) {
    if (!q) return escapeHtml(name);
    const lower = name.toLowerCase();
    const qLower = q.toLowerCase();

    // Find best match span to highlight
    let start = -1, end = -1;
    if (lower.startsWith(qLower)) {
        start = 0; end = qLower.length;
    } else if (lower.includes(qLower)) {
        start = lower.indexOf(qLower); end = start + qLower.length;
    } else {
        // Fuzzy: find first matching span greedily
        let qi = 0, first = -1, last = -1;
        for (let i = 0; i < lower.length && qi < qLower.length; i++) {
            if (lower[i] === qLower[qi]) {
                if (first === -1) first = i;
                last = i;
                qi++;
            }
        }
        if (qi === qLower.length) { start = first; end = last + 1; }
    }

    if (start >= 0) {
        return escapeHtml(name.substring(0, start))
            + '<span class="autocomplete-highlight">' + escapeHtml(name.substring(start, end)) + '</span>'
            + escapeHtml(name.substring(end));
    }
    return escapeHtml(name);
}

let autocompleteResultCache = null;
let autocompleteCacheKey = '';

function showAutocomplete(value) {
    const dropdown = document.getElementById('autocompleteDropdown');
    const q = value.trim().toLowerCase();

    // Use cache when query is a prefix of the previous query
    const cacheValid = autocompleteResultCache && q.startsWith(autocompleteCacheKey);

    if (!q) {
        autocompleteSuggestions = serverCommands.slice(0, MAX_SUGGESTIONS);
    } else if (cacheValid) {
        // Filter cached results with the longer query
        autocompleteSuggestions = autocompleteResultCache.filter(c => c._score >= 0);
    } else {
        // Score all commands
        const scored = serverCommands.map(c => ({
            ...c,
            _score: scoreCommand(c, q)
        })).filter(c => c._score >= 0)
          .sort((a, b) => b._score - a._score)
          .slice(0, MAX_SUGGESTIONS);
        autocompleteSuggestions = scored;
        autocompleteResultCache = scored;
        autocompleteCacheKey = q;
    }

    if (!autocompleteSuggestions.length) { hideAutocomplete(); return; }
    autocompleteIndex = -1;

    dropdown.innerHTML = autocompleteSuggestions.map((c, i) => {
        const desc = c.description ? `<span class="autocomplete-desc">${escapeHtml(c.description)}</span>` : '';
        const aliasHint = c._aliasMatch ? `<span class="autocomplete-alias-hint">← ${escapeHtml(c._aliasMatch)}</span>` : '';
        return `<div class="autocomplete-item" onmousedown="selectAutocompleteAt(${i})">
            <span class="autocomplete-name">${highlightMatch(c.name, q)}</span>${aliasHint}${desc}
        </div>`;
    }).join('');
    dropdown.classList.add('visible');
}

function navigateAutocomplete(d) {
    const items = document.querySelectorAll('.autocomplete-item');
    if (!items.length) return;
    if (autocompleteIndex >= 0) items[autocompleteIndex].classList.remove('selected');
    autocompleteIndex += d;
    if (autocompleteIndex < 0) autocompleteIndex = items.length - 1;
    if (autocompleteIndex >= items.length) autocompleteIndex = 0;
    items[autocompleteIndex].classList.add('selected');
    items[autocompleteIndex].scrollIntoView({ block: 'nearest' });
}

function selectAutocomplete() {
    if (autocompleteIndex >= 0 && autocompleteIndex < autocompleteSuggestions.length) {
        const cmd = autocompleteSuggestions[autocompleteIndex];
        const input = document.getElementById('terminalInput');
        // Preserve multi-word prefix: if user typed "server", fill "server switch"
        const qWords = input.value.trim().split(/\s+/).filter(Boolean);
        if (qWords.length > 1) {
            // Replace only the last word with the completed command's matching part
            const selectedWords = cmd.name.split(/\s+/);
            qWords[qWords.length - 1] = selectedWords[selectedWords.length - 1];
            input.value = qWords.join(' ') + ' ';
        } else {
            input.value = cmd.name + ' ';
        }
    }
    hideAutocomplete();
}

function selectAutocompleteAt(i) {
    const cmd = autocompleteSuggestions[i];
    const input = document.getElementById('terminalInput');
    const qWords = input.value.trim().split(/\s+/).filter(Boolean);
    if (qWords.length > 1) {
        const selectedWords = cmd.name.split(/\s+/);
        qWords[qWords.length - 1] = selectedWords[selectedWords.length - 1];
        input.value = qWords.join(' ') + ' ';
    } else {
        input.value = cmd.name + ' ';
    }
    hideAutocomplete();
    input.focus();
}

function hideAutocomplete() {
    autocompleteIndex = -1;
    autocompleteResultCache = null;
    autocompleteCacheKey = '';
    const d = document.getElementById('autocompleteDropdown');
    if (!d) return;
    d.classList.remove('visible');
    d.innerHTML = '';
}

// ── Terminal output ───────────────────────────
function addLogEntry(log) {
    logs.push(log);
    // Prevent unbounded memory growth
    if (logs.length > MAX_TERMINAL_LINES * 2) logs.splice(0, logs.length - MAX_TERMINAL_LINES);
    terminalLogCount++;
    updateTerminalCounts();
    if (currentPage !== 'terminal') return;
    const output = document.getElementById('terminalOutput');
    if (!output) return;
    const entry = document.createElement('div');
    entry.className = 'terminal-line terminal-line-log';
    const ts = new Date(log.timestamp).toLocaleTimeString();
    entry.innerHTML = `<span class="log-ts">${escapeHtml(ts)}</span><span class="log-lv ${escapeHtml(log.level.toLowerCase())}">${escapeHtml(log.level)}</span><span class="log-msg">[${escapeHtml(log.logger)}] ${escapeHtml(log.message)}</span>`;
    output.appendChild(entry);
    if (terminalScrollLocked) output.scrollTop = output.scrollHeight;
    while (output.children.length > MAX_TERMINAL_LINES) output.removeChild(output.firstChild);
}
function appendTerminalLine(text, className) {
    const output = document.getElementById('terminalOutput');
    if (!output) return;
    const line = document.createElement('div');
    line.className = `terminal-line ${className}`;
    line.textContent = text;
    output.appendChild(line);
    if (terminalScrollLocked) output.scrollTop = output.scrollHeight;
    while (output.children.length > MAX_TERMINAL_LINES) output.removeChild(output.firstChild);
}
function clearTerminal() {
    document.getElementById('terminalOutput').innerHTML = `
        <div class="terminal-welcome">
            <p style="color:var(--ctp-green);">Welcome to ZenithProxy Terminal</p>
            <p style="color:var(--ctp-overlay1);">Last login: <span id="lastLogin">${new Date().toLocaleString('zh-CN', { year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', second: '2-digit', hour12: false })}</span></p>
            <p style="color:var(--ctp-overlay0);font-size:0.75rem;margin-top:4px;">Type 'help' for available commands | Tab: autocomplete | Up/Down: history | Ctrl+L: clear</p>
        </div>`;
    logs = [];
    terminalLogCount = 0;
    terminalCmdCount = 0;
    updateTerminalCounts();
}

function updateTerminalCounts() {
    const cmdEl = document.getElementById('terminalCmdCount');
    const logEl = document.getElementById('terminalLogCount');
    if (cmdEl) cmdEl.textContent = `命令: ${terminalCmdCount}`;
    if (logEl) logEl.textContent = `日志: ${terminalLogCount}`;
}

function updateTerminalStatus() {
    const statusEl = document.getElementById('terminalStatus');
    if (statusEl) {
        statusEl.textContent = '已连接';
        statusEl.style.color = 'var(--ctp-green)';
    }
}

function exportTerminalContent() {
    const output = document.getElementById('terminalOutput');
    const lines = [];
    const now = new Date().toISOString().replace('T', ' ').slice(0, 19);
    lines.push('='.repeat(60), `ZenithProxy Terminal Export — ${now}`, '='.repeat(60), '');
    for (const el of output.children) lines.push(el.textContent);
    if (commandHistory.length > 0) {
        lines.push('', '-'.repeat(60), 'Command History', '-'.repeat(60));
        commandHistory.forEach((c, i) => lines.push(`  [${i + 1}] ${c}`));
    }
    lines.push('', '='.repeat(60), 'End');
    const a = document.createElement('a');
    a.href = URL.createObjectURL(new Blob([lines.join('\n')], { type: 'text/plain;charset=utf-8' }));
    a.download = `zenithproxy-terminal-${new Date().toISOString().split('T')[0]}.txt`;
    a.click();
    showNotification('已导出 / Exported', 'success');
}

// ── Players ───────────────────────────────────
function escapeHtml(str) {
    if (!str) return '';
    // Single-pass replacement avoids 5x full-string scans
    return str.replace(/[&<>"']/g, function(m) {
        switch (m) {
            case '&': return '&amp;';
            case '<': return '&lt;';
            case '>': return '&gt;';
            case '"': return '&quot;';
            case "'": return '&#039;';
            default:  return m;
        }
    });
}

// ── Whitelist ─────────────────────────────────
async function loadWhitelist() {
    const data = await apiCall('/whitelist');
    if (!data) return;
    document.getElementById('whitelistEnabled').checked = data.enabled;
    document.getElementById('whitelistAutoAdd').checked = data.autoAddClient;
    document.getElementById('whitelistKickMsg').value = data.kickmsg || '';
    const list = document.getElementById('whitelistList');
    if (!data.whitelist || !data.whitelist.length) {
        list.innerHTML = '<div class="whitelist-empty">白名单为空 / Whitelist is empty</div>';
    } else {
        list.innerHTML = data.whitelist.map(x =>
            `<div class="whitelist-entry">
                <span class="whitelist-entry-name">${escapeHtml(x.username)}</span>
                <span class="whitelist-entry-uuid">${escapeHtml(x.uuid)}</span>
                <button class="btn btn-danger btn-sm" onclick="removeWhitelistPlayer('${escapeHtml(x.username)}')">移除</button>
            </div>`
        ).join('');
    }
}

async function toggleWhitelist(enabled) {
    await apiCall('/whitelist', 'POST', { action: 'toggle', enabled });
    showNotification(enabled ? '白名单已启用 / Whitelist enabled' : '白名单已禁用 / Whitelist disabled', 'success');
}

async function addWhitelistPlayer() {
    const input = document.getElementById('whitelistAddInput');
    const username = input.value.trim();
    if (!username) return;
    const r = await apiCall('/whitelist', 'POST', { action: 'add', username });
    if (r && r.success) {
        input.value = '';
        showNotification(`已添加 ${r.username} / Added ${r.username}`, 'success');
        loadWhitelist();
    } else {
        showNotification('添加失败 / ' + (r?.error || 'Error'), 'error');
    }
}

async function removeWhitelistPlayer(username) {
    const r = await apiCall('/whitelist', 'POST', { action: 'remove', username });
    if (r && r.success) {
        showNotification(`已移除 ${username} / Removed ${username}`, 'success');
        loadWhitelist();
    } else {
        showNotification('移除失败 / ' + (r?.error || 'Error'), 'error');
    }
}

async function updateWhitelistConfig() {
    const kickmsg = document.getElementById('whitelistKickMsg').value;
    const autoAddClient = document.getElementById('whitelistAutoAdd').checked;
    await apiCall('/whitelist', 'POST', { action: 'updateConfig', kickmsg, autoAddClient });
    showNotification('配置已保存 / Config saved', 'success');
}

// ── Server Control ────────────────────────────
async function serverConnect() {
    const r = await apiCall('/commands', 'POST', { command: 'connect' });
    if (r && r.success) {
        showNotification('正在连接服务器 / Connecting...', 'success');
    } else {
        showNotification('连接失败 / ' + (r?.error || 'Error'), 'error');
    }
}

async function serverDisconnect() {
    const r = await apiCall('/commands', 'POST', { command: 'disconnect' });
    if (r && r.success) {
        showNotification('已断开连接 / Disconnected', 'success');
    } else {
        showNotification('断开失败 / ' + (r?.error || 'Error'), 'error');
    }
}

async function serverReconnect() {
    const r = await apiCall('/commands', 'POST', { command: 'reconnect' });
    if (r && r.success) {
        showNotification('正在重连 / Reconnecting...', 'success');
    } else {
        showNotification('重连失败 / ' + (r?.error || 'Error'), 'error');
    }
}

async function serverShutdown() {
    if (!confirm('确定要关闭代理吗？ / Are you sure you want to shutdown?')) return;
    const r = await apiCall('/commands', 'POST', { command: 'shutdown' });
    if (r && r.success) {
        showNotification('正在关闭 / Shutting down...', 'success');
    } else {
        showNotification('关闭失败 / ' + (r?.error || 'Error'), 'error');
    }
}

// ── Utility ───────────────────────────────────
function getPingClass(ping) {
    if (ping < 0) return '';
    if (ping <= 100) return 'ping-good';
    if (ping <= 200) return 'ping-medium';
    return 'ping-bad';
}

function showNotification(message, type = 'info') {
    const n = document.createElement('div');
    n.textContent = message;
    const colors = { success: 'var(--success-color)', error: 'var(--error-color)', warning: 'var(--warning-color)' };
    n.style.cssText = `position:fixed;top:16px;right:16px;padding:12px 20px;border-radius:4px;color:var(--ctp-crust);font-weight:500;font-size:0.85rem;z-index:1000;opacity:0;transform:translateY(-8px);transition:opacity 0.2s,transform 0.2s;background:${colors[type] || 'var(--info-color)'};`;
    document.body.appendChild(n);
    requestAnimationFrame(() => { n.style.opacity = '1'; n.style.transform = 'translateY(0)'; });
    setTimeout(() => { n.style.opacity = '0'; n.style.transform = 'translateY(-8px)'; setTimeout(() => n.remove(), 200); }, 3000);
}

// ══════════════════════════════════════════════════════════════
//  Config Center
//  数据来源：/api/config（当前运行配置快照）+ config-meta.json（中文标签）
//  + config-default.json（ZenithProxy 默认值）
// ══════════════════════════════════════════════════════════════

const CFG = {
    config: null,      // 当前配置树（服务端快照，含本地未保存改动）
    meta: {},          // { "a.b.c": {label, desc} }
    defaults: null,    // 默认配置树
    dirty: {},         // 待提交改动 { path: value }
    masked: new Set(), // 服务端打码的路径
    mask: '********',
    loaded: false,
    loading: false
};

// 场景预设 —— 与 hebaiju.cn/zenith-config 保持一致
const CFG_B2B2T_ON = {
    'server.ping.lanBroadcast': true,
    'server.dynamicQueueEtaEquation': true,
    'server.queueStatusRefreshWhileNotOn2b2t': true,
    'server.queueStatusRefreshMinutes': 5
};
const CFG_B2B2T_OFF = {
    'server.ping.lanBroadcast': false,
    'server.dynamicQueueEtaEquation': false,
    'server.queueStatusRefreshWhileNotOn2b2t': false,
    'server.queueStatusRefreshMinutes': 2147483647
};
const CFG_SCENARIOS = [
    {
        key: 'b2b2t', icon: '🏆', name: '2b2t 正版挂机', desc: '正版账号 + 2b2t.org',
        set: { 'authentication.accountType': 'device_code', 'client.server.address': '2b2t.org', 'server.verifyUsers': true }
    },
    {
        key: 'offline', icon: '🏰', name: '离线插件服', desc: '离线账号 + AuthMe 密码（3C3U.ORG）',
        set: {
            'authentication.accountType': 'offline', 'client.server.address': '3C3U.ORG',
            'server.verifyUsers': false,
            'client.keepAliveHandling.keepAliveMode': 'INDEPENDENT',
            'client.keepAliveHandling.keepAliveQueueTimeoutMs': 200000
        }
    },
    {
        key: 'other', icon: '🌐', name: '其他正版服', desc: '正版账号 + 3C3U.ORG',
        set: { 'authentication.accountType': 'device_code', 'client.server.address': '3C3U.ORG', 'server.verifyUsers': true }
    }
];

const CFG_REQUIRED_GROUPS = [
    ['账号登录', [
        'authentication.accountType', 'authentication.email', 'authentication.username',
        'authentication.password', 'authentication.openBrowserOnLogin'
    ]],
    ['服务器', [
        'client.server.address', 'client.server.port', 'client.viaversion.enabled',
        'client.viaversion.protocolVersion', 'server.bind.port', 'client.autoConnect',
        'server.extra.whitelist.whitelist'
    ]]
];
const CFG_MORE_GROUPS = [
    ['挂机保护', [
        'client.extra.antiafk.enabled', 'client.extra.autoRespawn.enabled', 'client.extra.autoEat.enabled',
        'client.extra.autoTotem.enabled', 'client.extra.autoReconnect.enabled'
    ]],
    ['服务端接入', [
        'server.verifyUsers', 'server.extra.whitelist.enable', 'server.extra.whitelist.autoAddClient',
        'server.ping.onlinePlayerCount'
    ]],
    ['连接保活', [
        'client.keepAliveHandling.keepAliveMode', 'client.keepAliveHandling.keepAliveQueueTimeoutMs'
    ]],
    ['提醒与通知', [
        'client.extra.visualRange.enabled', 'client.extra.sessionTimeLimit.enabled', 'discord.enable'
    ]]
];

const CFG_MODULE_NAMES = {
    authentication: '账号认证', client: '客户端', debug: '调试', server: '服务端',
    plugins: '插件', interactiveTerminal: '交互终端', inGameCommands: '游戏内命令',
    theme: '主题配色', discord: 'Discord', database: '数据库', autoUpdater: '自动更新'
};

function cfgGetPath(obj, path) {
    return path.split('.').reduce((o, k) => (o == null ? undefined : o[k]), obj);
}

function cfgSetPath(obj, path, val) {
    const keys = path.split('.');
    let o = obj;
    for (let i = 0; i < keys.length - 1; i++) {
        if (typeof o[keys[i]] !== 'object' || o[keys[i]] === null) o[keys[i]] = {};
        o = o[keys[i]];
    }
    o[keys[keys.length - 1]] = val;
}

function cfgIsSensitivePath(path) {
    return CFG.masked.has(path);
}

function cfgIsScalar(v) {
    return typeof v === 'boolean' || typeof v === 'number' || typeof v === 'string';
}

function cfgLabel(path) {
    const m = CFG.meta[path];
    return m && m.label ? m.label : path.split('.').pop();
}

function cfgDesc(path) {
    const m = CFG.meta[path];
    return m && m.desc ? m.desc : '';
}

function cfgStatus(text, kind) {
    const el = document.getElementById('cfgStatus');
    if (!el) return;
    el.textContent = text;
    el.className = 'cfg-status' + (kind ? ' cfg-status-' + kind : '');
    el.style.display = text ? '' : 'none';
}

async function cfgEnter() {
    if (CFG.loaded || CFG.loading) {
        cfgUpdateDirtyBar();
        return;
    }
    await cfgLoad();
}

async function cfgLoad() {
    CFG.loading = true;
    cfgStatus('正在读取配置...');
    try {
        const [metaRes, defaultRes, cfgRes] = await Promise.all([
            fetch('config-meta.json', { credentials: 'include' }),
            fetch('config-default.json', { credentials: 'include' }),
            fetch(API_BASE + '/config', { credentials: 'include' })
        ]);
        if (!metaRes.ok || !defaultRes.ok) throw new Error('静态资源加载失败');
        if (cfgRes.status === 401) { window.location.href = '/login.html'; return; }

        CFG.meta = await metaRes.json();
        CFG.defaults = await defaultRes.json();
        const data = await cfgRes.json();
        if (!data.success) throw new Error(data.error || '读取配置失败');

        CFG.config = data.config;
        CFG.mask = data.mask || '********';
        CFG.masked = new Set(data.masked || []);
        CFG.dirty = {};
        CFG.loaded = true;

        cfgRenderScenarios();
        cfgRenderGroups('cfgRequiredFields', CFG_REQUIRED_GROUPS);
        cfgRenderGroups('cfgMoreFields', CFG_MORE_GROUPS);
        cfgRenderTree();
        cfgUpdateDirtyBar();

        const maskedNote = CFG.masked.size ? `，${CFG.masked.size} 个敏感字段已隐藏` : '';
        cfgStatus(`已读取当前配置${maskedNote}。改动后点"保存"，写入运行内存；部分设置需重启 ZenithProxy 后生效。`, 'ok');
    } catch (e) {
        cfgStatus('读取配置失败：' + (e && e.message ? e.message : e), 'error');
    } finally {
        CFG.loading = false;
    }
}

function cfgReload() {
    CFG.loaded = false;
    CFG.dirty = {};
    cfgLoad();
}

function cfgRenderScenarios() {
    const root = document.getElementById('cfgScenarios');
    if (!root) return;
    root.innerHTML = '';
    CFG_SCENARIOS.forEach(sc => {
        const card = document.createElement('button');
        card.type = 'button';
        card.className = 'cfg-scenario';
        card.innerHTML = '<span class="cfg-scenario-icon">' + sc.icon + '</span>' +
            '<span class="cfg-scenario-name">' + sc.name + '</span>' +
            '<span class="cfg-scenario-desc">' + sc.desc + '</span>';
        card.addEventListener('click', () => cfgApplyScenario(sc));
        root.appendChild(card);
    });
}

function cfgApplyScenario(sc) {
    Object.keys(sc.set).forEach(p => {
        cfgSetPath(CFG.config, p, sc.set[p]);
        CFG.dirty[p] = sc.set[p];
    });
    // 2b2t 定制项：2b2t 场景打开，其他场景关闭
    const tune = sc.key === 'b2b2t' ? CFG_B2B2T_ON : CFG_B2B2T_OFF;
    Object.keys(tune).forEach(p => {
        cfgSetPath(CFG.config, p, tune[p]);
        CFG.dirty[p] = tune[p];
    });
    cfgRenderGroups('cfgRequiredFields', CFG_REQUIRED_GROUPS);
    cfgRenderGroups('cfgMoreFields', CFG_MORE_GROUPS);
    cfgRenderTree();
    cfgUpdateDirtyBar();
    toast('已应用场景：' + sc.name + '，记得点保存', 'info');
}

/** 渲染一组「标题 + 字段路径列表」 */
function cfgRenderGroups(containerId, groups) {
    const root = document.getElementById(containerId);
    if (!root) return;
    root.innerHTML = '';
    groups.forEach(([title, paths]) => {
        const group = document.createElement('div');
        group.className = 'cfg-group';
        const h = document.createElement('div');
        h.className = 'cfg-group-title';
        h.textContent = title;
        group.appendChild(h);
        paths.forEach(p => {
            const row = cfgBuildRow(p);
            if (row) group.appendChild(row);
        });
        root.appendChild(group);
    });
}

/** 为单个路径构建一行控件；路径不存在时返回提示行 */
function cfgBuildRow(path) {
    const value = cfgGetPath(CFG.config, path);
    const row = document.createElement('div');
    row.className = 'cfg-row';

    const labelWrap = document.createElement('div');
    labelWrap.className = 'cfg-label';
    const name = document.createElement('span');
    name.className = 'cfg-name';
    name.textContent = cfgLabel(path);
    const code = document.createElement('span');
    code.className = 'cfg-path';
    code.textContent = path;
    labelWrap.appendChild(name);
    labelWrap.appendChild(code);
    const desc = cfgDesc(path);
    if (desc) {
        const d = document.createElement('span');
        d.className = 'cfg-desc';
        d.textContent = desc;
        labelWrap.appendChild(d);
    }
    row.appendChild(labelWrap);

    const inputWrap = document.createElement('div');
    inputWrap.className = 'cfg-input';
    inputWrap.appendChild(cfgBuildControl(path, value));
    row.appendChild(inputWrap);
    return row;
}

/** 按值类型生成控件；数组/对象/未知路径给只读提示 */
function cfgBuildControl(path, value) {
    if (value === undefined) {
        const span = document.createElement('span');
        span.className = 'cfg-readonly';
        span.textContent = '当前配置中不存在该字段';
        return span;
    }
    if (Array.isArray(value)) {
        const span = document.createElement('span');
        span.className = 'cfg-readonly';
        span.textContent = '数组字段（' + value.length + ' 项），请在对应页面或 config.json 中编辑';
        return span;
    }
    if (value !== null && typeof value === 'object') {
        const span = document.createElement('span');
        span.className = 'cfg-readonly';
        span.textContent = '对象字段，请展开下方"全部字段"逐项编辑';
        return span;
    }

    if (typeof value === 'boolean') {
        const label = document.createElement('label');
        label.className = 'toggle';
        const input = document.createElement('input');
        input.type = 'checkbox';
        input.checked = value;
        input.addEventListener('change', () => cfgOnChange(path, input.checked));
        const slider = document.createElement('span');
        slider.className = 'toggle-slider';
        label.appendChild(input);
        label.appendChild(slider);
        return label;
    }

    const input = document.createElement('input');
    if (typeof value === 'number') {
        input.type = 'number';
        input.step = Number.isInteger(value) ? '1' : 'any';
        input.value = value;
        input.addEventListener('change', () => {
            const n = Number(input.value);
            if (Number.isFinite(n)) cfgOnChange(path, n);
            else input.value = cfgGetPath(CFG.config, path);
        });
    } else {
        const sensitive = cfgIsSensitivePath(path);
        input.type = sensitive ? 'password' : 'text';
        input.value = value;
        input.spellcheck = false;
        if (sensitive) input.placeholder = '已隐藏，留空表示不修改';
        input.addEventListener('change', () => cfgOnChange(path, input.value));
    }
    input.className = 'cfg-input-el';
    return input;
}

function cfgOnChange(path, value) {
    cfgSetPath(CFG.config, path, value);
    CFG.dirty[path] = value;
    cfgUpdateDirtyBar();
}

function cfgUpdateDirtyBar() {
    const bar = document.getElementById('cfgDirtyBar');
    const cnt = document.getElementById('cfgDirtyCount');
    if (!bar || !cnt) return;
    const n = Object.keys(CFG.dirty).length;
    cnt.textContent = n + ' 项待保存';
    bar.classList.toggle('visible', n > 0);
}

/** 全部字段：按顶层模块折叠，递归到叶子 */
function cfgRenderTree() {
    const root = document.getElementById('cfgTree');
    if (!root || !CFG.config) return;
    root.innerHTML = '';
    Object.keys(CFG.config).forEach(key => {
        if (key === 'warning' || key === 'deprecationWarning_26_1_2') return;
        root.appendChild(cfgBuildBranch(key, CFG.config[key], key, 0));
    });
}

function cfgBuildBranch(name, value, path, depth) {
    const wrap = document.createElement('details');
    wrap.className = 'cfg-branch';
    if (depth === 0) wrap.open = false;

    const summary = document.createElement('summary');
    summary.className = 'cfg-branch-summary';
    const moduleName = CFG_MODULE_NAMES[path];
    summary.textContent = moduleName ? moduleName + ' · ' + name : name;
    wrap.appendChild(summary);

    if (value !== null && typeof value === 'object' && !Array.isArray(value)) {
        const keys = Object.keys(value);
        if (keys.length === 0) {
            const empty = document.createElement('div');
            empty.className = 'cfg-readonly cfg-branch-empty';
            empty.textContent = '（空对象）';
            wrap.appendChild(empty);
        }
        keys.forEach(k => {
            const childPath = path + '.' + k;
            const childValue = value[k];
            if (childValue !== null && typeof childValue === 'object' && !Array.isArray(childValue)) {
                wrap.appendChild(cfgBuildBranch(k, childValue, childPath, depth + 1));
            } else {
                wrap.appendChild(cfgBuildRow(childPath));
            }
        });
    } else {
        wrap.appendChild(cfgBuildRow(path));
    }
    return wrap;
}

async function cfgApply() {
    const patch = CFG.dirty;
    const n = Object.keys(patch).length;
    if (n === 0) {
        toast('没有改动需要保存', 'info');
        return;
    }
    cfgStatus('正在保存 ' + n + ' 项改动...');
    try {
        const res = await fetch(API_BASE + '/config', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            credentials: 'include',
            body: JSON.stringify({ patch })
        });
        if (res.status === 401) { window.location.href = '/login.html'; return; }
        const data = await res.json();

        if (data.success) {
            CFG.dirty = {};
            cfgUpdateDirtyBar();
            const parts = ['已写入 ' + data.applied + ' 项'];
            if (data.skipped && data.skipped.length) parts.push('跳过 ' + data.skipped.length + ' 项');
            if (data.unchanged && data.unchanged.length) parts.push('敏感字段未改动 ' + data.unchanged.length + ' 项');
            cfgStatus(parts.join('，') + '。' + (data.note || ''), 'ok');
            toast('已写入 ' + data.applied + ' 项，重启后生效', 'success');
            if (data.failed && data.failed.length) {
                cfgStatus('部分字段写入失败：' + data.failed.join('; '), 'error');
            }
        } else {
            const msg = (data.failed && data.failed.length)
                ? data.failed.join('; ')
                : (data.error || '保存失败');
            cfgStatus('保存失败：' + msg, 'error');
            toast('保存失败', 'error');
        }
    } catch (e) {
        cfgStatus('保存失败：' + (e && e.message ? e.message : e), 'error');
        toast('保存失败', 'error');
    }
}

function cfgDiscard() {
    CFG.dirty = {};
    CFG.loaded = false;
    cfgLoad();
    toast('已放弃未保存的改动', 'info');
}

function cfgRestoreDefaults() {
    if (!CFG.defaults) return;
    if (!confirm('把表单恢复成 ZenithProxy 默认值？敏感字段（密码/令牌）不会被覆盖。')) return;
    CFG.config = JSON.parse(JSON.stringify(CFG.defaults));
    CFG.dirty = {};
    // 只把"非敏感且确实不同"的字段标为待保存，避免误覆盖密码
    cfgCollectLeaves(CFG.defaults, '', (path, val) => {
        if (!cfgIsScalar(val)) return;
        if (cfgIsSensitivePath(path)) return;
        CFG.dirty[path] = val;
    });
    cfgRenderGroups('cfgRequiredFields', CFG_REQUIRED_GROUPS);
    cfgRenderGroups('cfgMoreFields', CFG_MORE_GROUPS);
    cfgRenderTree();
    cfgUpdateDirtyBar();
    cfgStatus('已载入默认值，' + Object.keys(CFG.dirty).length + ' 项待保存。敏感字段保持原值。', 'ok');
}

function cfgCollectLeaves(obj, prefix, cb) {
    if (obj === null || typeof obj !== 'object' || Array.isArray(obj)) {
        if (prefix) cb(prefix, obj);
        return;
    }
    Object.keys(obj).forEach(k => {
        const p = prefix ? prefix + '.' + k : k;
        cfgCollectLeaves(obj[k], p, cb);
    });
}

function cfgDownload() {
    if (!CFG.config) return;
    const text = JSON.stringify(CFG.config, null, 2);
    const blob = new Blob([text], { type: 'application/json' });
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = 'config.json';
    document.body.appendChild(a);
    a.click();
    document.body.removeChild(a);
    setTimeout(() => URL.revokeObjectURL(url), 1000);
    const n = Object.keys(CFG.dirty).length;
    toast(n > 0 ? ('已导出（含 ' + n + ' 项未保存改动）') : '已导出 config.json', 'success');
}
