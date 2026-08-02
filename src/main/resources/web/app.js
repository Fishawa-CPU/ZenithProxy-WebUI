// ZenithProxy Web UI JavaScript

// Global variables
let ws = null;
let wsClientId = null;
let logs = [];
let commandHistory = [];
let historyIndex = -1;
let wsPort = 8081;
const API_BASE = window.location.origin + '/api';

// Autocomplete
let autocompleteIndex = -1;
let autocompleteSuggestions = [];

// Charts
let cpuChart = null;
let memoryChart = null;
let networkChart = null;
let latencyChart = null;
const MAX_DATA_POINTS = 60;
const MAX_TERMINAL_LINES = 500;

// Terminal
let terminalScrollLocked = true;
let terminalBusy = false;
let currentPage = 'dashboard';

// ZenithProxy real commands
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

let serverCommands = KNOWN_COMMANDS;

// ── Init ──────────────────────────────────────
document.addEventListener('DOMContentLoaded', () => {
    initNavigation();
    initWebSocket();
    initTerminal();
    initConfigTabs();
    loadDashboard();
    loadCommands();
});

// ── Navigation ────────────────────────────────
function initNavigation() {
    document.querySelectorAll('.nav-item').forEach(item => {
        item.addEventListener('click', () => navigateTo(item.dataset.page));
    });
}

function navigateTo(page) {
    currentPage = page;
    document.querySelectorAll('.nav-item').forEach(item => {
        item.classList.toggle('active', item.dataset.page === page);
    });
    document.querySelectorAll('.page').forEach(p => p.classList.remove('active'));
    document.getElementById(page).classList.add('active');

    switch (page) {
        case 'dashboard': loadDashboard(); initCharts(); startSystemPolling(); break;
        case 'modules': loadModules(); break;
        case 'config': loadConfig(); break;
        case 'players': loadPlayers(); break;
    }

    // Stop polling when leaving dashboard
    if (page !== 'dashboard') stopSystemPolling();
}

// ── API ───────────────────────────────────────
async function apiCall(endpoint, method = 'GET', body = null) {
    try {
        const opts = { method, headers: { 'Content-Type': 'application/json' } };
        if (body) opts.body = JSON.stringify(body);
        const r = await fetch(API_BASE + endpoint, opts);
        return await r.json();
    } catch (e) {
        console.error('API error:', e);
        return null;
    }
}

// ── Dashboard ─────────────────────────────────
async function loadDashboard() {
    const s = await apiCall('/status');
    if (!s) return;
    if (s.websocketPort) wsPort = s.websocketPort;
    document.getElementById('proxyStatus').textContent = s.connected ? '已连接' : '未连接';
    document.getElementById('proxyStatus').style.color = s.connected ? 'var(--success-color)' : 'var(--error-color)';
    document.getElementById('currentUsername').textContent = s.username || '-';
    document.getElementById('proxyAddress').textContent = s.proxyAddress || '-';
    document.getElementById('mcVersion').textContent = s.version || '-';
    if (s.uptime) {
        const h = Math.floor(s.uptime / 3600000);
        const m = Math.floor((s.uptime % 3600000) / 60000);
        const sec = Math.floor((s.uptime % 60000) / 1000);
        document.getElementById('uptime').textContent = `${h}时 ${m}分 ${sec}秒`;
    }
    if (s.inQueue) {
        document.getElementById('queueStatus').textContent = `队列位置: ${s.queuePosition}`;
        document.getElementById('queueStatus').style.color = 'var(--warning-color)';
    } else {
        document.getElementById('queueStatus').textContent = '不在队列中';
        document.getElementById('queueStatus').style.color = 'var(--success-color)';
    }
    document.getElementById('version').textContent = 'v' + s.version;
}

function refreshDashboard() { loadDashboard(); }

