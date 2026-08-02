// ZenithProxy Web UI JavaScript

// Global variables
let ws = null;
let logs = [];
let commandHistory = [];
let historyIndex = -1;
let wsPort = 8081;
const API_BASE = window.location.origin + '/api';

// Initialize
document.addEventListener('DOMContentLoaded', () => {
    initNavigation();
    initWebSocket();
    initTerminal();
    initConfigTabs();
    loadDashboard();
});

// Navigation
function initNavigation() {
    const navItems = document.querySelectorAll('.nav-item');
    navItems.forEach(item => {
        item.addEventListener('click', () => {
            const page = item.dataset.page;
            navigateTo(page);
        });
    });
}

function navigateTo(page) {
    // Update nav items
    document.querySelectorAll('.nav-item').forEach(item => {
        item.classList.remove('active');
        if (item.dataset.page === page) {
            item.classList.add('active');
        }
    });
    
    // Update pages
    document.querySelectorAll('.page').forEach(p => {
        p.classList.remove('active');
    });
    document.getElementById(page).classList.add('active');
    
    // Load page data
    switch(page) {
        case 'dashboard':
            loadDashboard();
            break;
        case 'modules':
            loadModules();
            break;
        case 'config':
            loadConfig();
            break;
        case 'players':
            loadPlayers();
            break;
    }
}

// API calls
async function apiCall(endpoint, method = 'GET', body = null) {
    try {
        const options = {
            method,
            headers: {
                'Content-Type': 'application/json'
            }
        };
        if (body) {
            options.body = JSON.stringify(body);
        }
        const response = await fetch(API_BASE + endpoint, options);
        return await response.json();
    } catch (error) {
        console.error('API call failed:', error);
        return null;
    }
}

// Dashboard
async function loadDashboard() {
    const status = await apiCall('/status');
    if (status) {
        if (status.websocketPort) wsPort = status.websocketPort;
        document.getElementById('proxyStatus').textContent = status.connected ? '已连接' : '未连接';
        document.getElementById('proxyStatus').style.color = status.connected ? 'var(--success-color)' : 'var(--error-color)';
        document.getElementById('currentUsername').textContent = status.username || '-';
        document.getElementById('proxyAddress').textContent = status.proxyAddress || '-';
        document.getElementById('mcVersion').textContent = status.version || '-';
        
        // Format uptime
        if (status.uptime) {
            const hours = Math.floor(status.uptime / 3600000);
            const minutes = Math.floor((status.uptime % 3600000) / 60000);
            const seconds = Math.floor((status.uptime % 60000) / 1000);
            document.getElementById('uptime').textContent = `${hours}时 ${minutes}分 ${seconds}秒`;
        }
        
        // Queue status
        if (status.inQueue) {
            document.getElementById('queueStatus').textContent = `队列位置: ${status.queuePosition}`;
            document.getElementById('queueStatus').style.color = 'var(--warning-color)';
        } else {
            document.getElementById('queueStatus').textContent = '不在队列中';
            document.getElementById('queueStatus').style.color = 'var(--success-color)';
        }
        
        // Update version
        document.getElementById('version').textContent = 'v' + status.version;
    }
}

function refreshDashboard() {
    loadDashboard();
}

// Modules
async function loadModules() {
    const modules = await apiCall('/modules');
    if (modules) {
        const container = document.getElementById('modulesList');
        container.innerHTML = modules.map(module => `
            <div class="module-card">
                <div class="module-info">
                    <h3>${module.name}</h3>
                    <p>${module.enabled ? '已启用' : '已禁用'}</p>
                </div>
                <label class="toggle">
                    <input type="checkbox" ${module.enabled ? 'checked' : ''} onchange="toggleModule('${module.name}', this.checked)">
                    <span class="toggle-slider"></span>
                </label>
            </div>
        `).join('');
    }
}

async function toggleModule(name, enabled) {
    await apiCall('/modules', 'POST', { name, enabled });
    loadModules();
}

function refreshModules() {
    loadModules();
}

// Config
async function loadConfig() {
    const config = await apiCall('/config');
    if (config) {
        renderConfigSections(config);
    }
}

