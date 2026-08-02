// ZenithProxy Web UI JavaScript

// Global variables
let ws = null;
let logs = [];
let commandHistory = [];
let historyIndex = -1;
let wsPort = 8081;
const API_BASE = window.location.origin + '/api';

// Autocomplete state
let autocompleteIndex = -1;
let autocompleteSuggestions = [];

// Chart instances
let cpuChart = null;
let memoryChart = null;
let networkChart = null;
let latencyChart = null;
const MAX_DATA_POINTS = 60;

// ZenithProxy known commands
const KNOWN_COMMANDS = [
    'connect', 'disconnect', 'reconnect', 'cancel',
    'help', 'modules', 'version', 'uptime', 'tps',
    'config', 'save', 'reload',
    'whitelist add', 'whitelist remove', 'whitelist list',
    'ban', 'unban', 'ipban', 'ipunban',
    'kick', 'kickall',
    'msg', 'tell', 'say',
    'list', 'who',
    'queue', 'queuepos', 'priority',
    'debug', 'dump', 'gc',
    'abort', 'stop',
    'sethome', 'home', 'home set',
    'aim Assist', 'anti AFK', 'auto reconnect', 'auto disconnect',
    'chat color', 'chat format', 'chat reply',
    'client brand', 'client settings',
    'fakeplayer',
    'instant disconnect', 'instant connect',
    'login', 'logout',
    'replace chat', 'replace chat add', 'replace chat remove',
    'schematic', 'schematic save', 'schematic load',
    'rotate', 'look', 'face', 'walk',
    'path', 'goto', 'follow',
    'inventory', 'inv see', 'inv swap',
    'stack', 'drop', 'click',
    'use', 'attack', 'attack entity',
    'baritone', 'b',
    'echo', 'print', 'log',
];

// Initialize
document.addEventListener('DOMContentLoaded', () => {
    initNavigation();
    initWebSocket();
    initTerminal();
    initConfigTabs();
    initCharts();
    loadDashboard();
    startSystemPolling();
});

// ── Navigation ────────────────────────────────
function initNavigation() {
    document.querySelectorAll('.nav-item').forEach(item => {
        item.addEventListener('click', () => navigateTo(item.dataset.page));
    });
}

function navigateTo(page) {
    document.querySelectorAll('.nav-item').forEach(item => {
        item.classList.toggle('active', item.dataset.page === page);
    });
    document.querySelectorAll('.page').forEach(p => p.classList.remove('active'));
    document.getElementById(page).classList.add('active');

    switch(page) {
        case 'dashboard': loadDashboard(); break;
        case 'modules': loadModules(); break;
        case 'config': loadConfig(); break;
        case 'players': loadPlayers(); break;
    }
}

// ── API ───────────────────────────────────────
async function apiCall(endpoint, method = 'GET', body = null) {
    try {
        const options = {
            method,
            headers: { 'Content-Type': 'application/json' }
        };
        if (body) options.body = JSON.stringify(body);
        const response = await fetch(API_BASE + endpoint, options);
        return await response.json();
    } catch (error) {
        console.error('API call failed:', error);
        return null;
    }
}

// ── Dashboard ─────────────────────────────────
async function loadDashboard() {
    const status = await apiCall('/status');
    if (!status) return;

    if (status.websocketPort) wsPort = status.websocketPort;
    document.getElementById('proxyStatus').textContent = status.connected ? '已连接' : '未连接';
    document.getElementById('proxyStatus').style.color = status.connected ? 'var(--success-color)' : 'var(--error-color)';
    document.getElementById('currentUsername').textContent = status.username || '-';
    document.getElementById('proxyAddress').textContent = status.proxyAddress || '-';
    document.getElementById('mcVersion').textContent = status.version || '-';

    if (status.uptime) {
        const h = Math.floor(status.uptime / 3600000);
        const m = Math.floor((status.uptime % 3600000) / 60000);
        const s = Math.floor((status.uptime % 60000) / 1000);
        document.getElementById('uptime').textContent = `${h}时 ${m}分 ${s}秒`;
    }

    if (status.inQueue) {
        document.getElementById('queueStatus').textContent = `队列位置: ${status.queuePosition}`;
        document.getElementById('queueStatus').style.color = 'var(--warning-color)';
    } else {
        document.getElementById('queueStatus').textContent = '不在队列中';
        document.getElementById('queueStatus').style.color = 'var(--success-color)';
    }

    document.getElementById('version').textContent = 'v' + status.version;
}

function refreshDashboard() { loadDashboard(); }