// ── System Charts ─────────────────────────────
const chartColors = {
    cpu: 'rgba(198, 160, 246, 1)', cpuBg: 'rgba(198, 160, 246, 0.15)',
    memory: 'rgba(166, 218, 149, 1)', memoryBg: 'rgba(166, 218, 149, 0.15)',
    netIn: 'rgba(138, 173, 244, 1)', netInBg: 'rgba(138, 173, 244, 0.15)',
    netOut: 'rgba(245, 169, 127, 1)', netOutBg: 'rgba(245, 169, 127, 0.15)',
    grid: 'rgba(110, 115, 141, 0.15)', text: '#8087a2',
};

function makeTimeLabels(count) {
    const labels = [];
    const now = new Date();
    for (let i = count - 1; i >= 0; i--) {
        const t = new Date(now - i * 5000);
        labels.push(t.toLocaleTimeString('zh-CN', { hour12: false, hour: '2-digit', minute: '2-digit', second: '2-digit' }));
    }
    return labels;
}

function makeChartOptions(unit, suggestedMax) {
    return {
        responsive: true, maintainAspectRatio: false,
        animation: { duration: 0 },
        interaction: { intersect: false, mode: 'index' },
        plugins: {
            legend: { display: false },
            tooltip: {
                backgroundColor: 'rgba(30, 32, 48, 0.95)', titleColor: '#cad3f5',
                bodyColor: '#b8c0e0', borderColor: 'rgba(91, 96, 120, 0.5)',
                borderWidth: 1, padding: 8, displayColors: true,
            }
        },
        scales: {
            x: {
                display: true,
                grid: { color: chartColors.grid, drawBorder: false },
                ticks: { color: chartColors.text, font: { size: 10 }, maxTicksLimit: 6 },
            },
            y: {
                display: true, min: 0, suggestedMax: suggestedMax,
                grid: { color: chartColors.grid, drawBorder: false },
                ticks: { color: chartColors.text, font: { size: 10 }, maxTicksLimit: 5, callback: v => v + (unit || '') },
            }
        }
    };
}

function createLineDataset(label, color, bgColor) {
    return { label, data: [], borderColor: color, backgroundColor: bgColor, borderWidth: 1.5, pointRadius: 0, fill: true, tension: 0.3 };
}

let chartsInitialized = false;

function initCharts() {
    if (chartsInitialized) return;
    chartsInitialized = true;
    const timeLabels = makeTimeLabels(MAX_DATA_POINTS);

    cpuChart = new Chart(document.getElementById('cpuChart'), {
        type: 'line', data: { labels: timeLabels, datasets: [createLineDataset('CPU', chartColors.cpu, chartColors.cpuBg)] },
        options: makeChartOptions('%', 100),
    });
    memoryChart = new Chart(document.getElementById('memoryChart'), {
        type: 'line', data: { labels: timeLabels, datasets: [createLineDataset('Memory', chartColors.memory, chartColors.memoryBg)] },
        options: makeChartOptions('%', 100),
    });
    networkChart = new Chart(document.getElementById('networkChart'), {
        type: 'line', data: { labels: timeLabels, datasets: [
            createLineDataset('In', chartColors.netIn, chartColors.netInBg),
            createLineDataset('Out', chartColors.netOut, chartColors.netOutBg),
        ]},
        options: {
            ...makeChartOptions('', undefined),
            plugins: {
                ...makeChartOptions('').plugins,
                legend: { display: true, position: 'top', labels: { color: chartColors.text, boxWidth: 12, padding: 10, font: { size: 11 } } },
            }
        },
    });
    latencyChart = new Chart(document.getElementById('latencyChart'), {
        type: 'line', data: { labels: timeLabels, datasets: [createLineDataset('Ping', 'rgba(238, 212, 159, 1)', 'rgba(238, 212, 159, 0.15)')] },
        options: makeChartOptions('ms', 200),
    });
}

let systemPollTimer = null;

function startSystemPolling() {
    if (systemPollTimer) return; // already running
    fetchSystemStats();
    systemPollTimer = setInterval(fetchSystemStats, 5000);
}