function renderConfigSections(config) {
    const container = document.getElementById('configContent');
    container.innerHTML = `
        <div class="config-section active" id="authSection">
            <h3>认证设置 <span class="en">Authentication</span></h3>
            <div class="form-group">
                <label>账号类型 <span class="en">Account Type</span></label>
                <select id="accountType">
                    <option value="DEVICE_CODE" ${config.authentication?.accountType === 'DEVICE_CODE' ? 'selected' : ''}>Device Code</option>
                    <option value="MSA" ${config.authentication?.accountType === 'MSA' ? 'selected' : ''}>Microsoft Account 微软账号</option>
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
        if (e.target.classList.contains('config-tab')) {
            // Update tabs
            document.querySelectorAll('.config-tab').forEach(tab => {
                tab.classList.remove('active');
            });
            e.target.classList.add('active');
            
            // Update sections
            const tabName = e.target.dataset.tab;
            document.querySelectorAll('.config-section').forEach(section => {
                section.classList.remove('active');
            });
            
            const sectionMap = {
                'authentication': 'authSection',
                'client': 'clientSection',
                'server': 'serverSection'
            };
            
            document.getElementById(sectionMap[tabName])?.classList.add('active');
        }
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
        showNotification('保存失败 / Save failed: ' + (result?.error || '未知错误 / Unknown error'), 'error');
    }
}

// Terminal
function initTerminal() {
    const input = document.getElementById('terminalInput');
    input.addEventListener('keydown', (e) => {
        if (e.key === 'Enter') {
            executeCommand(input.value);
            input.value = '';
        } else if (e.key === 'ArrowUp') {
            e.preventDefault();
            if (historyIndex < commandHistory.length - 1) {
                historyIndex++;
                input.value = commandHistory[commandHistory.length - 1 - historyIndex];
            }
        } else if (e.key === 'ArrowDown') {
            e.preventDefault();
            if (historyIndex > 0) {
                historyIndex--;
                input.value = commandHistory[commandHistory.length - 1 - historyIndex];
            } else {
                historyIndex = -1;
                input.value = '';
            }
        }
    });
}

async function executeCommand(command) {
    if (!command.trim()) return;
    
    // Add to history
    commandHistory.push(command);
    historyIndex = -1;
    
    // Add to output
    addTerminalLine(`> ${command}`, 'command');
    
    // Execute
    const result = await apiCall('/commands', 'POST', { command });
    if (result) {
        if (result.output && Array.isArray(result.output)) {
            result.output.forEach(line => addTerminalLine(line, 'output'));
        } else if (result.success) {
            addTerminalLine('命令执行成功 / Command executed', 'success');
        } else {
            addTerminalLine('错误 / Error: ' + (result.error || '未知错误 / Unknown error'), 'error');
        }
    }
}

function addTerminalLine(text, type = '') {
    const output = document.getElementById('terminalOutput');
    const line = document.createElement('div');
    line.className = `terminal-line ${type}`;
    line.textContent = text;
    output.appendChild(line);
    output.scrollTop = output.scrollHeight;
}

function clearTerminal() {
    const output = document.getElementById('terminalOutput');
    output.innerHTML = `
        <div class="terminal-welcome">
            <p>ZenithProxy 命令终端 <span class="en">Command Terminal</span></p>
            <p>输入命令并按回车执行 <span class="en">Type a command and press Enter</span></p>
        </div>
    `;
}

// Logs
function initWebSocket() {
    try {
        ws = new WebSocket(`ws://${window.location.hostname}:${wsPort}`);
        
        ws.onopen = () => {
            updateConnectionStatus(true);
        };
        
        ws.onmessage = (event) => {
            const data = JSON.parse(event.data);
            handleWebSocketMessage(data);
        };
        
        ws.onclose = () => {
            updateConnectionStatus(false);
            // Reconnect after 3 seconds
            setTimeout(initWebSocket, 3000);
        };
        
        ws.onerror = (error) => {
            console.error('WebSocket error:', error);
        };
    } catch (error) {
        console.error('WebSocket initialization failed:', error);
    }
}

function updateConnectionStatus(connected) {
    const statusDot = document.querySelector('.status-dot');
    const statusText = document.querySelector('.status-text');

    if (connected) {
        statusDot.className = 'status-dot connected';
        statusText.innerHTML = '已连接 <span class="en" style="font-size:0.7rem;color:var(--ctp-overlay0);">Connected</span>';
    } else {
        statusDot.className = 'status-dot disconnected';
        statusText.innerHTML = '未连接 <span class="en" style="font-size:0.7rem;color:var(--ctp-overlay0);">Disconnected</span>';
    }
}

function handleWebSocketMessage(data) {
    switch (data.type) {
        case 'log':
            addLogEntry(data.data);
            break;
        case 'status':
            updateDashboardStatus(data.data);
            break;
    }
}