// ── System Charts ─────────────────────────────
const chartColors = {
    cpu: 'rgba(198, 160, 246, 1)',       // mauve
    cpuBg: 'rgba(198, 160, 246, 0.15)',
    memory: 'rgba(166, 218, 149, 1)',     // green
    memoryBg: 'rgba(166, 218, 149, 0.15)',
    netIn: 'rgba(138, 173, 244, 1)',      // blue
    netInBg: 'rgba(138, 173, 244, 0.15)',
    netOut: 'rgba(245, 169, 127, 1)',     // peach
    netOutBg: 'rgba(245, 169, 127, 0.15)',
    grid: 'rgba(110, 115, 141, 0.15)',
    text: '#8087a2',
};

function makeTimeLabels(count) {
    const labels = [];
    const now = new Date();
    for (let i = count - 1; i >= 0; i--) {
        const t = new Date(now - i * 3000);
        labels.push(t.toLocaleTimeString('zh-CN', { hour12: false, hour: '2-digit', minute: '2-digit', second: '2-digit' }));
    }
    return labels;
}

function makeChartOptions(unit, suggestedMax) {
    return {
        responsive: true,
        maintainAspectRatio: false,
        animation: { duration: 300 },
        interaction: { intersect: false, mode: 'index' },
        plugins: {
            legend: { display: false },
            tooltip: {
                backgroundColor: 'rgba(30, 32, 48, 0.95)',
                titleColor: '#cad3f5',
                bodyColor: '#b8c0e0',
                borderColor: 'rgba(91, 96, 120, 0.5)',
                borderWidth: 1,
                padding: 8,
                displayColors: true,
            }
        },
        scales: {
            x: {
                display: true,
                grid: { color: chartColors.grid, drawBorder: false },
                ticks: { color: chartColors.text, font: { size: 10 }, maxTicksLimit: 6 },
            },
            y: {
                display: true,
                min: 0,
                suggestedMax: suggestedMax,
                grid: { color: chartColors.grid, drawBorder: false },
                ticks: {
                    color: chartColors.text,
                    font: { size: 10 },
                    maxTicksLimit: 5,
                    callback: v => v + (unit || ''),
                },
            }
        }
    };
}

function createLineDataset(label, color, bgColor) {
    return {
        label,
        data: [],
        borderColor: color,
        backgroundColor: bgColor,
        borderWidth: 1.5,
        pointRadius: 0,
        fill: true,
        tension: 0.3,
    };
}

function initCharts() {
    const timeLabels = makeTimeLabels(MAX_DATA_POINTS);

    cpuChart = new Chart(document.getElementById('cpuChart'), {
        type: 'line',
        data: {
            labels: timeLabels,
            datasets: [createLineDataset('CPU', chartColors.cpu, chartColors.cpuBg)],
        },
        options: makeChartOptions('%', 100),
    });

    memoryChart = new Chart(document.getElementById('memoryChart'), {
        type: 'line',
        data: {
            labels: timeLabels,
            datasets: [createLineDataset('Memory', chartColors.memory, chartColors.memoryBg)],
        },
        options: makeChartOptions('%', 100),
    });

    networkChart = new Chart(document.getElementById('networkChart'), {
        type: 'line',
        data: {
            labels: timeLabels,
            datasets: [
                createLineDataset('↓ 入站 In', chartColors.netIn, chartColors.netInBg),
                createLineDataset('↑ 出站 Out', chartColors.netOut, chartColors.netOutBg),
            ],
        },
        options: {
            ...makeChartOptions('', undefined),
            plugins: {
                ...makeChartOptions('').plugins,
                legend: { display: true, position: 'top', labels: { color: chartColors.text, boxWidth: 12, padding: 10, font: { size: 11 } } },
            }
        },
    });

    latencyChart = new Chart(document.getElementById('latencyChart'), {
        type: 'line',
        data: {
            labels: timeLabels,
            datasets: [createLineDataset('Ping', 'rgba(238, 212, 159, 1)', 'rgba(238, 212, 159, 0.15)')],
        },
        options: makeChartOptions('ms', 200),
    });
}

let systemPollTimer = null;

function startSystemPolling() {
    if (systemPollTimer) clearInterval(systemPollTimer);
    fetchSystemStats();
    systemPollTimer = setInterval(fetchSystemStats, 3000);
}

