/**
 * 参考实现 前端共享工具层（所有页面通过 <script src="js/common.js"></script> 引入）。
 *
 * 暴露全局对象 window.DA，提供：
 *   - token 管理（localStorage 持久化）
 *   - 统一 API 请求封装（自动带 satoken header，401 自动跳登录页）
 *   - SSE 流式请求封装（POST + fetch ReadableStream）
 *   - 当前用户管理（缓存 + 刷新）
 *   - 鉴权守卫（requireAuth / requireAdmin）
 *   - 主题切换 / Toast / 顶栏渲染
 *
 * 设计原则：与具体页面解耦，不依赖 Vue，纯原生 JS。
 */
(function () {
    'use strict';

    // ==================== 常量 ====================
    const TOKEN_KEY = 'da-token';
    const USER_KEY = 'da-user';
    const THEME_KEY = 'da-theme';
    const BACKEND_URL = window.location.origin;

    // ==================== token 管理 ====================
    function getToken() {
        try { return localStorage.getItem(TOKEN_KEY) || ''; } catch (e) { return ''; }
    }

    function setToken(token) {
        try { localStorage.setItem(TOKEN_KEY, token || ''); } catch (e) { /* ignore */ }
    }

    function clearToken() {
        try {
            localStorage.removeItem(TOKEN_KEY);
            localStorage.removeItem(USER_KEY);
        } catch (e) { /* ignore */ }
    }

    // ==================== 当前用户管理 ====================
    /**
     * 拉取并缓存当前登录用户完整信息（含角色/部门/dataScope）。
     * 失败（401）返回 null，调用方可据 requireAuth 决定是否跳转。
     */
    async function fetchCurrentUser() {
        const token = getToken();
        if (!token) {
            return null;
        }
        try {
            const resp = await fetch(BACKEND_URL + '/auth/me', {
                method: 'GET',
                headers: { 'satoken': token }
            });
            if (resp.status === 401) {
                clearToken();
                return null;
            }
            if (!resp.ok) {
                return null;
            }
            const json = await resp.json();
            if (json && json.code === 200 && json.data) {
                cacheUser(json.data);
                return json.data;
            }
            return null;
        } catch (e) {
            console.error('[DA] fetchCurrentUser 失败', e);
            return null;
        }
    }

    function cacheUser(user) {
        try { localStorage.setItem(USER_KEY, JSON.stringify(user || {})); } catch (e) { /* ignore */ }
    }

    function getCachedUser() {
        try { return JSON.parse(localStorage.getItem(USER_KEY) || 'null'); } catch (e) { return null; }
    }

    function isAdmin(user) {
        // 纯登录模型：无角色区分，所有登录用户可见全部功能
        const u = user || getCachedUser();
        return !!u;
    }

    /**
     * 展示名：优先真实姓名，昵称不同则拼接（真实姓名（昵称））。
     */
    function userDisplayName(user) {
        const u = user || {};
        const nick = u.nickname || u.username;
        if (u.realName && nick && nick !== u.realName) return `${u.realName}（${nick}）`;
        return u.realName || nick || '用户';
    }

    // ==================== 鉴权守卫 ====================
    /**
     * 检查登录状态，未登录则跳转欢迎页（welcome 承载登录入口）。
     * @returns 当前用户（已登录）或 null（已跳转）
     */
    async function requireAuth() {
        let user = getCachedUser();
        if (!user) {
            user = await fetchCurrentUser();
        }
        if (!user) {
            redirectToLogin();
            return null;
        }
        return user;
    }

    /**
     * 检查 admin 角色，非 admin 跳回主页。
     * @returns 当前用户（admin）或 null（已跳转）
     */
    async function requireAdmin() {
        const user = await requireAuth();
        if (!user) return null;
        if (!isAdmin(user)) {
            window.location.href = 'index.html';
            return null;
        }
        return user;
    }

    function redirectToLogin() {
        const current = window.location.pathname.split('/').pop() || 'index.html';
        if (current !== 'login.html' && current !== 'welcome.html') {
            window.location.href = 'welcome.html';
        }
    }

    async function logout() {
        try {
            await apiPost('/auth/logout', {});
        } catch (e) { /* 忽略，无论如何都跳转 */ }
        clearToken();
        window.location.href = 'login.html';
    }

    // ==================== 统一请求封装 ====================
    function buildHeaders(extra) {
        const token = getToken();
        const headers = Object.assign({ 'Content-Type': 'application/json' }, extra || {});
        if (token) headers['satoken'] = token;
        return headers;
    }

    function handleResponse401() {
        clearToken();
        redirectToLogin();
    }

    async function apiGet(url, params) {
        const qs = params ? '?' + new URLSearchParams(params).toString() : '';
        const resp = await fetch(BACKEND_URL + url + qs, {
            method: 'GET',
            headers: buildHeaders()
        });
        return await parseResponse(resp);
    }

    async function apiPost(url, data) {
        const resp = await fetch(BACKEND_URL + url, {
            method: 'POST',
            headers: buildHeaders(),
            body: JSON.stringify(data || {})
        });
        return await parseResponse(resp);
    }

    async function apiPut(url, data) {
        const resp = await fetch(BACKEND_URL + url, {
            method: 'PUT',
            headers: buildHeaders(),
            body: JSON.stringify(data || {})
        });
        return await parseResponse(resp);
    }

    async function apiDelete(url) {
        const resp = await fetch(BACKEND_URL + url, {
            method: 'DELETE',
            headers: buildHeaders()
        });
        return await parseResponse(resp);
    }

    /**
     * 文件上传（multipart/form-data）。
     * 注意：不要手动设置 Content-Type，浏览器会自动加上 boundary。
     */
    async function apiUpload(url, formData) {
        const token = getToken();
        const headers = {};
        if (token) headers['satoken'] = token;
        const resp = await fetch(BACKEND_URL + url, {
            method: 'POST',
            headers: headers,
            body: formData
        });
        return await parseResponse(resp);
    }

    async function parseResponse(resp) {
        if (resp.status === 401) {
            handleResponse401();
            throw new Error('未登录或登录已过期');
        }
        const text = await resp.text();
        let json;
        try { json = text ? JSON.parse(text) : {}; } catch (e) {
            throw new Error('响应解析失败：' + text.slice(0, 200));
        }
        if (resp.status === 403) {
            const msg = (json && json.msg) || '无权限访问';
            throw new Error(msg);
        }
        if (!resp.ok) {
            const msg = (json && json.msg) || ('HTTP ' + resp.status);
            throw new Error(msg);
        }
        return json;
    }

    /**
     * SSE 流式请求（POST + fetch ReadableStream）。
     *
     * Spring MVC 返回的 SSE 格式：每个 event 以 \n\n 分隔，data 行以 "data:" 前缀。
     *
     * @param url 后端接口路径（如 /agent/stream）
     * @param body 请求体（会被 JSON.stringify）
     * @param handlers 回调对象：
     *        { onEvent(eventData, eventName), onError(err), onClose() }
     * @returns AbortController（用于中止请求）
     */
    function apiStream(url, body, handlers) {
        const controller = new AbortController();
        const token = getToken();
        const headers = { 'Content-Type': 'application/json' };
        if (token) headers['satoken'] = token;

        fetch(BACKEND_URL + url, {
            method: 'POST',
            headers: headers,
            body: JSON.stringify(body || {}),
            signal: controller.signal
        }).then(async (resp) => {
            if (resp.status === 401) {
                handleResponse401();
                if (handlers.onError) handlers.onError(new Error('未登录或登录已过期'));
                return;
            }
            if (!resp.ok || !resp.body) {
                const txt = await resp.text().catch(() => '');
                // 优先取后端 JSON 的 message/msg 字段（如"尚未配置模型"提示）
                let msg = '';
                try {
                    const j = JSON.parse(txt);
                    msg = j.message || j.msg || '';
                } catch (e) { /* 非 JSON 原文 */ }
                if (!msg) msg = txt.slice(0, 200);
                if (handlers.onError) handlers.onError(new Error(msg || ('HTTP ' + resp.status)));
                return;
            }

            const reader = resp.body.getReader();
            const decoder = new TextDecoder();
            let buffer = '';

            try {
                while (true) {
                    const { done, value } = await reader.read();
                    if (done) break;
                    buffer += decoder.decode(value, { stream: true });
                    // 按 SSE 标准的 \n\n 切分 event
                    let idx;
                    while ((idx = buffer.indexOf('\n\n')) >= 0) {
                        const eventStr = buffer.slice(0, idx);
                        buffer = buffer.slice(idx + 2);
                        const parsed = parseSseEvent(eventStr);
                        if (parsed && handlers.onEvent) {
                            handlers.onEvent(parsed.data, parsed.event, parsed.id);
                        }
                    }
                }
                if (handlers.onClose) handlers.onClose();
            } catch (e) {
                if (e && e.name === 'AbortError') {
                    if (handlers.onClose) handlers.onClose();
                } else if (handlers.onError) {
                    handlers.onError(e);
                }
            }
        }).catch((e) => {
            if (e && e.name === 'AbortError') {
                if (handlers.onClose) handlers.onClose();
            } else if (handlers.onError) {
                handlers.onError(e);
            }
        });

        return controller;
    }

    /**
     * 解析单个 SSE event 块（\n\n 之间的内容）。
     * 识别 id:（事件序号，断点续传的游标）、event:、data: 三类行，其余行忽略。
     */
    function parseSseEvent(eventStr) {
        const lines = eventStr.split('\n');
        let eventName = 'message';
        let data = '';
        let eventId = '';
        for (const line of lines) {
            if (line.startsWith('event:')) {
                eventName = line.slice(6).trim();
            } else if (line.startsWith('id:')) {
                eventId = line.slice(3).trim();
            } else if (line.startsWith('data:')) {
                data += line.slice(5).trim();
            }
        }
        return { event: eventName, data, id: eventId };
    }

    // ==================== UI 工具 ====================
    function getTheme() {
        try { return localStorage.getItem(THEME_KEY) === 'dark' ? 'dark' : 'light'; } catch (e) { return 'light'; }
    }

    function applyTheme(t) {
        document.documentElement.setAttribute('data-theme', t);
        const lightLink = document.getElementById('hljs-light');
        const darkLink = document.getElementById('hljs-dark');
        if (lightLink) lightLink.disabled = (t === 'dark');
        if (darkLink) darkLink.disabled = (t !== 'dark');
        try { localStorage.setItem(THEME_KEY, t); } catch (e) { /* ignore */ }
    }

    function toggleTheme() {
        const next = getTheme() === 'light' ? 'dark' : 'light';
        applyTheme(next);
        return next;
    }

    /**
     * 全局 Toast 提示（操作 DOM，不依赖 Vue）。
     */
    /**
     * 全局统一 Toast（agentx 风格：类型着色 + 图标前缀）。
     * 兼容两种旧签名：showToast(msg, duration) 与 showToast(msg, 'error')。
     */
    function showToast(msg, typeOrDuration, duration) {
        if (!msg) return;
        let type = 'success';
        let ms = 2500;
        if (typeof typeOrDuration === 'string') {
            type = typeOrDuration;
            if (typeof duration === 'number') ms = duration;
        } else if (typeof typeOrDuration === 'number') {
            ms = typeOrDuration;
        }
        if (type === 'error') ms = Math.max(ms, 4500);

        let container = document.getElementById('da-toast-container');
        if (!container) {
            container = document.createElement('div');
            container.id = 'da-toast-container';
            container.className = 'toast-container';
            document.body.appendChild(container);
        }
        const el = document.createElement('div');
        el.className = 'toast toast-' + type;
        el.textContent = msg;
        container.appendChild(el);
        requestAnimationFrame(() => el.classList.add('show'));
        setTimeout(() => {
            el.classList.remove('show');
            setTimeout(() => el.remove(), 300);
        }, ms);
    }

    /**
     * 复制文本到剪贴板（通用，所有复制按钮共用）。
     * 优先走 Clipboard API（HTTPS / localhost 安全上下文）；在 HTTP + IP 等非安全上下文下
     * navigator.clipboard 不可用，自动降级为 document.execCommand('copy')，保证任意环境可复制。
     */
    function copyText(text) {
        if (text == null || text === '') {
            return Promise.reject(new Error('empty text'));
        }
        if (navigator.clipboard && window.isSecureContext) {
            return navigator.clipboard.writeText(text);
        }
        return new Promise((resolve, reject) => {
            const ta = document.createElement('textarea');
            ta.value = text;
            ta.setAttribute('readonly', '');
            ta.style.position = 'fixed';
            ta.style.left = '-9999px';
            ta.style.top = '0';
            document.body.appendChild(ta);
            ta.focus();
            ta.select();
            ta.setSelectionRange(0, text.length);
            let ok = false;
            try {
                ok = document.execCommand('copy');
            } catch (e) {
                // ignore
            }
            document.body.removeChild(ta);
            ok ? resolve() : reject(new Error('execCommand copy failed'));
        });
    }

    /**
     * 渲染全局侧边栏（登录后的所有页面都用）。
     *
     * 支持两种调用：
     *   renderHeader(hostEl, { active: 'xxx' })  → 注入到宿主元素
     *   renderHeader({ active: 'xxx' })          → 返回 HTML 字符串
     *
     * @param {Object} options.active 当前激活菜单 key（chat/observability/model/skills/users）
     */
    function renderHeader(arg1, arg2) {
        const host = arg1 instanceof HTMLElement ? arg1 : null;
        const opts = (host ? arg2 : arg1) || {};
        const user = opts.user || getCachedUser() || {};
        const displayName = userDisplayName(user);
        const initial = (displayName || '?').charAt(0).toUpperCase();

        const items = [
            { key: 'dashboard', href: 'dashboard.html', icon: 'fa-gauge-high', text: '仪表盘' },
            { key: 'observability', href: 'observability.html', icon: 'fa-chart-line', text: '调用追踪' },
            { key: 'model', href: 'model-mgmt.html', icon: 'fa-microchip', text: '模型配置' },
            { key: 'skills', href: 'skills.html', icon: 'fa-bolt', text: 'Skills' },
            { key: 'workspace', href: 'workspace.html', icon: 'fa-folder-tree', text: '工作区' }
        ];

        const navHtml = items.map(item => `
            <a href="${item.href}" class="ax-nav-item ${opts.active === item.key ? 'active' : ''}">
                <i class="fas ${item.icon}"></i><span>${item.text}</span>
            </a>`).join('');

        const html = `
        <aside class="ax-sidebar" id="ax-sidebar">
            <a class="ax-logo" href="dashboard.html" title="回到仪表盘">
                <img class="ax-logo-img" src="images/agentx-logo-mark.webp" alt="AgentX"/>
                <div class="ax-logo-text"><b>AgentX Tracer</b></div>
            </a>
            <button class="ax-new-chat" id="newChatBtn" title="开启新对话">
                <i class="fas fa-plus"></i><span>新对话</span>
            </button>
            <nav class="ax-nav">${navHtml}</nav>
            <div class="ax-conv-slot" id="ax-conv-slot">
                <div class="ax-conv-head"><span><i class="fas fa-clock-rotate-left"></i> 历史会话</span><span class="ax-conv-count" id="convCount"></span></div>
                <div class="conv-list" id="convList"></div>
            </div>
            <div class="ax-side-foot">
                <div class="ax-user-card" id="ax-user-card" title="查看个人信息与设置">
                    <div class="ax-avatar">${escapeHtml(initial)}</div>
                    <div class="ax-user-meta">
                        <b>${escapeHtml(displayName)}</b>
                        <span>@${escapeHtml(user.username || '')}</span>
                    </div>
                </div>
            </div>
        </aside>`;

        document.body.classList.add('ax-shell', 'ax-sidebar-on');
        if (host) {
            host.innerHTML = html;
        }
        return html;
    }

    /**
     * 绑定侧边栏交互（用户卡、三点菜单、历史会话）。在 renderHeader 后调用。
     */
    function bindHeaderEvents() {
        // 新对话兜底：聊天页由 agent-chat.js 接管（局部新建），其余页面跳转进入新会话
        const newChatBtn = document.getElementById('newChatBtn');
        if (newChatBtn) {
            newChatBtn.addEventListener('click', () => {
                if (window.__AGENT_CHAT__) return;
                location.href = 'index.html?new=1';
            });
        }
        // 用户卡 → 个人中心（tab 化弹层）
        const userCard = document.getElementById('ax-user-card');
        if (userCard) {
            userCard.addEventListener('click', () => openProfileCenter());
        }

        // 历史会话列表（所有页面固定显示）+ 交互委托
        bindSidebarConversationEvents();
        loadSidebarConversations();
    }

    const AX_CONV_CACHE_KEY = 'ax-sidebar-convs';

    /**
     * 渲染侧栏会话项 HTML（对齐 参考实现 conv-item 结构）。
     */
    function convItemHtml(c, currentCid) {
        const active = currentCid && c.conversationId === currentCid ? ' active' : '';
        const title = escapeHtml(c.title || '新对话');
        const live = c.streaming ? '<span class="conv-live"><i class="fas fa-spinner fa-spin"></i> 生成中</span>' : '';
        return `<div class="conv-item${active}" data-cid="${c.conversationId}">
            <div class="conv-title">${title}</div>
            <div class="conv-meta">
                ${live}
                <span>${c.messageCount ?? 0} 轮</span>
                <span>·</span>
                <span>${formatConvTime(c.lastActiveAt)}</span>
            </div>
            <button class="conv-delete" title="删除会话" data-del="${c.conversationId}">
                <i class="fas fa-trash"></i>
            </button>
        </div>`;
    }

    function formatConvTime(isoStr) {
        if (!isoStr) return '';
        try {
            const d = new Date(isoStr);
            const diffMin = Math.floor((Date.now() - d.getTime()) / 60000);
            if (diffMin < 1) return '刚刚';
            if (diffMin < 60) return diffMin + ' 分钟前';
            if (diffMin < 1440) return Math.floor(diffMin / 60) + ' 小时前';
            if (diffMin < 10080) return Math.floor(diffMin / 1440) + ' 天前';
            return d.toLocaleDateString('zh-CN', { month: 'short', day: 'numeric' });
        } catch (e) { return ''; }
    }

    function renderSidebarConversations(conversations, total) {
        const listEl = document.getElementById('convList');
        const countEl = document.getElementById('convCount');
        if (!listEl) return;
        if (countEl) countEl.textContent = total != null ? String(total) : '';
        if (!conversations || conversations.length === 0) {
            listEl.innerHTML = '<div class="conv-empty">还没有会话，点「新对话」开始</div>';
            return;
        }
        const current = new URLSearchParams(location.search).get('conversationId');
        listEl.innerHTML = conversations.map(c => convItemHtml(c, current)).join('');
    }

    /**
     * 加载侧栏历史会话列表：localStorage 缓存秒渲染 → 异步拉最新刷新并写缓存。
     * scrollToLoad=true 时为滚动加载更多（追加不覆盖）。
     */
    async function loadSidebarConversations(scrollToLoad) {
        const listEl = document.getElementById('convList');
        if (!listEl) return;
        // 聊天页由 agent-chat.js 接管会话列表（局部加载/点击/删除），这里不再重复拉取，避免双请求与闪烁
        if (window.__AGENT_CHAT__) return;
        if (!scrollToLoad) {
            try {
                const cached = JSON.parse(localStorage.getItem(AX_CONV_CACHE_KEY) || 'null');
                if (cached && Array.isArray(cached.conversations)) {
                    renderSidebarConversations(cached.conversations, cached.total);
                }
            } catch (e) { /* 缓存损坏忽略 */ }
        }
        try {
            const page = scrollToLoad ? Math.ceil(listEl.querySelectorAll('.conv-item').length / 10) : 0;
            const res = await apiGet('/api/sessions', { page, size: 10 });
            const data = res.data || {};
            const conversations = data.conversations || [];
            if (scrollToLoad) {
                if (conversations.length > 0) {
                    const current = new URLSearchParams(location.search).get('conversationId');
                    listEl.insertAdjacentHTML('beforeend', conversations.map(c => convItemHtml(c, current)).join(''));
                }
            } else {
                renderSidebarConversations(conversations, data.total);
                try {
                    localStorage.setItem(AX_CONV_CACHE_KEY,
                        JSON.stringify({ conversations, total: data.total, ts: Date.now() }));
                } catch (e) { /* 存储满忽略 */ }
            }
        } catch (e) {
            if (!scrollToLoad && !localStorage.getItem(AX_CONV_CACHE_KEY)) {
                listEl.innerHTML = '<div class="conv-empty">会话加载失败</div>';
            }
        }
    }

    /**
     * 发送瞬间刷新侧栏（对齐 agentx syncConversationEntry）：
     * 先刷列表（服务端开局即落库，通常立刻就有）；仍没有则本地乐观补一条"生成中"。
     */
    async function upsertSidebarConversation(cid, title) {
        const listEl = document.getElementById('convList');
        if (!listEl || !cid) return;
        await loadSidebarConversations();
        if (Array.from(listEl.querySelectorAll('.conv-item')).some(el => el.dataset.cid === cid)) return;
        const t = title && title.length > 40 ? title.slice(0, 40) + '...' : (title || '新对话');
        listEl.insertAdjacentHTML('afterbegin',
            `<div class="conv-item active" data-cid="${cid}">
                <div class="conv-title">${escapeHtml(t)}</div>
                <div class="conv-meta">
                    <span class="conv-live"><i class="fas fa-spinner fa-spin"></i> 生成中</span>
                    <span>1 轮</span><span>·</span><span>刚刚</span>
                </div>
                <button class="conv-delete" title="删除会话" data-del="${cid}">
                    <i class="fas fa-trash"></i>
                </button>
            </div>`);
        const empty = listEl.querySelector('.conv-empty');
        if (empty) empty.remove();
    }

    /**
     * 侧栏会话交互（事件委托）：点击切换 / 删除 / 滚动加载更多。bindHeaderEvents 时绑定一次。
     */
    function bindSidebarConversationEvents() {
        const listEl = document.getElementById('convList');
        if (!listEl || listEl.dataset.bound) return;
        listEl.dataset.bound = '1';
        listEl.addEventListener('click', async (e) => {
            if (window.__AGENT_CHAT__) return;   // 聊天页由 agent-chat.js 接管（局部加载/删除）
            const delBtn = e.target.closest('.conv-delete');
            if (delBtn) {
                e.preventDefault();
                e.stopPropagation();
                const cid = delBtn.dataset.del;
                if (!confirm('确认删除该会话？')) return;
                try {
                    await apiDelete('/api/sessions/' + encodeURIComponent(cid));
                    const item = listEl.querySelector(`.conv-item[data-cid="${cid}"]`);
                    if (item) item.remove();
                    // 删的是当前会话：回聊天页新建
                    const current = new URLSearchParams(location.search).get('conversationId');
                    if (current && current === cid && !location.pathname.endsWith('index.html')) {
                        location.href = 'index.html?new=1';
                    }
                    showToast('会话已删除');
                    loadSidebarConversations();
                } catch (err) {
                    showToast(err.message || '删除失败', 'error');
                }
                return;
            }
            const item = e.target.closest('.conv-item');
            if (item && item.dataset.cid) {
                if (item.dataset.cid === new URLSearchParams(location.search).get('conversationId')) return;
                location.href = 'index.html?conversationId=' + encodeURIComponent(item.dataset.cid);
            }
        });
        listEl.addEventListener('scroll', () => {
            if (window.__AGENT_CHAT__) return;   // 聊天页由 agent-chat.js 分页加载
            if (listEl.scrollTop + listEl.clientHeight >= listEl.scrollHeight - 40) {
                loadSidebarConversations(true);
            }
        });
    }

    /**
     * 个人中心弹层：个人信息（可编辑）/ 密码设置 / 其他设置（Opik 同步）+ 退出登录。
     */
    async function openProfileCenter() {
        const user = getCachedUser() || {};
        const displayName = userDisplayName(user);
        const initial = (displayName || '?').charAt(0).toUpperCase();

        closeProfileCenter();
        const mask = document.createElement('div');
        mask.className = 'ax-profile-mask';
        mask.innerHTML = `
            <div class="ax-profile-card">
                <div class="ax-profile-head">
                    <div class="ax-avatar lg">${escapeHtml(initial)}</div>
                    <div class="ax-profile-title">
                        <b>${escapeHtml(displayName)}</b>
                        <span>@${escapeHtml(user.username || '')}</span>
                    </div>
                    <button class="ax-profile-close" title="关闭"><i class="fas fa-xmark"></i></button>
                </div>
                <div class="ax-profile-tabs">
                    <button class="ax-profile-tab active" data-tab="info">个人信息</button>
                    <button class="ax-profile-tab" data-tab="pwd">密码设置</button>
                    <button class="ax-profile-tab" data-tab="opik">Otel 同步设置</button>
                    <button class="ax-profile-tab" data-tab="sandbox">沙箱设置</button>
                </div>
                <div class="ax-profile-body">
                    <div class="ax-profile-pane active" data-pane="info">
                        <div class="ax-form-field">
                            <label>用户 ID</label>
                            <div class="ax-phone-row">
                                <input class="ax-input" id="ax-prof-userid" value="${escapeHtml(user.id ?? '')}" disabled/>
                                <button type="button" id="ax-userid-copy" class="ax-btn" title="复制用户 ID"><i class="fas fa-copy"></i></button>
                            </div>
                        </div>
                        <div class="ax-form-field">
                            <label>用户名</label>
                            <input class="ax-input" value="${escapeHtml(user.username || '')}" disabled/>
                        </div>
                        <div class="ax-form-row-2">
                            <div class="ax-form-field">
                                <label>昵称</label>
                                <input class="ax-input" id="ax-prof-nickname" value="${escapeHtml(user.nickname || '')}" placeholder="请输入昵称"/>
                            </div>
                            <div class="ax-form-field">
                                <label>真实姓名</label>
                                <input class="ax-input" id="ax-prof-realname" value="${escapeHtml(user.realName || '')}" placeholder="请输入真实姓名"/>
                            </div>
                        </div>
                        <div class="ax-form-field">
                            <label>邮箱</label>
                            <input class="ax-input" id="ax-prof-email" value="${escapeHtml(user.email || '')}" placeholder="请输入邮箱"/>
                        </div>
                        <div class="ax-form-field">
                            <label>手机号</label>
                            <div class="ax-phone-row">
                                <input class="ax-input" id="ax-prof-phone" value="${escapeHtml(user.phone || '')}" disabled/>
                                <button type="button" id="ax-phone-change-btn" class="ax-btn">修改</button>
                            </div>
                        </div>
                        <div class="ax-phone-change" id="ax-phone-change" style="display:none;">
                            <div class="ax-form-field">
                                <label>新手机号</label>
                                <input class="ax-input" id="ax-phone-new" placeholder="请输入新手机号"/>
                            </div>
                            <div class="ax-form-field">
                                <label>验证码</label>
                                <div class="ax-opik-row">
                                    <input class="ax-input" id="ax-phone-code" maxlength="6" placeholder="6 位验证码"/>
                                    <button type="button" id="ax-phone-send-code" class="ax-btn">发送验证码</button>
                                </div>
                            </div>
                        </div>
                        <button id="ax-prof-save" class="ax-btn primary">保存资料</button>
                    </div>
                    <div class="ax-profile-pane" data-pane="pwd">
                        <div class="ax-form-field">
                            <label>旧密码</label>
                            <div class="ax-pwd-wrap">
                                <input type="password" id="ax-pwd-old" class="ax-input" autocomplete="current-password" placeholder="请输入旧密码"/>
                                <button type="button" class="ax-pwd-eye" data-target="ax-pwd-old"><i class="fas fa-eye"></i></button>
                            </div>
                        </div>
                        <div class="ax-form-field">
                            <label>新密码</label>
                            <div class="ax-pwd-wrap">
                                <input type="password" id="ax-pwd-new" class="ax-input" autocomplete="new-password" placeholder="请输入新密码"/>
                                <button type="button" class="ax-pwd-eye" data-target="ax-pwd-new"><i class="fas fa-eye"></i></button>
                            </div>
                        </div>
                        <div class="ax-form-field">
                            <label>确认新密码</label>
                            <div class="ax-pwd-wrap">
                                <input type="password" id="ax-pwd-confirm" class="ax-input" autocomplete="new-password" placeholder="请再次输入新密码"/>
                                <button type="button" class="ax-pwd-eye" data-target="ax-pwd-confirm"><i class="fas fa-eye"></i></button>
                            </div>
                        </div>
                        <button id="ax-pwd-save" class="ax-btn primary">保存密码</button>
                    </div>
                    <div class="ax-profile-pane" data-pane="opik">
                        <div class="ax-form-field">
                            <label>OTel 地址</label>
                            <input class="ax-input" id="ax-opik-endpoint" disabled/>
                        </div>
                        <div class="ax-form-field">
                            <label>Comet-Workspace</label>
                            <input class="ax-input" id="ax-opik-workspace" disabled/>
                        </div>
                        <div class="ax-form-field">
                            <label>Project Name</label>
                            <input class="ax-input" id="ax-opik-project" disabled/>
                        </div>
                        <label class="ax-opik-toggle">
                            <input type="checkbox" id="ax-opik-enabled" disabled/>
                            <span>同步到 Opik（需先通过连通性测试）</span>
                        </label>
                        <div class="ax-opik-actions">
                            <button type="button" id="ax-opik-test" class="ax-btn">连通性测试</button>
                            <button id="ax-opik-save" class="ax-btn primary">保存设置</button>
                        </div>
                    </div>
                    <div class="ax-profile-pane" data-pane="sandbox">
                        <label class="ax-opik-toggle">
                            <input type="checkbox" id="ax-sandbox-enabled"/>
                            <span>启用沙箱执行</span>
                        </label>
                        <p class="ax-sandbox-tip">开启后，工具（bash / 文件 / 代码搜索等）会在隔离的沙箱容器内执行，与宿主环境隔离；关闭则退化为宿主执行。</p>
                    </div>
                </div>
                <div class="ax-profile-foot">
                    <button id="ax-profile-logout" class="ax-btn danger"><i class="fas fa-right-from-bracket"></i> 退出登录</button>
                </div>
            </div>`;
        // 仅点击遮罩空白处关闭：用 mousedown 判定按下起点，避免在输入框拖选文字时松手到遮罩被误判关闭
        mask.addEventListener('mousedown', (e) => {
            if (e.target === mask) closeProfileCenter();
        });
        mask.addEventListener('click', (e) => {
            if (e.target.closest('.ax-profile-close')) closeProfileCenter();
        });
        document.body.appendChild(mask);
        document.addEventListener('keydown', profileCenterEsc);

        // Tab 切换
        mask.querySelectorAll('.ax-profile-tab').forEach(tab => {
            tab.addEventListener('click', () => {
                mask.querySelectorAll('.ax-profile-tab').forEach(t => t.classList.remove('active'));
                mask.querySelectorAll('.ax-profile-pane').forEach(p => p.classList.remove('active'));
                tab.classList.add('active');
                const pane = mask.querySelector(`.ax-profile-pane[data-pane="${tab.dataset.tab}"]`);
                if (pane) pane.classList.add('active');
            });
        });

        // 退出登录
        mask.querySelector('#ax-profile-logout').addEventListener('click', () => logout());

        // 复制用户 ID（用于在 Opik 按 opik.metadata.userId 筛选自己的链路）
        mask.querySelector('#ax-userid-copy').addEventListener('click', () => {
            const uid = mask.querySelector('#ax-prof-userid').value.trim();
            if (!uid) return;
            DA.copyText(uid).then(
                () => DA.showToast('用户 ID 已复制', 'success'),
                () => DA.showToast('复制失败', 'error')
            );
        });

        // 保存资料（昵称/邮箱；若填写了新手机号+验证码，则一并换绑手机号）
        mask.querySelector('#ax-prof-save').addEventListener('click', async () => {
            const newPhone = mask.querySelector('#ax-phone-new').value.trim();
            const code = mask.querySelector('#ax-phone-code').value.trim();
            try {
                if (newPhone || code) {
                    if (!newPhone || !code) {
                        DA.showToast('请完整填写新手机号和验证码', 'error');
                        return;
                    }
                    await apiPost('/auth/change-phone', { newPhone, code });
                }
                const res = await apiPost('/auth/profile', {
                    nickname: mask.querySelector('#ax-prof-nickname').value.trim(),
                    realName: mask.querySelector('#ax-prof-realname').value.trim(),
                    email: mask.querySelector('#ax-prof-email').value.trim()
                });
                const updated = res.data || {};
                cacheUser(updated);
                refreshSidebarUser(updated);
                const dn = userDisplayName(updated);
                const headB = mask.querySelector('.ax-profile-title b');
                if (headB) headB.textContent = dn;
                const headSpan = mask.querySelector('.ax-profile-title span');
                if (headSpan) headSpan.textContent = '@' + (updated.username || '');
                const headAv = mask.querySelector('.ax-profile-head .ax-avatar');
                if (headAv) headAv.textContent = (dn || '?').charAt(0).toUpperCase();
                const phoneInput = mask.querySelector('#ax-prof-phone');
                if (phoneInput) phoneInput.value = updated.phone || '';
                mask.querySelector('#ax-phone-new').value = '';
                mask.querySelector('#ax-phone-code').value = '';
                mask.querySelector('#ax-phone-change').style.display = 'none';
                DA.showToast(newPhone ? '资料与手机号已保存' : '资料已保存', 'success');
            } catch (e) {
                DA.showToast(e.message || '保存失败', 'error');
            }
        });

        // 修改手机号：展开换绑流程
        mask.querySelector('#ax-phone-change-btn').addEventListener('click', () => {
            const box = mask.querySelector('#ax-phone-change');
            box.style.display = box.style.display === 'none' ? 'block' : 'none';
        });

        // 发送验证码（换绑到新手机号）
        mask.querySelector('#ax-phone-send-code').addEventListener('click', async () => {
            const newPhone = mask.querySelector('#ax-phone-new').value.trim();
            if (!/^1\d{10}$/.test(newPhone)) {
                DA.showToast('请输入正确的手机号', 'error');
                return;
            }
            const btn = mask.querySelector('#ax-phone-send-code');
            btn.disabled = true;
            try {
                await apiPost('/auth/sms/send-code', { phone: newPhone });
                DA.showToast('验证码已发送，请查收短信', 'success');
            } catch (e) {
                DA.showToast(e.message || '发送失败', 'error');
                btn.disabled = false;
            }
        });

        // 密码可见性切换（小眼睛）
        mask.querySelectorAll('.ax-pwd-eye').forEach(btn => {
            btn.addEventListener('click', () => {
                const input = mask.querySelector('#' + btn.dataset.target);
                if (!input) return;
                const show = input.type === 'password';
                input.type = show ? 'text' : 'password';
                btn.querySelector('i').className = show ? 'fas fa-eye-slash' : 'fas fa-eye';
            });
        });

        // 修改密码
        mask.querySelector('#ax-pwd-save').addEventListener('click', async () => {
            const oldPwd = mask.querySelector('#ax-pwd-old').value.trim();
            const newPwd = mask.querySelector('#ax-pwd-new').value.trim();
            const confirmPwd = mask.querySelector('#ax-pwd-confirm').value.trim();
            if (!oldPwd || !newPwd || !confirmPwd) {
                DA.showToast('请完整填写旧密码、新密码和确认密码', 'error');
                return;
            }
            if (newPwd !== confirmPwd) {
                DA.showToast('两次输入的新密码不一致', 'error');
                return;
            }
            try {
                await apiPost('/auth/change-password', { oldPassword: oldPwd, newPassword: newPwd });
                DA.showToast('密码修改成功，请重新登录', 'success');
                setTimeout(() => logout(), 800);
            } catch (e) {
                DA.showToast(e.message || '修改失败', 'error');
            }
        });

        // ===== Opik 同步：先连通性测试，通过后才可开启同步 =====
        let opikTestPassed = false;
        const opikEnabledBox = mask.querySelector('#ax-opik-enabled');
        function setOpikTestPassed(passed) {
            opikTestPassed = passed;
            opikEnabledBox.disabled = !passed;
            if (!passed) opikEnabledBox.checked = false;
        }

        // 加载已有配置（endpoint/workspace/projectName 为全局内置，只读展示）
        try {
            const res = await apiGet('/opik/config');
            const cfg = res.data || {};
            opikEnabledBox.checked = !!cfg.enabled;
            mask.querySelector('#ax-opik-endpoint').value = cfg.endpoint || '';
            mask.querySelector('#ax-opik-workspace').value = cfg.workspace || '';
            mask.querySelector('#ax-opik-project').value = cfg.projectName || '';
            if (cfg.enabled) {
                // 已开启视为已通过测试，保持启用态
                opikEnabledBox.disabled = false;
                opikTestPassed = true;
            }
        } catch (e) { /* 配置加载失败忽略 */ }

        mask.querySelector('#ax-opik-save').addEventListener('click', async () => {
            const enabled = opikEnabledBox.checked;
            if (enabled && !opikTestPassed) {
                DA.showToast('请先通过连通性测试', 'error');
                return;
            }
            try {
                await apiPost('/opik/config', { enabled });
                DA.showToast('设置已保存', 'success');
            } catch (e) {
                DA.showToast(e.message || '保存失败', 'error');
            }
        });

        // 连通性测试（测全局内置端点）
        mask.querySelector('#ax-opik-test').addEventListener('click', async () => {
            const btn = mask.querySelector('#ax-opik-test');
            btn.disabled = true;
            btn.innerHTML = '<i class="fas fa-spinner fa-spin"></i> 测试中';
            try {
                const res = await apiPost('/opik/test', {});
                const r = res.data || {};
                setOpikTestPassed(r.success === true);
                DA.showToast(r.message || (r.success ? '连接成功' : '连接失败'), r.success ? 'success' : 'error');
            } catch (e) {
                setOpikTestPassed(false);
                DA.showToast(e.message || '测试失败', 'error');
            } finally {
                btn.disabled = false;
                btn.textContent = '连通性测试';
            }
        });

        // 加载沙箱开关（默认开）
        try {
            const res = await apiGet('/sandbox/config');
            const cfg = res.data || {};
            mask.querySelector('#ax-sandbox-enabled').checked = cfg.enabled !== false;
        } catch (e) { /* 加载失败忽略 */ }

        // 沙箱开关：切换即保存
        mask.querySelector('#ax-sandbox-enabled').addEventListener('change', async () => {
            const enabled = mask.querySelector('#ax-sandbox-enabled').checked;
            try {
                await apiPost('/sandbox/config', { enabled });
                DA.showToast(enabled ? '已开启沙箱执行' : '已关闭沙箱执行', 'success');
            } catch (e) {
                DA.showToast(e.message || '保存失败', 'error');
            }
        });
    }

    /**
     * 更新侧边栏用户卡（昵称/头像），资料保存后即时刷新。
     */
    function refreshSidebarUser(user) {
        const card = document.getElementById('ax-user-card');
        if (!card) return;
        const dn = userDisplayName(user);
        const b = card.querySelector('.ax-user-meta b');
        if (b) b.textContent = dn;
        const span = card.querySelector('.ax-user-meta span');
        if (span) span.textContent = '@' + (user.username || '');
        const av = card.querySelector('.ax-avatar');
        if (av) av.textContent = (dn || '?').charAt(0).toUpperCase();
    }

    function profileCenterEsc(e) { if (e.key === 'Escape') closeProfileCenter(); }

    function closeProfileCenter() {
        document.removeEventListener('keydown', profileCenterEsc);
        document.querySelectorAll('.ax-profile-mask').forEach(el => el.remove());
    }

    function escapeHtml(s) {
        if (s == null) return '';
        return String(s)
            .replace(/&/g, '&amp;')
            .replace(/</g, '&lt;')
            .replace(/>/g, '&gt;')
            .replace(/"/g, '&quot;')
            .replace(/'/g, '&#39;');
    }

    // ==================== 暴露全局 ====================
    window.DA = {
        // token
        getToken, setToken, clearToken,
        // user
        fetchCurrentUser, getCachedUser, cacheUser, isAdmin,
        requireAuth, requireAdmin, logout,
        // api
        apiGet, apiPost, apiPut, apiDelete, apiUpload, apiStream,
        // ui
        getTheme, applyTheme, toggleTheme, showToast, copyText,
        renderHeader, bindHeaderEvents, openProfileCenter, loadSidebarConversations,
        upsertSidebarConversation, escapeHtml,
        // 常量
        BACKEND_URL
    };

    // 启动时立即应用主题，避免白屏闪烁
    applyTheme(getTheme());
})();