function addLogEntry(log) {
    logs.push(log);

    // Filter by level
    const levelFilter = document.getElementById('logLevel')?.value || 'all';
    if (levelFilter !== 'all' && log.level !== levelFilter) {
        return;
    }

    const container = document.getElementById('logsOutput');
    const emptyMsg = container.querySelector('.log-empty');
    if (emptyMsg) {
        emptyMsg.remove();
    }

    const entry = document.createElement('div');
    entry.className = 'log-entry';

    const timestamp = new Date(log.timestamp).toLocaleTimeString();
    entry.innerHTML = `
        <span class="log-timestamp">${timestamp}</span>
        <span class="log-level ${log.level.toLowerCase()}">${log.level}</span>
        <span class="log-message">[${log.logger}] ${log.message}</span>
    `;

    container.appendChild(entry);
    container.scrollTop = container.scrollHeight;

    // Keep only last 1000 entries
    if (container.children.length > 1000) {
        container.removeChild(container.firstChild);
    }
}

function filterLogs() {
    const container = document.getElementById('logsOutput');
    container.innerHTML = '';
    
    const levelFilter = document.getElementById('logLevel').value;
    logs.forEach(log => {
        if (levelFilter === 'all' || log.level === levelFilter) {
            addLogEntry(log);
        }
    });
}

function clearLogs() {
    document.getElementById('logsOutput').innerHTML = '<div class="log-empty">等待日志... Waiting for logs...</div>';
    logs = [];
}

function exportLogs() {
    const logText = logs.map(log => {
        const timestamp = new Date(log.timestamp).toISOString();
        return `[${timestamp}] [${log.level}] [${log.logger}] ${log.message}`;
    }).join('\n');

    const blob = new Blob([logText], { type: 'text/plain' });
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = `zenithproxy-logs-${new Date().toISOString().split('T')[0]}.txt`;
    a.click();
    URL.revokeObjectURL(url);
    showNotification('日志已导出 / Logs exported', 'success');
}

// Players
async function loadPlayers() {
    const players = await apiCall('/players');
    if (players) {
        const container = document.getElementById('playersList');
        if (players.length === 0) {
            container.innerHTML = '<tr><td colspan="4" style="text-align: center; padding: 40px; color: var(--ctp-overlay1);">没有连接的玩家 / No connected players</td></tr>';
        } else {
            container.innerHTML = players.map(player => `
                <tr>
                    <td>${player.name}</td>
                    <td style="font-family: monospace; font-size: 0.85rem; color: var(--ctp-overlay1);">${player.uuid}</td>
                    <td><span class="player-badge ${player.type}">${player.type === 'controller' ? '控制器 Controller' : '观察者 Spectator'}</span></td>
                    <td>
                        <button class="btn btn-secondary" onclick="sendCommand('/kick ${player.name}')">踢出 Kick</button>
                    </td>
                </tr>
            `).join('');
        }
    }
}

function refreshPlayers() {
    loadPlayers();
}

// Utility functions
function sendCommand(command) {
    executeCommand(command);
}

function updateDashboardStatus(status) {
    if (status) {
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
}

function showNotification(message, type = 'info') {
    const notification = document.createElement('div');
    notification.className = `notification ${type}`;
    notification.textContent = message;
    notification.style.cssText = `
        position: fixed;
        top: 16px;
        right: 16px;
        padding: 12px 20px;
        border-radius: 4px;
        color: var(--ctp-crust);
        font-weight: 500;
        font-size: 0.85rem;
        z-index: 1000;
        opacity: 0;
        transform: translateY(-8px);
        transition: opacity 0.2s ease, transform 0.2s ease;
    `;

    switch (type) {
        case 'success':
            notification.style.backgroundColor = 'var(--success-color)';
            break;
        case 'error':
            notification.style.backgroundColor = 'var(--error-color)';
            break;
        case 'warning':
            notification.style.backgroundColor = 'var(--warning-color)';
            break;
        default:
            notification.style.backgroundColor = 'var(--info-color)';
    }

    document.body.appendChild(notification);

    requestAnimationFrame(() => {
        notification.style.opacity = '1';
        notification.style.transform = 'translateY(0)';
    });

    setTimeout(() => {
        notification.style.opacity = '0';
        notification.style.transform = 'translateY(-8px)';
        setTimeout(() => notification.remove(), 200);
    }, 3000);
}
