/**
 * Skills 管理页逻辑（独立页面，从原 app.js 拆出）。
 *
 * 依赖：
 *   - common.js 暴露的 window.DA（token / api / auth / theme / toast / renderHeader）
 *   - Vue 3 通过 CDN 引入
 *
 * 功能：
 *   - 列出后端已加载的 skills（GET /api/skills）
 *   - 上传 .zip 包安装新 skill（POST /api/skills/upload）
 *   - 启用/禁用某个 skill（PUT /api/skills/{name}/toggle）
 *   - 删除 skill（DELETE /api/skills/{name}）
 */
const { createApp, ref, computed, onMounted } = Vue;

createApp({
    setup() {
        const skills = ref([]);
        const loading = ref(false);
        const skillUploading = ref(false);
        const skillFileInputRef = ref(null);
        const uploadDragOver = ref(false);

        // 分组：平台内置（ownerUserId=0） / 我的技能
        const builtinSkills = computed(() => skills.value.filter(s => !s.ownerUserId));
        const mySkills = computed(() => skills.value.filter(s => !!s.ownerUserId));

        // ==================== Skills 加载 ====================
        const loadSkills = async () => {
            loading.value = true;
            try {
                const body = await DA.apiGet('/api/skills');
                const data = body && typeof body === 'object' ? body.data : null;
                skills.value = Array.isArray(data) ? data : [];
            } catch (e) {
                console.warn('[skills] 加载失败', e);
                DA.showToast(e.message || '加载 Skills 失败');
            } finally {
                loading.value = false;
            }
        };

        // ==================== Skill 上传 ====================
        const triggerSkillUpload = () => {
            if (skillUploading.value) return;
            if (skillFileInputRef.value) skillFileInputRef.value.click();
        };

        const onSkillFileSelected = (e) => {
            const file = e.target.files && e.target.files[0];
            if (file) uploadSkill(file);
            if (skillFileInputRef.value) skillFileInputRef.value.value = '';
        };

        const onSkillDrop = (e) => {
            uploadDragOver.value = false;
            const file = e.dataTransfer.files && e.dataTransfer.files[0];
            if (file) uploadSkill(file);
        };

        const uploadSkill = async (file) => {
            if (!file.name.toLowerCase().endsWith('.zip')) {
                DA.showToast('仅支持 .zip 文件');
                return;
            }
            skillUploading.value = true;
            try {
                const formData = new FormData();
                formData.append('file', file);
                const body = await DA.apiUpload('/api/skills/upload', formData);
                if (body && body.code === 200) {
                    DA.showToast('上传成功，已自动启用');
                    await loadSkills();
                } else {
                    DA.showToast((body && body.msg) || '上传失败');
                }
            } catch (e) {
                DA.showToast(e.message || '上传失败');
            } finally {
                skillUploading.value = false;
            }
        };

        // ==================== 启用/禁用 ====================
        const toggleSkill = async (name, enabled) => {
            try {
                // 后端 @RequestParam boolean enabled，必须走 query 参数
                const body = await DA.apiPut(
                    '/api/skills/toggle?name=' + encodeURIComponent(name) + '&enabled=' + enabled,
                    {}
                );
                if (body && body.code === 200) {
                    DA.showToast(enabled ? '已启用' : '已禁用');
                    const skill = skills.value.find(s => s.storageKey === name);
                    if (skill) skill.enabled = enabled;
                } else {
                    DA.showToast((body && body.msg) || '操作失败');
                    await loadSkills();
                }
            } catch (e) {
                DA.showToast(e.message || '操作失败');
                await loadSkills();
            }
        };

        // ==================== 删除 ====================
        const deleteSkill = async (name) => {
            const displayName = String(name || '').includes('/') ? name.split('/').pop() : name;
            if (!confirm(`确认删除 skill「${displayName}」？`)) return;
            try {
                const body = await DA.apiDelete('/api/skills?name=' + encodeURIComponent(name));
                if (body && body.code === 200) {
                    DA.showToast('已删除');
                    await loadSkills();
                } else {
                    DA.showToast((body && body.msg) || '删除失败');
                }
            } catch (e) {
                DA.showToast(e.message || '删除失败');
            }
        };

        // ==================== 生命周期 ====================
        onMounted(async () => {
            // 鉴权守卫
            const user = await DA.requireAuth();
            if (!user) return;

            // 渲染顶栏
            const headerHost = document.getElementById('da-header-host');
            if (headerHost) {
                if (new URLSearchParams(location.search).get('embed') === '1') {
                    document.body.classList.add('ax-embed');
                } else {
                    headerHost.innerHTML = DA.renderHeader({ active: 'skills', user: user });
                    DA.bindHeaderEvents();
                }
            }

            loadSkills();
        });

        return {
            skills,
            loading,
            skillUploading,
            skillFileInputRef,
            uploadDragOver,
            builtinSkills,
            mySkills,
            triggerSkillUpload,
            onSkillFileSelected,
            onSkillDrop,
            toggleSkill,
            deleteSkill
        };
    }
}).mount('#da-skills-app');
