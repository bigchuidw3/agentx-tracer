/**
 * 模型管理页：单模型配置（端点/Key/单价/上下文窗口）+ 连通性测试 + 保存热生效。
 */
(function () {
    const { createApp, ref, onMounted } = Vue;
    const DA = window.DA;

    createApp({
        setup() {
            const form = ref(null);
            const saving = ref(false);
            const testing = ref(false);
            const testResult = ref(null);
            const configured = ref(false);
            const loading = ref(false);
            const contextValue = ref(128);
            const contextUnit = ref('K');

            /** 把 token 数拆成「数值 + 单位」（默认 128 K） */
            function applyContextWindow(tokens) {
                if (tokens == null) { contextValue.value = 128; contextUnit.value = 'K'; return; }
                if (tokens >= 1024 * 1024 && tokens % (1024 * 1024) === 0) {
                    contextValue.value = Math.round(tokens / (1024 * 1024));
                    contextUnit.value = 'M';
                } else {
                    contextValue.value = Math.round(tokens / 1024);
                    contextUnit.value = 'K';
                }
            }

            /** 由「数值 + 单位」算回 token 数 */
            function contextTokens() {
                if (contextValue.value == null) return null;
                const factor = contextUnit.value === 'M' ? 1024 * 1024 : 1024;
                return Math.round(contextValue.value * factor);
            }

            async function load() {
                loading.value = true;
                try {
                    const res = await DA.apiGet('/model');
                    const models = (res.data || []);
                    if (models.length > 0) {
                        const m = models[0];
                        form.value = {
                            id: m.id,
                            baseUrl: m.baseUrl || '',
                            apiKey: m.apiKey || '',
                            modelName: m.modelName || '',
                            inputPrice: m.inputPrice != null ? String(m.inputPrice) : '',
                            outputPrice: m.outputPrice != null ? String(m.outputPrice) : '',
                            temperature: m.temperature != null ? Number(m.temperature) : 0.7
                        };
                        applyContextWindow(m.contextWindow);
                        configured.value = true;
                        return;
                    }
                } catch (e) {
                    DA.showToast(e.message || '加载失败', 'error');
                } finally {
                    loading.value = false;
                }
                form.value = {
                    id: null, baseUrl: '', apiKey: '', modelName: '',
                    inputPrice: '', outputPrice: '', temperature: 0.7
                };
                applyContextWindow(null);
                configured.value = false;
            }

            async function save() {
                if (!form.value.baseUrl || !form.value.modelName) {
                    DA.showToast('Base URL 与模型标识必填', 'error');
                    return;
                }
                saving.value = true;
                try {
                    const payload = { ...form.value, contextWindow: contextTokens() };
                    const res = await DA.apiPost('/model', payload);
                    DA.showToast((res.msg || '保存成功') + '，模型配置已热生效');
                    await load();
                } catch (e) {
                    DA.showToast(e.message || '保存失败', 'error');
                } finally {
                    saving.value = false;
                }
            }

            async function test() {
                if (!form.value.baseUrl || !form.value.modelName) {
                    DA.showToast('Base URL 与模型标识必填', 'error');
                    return;
                }
                testing.value = true;
                testResult.value = null;
                try {
                    const res = await DA.apiPost('/model/test', { ...form.value, contextWindow: contextTokens() });
                    testResult.value = res.data;
                } catch (e) {
                    testResult.value = { success: false, error: e.message };
                } finally {
                    testing.value = false;
                }
            }

            onMounted(async () => {
                const user = await DA.requireAuth();
                if (!user) return;
                if (new URLSearchParams(location.search).get('embed') === '1') {
                    document.body.classList.add('ax-embed');
                } else {
                    DA.renderHeader(document.getElementById('da-header-host'), { active: 'model' });
                    DA.bindHeaderEvents();
                }
                load();
            });

            return { form, saving, testing, testResult, configured, loading, contextValue, contextUnit, save, test };
        }
    }).mount('#ax-model-app');
})();
