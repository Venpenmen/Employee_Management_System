(() => {
  'use strict';

  function csrfToken() {
    return document.querySelector('#comm-csrf, #announcement-csrf')?.value || '';
  }

  async function api(path, options = {}) {
    const method = (options.method || 'GET').toUpperCase();
    const headers = new Headers(options.headers || {});
    headers.set('Accept', 'application/json');
    headers.set('Cache-Control', 'no-store');
    if (method !== 'GET' && method !== 'HEAD') headers.set('X-CSRF-Token', csrfToken());
    const response = await fetch(path, {...options, method, headers, credentials: 'same-origin', cache: 'no-store'});
    if (response.status === 401) {
      window.location.href = '/login?reason=expired';
      throw new Error('登录已失效');
    }
    let payload;
    try {
      payload = await response.json();
    } catch {
      throw new Error('服务器返回格式异常');
    }
    if (!response.ok || payload.code !== 200) throw new Error(payload.msg || '操作失败');
    return payload.data;
  }

  function formData(fields) {
    const data = new URLSearchParams();
    Object.entries(fields || {}).forEach(([key, value]) => {
      if (value !== undefined && value !== null) data.set(key, String(value));
    });
    return data;
  }

  function el(tag, className, text) {
    const node = document.createElement(tag);
    if (className) node.className = className;
    if (text !== undefined) node.textContent = String(text ?? '');
    return node;
  }

  function button(text, action, className = 'small') {
    const node = el('button', className, text);
    node.type = 'button';
    node.addEventListener('click', action);
    return node;
  }

  function fmtTime(value) {
    if (!value) return '';
    const date = new Date(value);
    if (Number.isNaN(date.getTime())) return String(value);
    return date.toLocaleString('zh-CN', {hour12: false});
  }

  function fmtSize(bytes) {
    const value = Number(bytes || 0);
    if (value < 1024) return value + ' B';
    if (value < 1024 * 1024) return (value / 1024).toFixed(1) + ' KB';
    return (value / 1024 / 1024).toFixed(1) + ' MB';
  }

  function attachmentNode(file, withRemove = false) {
    const item = el('div', 'attachment-item');
    const link = el('a', '', file.original_name || '附件');
    link.href = '/communications/attachments/' + file.id;
    link.target = '_blank';
    link.rel = 'noopener';
    const wrap = el('span');
    wrap.append(link, document.createTextNode(' · '), el('small', '', fmtSize(file.byte_size)));
    item.append(wrap);
    if (withRemove) {
      const label = el('label', 'check');
      const box = el('input');
      box.type = 'checkbox';
      box.value = file.id;
      box.dataset.removeAttachment = 'true';
      label.append(box, document.createTextNode('移除'));
      item.append(label);
    }
    return item;
  }

  function parseBody(value) {
    try {
      const data = typeof value === 'string' ? JSON.parse(value) : value;
      return data && Array.isArray(data.blocks) ? data : {version: 1, blocks: []};
    } catch {
      return {version: 1, blocks: []};
    }
  }

  function renderRichBody(root, data, editable = false) {
    root.replaceChildren();
    let list = null;
    let listType = null;
    (data.blocks || []).forEach(block => {
      const type = block.type === 'bullet' ? 'ul' : block.type === 'numbered' ? 'ol' : 'p';
      if (type !== listType) {
        if (type === 'p') {
          list = null;
          listType = null;
        } else {
          list = el(type);
          listType = type;
          root.append(list);
        }
      }
      const item = type === 'p' ? el('p') : el('li');
      (block.spans || []).forEach(span => {
        let node = document.createTextNode(String(span.text || ''));
        if (span.underline) { const wrapper = el('u'); wrapper.append(node); node = wrapper; }
        if (span.italic) { const wrapper = el('em'); wrapper.append(node); node = wrapper; }
        if (span.bold) { const wrapper = el('strong'); wrapper.append(node); node = wrapper; }
        item.append(node);
      });
      if (type === 'p') root.append(item);
      else list.append(item);
    });
    if (editable && !root.childElementCount) root.append(el('p'));
    root.dataset.empty = root.childElementCount ? 'false' : 'true';
  }

  function blocksFromEditor(root) {
    const output = [];
    function collect(node, style, spans) {
      if (node.nodeType === Node.TEXT_NODE) {
        const text = node.nodeValue.replace(/\u00a0/g, ' ');
        if (text) spans.push({text, ...style});
        return;
      }
      if (node.nodeType !== Node.ELEMENT_NODE) return;
      const tag = node.tagName;
      if (tag === 'BR') {
        spans.push({text: '\n', ...style});
        return;
      }
      const next = {
        bold: style.bold || tag === 'B' || tag === 'STRONG',
        italic: style.italic || tag === 'I' || tag === 'EM',
        underline: style.underline || tag === 'U'
      };
      node.childNodes.forEach(child => collect(child, next, spans));
    }
    function appendBlock(type, node) {
      const spans = [];
      node.childNodes.forEach(child => collect(child, {}, spans));
      if (spans.some(span => span.text.trim())) output.push({type, spans});
    }
    [...root.childNodes].forEach(node => {
      if (node.nodeType === Node.TEXT_NODE) {
        const p = el('p');
        p.textContent = node.nodeValue;
        appendBlock('paragraph', p);
      } else if (node.nodeType === Node.ELEMENT_NODE) {
        if (node.tagName === 'UL' || node.tagName === 'OL') {
          const type = node.tagName === 'UL' ? 'bullet' : 'numbered';
          [...node.children].forEach(child => appendBlock(type, child));
        } else {
          appendBlock('paragraph', node);
        }
      }
    });
    return output;
  }

  window.EMSComm = {api, formData, el, button, fmtTime, fmtSize, attachmentNode, parseBody, renderRichBody, blocksFromEditor};
})();