function stopSystemPolling() {
    if (systemPollTimer) { clearInterval(systemPollTimer); systemPollTimer = null; }
}

async function fetchSystemStats() {
    if (currentPage !== 'dashboard') { stopSystemPolling(); return; }

    const [sysData, latData] = await Promise.all([
        apiCall('/system'),
        apiCall('/latency'),
    ]);

    const now = new Date().toLocaleTimeString('zh-CN', { hour12: false, hour: '2-digit', minute: '2-digit', second: '2-digit' });

    if (sysData && cpuChart) {
        cpuChart.data.datasets[0].data.push(Math.max(0, sysData.cpuLoad || 0));
        cpuChart.data.labels.push(now);
        if (cpuChart.data.datasets[0].data.length > MAX_DATA_POINTS) { cpuChart.data.datasets[0].data.shift(); cpuChart.data.labels.shift(); }
        cpuChart.update('none');
    }
    if (sysData && memoryChart) {
        memoryChart.data.datasets[0].data.push(sysData.memoryPercent || 0);
        memoryChart.data.labels.push(now);
        if (memoryChart.data.datasets[0].data.length > MAX_DATA_POINTS) { memoryChart.data.datasets[0].data.shift(); memoryChart.data.labels.shift(); }
        memoryChart.update('none');
    }
    if (sysData && networkChart) {
        networkChart.data.datasets[0].data.push(Math.round((sysData.netBytesPerSecIn || 0) / 1024 * 10) / 10);
        networkChart.data.datasets[1].data.push(Math.round((sysData.netBytesPerSecOut || 0) / 1024 * 10) / 10);
        networkChart.data.labels.push(now);
        if (networkChart.data.datasets[0].data.length > MAX_DATA_POINTS) {
            networkChart.data.datasets[0].data.shift();
            networkChart.data.datasets[1].data.shift();
            networkChart.data.labels.shift();
        }
        networkChart.update('none');
    }
    if (latData && latencyChart) {
        latencyChart.data.datasets[0].data.push(latData.connected ? latData.latency : 0);
        latencyChart.data.labels.push(now);
        if (latencyChart.data.datasets[0].data.length > MAX_DATA_POINTS) { latencyChart.data.datasets[0].data.shift(); latencyChart.data.labels.shift(); }
        latencyChart.update('none');
    }
}

// ── Modules ───────────────────────────────────
async function loadModules() {
    const modules = await apiCall('/modules');
    if (!modules) return;
    document.getElementById('modulesList').innerHTML = modules.map(m => `
        <div class="module-card">
            <div class="module-info">
                <h3>${m.name}</h3>
                <p>${m.enabled ? '已启用' : '已禁用'}</p>
            </div>
            <label class="toggle">
                <input type="checkbox" ${m.enabled ? 'checked' : ''} onchange="toggleModule('${m.name}', this.checked)">
                <span class="toggle-slider"></span>
            </label>
        </div>
    `).join('');
}

async function toggleModule(name, enabled) {
    await apiCall('/modules', 'POST', { name, enabled });
    loadModules();
}

function refreshModules() { loadModules(); }

// ── Config ────────────────────────────────────
async function loadConfig() {
    const config = await apiCall('/config');
    if (config) renderConfigSections(config);
}

