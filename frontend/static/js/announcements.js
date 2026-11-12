(() => {
  'use strict';

  const center = document.querySelector('#announcement-center');
  if (!center) return;
  const {api, formData, el, button, fmtTime, attachmentNode, parseBody, renderRichBody, blocksFromEditor} = window.EMSComm;
  const body = document.body;
  const userId = Number(body.dataset.userId);
  const role = Number(body.dataset.role);
  const ownDepartmentId = body.dataset.departmentId || null;
  const ownDepartmentName = body.dataset.departmentName || '';
  const isManager = role === 1;
  const homeMode = center.dataset.homeDefault === 'true';
  const state = {
    mode: 'inbox',
    announcements: [],
    selected: null,
    recipientContacts: null,
    listRequest: 0,
    detailRequest: 0,
    selectedFromHome: false,
    socket: null,
    reconnectDelay: 1000,
    refreshTimer: null
  };

  function status(text, kind = '') {
    const node = document.querySelector('#announcement-status-message');
    node.textContent = text || '';
    node.className = kind ? `alert ${kind}` : 'muted';
    node.hidden = !text;
    document.querySelector('#announcement-retry').hidden = kind !== 'error';
  }

  function announcementStatus(value, hasDraft) {
    if (Number(value) === 0) return '草稿';
    if (Number(value) === 1) return Number(hasDraft) ? '已发布 · 待更新' : '已发布';
    if (Number(value) === 2) return '已撤回';
    if (Number(value) === 3) return '已归档';
    return '未知状态';
  }

  async function loadHome() {
    const request = ++state.listRequest;
    const detailAtStart = state.detailRequest;
    status('正在加载最新公告…');
    document.querySelector('#announcement-loading').hidden = false;
    try {
      const items = await api('/api/communications/announcements/home?limit=5');
      if (request !== state.listRequest || detailAtStart !== state.detailRequest) return;
      state.announcements = items;
      if (items.length) {
        state.selected = items[0];
        state.selectedFromHome = true;
        renderAnnouncement(items[0], {readOnly: true});
      } else {
        state.selected = null;
        renderEmpty('暂无可接收公告');
      }
      renderAnnouncements();
      status('');
      // The home endpoint is intentionally only a fast first paint. Replace the
      // five-item projection with the complete inbox list without changing selection.
      loadAnnouncements(true).catch(error => status(error.message, 'error'));
    } catch (error) {
      if (request !== state.listRequest) return;
      status(error.message, 'error');
      renderEmpty('公告加载失败，请重试');
    } finally {
      if (request === state.listRequest) document.querySelector('#announcement-loading').hidden = true;
    }
  }

  async function loadAnnouncements(preserveSelection = false) {
    const request = ++state.listRequest;
    const detailAtStart = state.detailRequest;
    const keyword = document.querySelector('#announcement-search').value.trim();
    const statusValue = document.querySelector('#announcement-status').value;
    const params = new URLSearchParams({mode: state.mode, limit: '100'});
    if (keyword) params.set('keyword', keyword);
    if (statusValue) params.set('status', statusValue);
    status('正在加载公告…');
    document.querySelector('#announcement-loading').hidden = false;
    try {
      const items = await api('/api/communications/announcements?' + params);
      if (request !== state.listRequest || detailAtStart !== state.detailRequest) return;
      const selectedId = preserveSelection ? state.selected?.id : null;
      state.announcements = items;
      if (selectedId != null) {
        const refreshed = items.find(item => Number(item.id) === Number(selectedId));
        if (refreshed) state.selected = {...state.selected, ...refreshed};
      }
      renderAnnouncements();
      if (!preserveSelection) {
        state.selected = null;
        state.selectedFromHome = false;
        renderEmpty('选择一条公告查看详情。');
      }
      status('');
    } catch (error) {
      if (request !== state.listRequest) return;
      status(error.message, 'error');
      renderEmpty('公告加载失败，请重试');
    } finally {
      if (request === state.listRequest) document.querySelector('#announcement-loading').hidden = true;
    }
  }

  function renderAnnouncements() {
    const root = document.querySelector('#announcement-list');
    root.replaceChildren();
    state.announcements.forEach(item => {
      const title = item.display_title ?? item.version_title ?? item.title;
      const category = item.display_category ?? item.version_category ?? item.category;
      const requiresAck = item.display_ack ?? item.version_ack ?? item.requires_ack;
      const row = el('button', 'announcement-row' +
        (state.selected && Number(state.selected.id) === Number(item.id) ? ' active' : ''));
      row.type = 'button';
      const titleLine = el('span', 'announcement-row-title');
      titleLine.append(el('strong', '', title || '未命名公告'));
      if (item.pinned_at) titleLine.append(el('span', 'pin-mark', '置顶'));
      row.append(titleLine);
      const meta = el('span', 'row-meta');
      meta.append(el('span', '', fmtTime(item.published_at || item.update_time || item.create_time)));
      meta.append(el('span', 'tag', category || '通知'));
      row.append(meta);
      if (state.mode === 'inbox' && (!item.read_at || (Number(requiresAck) === 1 && !item.confirmed_at))) {
        row.append(el('span', 'announcement-state', Number(requiresAck) === 1 && !item.confirmed_at ? '待确认' : '未读'));
      }
      if (state.mode !== 'inbox') {
        row.append(el('span', 'tag', announcementStatus(item.announcement_status, item.has_draft)));
      }
      row.addEventListener('click', () => openAnnouncement(item.id).catch(error => status(error.message, 'error')));
      root.append(row);
    });
    if (!root.childElementCount) root.append(el('p', 'muted', '暂无公告'));
  }

  function renderEmpty(message) {
    document.querySelector('#announcement-empty').textContent = message;
    document.querySelector('#announcement-empty').hidden = false;
    document.querySelector('#announcement-detail').hidden = true;
    document.querySelector('#announcement-stats').hidden = true;
    document.querySelector('#announcement-history').hidden = true;
  }

  async function openAnnouncement(id) {
    const request = ++state.detailRequest;
    status('正在加载公告详情…');
    try {
      const item = await api('/api/communications/announcements/' + id);
      if (request !== state.detailRequest) return;
      state.selected = item;
      state.selectedFromHome = false;
      renderAnnouncement(item, {readOnly: false});
      status('');
      renderAnnouncements();
    } catch (error) {
      if (request === state.detailRequest) status(error.message, 'error');
      throw error;
    }
  }

  function renderAnnouncement(item, {readOnly = false} = {}) {
    document.querySelector('#announcement-empty').hidden = true;
    document.querySelector('#announcement-detail').hidden = false;
    document.querySelector('#announcement-title').textContent = item.display_title || item.version_title || item.title;
    document.querySelector('#announcement-category').textContent = item.display_category || item.version_category || item.category;
    const ack = Number(item.version_ack ?? item.display_ack ?? item.requires_ack);
    document.querySelector('#announcement-meta').textContent =
      fmtTime(item.published_at || item.version_published_at) + (ack === 1 ? ' · 需要确认' : '');
    document.querySelector('#announcement-pin').hidden = !item.pinned_at;
    renderRichBody(document.querySelector('#announcement-body'), parseBody(item.body_json));
    const attachmentRoot = document.querySelector('#announcement-attachments');
    attachmentRoot.replaceChildren();
    (item.attachments || []).forEach(file => attachmentRoot.append(attachmentNode(file)));
    document.querySelector('#announcement-confirm').hidden =
      !(ack === 1 && !item.confirmed_at && Number(item.announcement_status) === 1 && state.mode === 'inbox');
    document.querySelector('#announcement-actions').replaceChildren();
    if (state.mode === 'inbox') {
      if (!item.read_at && ack !== 1) {
        document.querySelector('#announcement-actions').append(
          button('标记已读', () => markRead(item), 'small'));
      }
      document.querySelector('#announcement-actions').append(
        button(item.read_at ? '已读' : '未读', () => {}, 'small quiet'));
      document.querySelector('#announcement-actions').lastElementChild.disabled = true;
    }
    if (!readOnly && item.can_manage === true && state.mode === 'manage') renderAnnouncementActions(item);
    document.querySelector('#announcement-history').hidden =
      !(!readOnly && item.can_manage && state.mode === 'manage' && item.history);
    if (!document.querySelector('#announcement-history').hidden) {
      renderAnnouncementHistory(item.history);
      renderHandoverHistory(item.handover_history || []);
    }
    document.querySelector('#announcement-stats').hidden =
      !(!readOnly && item.can_manage && state.mode === 'manage' && Number(item.announcement_status) === 1);
    if (!document.querySelector('#announcement-stats').hidden) {
      loadAnnouncementStats(item.id, state.detailRequest).catch(error => status(error.message, 'error'));
    }
  }

  async function markRead(item) {
    try {
      await api('/api/communications/announcements/' + item.id + '/read', {method: 'POST'});
      item.read_at = new Date().toISOString();
      renderAnnouncement(item);
      renderAnnouncements();
      status('公告已标记为已读。', 'success');
      window.dispatchEvent(new Event('ems:communications-changed'));
    } catch (error) {
      status(error.message, 'error');
    }
  }

  function renderAnnouncementActions(item) {
    if (!item.can_manage) return;
    const root = document.querySelector('#announcement-actions');
    if (role === 2 && Number(item.scope_type) === 1) {
      root.append(button('授权交接', () => openAnnouncementHandover(item).catch(error => status(error.message, 'error'))));
      if ((item.handover_history || []).some(entry => !entry.revoked_at)) {
        root.append(button('撤销交接', () => revokeAnnouncementHandover(item).catch(error => status(error.message, 'error')), 'small danger'));
      }
    }
    if (item.draft) {
      root.append(button('继续编辑草稿', () => openAnnouncementEditor(item), 'small primary'));
      root.append(button('发布当前草稿', () => publishAnnouncement(item)));
    } else {
      root.append(button('创建修改版本', () => openAnnouncementEditor(item)));
    }
    if (Number(item.announcement_status) === 1) {
      root.append(button(item.pinned_at ? '取消置顶' : '置顶', () => pinAnnouncement(item)));
      root.append(button('撤回', () => announcementStateAction(item, 'recall'), 'small danger'));
      root.append(button('归档', () => announcementStateAction(item, 'archive')));
    }
  }

  function renderAnnouncementHistory(history) {
    const root = document.querySelector('#announcement-history-list');
    root.replaceChildren();
    history.forEach(version => {
      const row = el('div', 'history-row');
      const text = el('span');
      text.append(el('strong', '', 'V' + version.version_no + ' · ' + version.title));
      text.append(el('small', '', ' · ' + fmtTime(version.published_at || version.create_time)));
      row.append(text, el('span', 'tag', ['草稿', '已发布', '已被替换'][Number(version.version_status)] || ''));
      root.append(row);
    });
  }

  function renderHandoverHistory(history) {
    const root = document.querySelector('#announcement-history-list');
    if (!history.length) return;
    root.append(el('h4', '', '管理权交接历史'));
    history.forEach(entry => {
      const row = el('div', 'history-row');
      row.append(el('strong', '', (entry.creator_name || '原创建者') + ' → ' +
        (entry.manager_name || '接任经理') + (entry.revoked_at ? ' · 已撤销' : ' · 已授权')));
      row.append(el('small', '', fmtTime(entry.authorized_at) + ' · ' + (entry.reason || '')));
      if (entry.revoked_at) row.append(el('small', '', '撤销原因：' + (entry.revoke_reason || '')));
      root.append(row);
    });
  }

  async function loadAnnouncementStats(id, request) {
    const stats = await api('/api/communications/announcements/' + id + '/stats');
    if (request !== state.detailRequest || Number(state.selected?.id) !== Number(id)) return;
    const root = document.querySelector('#announcement-stats-summary');
    root.replaceChildren();
    [['接收人数', stats.summary.total], ['已读', stats.summary.read_count],
      ['未读', stats.summary.unread_count], ['已确认', stats.summary.confirmed_count],
      ['未确认', stats.summary.unconfirmed_count]].forEach(([label, value]) => {
      const card = el('div', 'stat');
      card.append(el('b', '', value || 0), el('span', '', label));
      root.append(card);
    });
    const table = el('table');
    const head = el('thead');
    const header = el('tr');
    ['员工', '部门', '已读', '已确认'].forEach(label => header.append(el('th', '', label)));
    head.append(header);
    const rows = el('tbody');
    stats.recipients.forEach(person => {
      const row = el('tr');
      row.append(el('td', '', person.name + '（' + person.employee_no + '）'),
        el('td', '', person.department_name || '未分配'),
        el('td', '', person.read_at ? fmtTime(person.read_at) : '未读'),
        el('td', '', person.confirmed_at ? fmtTime(person.confirmed_at) : '未确认'));
      rows.append(row);
    });
    table.append(head, rows);
    document.querySelector('#announcement-stats-people').replaceChildren(table);
  }

  async function openAnnouncementHandover(item) {
    const candidates = await api('/api/communications/announcements/' + item.id + '/handover/candidates');
    if (!candidates.length) {
      status('当前公告部门没有可供交接的在职经理。', 'error');
      return;
    }
    const dialog = el('dialog', 'announcement-handover-dialog');
    const wrapper = el('div', 'stack');
    wrapper.append(el('h3', '', '授权公告管理权交接'));
    wrapper.append(el('p', 'muted', '只交接本公告管理权限，原创建者、接收人和历史回执保持不变。'));
    const label = el('label', '', '接任经理');
    const select = el('select');
    candidates.forEach(person => {
      const option = el('option', '', person.name + '（' + person.employee_no + '）');
      option.value = String(person.id);
      select.append(option);
    });
    label.append(select);
    const reason = el('textarea');
    reason.rows = 3;
    reason.maxLength = 500;
    reason.placeholder = '交接原因（必填）';
    const actions = el('div', 'dialog-actions');
    const accept = button('确认授权', async () => {
      if (!reason.value.trim()) {
        status('请填写交接原因。', 'error');
        return;
      }
      accept.disabled = true;
      try {
        await api('/api/communications/announcements/' + item.id + '/handover', {
          method: 'POST',
          headers: {'Content-Type': 'application/x-www-form-urlencoded;charset=UTF-8'},
          body: formData({managerId: select.value, reason: reason.value.trim()})
        });
        dialog.close();
        await loadAnnouncements();
        await openAnnouncement(item.id);
        status('公告管理权已交接。', 'success');
      } catch (error) {
        status(error.message, 'error');
        accept.disabled = false;
      }
    }, 'primary');
    actions.append(accept, button('取消', () => dialog.close()));
    wrapper.append(label, reason, actions);
    dialog.append(wrapper);
    dialog.addEventListener('close', () => dialog.remove(), {once: true});
    document.body.append(dialog);
    dialog.showModal();
  }

  async function revokeAnnouncementHandover(item) {
    const reason = prompt('请输入撤销公告交接授权的原因（必填）');
    if (!reason?.trim()) return;
    await api('/api/communications/announcements/' + item.id + '/handover/revoke', {
      method: 'POST',
      headers: {'Content-Type': 'application/x-www-form-urlencoded;charset=UTF-8'},
      body: formData({reason: reason.trim()})
    });
    await loadAnnouncements();
    await openAnnouncement(item.id);
    status('公告交接授权已撤销。', 'success');
  }

  async function publishAnnouncement(item) {
    if (!confirm('发布后接收人将看到该版本，需要确认的公告会要求回执。继续吗？')) return;
    try {
      await api('/api/communications/announcements/' + item.id + '/publish', {method: 'POST'});
      await loadAnnouncements();
      await openAnnouncement(item.id);
      status('公告已发布。', 'success');
    } catch (error) {
      status(error.message, 'error');
    }
  }

  async function pinAnnouncement(item) {
    await api('/api/communications/announcements/' + item.id + '/pin', {
      method: 'POST', headers: {'Content-Type': 'application/x-www-form-urlencoded;charset=UTF-8'},
      body: formData({pinned: !item.pinned_at})
    });
    await loadAnnouncements();
    await openAnnouncement(item.id);
  }

  async function announcementStateAction(item, action) {
    if (!confirm(action === 'recall' ? '撤回后接收人不能再读取或确认，确定继续吗？' : '归档后仅管理者可查看，确定继续吗？')) return;
    await api('/api/communications/announcements/' + item.id + '/' + action, {method: 'POST'});
    await loadAnnouncements();
    await openAnnouncement(item.id);
  }

  async function loadRecipientOptions() {
    if (state.recipientContacts) return state.recipientContacts;
    state.recipientContacts = await api('/api/communications/contacts?limit=100');
    return state.recipientContacts;
  }

  async function populateRecipientOptions() {
    const contacts = await loadRecipientOptions();
    const employeeOptions = document.querySelector('#announcement-employees');
    const departmentOptions = document.querySelector('#announcement-departments');
    const selectedEmployees = new Set([...employeeOptions.selectedOptions].map(option => option.value));
    const selectedDepartments = new Set([...departmentOptions.selectedOptions].map(option => option.value));
    employeeOptions.replaceChildren();
    departmentOptions.replaceChildren();
    const departments = new Map();
    contacts.forEach(item => {
      if (!isManager || (ownDepartmentId && String(item.department_id) === ownDepartmentId)) {
        const option = el('option', '', item.name + ' · ' + item.employee_no + (item.department_name ? ' · ' + item.department_name : ''));
        option.value = item.id;
        option.selected = selectedEmployees.has(String(item.id));
        employeeOptions.append(option);
      }
      if (item.department_id != null && item.department_name) departments.set(String(item.department_id), item.department_name);
    });
    if (isManager && ownDepartmentId && ownDepartmentName) departments.set(ownDepartmentId, ownDepartmentName);
    [...departments.entries()]
      .filter(([id]) => !isManager || id === ownDepartmentId)
      .sort((a, b) => a[1].localeCompare(b[1], 'zh-CN'))
      .forEach(([id, label]) => {
        const option = el('option', '', label);
        option.value = id;
        option.selected = selectedDepartments.has(id);
        departmentOptions.append(option);
      });
  }

  async function openAnnouncementEditor(item = null) {
    const form = document.querySelector('#announcement-form');
    form.reset();
    document.querySelector('#announcement-edit-id').value = item?.id || '';
    document.querySelector('#announcement-editor-title').textContent = item ? '编辑公告' : '新建公告';
    const draft = item?.draft || null;
    const source = draft || item;
    form.elements.title.value = source?.version_title || source?.title || '';
    form.elements.category.value = source?.version_category || source?.category || '通知';
    document.querySelector('#announcement-ack').checked =
      Number(source?.version_ack ?? source?.requires_ack ?? 0) === 1;
    const scopeSelect = document.querySelector('#announcement-scope');
    const allOption = scopeSelect.querySelector('option[value=all]');
    if (isManager) allOption?.remove();
    else if (!allOption) {
      const option = el('option', '', '全员');
      option.value = 'all';
      scopeSelect.prepend(option);
    }
    const scope = Number(item?.scope_type ?? (isManager ? 1 : 0));
    scopeSelect.value = scope === 1 ? 'departments' : scope === 2 ? 'employees' : (isManager ? 'departments' : 'all');
    await populateRecipientOptions();
    if (item?.targets) {
      const departments = new Set(item.targets.filter(target => Number(target.target_type) === 0).map(target => String(target.department_id)));
      const employees = new Set(item.targets.filter(target => Number(target.target_type) === 1).map(target => String(target.employee_id)));
      [...document.querySelector('#announcement-departments').options].forEach(option => option.selected = departments.has(option.value));
      [...document.querySelector('#announcement-employees').options].forEach(option => option.selected = employees.has(option.value));
    }
    renderRichBody(document.querySelector('#announcement-editor'),
      parseBody(draft?.body_json || item?.body_json || '{"version":1,"blocks":[]}'), true);
    renderExistingAnnouncementFiles(item?.attachments || []);
    updateAnnouncementScope();
    document.querySelector('#announcement-editor-dialog').showModal();
  }

  function renderExistingAnnouncementFiles(files) {
    const root = document.querySelector('#announcement-existing-files');
    root.replaceChildren();
    files.forEach(file => root.append(attachmentNode(file, true)));
  }

  function updateAnnouncementScope() {
    const scope = document.querySelector('#announcement-scope').value;
    document.querySelector('#announcement-departments-wrap').hidden = scope !== 'departments';
    document.querySelector('#announcement-employees-wrap').hidden = scope !== 'employees';
  }

  async function saveAnnouncement(event) {
    event.preventDefault();
    const editor = document.querySelector('#announcement-editor');
    const bodyJson = JSON.stringify({version: 1, blocks: blocksFromEditor(editor)});
    document.querySelector('#announcement-body-json').value = bodyJson;
    const data = new FormData(event.currentTarget);
    data.delete('departmentIds');
    data.delete('employeeIds');
    data.set('bodyJson', bodyJson);
    const scope = document.querySelector('#announcement-scope').value;
    if (scope === 'departments') {
      [...document.querySelector('#announcement-departments').selectedOptions].forEach(option => data.append('departmentIds', option.value));
    }
    if (scope === 'employees') {
      [...document.querySelector('#announcement-employees').selectedOptions].forEach(option => data.append('employeeIds', option.value));
    }
    if (!document.querySelector('#announcement-ack').checked) data.delete('requiresAck');
    document.querySelectorAll('[data-remove-attachment]:checked').forEach(box => data.append('removeAttachmentIds', box.value));
    try {
      const result = await api('/api/communications/announcements', {method: 'POST', body: data});
      document.querySelector('#announcement-editor-dialog').close();
      state.mode = 'manage';
      document.querySelectorAll('[data-ann-mode]').forEach(node => node.classList.toggle('active', node.dataset.annMode === 'manage'));
      await loadAnnouncements();
      await openAnnouncement(result.id);
      status('公告草稿已保存。', 'success');
    } catch (error) {
      status(error.message, 'error');
    }
  }

  function executeEditorCommand(command) {
    document.querySelector('#announcement-editor').focus();
    document.execCommand(command, false);
  }

  function bindEvents() {
    document.querySelector('#announcement-retry').addEventListener('click', () => {
      if (state.mode === 'inbox' && homeMode && !document.querySelector('#announcement-search').value.trim()) {
        loadHome();
      } else {
        loadAnnouncements(true);
      }
    });
    document.querySelectorAll('[data-ann-mode]').forEach(node => node.addEventListener('click', () => {
      ++state.detailRequest;
      ++state.listRequest;
      state.mode = node.dataset.annMode;
      state.selected = null;
      state.selectedFromHome = false;
      document.querySelectorAll('[data-ann-mode]').forEach(other => other.classList.toggle('active', other === node));
      document.querySelector('#announcement-status').hidden = state.mode !== 'manage';
      renderEmpty('选择一条公告查看详情。');
      if (state.mode === 'inbox' && homeMode && !document.querySelector('#announcement-search').value.trim()) loadHome();
      else loadAnnouncements();
    }));
    let searchTimer;
    document.querySelector('#announcement-search').addEventListener('input', () => {
      clearTimeout(searchTimer);
      searchTimer = setTimeout(() => loadAnnouncements(), 250);
    });
    document.querySelector('#announcement-status').addEventListener('change', () => loadAnnouncements());
    document.querySelector('#new-announcement')?.addEventListener('click', () => openAnnouncementEditor().catch(error => status(error.message, 'error')));
    document.querySelector('#announcement-scope').addEventListener('change', updateAnnouncementScope);
    document.querySelector('#announcement-form').addEventListener('submit', saveAnnouncement);
    document.querySelectorAll('[data-command]').forEach(node => node.addEventListener('click', () => executeEditorCommand(node.dataset.command)));
    document.querySelector('#announcement-confirm-button').addEventListener('click', async () => {
      if (!state.selected) return;
      try {
        await api('/api/communications/announcements/' + state.selected.id + '/confirm', {method: 'POST'});
        state.selected.read_at = new Date().toISOString();
        state.selected.confirmed_at = new Date().toISOString();
        renderAnnouncement(state.selected);
        renderAnnouncements();
        status('公告已确认。', 'success');
        window.dispatchEvent(new Event('ems:communications-changed'));
      } catch (error) {
        status(error.message, 'error');
      }
    });
    document.querySelectorAll('[data-close-dialog]').forEach(node => node.addEventListener('click', () => node.closest('dialog').close()));
  }

  function connectAnnouncementSocket() {
    if (!homeMode || (state.socket &&
        (state.socket.readyState === WebSocket.OPEN || state.socket.readyState === WebSocket.CONNECTING))) return;
    const protocol = location.protocol === 'https:' ? 'wss:' : 'ws:';
    const socket = new WebSocket(protocol + '//' + location.host + '/ws/communications');
    state.socket = socket;
    socket.addEventListener('open', () => { state.reconnectDelay = 1000; });
    socket.addEventListener('message', event => {
      let frame;
      try { frame = JSON.parse(event.data); } catch { return; }
      if (frame.type === 'session-ended') {
        location.href = '/login?reason=expired';
        return;
      }
      if (!String(frame.type || '').startsWith('announcement.')) return;
      window.dispatchEvent(new Event('ems:communications-changed'));
      clearTimeout(state.refreshTimer);
      state.refreshTimer = setTimeout(() => {
        const keyword = document.querySelector('#announcement-search').value.trim();
        if (state.mode === 'inbox' && !keyword && (!state.selected || state.selectedFromHome)) {
          loadHome();
        } else {
          loadAnnouncements(true);
        }
      }, 200);
    });
    socket.addEventListener('close', () => {
      if (state.socket !== socket) return;
      const delay = state.reconnectDelay;
      state.reconnectDelay = Math.min(30000, delay * 2);
      setTimeout(connectAnnouncementSocket, delay);
    });
    socket.addEventListener('error', () => socket.close());
  }

  async function init() {
    bindEvents();
    connectAnnouncementSocket();
    if (homeMode) {
      await loadHome();
      const target = Number(new URLSearchParams(location.search).get('announcement'));
      if (Number.isSafeInteger(target) && target > 0) await openAnnouncement(target);
    } else await loadAnnouncements();
  }

  init().catch(error => status(error.message, 'error'));
})();