async function fetchSystemStats() {
    const data = await apiCall('/system');
    if (!data) return;

    const now = new Date().toLocaleTimeString('zh-CN', { hour12: false, hour: '2-digit', minute: '2-digit', second: '2-digit' });

    // CPU
    if (cpuChart) {
        const ds = cpuChart.data.datasets[0];
        ds.data.push(Math.max(0, data.cpuLoad || 0));
        cpuChart.data.labels.push(now);
        if (ds.data.length > MAX_DATA_POINTS) { ds.data.shift(); cpuChart.data.labels.shift(); }
        cpuChart.update('none');
    }

    // Memory
    if (memoryChart) {
        const ds = memoryChart.data.datasets[0];
        ds.data.push(data.memoryPercent || 0);
        memoryChart.data.labels.push(now);
        if (ds.data.length > MAX_DATA_POINTS) { ds.data.shift(); memoryChart.data.labels.shift(); }
        memoryChart.update('none');
    }

    // Network (convert to KB/s for display)
    if (networkChart) {
        const kbIn = Math.round((data.netBytesPerSecIn || 0) / 1024 * 10) / 10;
        const kbOut = Math.round((data.netBytesPerSecOut || 0) / 1024 * 10) / 10;
        networkChart.data.datasets[0].data.push(kbIn);
        networkChart.data.datasets[1].data.push(kbOut);
        networkChart.data.labels.push(now);
        if (networkChart.data.datasets[0].data.length > MAX_DATA_POINTS) {
            networkChart.data.datasets[0].data.shift();
            networkChart.data.datasets[1].data.shift();
            networkChart.data.labels.shift();
        }
        networkChart.update('none');
    }

    // Latency
    fetchLatency();
}

async function fetchLatency() {
    const data = await apiCall('/latency');
    if (!data || !latencyChart) return;
    const now = new Date().toLocaleTimeString('zh-CN', { hour12: false, hour: '2-digit', minute: '2-digit', second: '2-digit' });
    const ds = latencyChart.data.datasets[0];
    ds.data.push(data.connected ? data.latency : 0);
    latencyChart.data.labels.push(now);
    if (ds.data.length > MAX_DATA_POINTS) { ds.data.shift(); latencyChart.data.labels.shift(); }
    latencyChart.update('none');
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
            server: {
                address: document.getElementById('targetServer')?.value,
                port: parseInt(document.getElementById('targetPort')?.value) || 25565
            }
        },
        server: {
            bind: {
                address: document.getElementById('bindAddress')?.value,
                port: parseInt(document.getElementById('bindPort')?.value) || 25565
            },
            enabled: document.getElementById('serverEnabled')?.checked
        }
    };
    const result = await apiCall('/config', 'POST', config);
    if (result?.success) {
        showNotification('配置已保存 / Config saved', 'success');
    } else {
        showNotification('保存失败 / Save failed: ' + (result?.error || '未知错误'), 'error');
    }
}

// ── Terminal + Logs (Unified) ─────────────────
let terminalScrollLocked = true;

function initTerminal() {
    const input = document.getElementById('terminalInput');
    const dropdown = document.getElementById('autocompleteDropdown');
    const output = document.getElementById('terminalOutput');

    output.tabIndex = 0;

    // Mouse wheel scroll in terminal output
    output.addEventListener('wheel', (e) => {
        // Allow native scroll, just update lock state
        setTimeout(() => {
            const atBottom = output.scrollHeight - output.scrollTop - output.clientHeight < 60;
            terminalScrollLocked = atBottom;
        }, 0);
    }, { passive: true });

    // PageUp/PageDown
    output.addEventListener('keydown', (e) => {
        if (e.key === 'PageUp') {
            e.preventDefault();
            output.scrollTop -= output.clientHeight - 40;
            terminalScrollLocked = false;
        } else if (e.key === 'PageDown') {
            e.preventDefault();
            output.scrollTop += output.clientHeight - 40;
            if (output.scrollHeight - output.scrollTop - output.clientHeight < 60) {
                terminalScrollLocked = true;
            }
        } else if (e.key === 'End') {
            e.preventDefault();
            output.scrollTop = output.scrollHeight;
            terminalScrollLocked = true;
        }
    });

    input.addEventListener('keydown', (e) => {
        // Autocomplete navigation
        if (dropdown.classList.contains('visible')) {
            if (e.key === 'ArrowDown') { e.preventDefault(); navigateAutocomplete(1); return; }
            if (e.key === 'ArrowUp') { e.preventDefault(); navigateAutocomplete(-1); return; }
            if (e.key === 'Tab') { e.preventDefault(); selectAutocomplete(); return; }
            if (e.key === 'Escape') { hideAutocomplete(); return; }
            if (e.key === 'Enter' && autocompleteIndex >= 0) { e.preventDefault(); selectAutocomplete(); return; }
        }

        if (e.key === 'Enter') {
            e.preventDefault();
            executeCommand(input.value);
            input.value = '';
            hideAutocomplete();
        } else if (e.key === 'ArrowUp') {
            e.preventDefault();
            if (historyIndex < commandHistory.length - 1) {
                historyIndex++;
                input.value = commandHistory[commandHistory.length - 1 - historyIndex];
                setTimeout(() => input.setSelectionRange(input.value.length, input.value.length), 0);
            }
        } else if (e.key === 'ArrowDown') {
            e.preventDefault();
            if (historyIndex > 0) {
                historyIndex--;
                input.value = commandHistory[commandHistory.length - 1 - historyIndex];
                setTimeout(() => input.setSelectionRange(input.value.length, input.value.length), 0);
            } else {
                historyIndex = -1;
                input.value = '';
            }
        } else if (e.key === 'Tab') {
            e.preventDefault();
            showAutocomplete(input.value);
        }
    });

    input.addEventListener('input', () => hideAutocomplete());

    document.addEventListener('click', (e) => {
        if (!e.target.closest('.terminal-input-wrapper') && !e.target.closest('.modal')) {
            hideAutocomplete();
        }
    });
}