function renderConfigSections(config) {
    document.getElementById('configContent').innerHTML = `
        <div class="config-section active" id="authSection">
            <h3>认证设置 <span class="en">Authentication</span></h3>
            <div class="form-group">
                <label>账号类型 <span class="en">Account Type</span></label>
                <select id="accountType">
                    <option value="DEVICE_CODE" ${config.authentication?.accountType === 'DEVICE_CODE' ? 'selected' : ''}>Device Code</option>
                    <option value="MSA" ${config.authentication?.accountType === 'MSA' ? 'selected' : ''}>MSA 微软账号</option>
                    <option value="OFFLINE" ${config.authentication?.accountType === 'OFFLINE' ? 'selected' : ''}>Offline 离线</option>
                    <option value="PRISM" ${config.authentication?.accountType === 'PRISM' ? 'selected' : ''}>Prism</option>
                </select>
            </div>
            <div class="form-group">
                <label>用户名 <span class="en">Username</span></label>
                <input type="text" id="username" value="${config.authentication?.username || ''}">
            </div>
            <div class="form-group">
                <label>密码 <span class="en">Password</span></label>
                <input type="password" id="password" value="${config.authentication?.password || ''}">
            </div>
            <div class="form-group">
                <label>邮箱 <span class="en">Email</span></label>
                <input type="email" id="email" value="${config.authentication?.email || ''}">
            </div>
        </div>
        <div class="config-section" id="clientSection">
            <h3>客户端设置 <span class="en">Client</span></h3>
            <div class="form-group">
                <label>自动连接 <span class="en">Auto Connect</span></label>
                <label class="toggle">
                    <input type="checkbox" id="autoConnect" ${config.client?.autoConnect ? 'checked' : ''}>
                    <span class="toggle-slider"></span>
                </label>
            </div>
            <div class="form-group">
                <label>目标服务器 <span class="en">Target Server</span></label>
                <input type="text" id="targetServer" value="${config.client?.server?.address || ''}">
            </div>
            <div class="form-group">
                <label>目标端口 <span class="en">Target Port</span></label>
                <input type="number" id="targetPort" value="${config.client?.server?.port || 25565}">
            </div>
        </div>
        <div class="config-section" id="serverSection">
            <h3>服务器设置 <span class="en">Server</span></h3>
            <div class="form-group">
                <label>监听地址 <span class="en">Bind Address</span></label>
                <input type="text" id="bindAddress" value="${config.server?.bind?.address || '0.0.0.0'}">
            </div>
            <div class="form-group">
                <label>监听端口 <span class="en">Bind Port</span></label>
                <input type="number" id="bindPort" value="${config.server?.bind?.port || 25565}">
            </div>
            <div class="form-group">
                <label>启用服务器 <span class="en">Enable Server</span></label>
                <label class="toggle">
                    <input type="checkbox" id="serverEnabled" ${config.server?.enabled ? 'checked' : ''}>
                    <span class="toggle-slider"></span>
                </label>
            </div>
        </div>
    `;
}

function initConfigTabs() {
    document.addEventListener('click', (e) => {
        if (!e.target.classList.contains('config-tab')) return;
        document.querySelectorAll('.config-tab').forEach(t => t.classList.remove('active'));
        e.target.classList.add('active');
        document.querySelectorAll('.config-section').forEach(s => s.classList.remove('active'));
        const map = { authentication: 'authSection', client: 'clientSection', server: 'serverSection' };
        document.getElementById(map[e.target.dataset.tab])?.classList.add('active');
    });
}

async function saveConfig() {
    const config = {
        authentication: {
            accountType: document.getElementById('accountType')?.value,
            username: document.getElementById('username')?.value,
            password: document.getElementById('password')?.value,
            email: document.getElementById('email')?.value
        },
        client: {
            autoConnect: document.getElementById('autoConnect')?.checked,
            server: { address: document.getElementById('targetServer')?.value, port: parseInt(document.getElementById('targetPort')?.value) || 25565 }
        },
        server: {
            bind: { address: document.getElementById('bindAddress')?.value, port: parseInt(document.getElementById('bindPort')?.value) || 25565 },
            enabled: document.getElementById('serverEnabled')?.checked
        }
    };
    const r = await apiCall('/config', 'POST', config);
    if (r?.success) showNotification('配置已保存 / Config saved', 'success');
    else showNotification('保存失败 / Save failed: ' + (r?.error || '未知错误'), 'error');
}

// ── Fetch commands from server ────────────────
async function loadCommands() {
    try {
        const r = await fetch(API_BASE + '/commands');
        const data = await r.json();
        if (Array.isArray(data) && data.length > 0) serverCommands = data.sort();
    } catch (e) { /* use defaults */ }
}

