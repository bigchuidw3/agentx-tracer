/**
 * 可观测性页面：调用列表 + 调用详情（统计/轨迹双页签）+ Span 全文。
 *
 * 脱敏在前端展示层完成（默认开启）：掩码 API Key / Bearer / 长数字串。
 */
(function () {
    const { createApp, ref, reactive, computed, onMounted, watch, nextTick } = Vue;
    const DA = window.DA;

    createApp({
        setup() {
            const calls = ref([]);
            const loading = ref(false);
            const page = ref(1);
            const size = ref(10);
            const total = ref(0);
            const totalPages = computed(() => Math.max(1, Math.ceil(total.value / size.value)));
            const filters = reactive({ status: '', keyword: '' });

            const detailOpen = ref(false);
            const detailTab = ref('stats');
            const stats = ref(null);
            const trace = ref(null);
            const spanDetail = ref(null);
            const selectedSpanId = ref(null);
            const currentSession = ref(null);
            const showIdTip = ref(false);
            const jsonSearch = ref('');
            const matchIndex = ref(0);
            const matchTotal = ref(0);

            // ==================== 列表 ====================
            async function loadCalls() {
                loading.value = true;
                try {
                    const params = new URLSearchParams({ page: page.value, size: size.value });
                    if (filters.status) params.set('status', filters.status);
                    if (filters.keyword) params.set('keyword', filters.keyword);
                    const res = await DA.apiGet(`/observ/calls?${params}`);
                    calls.value = (res.data && res.data.records) || [];
                    total.value = (res.data && res.data.total) || 0;
                } catch (e) {
                    DA.showToast(e.message || '加载失败', 'error');
                } finally {
                    loading.value = false;
                }
            }

            function reload() {
                page.value = 1;
                loadCalls();
            }

            // ==================== 详情 ====================
            async function openDetail(call) {
                currentSession.value = call.sessionId;
                detailOpen.value = true;
                detailTab.value = 'stats';
                stats.value = null;
                trace.value = null;
                try {
                    stats.value = (await DA.apiGet(`/observ/calls/${call.sessionId}/stats`)).data;
                    trace.value = (await DA.apiGet(`/observ/calls/${call.sessionId}/trace`)).data;
                } catch (e) {
                    DA.showToast(e.message || '详情加载失败', 'error');
                }
                nextTick(renderCharts);
            }

            function closeDetail() {
                detailOpen.value = false;
                selectedSpanId.value = null;
                spanDetail.value = null;
                disposeCharts();
            }

            async function selectSpan(span) {
                if (!span) return;
                selectedSpanId.value = span.id;
                spanDetail.value = null;
                jsonSearch.value = '';
                try {
                    spanDetail.value = (await DA.apiGet(`/observ/span/${span.id}`)).data;
                    nextTick(() => renderJsonViews(2));
                } catch (e) {
                    DA.showToast(e.message || 'Span 加载失败', 'error');
                }
            }

            function renderJsonViews(openDepth) {
                if (typeof JSONFormatter === 'undefined' || !spanDetail.value) return;
                const depth = openDepth == null ? 2 : openDepth;
                const inputEl = document.getElementById('json-input');
                if (inputEl && spanDetail.value.input_data) {
                    inputEl.innerHTML = '';
                    inputEl.appendChild(renderJson(spanDetail.value.input_data, depth));
                }
                const outputEl = document.getElementById('json-output');
                if (outputEl && spanDetail.value.output_data) {
                    outputEl.innerHTML = '';
                    outputEl.appendChild(renderJson(spanDetail.value.output_data, depth));
                }
            }

            function renderJson(text, openDepth) {
                const depth = openDepth == null ? 2 : openDepth;
                let obj;
                try {
                    obj = JSON.parse(text);
                } catch (e) {
                    const pre = document.createElement('pre');
                    pre.className = 'da-json-plain';
                    pre.textContent = text;
                    return pre;
                }
                return new JSONFormatter(obj, depth).render();
            }

            async function copyText(text) {
                try {
                    await DA.copyText(text);
                    DA.showToast('已复制到剪贴板', 'success');
                } catch (e) {
                    DA.showToast('复制失败', 'error');
                }
            }

            async function exportTraceCsv() {
                const tr = trace.value;
                if (!tr || !tr.spans || !tr.spans.length) {
                    DA.showToast('暂无轨迹数据可导出', 'error');
                    return;
                }
                try {
                    DA.showToast('正在导出完整调用链…', 'info');
                    const spans = [];
                    for (const s of tr.spans) {
                        try {
                            const d = (await DA.apiGet(`/observ/span/${s.id}`)).data;
                            spans.push({
                                id: d.id, round: d.round, spanType: d.span_type,
                                toolName: d.tool_name, toolCallId: d.tool_call_id,
                                durationMs: d.duration_ms, promptTokens: d.prompt_tokens,
                                completionTokens: d.completion_tokens, success: d.success,
                                inputData: d.input_data, outputData: d.output_data,
                                think: d.think, errorMessage: d.error_message, createdAt: d.created_at
                            });
                        } catch (e) {
                            spans.push({ id: s.id, round: s.round, spanType: s.spanType, toolName: s.toolName });
                        }
                    }
                    const payload = {
                        sessionId: tr.sessionId,
                        conversationId: tr.conversationId,
                        llmRounds: tr.llmRounds,
                        toolCalls: tr.toolCalls,
                        toolFailures: tr.toolFailures,
                        totalTokens: tr.totalTokens,
                        toolDurationMs: tr.toolDurationMs,
                        totalDurationMs: tr.totalDurationMs,
                        toolMetrics: tr.toolMetrics,
                        spans
                    };
                    const json = JSON.stringify(payload, null, 2);
                    const blob = new Blob([json], { type: 'application/json;charset=utf-8;' });
                    const url = URL.createObjectURL(blob);
                    const a = document.createElement('a');
                    a.href = url;
                    a.download = `调用链_${currentSession.value || 'session'}.json`;
                    document.body.appendChild(a);
                    a.click();
                    document.body.removeChild(a);
                    URL.revokeObjectURL(url);
                } catch (e) {
                    DA.showToast('导出失败', 'error');
                }
            }

            function highlightSearch() {
                const term = jsonSearch.value.trim();
                if (term) {
                    // 全部展开后高亮，保证折叠节点里的匹配也能被搜到
                    renderJsonViews(Infinity);
                    document.querySelectorAll('.da-json-view').forEach(view => highlightIn(view, term));
                    updateMatchInfo();
                } else {
                    renderJsonViews(2);
                    matchIndex.value = 0;
                    matchTotal.value = 0;
                }
            }

            function updateMatchInfo() {
                const marks = document.querySelectorAll('.da-json-view mark.da-search-hl');
                matchTotal.value = marks.length;
                matchIndex.value = marks.length ? 1 : 0;
                if (marks.length) scrollToMatch(0);
            }

            function scrollToMatch(idx) {
                const marks = document.querySelectorAll('.da-json-view mark.da-search-hl');
                if (!marks.length) return;
                marks.forEach(m => m.classList.remove('da-search-cur'));
                const target = marks[idx];
                if (!target) return;
                target.classList.add('da-search-cur');
                target.scrollIntoView({ block: 'center', behavior: 'smooth' });
                matchIndex.value = idx + 1;
            }

            function nextMatch() {
                const total = matchTotal.value;
                if (!total) return;
                const cur = matchIndex.value - 1;
                scrollToMatch((cur + 1) % total);
            }

            function prevMatch() {
                const total = matchTotal.value;
                if (!total) return;
                const cur = matchIndex.value - 1;
                scrollToMatch((cur - 1 + total) % total);
            }

            function highlightIn(root, term) {
                const walker = document.createTreeWalker(root, NodeFilter.SHOW_TEXT);
                const nodes = [];
                while (walker.nextNode()) nodes.push(walker.currentNode);
                const regex = new RegExp(escapeRegExp(term), 'gi');
                for (const node of nodes) {
                    const text = node.textContent;
                    if (!text.toLowerCase().includes(term.toLowerCase())) continue;
                    const frag = document.createDocumentFragment();
                    let lastIdx = 0, m;
                    regex.lastIndex = 0;
                    while ((m = regex.exec(text)) !== null) {
                        if (m.index > lastIdx) frag.appendChild(document.createTextNode(text.slice(lastIdx, m.index)));
                        const mark = document.createElement('mark');
                        mark.className = 'da-search-hl';
                        mark.textContent = m[0];
                        frag.appendChild(mark);
                        lastIdx = m.index + m[0].length;
                        if (m.index === regex.lastIndex) regex.lastIndex++;
                    }
                    if (lastIdx < text.length) frag.appendChild(document.createTextNode(text.slice(lastIdx)));
                    node.parentNode.replaceChild(frag, node);
                }
            }

            function escapeRegExp(s) {
                return s.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
            }

            // ==================== 计算 ====================
            const groupedSpans = computed(() => {
                if (!trace.value?.spans) return [];
                const groups = [];
                let current = null;
                for (const s of trace.value.spans) {
                    if (s.spanType === 'COMPACT') {
                        if (current && current.llm === null && current.round === s.round) {
                            current.compacts.push({ span: s, summary: null });
                        } else {
                            current = { round: s.round, llm: null, compacts: [{ span: s, summary: null }], tools: [] };
                            groups.push(current);
                        }
                    } else if (s.spanType === 'LLM' && s.compactId != null) {
                        // 摘要 LLM：挂到所属 COMPACT 下
                        const compact = findCompact(groups, s.compactId);
                        if (compact) {
                            compact.summary = s;
                        } else {
                            current = { round: s.round, llm: s, compacts: [], tools: [] };
                            groups.push(current);
                        }
                    } else if (s.spanType === 'LLM') {
                        if (current && current.llm === null && current.compacts.length && current.round === s.round) {
                            current.llm = s;
                        } else {
                            current = { round: s.round, llm: s, compacts: [], tools: [] };
                            groups.push(current);
                        }
                    } else if (current) {
                        current.tools.push(s);
                    } else {
                        current = { round: s.round, llm: null, compacts: [], tools: [s] };
                        groups.push(current);
                    }
                }
                return groups;
            });

            function findCompact(groups, parentId) {
                const pid = String(parentId);
                for (const g of groups) {
                    for (const c of g.compacts) {
                        if (String(c.span.id) === pid) return c;
                    }
                }
                return null;
            }

            const inOutPercent = computed(() => {
                if (!stats.value) return { in: 50, out: 50 };
                const c = stats.value.call || {};
                // 后端 Long 序列化为字符串，需 Number() 转数值，否则 p+q 变字符串拼接
                const p = Number(c.promptTokens) || 0, q = Number(c.completionTokens) || 0;
                const sum = p + q;
                return sum === 0 ? { in: 0, out: 0 } : {
                    in: Math.round(p * 100 / sum),
                    out: Math.round(q * 100 / sum)
                };
            });

            const msgStats = computed(() => {
                const m = stats.value?.messageCount || {};
                const s = Number(m.system) || 0, u = Number(m.user) || 0,
                    a = Number(m.assistant) || 0, t = Number(m.tool) || 0;
                const sum = s + u + a + t;
                return {
                    system: s, user: u, assistant: a, tool: t, total: sum,
                    systemPct: sum === 0 ? 0 : Math.round(s * 100 / sum),
                    userPct: sum === 0 ? 0 : Math.round(u * 100 / sum),
                    assistantPct: sum === 0 ? 0 : Math.round(a * 100 / sum),
                    toolPct: sum === 0 ? 0 : Math.round(t * 100 / sum)
                };
            });

            const durationDist = computed(() => {
                const t = trace.value || {};
                const total = Number(t.totalDurationMs) || 0;
                const tool = Number(t.toolDurationMs) || 0;
                const llm = Math.max(total - tool, 0);
                const sum = llm + tool;
                return {
                    llm, tool,
                    llmPct: sum === 0 ? 0 : Math.round(llm * 100 / sum),
                    toolPct: sum === 0 ? 0 : Math.round(tool * 100 / sum)
                };
            });

            const inflation = computed(() => {
                const arr = trace.value?.promptPerRound || [];
                if (arr.length < 2) return '';
                const first = arr[0][1], last = arr[arr.length - 1][1];
                return first > 0 ? `${(last / first).toFixed(1)}×` : '';
            });

            const msgCountText = computed(() => {
                const m = stats.value?.messageCount || {};
                return `${m.user || 0} / ${m.assistant || 0} / ${m.tool || 0}`;
            });

            function roundBarHeight(tok) {
                const arr = trace.value?.promptPerRound || [];
                const max = Math.max(...arr.map(p => p[1]), 1);
                return Math.max(2, Math.round(tok * 100 / max));
            }

            // ==================== 格式化 ====================
            function fmtNum(n) {
                if (n == null) return '-';
                return Number(n).toLocaleString('zh-CN');
            }

            function fmtTime(t) {
                if (!t) return '-';
                const d = new Date(t);
                const p = v => String(v).padStart(2, '0');
                return `${p(d.getMonth() + 1)}-${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}:${p(d.getSeconds())}`;
            }

            function fmtDuration(ms) {
                if (ms == null) return '-';
                if (ms < 1000) return `${ms}ms`;
                if (ms < 60000) return `${(ms / 1000).toFixed(1)}s`;
                return `${Math.floor(ms / 60000)}m${Math.round(ms % 60000 / 1000)}s`;
            }

            function statusClass(st) {
                return `st-badge st-${st || 'unknown'}`;
            }

            function statusLabel(st) {
                const map = { completed: '已完成', interrupted: '已中断', error: '错误', running: '运行中' };
                return map[st] || st || '-';
            }

            function spanTypeLabel(type) {
                const map = { LLM: 'LLM', TOOL: 'TOOL', COMPACT: '压缩' };
                return map[type] || type || '-';
            }

            onMounted(async () => {
                const user = await DA.requireAuth();
                if (!user) return;
                DA.renderHeader(document.getElementById('da-header-host'), { active: 'observability' });
                DA.bindHeaderEvents();
                // 支持从仪表盘 Top 下钻：?keyword=<sessionId> 预填搜索框（列表页即筛选条件）
                const kw = new URLSearchParams(location.search).get('keyword');
                if (kw) filters.keyword = kw;
                loadCalls();
                // 点击 ID 说明 tip 外部时关闭
                document.addEventListener('click', (e) => {
                    if (showIdTip.value && !e.target.closest('.da-id-tip-wrap')) {
                        showIdTip.value = false;
                    }
                });
            });

            // ==================== ECharts（统计页签，本地加载） ====================
            let chartInstances = [];

            function disposeCharts() {
                chartInstances.forEach(c => { try { c.dispose(); } catch (e) {} });
                chartInstances = [];
            }

            function donutOption(data, centerLabel, unit) {
                const opt = {
                    tooltip: { trigger: 'item', formatter: '{b}: {c}' + (unit || '') },
                    series: [{
                        type: 'pie', radius: ['55%', '78%'], center: ['50%', '45%'],
                        label: { show: false }, emphasis: { scale: false },
                        itemStyle: { borderRadius: 6, borderColor: '#fff', borderWidth: 2 },
                        data
                    }]
                };
                if (centerLabel) {
                    opt.series[0].label = {
                        show: true, position: 'center',
                        formatter: centerLabel.text + '\n' + centerLabel.sub,
                        fontSize: 18, fontWeight: 700, color: '#0f172a', lineHeight: 22
                    };
                    opt.series[0].emphasis = { label: { show: true } };
                } else {
                    opt.legend = { bottom: 0, icon: 'circle', itemWidth: 8, itemHeight: 8, textStyle: { color: '#64748b', fontSize: 12 } };
                    opt.series[0].center = ['50%', '42%'];
                }
                return opt;
            }

            function renderCharts() {
                if (typeof echarts === 'undefined') return;
                disposeCharts();
                const st = stats.value, tr = trace.value;
                if (!st) return;

                const ctxEl = document.getElementById('chart-context');
                if (ctxEl) {
                    const pct = st.contextUsagePercent || 0;
                    const c = echarts.init(ctxEl);
                    c.setOption(donutOption(
                        [
                            { value: pct, name: '已占用', itemStyle: { color: '#4f6ef7' } },
                            { value: Math.max(100 - pct, 0), name: '空闲', itemStyle: { color: '#eef2f7' } }
                        ],
                        { text: pct + '%', sub: '峰值占用' },
                        '%'
                    ));
                    chartInstances.push(c);
                }

                const tokenEl = document.getElementById('chart-token');
                if (tokenEl) {
                    const c = echarts.init(tokenEl);
                    c.setOption(donutOption([
                        { value: inOutPercent.value.in, name: '输入', itemStyle: { color: '#4f6ef7' } },
                        { value: inOutPercent.value.out, name: '输出', itemStyle: { color: '#10b981' } }
                    ], null, '%'));
                    chartInstances.push(c);
                }

                const msgEl = document.getElementById('chart-msg');
                if (msgEl) {
                    const c = echarts.init(msgEl);
                    c.setOption(donutOption([
                        { value: msgStats.value.system, name: '系统', itemStyle: { color: '#64748b' } },
                        { value: msgStats.value.user, name: '用户', itemStyle: { color: '#4f6ef7' } },
                        { value: msgStats.value.assistant, name: '助手', itemStyle: { color: '#10b981' } },
                        { value: msgStats.value.tool, name: '工具', itemStyle: { color: '#f59e0b' } }
                    ], { text: msgStats.value.total, sub: '总消息' }, ' 条'));
                    chartInstances.push(c);
                }

                const durEl = document.getElementById('chart-duration');
                if (durEl) {
                    const c = echarts.init(durEl);
                    c.setOption(donutOption([
                        { value: durationDist.value.llmPct, name: 'LLM', itemStyle: { color: '#8b5cf6' } },
                        { value: durationDist.value.toolPct, name: '工具', itemStyle: { color: '#f59e0b' } }
                    ], null, '%'));
                    chartInstances.push(c);
                }

                const roundsEl = document.getElementById('chart-rounds');
                if (roundsEl && tr && tr.promptPerRound && tr.promptPerRound.length >= 1) {
                    // 中断恢复会开新 trace，但框架轮次是全局递增的，这里改成相对轮次（1 起）
                    const minRound = Math.min(...tr.promptPerRound.map(p => p[0]));
                    const rounds = tr.promptPerRound.map(p => p[0] - minRound + 1);
                    const c = echarts.init(roundsEl);
                    c.setOption({
                        tooltip: { trigger: 'axis', formatter: p => `第 ${p[0].axisValue} 轮：${Number(p[0].value).toLocaleString('zh-CN')} tok` },
                        grid: { left: 10, right: 24, top: 24, bottom: 30, containLabel: true },
                        xAxis: { type: 'category', data: rounds, axisLabel: { color: '#94a3b8', fontSize: 11 } },
                        yAxis: { type: 'value', axisLabel: { color: '#94a3b8', fontSize: 11 }, splitLine: { lineStyle: { color: '#f0f2f5' } } },
                        series: [{
                            type: 'line', smooth: true, data: tr.promptPerRound.map(p => Number(p[1])),
                            lineStyle: { color: '#4f6ef7', width: 2 }, itemStyle: { color: '#4f6ef7' },
                            areaStyle: { color: 'rgba(79,110,247,0.08)' }
                        }]
                    });
                    chartInstances.push(c);
                }

                const toolsEl = document.getElementById('chart-tools');
                if (toolsEl && tr && tr.toolMetrics && tr.toolMetrics.length > 0) {
                    const c = echarts.init(toolsEl);
                    const toolNames = tr.toolMetrics.map(m => m.toolName);
                    const counts = tr.toolMetrics.map(m => Number(m.count));
                    c.setOption({
                        tooltip: { trigger: 'axis', axisPointer: { type: 'shadow' },
                            formatter: ps => {
                                const m = tr.toolMetrics[ps[0].dataIndex];
                                const avg = m.count > 0 ? Math.round(m.totalDurationMs / m.count) : 0;
                                return `${m.toolName}<br/>次数：${m.count}<br/>总耗时：${fmtDuration(m.totalDurationMs)}` +
                                    `<br/>平均耗时：${fmtDuration(avg)}<br/>最大耗时：${fmtDuration(m.maxMs)}`;
                            } },
                        grid: { left: 10, right: 36, top: 8, bottom: 8, containLabel: true },
                        xAxis: { type: 'value', axisLabel: { color: '#94a3b8', fontSize: 11 }, splitLine: { lineStyle: { color: '#f0f2f5' } } },
                        yAxis: { type: 'category', data: toolNames, axisLine: { show: false }, axisTick: { show: false }, axisLabel: { color: '#64748b', fontSize: 11 } },
                        series: [{
                            type: 'bar', barWidth: 12, data: counts,
                            itemStyle: { color: '#4f6ef7', borderRadius: [0, 6, 6, 0] },
                            label: { show: true, position: 'right', color: '#64748b', fontSize: 11 }
                        }]
                    });
                    chartInstances.push(c);
                }
            }

            watch([stats, detailTab], () => {
                if (detailTab.value === 'stats' && stats.value) {
                    nextTick(renderCharts);
                }
            });

            return {
                calls, loading, page, size, total, totalPages, filters,
                detailOpen, detailTab, stats, trace,
                selectedSpanId, spanDetail, currentSession, showIdTip, jsonSearch, matchIndex, matchTotal,
                loadCalls, reload, openDetail, closeDetail, selectSpan, copyText,
                exportTraceCsv, highlightSearch, nextMatch, prevMatch,
                groupedSpans, inOutPercent, msgStats, durationDist, inflation, msgCountText, roundBarHeight,
                fmtNum, fmtTime, fmtDuration, statusClass, statusLabel, spanTypeLabel
            };
        }
    }).mount('#da-observ-app');
})();