function showAutocomplete(value) {
    const dropdown = document.getElementById('autocompleteDropdown');
    const query = value.trim().toLowerCase();
    autocompleteSuggestions = !query
        ? [...KNOWN_COMMANDS]
        : KNOWN_COMMANDS.filter(cmd => cmd.toLowerCase().includes(query));

    if (autocompleteSuggestions.length === 0) { hideAutocomplete(); return; }

    autocompleteIndex = -1;
    dropdown.innerHTML = autocompleteSuggestions.map((cmd, i) =>
        `<div class="autocomplete-item" data-index="${i}" onmousedown="selectAutocompleteAt(${i})">${cmd}</div>`
    ).join('');
    dropdown.classList.add('visible');
}

function navigateAutocomplete(direction) {
    const items = document.querySelectorAll('.autocomplete-item');
    if (items.length === 0) return;
    if (autocompleteIndex >= 0) items[autocompleteIndex].classList.remove('selected');
    autocompleteIndex += direction;
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

function selectAutocompleteAt(index) {
    document.getElementById('terminalInput').value = autocompleteSuggestions[index] + ' ';
    hideAutocomplete();
    document.getElementById('terminalInput').focus();
}

function hideAutocomplete() {
    autocompleteIndex = -1;
    const d = document.getElementById('autocompleteDropdown');
    d.classList.remove('visible');
    d.innerHTML = '';
}

async function executeCommand(command) {
    if (!command.trim()) return;

    commandHistory.push(command);
    historyIndex = -1;

    appendTerminalLine(`> ${command}`, 'terminal-line-command');

    const result = await apiCall('/commands', 'POST', { command });
    if (result) {
        if (result.output && Array.isArray(result.output)) {
            result.output.forEach(line => appendTerminalLine(line, 'terminal-line-output'));
        } else if (result.success) {
            appendTerminalLine('命令执行成功 / Command executed', 'terminal-line-success');
        } else {
            appendTerminalLine('错误 / Error: ' + (result.error || '未知错误'), 'terminal-line-error');
        }
    }
}

// ── Logs (streamed into terminal output) ──────
function addLogEntry(log) {
    logs.push(log);

    const output = document.getElementById('terminalOutput');
    const entry = document.createElement('div');
    entry.className = 'terminal-line terminal-line-log';
    entry.dataset.level = log.level;

    const ts = new Date(log.timestamp).toLocaleTimeString();
    entry.innerHTML = `<span class="log-ts">${ts}</span><span class="log-lv ${log.level.toLowerCase()}">${log.level}</span><span class="log-msg">[${log.logger}] ${log.message}</span>`;

    output.appendChild(entry);

    // Apply current filter
    const levelFilter = document.getElementById('logLevel')?.value || 'all';
    if (levelFilter !== 'all' && log.level !== levelFilter) {
        entry.style.display = 'none';
    }

    // Auto-scroll only if at bottom
    if (terminalScrollLocked) {
        output.scrollTop = output.scrollHeight;
    }

    // Cap entries
    if (output.children.length > 2000) {
        output.removeChild(output.firstChild);
    }
}

function filterLogs() {
    const levelFilter = document.getElementById('logLevel').value;
    document.querySelectorAll('.terminal-line-log').forEach(entry => {
        entry.style.display = (levelFilter === 'all' || entry.dataset.level === levelFilter) ? '' : 'none';
    });
}

function appendTerminalLine(text, className) {
    const output = document.getElementById('terminalOutput');
    const line = document.createElement('div');
    line.className = `terminal-line ${className}`;
    line.textContent = text;
    output.appendChild(line);
    if (terminalScrollLocked) {
        output.scrollTop = output.scrollHeight;
    }
}

function clearTerminal() {
    document.getElementById('terminalOutput').innerHTML = `
        <div class="terminal-welcome">
            <p>ZenithProxy 终端 <span class="en">Terminal</span></p>
            <p>实时日志与命令执行 <span class="en">Live logs &amp; command execution</span></p>
        </div>
    `;
    logs = [];
}

// ── Export terminal content ────────────────────
function exportTerminalContent() {
    const output = document.getElementById('terminalOutput');
    const lines = [];
    const now = new Date().toISOString().replace('T', ' ').slice(0, 19);

    lines.push('='.repeat(60));
    lines.push(`ZenithProxy Terminal Export — ${now}`);
    lines.push('='.repeat(60));
    lines.push('');

    for (const el of output.children) {
        if (el.style.display === 'none') continue;
        lines.push(el.textContent);
    }

    if (commandHistory.length > 0) {
        lines.push('');
        lines.push('-'.repeat(60));
        lines.push('Command History 命令历史');
        lines.push('-'.repeat(60));
        commandHistory.forEach((cmd, i) => lines.push(`  [${i + 1}] ${cmd}`));
    }

    lines.push('');
    lines.push('='.repeat(60));
    lines.push('End of export');

    const blob = new Blob([lines.join('\n')], { type: 'text/plain;charset=utf-8' });
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = `zenithproxy-terminal-${new Date().toISOString().split('T')[0]}.txt`;
    a.click();
    URL.revokeObjectURL(url);
    showNotification('终端日志已导出 / Terminal exported', 'success');
}

// ── WebSocket ─────────────────────────────────
function initWebSocket() {
    try {
        ws = new WebSocket(`ws://${window.location.hostname}:${wsPort}`);

        ws.onopen = () => updateConnectionStatus(true);

        ws.onmessage = (event) => {
            const data = JSON.parse(event.data);
            if (data.type === 'log') addLogEntry(data.data);
            if (data.type === 'status') updateDashboardStatus(data.data);
        };

        ws.onclose = () => {
            updateConnectionStatus(false);
            setTimeout(initWebSocket, 3000);
        };

        ws.onerror = (error) => console.error('WebSocket error:', error);
    } catch (error) {
        console.error('WebSocket initialization failed:', error);
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
function sendCommand(cmd) { executeCommand(cmd); }

function updateDashboardStatus(status) {
    if (!status) return;
    document.getElementById('proxyStatus').textContent = status.connected ? '已连接' : '未连接';
    document.getElementById('proxyStatus').style.color = status.connected ? 'var(--success-color)' : 'var(--error-color)';
    if (status.inQueue) {
        document.getElementById('queueStatus').textContent = `队列位置: ${status.queuePosition}`;
        document.getElementById('queueStatus').style.color = 'var(--warning-color)';
    } else {
        document.getElementById('queueStatus').textContent = '不在队列中';
        document.getElementById('queueStatus').style.color = 'var(--success-color)';
    }
}

function showNotification(message, type = 'info') {
    const n = document.createElement('div');
    n.className = `notification ${type}`;
    n.textContent = message;
    n.style.cssText = `
        position:fixed;top:16px;right:16px;padding:12px 20px;
        border-radius:4px;color:var(--ctp-crust);font-weight:500;
        font-size:0.85rem;z-index:1000;opacity:0;transform:translateY(-8px);
        transition:opacity 0.2s ease,transform 0.2s ease;
    `;
    switch (type) {
        case 'success': n.style.backgroundColor = 'var(--success-color)'; break;
        case 'error': n.style.backgroundColor = 'var(--error-color)'; break;
        case 'warning': n.style.backgroundColor = 'var(--warning-color)'; break;
        default: n.style.backgroundColor = 'var(--info-color)';
    }
    document.body.appendChild(n);
    requestAnimationFrame(() => { n.style.opacity = '1'; n.style.transform = 'translateY(0)'; });
    setTimeout(() => {
        n.style.opacity = '0';
        n.style.transform = 'translateY(-8px)';
        setTimeout(() => n.remove(), 200);
    }, 3000);
}
