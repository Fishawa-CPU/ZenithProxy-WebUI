// ZenithProxy Web UI JavaScript

let logs = [];
let commandHistory = [];
let historyIndex = -1;
const API_BASE = window.location.origin + '/api';

let autocompleteIndex = -1;
let autocompleteSuggestions = [];

let cpuChart = null, memoryChart = null, networkChart = null, latencyChart = null;
const MAX_DATA_POINTS = 60;
const MAX_TERMINAL_LINES = 500;

let terminalScrollLocked = true;
let currentPage = 'dashboard';
let chartsInitialized = false;

// Log polling state
let lastLogId = -1;
let logPollTimer = null;
const LOG_POLL_INTERVAL = 1000; // 1 second

// Terminal command tracking
let terminalCmdCount = 0;
let terminalLogCount = 0;

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
document.addEventListener('DOMContentLoaded', async () => {
    initNavigation();
    initTerminal();
    initConfigTabs();

    // Whitelist add input Enter key
    const wlInput = document.getElementById('whitelistAddInput');
    if (wlInput) {
        wlInput.addEventListener('keydown', (e) => {
            if (e.key === 'Enter') { e.preventDefault(); addWhitelistPlayer(); }
        });
    }

    loadDashboard();
    loadCommands();
    startLogPolling();
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
        case 'config': loadConfig(); break;
        case 'players': loadPlayers(); loadWhitelist(); break;
    }
    if (page !== 'dashboard') stopSystemPolling();
}

// ── API ───────────────────────────────────────
async function apiCall(endpoint, method = 'GET', body = null) {
    try {
        const opts = { method, headers: { 'Content-Type': 'application/json' } };
        if (body) opts.body = JSON.stringify(body);
        const r = await fetch(API_BASE + endpoint, opts);
        return await r.json();
    } catch (e) { return null; }
}

