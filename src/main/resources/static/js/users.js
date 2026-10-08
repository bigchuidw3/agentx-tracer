/**
 * 用户管理页逻辑（对接 /sys/user 接口，纯用户模型无角色）。
 *
 * 依赖 common.js（window.DA）+ Vue 3 CDN。
 */
(function () {
    const { createApp, ref, onMounted } = Vue;
    const DA = window.DA;

    createApp({
        setup() {
            const users = ref([]);
            const loading = ref(false);
            const page = ref(1);
            const size = 20;
            const total = ref(0);
            const keyword = ref('');

            const formVisible = ref(false);
            const form = ref({});
            const saving = ref(false);

            const resetVisible = ref(false);
            const resetTarget = ref(null);
            const resetPasswordVal = ref('');

            async function load() {
                loading.value = true;
                try {
                    const params = { page: page.value, size };
                    if (keyword.value) params.keyword = keyword.value;
                    const res = await DA.apiGet('/sys/user/list', params);
                    users.value = (res.data && res.data.records) || [];
                    total.value = (res.data && res.data.total) || 0;
                } catch (e) {
                    DA.showToast(e.message || '加载失败', 'error');
                } finally {
                    loading.value = false;
                }
            }

            function reload() { page.value = 1; load(); }

            function openCreate() {
                form.value = { id: null, username: '', password: '', nickname: '', realName: '', email: '', phone: '' };
                formVisible.value = true;
            }

            function openEdit(u) {
                form.value = {
                    id: u.id, username: u.username, password: u.password || '',
                    nickname: u.nickname || '', realName: u.realName || '',
                    email: u.email || '', phone: u.phone || ''
                };
                formVisible.value = true;
            }

            function closeForm() { formVisible.value = false; }

            async function save() {
                const f = form.value;
                if (!f.id && (!f.username || !f.password)) {
                    DA.showToast('新增时用户名与密码必填', 'error');
                    return;
                }
                saving.value = true;
                try {
                    const res = f.id
                        ? await DA.apiPut(`/sys/user/${f.id}`, f)
                        : await DA.apiPost('/sys/user', f);
                    DA.showToast(res.msg || '保存成功');
                    formVisible.value = false;
                    load();
                } catch (e) {
                    DA.showToast(e.message || '保存失败', 'error');
                } finally {
                    saving.value = false;
                }
            }

            async function toggleStatus(u) {
                const next = u.status === 'ACTIVE' ? 'DISABLED' : 'ACTIVE';
                try {
                    await DA.apiPut(`/sys/user/${u.id}/status?status=${next}`, {});
                    u.status = next;
                    DA.showToast(next === 'ACTIVE' ? '已启用' : '已禁用');
                } catch (e) {
                    DA.showToast(e.message || '操作失败', 'error');
                }
            }

            function openReset(u) {
                resetTarget.value = u;
                resetPasswordVal.value = '';
                resetVisible.value = true;
            }

            async function doReset() {
                if (!resetPasswordVal.value) {
                    DA.showToast('请输入新密码', 'error');
                    return;
                }
                try {
                    await DA.apiPost('/sys/user/reset-password', {
                        userId: resetTarget.value.id, newPassword: resetPasswordVal.value
                    });
                    DA.showToast('密码已重置');
                    resetVisible.value = false;
                } catch (e) {
                    DA.showToast(e.message || '重置失败', 'error');
                }
            }

            async function remove(u) {
                if (!confirm(`确定删除用户 @${u.username}？`)) return;
                try {
                    await DA.apiDelete(`/sys/user/${u.id}`);
                    DA.showToast('已删除');
                    load();
                } catch (e) {
                    DA.showToast(e.message || '删除失败', 'error');
                }
            }

            function initial(u) {
                return (u.realName || u.nickname || u.username || '?').charAt(0).toUpperCase();
            }

            function fmtTime(t) {
                if (!t) return '—';
                const d = new Date(t);
                const p = v => String(v).padStart(2, '0');
                return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())}`;
            }

            onMounted(async () => {
                const user = await DA.requireAuth();
                if (!user) return;
                if (new URLSearchParams(location.search).get('embed') === '1') {
                    document.body.classList.add('ax-embed');
                } else {
                    const host = document.getElementById('da-header-host');
                    host.innerHTML = DA.renderHeader({ active: 'users', user: user });
                    DA.bindHeaderEvents();
                }
                load();
            });

            return {
                users, loading, page, size, total, keyword,
                formVisible, form, saving, resetVisible, resetTarget, resetPasswordVal,
                load, reload, openCreate, openEdit, closeForm, save,
                toggleStatus, openReset, doReset, remove, initial, fmtTime
            };
        }
    }).mount('#da-users-app');
})();
