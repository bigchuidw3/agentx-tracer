/**
 * 工作区文件目录：浏览 / 预览 / 下载 / 搜索 / 批量操作 / 清理当前用户工作区。
 */
(function () {
    const { createApp, ref, reactive, computed, onMounted } = Vue;
    const DA = window.DA;

    createApp({
        setup() {
            const path = ref('');
            const entries = ref([]);
            const loading = ref(false);
            const keyword = ref('');
            const searching = ref(false);
            const selected = ref(new Set());

            const previewing = ref(false);
            const previewName = ref('');
            const previewIsImage = ref(false);
            const previewText = ref('');
            const previewUrl = ref('');

            const confirm = reactive({ show: false, title: '', message: '', confirmText: '', danger: false, _resolve: null });

            const crumbs = computed(() => path.value ? path.value.split('/').filter(Boolean) : []);
            const allSelected = computed(() => {
                const files = entries.value.filter(e => !e.isDir);
                return files.length > 0 && files.every(e => selected.value.has(e.path));
            });
            const selectedCount = computed(() => selected.value.size);
            const selectedPaths = computed(() => Array.from(selected.value));

            function crumbPath(i) {
                return crumbs.value.slice(0, i + 1).join('/');
            }

            function authFetch(url, options) {
                const token = DA.getToken();
                const opts = Object.assign({}, options || {});
                opts.headers = Object.assign({}, opts.headers || {});
                if (token) opts.headers['satoken'] = token;
                return fetch((DA.BACKEND_URL || '') + url, opts);
            }

            function isSelected(e) {
                return selected.value.has(e.path);
            }

            function toggleSelect(e) {
                const s = new Set(selected.value);
                if (s.has(e.path)) s.delete(e.path); else s.add(e.path);
                selected.value = s;
            }

            function toggleAll() {
                if (allSelected.value) {
                    selected.value = new Set();
                } else {
                    selected.value = new Set(entries.value.filter(e => !e.isDir).map(e => e.path));
                }
            }

            function pathsQuery(paths) {
                return paths.map(p => 'paths=' + encodeURIComponent(p)).join('&');
            }

            async function loadList() {
                loading.value = true;
                try {
                    const res = await DA.apiGet('/api/workspace/list', { path: path.value });
                    const d = res.data || {};
                    path.value = d.path || '';
                    entries.value = d.entries || [];
                    selected.value = new Set();
                } catch (e) {
                    DA.showToast(e.message || '加载失败', 'error');
                } finally {
                    loading.value = false;
                }
            }

            function goPath(p) {
                searching.value = false;
                keyword.value = '';
                path.value = p || '';
                loadList();
            }

            function goUp() {
                if (searching.value || !path.value) return;
                const parts = path.value.split('/').filter(Boolean);
                parts.pop();
                goPath(parts.join('/'));
            }

            function reload() {
                if (searching.value) doSearch(); else loadList();
            }

            async function doSearch() {
                const kw = keyword.value.trim();
                if (!kw) return;
                loading.value = true;
                searching.value = true;
                try {
                    const res = await DA.apiGet('/api/workspace/search', { keyword: kw });
                    entries.value = (res.data || []);
                    selected.value = new Set();
                } catch (e) {
                    DA.showToast(e.message || '搜索失败', 'error');
                } finally {
                    loading.value = false;
                }
            }

            function clearSearch() {
                searching.value = false;
                keyword.value = '';
                loadList();
            }

            async function download(e) {
                const resp = await authFetch('/api/workspace/download?path=' + encodeURIComponent(e.path));
                if (!resp.ok) {
                    DA.showToast('下载失败', 'error');
                    return;
                }
                const blob = await resp.blob();
                const url = URL.createObjectURL(blob);
                const a = document.createElement('a');
                a.href = url;
                a.download = e.name;
                document.body.appendChild(a);
                a.click();
                document.body.removeChild(a);
                URL.revokeObjectURL(url);
            }

            async function batchDownload() {
                const paths = selectedPaths.value;
                if (paths.length === 0) return;
                const resp = await authFetch('/api/workspace/download-zip?' + pathsQuery(paths));
                if (!resp.ok) {
                    DA.showToast('下载失败', 'error');
                    return;
                }
                const blob = await resp.blob();
                const url = URL.createObjectURL(blob);
                const a = document.createElement('a');
                a.href = url;
                a.download = 'workspace.zip';
                document.body.appendChild(a);
                a.click();
                document.body.removeChild(a);
                URL.revokeObjectURL(url);
            }

            async function preview(e) {
                const resp = await authFetch('/api/workspace/preview?path=' + encodeURIComponent(e.path));
                if (!resp.ok) {
                    DA.showToast('该文件不支持预览或文件过大', 'error');
                    return;
                }
                const contentType = resp.headers.get('Content-Type') || '';
                if (contentType.startsWith('image/')) {
                    previewUrl.value = URL.createObjectURL(await resp.blob());
                    previewIsImage.value = true;
                    previewText.value = '';
                } else {
                    previewText.value = await resp.text();
                    previewIsImage.value = false;
                    previewUrl.value = '';
                }
                previewName.value = e.name;
                previewing.value = true;
            }

            function closePreview() {
                previewing.value = false;
                if (previewUrl.value) {
                    URL.revokeObjectURL(previewUrl.value);
                    previewUrl.value = '';
                }
            }

            function confirmDialog(opts) {
                return new Promise(resolve => {
                    confirm.title = opts.title || '确认操作';
                    confirm.message = opts.message || '';
                    confirm.confirmText = opts.confirmText || '确定';
                    confirm.danger = !!opts.danger;
                    confirm._resolve = resolve;
                    confirm.show = true;
                });
            }

            function confirmOk() {
                confirm.show = false;
                if (confirm._resolve) confirm._resolve(true);
            }

            function confirmCancel() {
                confirm.show = false;
                if (confirm._resolve) confirm._resolve(false);
            }

            async function removeEntry(e) {
                const ok = await confirmDialog({ title: '删除文件', message: '确认删除「' + e.name + '」？此操作不可恢复。', confirmText: '删除', danger: true });
                if (!ok) return;
                try {
                    await DA.apiDelete('/api/workspace?path=' + encodeURIComponent(e.path));
                    DA.showToast('已删除', 'success');
                    reload();
                } catch (err) {
                    DA.showToast(err.message || '删除失败', 'error');
                }
            }

            async function batchDelete() {
                const paths = selectedPaths.value;
                if (paths.length === 0) return;
                const ok = await confirmDialog({ title: '批量删除', message: '确认删除选中的 ' + paths.length + ' 个文件/目录？此操作不可恢复。', confirmText: '删除', danger: true });
                if (!ok) return;
                try {
                    await DA.apiDelete('/api/workspace/batch?' + pathsQuery(paths));
                    DA.showToast('已删除', 'success');
                    selected.value = new Set();
                    reload();
                } catch (err) {
                    DA.showToast(err.message || '删除失败', 'error');
                }
            }

            async function cleanWorkspace() {
                const ok = await confirmDialog({ title: '清理工作区', message: '此操作将删除工作区内的所有文件，且不可恢复，请谨慎操作。', confirmText: '确认清理', danger: true });
                if (!ok) return;
                try {
                    await DA.apiDelete('/api/workspace/clean');
                    DA.showToast('工作区已清空', 'success');
                    path.value = '';
                    searching.value = false;
                    selected.value = new Set();
                    loadList();
                } catch (err) {
                    DA.showToast(err.message || '清理失败', 'error');
                }
            }

            function fmtSize(n) {
                if (n == null) return '-';
                if (n < 1024) return n + ' B';
                if (n < 1024 * 1024) return (n / 1024).toFixed(1) + ' KB';
                return (n / 1024 / 1024).toFixed(1) + ' MB';
            }

            function fmtTime(iso) {
                if (!iso) return '-';
                const d = new Date(iso);
                const p = v => String(v).padStart(2, '0');
                return p(d.getMonth() + 1) + '-' + p(d.getDate()) + ' ' + p(d.getHours()) + ':' + p(d.getMinutes());
            }

            onMounted(async () => {
                const user = await DA.requireAuth();
                if (!user) return;
                DA.renderHeader(document.getElementById('da-header-host'), { active: 'workspace' });
                DA.bindHeaderEvents();
                loadList();
            });

            return {
                path, entries, loading, keyword, searching, selected, crumbs,
                allSelected, selectedCount, selectedPaths, confirm,
                previewing, previewName, previewIsImage, previewText, previewUrl,
                crumbPath, isSelected, toggleSelect, toggleAll,
                goPath, goUp, reload, doSearch, clearSearch,
                download, batchDownload, preview, closePreview,
                confirmOk, confirmCancel, removeEntry, batchDelete, cleanWorkspace,
                fmtSize, fmtTime
            };
        }
    }).mount('#da-workspace-app');
})();
