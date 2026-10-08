/**
 * 仪表盘：全局调用量 / Token / 成本 / 质量看板。
 * 时间范围（当天/近7天/近30天）全局联动所有图表。
 */
(function () {
    const { createApp, ref, onMounted, nextTick } = Vue;
    const DA = window.DA;

    createApp({
        setup() {
            const days = ref(7);
            const ranges = [
                { days: 0, label: '当天' },
                { days: 7, label: '近 7 天' },
                { days: 30, label: '近 30 天' }
            ];
            const kpi = ref({});
            const trend = ref([]);
            const toolDistribution = ref([]);
            const statusDistribution = ref([]);
            const topTokens = ref([]);
            const topDuration = ref([]);
            const topRounds = ref([]);
            const topTools = ref([]);

            async function loadOverview() {
                try {
                    const res = await DA.apiGet(`/dashboard/overview?days=${days.value}`);
                    const d = res.data || {};
                    kpi.value = d.kpi || {};
                    trend.value = d.trend || [];
                    toolDistribution.value = d.toolDistribution || [];
                    statusDistribution.value = d.statusDistribution || [];
                    topTokens.value = d.topTokens || [];
                    topDuration.value = d.topDuration || [];
                    topRounds.value = d.topRounds || [];
                    topTools.value = d.topTools || [];
                    nextTick(renderCharts);
                } catch (e) {
                    DA.showToast(e.message || '加载失败', 'error');
                }
            }

            function switchRange(d) {
                days.value = d;
                loadOverview();
            }

            function drillTo(s) {
                if (s && s.sessionId) {
                    location.href = `observability.html?keyword=${encodeURIComponent(s.sessionId)}`;
                }
            }

            function fmtNum(n) {
                if (n == null) return '-';
                return Number(n).toLocaleString('zh-CN');
            }

            function fmtDuration(ms) {
                if (ms == null) return '-';
                if (ms < 1000) return Math.round(ms) + 'ms';
                if (ms < 60000) return (ms / 1000).toFixed(1) + 's';
                return Math.floor(ms / 60000) + 'm' + Math.round(ms % 60000 / 1000) + 's';
            }

            // 纵轴大数缩写（1.2万 / 3.4亿），避免坐标文字过宽被截断
            function axisCompact(v) {
                if (v == null) return '';
                if (Math.abs(v) >= 100000000) return (v / 100000000).toFixed(1).replace(/\.0$/, '') + '亿';
                if (Math.abs(v) >= 10000) return (v / 10000).toFixed(1).replace(/\.0$/, '') + '万';
                return v;
            }

            // ==================== ECharts ====================
            let charts = [];
            function disposeCharts() {
                charts.forEach(c => { try { c.dispose(); } catch (e) {} });
                charts = [];
            }

            function renderCharts() {
                if (typeof echarts === 'undefined') return;
                disposeCharts();
                renderTrendCalls();
                renderTrendTokens();
                renderTrendRate();
                renderTools();
                renderStatus();
            }

            function axisCategory() {
                return { type: 'category', data: trend.value.map(t => t.label), axisLabel: { color: '#94a3b8', fontSize: 11 } };
            }

            function valueAxis(formatter) {
                return {
                    type: 'value',
                    axisLabel: { color: '#94a3b8', fontSize: 11, formatter: formatter || null },
                    splitLine: { lineStyle: { color: '#f0f2f5' } }
                };
            }

            function renderTrendCalls() {
                const el = document.getElementById('chart-trend-calls');
                if (!el) return;
                const c = echarts.init(el);
                c.setOption({
                    tooltip: { trigger: 'axis' },
                    grid: { left: 12, right: 16, top: 20, bottom: 30, containLabel: true },
                    xAxis: axisCategory(),
                    yAxis: valueAxis(),
                    series: [{
                        type: 'bar', data: trend.value.map(t => t.calls), barWidth: 14,
                        itemStyle: { color: '#4f6ef7', borderRadius: [4, 4, 0, 0] }
                    }]
                });
                charts.push(c);
            }

            function renderTrendTokens() {
                const el = document.getElementById('chart-trend-tokens');
                if (!el) return;
                const c = echarts.init(el);
                c.setOption({
                    tooltip: { trigger: 'axis', valueFormatter: v => Number(v).toLocaleString('zh-CN') },
                    grid: { left: 12, right: 16, top: 20, bottom: 30, containLabel: true },
                    xAxis: axisCategory(),
                    yAxis: valueAxis(axisCompact),
                    series: [{
                        name: 'Token', type: 'line', smooth: true, data: trend.value.map(t => t.tokens),
                        lineStyle: { color: '#4f6ef7', width: 2 }, itemStyle: { color: '#4f6ef7' },
                        areaStyle: { color: 'rgba(79,110,247,0.08)' }
                    }]
                });
                charts.push(c);
            }

            function renderTrendRate() {
                const el = document.getElementById('chart-trend-rate');
                if (!el) return;
                const c = echarts.init(el);
                c.setOption({
                    tooltip: { trigger: 'axis', valueFormatter: v => v + '%' },
                    grid: { left: 12, right: 16, top: 20, bottom: 30, containLabel: true },
                    xAxis: axisCategory(),
                    yAxis: { type: 'value', max: 100, axisLabel: { color: '#94a3b8', fontSize: 11, formatter: '{value}%' }, splitLine: { lineStyle: { color: '#f0f2f5' } } },
                    series: [{
                        name: '成功率', type: 'line', smooth: true, data: trend.value.map(t => t.successRate),
                        lineStyle: { color: '#10b981', width: 2 }, itemStyle: { color: '#10b981' },
                        areaStyle: { color: 'rgba(16,185,129,0.08)' }
                    }]
                });
                charts.push(c);
            }

            function renderTools() {
                const el = document.getElementById('chart-tools');
                if (!el) return;
                const c = echarts.init(el);
                const reversed = [...toolDistribution.value].reverse();
                c.setOption({
                    tooltip: { trigger: 'axis', axisPointer: { type: 'shadow' } },
                    legend: { data: ['成功', '失败'], bottom: 0, textStyle: { color: '#64748b', fontSize: 11 } },
                    grid: { left: 12, right: 30, top: 10, bottom: 30, containLabel: true },
                    xAxis: { type: 'value', axisLabel: { color: '#94a3b8', fontSize: 11 }, splitLine: { lineStyle: { color: '#f0f2f5' } } },
                    yAxis: { type: 'category', data: reversed.map(t => t.toolName), axisLine: { show: false }, axisTick: { show: false }, axisLabel: { color: '#64748b', fontSize: 11 } },
                    series: [
                        { name: '成功', type: 'bar', stack: 'total', data: reversed.map(t => t.count - t.failures), itemStyle: { color: '#4f6ef7' } },
                        { name: '失败', type: 'bar', stack: 'total', data: reversed.map(t => t.failures), itemStyle: { color: '#f59e0b' } }
                    ]
                });
                charts.push(c);
            }

            function renderStatus() {
                const el = document.getElementById('chart-status');
                if (!el) return;
                const c = echarts.init(el);
                const map = { completed: '已完成', interrupted: '已中断', error: '错误', running: '运行中' };
                c.setOption({
                    tooltip: { trigger: 'item', formatter: '{b}: {c} ({d}%)' },
                    legend: { bottom: 0, icon: 'circle', itemWidth: 8, itemHeight: 8, textStyle: { color: '#64748b', fontSize: 11 } },
                    series: [{
                        type: 'pie', radius: ['45%', '70%'], center: ['50%', '45%'],
                        label: { show: false }, emphasis: { scale: false },
                        itemStyle: { borderRadius: 6, borderColor: '#fff', borderWidth: 2 },
                        data: statusDistribution.value.map(s => ({ name: map[s.status] || s.status || '未知', value: s.count }))
                    }]
                });
                charts.push(c);
            }

            onMounted(async () => {
                const user = await DA.requireAuth();
                if (!user) return;
                DA.renderHeader(document.getElementById('da-header-host'), { active: 'dashboard' });
                DA.bindHeaderEvents();
                loadOverview();
            });

            return {
                days, ranges, kpi, trend, toolDistribution, statusDistribution,
                topTokens, topDuration, topRounds, topTools,
                switchRange, drillTo, fmtNum, fmtDuration
            };
        }
    }).mount('#da-dashboard-app');
})();
