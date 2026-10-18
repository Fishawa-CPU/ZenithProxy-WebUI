/*
 * ZenithProxy Bot Manager — 前端
 * 两种运行形态：
 *   1) 总览页（/）           —— 渲染 bot 卡片、启停、新建、删除、改设置
 *   2) 注入进 bot 的 WebUI   —— 在左侧栏插入 bot 切换器
 * 无框架、无构建步骤，与原 WebUI 的前端约定保持一致。
 */
(function () {
    'use strict';

    var isBotPage = typeof window.__BOT_NAME__ === 'string';
    var currentBot = isBotPage ? window.__BOT_NAME__ : null;
    var pollTimer = null;

    // ────────────────────────────────────────────── 公共

    function api(body) {
        var opt = body
            ? { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body) }
            : { method: 'GET' };
        return fetch('/api/bots', opt).then(function (r) {
            return r.json().catch(function () { return { ok: false, error: 'HTTP ' + r.status }; });
        });
    }

    function esc(s) {
        return String(s == null ? '' : s).replace(/[&<>"']/g, function (c) {
            return ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[c];
        });
    }

    function initial(name) {
        return (name || '?').slice(0, 1);
    }

    function fmtUptime(ms) {
        if (!ms || ms < 0) return '—';
        var s = Math.floor(ms / 1000);
        var d = Math.floor(s / 86400);
        var h = Math.floor((s % 86400) / 3600);
        var m = Math.floor((s % 3600) / 60);
        if (d > 0) return d + '天' + h + '小时';
        if (h > 0) return h + '小时' + m + '分';
        return m + '分' + (s % 60) + '秒';
    }

    function toast(msg, isError) {
        var el = document.querySelector('.mgr-toast');
        if (!el) {
            el = document.createElement('div');
            el.className = 'mgr-toast';
            document.body.appendChild(el);
        }
        el.textContent = msg;
        el.className = 'mgr-toast' + (isError ? ' error' : '');
        // 强制重排以重启动画
        void el.offsetWidth;
        el.classList.add('show');
        clearTimeout(el._t);
        el._t = setTimeout(function () { el.classList.remove('show'); }, 2600);
    }

    /** 未登录时不要露出 "Unknown"：那是 config.json 里等待回写的占位值。 */
    function accountText(b) {
        return b.loggedIn && b.account ? b.account : '未登录';
    }

    function serverText(b) {
        var host = b.server || '—';
        var port = b.serverPort || 25565;
        return host + ':' + port;
    }

    /**
     * 配置字段的中文名/说明，来自 zenith-config 的字段表（498 个字段）。
     * 单独一个 json 资源，避免把它塞进这个文件。
     */
    var CFG_LABELS = null;
    var SECTION_LABELS = {
        warning: '警告', authentication: '账号认证', client: '客户端', debug: '调试',
        server: '服务端', plugins: '插件', interactiveTerminal: '交互终端',
        inGameCommands: '游戏内命令', theme: '主题配色', discord: 'Discord',
        database: '数据库', autoUpdater: '自动更新'
    };

    /** 嵌套分组的中文名（没有就显示原字段名） */
    var GROUP_LABELS = {
        bind: '监听绑定', server: '目标服务器', connectionProxy: '连接代理', viaversion: 'ViaVersion',
        timeout: '超时', ping: 'Ping', chatSigning: '聊天签名', extra: '扩展功能', inventory: '背包',
        chatSchemas: '聊天架构', keepAliveHandling: 'KeepAlive', antiafk: '防AFK', spook: '惊吓模块',
        utility: '实用工具', actions: '自动动作', autoDisconnect: '自动断开', activeHours: '活跃时段',
        autoReconnect: '自动重连', autoRespawn: '自动重生', spammer: '刷屏', autoReply: '自动回复',
        stalk: '追踪', autoEat: '自动进食', autoFish: '自动钓鱼', killAura: '战斗辅助',
        autoTotem: '自动图腾', antiLeak: '防泄露', chat: '聊天', antiKick: '防踢',
        replayMod: '回放', coordObfuscation: '坐标混淆', actionLimiter: '动作限制',
        visualRange: '视觉范围', autoArmor: '自动穿甲', autoMend: '自动修补', queueWarning: '队列提醒',
        click: '点击模拟', sessionTimeLimit: '会话时限', autoOmen: '自动凶兆', pathfinder: '寻路',
        spawnPatrol: '出生点巡逻', pearlLoader: '珍珠加载', waypoints: '路点', autoDrop: '自动丢弃',
        tasks: '任务', packetLog: '抓包日志', clientPacketLog: '客户端包', serverPacketLog: '服务端包',
        cache: '缓存', whitelist: '白名单', chatHistory: '聊天历史', serverSwitcher: '服务器切换',
        spectator: '观战', loginRateLimiter: '登录限流', packetRateLimiter: '封包限流',
        loginTimeout: '登录超时', lock: '分布式锁', chatRelay: '聊天中继'
    };

    function loadLabels() {
        if (CFG_LABELS) return Promise.resolve(CFG_LABELS);
        return fetch('/__mgr/labels.json')
            .then(function (r) { return r.json(); })
            .then(function (j) { CFG_LABELS = j || {}; return CFG_LABELS; })
            .catch(function () { CFG_LABELS = {}; return CFG_LABELS; });
    }

    function metaOf(path) {
        return (CFG_LABELS && CFG_LABELS[path]) || null;
    }

    /** 登录方式：直接给中文下拉，不要让人去猜 device_code 是什么 */
    var ACCOUNT_OPTIONS = [
        { v: 'device_code', t: '正版 · 设备码登录（推荐）' },
        { v: 'msa', t: '正版 · 邮箱 + 密码' },
        { v: 'prism', t: '正版 · Prism 启动器' },
        { v: 'offline', t: '离线（盗版服 / 本地测试）' }
    ];

    /**
     * 配置只有进程启动时才读进内存（ZenithProxy 的 CONFIG 是 static final，跑起来就不再读盘），
     * 所以改完必须重启才生效。这里直接替用户把重启做了。
     */
    function applyIfRunning(bot, okMsg) {
        if (bot && bot.running) {
            api({ action: 'restart', name: bot.name }).then(function (r) {
                if (r && r.ok) toast(okMsg + ' · 已重启生效');
                else toast(okMsg + ' · 重启失败，请手动重启', true);
                refresh();
            });
        } else {
            toast(okMsg);
            refresh();
        }
    }

    // ══════════════════════════════════════════════ 账号类型

    var ACCOUNT_TYPES = [
        {
            v: 'device_code',
            label: '正版 · 设备码登录（推荐）',
            hint: '不用在这里填。启动后点「连接」，按终端里的 microsoft.com/link 输码，登录成功后自动记住。'
        },
        {
            v: 'msa',
            label: '正版 · 邮箱密码',
            hint: '填微软邮箱和密码，启动后点「连接」直接登录。密码以明文存在本地配置里，能不用就不用。'
        },
        {
            v: 'prism',
            label: '正版 · Prism 启动器',
            hint: '复用 Prism Launcher 的登录缓存，需要本机已经用 Prism 登录过同一个号。'
        },
        {
            v: 'offline',
            label: '离线（盗版服 / 本地测试）',
            hint: '游戏名就是角色名，UUID 按名字固定推导。2b2t 这类正版验证服不接受离线账号。'
        }
    ];

    function typeMeta(v) {
        for (var i = 0; i < ACCOUNT_TYPES.length; i++) {
            if (ACCOUNT_TYPES[i].v === v) return ACCOUNT_TYPES[i];
        }
        return ACCOUNT_TYPES[0];
    }

    // ══════════════════════════════════════════════ 新建 / 设置弹窗

    function openBotDialog(bot, onSaved) {
        var isEdit = !!bot;
        var old = document.querySelector('.mgr-modal-mask');
        if (old) old.parentNode.removeChild(old);

        var accType = (bot && bot.accountManaged && bot.accountType) ? bot.accountType : 'device_code';
        var options = ACCOUNT_TYPES.map(function (t) {
            return '<option value="' + t.v + '"' + (t.v === accType ? ' selected' : '') + '>' + t.label + '</option>';
        }).join('');

        var mask = document.createElement('div');
        mask.className = 'mgr-modal-mask';
        mask.innerHTML =
            '<div class="mgr-modal" role="dialog" aria-modal="true">' +
            '  <div class="mgr-modal-title">' + (isEdit ? 'Bot 设置' : '新建 Bot') + '</div>' +

            '  <div class="mgr-field">' +
            '    <label>名称</label>' +
            '    <input id="mgr-f-name" type="text" maxlength="32" ' +
            (isEdit ? 'value="' + esc(bot.name) + '" disabled' : 'value="bot' + (Date.now() % 1000) + '"') + '>' +
            '    <div class="mgr-hint">字母/数字/下划线/连字符，1-32 位</div>' +
            '  </div>' +

            '  <div class="mgr-field mgr-field-row">' +
            '    <div style="flex:1">' +
            '      <label>目标服务器</label>' +
            '      <input id="mgr-f-host" type="text" placeholder="2b2t.org" value="' + esc((bot && bot.server) || '2b2t.org') + '">' +
            '    </div>' +
            '    <div style="width:110px">' +
            '      <label>端口</label>' +
            '      <input id="mgr-f-port" type="number" min="1" max="65535" value="' + ((bot && bot.serverPort) || 25565) + '">' +
            '    </div>' +
            '  </div>' +

            '  <div class="mgr-field">' +
            '    <label>登录方式</label>' +
            '    <select id="mgr-f-atype">' + options + '</select>' +
            '    <div class="mgr-hint" id="mgr-f-ahint"></div>' +
            '  </div>' +

            '  <div class="mgr-field" id="mgr-f-offline" style="display:none">' +
            '    <label>离线游戏名</label>' +
            '    <input id="mgr-f-auser" type="text" maxlength="16" placeholder="Steve" value="' +
            esc((bot && bot.accountUsername) || '') + '">' +
            '    <div class="mgr-hint">进入服务器后显示的角色名，改名字会换掉离线 UUID</div>' +
            '  </div>' +

            '  <div id="mgr-f-msa" style="display:none">' +
            '    <div class="mgr-field">' +
            '      <label>微软邮箱</label>' +
            '      <input id="mgr-f-aemail" type="text" placeholder="you@example.com" value="' +
            esc((bot && bot.accountEmail) || '') + '">' +
            '    </div>' +
            '    <div class="mgr-field">' +
            '      <label>密码</label>' +
            '      <input id="mgr-f-apass" type="password" placeholder="' +
            (bot && bot.accountPassword ? '已保存，留空则不修改' : '') + '" value="">' +
            '    </div>' +
            '  </div>' +

            '  <label class="mgr-check">' +
            '    <input id="mgr-f-auto" type="checkbox" ' + ((bot && bot.autoConnect) ? 'checked' : '') + '>' +
            '    <span>启动后自动连接这个服务器</span>' +
            '  </label>' +

            '  <div class="mgr-modal-note" id="mgr-f-note"></div>' +
            '  <div class="mgr-modal-foot">' +
            (isEdit ? '    <button class="mgr-btn" id="mgr-f-full" style="margin-right:auto">全部配置</button>' : '') +
            '    <button class="mgr-btn" id="mgr-f-cancel">取消</button>' +
            '    <button class="mgr-btn primary" id="mgr-f-save">' + (isEdit ? '保存' : '创建') + '</button>' +
            '  </div>' +
            '</div>';

        document.body.appendChild(mask);

        var nameEl = mask.querySelector('#mgr-f-name');
        var typeEl = mask.querySelector('#mgr-f-atype');
        var offlineBox = mask.querySelector('#mgr-f-offline');
        var msaBox = mask.querySelector('#mgr-f-msa');
        var hintEl = mask.querySelector('#mgr-f-ahint');
        var noteEl = mask.querySelector('#mgr-f-note');

        function refreshType() {
            var t = typeEl.value;
            var meta = typeMeta(t);
            hintEl.textContent = meta.hint;
            offlineBox.style.display = t === 'offline' ? '' : 'none';
            msaBox.style.display = t === 'msa' ? '' : 'none';

            var login = isEdit
                ? (bot.loggedIn && bot.account ? '已登录：<b>' + esc(bot.account) + '</b>' : '当前状态：<b>未登录</b>')
                : '当前状态：<b>尚未登录</b>';
            noteEl.innerHTML = login +
                '<br>账号信息在 bot 启动时写入 <code>bots/&lt;名字&gt;/config.json</code> 的 authentication 段，改完要重启 bot 才生效。';
        }

        typeEl.addEventListener('change', refreshType);
        refreshType();

        if (!isEdit) setTimeout(function () { nameEl.focus(); nameEl.select(); }, 30);

        function close() {
            if (mask.parentNode) mask.parentNode.removeChild(mask);
        }

        mask.addEventListener('click', function (e) {
            if (e.target === mask) close();
        });
        mask.querySelector('#mgr-f-cancel').addEventListener('click', close);
        if (isEdit) {
            mask.querySelector('#mgr-f-full').addEventListener('click', function () {
                close();
                openConfigEditor(bot);
            });
        }
        mask.addEventListener('keydown', function (e) {
            if (e.key === 'Escape') close();
            if (e.key === 'Enter' && e.target.tagName !== 'TEXTAREA') mask.querySelector('#mgr-f-save').click();
        });

        mask.querySelector('#mgr-f-save').addEventListener('click', function () {
            var type = typeEl.value;
            var user = mask.querySelector('#mgr-f-auser').value.trim();
            var pass = mask.querySelector('#mgr-f-apass').value;

            if (type === 'offline' && !user) {
                toast('离线模式必须填游戏名', true);
                return;
            }
            if (type === 'offline' && /^2b2t\./i.test(mask.querySelector('#mgr-f-host').value.trim())) {
                toast('2b2t 是正版验证服，离线账号连不上', true);
                return;
            }

            var payload = {
                server: mask.querySelector('#mgr-f-host').value.trim() || '2b2t.org',
                serverPort: parseInt(mask.querySelector('#mgr-f-port').value, 10) || 25565,
                autoConnect: mask.querySelector('#mgr-f-auto').checked,
                accountType: type,
                accountUsername: user,
                accountEmail: mask.querySelector('#mgr-f-aemail').value.trim(),
                accountPassword: pass
            };
            // 密码留空 = 不修改已保存的密码
            if (type === 'msa' && !pass) delete payload.accountPassword;

            if (isEdit) {
                payload.action = 'update';
                payload.name = bot.name;
            } else {
                payload.action = 'create';
                payload.name = nameEl.value.trim();
                if (!payload.name) {
                    toast('请填写名称', true);
                    return;
                }
            }

            var btn = mask.querySelector('#mgr-f-save');
            btn.disabled = true;
            api(payload).then(function (res) {
                btn.disabled = false;
                if (!res.ok) return toast(res.error || '保存失败', true);
                close();
                applyIfRunning(bot, isEdit ? '已保存设置' : '已创建 ' + payload.name);
                if (onSaved && !isEdit) onSaved();
            });
        });
    }

    // ══════════════════════════════════════════════ 全部配置编辑器

    /**
     * 直接编辑 bots/&lt;name&gt;/config.json 的完整内容（字段集与 ZenithProxy 的
     * Config.java 一致）。保存前会把原文件备份成 config.json.bak。
     */
    function openConfigEditor(bot) {
        loadLabels().then(function () {
            return fetch('/api/bots?config=' + encodeURIComponent(bot.name)).then(function (r) { return r.json(); });
        }).then(function (res) {
            if (!res || !res.config) return toast((res && res.error) || '读取配置失败', true);
            buildConfigEditor(bot, res, false);
        }).catch(function () { toast('读取配置失败', true); });
    }

    /** 新建：直接拿完整模板配置给用户改，改完再创建 */
    function openCreateEditor() {
        loadLabels().then(function () {
            return fetch('/api/bots?template=1').then(function (r) { return r.json(); });
        }).then(function (res) {
            if (!res || !res.config) return toast((res && res.error) || '读取配置模板失败', true);
            buildConfigEditor(null, res, true);
        }).catch(function () { toast('读取配置模板失败', true); });
    }

    function buildConfigEditor(bot, res, isCreate) {
        var draft = res.config;
        var old = document.querySelector('.mgr-cfg-mask');
        if (old) old.parentNode.removeChild(old);

        var defaultName = 'bot' + (Date.now() % 1000);
        var mask = document.createElement('div');
        mask.className = 'mgr-modal-mask mgr-cfg-mask';
        mask.innerHTML =
            '<div class="mgr-cfg">' +
            '  <div class="mgr-cfg-head">' +
            '    <div style="min-width:0">' +
            '      <div class="mgr-cfg-title">' +
            (isCreate ? '新建 Bot · 全部配置' : esc(bot.name) + ' · 全部配置') + '</div>' +
            '      <div class="mgr-cfg-path">' +
            (isCreate ? '模板 → bots/&lt;名字&gt;/config.json' : esc(res.path)) + '</div>' +
            '    </div>' +
            '    <div class="mgr-cfg-tools">' +
            (isCreate ? '      <input id="mgr-cfg-name" type="text" maxlength="32" placeholder="Bot 名称" value="' + defaultName + '">' : '') +
            '      <input id="mgr-cfg-search" type="text" placeholder="搜索字段…">' +
            '      <button class="mgr-btn" id="mgr-cfg-cancel">取消</button>' +
            '      <button class="mgr-btn primary" id="mgr-cfg-save">' + (isCreate ? '创建' : '保存') + '</button>' +
            '    </div>' +
            '  </div>' +
            '  <div class="mgr-cfg-warn">' +
            (isCreate
                ? '这份配置会原样写进新 bot 自己的 <code>bots/&lt;名字&gt;/config.json</code>；游戏端口、WebUI 端口由管理器分配后覆盖。只连 2b2t 的话，填好名字直接点「创建」就行。'
                : '端口、目标服务器、登录方式由管理器托管，下次启动会被重新对齐；其余字段原样写回。') +
            '  </div>' +
            '  <div class="mgr-cfg-body" id="mgr-cfg-body"></div>' +
            '</div>';

        document.body.appendChild(mask);

        var body = mask.querySelector('#mgr-cfg-body');

        function renderNode(obj, path) {
            var html = '';
            Object.keys(obj).forEach(function (k) {
                var v = obj[k];
                var p = path.concat([k]);
                var full = p.join('.');
                var enc = encodeURIComponent(JSON.stringify(p));
                var isObj = v !== null && typeof v === 'object' && !Array.isArray(v);
                var meta = metaOf(full);
                var cn = meta ? meta[0] : null;

                if (isObj) {
                    var groupName = SECTION_LABELS[k] || GROUP_LABELS[k] || k;
                    html += '<details class="cfg-group"' + (path.length === 0 ? ' open' : '') + '>' +
                        '<summary><span class="cfg-key">' + esc(groupName) + '</span>' +
                        (groupName !== k ? '<span class="cfg-en">' + esc(k) + '</span>' : '') +
                        '<span class="cfg-count">' + Object.keys(v).length + '</span></summary>' +
                        '<div class="cfg-children">' + renderNode(v, p) + '</div></details>';
                    return;
                }

                var input;
                if (full === 'authentication.accountType') {
                    input = '<select data-path="' + enc + '" data-kind="str">' +
                        ACCOUNT_OPTIONS.map(function (o) {
                            return '<option value="' + o.v + '"' + (String(v) === o.v ? ' selected' : '') + '>' +
                                o.t + '（' + o.v + '）</option>';
                        }).join('') + '</select>';
                } else if (typeof v === 'boolean') {
                    input = '<input type="checkbox" data-path="' + enc + '" data-kind="bool"' + (v ? ' checked' : '') + '>';
                } else if (typeof v === 'number') {
                    input = '<input type="number" step="any" data-path="' + enc + '" data-kind="num" value="' + v + '">';
                } else if (Array.isArray(v)) {
                    input = '<textarea rows="2" data-path="' + enc + '" data-kind="arr">' + esc(JSON.stringify(v)) + '</textarea>';
                } else {
                    var s = v === null ? '' : String(v);
                    input = s.length > 60
                        ? '<textarea rows="2" data-path="' + enc + '" data-kind="str">' + esc(s) + '</textarea>'
                        : '<input type="text" data-path="' + enc + '" data-kind="str" value="' + esc(s) + '"' +
                          (v === null ? ' placeholder="null"' : '') + '>';
                }

                var tip = full + (meta && meta[1] ? '\n' + meta[1] : '');
                html += '<div class="cfg-row" data-key="' + esc(full.toLowerCase()) + '"' +
                    ' data-label="' + esc((cn || '').toLowerCase()) + '">' +
                    '<div class="cfg-key" title="' + esc(tip) + '">' +
                    (cn ? esc(cn) + '<span class="cfg-en">' + esc(k) + '</span>' : esc(k)) +
                    '</div>' +
                    '<div class="cfg-val">' + input + '</div></div>';
            });
            return html;
        }

        body.innerHTML = renderNode(draft, []);

        // 选「离线」时把游戏名那一行拉到眼前，并清掉模板里的 Unknown
        body.addEventListener('change', function (e) {
            var el = e.target;
            if (!el || el.tagName !== 'SELECT' || el.dataset.kind !== 'str') return;
            var parts;
            try { parts = JSON.parse(decodeURIComponent(el.dataset.path)); } catch (x) { return; }
            if (parts.join('.') !== 'authentication.accountType' || el.value !== 'offline') return;

            var row = body.querySelector('.cfg-row[data-key="authentication.username"]');
            var inp = row && row.querySelector('input, textarea');
            if (inp) {
                if (!inp.value || inp.value.toLowerCase() === 'unknown') {
                    inp.value = '';
                    inp.dispatchEvent(new Event('input', { bubbles: true }));
                    inp.dispatchEvent(new Event('change', { bubbles: true }));
                }
                inp.placeholder = '必填：你的离线游戏名';
                if (row.scrollIntoView) row.scrollIntoView({ block: 'center' });
                inp.focus();
            }
            toast('离线模式：填上游戏名就能用，UUID 会按名字自动推导');
        });

        function setAt(root, parts, value) {
            var o = root;
            for (var i = 0; i < parts.length - 1; i++) o = o[parts[i]];
            o[parts[parts.length - 1]] = value;
        }

        function onEdit(e) {
            var el = e.target;
            if (!el || !el.dataset || !el.dataset.path) return;
            var parts;
            try { parts = JSON.parse(decodeURIComponent(el.dataset.path)); } catch (err) { return; }
            var kind = el.dataset.kind;
            if (kind === 'bool') {
                setAt(draft, parts, el.checked);
            } else if (kind === 'num') {
                setAt(draft, parts, el.value === '' ? 0 : Number(el.value));
            } else if (kind === 'arr') {
                try {
                    setAt(draft, parts, JSON.parse(el.value));
                    el.classList.remove('bad');
                } catch (err) {
                    el.classList.add('bad');
                }
            } else {
                setAt(draft, parts, el.value);
            }
        }

        body.addEventListener('input', onEdit);
        body.addEventListener('change', onEdit);

        // 搜索：按完整路径过滤
        mask.querySelector('#mgr-cfg-search').addEventListener('input', function () {
            var q = this.value.trim().toLowerCase();
            if (q) mask.querySelectorAll('details').forEach(function (d) { d.open = true; });
            body.querySelectorAll('.cfg-row').forEach(function (row) {
                var hit = !q
                    || row.getAttribute('data-key').indexOf(q) >= 0
                    || (row.getAttribute('data-label') || '').indexOf(q) >= 0;
                row.style.display = hit ? '' : 'none';
            });
        });

        function close() {
            if (mask.parentNode) mask.parentNode.removeChild(mask);
        }

        mask.querySelector('#mgr-cfg-cancel').addEventListener('click', close);
        mask.addEventListener('keydown', function (e) {
            if (e.key === 'Escape') close();
        });

        mask.querySelector('#mgr-cfg-save').addEventListener('click', function () {
            if (body.querySelector('textarea.bad')) {
                toast('有数组字段不是合法 JSON，先修正', true);
                return;
            }
            var btn = this;

            if (isCreate) {
                var nameEl = mask.querySelector('#mgr-cfg-name');
                var botName = nameEl.value.trim();
                if (!botName) {
                    toast('请填写 Bot 名称', true);
                    return;
                }
                var auth = draft.authentication || {};
                if (String(auth.accountType) === 'offline') {
                    var uname = String(auth.username || '').trim();
                    if (!uname || uname.toLowerCase() === 'unknown') {
                        toast('离线模式请先在「离线游戏名」里填游戏名', true);
                        return;
                    }
                }
                btn.disabled = true;
                btn.textContent = '创建中…';
                api({ action: 'create', name: botName, config: draft }).then(function (r) {
                    btn.disabled = false;
                    btn.textContent = '创建';
                    if (!r || !r.ok) return toast((r && r.error) || '创建失败', true);
                    toast('已创建 ' + botName + '，配置已写入它自己的目录');
                    close();
                    refresh();
                });
                return;
            }

            btn.disabled = true;
            btn.textContent = '保存中…';
            api({ action: 'saveConfig', name: bot.name, config: draft }).then(function (r) {
                btn.disabled = false;
                btn.textContent = '保存';
                if (!r || !r.ok) return toast((r && r.error) || '保存失败', true);
                close();
                if (r.hotReload) {
                    toast('已保存，热加载生效（进程未重启）');
                } else if (r.running) {
                    toast('已保存，但没等到热加载确认；改端口/账号仍需重启', true);
                } else {
                    toast('已保存到 config.json（bot 未运行，下次启动生效）');
                }
                refresh();
            });
        });
    }

    // ══════════════════════════════════════════════ 形态 1：总览页

    function initOverview() {
        document.documentElement.classList.add('mgr-page');
        document.body.classList.add('mgr-page');
        document.body.innerHTML =
            '<div class="mgr-wrap">' +
            '  <div class="mgr-head">' +
            '    <h1>ZenithProxy 管理器<span class="mgr-sub">一个窗口管理所有 bot</span></h1>' +
            '    <div class="mgr-actions">' +
            '      <button class="mgr-btn primary" id="mgr-new">+ 新建 Bot</button>' +
            '    </div>' +
            '  </div>' +
            '  <div class="mgr-grid" id="mgr-grid"><div class="mgr-empty">加载中…</div></div>' +
            '</div>';

        document.getElementById('mgr-new').addEventListener('click', function () {
            openCreateEditor();
        });

        refresh();
        pollTimer = setInterval(refresh, 2500);
    }

    function refresh() {
        api().then(function (res) {
            if (!res.bots) return;
            renderGrid(res.bots);
        }).catch(function () { /* 管理器暂时不可达，保持上一帧 */ });
    }

    function renderGrid(bots) {
        var grid = document.getElementById('mgr-grid');
        if (!grid) return;

        if (!bots.length) {
            grid.innerHTML = '<div class="mgr-empty"><strong>还没有 bot</strong>点右上角「新建 Bot」开始</div>';
            return;
        }

        var html = bots.map(function (b) {
            var dotCls = b.reachable ? 'on' : (b.running ? 'warn' : 'off');
            var statusText = b.reachable ? '在线' : (b.running ? '启动中' : '已停止');
            var accCls = b.loggedIn ? 'ok' : 'warn';
            var accLabel = b.loggedIn ? accountText(b) : (b.accountType === 'offline' ? '离线 · 未启动' : '未登录');
            return '' +
                '<div class="mgr-card" data-name="' + esc(b.name) + '">' +
                '  <div class="mgr-card-top">' +
                '    <div class="mgr-avatar">' + esc(initial(b.name)) + '</div>' +
                '    <div style="min-width:0;flex:1">' +
                '      <div class="mgr-card-name">' + esc(b.name) +
                '        <span class="mgr-dot ' + dotCls + '" title="' + statusText + '"></span>' +
                '      </div>' +
                '      <div class="mgr-card-meta">' + statusText + ' · 游戏端口 ' + b.proxyPort + '</div>' +
                '    </div>' +
                '  </div>' +
                '  <div class="mgr-stats">' +
                '    <div><div class="mgr-stat-k">账号</div><div class="mgr-stat-v mgr-acc ' + accCls + '">' + esc(accLabel) + '</div></div>' +
                '    <div><div class="mgr-stat-k">目标服务器</div><div class="mgr-stat-v">' + esc(serverText(b)) + '</div></div>' +
                '    <div><div class="mgr-stat-k">运行时长</div><div class="mgr-stat-v">' + fmtUptime(b.uptimeMs) + '</div></div>' +
                '  </div>' +
                '  <div class="mgr-card-foot">' +
                (b.reachable
                    ? '<button class="mgr-btn primary" data-act="open">打开</button>'
                    : '<button class="mgr-btn primary" data-act="start">启动</button>') +
                (b.running ? '<button class="mgr-btn" data-act="stop">停止</button>' : '') +
                '    <button class="mgr-btn" data-act="config">设置</button>' +
                '    <button class="mgr-btn danger" data-act="delete">删除</button>' +
                '  </div>' +
                '</div>';
        }).join('');

        grid.innerHTML = html;

        grid.querySelectorAll('.mgr-card').forEach(function (card) {
            var name = card.getAttribute('data-name');
            var bot = bots.filter(function (x) { return x.name === name; })[0];
            card.querySelectorAll('[data-act]').forEach(function (btn) {
                btn.addEventListener('click', function () {
                    var act = btn.getAttribute('data-act');
                    if (act === 'open') {
                        window.location.href = '/bot/' + encodeURIComponent(name) + '/';
                        return;
                    }
                    if (act === 'config') {
                        openBotDialog(bot, refresh);
                        return;
                    }
                    if (act === 'delete') {
                        if (!window.confirm('删除 bot「' + name + '」？该目录下的配置和日志会一起删掉，不可恢复。')) return;
                    }
                    btn.disabled = true;
                    api({ action: act, name: name }).then(function (res) {
                        btn.disabled = false;
                        if (!res.ok) return toast(res.error || '操作失败', true);
                        if (act === 'start') toast('已启动 ' + name);
                        if (act === 'stop') toast('已停止 ' + name);
                        if (act === 'delete') toast('已删除 ' + name);
                        refresh();
                    });
                });
            });
        });
    }

    // ══════════════════════════════════════════════ 形态 2：注入切换器

    /**
     * 原 WebUI 的 ``#currentUsername`` 直接显示 config.json 的 authentication.username，
     * 没登录时是占位值 "Unknown"。这里把它换成「未登录」，并挂一个提示，
     * 免得用户以为是 bug。
     */
    function patchAccountLabel() {
        var tries = 0;
        (function attempt() {
            var el = document.getElementById('currentUsername');
            if (!el) {
                if (tries++ < 40) setTimeout(attempt, 250);
                return;
            }
            var fix = function () {
                var t = (el.textContent || '').trim();
                if (t === 'Unknown' || t === '-') {
                    el.textContent = '未登录';
                    el.classList.add('mgr-acc', 'warn');
                    el.title = '还没完成登录。点上方「连接」，按终端提示在 microsoft.com/link 输码即可。';
                    if (!el._mgrClick) {
                        el._mgrClick = true;
                        el.style.cursor = 'pointer';
                        el.addEventListener('click', function () {
                            toast('点上方「连接」完成登录，登录后这里会显示你的游戏 ID');
                        });
                    }
                }
            };
            fix();
            if (window.MutationObserver) {
                new MutationObserver(fix).observe(el, { childList: true, characterData: true, subtree: true });
            } else {
                setInterval(fix, 2000);
            }
        })();
    }

    function initInjector() {
        patchAccountLabel();

        var sidebar = document.querySelector('.sidebar');
        if (!sidebar) return;

        var box = document.createElement('div');
        box.className = 'mgr-bots';
        box.innerHTML = '<div class="mgr-bots-label">Bots</div>';
        var navMenu = sidebar.querySelector('.nav-menu');
        if (navMenu) {
            sidebar.insertBefore(box, navMenu);
        } else {
            sidebar.appendChild(box);
        }

        var listBox = document.createElement('div');
        listBox.className = 'mgr-bots-list';
        box.appendChild(listBox);

        function render(bots) {
            listBox.innerHTML = bots.map(function (b) {
                var active = b.name === currentBot;
                return '<a class="mgr-bot' + (active ? ' active' : '') + '"' +
                    ' href="/bot/' + encodeURIComponent(b.name) + '/"' +
                    ' data-tip="' + esc(b.name) + '">' +
                    '<span class="mgr-bot-avatar' + (b.reachable ? ' run' : '') + '">' + esc(initial(b.name)) + '</span>' +
                    '<span class="mgr-bot-name">' + esc(b.name) + '</span>' +
                    '</a>';
            }).join('') +
                '<a class="mgr-bot" href="/" data-tip="总览" style="margin-top:4px">' +
                '<span class="mgr-bot-avatar" style="font-size:14px">⌂</span>' +
                '<span class="mgr-bot-name">总览</span></a>';
        }

        function tick() {
            api().then(function (res) {
                if (res && res.bots) render(res.bots);
            }).catch(function () { });
        }

        tick();
        pollTimer = setInterval(tick, 4000);
    }

    // ────────────────────────────────────────────── 启动

    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', function () {
            isBotPage ? initInjector() : initOverview();
        });
    } else {
        isBotPage ? initInjector() : initOverview();
    }

    window.addEventListener('beforeunload', function () {
        if (pollTimer) clearInterval(pollTimer);
    });
})();