// ── Terminal ──────────────────────────────────
function initTerminal() {
    const input = document.getElementById('terminalInput');
    const dropdown = document.getElementById('autocompleteDropdown');
    const output = document.getElementById('terminalOutput');

    output.tabIndex = 0;

    output.addEventListener('wheel', () => {
        setTimeout(() => {
            terminalScrollLocked = output.scrollHeight - output.scrollTop - output.clientHeight < 60;
        }, 0);
    }, { passive: true });

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
        } else if (e.key === 'ArrowUp') {
            e.preventDefault();
            if (historyIndex < commandHistory.length - 1) { historyIndex++; input.value = commandHistory[commandHistory.length - 1 - historyIndex]; }
        } else if (e.key === 'ArrowDown') {
            e.preventDefault();
            if (historyIndex > 0) { historyIndex--; input.value = commandHistory[commandHistory.length - 1 - historyIndex]; }
            else { historyIndex = -1; input.value = ''; }
        } else if (e.key === 'Tab') {
            e.preventDefault();
            showAutocomplete(input.value);
        }
    });

    input.addEventListener('input', hideAutocomplete);
    document.addEventListener('click', (e) => { if (!e.target.closest('.terminal-input-wrapper')) hideAutocomplete(); });
}

function sendTerminalCommand(command) {
    if (!command.trim()) return;
    if (terminalBusy) {
        appendTerminalLine('前一个命令还在执行中... / Previous command still running...', 'terminal-line-warn');
        return;
    }
    commandHistory.push(command);
    historyIndex = -1;
    terminalBusy = true;

    // Auto-reset busy after 10s timeout
    setTimeout(() => { if (terminalBusy) { terminalBusy = false; appendTerminalLine('命令超时 / Command timed out', 'terminal-line-warn'); } }, 10000);

    appendTerminalLine(`root@zenith $ ${command}`, 'terminal-line-command');

    if (ws && ws.readyState === WebSocket.OPEN) {
        ws.send(JSON.stringify({ type: 'command', data: command }));
    } else {
        appendTerminalLine('WebSocket 未连接 / Not connected', 'terminal-line-error');
        terminalBusy = false;
    }
}

// ── Autocomplete ──────────────────────────────
function showAutocomplete(value) {
    const dropdown = document.getElementById('autocompleteDropdown');
    const query = value.trim().toLowerCase();
    autocompleteSuggestions = !query ? [...serverCommands] : serverCommands.filter(c => c.toLowerCase().includes(query));
    if (autocompleteSuggestions.length === 0) { hideAutocomplete(); return; }
    autocompleteIndex = -1;
    dropdown.innerHTML = autocompleteSuggestions.map((c, i) => `<div class="autocomplete-item" onmousedown="selectAutocompleteAt(${i})">${c}</div>`).join('');
    dropdown.classList.add('visible');
}

function navigateAutocomplete(dir) {
    const items = document.querySelectorAll('.autocomplete-item');
    if (!items.length) return;
    if (autocompleteIndex >= 0) items[autocompleteIndex].classList.remove('selected');
    autocompleteIndex += dir;
    if (autocompleteIndex < 0) autocompleteIndex = items.length - 1;
    if (autocompleteIndex >= items.length) autocompleteIndex = 0;
    items[autocompleteIndex].classList.add('selected');
    items[autocompleteIndex].scrollIntoView({ block: 'nearest' });
}

function selectAutocomplete() {
    if (autocompleteIndex >= 0 && autocompleteIndex < autocompleteSuggestions.length) {
        document.getElementById('terminalInput').value = autocompleteSuggestions[autocompleteIndex] + ' ';
    }
    hideAutocomplete();
}

function selectAutocompleteAt(i) {
    document.getElementById('terminalInput').value = autocompleteSuggestions[i] + ' ';
    hideAutocomplete();
    document.getElementById('terminalInput').focus();
}

