/**
 * MaaS 2.0 - 安全智能体对话页
 * 严格照抄 参考实现 app.js：
 *   - PascalCase SSE 事件类型（AgentStart/Thinking/Text/ToolStart/ToolEnd/...）
 *   - 纯时间线模式：所有内容（thinking/text/tool/todo/error）都在 timeline[] 数组中
 *   - 流式时增量更新 DOM，不重建整个消息容器
 */
(function () {
  'use strict';

  const { Auth, Api, Nav, Toast, Markdown, Utils, AgentTimeline } = window.MaaS;
  if (!Auth.requireAuth()) return;

  // ==================== 常量（照抄 参考实现） ====================
  const EVENTS = {
    AGENT_START: 'AgentStart',
    THINKING: 'Thinking',
    TEXT: 'Text',
    TOOL_START: 'ToolStart',
    TOOL_END: 'ToolEnd',
    TODO_PROGRESS: 'TodoProgress',
    STAGE_OUTPUT: 'StageOutput',
    ERROR: 'Error',
    COMPLETE: 'Complete',
    PAUSED: 'Paused'
  };
  const SUPPORTED_EVENTS = new Set(AgentTimeline.types);
  const SCENES = Object.freeze({
    general: {
      title: 'AgentX 智能体',
      greeting: '你好呀👋 有什么可以帮你的？',
      description: '',
      placeholder: '输入安全问题、研判线索或待分析内容，Enter 发送，Shift+Enter 换行\n支持拖拽上传文件（PDF / Office / 图片，最多 3 个，每个 ≤ 100MB）',
      footer: '智能体也会犯错，回答仅供参考，关键信息请仔细甄别。',
      samplePage: 'agent-chat',
      suggestions: [
        { icon: 'fas fa-shield-halved', text: '帮我分析一段可疑的 PHP 代码' },
        { icon: 'fas fa-database', text: '常见的 SQL 注入攻击手法有哪些' },
        { icon: 'fas fa-bug', text: '如何检测和防御 WebShell' },
        { icon: 'fas fa-code', text: '讲讲 XSS 攻击与防御措施' }
      ]
    },
    'security-qa': {
      title: '安全知识问答',
      greeting: '从安全概念到落地实践，都可以继续追问。',
      description: '适合安全术语解释、检测思路梳理和防护方案拆解，支持多轮展开细节。',
      placeholder: '输入想了解的安全概念、机制或防护问题，Enter 发送，Shift+Enter 换行\n支持拖拽上传文件（PDF / Office / 图片，最多 3 个，每个 ≤ 100MB）',
      footer: '安全知识问答场景适合方法论讨论与术语解释，最终落地仍需结合你的实际环境核验。',
      samplePage: 'agent-chat-security-qa',
      suggestions: [
        { icon: 'fas fa-diagram-project', text: '请用通俗语言解释零信任架构的核心原则，并给出企业落地时的最小实施清单。' },
        { icon: 'fas fa-network-wired', text: '横向移动常见手法有哪些？请从身份、主机和网络三个层面给出检测思路。' },
        { icon: 'fas fa-user-secret', text: '讲讲攻击者常见的权限提升路径，以及蓝队如何做基线防护。' }
      ]
    },
    'alert-interpretation': {
      title: '告警解读',
      greeting: '给我告警线索，我来帮你拆解风险与处置顺序。',
      description: '适合单条或同类告警的误报判断、攻击阶段分析和优先级排序。',
      placeholder: '输入告警内容、资产背景或时间线，Enter 发送，Shift+Enter 换行\n支持拖拽上传文件（PDF / Office / 图片，最多 3 个，每个 ≤ 100MB）',
      footer: '告警解读会给出研判方向和优先级建议，实际处置前请结合原始日志与环境基线复核。',
      samplePage: 'agent-chat-alert-interpretation',
      suggestions: [
        { icon: 'fas fa-triangle-exclamation', text: '请解读以下告警，判断风险等级、可能攻击阶段、误报可能性和处置顺序：主机 win-app-07 出现进程链 winword.exe → powershell.exe → rundll32.exe，并外联 198.51.100.23:443。' },
        { icon: 'fas fa-earth-asia', text: '某账号在 10 分钟内从两个相距较远的地区登录，并在登录后批量读取敏感文件。请分析需要补充哪些证据，并给出初步处置建议。' },
        { icon: 'fas fa-list-check', text: '收到一条“疑似异常登录后执行 PowerShell 下载”的告警时，首轮研判应该先看哪些字段？' }
      ]
    },
    'incident-interpretation': {
      title: '安全事件解读',
      greeting: '把事件线索交给我，我来帮你还原攻击链。',
      description: '适合多条事件线索拼接、证据缺口梳理和取证/遏制动作规划。',
      placeholder: '输入事件时间线、主机行为或调查记录，Enter 发送，Shift+Enter 换行\n支持拖拽上传文件（PDF / Office / 图片，最多 3 个，每个 ≤ 100MB）',
      footer: '安全事件解读用于帮助整理攻击链和响应动作，关键结论应结合现场证据和应急流程确认。',
      samplePage: 'agent-chat-incident-interpretation',
      suggestions: [
        { icon: 'fas fa-timeline', text: '请根据以下时间线还原攻击链并给出处置优先级：10:02 VPN 异地登录；10:06 目标主机执行编码 PowerShell；10:08 访问域控共享；10:12 多台主机出现相同外联。' },
        { icon: 'fas fa-magnifying-glass-chart', text: '请把这起事件按“入口、执行、持久化、横向移动、影响”五个阶段整理，并标出证据缺口和下一步取证项。' },
        { icon: 'fas fa-user-shield', text: '如果你只有 EDR 进程链和少量网络日志，事件复盘时应如何标记高置信与低置信结论？' }
      ]
    },
    'vulnerability-interpretation': {
      title: '漏洞解读',
      greeting: '给我漏洞编号或组件信息，我来帮你拆解风险与修复路径。',
      description: '适合 CVE 解读、影响范围判断、补丁优先级排序和回归验证设计。',
      placeholder: '输入漏洞编号、组件版本或暴露面信息，Enter 发送，Shift+Enter 换行\n支持拖拽上传文件（PDF / Office / 图片，最多 3 个，每个 ≤ 100MB）',
      footer: '漏洞解读会提供修复与验证建议，但最终影响范围仍需结合你的资产清单与版本基线确认。',
      samplePage: 'agent-chat-vulnerability-interpretation',
      suggestions: [
        { icon: 'fas fa-bug-slash', text: '请解读 CVE-2021-44228，包括影响范围、利用前提、排查方法、临时缓解、正式修复和修复后的验证步骤。' },
        { icon: 'fas fa-server', text: '一台暴露公网的服务器运行 Apache HTTP Server 2.4.49。请给出风险判断、验证边界、修复优先级和回归检查清单。' },
        { icon: 'fas fa-screwdriver-wrench', text: '面对高危漏洞公告时，如何快速区分“必须立即停机修复”和“可通过缓解措施短期降险”的场景？' }
      ]
    },
    'threat-intelligence': {
      title: '威胁情报查询',
      greeting: '把 IOC 或域名交给我，我来帮你梳理情报判断边界。',
      description: '适合 IP、域名、URL 或样本线索的基础情报解读，并明确时效性限制。',
      placeholder: '输入 IOC、域名、URL 或情报线索，Enter 发送，Shift+Enter 换行\n支持拖拽上传文件（PDF / Office / 图片，最多 3 个，每个 ≤ 100MB）',
      footer: '威胁情报查询强调信誉与时效性，不会把“暂未发现恶意证据”等同于“确认安全”。',
      samplePage: 'agent-chat-threat-intelligence',
      suggestions: [
        { icon: 'fas fa-location-dot', text: '请查询并解读 IOC 8.8.8.8，包括归属、信誉、关联风险和是否建议封禁，并说明情报时效性。' },
        { icon: 'fas fa-globe', text: '请对域名 example.com 做基础威胁情报分析，并明确区分“未发现恶意证据”和“确认安全”。' },
        { icon: 'fas fa-clock-rotate-left', text: '如果某个 IOC 曾在历史事件中出现，但最近 90 天没有新的恶意证据，研判时应该怎么表述风险？' }
      ]
    },
    'webshell-detect': {
      title: 'Webshell检测',
      greeting: '上传脚本文件或贴出代码，我来帮你研判是否 Webshell。',
      description: '适合 PHP / JSP / ASP 等脚本文件的 Webshell 研判与恶意特征识别。',
      placeholder: '上传脚本文件或输入文件路径/代码片段，Enter 发送，Shift+Enter 换行\n支持拖拽上传文件（PDF / Office / 图片 / 脚本，最多 3 个，每个 ≤ 100MB）',
      footer: 'Webshell 检测结果仅供参考，正式处置前请结合人工复核。',
      samplePage: 'agent-chat',
      suggestions: [
        { icon: 'fas fa-bug', text: '帮我检测这个 PHP 文件是不是 Webshell' },
        { icon: 'fas fa-file-code', text: '分析这段 JSP 代码是否包含后门' },
        { icon: 'fas fa-shield-halved', text: '如何识别一句话木马的常见混淆手法？' }
      ]
    },
    'pcap-detect': {
      title: 'PCAP检测',
      greeting: '上传 PCAP 抓包文件，我来帮你研判恶意流量。',
      description: '对 PCAP 抓包文件做恶意流量研判，识别攻击行为（Webshell 上传、命令执行、SQL 注入、扫描、挖矿、外泄等）并提取 IOC。',
      placeholder: '上传 pcap/pcapng 文件或输入文件路径，Enter 发送，Shift+Enter 换行；支持拖拽上传（最多 3 个，每个 ≤ 100MB）',
      footer: 'PCAP 检测结果仅供参考，正式处置前请结合人工复核。',
      samplePage: 'agent-chat',
      suggestions: [
        { icon: 'fas fa-network-wired', text: '帮我分析这个 pcap 是否包含恶意流量' },
        { icon: 'fas fa-bug', text: '检测这段流量里是否有 Webshell 上传行为' },
        { icon: 'fas fa-file-code', text: '从抓包里提取可疑 IOC' }
      ]
    }
  });

  const TODO_TOOL_MAP = [
    { keys: ['探查', 'schema', '表结构', '列名', '字段'], tools: ['exploreSchema', 'describeTables', 'listTables'] },
    { keys: ['口径', '术语', '活跃', 'VIP', '大额', '指标'], tools: ['lookupGlossary'] },
    { keys: ['图', '可视化', '趋势', '柱状', '折线', '饼', '出图', '画'], tools: ['generate_chart', 'generateChart'] },
    { keys: ['计算', 'python', '环比', '贡献度', '归因'], tools: ['bash'] },
    { keys: ['校验', '验证', '检查'], tools: ['validateSql'] },
    { keys: ['SQL', '查询', '执行', '统计'], tools: ['executeSql'] },
    { keys: ['搜索', '联网', '查一下'], tools: ['tavily_search', 'tavily-search'] },
    { keys: ['加载', '读取', '文档', '文件'], tools: ['analyzeFile'] }
  ];

  // ==================== 状态 ====================
  const activeSceneKey = resolveSceneKey();
  const activeScene = SCENES[activeSceneKey];
  let conversationId = getOrCreateConvId();
  let messages = [];
  let isSending = false;
  let sampleMaterializing = false;

  // 文件上传（照抄 参考实现）
  const MAX_FILES = 3;
  const MAX_SIZE_MB = 100;
  const IMAGE_EXT_RE = /^(jpg|jpeg|png|gif|bmp|webp)$/i;
  let uploadedFiles = [];
  let inFlightMaterializedFiles = [];
  let isUploading = false;
  const SAMPLE_BATCH_SIZE = 4;
  let sampleQuestionsCursor = 0;
  let sampleQuestionsLoading = false;

  // 流式时缓存的 DOM 引用（照抄 参考实现 nextTick 后 querySelector 模式）
  let currentTimelineEl = null;       // .chat-timeline 容器
  let currentLastTextEl = null;      // 最后一个 text 类型 item 的 .text-item DOM
  let currentLastThinkingEl = null;  // 最后一个 thinking 类型 item 的 .thinking-card DOM
  let currentLastThinkingTextEl = null; // thinking 里的 .thinking-text
  let currentLoadingEl = null;       // .loading-dots

  // DOM 缓存
  const chatMessages = document.getElementById('chatMessages');
  const chatEmpty = document.getElementById('chatEmpty');
  const chatLoading = document.getElementById('chatLoading');
  const chatSceneTitle = document.getElementById('chatSceneTitle');
  const chatSceneGreeting = document.getElementById('chatSceneGreeting');
  const chatSceneDescription = document.getElementById('chatSceneDescription');
  const chatSuggestions = document.getElementById('chatSuggestions');
  const chatFooter = document.getElementById('chatFooter');
  const convList = document.getElementById('convList');
  const convCount = document.getElementById('convCount');
  const chatInput = document.getElementById('chatInput');
  const sendBtn = document.getElementById('sendBtn');
  const fileChips = document.getElementById('fileChips');
  const fileInput = document.getElementById('fileInput');
  const uploadBtn = document.getElementById('uploadBtn');
  const chatInputWrap = document.getElementById('chatInputWrap');
  const chatFooterHintText = document.getElementById('chatFooterHintText');
  const sampleQuestionsList = document.getElementById('sampleQuestionsList');
  const sampleQuestionsRefresh = document.getElementById('sampleQuestionsRefresh');

  // 渲染导航栏
  Nav.render('agent-square', document.getElementById('maas-header'));

  // ==================== 工具函数 ====================
  function resolveSceneKey() {
    const url = new URL(window.location.href);
    const scene = url.searchParams.get('scene');
    return Object.prototype.hasOwnProperty.call(SCENES, scene) ? scene : 'general';
  }

  function getOrCreateConvId() {
    const url = new URL(window.location.href);
    return url.searchParams.get('conversationId')
      || ('conv_' + Date.now() + '_' + Math.random().toString(36).slice(2, 8));
  }

  function renderSceneSuggestions() {
    if (!chatSuggestions) return;
    // 随机打乱顺序，每次进入/刷新推荐问题都会轮换展示
    const shuffled = activeScene.suggestions.slice().sort(function () { return Math.random() - 0.5; });
    chatSuggestions.innerHTML = shuffled.map(function (item) {
      return '' +
        '<button class="chat-suggestion-item" data-text="' + Utils.escapeHtml(item.text) + '">' +
          '<span class="chat-suggestion-icon"><i class="' + item.icon + '"></i></span>' +
          '<span class="chat-suggestion-text">' + Utils.escapeHtml(item.text) + '</span>' +
          '<i class="fas fa-arrow-right chat-suggestion-arrow"></i>' +
        '</button>';
    }).join('');
  }

  function applySceneContent() {
    document.title = activeScene.title + ' · AgentX Tracer';
    if (chatSceneTitle) chatSceneTitle.textContent = activeScene.title;
    if (chatSceneGreeting) chatSceneGreeting.textContent = activeScene.greeting;
    if (chatSceneDescription) chatSceneDescription.textContent = activeScene.description;
    if (chatInput) chatInput.placeholder = activeScene.placeholder;
    if (chatFooterHintText) chatFooterHintText.textContent = activeScene.footer;
    renderSceneSuggestions();
  }

  function normalizeSceneInUrl(url) {
    if (activeSceneKey === 'general') {
      url.searchParams.delete('scene');
    } else {
      url.searchParams.set('scene', activeSceneKey);
    }
  }

  function clearStreamRefs() {
    currentTimelineEl = null;
    currentLastTextEl = null;
    currentLastThinkingEl = null;
    currentLastThinkingTextEl = null;
    currentLoadingEl = null;
  }

  function generateId() {
    return 'msg_' + Date.now() + '_' + Math.random().toString(36).slice(2, 8);
  }

  function hasPendingAttachments() {
    return isUploading || uploadedFiles.length > 0;
  }

  function cleanupMaterializedFile(file, keepalive) {
    if (!file || !file.sampleMaterialized || !file.fileId) {
      return Promise.resolve();
    }
    file.sampleMaterialized = false;
    return fetch('/api/sample-questions/files/' + encodeURIComponent(file.fileId), {
      method: 'DELETE',
      headers: { 'satoken': Auth.getToken() },
      keepalive: !!keepalive
    }).then(function (response) {
      if (!response.ok && response.status !== 404) {
        console.warn('[sample-questions] materialized file cleanup rejected: %s', response.status);
      }
    }).catch(function (error) {
      console.warn('[sample-questions] materialized file cleanup failed: %s', error && error.message);
    });
  }

  function cleanupPendingMaterializedFiles(files, keepalive) {
    (files || []).forEach(function (file) {
      cleanupMaterializedFile(file, keepalive);
    });
  }

  // ==================== 文件上传（照抄 参考实现） ====================
  function isImageFile(f) {
    var ext = (f && f.fileType) ? f.fileType.toLowerCase() : '';
    return IMAGE_EXT_RE.test(ext);
  }

  // 只有图片有缩略图，非图片一律返回空（照抄 参考实现：非图片不渲染 <img>）
  function fileThumb(f) {
    if (!isImageFile(f)) return '';
    return f.previewUrl || '';
  }

  function renderFileChips() {
    if (uploadedFiles.length === 0) {
      fileChips.style.display = 'none';
      fileChips.innerHTML = '';
      return;
    }
    fileChips.style.display = '';
    fileChips.innerHTML = uploadedFiles.map(function (f, idx) {
      var isImg = isImageFile(f);
      var thumb = fileThumb(f);
      var statusCls = '';
      var statusText = '';
      if (f.status === 'uploading') { statusCls = ' uploading'; statusText = '上传中…'; }
      else if (f.status === 'FAILED') { statusCls = ' failed'; statusText = '失败'; }
      else if (f.status === 'PROCESSING') { statusCls = ''; statusText = '处理中…'; }
      return '<div class="da-file-card' + statusCls + '">' +
        '<div class="da-file-thumb">' +
          (thumb ? '<img src="' + thumb + '" alt="" />' : '<i class="fas ' + (isImg ? 'fa-image' : 'fa-file-lines') + '"></i>') +
          (f.status === 'SUCCESS' ? '<span class="da-file-check"><i class="fas fa-check"></i></span>' : '') +
          (f.status === 'uploading' ? '<div class="da-file-thumb-loading"><span class="da-spinner"></span></div>' : '') +
          (f.status === 'FAILED' ? '<div class="da-file-thumb-failed"><i class="fas fa-triangle-exclamation"></i></div>' : '') +
        '</div>' +
        '<div class="da-file-meta">' +
          '<div class="da-file-name" title="' + Utils.escapeHtml(f.fileName) + '">' + Utils.escapeHtml(f.fileName) + '</div>' +
          (statusText ? '<div class="da-file-sub"><span class="da-file-status' + (f.status === 'FAILED' ? ' failed' : '') + '">' + statusText + '</span></div>' : '') +
        '</div>' +
        '<button class="da-file-remove" onclick="window._removeFile(' + idx + ')" title="移除"><i class="fas fa-xmark"></i></button>' +
      '</div>';
    }).join('');
  }

  function uploadFile(file) {
    if (uploadedFiles.length >= MAX_FILES) {
      Toast.warning('最多上传 ' + MAX_FILES + ' 个文件');
      return;
    }
    if (file.size > MAX_SIZE_MB * 1024 * 1024) {
      Toast.warning('文件 "' + file.name + '" 超过 ' + MAX_SIZE_MB + 'MB');
      return;
    }

    var lowerName = file.name.toLowerCase();
    var isImg = /\.(jpg|jpeg|png|gif|bmp|webp)$/.test(lowerName);
    var placeholder = {
      fileId: null,
      fileName: file.name,
      fileType: lowerName.split('.').pop() || '',
      fileSize: file.size,
      status: 'uploading',
      previewUrl: isImg ? URL.createObjectURL(file) : '',
      url: ''
    };
    uploadedFiles.push(placeholder);
    isUploading = true;
    renderFileChips();

    var formData = new FormData();
    formData.append('file', file);

    Api.postForm('/api/file/chat-upload', formData).then(function (data) {
      placeholder.fileId = data.fileId;
      placeholder.url = data.url || '';
      placeholder.fileName = data.fileName || file.name;
      placeholder.fileType = data.fileType || placeholder.fileType;
      placeholder.fileSize = data.fileSize || file.size;
      placeholder.status = 'SUCCESS';
    }).catch(function (err) {
      console.error('[maas] 上传失败', err);
      placeholder.status = 'FAILED';
      Toast.error('"' + file.name + '" 上传失败: ' + err.message);
    }).finally(function () {
      isUploading = uploadedFiles.some(function (f) { return f.status === 'uploading'; });
      renderFileChips();
    });
  }

  window._removeFile = function (idx) {
    var f = uploadedFiles[idx];
    if (!f) return;
    cleanupMaterializedFile(f, false);
    if (f.previewUrl) {
      try { URL.revokeObjectURL(f.previewUrl); } catch (e) { /* ignore */ }
    }
    uploadedFiles.splice(idx, 1);
    isUploading = uploadedFiles.some(function (f) { return f.status === 'uploading'; });
    renderFileChips();
  };

  function triggerFileUpload() {
    if (isUploading) return;
    if (fileInput) fileInput.click();
  }

  function onFileSelected(e) {
    var files = Array.from(e.target.files || []);
    files.forEach(function (f) { uploadFile(f); });
    if (fileInput) fileInput.value = '';
  }

  // ==================== 会话列表 ====================
  const CONV_PAGE_SIZE = 10;
  let convPage = 0;
  let convTotal = 0;
  let convRenderedCount = 0;
  let convHasMore = true;
  let convLoading = false;

  function clearConvLoader() {
    var loader = convList.querySelector('.conv-loader');
    if (loader) loader.remove();
  }

  function ensureConvLoader() {
    var loader = convList.querySelector('.conv-loader');
    if (!loader) {
      loader = document.createElement('div');
      loader.className = 'conv-loader';
      loader.innerHTML = '<div style="text-align:center;padding:12px;color:var(--text-muted);font-size:12px;">加载中...</div>';
      convList.appendChild(loader);
    }
  }

  async function loadConversations(reset) {
    if (reset) {
      convPage = 0;
      convTotal = 0;
      convRenderedCount = 0;
      convHasMore = true;
      clearConvLoader();
    }
    if (convLoading || !convHasMore) return;
    convLoading = true;
    try {
      const requestPage = convPage;
      const data = await Api.get('/api/sessions?page=' + requestPage + '&size=' + CONV_PAGE_SIZE);
      const convs = data.conversations || [];
      convTotal = Number(data.total) || 0;
      convCount.textContent = convTotal;
      if (requestPage === 0) {
        renderConvList(convs);
        convRenderedCount = convs.length;
      } else {
        appendConvList(convs);
        convRenderedCount += convs.length;
      }
      convHasMore = convs.length > 0 && convRenderedCount < convTotal;
      convPage = requestPage + 1;
    } catch (err) {
      if (convPage === 0) {
        convCount.textContent = '0';
        convList.innerHTML = '<div style="text-align:center;padding:20px;color:var(--text-muted);font-size:13px;">暂无历史会话</div>';
      }
    } finally {
      clearConvLoader();
      convLoading = false;
    }
  }

  function renderConvList(convs) {
    if (!convs || convs.length === 0) {
      convList.innerHTML = '<div style="text-align:center;padding:20px;color:var(--text-muted);font-size:13px;">暂无历史会话</div>';
      return;
    }
    convList.innerHTML = convs.map(c =>
      `<div class="conv-item${c.conversationId === conversationId ? ' active' : ''}" data-cid="${c.conversationId}">
        <div class="conv-title">${Utils.escapeHtml(c.title || '新对话')}</div>
        <div class="conv-meta">
          ${(c.streaming || isConvStreaming(c.conversationId)) ? '<span class="conv-live"><i class="fas fa-spinner fa-spin"></i> 生成中</span>' : ''}
          <span>${c.messageCount} 轮</span>
          <span>·</span>
          <span>${Utils.formatTime(c.lastActiveAt)}</span>
        </div>
        <button class="conv-delete" title="删除" data-del="${c.conversationId}">
          <i class="fas fa-trash"></i>
        </button>
      </div>`
    ).join('');
  }

  function appendConvList(convs) {
    if (!convs || convs.length === 0) return;
    var html = convs.map(c =>
      `<div class="conv-item${c.conversationId === conversationId ? ' active' : ''}" data-cid="${c.conversationId}">
        <div class="conv-title">${Utils.escapeHtml(c.title || '新对话')}</div>
        <div class="conv-meta">
          ${(c.streaming || isConvStreaming(c.conversationId)) ? '<span class="conv-live"><i class="fas fa-spinner fa-spin"></i> 生成中</span>' : ''}
          <span>${c.messageCount} 轮</span>
          <span>·</span>
          <span>${Utils.formatTime(c.lastActiveAt)}</span>
        </div>
        <button class="conv-delete" title="删除" data-del="${c.conversationId}">
          <i class="fas fa-trash"></i>
        </button>
      </div>`
    ).join('');
    convList.insertAdjacentHTML('beforeend', html);
  }

  // 滚动加载更多
  convList.addEventListener('scroll', function () {
    if (convLoading || !convHasMore) return;
    var scrolled = convList.scrollTop + convList.clientHeight;
    if (scrolled >= convList.scrollHeight - 40) {
      ensureConvLoader();
      loadConversations(false);
    }
  });

  convList.addEventListener('click', function (e) {
    var deleteBtn = e.target.closest('.conv-delete');
    if (deleteBtn) {
      e.stopPropagation();
      deleteConversation(deleteBtn.dataset.del);
      return;
    }
    var item = e.target.closest('.conv-item');
    if (item && item.dataset.cid) {
      loadConversation(item.dataset.cid);
    }
  });

  // ==================== 调用示例 ====================
  function loadSampleQuestions(resetCursor) {
    // 本平台聊天页未内嵌“调用示例”侧栏（由全局侧栏承载历史会话），跳过请求避免 404
    if (!sampleQuestionsList) return Promise.resolve();
    if (sampleQuestionsLoading) return Promise.resolve();
    if (resetCursor) sampleQuestionsCursor = 0;
    sampleQuestionsLoading = true;
    if (sampleQuestionsRefresh) {
      sampleQuestionsRefresh.disabled = true;
      sampleQuestionsRefresh.classList.add('refreshing');
    }
    var url = '/api/sample-questions/batch?page=' + encodeURIComponent(activeScene.samplePage) +
      '&cursor=' + sampleQuestionsCursor + '&size=' + SAMPLE_BATCH_SIZE;
    return Api.get(url).then(function (batch) {
      var items = batch && Array.isArray(batch.items) ? batch.items : [];
      renderSampleQuestions(items.map(function (item) {
        return { type: item.type || '', item: item };
      }));
      sampleQuestionsCursor = batch && Number.isInteger(batch.nextCursor) ? batch.nextCursor : 0;
      if (sampleQuestionsRefresh) {
        sampleQuestionsRefresh.hidden = !(batch && batch.refreshable);
      }
    }).catch(function () {
      if (sampleQuestionsList) {
        sampleQuestionsList.innerHTML = '<div class="sidebar-empty">暂无示例</div>';
        sampleQuestionsList._sampleItems = [];
      }
      sampleQuestionsCursor = 0;
      if (sampleQuestionsRefresh) sampleQuestionsRefresh.hidden = true;
    }).finally(function () {
      sampleQuestionsLoading = false;
      if (sampleQuestionsRefresh) {
        sampleQuestionsRefresh.disabled = false;
        sampleQuestionsRefresh.classList.remove('refreshing');
      }
    });
  }

  function renderSampleQuestions(entries) {
    var container = sampleQuestionsList;
    if (!container) return;
    if (!entries || entries.length === 0) {
      container.innerHTML = '<div class="sidebar-empty">暂无示例</div>';
      container._sampleItems = [];
      return;
    }

    var html = '';
    var allItems = [];
    var currentType = null;
    for (var i = 0; i < entries.length; i++) {
      var type = entries[i].type;
      var item = entries[i].item;
      if (type !== currentType) {
        if (currentType !== null) html += '</div>';
        html += '<div class="sq-type-group">';
        html += '<div class="sq-type-label">' + Utils.escapeHtml(type) + '</div>';
        currentType = type;
      }
      var sampleIndex = allItems.length;
      var fileCount = (item.files && item.files.length) || 0;
      var fileBadge = '';
      if (fileCount > 0) {
        fileBadge = '<span class="sq-file-badge"><i class="fas fa-paperclip"></i>' + fileCount + '</span>';
      }
      html += '<button type="button" class="sq-item" data-sample-index="' + sampleIndex + '" title="' + Utils.escapeHtml(item.question) + '">' +
        '<span class="sq-item-text">' + Utils.escapeHtml(item.question) + '</span>' +
        fileBadge +
      '</button>';
      allItems.push(item);
    }
    if (currentType !== null) {
      html += '</div>';
    }
    container.innerHTML = html;
    container._sampleItems = allItems;
  }

  async function handleSampleQuestionClick(item) {
    if (isSending) return;
    if (sampleMaterializing) {
      Toast.warning('示例附件准备中，请稍候');
      return;
    }
    if (hasPendingAttachments()) {
      Toast.warning('请先发送或移除当前附件，再使用调用示例');
      return;
    }

    var sampleFiles = Array.isArray(item.files) ? item.files : [];
    if (sampleFiles.length === 0) {
      sendMessage(item.question);
      return;
    }
    if (!item.id) {
      Toast.error('示例缺少编号，无法准备附件');
      return;
    }

    sampleMaterializing = true;
    Toast.info('正在准备示例附件...');
    try {
      var materialized = await Api.post('/api/sample-questions/' + encodeURIComponent(item.id) + '/materialize-files', {});
      var freshFiles = Array.isArray(materialized) ? materialized : [];
      if (freshFiles.length === 0) {
        throw new Error('示例未配置可用附件');
      }
      if (freshFiles.length > MAX_FILES) {
        throw new Error('示例附件数量超过上限（最多 3 个）');
      }
      uploadedFiles = freshFiles.map(function (f) {
        return {
          fileId: f.fileId,
          fileName: f.fileName,
          fileType: f.fileType,
          fileSize: f.fileSize,
          status: 'SUCCESS',
          sampleMaterialized: true,
          previewUrl: isImageFile(f) ? ('/api/file/preview/' + f.fileId) : '',
          url: ''
        };
      });
      renderFileChips();
      await sendMessage(item.question, { preserveAttachmentsOnRequestFailure: true });
    } catch (err) {
      Toast.error('加载示例附件失败：' + err.message);
    } finally {
      sampleMaterializing = false;
    }
  }

  if (sampleQuestionsList) {
    sampleQuestionsList.addEventListener('click', function (e) {
      var itemEl = e.target.closest('.sq-item');
      if (!itemEl || !sampleQuestionsList.contains(itemEl)) return;
      var items = sampleQuestionsList._sampleItems || [];
      var item = items[parseInt(itemEl.dataset.sampleIndex, 10)];
      if (item) {
        handleSampleQuestionClick(item);
      }
    });
  }
  if (sampleQuestionsRefresh) {
    sampleQuestionsRefresh.addEventListener('click', function () {
      loadSampleQuestions(false);
    });
  }

  // ==================== 消息渲染（全量，只在初始和完成后调用） ====================
  function renderAllMessages() {
    clearStreamRefs();
    if (messages.length === 0) {
      chatEmpty.style.display = '';
      chatMessages.innerHTML = '';
      chatMessages.appendChild(chatEmpty);
      return;
    }
    chatEmpty.style.display = 'none';

    let html = '';
    for (const msg of messages) {
      if (msg.role === 'user') {
        html += renderUserMsg(msg);
      } else {
        html += renderAssistantMsg(msg);
      }
    }
    chatMessages.innerHTML = html;

    // 缓存最后一个 AI 消息的 timeline 和 loading DOM 引用
    const aiMsgs = chatMessages.querySelectorAll('.msg-assistant');
    const lastAi = aiMsgs[aiMsgs.length - 1];
    if (lastAi) {
      currentTimelineEl = lastAi.querySelector('.chat-timeline');
      currentLoadingEl = lastAi.querySelector('.loading-dots');
    }
  }

  function renderUserMsg(msg) {
    var attachHtml = '';
    if (msg.attachments && msg.attachments.length > 0) {
      attachHtml = '<div class="da-msg-attachments">' +
        msg.attachments.map(function (a) {
          var isImg = /\.(jpg|jpeg|png|gif|bmp|webp)$/i.test('.' + (a.fileType || ''));
          // 只有图片才有缩略图，非图片一律显示文件图标（照抄 参考实现）
          var thumb = isImg ? (a.previewUrl || '') : '';
          // 构造点击链接：图片用 previewUrl 做弹窗预览，文件用 fileId 构造下载地址
          var clickUrl = '';
          if (isImg) {
            clickUrl = a.previewUrl || a.url || (a.fileId ? '/api/file/preview/' + a.fileId : '');
          } else if (a.fileId) {
            clickUrl = '/api/file/download/' + a.fileId;
          }
          // 构建 class（必须合并为单个属性，否则浏览器只认第一个）
          var extraClass = isImg ? ' image' : '';
          if (clickUrl) extraClass += ' da-attachment-clickable';
          var dataAttr = '';
          if (isImg && clickUrl) {
            dataAttr = ' data-preview="' + Utils.escapeHtml(clickUrl) + '"';
          } else if (!isImg && clickUrl) {
            dataAttr = ' data-url="' + Utils.escapeHtml(clickUrl) + '"';
          }
          return '<div class="da-msg-attachment' + extraClass + '" title="' + Utils.escapeHtml(a.fileName) + '"' + dataAttr + '>' +
            '<div class="da-msg-attachment-thumb">' +
              (thumb ? '<img src="' + thumb + '" alt="" />' : '<i class="fas fa-file-lines"></i>') +
            '</div>' +
            '<span class="da-msg-attachment-name">' + Utils.escapeHtml(a.fileName) + '</span>' +
          '</div>';
        }).join('') +
      '</div>';
    }
    return '<div class="da-msg msg-user">' +
      '<div class="da-avatar da-avatar-user"><i class="fas fa-user"></i></div>' +
      '<div class="da-user-body">' +
        attachHtml +
        (msg.content ? '<div class="da-bubble da-bubble-user">' + Utils.escapeHtml(msg.content) + '</div>' : '') +
      '</div>' +
    '</div>';
  }

  function renderAssistantMsg(msg) {
    return `
      <div class="da-msg msg-assistant">
        <div class="da-avatar da-avatar-ai"><i class="fas fa-robot"></i></div>
        <div class="da-assistant-body">
          <div class="chat-timeline">${renderTimelineItems(msg.timeline)}</div>
          ${msg.loading ? '<div class="loading-dots"><span class="dot"></span><span class="dot"></span><span class="dot"></span></div>' : ''}
        </div>
      </div>`;
  }

  // ==================== 时间线渲染 ====================
  function renderTimelineItems(timeline) {
    if (!timeline || timeline.length === 0) return '';
    return timeline.map((item, i) => renderTimelineItem(item, i, timeline)).join('');
  }

  function renderTimelineItem(item, idx, timeline) {
    switch (item.type) {
      case 'thinking':
        return `
          <div class="da-timeline-item">
            <div class="da-timeline-dot thinking"></div>
            <div class="da-timeline-body">
              <div class="da-thinking thinking-card">
                <div class="da-thinking-label"><i class="fas fa-brain"></i> 思考过程</div>
                <div class="da-thinking-text markdown-body">${Markdown.render(item.content || '')}</div>
              </div>
            </div>
          </div>`;

      case 'text':
        return `
          <div class="da-timeline-item">
            <div class="da-timeline-dot text"></div>
            <div class="da-timeline-body">
              <div class="da-text-item markdown-body text-item">${Markdown.render(item.content || '')}</div>
            </div>
          </div>`;

      case 'tool':
        return `
          <div class="da-timeline-item" data-tool-call-id="${Utils.escapeHtml(item.toolCallId || '')}">
            <div class="da-timeline-dot ${item.status || 'running'}"></div>
            <div class="da-timeline-body">
              <div class="da-tool ${item.status || 'running'}">
                <i class="fas fa-wrench da-tool-icon"></i>
                <span class="da-tool-name">${Utils.escapeHtml(item.toolName || 'unknown')}</span>
                <span class="da-tool-status">
                  ${item.status === 'running' ? '<i class="fas fa-spinner fa-spin"></i>' :
                    item.status === 'completed' ? '<i class="fas fa-check"></i>' :
                    '<i class="fas fa-times"></i>'}
                </span>
                ${(item.arguments || item.result) ? `
                <button class="da-tool-expand" onclick="var d=this.parentElement.querySelector('.da-tool-detail');var s=d.style.display;d.style.display=s==='none'?'block':'none';this.querySelector('span').textContent=s==='none'?'收起':'查看详情';">
                  <i class="fas fa-eye"></i> <span>查看详情</span>
                </button>` : ''}
                ${(item.arguments || item.result) ? `
                <div class="da-tool-detail" style="display:none;">
                  ${item.arguments ? `
                  <div class="da-tool-section-label"><i class="fas fa-sign-out-alt"></i> 入参</div>
                  <pre class="da-tool-args"><code>${Utils.escapeHtml(formatArgs(item.arguments))}</code></pre>` : ''}
                  ${item.result ? `
                  <div class="da-tool-section-label"><i class="fas fa-sign-in-alt"></i> 结果</div>
                  <div class="da-tool-result markdown-body">${Markdown.render(typeof item.result === 'string' ? item.result.substring(0, 2000) : '')}</div>` : ''}
                </div>` : ''}
              </div>
            </div>
          </div>`;

      case 'todo':
        const completedCnt = (item.items || []).filter(t => t.status === 'completed').length;
        const totalCnt = (item.items || []).length;
        return `
          <div class="da-timeline-item">
            <div class="da-timeline-dot todo"></div>
            <div class="da-timeline-body">
              <div class="da-todo-inline">
                <div class="da-todo-inline-header">
                  <i class="fas fa-list-check"></i>
                  <span class="da-todo-inline-title">执行计划</span>
                  <span class="da-todo-count">${completedCnt}/${totalCnt}</span>
                </div>
                <div class="da-todo-inline-body">
                  ${(item.items || []).map(t => `
                    <div class="da-todo-item ${t.status || 'pending'}">
                      <i class="${todoIcon(t.status)}"></i>
                      <span>${Utils.escapeHtml(t.text || t.content || '')}</span>
                    </div>
                  `).join('')}
                </div>
              </div>
            </div>
          </div>`;

      case 'error':
        return `
          <div class="da-timeline-item">
            <div class="da-timeline-dot error"></div>
            <div class="da-timeline-body">
              <div class="da-error-item">
                <i class="fas fa-exclamation-triangle"></i>
                <div class="da-error-body">
                  <div class="da-error-msg">${Utils.escapeHtml(item.message || '未知错误')}</div>
                  ${item.detail ? `<div class="da-error-detail">${Utils.escapeHtml(item.detail)}</div>` : ''}
                </div>
              </div>
            </div>
          </div>`;

      default:
        return '';
    }
  }

  function formatArgs(argStr) {
    if (!argStr) return '';
    try { return JSON.stringify(JSON.parse(argStr), null, 2); } catch (e) { return argStr; }
  }

  function todoIcon(status) {
    if (status === 'completed') return 'fas fa-check-circle';
    if (status === 'in_progress') return 'fas fa-circle-half-stroke';
    return 'far fa-circle';
  }

  // ==================== 时间线增量 DOM 操作（流式更新用） ====================
  function appendTimelineItemHTML(html) {
    if (!currentTimelineEl) return null;
    currentTimelineEl.insertAdjacentHTML('beforeend', html);
    const items = currentTimelineEl.querySelectorAll('.da-timeline-item');
    return items[items.length - 1];
  }

  // rAF 节流：流式 chunk 高频到达，每帧最多渲染一次，避免频繁全量重渲染
  let streamRenderPending = false;

  function scheduleStreamRender(fn) {
    if (streamRenderPending) return;
    streamRenderPending = true;
    requestAnimationFrame(() => {
      streamRenderPending = false;
      fn();
    });
  }

  function updateLastTextContent(aiMsg) {
    // 找到最后一个 text timeline item 并更新其内容
    if (!currentTimelineEl) return;
    const textItems = currentTimelineEl.querySelectorAll('.da-text-item');
    const lastText = textItems[textItems.length - 1];
    if (lastText) {
      lastText.innerHTML = Markdown.render(aiMsg.timeline[aiMsg.timeline.length - 1].content);
      if (!lastText.classList.contains('is-streaming')) {
        lastText.classList.add('is-streaming');
      }
      if (typeof hljs !== 'undefined') {
        lastText.querySelectorAll('pre code').forEach(block => { hljs.highlightElement(block); });
      }
    }
  }

  function updateLastThinkingContent(aiMsg) {
    if (!currentTimelineEl) return;
    const thinkingTexts = currentTimelineEl.querySelectorAll('.da-thinking-text');
    const lastThinking = thinkingTexts[thinkingTexts.length - 1];
    if (lastThinking) {
      // 取最后一个 thinking 块的内容（而不是 timeline 最后一项，避免 rAF 延迟后拿到 text 的内容）
      let thinkingContent = '';
      for (let i = aiMsg.timeline.length - 1; i >= 0; i--) {
        if (aiMsg.timeline[i].type === 'thinking') {
          thinkingContent = aiMsg.timeline[i].content;
          break;
        }
      }
      lastThinking.innerHTML = Markdown.render(thinkingContent);
      lastThinking.scrollTop = lastThinking.scrollHeight;
    }
  }

  // ==================== AgentX 事件处理（严格照抄 参考实现 processAgentxEvent） ====================
  const inferTodoStatus = (todo, completedTools) => {
    if (todo.status === 'completed') return 'completed';
    const text = (todo.text || todo.content || todo.task || '').toLowerCase();
    for (const mapping of TODO_TOOL_MAP) {
      if (mapping.keys.some(k => text.includes(k.toLowerCase()))) {
        if (mapping.tools.some(t => completedTools.has(t))) return 'completed';
      }
    }
    return todo.status || 'pending';
  };

  const buildTodoSnapshot = (items, aiMsg) => {
    const completedTools = new Set(
      aiMsg.timeline
        .filter(it => it.type === 'tool' && it.status === 'completed')
        .map(it => it.toolName)
    );
    return items.map(it => {
      const todo = { text: it.text || it.content || it.task || '', status: it.status || 'pending' };
      if (todo.status !== 'completed') todo.status = inferTodoStatus(todo, completedTools);
      return todo;
    });
  };

  // 解析 TodoWrite 工具的 todos 参数为 todo 面板需要的 items。
  // 参数可能是 {"todos":[{content,status}]}（Spring AI 包一层）或直接 [{content,status}]
  function parseTodoArgs(argumentsStr) {
    if (!argumentsStr) return [];
    try {
      const parsed = JSON.parse(argumentsStr);
      const arr = Array.isArray(parsed)
        ? parsed
        : (parsed && Array.isArray(parsed.todos) ? parsed.todos : null);
      if (!Array.isArray(arr)) return [];
      return arr.map(t => ({ text: t.content || t.text || '', status: t.status || 'pending' }));
    } catch (e) {
      return [];
    }
  }

  // 结束 loading 态的统一 UI 处理：隐藏加载动画 + 移除最后一个 text 的 streaming 光标
  function finishLoadingUI() {
    if (currentLoadingEl) currentLoadingEl.style.display = 'none';
    if (currentTimelineEl) {
      const allText = currentTimelineEl.querySelectorAll('.da-text-item');
      if (allText.length) allText[allText.length - 1].classList.remove('is-streaming');
    }
  }

  // 把 timeline 里还在 running 的工具卡片标记为 failed（用户停止/中断时）
  function markRunningToolsFailed(aiMsg) {
    let changed = false;
    for (const item of aiMsg.timeline) {
      if (item.type === 'tool' && item.status === 'running') {
        item.status = 'failed';
        changed = true;
      }
    }
    return changed;
  }

  function processAgentxEvent(event, aiMsg, visible) {
    if (!event || !SUPPORTED_EVENTS.has(event.type)) return;
    // visible=false 表示这是后台会话的流（用户切走了）：只改数据，不碰当前页面的 DOM 引用
    switch (event.type) {
      case EVENTS.AGENT_START:
        aiMsg.loading = true;
        if (visible && currentLoadingEl) currentLoadingEl.style.display = '';
        break;

      case EVENTS.THINKING: {
        const content = event.content || '';
        if (!content) break;
        const last = aiMsg.timeline[aiMsg.timeline.length - 1];
        if (last && last.type === 'thinking') {
          last.content += content;
          if (visible) scheduleStreamRender(() => updateLastThinkingContent(aiMsg));
        } else {
          aiMsg.timeline.push({ type: 'thinking', content });
          if (visible) {
            const newItem = renderTimelineItem(
              { type: 'thinking', content },
              aiMsg.timeline.length - 1,
              aiMsg.timeline
            );
            appendTimelineItemHTML(newItem);
          }
        }
        break;
      }

      case EVENTS.TEXT: {
        const content = event.content || '';
        if (!content) break;
        const last = aiMsg.timeline[aiMsg.timeline.length - 1];
        if (last && last.type === 'text') {
          last.content += content;
          if (visible) updateLastTextContent(aiMsg);
        } else {
          aiMsg.timeline.push({ type: 'text', content });
          if (visible) {
            const newItem = renderTimelineItem(
              { type: 'text', content },
              aiMsg.timeline.length - 1,
              aiMsg.timeline
            );
            appendTimelineItemHTML(newItem);
          }
        }
        break;
      }

      case EVENTS.TOOL_START:
        if ((event.toolName || '').toLowerCase() === 'todowrite') {
          // TodoWrite：框架里就是普通工具（无 TodoProgress 事件），解析 todos 参数渲染成执行计划面板
          const todoItems = parseTodoArgs(event.arguments);
          if (todoItems.length > 0) {
            const todoItem = { type: 'todo', items: todoItems };
            aiMsg.timeline.push(todoItem);
            if (visible) {
              const html = renderTimelineItem(todoItem, aiMsg.timeline.length - 1, aiMsg.timeline);
              appendTimelineItemHTML(html);
            }
          }
          break;
        }
        {
          const toolItem = {
            type: 'tool',
            toolName: event.toolName || 'unknown',
            toolCallId: event.toolCallId || '',
            arguments: event.arguments || '',
            status: 'running',
            result: '',
            showResult: false
          };
          aiMsg.timeline.push(toolItem);
          if (visible) {
            const html = renderTimelineItem(toolItem, aiMsg.timeline.length - 1, aiMsg.timeline);
            appendTimelineItemHTML(html);
          }
        }
        break;

      case EVENTS.TOOL_END: {
        if ((event.toolName || '').toLowerCase() === 'todowrite') break;
        const toolCallId = event.toolCallId || '';
        const entry = aiMsg.timeline.find(it =>
          it.type === 'tool' && it.toolCallId === toolCallId && it.status === 'running'
        );
        if (entry) {
          entry.status = 'completed';
          entry.result = event.result || '';
          if (visible && currentTimelineEl) {
            const idx = aiMsg.timeline.indexOf(entry);
            const el = currentTimelineEl.querySelector(`[data-tool-call-id="${toolCallId}"]`);
            if (el) {
              // 只替换刚完成的这个工具卡片，避免整块重建关闭用户已展开的其它详情
              el.outerHTML = renderTimelineItem(entry, idx, aiMsg.timeline);
            } else {
              currentTimelineEl.innerHTML = renderTimelineItems(aiMsg.timeline);
            }
          }
        } else {
          const newItem = {
            type: 'tool', toolName: event.toolName || 'unknown', toolCallId,
            arguments: '', status: 'completed', result: event.result || '', showResult: false
          };
          aiMsg.timeline.push(newItem);
          if (visible) {
            const html = renderTimelineItem(newItem, aiMsg.timeline.length - 1, aiMsg.timeline);
            appendTimelineItemHTML(html);
          }
        }
        break;
      }

      case EVENTS.TODO_PROGRESS: {
        const items = Array.isArray(event.items) ? event.items : [];
        const todoItem = { type: 'todo', items: buildTodoSnapshot(items, aiMsg) };
        aiMsg.timeline.push(todoItem);
        if (visible) {
          const html = renderTimelineItem(todoItem, aiMsg.timeline.length - 1, aiMsg.timeline);
          appendTimelineItemHTML(html);
        }
        break;
      }

      case EVENTS.STAGE_OUTPUT:
        break;

      case EVENTS.ERROR:
        {
          const errItem = { type: 'error', message: event.message || '未知错误', detail: event.detail || '' };
          aiMsg.timeline.push(errItem);
          if (visible) {
            const html = renderTimelineItem(errItem, aiMsg.timeline.length - 1, aiMsg.timeline);
            appendTimelineItemHTML(html);
          }
        }
        break;

      case EVENTS.COMPLETE:
        aiMsg.loading = false;
        if (visible) { finishLoadingUI(); clearStreamRefs(); }
        break;

      case EVENTS.PAUSED:
        // 用户主动中断：结束 loading 态 + 把 running 的工具卡片标记为 failed
        aiMsg.loading = false;
        if (visible) {
          if (markRunningToolsFailed(aiMsg) && currentTimelineEl) {
            currentTimelineEl.innerHTML = renderTimelineItems(aiMsg.timeline);
          }
          finishLoadingUI();
          clearStreamRefs();
        }
        break;

      default:
        break;
    }
  }

  // ==================== 发送消息 / SSE（照抄 参考实现 sendMessage） ====================
  // ==================== 断流续传（对齐 参考实现） ====================
  // 进行中的流（按 conversationId）：切会话/新建会话不中断，服务端继续执行；
  // 游标（最后收到的事件序号）持久化到 localStorage，关页重开也能续
  const liveStreams = new Map();   // convId -> { aiMsg, controller, lastSeq, retries, question }
  const seqKey = (cid) => 'agentx-stream-seq-' + cid;

  function persistSeq(cid, seq) {
    try { localStorage.setItem(seqKey(cid), String(seq)); } catch (e) { /* ignore */ }
  }
  function clearSeq(cid) {
    try { localStorage.removeItem(seqKey(cid)); } catch (e) { /* ignore */ }
  }

  /** 按是否流式设置发送按钮（停止/发送）与 isSending，全站唯一入口。 */
  function setSendButton(streaming) {
    isSending = streaming;
    if (streaming) {
      sendBtn.innerHTML = '<i class="fas fa-stop"></i>';
      sendBtn.classList.add('stop');
    } else {
      sendBtn.innerHTML = '<i class="fas fa-paper-plane"></i>';
      sendBtn.classList.remove('stop');
    }
  }

  /** 流结束（完成/停止/彻底断开）：清理注册表和游标，恢复界面状态。 */
  function finishStream(cid) {
    const entry = liveStreams.get(cid);
    liveStreams.delete(cid);
    clearSeq(cid);
    if (entry) entry.aiMsg.loading = false;
    if (cid === conversationId) setSendButton(false);
  }

  function recoveryBody(cid, startSeq) {
    return { conversationId: cid, recovery: true, startSeq: startSeq };
  }

  /** 消费 SSE 响应：逐行解析 id:/data:，事件交给 handleStreamEvent。 */
  async function consumeStream(resp, cid, entry) {
    const reader = resp.body.getReader();
    const decoder = new TextDecoder('utf-8');
    let buffer = '';
    let pendingId = '';
    const handleLine = (rawLine) => {
      const line = rawLine.trim();
      if (!line) return;
      if (line.startsWith('id:')) { pendingId = line.slice(3).trim(); return; }
      if (line.startsWith('data:')) {
        const jsonStr = line.slice(5).trim();
        if (!jsonStr) return;
        try {
          handleStreamEvent(cid, entry, JSON.parse(jsonStr), pendingId);
        } catch (e) { /* ignore parse errors */ }
        pendingId = '';
      }
    };
    while (true) {
      const { done, value } = await reader.read();
      if (done) break;
      buffer += decoder.decode(value, { stream: true });
      let idx;
      while ((idx = buffer.indexOf('\n')) !== -1) {
        const line = buffer.slice(0, idx);
        buffer = buffer.slice(idx + 1);
        handleLine(line);
      }
    }
    if (buffer.trim()) handleLine(buffer);
  }

  /** 事件处理：记录游标、按序号防重、分发（仅当前会话可见时更新 DOM）。 */
  function handleStreamEvent(cid, entry, event, eventId) {
    if (eventId) {
      const seq = Number(eventId);
      if (seq <= entry.lastSeq) return;   // 补发重叠时丢掉已收到的事件
      entry.lastSeq = seq;
      persistSeq(cid, seq);
    }
    if (event && event.type) {
      processAgentxEvent(event, entry.aiMsg, cid === conversationId);
    }
  }

  /** 出错处理：404 视为已结束；流已建立过则退避重连；否则按失败处理。 */
  function handleStreamError(cid, entry, err) {
    console.error('[agent-chat] 流式请求失败', err);
    if (err && err.message && err.message.includes('HTTP 404')) {
      finishStream(cid);
      if (cid === conversationId) loadConversation(cid);
      return;
    }
    if (entry.lastSeq > 0 && entry.retries < 3) {
      entry.retries++;
      setTimeout(() => resumeStream(cid, entry), 1000 * entry.retries);
      return;
    }
    cleanupPendingMaterializedFiles(inFlightMaterializedFiles, false);
    inFlightMaterializedFiles = [];
    entry.aiMsg.timeline.push({ type: 'error', message: '连接中断', detail: err.message || '' });
    finishStream(cid);
    if (cid === conversationId) { renderAllMessages(); loadConversations(true); }
  }

  /** 断线后接回：recovery + startSeq 从上次收到的下一个事件增量续传。 */
  async function resumeStream(cid, entry) {
    if (liveStreams.get(cid) !== entry) return;
    entry.controller = new AbortController();
    const token = Auth.getToken();
    try {
      const resp = await fetch('/api/agent/stream', {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          'satoken': token,
          'Accept': 'text/event-stream',
          'Cache-Control': 'no-cache'
        },
        body: JSON.stringify(recoveryBody(cid, entry.lastSeq + 1)),
        signal: entry.controller.signal
      });
      if (!resp.ok) {
        let errMsg = 'HTTP ' + resp.status;
        try { const d = await resp.json(); errMsg = d.msg || errMsg; } catch (e) {}
        throw new Error(errMsg);
      }
      await consumeStream(resp, cid, entry);
      entry.aiMsg.loading = false;
      finishStream(cid);
      if (cid === conversationId) { finishLoadingUI(); clearStreamRefs(); }
      loadConversations(true);
    } catch (err) {
      if (err && err.name === 'AbortError') {
        if (markRunningToolsFailed(entry.aiMsg) && cid === conversationId) {
          renderAllMessages();
        }
        finishStream(cid);
        return;
      }
      handleStreamError(cid, entry, err);
    }
  }

  /** 该会话是否有进行中的流（侧栏"生成中"标记）。 */
  function isConvStreaming(cid) { return liveStreams.has(cid); }

  /** 切换到指定会话：更新当前会话 id、地址栏。 */
  function switchToConversation(convId) {
    conversationId = convId;
    const u = new URL(window.location.href);
    normalizeSceneInUrl(u);
    u.searchParams.set('conversationId', convId);
    window.history.replaceState({}, '', u.toString());
  }

  /**
   * 探测该会话是否有进行中的流（刷新/关页重开/点击历史会话）。
   * 有则 recovery 请求从第 1 个事件全量补发（本地没有渲染状态）。
   */
  async function tryResumeOnLoad(cid) {
    if (liveStreams.has(cid)) return;   // 本地已有活跃流
    let active = false;
    try {
      const st = await Api.get('/api/agent/stream/status?conversationId=' + encodeURIComponent(cid));
      active = !!(st && st.active);
    } catch (e) { /* ignore */ }
    if (!active) { clearSeq(cid); return; }
    // 历史里这轮（进行中的流）的半条助手占位整条去掉，换成接流的实时消息
    const lastMsg = messages[messages.length - 1];
    if (lastMsg && lastMsg.role === 'assistant') messages.pop();
    const aiMsg = { id: generateId(), role: 'assistant', timeline: [], loading: true, timestamp: Date.now() };
    messages.push(aiMsg);
    renderAllMessages();
    const entry = { aiMsg, question: '', controller: new AbortController(), lastSeq: 0, retries: 0 };
    liveStreams.set(cid, entry);
    if (cid === conversationId) setSendButton(true);
    resumeStream(cid, entry);
  }

  /** 发送瞬间刷新侧栏：服务端开局即落库，刷新通常立刻就有；否则本地补一条。 */
  async function syncConversationEntry(cid, title) {
    await loadConversations(true);
    if (Array.from(convList.querySelectorAll('.conv-item')).some(el => el.dataset.cid === cid)) return;
    const item = document.createElement('div');
    item.className = 'conv-item active';
    item.dataset.cid = cid;
    item.innerHTML =
      '<div class="conv-title">' + Utils.escapeHtml(title && title.length > 40 ? title.slice(0, 40) + '...' : (title || '新对话')) + '</div>' +
      '<div class="conv-meta"><span class="conv-live"><i class="fas fa-spinner fa-spin"></i> 生成中</span>' +
      '<span>1 轮</span><span>·</span><span>刚刚</span></div>';
    convList.prepend(item);
  }

  async function sendMessage(presetText, options) {
    const text = (presetText !== undefined ? presetText : chatInput.value).toString().trim();
    var hasFiles = uploadedFiles.some(function (f) { return f.status === 'SUCCESS' && f.fileId; });
    if ((!text && !hasFiles) || liveStreams.has(conversationId)) return;
    var displayText = text || '请帮我分析上传的文件';
    var preserveAttachmentsOnRequestFailure = !!(options && options.preserveAttachmentsOnRequestFailure);
    var requestConversationId = conversationId;

    if (presetText === undefined) {
      chatInput.value = '';
      chatInput.style.height = 'auto';
    }

    // 把已上传成功的文件作为附件贴到该条用户消息上（照抄 参考实现）
    var successFiles = uploadedFiles
      .filter(function (f) { return f.status === 'SUCCESS' && f.fileId; })
      .map(function (f) {
        return {
          fileId: f.fileId,
          fileName: f.fileName,
          fileType: f.fileType,
          fileSize: f.fileSize,
          sampleMaterialized: !!f.sampleMaterialized,
          previewUrl: f.previewUrl || '',
          url: f.url || ''
        };
      });
    inFlightMaterializedFiles = successFiles.filter(function (f) {
      return f.sampleMaterialized;
    });

    // 添加用户消息
    var userMsg = {
      id: generateId(), role: 'user', content: displayText,
      attachments: successFiles.length > 0 ? successFiles : undefined,
      timestamp: Date.now()
    };
    messages.push(userMsg);

    // 清空输入框上的文件卡片
    uploadedFiles = [];
    isUploading = false;
    renderFileChips();

    // 添加 AI 占位消息（纯时间线模式 — 照抄 参考实现）
    var aiMsg = {
      id: generateId(), role: 'assistant', timeline: [],
      loading: true, timestamp: Date.now()
    };
    messages.push(aiMsg);

    setSendButton(true);

    // 全量渲染一次，获取 DOM 引用
    renderAllMessages();

    // 注册进行中的流：切会话/新建会话不再中断它
    const cid = conversationId;
    const entry = { aiMsg, question: displayText, controller: new AbortController(), lastSeq: 0, retries: 0 };
    liveStreams.set(cid, entry);
    // 发起的瞬间就让侧栏出现这个会话（带"生成中"标记），方便切走后再切回来
    syncConversationEntry(cid, displayText);

    try {
      const resp = await fetch('/api/agent/stream', {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          'satoken': Auth.getToken(),
          'Accept': 'text/event-stream',
          'Cache-Control': 'no-cache'
        },
        body: JSON.stringify({
          query: text,
          conversationId: conversationId,
          fileIds: successFiles.length > 0 ? successFiles.map(function (f) { return f.fileId; }) : undefined
        }),
        signal: entry.controller.signal
      });

      if (!resp.ok) {
        let errMsg = 'HTTP ' + resp.status;
        try { const d = await resp.json(); errMsg = d.msg || errMsg; } catch (e) {}
        throw new Error(errMsg);
      }

      await consumeStream(resp, cid, entry);

      entry.aiMsg.loading = false;
      finishStream(cid);
      if (cid === conversationId) { finishLoadingUI(); clearStreamRefs(); }
      // 框架已自动持久化 timeline，只需刷新侧栏历史列表（照抄 参考实现）
      loadConversations(true);
      inFlightMaterializedFiles = [];
    } catch (err) {
      if (err && err.name === 'AbortError') {
        // 用户点停止：stopMessage 已通知后端中断，这里收尾 + 把 running 工具标记为失败
        if (markRunningToolsFailed(entry.aiMsg) && cid === conversationId) {
          renderAllMessages();
        }
        finishStream(cid);
        if (cid === conversationId) { finishLoadingUI(); clearStreamRefs(); }
        loadConversations(true);
        return;
      }
      // 首发失败且带示例附件：保留附件便于重试
      if (entry.lastSeq === 0 && preserveAttachmentsOnRequestFailure && successFiles.length > 0) {
        if (conversationId === requestConversationId) {
          messages = messages.filter(function (msg) { return msg !== userMsg && msg !== aiMsg; });
          uploadedFiles = successFiles.map(function (f) {
            return {
              fileId: f.fileId,
              fileName: f.fileName,
              fileType: f.fileType,
              fileSize: f.fileSize,
              status: 'SUCCESS',
              sampleMaterialized: !!f.sampleMaterialized,
              previewUrl: f.previewUrl || '',
              url: f.url || ''
            };
          });
          inFlightMaterializedFiles = [];
          renderFileChips();
          renderAllMessages();
          Toast.error('发送失败，示例附件已保留，可直接重试：' + err.message);
          finishStream(cid);
          return;
        }
      }
      handleStreamError(cid, entry, err);
    }
  }

  async function stopMessage() {
    // 对齐 参考实现：先 await 后端 interrupt 完成（doFinally 摘除注册表），再 abort 本地 SSE，
    // 这样 abort 触发的刷新能读到最新 streaming=false，不会卡在"生成中"
    const entry = liveStreams.get(conversationId);
    if (!entry) return;
    try {
      await fetch('/api/agent/stop?conversationId=' + encodeURIComponent(conversationId), {
        headers: { 'satoken': Auth.getToken() }
      });
    } catch (e) { /* ignore */ }
    if (entry.controller && liveStreams.get(conversationId) === entry) entry.controller.abort();
  }

  function newConversation() {
    // 不再中断进行中的流：它留在 liveStreams 里后台继续，切回原会话接着看
    conversationId = 'conv_' + Date.now() + '_' + Math.random().toString(36).slice(2, 8);
    setSendButton(false);
    messages = [];
    cleanupPendingMaterializedFiles(uploadedFiles, false);
    cleanupPendingMaterializedFiles(inFlightMaterializedFiles, false);
    inFlightMaterializedFiles = [];
    // 释放本地 object URL
    uploadedFiles.forEach(function (f) {
      if (f.previewUrl) { try { URL.revokeObjectURL(f.previewUrl); } catch (e) { /* ignore */ } }
    });
    uploadedFiles = [];
    isUploading = false;
    renderFileChips();
    clearStreamRefs();
    chatEmpty.style.display = '';
    chatMessages.innerHTML = '';
    chatMessages.appendChild(chatEmpty);
    var u = new URL(window.location.href);
    normalizeSceneInUrl(u);
    u.searchParams.set('conversationId', conversationId);
    window.history.replaceState({}, '', u.toString());
    chatInput.focus();
  }

  window.addEventListener('pagehide', function () {
    cleanupPendingMaterializedFiles(uploadedFiles, true);
    cleanupPendingMaterializedFiles(inFlightMaterializedFiles, true);
  });

  // ==================== 历史会话（照抄 参考实现 loadConversation） ====================
  async function loadConversation(convId) {
    if (chatLoading) chatLoading.style.display = '';
    if (chatEmpty) chatEmpty.style.display = 'none';
    if (chatMessages) chatMessages.style.display = 'none';
    try {
      const res = await fetch('/api/sessions/' + encodeURIComponent(convId), {
        headers: { 'satoken': Auth.getToken() }
      });
      if (!res.ok) return;
      const body = await res.json();
      const data = body && body.data ? body.data : (body || {});
      if (!data.messages) {
        // 服务端记录还没可见（首轮刚发起）：本地有活跃流就直接切过去接着看
        const live = liveStreams.get(convId);
        if (live) {
          messages = [];
          if (live.question) {
            messages.push({ id: 'live_u_' + Date.now(), role: 'user', content: live.question, timestamp: Date.now() });
          }
          messages.push(live.aiMsg);
          switchToConversation(convId);
          renderAllMessages();
          renderConvListFromCurrent();
          setSendButton(true);
        } else {
          // 空会话（无消息记录、无进行中流）：回到欢迎态，展示问候语与推荐问题
          messages = [];
          switchToConversation(convId);
          renderAllMessages();
          if (chatMessages) chatMessages.style.display = '';
          setSendButton(false);
        }
        return;
      }

      const loaded = [];
      for (const round of data.messages) {
        // 恢复附件数据（照抄 参考实现：图片用 fileId 构造预览地址）
        var attachments = (round.attachments || []).map(function (a) {
          var isImg = /\.(jpg|jpeg|png|gif|bmp|webp)$/i.test('.' + (a.fileType || ''));
          return {
            fileId: a.fileId,
            fileName: a.fileName,
            fileType: a.fileType,
            fileSize: a.fileSize,
            previewUrl: isImg ? ('/api/file/preview/' + a.fileId) : '',
            url: ''
          };
        });
        loaded.push({
          id: 'hist_u_' + round.id, role: 'user', content: round.question || '',
          attachments: attachments.length > 0 ? attachments : undefined,
          timestamp: Date.now()
        });

        // 解析框架原生 timeline
        let items = [];
        if (round.timeline) {
          try {
            const parsed = JSON.parse(round.timeline);
            items = Array.isArray(parsed) ? parsed : [];
          } catch (e) {}
        }
        // 工具条目重置 showResult
        items = items.map(it => it.type === 'tool' ? { ...it, showResult: false } : it);
        // 旧数据降级
        if (items.length === 0 && round.answer) {
          items.push({ type: 'text', content: round.answer });
        }

        loaded.push({
          id: 'hist_a_' + round.id, role: 'assistant',
          timeline: items, loading: false, timestamp: Date.now()
        });
      }

      // 该会话有进行中的流（切走再切回来）：最后一轮就是这条流，历史里它那半条
      // 助手占位（可能已写了一半时间线）整条去掉，换成实时消息，避免出现两个回复
      const live = liveStreams.get(convId);
      if (live) {
        const lastMsg = loaded[loaded.length - 1];
        if (lastMsg && lastMsg.role === 'assistant') loaded.pop();
        loaded.push(live.aiMsg);
        setSendButton(true);
      } else {
        setSendButton(false);
        // 本地没有流（新开页面/别的窗口发起）：探测服务端是否有进行中的流，有则接回
        tryResumeOnLoad(convId);
      }

      messages = loaded;
      switchToConversation(convId);
      renderAllMessages();
      if (chatMessages) chatMessages.style.display = '';
      renderConvListFromCurrent();
      chatInput.focus();
    } catch (err) {
      Toast.error('加载会话失败: ' + err.message);
      if (chatMessages) chatMessages.style.display = '';
      renderAllMessages();
    } finally {
      if (chatLoading) chatLoading.style.display = 'none';
    }
  }

  function renderConvListFromCurrent() {
    convList.querySelectorAll('.conv-item').forEach(el => {
      el.classList.toggle('active', el.dataset.cid === conversationId);
    });
  }

  async function deleteConversation(convId) {
    if (!(await window.MaaS.Modal.confirm({ title: '删除会话', message: '确认删除该会话？', confirmText: '删除', danger: true }))) return;
    try {
      await Api.del('/api/sessions/' + encodeURIComponent(convId));
      if (conversationId === convId) newConversation();
      await loadConversations(true);
      Toast.success('已删除');
    } catch (err) {
      Toast.error('删除失败: ' + err.message);
    }
  }

  // ==================== Event Listeners ====================
  document.getElementById('newChatBtn').addEventListener('click', newConversation);

  if (chatSuggestions) {
    chatSuggestions.addEventListener('click', function (e) {
      var btn = e.target.closest('.chat-suggestion-item');
      if (btn && chatSuggestions.contains(btn)) {
        sendMessage(btn.dataset.text);
      }
    });
  }

  sendBtn.addEventListener('click', function () {
    if (liveStreams.has(conversationId)) stopMessage();
    else sendMessage();
  });

  chatInput.addEventListener('keydown', function (e) {
    if (e.key === 'Enter' && !e.shiftKey) {
      e.preventDefault();
      sendMessage();
    }
  });

  chatInput.addEventListener('input', function () {
    this.style.height = 'auto';
    this.style.height = Math.min(this.scrollHeight, 200) + 'px';
  });

  // 文件上传事件
  if (uploadBtn) {
    uploadBtn.addEventListener('click', triggerFileUpload);
  }
  if (fileInput) {
    fileInput.addEventListener('change', onFileSelected);
  }

  // 输入区拖拽上传
  if (chatInputWrap) {
    chatInputWrap.addEventListener('dragover', function (e) {
      e.preventDefault();
      chatInputWrap.classList.add('dragging');
    });
    chatInputWrap.addEventListener('dragleave', function () {
      chatInputWrap.classList.remove('dragging');
    });
    chatInputWrap.addEventListener('drop', function (e) {
      e.preventDefault();
      chatInputWrap.classList.remove('dragging');
      var files = Array.from(e.dataTransfer.files || []);
      files.forEach(function (f) { uploadFile(f); });
    });
  }

  // 附件点击（事件委托）：图片弹出预览弹层，文件新标签打开下载（照抄 参考实现）
  chatMessages.addEventListener('click', function (e) {
    var attach = e.target.closest('.da-attachment-clickable');
    if (!attach) return;
    // 图片：弹出预览弹层
    if (attach.dataset.preview) {
      var previewEl = document.getElementById('imagePreview');
      var previewImg = document.getElementById('imagePreviewImg');
      if (previewEl && previewImg) {
        previewImg.src = attach.dataset.preview;
        previewEl.style.display = 'flex';
      }
      return;
    }
    // 非图片文件：新标签打开下载
    if (attach.dataset.url) {
      window.open(attach.dataset.url, '_blank');
    }
  });

  // 标记 agent-chat 已接管本页（common.js 侧栏兜底逻辑让位）
  window.__AGENT_CHAT__ = true;

  // ==================== 初始化 ====================
  applySceneContent();
  Markdown.init();
  loadConversations(true);
  loadSampleQuestions(true);

  // URL 带了 conversationId → 恢复该会话
  const url = new URL(window.location.href);
  const urlConvId = url.searchParams.get('conversationId');
  normalizeSceneInUrl(url);
  url.searchParams.set('conversationId', conversationId);
  window.history.replaceState({}, '', url.toString());
  if (urlConvId && urlConvId === conversationId) {
    // loadConversation 内部会探测进行中的流并接回（tryResumeOnLoad）
    loadConversation(urlConvId);
  }

  chatInput.focus();
})();
