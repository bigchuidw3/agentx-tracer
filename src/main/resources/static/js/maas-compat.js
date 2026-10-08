/**
 * maas-compat：为自 参考实现 移植的 agent-chat.js / agent-timeline.js
 * 提供其依赖的 maas-common 全局对象（Api / Auth / Toast / Modal / Markdown / Utils / MaaS / Nav），
 * 底层桥接到本平台 common.js 的 DA（sa-token 认证、R 响应结构）。
 *
 * 必须在 common.js 之后、agent-chat.js 之前加载。
 */
(function () {
    'use strict';
    const DA = window.DA;

    // ==================== Utils ====================
    const Utils = {
        escapeHtml(str) {
            const div = document.createElement('div');
            div.textContent = str == null ? '' : String(str);
            return div.innerHTML;
        },
        formatTime(dateStr) {
            if (!dateStr) return '';
            const d = new Date(dateStr);
            if (Number.isNaN(d.getTime())) return '';
            const diff = Date.now() - d.getTime();
            if (diff < 60000) return '刚刚';
            if (diff < 3600000) return Math.floor(diff / 60000) + ' 分钟前';
            if (diff < 86400000) return Math.floor(diff / 3600000) + ' 小时前';
            if (diff < 604800000) return Math.floor(diff / 86400000) + ' 天前';
            return (d.getMonth() + 1) + '/' + d.getDate();
        },
        generateId() {
            return 'id_' + Date.now() + '_' + Math.random().toString(36).slice(2, 8);
        }
    };

    // ==================== Auth ====================
    const Auth = {
        getToken: () => DA.getToken(),
        clearToken: () => DA.clearToken(),
        clearUser() { try { localStorage.removeItem('da-user'); } catch (e) { /* ignore */ } },
        // 同步登录检查（agent-chat.js 入口用）：本地有 token 即放行，失效由请求层 401 拦截
        requireAuth() {
            if (!DA.getToken()) {
                window.location.href = 'login.html';
                return false;
            }
            return true;
        }
    };

    // ==================== Api（sa-token + R 结构） ====================
    function authHeaders() {
        const h = {};
        const token = DA.getToken();
        if (token) h['satoken'] = token;
        return h;
    }

    async function parseR(resp, mutation) {
        const text = await resp.text().catch(() => '');
        if (resp.status === 401) {
            DA.clearToken();
            window.location.href = 'welcome.html';
            throw new Error('未登录或登录已过期');
        }
        let json = null;
        try { json = text ? JSON.parse(text) : {}; } catch (e) { /* 非 JSON */ }
        if (!resp.ok) {
            throw new Error((json && (json.message || json.msg)) || text.slice(0, 200) || ('HTTP ' + resp.status));
        }
        if (json && (json.code === 200 || json.code === 0)) return json.data;
        throw new Error((json && (json.message || json.msg)) ||
            (mutation ? '操作结果不确定，请刷新列表确认' : '请求失败'));
    }

    const Api = {
        async request(url, options = {}) {
            const headers = { ...authHeaders(), ...(options.headers || {}) };
            if (options.body && !(options.body instanceof FormData) && !headers['Content-Type']) {
                headers['Content-Type'] = 'application/json';
            }
            return fetch(url, { ...options, headers });
        },
        async get(url) { return parseR(await this.request(url), false); },
        async post(url, body) {
            return parseR(await this.request(url, { method: 'POST', body: JSON.stringify(body || {}) }), true);
        },
        async postForm(url, formData) {
            return parseR(await this.request(url, { method: 'POST', body: formData }), true);
        },
        async put(url, body) {
            return parseR(await this.request(url, { method: 'PUT', body: JSON.stringify(body || {}) }), true);
        },
        async delete(url) { return parseR(await this.request(url, { method: 'DELETE' }), true); },
        async del(url) { return parseR(await this.request(url, { method: 'DELETE' }), true); }
    };

    // ==================== Toast（带类型，样式全局统一） ====================
    const Toast = {
        show(msg, type, duration) { DA.showToast(msg, type || 'success', duration); },
        success(msg) { DA.showToast(msg, 'success'); },
        error(msg) { DA.showToast(msg, 'error', 5000); },
        warning(msg) { DA.showToast(msg, 'warning'); },
        info(msg) { DA.showToast(msg, 'info'); }
    };

    // ==================== Modal（自 agentx 移植，样式在 chat.css） ====================
    const Modal = {
        _root: null, _ui: null,
        _ensure() {
            if (this._root) return this._ui;
            const root = document.createElement('div');
            root.className = 'modal-overlay';
            root.innerHTML = `
                <div class="modal" style="min-width:340px;max-width:400px;">
                  <h3 style="display:flex;align-items:center;gap:8px;">
                    <i class="fas fa-circle-question"></i><span class="mc-title"></span>
                  </h3>
                  <div class="mc-message" style="font-size:13px;line-height:1.7;color:var(--text-muted);word-break:break-word;white-space:pre-line;margin:4px 0 4px 26px;"></div>
                  <div class="modal-actions">
                    <button type="button" class="btn btn-outline mc-cancel">取消</button>
                    <button type="button" class="btn btn-primary mc-ok">确定</button>
                  </div>
                </div>`;
            document.body.appendChild(root);
            this._root = root;
            this._ui = {
                root,
                icon: root.querySelector('h3 > i'),
                title: root.querySelector('.mc-title'),
                message: root.querySelector('.mc-message'),
                cancel: root.querySelector('.mc-cancel'),
                ok: root.querySelector('.mc-ok'),
            };
            return this._ui;
        },
        _open(opts, resolve) {
            const ui = this._ensure();
            const o = opts || {};
            const isAlert = o.type === 'alert';
            const danger = !!o.danger;
            ui.title.textContent = o.title || (isAlert ? '提示' : '确认操作');
            ui.message.textContent = o.message || '';
            ui.ok.textContent = o.confirmText || (danger ? '删除' : '确定');
            ui.cancel.style.display = isAlert ? 'none' : '';
            ui.cancel.textContent = o.cancelText || '取消';
            ui.icon.className = danger ? 'fas fa-triangle-exclamation' : (isAlert ? 'fas fa-circle-info' : 'fas fa-circle-question');
            ui.icon.style.color = danger ? 'var(--danger)' : 'var(--primary)';
            ui.ok.style.background = danger ? 'var(--danger)' : '';
            const done = (val) => {
                ui.ok.onclick = ui.cancel.onclick = ui.root.onclick = ui.root.onkeydown = null;
                ui.root.classList.remove('show');
                resolve(val);
            };
            ui.ok.onclick = () => done(true);
            ui.cancel.onclick = () => done(false);
            ui.root.onclick = (e) => { if (e.target === ui.root) done(false); };
            ui.root.onkeydown = (e) => { if (e.key === 'Escape') done(false); };
            ui.root.classList.add('show');
            ui.ok.focus();
        },
        confirm(opts) {
            return new Promise((resolve) => this._open(Object.assign({}, opts, { type: 'confirm' }), resolve));
        },
        alert(opts) {
            return new Promise((resolve) => this._open(Object.assign({}, opts, { type: 'alert' }), resolve));
        }
    };

    // ==================== Markdown（marked + DOMPurify + hljs） ====================
    let _markedReady = false;
    const Markdown = {
        init() {
            if (typeof marked === 'undefined' || _markedReady) return;
            _markedReady = true;
            if (typeof DOMPurify !== 'undefined' && !DOMPurify.__linkHookAdded) {
                DOMPurify.addHook('afterSanitizeAttributes', function (node) {
                    if (node && node.nodeName === 'A') {
                        node.setAttribute('target', '_blank');
                        node.setAttribute('rel', 'noopener noreferrer nofollow');
                    }
                });
                DOMPurify.__linkHookAdded = true;
            }
            marked.setOptions({
                breaks: true,
                highlight: function (code, lang) {
                    if (typeof hljs !== 'undefined' && lang && hljs.getLanguage(lang)) {
                        try { return hljs.highlight(code, { language: lang }).value; } catch (e) { /* ignore */ }
                    }
                    return undefined;
                }
            });
        },
        render(text) {
            if (!text) return '';
            this.init();
            try {
                const html = marked.parse(String(text));
                return typeof DOMPurify !== 'undefined' ? DOMPurify.sanitize(html) : html;
            } catch (e) {
                return Utils.escapeHtml(text);
            }
        }
    };

    // ==================== Nav（空实现：本平台导航由 DA.renderHeader 渲染） ====================
    const Nav = { render() { /* no-op */ } };

    // ==================== 暴露 ====================
    window.Utils = Utils;
    window.Auth = Auth;
    window.Api = Api;
    window.Toast = Toast;
    window.Modal = Modal;
    window.Markdown = Markdown;
    window.MaaS = { Auth, Api, Toast, Modal, Markdown, Utils, Nav };
})();