function hideAutocomplete() {
    autocompleteIndex = -1;
    const d = document.getElementById('autocompleteDropdown');
    d.classList.remove('visible');
    d.innerHTML = '';
}

// ── Terminal output ───────────────────────────
function addLogEntry(log) {
    logs.push(log);
    const output = document.getElementById('terminalOutput');
    if (!output) return;

    const entry = document.createElement('div');
    entry.className = 'terminal-line terminal-line-log';
    entry.dataset.level = log.level;
    const ts = new Date(log.timestamp).toLocaleTimeString();
    entry.innerHTML = `<span class="log-ts">${ts}</span><span class="log-lv ${log.level.toLowerCase()}">${log.level}</span><span class="log-msg">[${log.logger}] ${log.message}</span>`;

    output.appendChild(entry);

    const levelFilter = document.getElementById('logLevel')?.value || 'all';
    if (levelFilter !== 'all' && log.level !== levelFilter) entry.style.display = 'none';

    if (terminalScrollLocked) output.scrollTop = output.scrollHeight;

    // Aggressive cap
    while (output.children.length > MAX_TERMINAL_LINES) output.removeChild(output.firstChild);
}

function filterLogs() {
    const f = document.getElementById('logLevel').value;
    document.querySelectorAll('.terminal-line-log').forEach(e => {
        e.style.display = (f === 'all' || e.dataset.level === f) ? '' : 'none';
    });
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
            <p style="color:var(--ctp-green);">ZenithProxy Terminal v26.1.2</p>
            <p style="color:var(--ctp-overlay1);">Type 'help' for available commands</p>
            <p style="color:var(--ctp-overlay0);font-size:0.75rem;">Tab: autocomplete | Up/Down: history | PgUp/PgDn: scroll</p>
        </div>
    `;
    logs = [];
}

function exportTerminalContent() {
    const output = document.getElementById('terminalOutput');
    const lines = [];
    const now = new Date().toISOString().replace('T', ' ').slice(0, 19);
    lines.push('='.repeat(60), `ZenithProxy Terminal Export — ${now}`, '='.repeat(60), '');
    for (const el of output.children) { if (el.style.display !== 'none') lines.push(el.textContent); }
    if (commandHistory.length > 0) {
        lines.push('', '-'.repeat(60), 'Command History', '-'.repeat(60));
        commandHistory.forEach((c, i) => lines.push(`  [${i + 1}] ${c}`));
    }
    lines.push('', '='.repeat(60), 'End of export');
    const blob = new Blob([lines.join('\n')], { type: 'text/plain;charset=utf-8' });
    const a = document.createElement('a');
    a.href = URL.createObjectURL(blob);
    a.download = `zenithproxy-terminal-${new Date().toISOString().split('T')[0]}.txt`;
    a.click();
    showNotification('终端日志已导出 / Terminal exported', 'success');
}

// ── WebSocket ─────────────────────────────────
function initWebSocket() {
    // Close old connection first
    if (ws) { try { ws.onclose = null; ws.close(); } catch (e) {} ws = null; }

    try {
        ws = new WebSocket(`ws://${window.location.hostname}:${wsPort}`);

        ws.onopen = () => {
            updateConnectionStatus(true);
            appendTerminalLine('已连接到 ZenithProxy Terminal', 'terminal-line-success');
        };

        ws.onmessage = (event) => {
            try {
                const data = JSON.parse(event.data);
                switch (data.type) {
                    case 'welcome': wsClientId = data.data?.clientId; break;
                    case 'log': addLogEntry(data.data); break;
                    case 'terminal': break; // echoed client-side
                    case 'command_result':
                        terminalBusy = false;
                        const r = data.data;
                        if (r.success) {
                            if (r.output && Array.isArray(r.output)) r.output.forEach(l => appendTerminalLine(l, 'terminal-line-output'));
                            else if (r.message) appendTerminalLine(r.message, 'terminal-line-success');
                        } else {
                            appendTerminalLine('Error: ' + (r.error || 'Unknown'), 'terminal-line-error');
                        }
                        break;
                    case 'status': updateDashboardStatus(data.data); break;
                }
            } catch (e) { console.error('WS parse error:', e); }
        };

        ws.onclose = () => {
            updateConnectionStatus(false);
            setTimeout(initWebSocket, 5000);
        };

        ws.onerror = () => {};
    } catch (e) {
        console.error('WS init failed:', e);
        setTimeout(initWebSocket, 5000);
    }
}