// ── Dashboard ─────────────────────────────────
async function loadDashboard() {
    const [s, p] = await Promise.all([apiCall('/status'), apiCall('/players')]);
    if (!s) return;
    document.getElementById('proxyStatus').textContent = s.connected ? '已连接' : '未连接';
    document.getElementById('proxyStatus').style.color = s.connected ? 'var(--success-color)' : 'var(--error-color)';
    document.getElementById('currentUsername').textContent = s.username || '-';
    document.getElementById('proxyAddress').textContent = s.proxyAddress || '-';
    document.getElementById('mcVersion').textContent = s.version || '-';
    if (s.uptime) {
        const h = Math.floor(s.uptime / 3600000), m = Math.floor((s.uptime % 3600000) / 60000), sec = Math.floor((s.uptime % 60000) / 1000);
        document.getElementById('uptime').textContent = `${h}时 ${m}分 ${sec}秒`;
    }
    // Player connection display
    const pc = document.getElementById('playerConnection');
    if (p && p.length > 0) {
        const names = p.map(x => x.name).join(', ');
        pc.textContent = `${p.length} 人: ${names}`;
        pc.style.color = 'var(--success-color)';
    } else {
        pc.textContent = '无连接';
        pc.style.color = 'var(--ctp-overlay1)';
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
    const labels = [], now = new Date();
    for (let i = count - 1; i >= 0; i--) { const t = new Date(now - i * 5000); labels.push(t.toLocaleTimeString('zh-CN', { hour12: false, hour: '2-digit', minute: '2-digit', second: '2-digit' })); }
    return labels;
}

function makeChartOptions(unit, max) {
    return {
        responsive: true, maintainAspectRatio: false, animation: { duration: 0 },
        interaction: { intersect: false, mode: 'index' },
        plugins: { legend: { display: false }, tooltip: { backgroundColor: 'rgba(30,32,48,0.95)', titleColor: '#cad3f5', bodyColor: '#b8c0e0', borderColor: 'rgba(91,96,120,0.5)', borderWidth: 1, padding: 8 } },
        scales: {
            x: { display: true, grid: { color: chartColors.grid }, ticks: { color: chartColors.text, font: { size: 10 }, maxTicksLimit: 6 } },
            y: { display: true, min: 0, suggestedMax: max, grid: { color: chartColors.grid }, ticks: { color: chartColors.text, font: { size: 10 }, maxTicksLimit: 5, callback: v => v + (unit || '') } }
        }
    };
}

function makeDS(label, color, bg) {
    return { label, data: [], borderColor: color, backgroundColor: bg, borderWidth: 1.5, pointRadius: 0, fill: true, tension: 0.3 };
}

function initCharts() {
    if (chartsInitialized) return;
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

let systemPollTimer = null;
function startSystemPolling() { if (systemPollTimer) return; fetchSystemStats(); systemPollTimer = setInterval(fetchSystemStats, 5000); }
function stopSystemPolling() { if (systemPollTimer) { clearInterval(systemPollTimer); systemPollTimer = null; } }

async function fetchSystemStats() {
    if (currentPage !== 'dashboard') { stopSystemPolling(); return; }
    const [sys, lat] = await Promise.all([apiCall('/system'), apiCall('/latency')]);
    const now = new Date().toLocaleTimeString('zh-CN', { hour12: false, hour: '2-digit', minute: '2-digit', second: '2-digit' });
    if (sys && cpuChart) { cpuChart.data.datasets[0].data.push(Math.max(0, sys.cpuLoad || 0)); cpuChart.data.labels.push(now); if (cpuChart.data.datasets[0].data.length > MAX_DATA_POINTS) { cpuChart.data.datasets[0].data.shift(); cpuChart.data.labels.shift(); } cpuChart.update('none'); }
    if (sys && memoryChart) { memoryChart.data.datasets[0].data.push(sys.memoryPercent || 0); memoryChart.data.labels.push(now); if (memoryChart.data.datasets[0].data.length > MAX_DATA_POINTS) { memoryChart.data.datasets[0].data.shift(); memoryChart.data.labels.shift(); } memoryChart.update('none'); }
    if (sys && networkChart) { networkChart.data.datasets[0].data.push(Math.round((sys.netBytesPerSecIn || 0) / 1024 * 10) / 10); networkChart.data.datasets[1].data.push(Math.round((sys.netBytesPerSecOut || 0) / 1024 * 10) / 10); networkChart.data.labels.push(now); if (networkChart.data.datasets[0].data.length > MAX_DATA_POINTS) { networkChart.data.datasets[0].data.shift(); networkChart.data.datasets[1].data.shift(); networkChart.data.labels.shift(); } networkChart.update('none'); }
    if (lat && latencyChart) {
        latencyChart.data.datasets[0].data.push(lat.connected ? (lat.botLatency || 0) : 0);
        // Average player latency
        let avgPlayerPing = 0;
        if (lat.players && lat.players.length > 0) {
            let sum = 0;
            for (const p of lat.players) sum += (p.ping || 0);
            avgPlayerPing = Math.round(sum / lat.players.length);
        }
        latencyChart.data.datasets[1].data.push(avgPlayerPing);
        latencyChart.data.labels.push(now);
        if (latencyChart.data.datasets[0].data.length > MAX_DATA_POINTS) {
            latencyChart.data.datasets[0].data.shift();
            latencyChart.data.datasets[1].data.shift();
            latencyChart.data.labels.shift();
        }
        latencyChart.update('none');
        // Update player latency detail
        const detail = document.getElementById('latencyDetail');
        if (detail) {
            let html = `<div class="latency-item"><span class="player-name">Bot→Server</span><span class="ping-value ${getPingClass(lat.botLatency)}">${lat.botLatency}ms</span></div>`;
            if (lat.players && lat.players.length > 0) {
                for (const p of lat.players) {
                    html += `<div class="latency-item"><span class="player-name">${p.name}</span><span class="ping-value ${getPingClass(p.ping)}">${p.ping}ms</span></div>`;
                }
            }
            detail.innerHTML = html;
        }
    }
}

// ── Config ────────────────────────────────────
async function loadConfig() { const c = await apiCall('/config'); if (c) renderConfigSections(c); }
function renderConfigSections(c) {
    document.getElementById('configContent').innerHTML = `
        <div class="config-section active" id="authSection"><h3>认证设置 <span class="en">Auth</span></h3>
            <div class="form-group"><label>账号类型</label><select id="accountType"><option value="DEVICE_CODE" ${c.authentication?.accountType === 'DEVICE_CODE' ? 'selected' : ''}>Device Code</option><option value="MSA" ${c.authentication?.accountType === 'MSA' ? 'selected' : ''}>MSA</option><option value="OFFLINE" ${c.authentication?.accountType === 'OFFLINE' ? 'selected' : ''}>Offline</option><option value="PRISM" ${c.authentication?.accountType === 'PRISM' ? 'selected' : ''}>Prism</option></select></div>
            <div class="form-group"><label>用户名</label><input type="text" id="username" value="${c.authentication?.username || ''}"></div>
            <div class="form-group"><label>密码</label><input type="password" id="password" value="${c.authentication?.password || ''}"></div>
            <div class="form-group"><label>邮箱</label><input type="email" id="email" value="${c.authentication?.email || ''}"></div>
        </div>
        <div class="config-section" id="clientSection"><h3>客户端设置 <span class="en">Client</span></h3>
            <div class="form-group"><label>自动连接</label><label class="toggle"><input type="checkbox" id="autoConnect" ${c.client?.autoConnect ? 'checked' : ''}><span class="toggle-slider"></span></label></div>
            <div class="form-group"><label>目标服务器</label><input type="text" id="targetServer" value="${c.client?.server?.address || ''}"></div>
            <div class="form-group"><label>目标端口</label><input type="number" id="targetPort" value="${c.client?.server?.port || 25565}"></div>
        </div>
        <div class="config-section" id="serverSection"><h3>服务器设置 <span class="en">Server</span></h3>
            <div class="form-group"><label>监听地址</label><input type="text" id="bindAddress" value="${c.server?.bind?.address || '0.0.0.0'}"></div>
            <div class="form-group"><label>监听端口</label><input type="number" id="bindPort" value="${c.server?.bind?.port || 25565}"></div>
            <div class="form-group"><label>启用服务器</label><label class="toggle"><input type="checkbox" id="serverEnabled" ${c.server?.enabled ? 'checked' : ''}><span class="toggle-slider"></span></label></div>
        </div>`;
}
function initConfigTabs() { document.addEventListener('click', (e) => { if (!e.target.classList.contains('config-tab')) return; document.querySelectorAll('.config-tab').forEach(t => t.classList.remove('active')); e.target.classList.add('active'); document.querySelectorAll('.config-section').forEach(s => s.classList.remove('active')); const m = { authentication: 'authSection', client: 'clientSection', server: 'serverSection' }; document.getElementById(m[e.target.dataset.tab])?.classList.add('active'); }); }
async function saveConfig() {
    const cfg = { authentication: { accountType: document.getElementById('accountType')?.value, username: document.getElementById('username')?.value, password: document.getElementById('password')?.value, email: document.getElementById('email')?.value }, client: { autoConnect: document.getElementById('autoConnect')?.checked, server: { address: document.getElementById('targetServer')?.value, port: parseInt(document.getElementById('targetPort')?.value) || 25565 } }, server: { bind: { address: document.getElementById('bindAddress')?.value, port: parseInt(document.getElementById('bindPort')?.value) || 25565 }, enabled: document.getElementById('serverEnabled')?.checked } };
    const r = await apiCall('/config', 'POST', cfg);
    showNotification(r?.success ? '配置已保存 / Config saved' : '保存失败 / ' + (r?.error || 'Error'), r?.success ? 'success' : 'error');
}

// ── Commands from server ──────────────────────
async function loadCommands() { try { const r = await fetch(API_BASE + '/commands'); const d = await r.json(); if (Array.isArray(d) && d.length > 0) serverCommands = d.sort(); } catch (e) {} }

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
    input.addEventListener('input', hideAutocomplete);
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
    appendTerminalLine(`root@zenith:~$ ${command}`, 'terminal-line-command');

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

// ── Log Polling (HTTP) ────────────────────────
function startLogPolling() {
    if (logPollTimer) return;
    fetchLogs(); // fetch immediately
    logPollTimer = setInterval(fetchLogs, LOG_POLL_INTERVAL);
}

function stopLogPolling() {
    if (logPollTimer) {
        clearInterval(logPollTimer);
        logPollTimer = null;
    }
}

async function fetchLogs() {
    try {
        const url = API_BASE + '/logs/live' + (lastLogId >= 0 ? `?after=${lastLogId}` : '');
        const r = await fetch(url);
        const data = await r.json();
        if (data && data.logs && Array.isArray(data.logs)) {
            for (const log of data.logs) {
                addLogEntry(log);
            }
            if (data.logs.length > 0) {
                lastLogId = data.counter - 1;
            }
        }
    } catch (e) {
        // polling failed, will retry next interval
    }
}

// ── Autocomplete ──────────────────────────────
function showAutocomplete(value) {
    const dropdown = document.getElementById('autocompleteDropdown');
    const q = value.trim().toLowerCase();
    autocompleteSuggestions = !q ? [...serverCommands] : serverCommands.filter(c => c.toLowerCase().includes(q));
    if (!autocompleteSuggestions.length) { hideAutocomplete(); return; }
    autocompleteIndex = -1;
    dropdown.innerHTML = autocompleteSuggestions.map((c, i) => `<div class="autocomplete-item" onmousedown="selectAutocompleteAt(${i})">${c}</div>`).join('');
    dropdown.classList.add('visible');
}
function navigateAutocomplete(d) { const items = document.querySelectorAll('.autocomplete-item'); if (!items.length) return; if (autocompleteIndex >= 0) items[autocompleteIndex].classList.remove('selected'); autocompleteIndex += d; if (autocompleteIndex < 0) autocompleteIndex = items.length - 1; if (autocompleteIndex >= items.length) autocompleteIndex = 0; items[autocompleteIndex].classList.add('selected'); items[autocompleteIndex].scrollIntoView({ block: 'nearest' }); }
function selectAutocomplete() { if (autocompleteIndex >= 0 && autocompleteIndex < autocompleteSuggestions.length) document.getElementById('terminalInput').value = autocompleteSuggestions[autocompleteIndex] + ' '; hideAutocomplete(); }
function selectAutocompleteAt(i) { document.getElementById('terminalInput').value = autocompleteSuggestions[i] + ' '; hideAutocomplete(); document.getElementById('terminalInput').focus(); }
function hideAutocomplete() { autocompleteIndex = -1; const d = document.getElementById('autocompleteDropdown'); d.classList.remove('visible'); d.innerHTML = ''; }

// ── Terminal output ───────────────────────────
function addLogEntry(log) {
    logs.push(log);
    terminalLogCount++;
    updateTerminalCounts();
    const output = document.getElementById('terminalOutput');
    if (!output) return;
    const entry = document.createElement('div');
    entry.className = 'terminal-line terminal-line-log';
    const ts = new Date(log.timestamp).toLocaleTimeString();
    entry.innerHTML = `<span class="log-ts">${ts}</span><span class="log-lv ${log.level.toLowerCase()}">${log.level}</span><span class="log-msg">[${log.logger}] ${log.message}</span>`;
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

let terminalCmdCountDisplay = 0;
let terminalLogCountDisplay = 0;

function updateTerminalCounts() {
    const cmdEl = document.getElementById('terminalCmdCount');
    const logEl = document.getElementById('terminalLogCount');
    if (cmdEl) cmdEl.textContent = `命令数: ${terminalCmdCount}`;
    if (logEl) logEl.textContent = `日志数: ${terminalLogCount}`;
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
    return str.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;').replace(/'/g, '&#039;');
}

async function loadPlayers() {
    const p = await apiCall('/players');
    if (!p) return;
    const c = document.getElementById('playersList');
    if (!p.length) {
        c.innerHTML = '<tr><td colspan="4" style="text-align:center;padding:40px;color:var(--ctp-overlay1);">没有连接的玩家</td></tr>';
    } else {
        c.innerHTML = p.map(x => `<tr><td>${escapeHtml(x.name)}</td><td style="font-family:monospace;font-size:0.85rem;color:var(--ctp-overlay1);">${escapeHtml(x.uuid)}</td><td><span class="player-badge ${escapeHtml(x.type)}">${x.type === 'controller' ? '控制器' : '观察者'}</span></td><td><button class="btn btn-secondary" onclick="sendTerminalCommand('/kick ${escapeHtml(x.name)}')">踢出</button></td></tr>`).join('');
    }
}
function refreshPlayers() { loadPlayers(); loadWhitelist(); }

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
