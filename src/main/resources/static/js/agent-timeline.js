(function () {
  'use strict';

  const TYPES = ['AgentStart', 'Thinking', 'Text', 'ToolStart', 'ToolEnd',
    'TodoProgress', 'StageOutput', 'Paused', 'Error', 'Complete'];

  function create(container) {
    const { Markdown, Utils } = window.MaaS;
    let events = [];
    let completed = false;
    let renderedNodes = [];
    let renderedSigs = [];

    function reset(initialEvents) {
      events = [];
      completed = false;
      renderedNodes = [];
      renderedSigs = [];
      container.innerHTML = '<div class="da-timeline"></div><div class="analysis-loading"><span></span><span></span><span></span></div>';
      (initialEvents || []).forEach(process);
    }

    function process(event) {
      if (!event || !TYPES.includes(event.type)) return;
      events.push(event);
      if (event.type === 'Complete') completed = true;
      render();
    }

    // 增量渲染：只更新变化/新增的条目，避免流式时全量重建 DOM 导致闪白
    function render() {
      const timeline = container.querySelector('.da-timeline');
      if (!timeline) return;
      const items = normalize(events);
      for (let i = 0; i < items.length; i++) {
        const item = items[i];
        const signature = itemSignature(item);
        if (i < renderedNodes.length && renderedSigs[i] === signature) continue;
        if (i < renderedNodes.length && (item.kind === 'text' || item.kind === 'thinking')) {
          // 流式追加（对齐 agent-chat）：外层节点不动，只更新内容区 innerHTML，避免块级闪白
          const contentEl = renderedNodes[i].querySelector(item.kind === 'text' ? '.da-text-item' : '.da-thinking-text');
          if (contentEl) {
            contentEl.innerHTML = Markdown.render(item.content);
            renderedSigs[i] = signature;
            continue;
          }
        }
        const wrapper = document.createElement('div');
        wrapper.innerHTML = itemHtml(item, Markdown, Utils);
        const node = wrapper.firstElementChild;
        if (!node) continue;
        if (i < renderedNodes.length) {
          preserveToolExpansion(renderedNodes[i], node);
          renderedNodes[i].replaceWith(node);
          renderedNodes[i] = node;
          renderedSigs[i] = signature;
        } else {
          timeline.appendChild(node);
          renderedNodes.push(node);
          renderedSigs.push(signature);
        }
        bindToolToggle(node);
        highlight(node);
      }
      const loading = container.querySelector('.analysis-loading');
      if (loading) loading.style.display = completed ? 'none' : 'flex';
    }

    function itemSignature(item) {
      switch (item.kind) {
        case 'thinking':
        case 'text':
          return item.kind + '|' + item.content.length;
        case 'tool':
          return 'tool|' + item.id + '|' + item.status + '|' + item.args.length + '|' + item.result.length;
        case 'todo':
          return 'todo|' + item.items.length;
        case 'stage':
          return 'stage|' + item.name;
        case 'error':
          return 'error|' + item.message + '|' + item.detail.length;
        default:
          return item.kind;
      }
    }

    function preserveToolExpansion(oldNode, newNode) {
      const oldDetail = oldNode.querySelector('.da-tool-detail');
      if (!oldDetail || oldDetail.hidden) return;
      const detail = newNode.querySelector('.da-tool-detail');
      const button = newNode.querySelector('[data-tool-toggle]');
      if (!detail || !button) return;
      detail.hidden = false;
      button.setAttribute('aria-expanded', 'true');
      button.title = '收起工具详情';
      const icon = button.querySelector('i');
      if (icon) {
        icon.classList.remove('fa-chevron-down');
        icon.classList.add('fa-chevron-up');
      }
    }

    function bindToolToggle(scope) {
      scope.querySelectorAll('[data-tool-toggle]').forEach(button => {
        button.onclick = function () {
          const detail = this.closest('.da-tool').querySelector('.da-tool-detail');
          if (!detail) return;
          const expanded = detail.hidden;
          detail.hidden = !expanded;
          this.setAttribute('aria-expanded', String(expanded));
          this.title = expanded ? '收起工具详情' : '查看工具详情';
          const icon = this.querySelector('i');
          if (icon) {
            icon.classList.toggle('fa-chevron-down', !expanded);
            icon.classList.toggle('fa-chevron-up', expanded);
          }
        };
      });
    }

    function highlight(scope) {
      if (typeof hljs === 'undefined') return;
      scope.querySelectorAll('pre code').forEach(block => {
        if (!block.dataset.highlighted) hljs.highlightElement(block);
      });
    }

    function getEvents() {
      return events.slice();
    }

    function finish() {
      completed = true;
      render();
    }

    reset();
    return { reset, process, finish, getEvents };
  }

  function normalize(events) {
    const items = [];
    events.forEach(event => {
      if (event.type === 'Thinking' || event.type === 'Text') {
        const kind = event.type === 'Thinking' ? 'thinking' : 'text';
        const last = items[items.length - 1];
        if (last && last.kind === kind) last.content += event.content || '';
        else if (event.content) items.push({ kind, content: event.content });
      } else if (event.type === 'ToolStart') {
        if ((event.toolName || '').toLowerCase() !== 'todowrite') {
          items.push({ kind: 'tool', id: event.toolCallId || '', name: event.toolName || 'unknown',
            args: event.arguments || '', result: '', status: 'running' });
        }
      } else if (event.type === 'ToolEnd') {
        if ((event.toolName || '').toLowerCase() !== 'todowrite') {
          const tool = items.find(item => item.kind === 'tool' && item.id === (event.toolCallId || '') && item.status === 'running');
          if (tool) { tool.status = 'completed'; tool.result = event.result || ''; }
          else items.push({ kind: 'tool', id: event.toolCallId || '', name: event.toolName || 'unknown',
            args: '', result: event.result || '', status: 'completed' });
        }
      } else if (event.type === 'TodoProgress') {
        items.push({ kind: 'todo', items: Array.isArray(event.items) ? event.items : [] });
      } else if (event.type === 'StageOutput') {
        items.push({ kind: 'stage', name: event.stage || event.name || '阶段输出', data: event.data || {} });
      } else if (event.type === 'Error') {
        items.push({ kind: 'error', message: event.message || '分析失败', detail: event.detail || '' });
      } else if (event.type === 'Paused') {
        items.push({ kind: 'stage', name: '已暂停', data: {} });
      }
    });
    return items;
  }

  function itemHtml(item, Markdown, Utils) {
    if (item.kind === 'thinking') return timelineItem('thinking',
      '<div class="da-thinking"><div class="da-thinking-label"><i class="fas fa-brain"></i> 思考过程</div>' +
      '<div class="da-thinking-text">' + Markdown.render(item.content) + '</div></div>');
    if (item.kind === 'text') return timelineItem('text',
      '<div class="da-text-item">' + Markdown.render(item.content) + '</div>');
    if (item.kind === 'tool') return timelineItem(item.status,
      '<div class="da-tool ' + item.status + '"><i class="fas fa-wrench da-tool-icon"></i>' +
      '<span class="da-tool-name">' + Utils.escapeHtml(item.name) + '</span>' +
      '<span class="da-tool-status">' + (item.status === 'running' ? '执行中' : '已完成') + '</span>' +
      '<button type="button" class="da-tool-expand" data-tool-toggle aria-expanded="false" title="查看工具详情"><i class="fas fa-chevron-down"></i></button>' +
      '<div class="da-tool-detail" hidden><div class="da-tool-section-label">参数</div><pre class="da-tool-args"><code>' +
      Utils.escapeHtml(format(item.args)) + '</code></pre><div class="da-tool-section-label">结果</div>' +
      '<div class="da-tool-result">' + Utils.escapeHtml(format(item.result)) + '</div></div></div>');
    if (item.kind === 'todo') return timelineItem('todo', '<div class="da-todo-card">' + item.items.map(todo =>
      '<div><i class="far fa-circle"></i> ' + Utils.escapeHtml(todo.text || todo.content || todo.task || '') + '</div>').join('') + '</div>');
    if (item.kind === 'stage') return timelineItem('completed',
      '<div class="analysis-stage"><i class="fas fa-circle-check"></i><span>' + Utils.escapeHtml(item.name) + '</span></div>');
    if (item.kind === 'error') return timelineItem('error',
      '<div class="da-error"><div class="da-error-title"><i class="fas fa-triangle-exclamation"></i> ' +
      Utils.escapeHtml(item.message) + '</div><div class="da-error-detail">' + Utils.escapeHtml(item.detail) + '</div></div>');
    return '';
  }

  function timelineItem(dot, body) {
    return '<div class="da-timeline-item"><span class="da-timeline-dot ' + dot + '"></span>' +
      '<div class="da-timeline-body">' + body + '</div></div>';
  }

  function format(value) {
    if (typeof value !== 'string') return JSON.stringify(value || {}, null, 2);
    try { return JSON.stringify(JSON.parse(value), null, 2); } catch (e) { return value; }
  }

  window.MaaS.AgentTimeline = { create, types: TYPES.slice() };
})();