function updateConnectionStatus(connected) {
    const dot = document.querySelector('.status-dot');
    const text = document.querySelector('.status-text');
    if (connected) {
        dot.className = 'status-dot connected';
        text.innerHTML = '已连接 <span class="en" style="font-size:0.7rem;color:var(--ctp-overlay0);">Connected</span>';
    } else {
        dot.className = 'status-dot disconnected';
        text.innerHTML = '未连接 <span class="en" style="font-size:0.7rem;color:var(--ctp-overlay0);">Disconnected</span>';
    }
}

function updateDashboardStatus(s) {
    if (!s) return;
    document.getElementById('proxyStatus').textContent = s.connected ? '已连接' : '未连接';
    document.getElementById('proxyStatus').style.color = s.connected ? 'var(--success-color)' : 'var(--error-color)';
    if (s.inQueue) {
        document.getElementById('queueStatus').textContent = `队列位置: ${s.queuePosition}`;
        document.getElementById('queueStatus').style.color = 'var(--warning-color)';
    } else {
        document.getElementById('queueStatus').textContent = '不在队列中';
        document.getElementById('queueStatus').style.color = 'var(--success-color)';
    }
}

// ── Players ───────────────────────────────────
async function loadPlayers() {
    const players = await apiCall('/players');
    if (!players) return;
    const container = document.getElementById('playersList');
    if (players.length === 0) {
        container.innerHTML = '<tr><td colspan="4" style="text-align:center;padding:40px;color:var(--ctp-overlay1);">没有连接的玩家 / No connected players</td></tr>';
    } else {
        container.innerHTML = players.map(p => `
            <tr>
                <td>${p.name}</td>
                <td style="font-family:monospace;font-size:0.85rem;color:var(--ctp-overlay1);">${p.uuid}</td>
                <td><span class="player-badge ${p.type}">${p.type === 'controller' ? '控制器 Controller' : '观察者 Spectator'}</span></td>
                <td><button class="btn btn-secondary" onclick="sendCommand('/kick ${p.name}')">踢出 Kick</button></td>
            </tr>
        `).join('');
    }
}

function refreshPlayers() { loadPlayers(); }

// ── Utility ───────────────────────────────────
function sendCommand(cmd) { sendTerminalCommand(cmd); }

function showNotification(message, type = 'info') {
    const n = document.createElement('div');
    n.className = `notification ${type}`;
    n.textContent = message;
    n.style.cssText = 'position:fixed;top:16px;right:16px;padding:12px 20px;border-radius:4px;color:var(--ctp-crust);font-weight:500;font-size:0.85rem;z-index:1000;opacity:0;transform:translateY(-8px);transition:opacity 0.2s ease,transform 0.2s ease;';
    switch (type) {
        case 'success': n.style.backgroundColor = 'var(--success-color)'; break;
        case 'error': n.style.backgroundColor = 'var(--error-color)'; break;
        case 'warning': n.style.backgroundColor = 'var(--warning-color)'; break;
        default: n.style.backgroundColor = 'var(--info-color)';
    }
    document.body.appendChild(n);
    requestAnimationFrame(() => { n.style.opacity = '1'; n.style.transform = 'translateY(0)'; });
    setTimeout(() => { n.style.opacity = '0'; n.style.transform = 'translateY(-8px)'; setTimeout(() => n.remove(), 200); }, 3000);
}
