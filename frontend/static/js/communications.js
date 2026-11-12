(() => {
  'use strict';

  const {api, formData, el, button, fmtTime, attachmentNode} = window.EMSComm;
  const body = document.body;
  const userId = Number(body.dataset.userId);
  const workspace = document.querySelector('#messages-workspace');
  const connectionState = document.querySelector('#connection-state');
  const state = {
    contacts: [],
    groupContacts: [],
    conversations: [],
    conversation: null,
    messages: [],
    oldestSeq: null,
    replyTo: null,
    pendingClientId: null,
    socket: null,
    reconnectDelay: 1000,
    heartbeat: null,
    messageRequest: 0,
    conversationOpenRequest: 0,
    conversationListRequest: 0,
    contactsRequest: 0
  };

  function initial(name) {
    const text = String(name || '').trim();
    return text ? text.slice(0, 1).toUpperCase() : '?';
  }

  function setListState(message, error = false, retry = null) {
    const root = document.querySelector('#conversation-list');
    const node = el('div', 'panel-state' + (error ? ' error' : ''), message);
    if (retry) node.append(button('重试', retry));
    root.replaceChildren(node);
  }

  function setMessageState(message, error = false, retry = null) {
    const root = document.querySelector('#message-list');
    const node = el('div', 'panel-state' + (error ? ' error' : ''), message);
    if (retry) node.append(button('重试', retry));
    root.replaceChildren(node);
  }

  function syncWorkspaceHeight() {
    if (!window.matchMedia('(max-width: 900px)').matches) {
      workspace.style.removeProperty('--comm-workspace-height');
      return;
    }
    const viewport = window.visualViewport;
    const visibleHeight = viewport?.height || window.innerHeight;
    const viewportOffset = viewport?.offsetTop || 0;
    const top = Math.max(0, workspace.getBoundingClientRect().top - viewportOffset);
    workspace.style.setProperty('--comm-workspace-height',
      Math.max(280, Math.floor(visibleHeight - top - 6)) + 'px');
  }

  function setMobileChat(open) {
    workspace.classList.toggle('chat-open', Boolean(open));
    document.body.classList.toggle('comm-chat-open', Boolean(open));
    requestAnimationFrame(syncWorkspaceHeight);
  }

  async function loadContacts() {
    const request = ++state.contactsRequest;
    const keyword = document.querySelector('#contact-search').value.trim();
    try {
      const contacts = await api('/api/communications/contacts?limit=100' + (keyword ? '&keyword=' + encodeURIComponent(keyword) : ''));
      if (request !== state.contactsRequest) return;
      state.contacts = contacts;
      renderContacts();
      populateGroupOptions();
    } catch (error) {
      if (request !== state.contactsRequest) return;
      const root = document.querySelector('#contact-results');
      root.replaceChildren(el('p', 'error', error.message));
    }
  }

  async function loadGroupContacts() {
    state.groupContacts = await api('/api/communications/contacts?limit=100');
    populateGroupOptions();
  }

  function renderContacts() {
    const root = document.querySelector('#contact-results');
    root.replaceChildren();
    state.contacts.filter(item => Number(item.id) !== userId).forEach(item => {
      const row = el('button', 'contact-row');
      row.type = 'button';
      const identity = el('span', 'inline');
      identity.append(el('span', 'contact-avatar', initial(item.name)),
        el('strong', '', item.name + ' · ' + item.employee_no));
      row.append(identity);
      row.append(el('span', 'row-meta', (item.department_name || '未分配') + (item.position_name ? ' / ' + item.position_name : '')));
      row.addEventListener('click', async () => {
        try {
          const conversation = await api('/api/communications/conversations/direct', {
            method: 'POST',
            headers: {'Content-Type': 'application/x-www-form-urlencoded;charset=UTF-8'},
            body: formData({employeeId: item.id})
          });
          await loadConversations();
          await openConversation(conversation.id);
        } catch (error) {
          connectionState.textContent = error.message;
          connectionState.classList.add('error');
        }
      });
      root.append(row);
    });
    if (!root.childElementCount) root.append(el('p', 'muted', keywordEmptyText()));
  }

  function keywordEmptyText() {
    return document.querySelector('#contact-search').value.trim() ? '没有匹配的联系人' : '通讯录暂无可联系员工';
  }

  function selectedGroupMembers(root) {
    return [...root.querySelectorAll('input[type="checkbox"]:checked')].map(input => input.value);
  }

  function updateGroupMemberCount(root) {
    root.closest('.group-member-field').querySelector('.group-member-count').textContent =
      '已选 ' + selectedGroupMembers(root).length + ' 人';
  }

  function filterGroupMembers(root, keyword) {
    const needle = String(keyword || '').trim().toLocaleLowerCase();
    root.querySelectorAll('.group-member-option').forEach(label => {
      label.hidden = !label.textContent.toLocaleLowerCase().includes(needle);
    });
  }

  function renderGroupMemberChoices(root, contacts, selected = new Set()) {
    root.replaceChildren();
    contacts.forEach(item => {
      const label = el('label', 'group-member-option');
      const checkbox = document.createElement('input');
      checkbox.type = 'checkbox';
      checkbox.value = String(item.id);
      checkbox.checked = selected.has(checkbox.value);
      label.append( el('span', '', item.name + ' · ' + item.employee_no +
        (item.department_name ? ' · ' + item.department_name : '')), checkbox);
      root.append(label);
    });
    if (!contacts.length) root.append(el('p', 'muted', '暂无可邀请成员'));
    updateGroupMemberCount(root);
    filterGroupMembers(root, root.closest('.group-member-field').querySelector('input[type="search"]').value);
  }

  function populateGroupOptions() {
    const root = document.querySelector('#new-group-members');
    renderGroupMemberChoices(root, state.groupContacts.filter(item => Number(item.id) !== userId),
      new Set(selectedGroupMembers(root)));
  }

  async function loadConversations() {
    const request = ++state.conversationListRequest;
    try {
      if (!state.conversations.length) setListState('正在加载会话…');
      const conversations = await api('/api/communications/conversations');
      if (request !== state.conversationListRequest) return;
      state.conversations = conversations;
      renderConversations();
    } catch (error) {
      if (request === state.conversationListRequest) setListState(error.message, true, () => loadConversations());
    }
  }

  function clearActiveConversation(id) {
    if (Number(state.conversation?.id) !== Number(id)) return;
    ++state.conversationOpenRequest;
    ++state.messageRequest;
    state.conversation = null;
    state.messages = [];
    state.replyTo = null;
    document.querySelector('#chat-panel').hidden = true;
    document.querySelector('#empty-chat').hidden = false;
    setMobileChat(false);
  }

  async function actOnConversation(item, action) {
    const id = Number(item.id);
    try {
      if (action === 'pin') {
        const pinned = !item.pinned_at;
        await api('/api/communications/conversations/' + id + '/pin', {
          method: 'POST', headers: {'Content-Type': 'application/x-www-form-urlencoded;charset=UTF-8'},
          body: formData({pinned})
        });
        if (Number(state.conversation?.id) === id) {
          state.conversation.pinned_at = pinned ? new Date().toISOString() : null;
          document.querySelector('#chat-pin').textContent = pinned ? '取消置顶' : '置顶';
        }
      } else {
        const removing = action === 'remove';
        const warning = removing
          ? '从我的列表删除会话并取消置顶？聊天记录和未读状态不会删除，新消息到达后会重新出现。'
          : '隐藏后，新消息到达时会重新出现。继续吗？';
        if (!confirm(warning)) return;
        await api('/api/communications/conversations/' + id + (removing ? '/remove' : '/hide'),
          {method: 'POST'});
        clearActiveConversation(id);
      }
      await loadConversations();
    } catch (error) {
      connectionState.textContent = error.message;
      connectionState.classList.add('error');
    }
  }

  function renderConversations() {
    const root = document.querySelector('#conversation-list');
    const snapshot = JSON.stringify([state.conversations, Number(state.conversation?.id || 0)]);
    if (state.renderedConversationSnapshot === snapshot) return;
    state.renderedConversationSnapshot = snapshot;
    root.replaceChildren();
    document.querySelector('#conversation-total').textContent = String(state.conversations.length);
    state.conversations.forEach(item => {
      const selected = state.conversation && Number(state.conversation.id) === Number(item.id);
      const entry = el('div', 'conversation-entry');
      const row = el('button', 'conversation-row' + (selected ? ' active' : ''));
      row.type = 'button';
      const title = Number(item.conversation_type) === 0 ? (item.title || item.peer?.name || '私聊') : (item.title || '群聊');
      row.append(el('span', 'conversation-avatar' + (Number(item.conversation_type) === 1 ? ' group-avatar' : ''),
        Number(item.conversation_type) === 1 ? '群' : initial(title)));
      const copy = el('span', 'conversation-copy');
      const titleLine = el('span', 'conversation-title');
      titleLine.append(el('strong', '', title));
      if (item.pinned_at) titleLine.append(el('span', 'pin-mark', '置顶'));
      copy.append(titleLine);
      const latest = item.latest;
      const preview = latest
        ? (latest.sender_name ? latest.sender_name + '：' : '') + (latest.body_text || (latest.message_type ? '[附件]' : ''))
        : '暂无消息';
      copy.append(el('span', 'conversation-preview', preview));
      row.append(copy);
      const side = el('span', 'conversation-side');
      side.append(el('span', '', latest ? fmtTime(latest.sent_at) : ''));
      const unread = Number(item.unread_count || 0);
      if (unread > 0) side.append(el('span', 'unread-badge', unread > 99 ? '99+' : unread));
      row.append(side);
      row.addEventListener('click', () => openConversation(item.id).catch(error => {
        setMessageState(error.message, true, () => openConversation(item.id));
      }));
      const menu = el('details', 'conversation-menu');
      const summary = el('summary', '', '···');
      summary.title = '设置会话：' + title;
      summary.setAttribute('aria-label', '设置会话：' + title);
      const actions = el('div', 'conversation-menu-panel');
      [
        [item.pinned_at ? '取消置顶' : '置顶会话', 'pin'],
        ['隐藏会话', 'hide'],
        ['删除会话', 'remove']
      ].forEach(([label, action]) => {
        const control = el('button', action === 'remove' ? 'conversation-remove' : '', label);
        control.type = 'button';
        control.addEventListener('click', () => {
          menu.open = false;
          actOnConversation(item, action);
        });
        actions.append(control);
      });
      menu.append(summary, actions);
      menu.addEventListener('toggle', () => {
        if (menu.open) root.querySelectorAll('.conversation-menu[open]').forEach(other => {
          if (other !== menu) other.open = false;
        });
      });
      entry.append(row, menu);
      root.append(entry);
    });
    if (!root.childElementCount) setListState('暂无会话，选择联系人开始私聊。');
  }

  const submittedRead = new Map();

  async function openConversation(id) {
    const request = ++state.conversationOpenRequest;
    ++state.messageRequest;
    const conversation = await api('/api/communications/conversations/' + id);
    if (request !== state.conversationOpenRequest) return;
    state.conversation = conversation;
    submittedRead.set(Number(conversation.id), Number(conversation.last_read_seq || 0));
    state.replyTo = null;
    state.pendingClientId = null;
    state.oldestSeq = null;
    state.messages = [];
    document.querySelector('#empty-chat').hidden = true;
    document.querySelector('#chat-panel').hidden = false;
    document.querySelector('#reply-preview').hidden = true;
    const group = Number(state.conversation.conversation_type) === 1;
    const chatName = group ? (state.conversation.title || '群聊')
      : (state.conversation.peer?.name || state.conversation.title || '私聊');
    document.querySelector('#chat-title').textContent = chatName;
    const headerAvatar = document.querySelector('#chat-avatar');
    headerAvatar.classList.toggle('is-group', group);
    headerAvatar.textContent = group ? '群' : initial(document.querySelector('#chat-title').textContent);
    document.querySelector('#chat-more-menu').open = false;
    document.querySelector('#chat-search-panel').hidden = true;
    document.querySelector('#chat-search-toggle').setAttribute('aria-expanded', 'false');
    document.querySelector('#chat-meta').textContent = group
      ? (state.conversation.group_type === 1 ? '部门群' : groupRoleName(state.conversation.current_role ?? -1))
      : ('私聊' + (state.conversation.peer?.department_name ? ' · ' + state.conversation.peer.department_name : ''));
    document.querySelector('#chat-pin').textContent = state.conversation.pinned_at ? '取消置顶' : '置顶';
    for (const id of ['chat-profile', 'chat-search-menu', 'chat-direct-files', 'chat-copy-link'])
      document.getElementById(id).hidden = group;
    document.querySelector('#chat-members').hidden = !group;
    document.querySelector('#chat-files').hidden = !group;
    document.querySelector('#chat-settings').hidden = !group;
    document.querySelector('#mention-all-wrap').hidden = !group || Number(state.conversation.current_role) < 1;
    document.querySelector('#mention-ids').hidden = !group;
    if (group) populateGroupMentions();
    setMobileChat(true);
    await loadMessages();
    renderConversations();
  }

  function groupRoleName(value) {
    return Number(value) === 2 ? '群主' : Number(value) === 1 ? '管理员' : Number(value) === 0 ? '普通成员' : '只读历史';
  }

  async function loadMessages(older = false) {
    if (!state.conversation) return;
    const conversationId = Number(state.conversation.id);
    const request = ++state.messageRequest;
    const params = new URLSearchParams({size: '30'});
    if (older && state.oldestSeq != null) params.set('beforeSeq', state.oldestSeq);
    const keyword = document.querySelector('#message-keyword').value.trim();
    const from = document.querySelector('#message-from').value;
    const to = document.querySelector('#message-to').value;
    if (keyword) params.set('keyword', keyword);
    if (from) params.set('from', from);
    if (to) params.set('to', to);
    if (!older && !state.messages.length) setMessageState('正在加载消息…');
    try {
      const result = await api('/api/communications/conversations/' + conversationId + '/messages?' + params);
      if (request !== state.messageRequest || Number(state.conversation?.id) !== conversationId) return;
      const rows = result.items.slice().reverse();
      state.messages = older ? [...rows, ...state.messages] : rows;
      if (rows.length) state.oldestSeq = rows[0].conversation_seq;
      renderMessages();
      document.querySelector('#message-load-older-wrap').hidden = !result.has_more;
      const latest = state.messages.reduce((max, message) => Math.max(max, Number(message.conversation_seq)), 0);
      const previousRead = submittedRead.get(conversationId) || 0;
      if (latest > previousRead) {
        submittedRead.set(conversationId, latest);
        api('/api/communications/conversations/' + conversationId + '/read', {
          method: 'POST',
          headers: {'Content-Type': 'application/x-www-form-urlencoded;charset=UTF-8'},
          body: formData({sequence: latest})
        }).catch(() => {
          if (submittedRead.get(conversationId) === latest) submittedRead.set(conversationId, previousRead);
        });
      }
    } catch (error) {
      if (request === state.messageRequest) {
        setMessageState(error.message, true, () => loadMessages(older));
      }
    }
  }

  function renderMessages() {
    const root = document.querySelector('#message-list');
    const snapshot = JSON.stringify([Number(state.conversation?.id || 0), state.messages]);
    if (state.renderedMessageSnapshot === snapshot) return;
    state.renderedMessageSnapshot = snapshot;
    root.replaceChildren();
    let previousDay = '';
    state.messages.forEach(message => {
      const timestamp = message.sent_at ? new Date(message.sent_at) : null;
      const day = timestamp && !Number.isNaN(timestamp.getTime())
        ? timestamp.toLocaleDateString('zh-CN', {year: 'numeric', month: 'long', day: 'numeric'})
        : '';
      if (day && day !== previousDay) {
        const divider = el('div', 'message-day-divider');
        divider.append(el('span', '', day));
        root.append(divider);
      }
      previousDay = day;
      root.append(messageNode(message));
    });
    if (!state.messages.length) root.append(el('div', 'panel-state', '暂无符合条件的聊天消息'));
    requestAnimationFrame(() => { root.scrollTop = root.scrollHeight; });
  }

  function messageNode(message) {
    const mine = Number(message.sender_id) === userId;
    const recalled = Number(message.status) === 1;
    const row = el('article', 'message-row ' + (mine ? 'mine' : 'received') + (recalled ? ' recalled' : ''));
    row.append(el('span', 'message-avatar', initial(message.sender_name || (mine ? '我' : '同事'))));
    const bubble = el('div', 'message-bubble');
    const head = el('div', 'message-head');
    head.append(el('strong', '', mine ? '我' : (message.sender_name || '同事')));
    head.append(el('span', '', fmtTime(message.sent_at)));
    bubble.append(head);
    if (message.quoted_message_id && !recalled) {
      bubble.append(el('div', 'quoted', Number(message.quoted_status) === 1
        ? '原消息已撤回' : (message.quoted_body || '引用消息')));
    }
    bubble.append(el('div', 'message-body', recalled ? '该消息已撤回' : (message.body_text || '[附件消息]')));
    if (!recalled && message.attachments?.length) {
      const files = el('div', 'attachment-list');
      message.attachments.forEach(file => files.append(attachmentNode(file)));
      bubble.append(files);
    }
    if (!recalled && Number(message.mention_all) === 1) bubble.append(el('div', 'message-mention', '@全体成员'));
    if (!recalled && message.mentions?.length) {
      bubble.append(el('div', 'message-mention', message.mentions.map(item => '@' + item.name).join(' ')));
    }
    if (mine && !recalled) bubble.append(el('div', 'message-status',
      `已读 ${message.read_count || 0}/${message.recipients || 0}`));
    if (!recalled) {
      const actions = el('div', 'message-actions');
      actions.append(button('引用', () => beginReply(message)));
      if (mine) actions.append(button('撤回', () => recallMessage(message), 'small'));
      bubble.append(actions);
    }
    row.append(bubble);
    return row;
  }

  function beginReply(message) {
    state.replyTo = message;
    document.querySelector('#reply-preview').hidden = false;
    document.querySelector('#reply-preview-text').textContent = '回复：' + (message.body_text || '[附件消息]').slice(0, 180);
    document.querySelector('#message-text').focus();
  }

  function cancelReply() {
    state.replyTo = null;
    document.querySelector('#reply-preview').hidden = true;
    document.querySelector('#reply-preview-text').textContent = '';
  }

  async function recallMessage(message) {
    if (!confirm('确定撤回这条消息吗？超过两分钟将失败。')) return;
    try {
      await api('/api/communications/messages/' + message.id + '/recall', {method: 'POST'});
      await loadMessages();
      await loadConversations();
    } catch (error) {
      document.querySelector('#compose-state').textContent = error.message;
    }
  }

  async function sendMessage(event) {
    event.preventDefault();
    if (!state.conversation) return;
    const submit = document.querySelector('#message-form button[type=submit]');
    const conversationId = Number(state.conversation.id);
    const text = document.querySelector('#message-text').value;
    const files = [...document.querySelector('#message-files').files];
    if (!text.trim() && !files.length) return;
    submit.disabled = true;
    document.querySelector('#compose-state').textContent = '发送中…';
    const data = new FormData();
    state.pendingClientId = state.pendingClientId || crypto.randomUUID();
    data.set('clientMessageId', state.pendingClientId);
    data.set('content', text);
    if (state.replyTo) data.set('quotedMessageId', state.replyTo.id);
    if (document.querySelector('#mention-all').checked) data.set('mentionAll', 'true');
    [...document.querySelector('#mention-ids').selectedOptions].forEach(option => data.append('mentionIds', option.value));
    files.forEach(file => data.append('files', file));
    try {
      await api('/api/communications/conversations/' + conversationId + '/messages', {method: 'POST', body: data});
      if (Number(state.conversation?.id) === conversationId) {
        document.querySelector('#message-text').value = '';
        document.querySelector('#message-text').style.height = '';
        document.querySelector('#message-files').value = '';
        document.querySelector('#selected-file-info').textContent = '';
        document.querySelector('#mention-all').checked = false;
        document.querySelector('#mention-ids').selectedIndex = -1;
        cancelReply();
        state.pendingClientId = null;
        document.querySelector('#compose-state').textContent = '已发送';
        await loadMessages();
      }
      await loadConversations();
    } catch (error) {
      if (Number(state.conversation?.id) === conversationId)
        document.querySelector('#compose-state').textContent = error.message + '，可直接重试';
    } finally {
      submit.disabled = false;
    }
  }

  async function togglePin() {
    if (!state.conversation) return;
    await api('/api/communications/conversations/' + state.conversation.id + '/pin', {
      method: 'POST', headers: {'Content-Type': 'application/x-www-form-urlencoded;charset=UTF-8'},
      body: formData({pinned: !state.conversation.pinned_at})
    });
    await openConversation(state.conversation.id);
    await loadConversations();
  }

  async function hideConversation() {
    if (!state.conversation || !confirm('隐藏后，新消息到达时会重新出现。继续吗？')) return;
    await api('/api/communications/conversations/' + state.conversation.id + '/hide', {method: 'POST'});
    state.conversation = null;
    document.querySelector('#chat-panel').hidden = true;
    document.querySelector('#empty-chat').hidden = false;
    setMobileChat(false);
    await loadConversations();
  }

  async function openMembers() {
    if (!state.conversation) return;
    const members = await api('/api/communications/conversations/' + state.conversation.id + '/members');
    const root = document.querySelector('#members-list');
    root.replaceChildren();
    const myRole = Number(state.conversation.current_role);
    const department = Number(state.conversation.group_type) === 1;
    members.filter(member => member.left_after_seq == null).forEach(member => {
      const row = el('div', 'member-row');
      const identity = el('span');
      identity.append(el('strong', '', member.name + ' · ' + member.employee_no), document.createTextNode(' '),
        el('span', 'tag', groupRoleName(member.member_role)));
      row.append(identity);
      const actions = el('div', 'member-actions');
      if (!department && myRole === 2 && Number(member.member_role) < 2) {
        actions.append(button(Number(member.member_role) === 1 ? '取消管理员' : '设为管理员', async () => {
          await api('/api/communications/groups/' + state.conversation.id + '/admins/' + member.id, {
            method: 'POST', headers: {'Content-Type': 'application/x-www-form-urlencoded;charset=UTF-8'},
            body: formData({admin: Number(member.member_role) !== 1})
          });
          await openMembers();
        }));
        actions.append(button('转让群主', async () => {
          if (!confirm('转让后您将成为管理员，确定继续吗？')) return;
          await api('/api/communications/groups/' + state.conversation.id + '/owner', {
            method: 'POST', headers: {'Content-Type': 'application/x-www-form-urlencoded;charset=UTF-8'},
            body: formData({employeeId: member.id})
          });
          document.querySelector('#members-dialog').close();
          await loadConversations();
          await openConversation(state.conversation.id);
        }));
      }
      if (!department && Number(member.id) !== userId && Number(member.member_role) < 2 &&
          (myRole === 2 || (myRole === 1 && Number(member.member_role) === 0))) {
        actions.append(button('移出', async () => {
          if (!confirm('移出后该成员保留此前历史，但无法看到之后的消息。继续吗？')) return;
          await api('/api/communications/groups/' + state.conversation.id + '/members/' + member.id + '/remove', {method: 'POST'});
          await openMembers();
        }, 'small danger'));
      }
      row.append(actions);
      root.append(row);
    });
    document.querySelector('#members-dialog').showModal();
  }

  async function openGroupSettings() {
    if (!state.conversation) return;
    document.querySelector('#group-title-input').value = state.conversation.title || '';
    document.querySelector('#group-description-input').value = state.conversation.description || '';
    await loadGroupContacts();
    const members = new Set((await api('/api/communications/conversations/' + state.conversation.id + '/members'))
      .filter(item => item.left_after_seq == null).map(item => Number(item.id)));
    const options = document.querySelector('#group-add-members');
    document.querySelector('#group-add-member-search').value = '';
    renderGroupMemberChoices(options, state.groupContacts.filter(item => !members.has(Number(item.id))));
    const department = Number(state.conversation.group_type) === 1;
    const owner = Number(state.conversation.current_role) === 2;
    options.closest('.group-member-field').hidden = department;
    document.querySelector('#group-title-input').readOnly = department;
    document.querySelector('#group-leave-button').hidden = department || owner;
    document.querySelector('#group-dissolve-button').hidden = department || !owner;
    document.querySelector('#group-settings-dialog').showModal();
  }

  async function saveGroupSettings(event) {
    event.preventDefault();
    const form = event.currentTarget;
    try {
      const title = document.querySelector('#group-title-input').value.trim();
      if (title) {
        const profile = new FormData();
        profile.set('title', title);
        profile.set('description', document.querySelector('#group-description-input').value);
        const avatar = form.querySelector('[name=avatar]').files[0];
        if (avatar) profile.set('avatar', avatar);
        await api('/api/communications/groups/' + state.conversation.id + '/profile', {method: 'POST', body: profile});
      }
      const selected = selectedGroupMembers(document.querySelector('#group-add-members'));
      if (selected.length) {
        const add = new URLSearchParams();
        selected.forEach(value => add.append('employeeIds', value));
        await api('/api/communications/groups/' + state.conversation.id + '/members', {method: 'POST', body: add});
      }
      form.reset();
      document.querySelector('#group-settings-dialog').close();
      await loadConversations();
      await openConversation(state.conversation.id);
    } catch (error) {
      connectionState.textContent = error.message;
      connectionState.classList.add('error');
    }
  }

  async function leaveGroup() {
    if (!state.conversation || !confirm('退出后保留此前历史，但无法看到之后的消息。确定退出吗？')) return;
    await api('/api/communications/groups/' + state.conversation.id + '/leave', {method: 'POST'});
    document.querySelector('#group-settings-dialog').close();
    state.conversation = null;
    setMobileChat(false);
    await loadConversations();
  }

  async function dissolveGroup() {
    if (!state.conversation || !confirm('解散后群聊只读保留历史，确定继续吗？')) return;
    await api('/api/communications/groups/' + state.conversation.id + '/dissolve', {method: 'POST'});
    document.querySelector('#group-settings-dialog').close();
    await loadConversations();
  }

  function openDirectProfile() {
    const peer = state.conversation?.peer;
    if (!peer || Number(state.conversation.conversation_type) !== 0) return;
    const root = document.getElementById('contact-profile-content');
    root.replaceChildren();
    for (const [label, value] of [
      ['姓名', peer.name],
      ['工号', peer.employee_no],
      ['部门', peer.department_name || '未分配'],
      ['职位', peer.position_name || '待分配']
    ]) {
      const row = el('div', 'profile-field');
      row.append(el('span', '', label), el('strong', '', String(value || '')));
      root.append(row);
    }
    document.getElementById('contact-profile-dialog').showModal();
  }

  async function copyConversationLink() {
    if (!state.conversation) return;
    const link = location.origin + '/communications?conversation=' + encodeURIComponent(state.conversation.id);
    try {
      await navigator.clipboard.writeText(link);
      connectionState.textContent = '会话链接已复制';
    } catch {
      window.prompt('复制会话链接', link);
    }
  }

  async function openGroupFiles() {
    if (!state.conversation) return;
    const files = await api('/api/communications/conversations/' + state.conversation.id + '/files');
    document.querySelector('#chat-files-heading').textContent =
      Number(state.conversation.conversation_type) === 1 ? '群文件' : '聊天文件';
    const root = document.querySelector('#group-files-list');
    root.replaceChildren();
    files.forEach(file => {
      const row = attachmentNode(file);
      row.append(el('small', '', ' · ' + (file.sender_name || '') + ' · ' + fmtTime(file.sent_at)));
      root.append(row);
    });
    if (!root.childElementCount) root.append(el('div', 'empty', '暂无聊天文件'));
    document.querySelector('#files-dialog').showModal();
  }

  async function populateGroupMentions() {
    if (!state.conversation || state.conversation.conversation_type !== 1) return;
    const conversationId = Number(state.conversation.id);
    const members = await api('/api/communications/conversations/' + conversationId + '/members');
    if (Number(state.conversation?.id) !== conversationId) return;
    const options = document.querySelector('#mention-ids');
    options.replaceChildren();
    members.filter(member => member.left_after_seq == null && Number(member.id) !== userId).forEach(member => {
      const option = el('option', '', '@' + member.name);
      option.value = member.id;
      options.append(option);
    });
  }

  async function createGroup(event) {
    event.preventDefault();
    const form = event.currentTarget;
    const data = new FormData();
    data.set('title', form.elements.title.value);
    data.set('description', form.elements.description.value);
    selectedGroupMembers(document.querySelector('#new-group-members')).forEach(id => data.append('memberIds', id));
    if (form.elements.avatar.files[0]) data.set('avatar', form.elements.avatar.files[0]);
    try {
      const conversation = await api('/api/communications/groups', {method: 'POST', body: data});
      form.reset();
      document.querySelector('#new-group-dialog').close();
      await loadConversations();
      await openConversation(conversation.id);
    } catch (error) {
      connectionState.textContent = error.message;
      connectionState.classList.add('error');
    }
  }

  async function syncAfterReconnect(socket) {
    loadConversations();
    const conversation = state.conversation;
    const generation = state.conversationOpenRequest;
    if (!conversation) return;
    const id = Number(conversation.id);
    try {
      const current = await api('/api/communications/conversations/' + id);
      if (state.socket !== socket || socket.readyState !== WebSocket.OPEN ||
          generation !== state.conversationOpenRequest || state.conversation !== conversation) return;
      submittedRead.set(id, Math.max(submittedRead.get(id) || 0, Number(current.last_read_seq || 0)));
      await loadMessages();
    } catch (error) {
      if (state.socket === socket && generation === state.conversationOpenRequest &&
          state.conversation === conversation) {
        setMessageState(error.message, true, () => syncAfterReconnect(socket));
      }
    }
  }

  function connectSocket() {
    if (state.socket && (state.socket.readyState === WebSocket.OPEN || state.socket.readyState === WebSocket.CONNECTING)) return;
    const protocol = location.protocol === 'https:' ? 'wss:' : 'ws:';
    const reconnecting = Boolean(state.socket);
    const socket = new WebSocket(protocol + '//' + location.host + '/ws/communications');
    state.socket = socket;
    socket.addEventListener('open', () => {
      if (reconnecting) void syncAfterReconnect(socket);
      state.reconnectDelay = 1000;
      connectionState.textContent = '实时连接已建立';
      connectionState.classList.add('success');
      connectionState.classList.remove('error');
      clearInterval(state.heartbeat);
      state.heartbeat = setInterval(() => {
        if (socket.readyState === WebSocket.OPEN) socket.send(JSON.stringify({type: 'ping'}));
      }, 20000);
    });
    socket.addEventListener('message', event => {
      let frame;
      try { frame = JSON.parse(event.data); } catch { return; }
      if (frame.type === 'ready' || frame.type === 'heartbeat' || frame.type === 'pong') return;
      if (frame.type === 'session-ended') {
        location.href = '/login?reason=expired';
        return;
      }
      window.dispatchEvent(new Event('ems:communications-changed'));
      if (String(frame.type).startsWith('announcement.')) return;
      loadConversations();
      if (state.conversation && Number(frame.conversationId) === Number(state.conversation.id)) {
        loadMessages().catch(() => {});
      }
    });
    socket.addEventListener('close', () => {
      clearInterval(state.heartbeat);
      connectionState.textContent = '实时连接中断，正在重连；消息可通过刷新补偿';
      connectionState.classList.add('error');
      connectionState.classList.remove('success');
      const delay = state.reconnectDelay;
      state.reconnectDelay = Math.min(30000, delay * 2);
      setTimeout(connectSocket, delay);
    });
    socket.addEventListener('error', () => socket.close());
  }

  function bindEvents() {
    let contactTimer;
    document.querySelector('#contact-search').addEventListener('input', () => {
      clearTimeout(contactTimer);
      contactTimer = setTimeout(loadContacts, 250);
    });
    document.querySelector('#message-form').addEventListener('submit', sendMessage);
    const composer = document.querySelector('#message-text');
    composer.addEventListener('input', () => {
      composer.style.height = 'auto';
      composer.style.height = Math.min(composer.scrollHeight, 130) + 'px';
    });
    document.querySelector('#message-files').addEventListener('change', event => {
      const files = [...event.currentTarget.files];
      document.querySelector('#selected-file-info').textContent = files.length
        ? files.length === 1 ? files[0].name : `已选择 ${files.length} 个附件`
        : '';
    });
    const searchToggle = document.querySelector('#chat-search-toggle');
    const searchPanel = document.querySelector('#chat-search-panel');
    searchToggle.addEventListener('click', () => {
      const expanded = searchPanel.hidden;
      searchPanel.hidden = !expanded;
      searchToggle.setAttribute('aria-expanded', String(expanded));
      if (expanded) document.querySelector('#message-keyword').focus();
    });
    document.addEventListener('click', event => {
      const menu = document.querySelector('#chat-more-menu');
      if (menu.open && !menu.contains(event.target)) menu.open = false;
      if (menu.open && event.target.closest('#chat-more-menu button')) menu.open = false;
      document.querySelectorAll('.conversation-menu[open]').forEach(item => {
        if (!item.contains(event.target)) item.open = false;
      });
    });
    composer.addEventListener('keydown', event => {
      if (event.key === 'Enter' && !event.shiftKey && !event.isComposing) {
        event.preventDefault();
        document.querySelector('#message-form').requestSubmit();
      }
    });
    document.querySelector('#message-load-older').addEventListener('click', () => loadMessages(true));
    document.querySelector('#message-search-button').addEventListener('click', () => loadMessages());
    document.querySelector('#message-clear-search').addEventListener('click', () => {
      document.querySelector('#message-keyword').value = '';
      document.querySelector('#message-from').value = '';
      document.querySelector('#message-to').value = '';
      loadMessages();
    });
    document.querySelector('#reply-cancel').addEventListener('click', cancelReply);
    document.querySelector('#chat-pin').addEventListener('click', () => togglePin().catch(error => {
      connectionState.textContent = error.message;
      connectionState.classList.add('error');
    }));
    document.querySelector('#chat-hide').addEventListener('click', () => hideConversation().catch(error => {
      connectionState.textContent = error.message;
      connectionState.classList.add('error');
    }));
    document.querySelector('#chat-profile').addEventListener('click', openDirectProfile);
    document.querySelector('#chat-search-menu').addEventListener('click', () => {
      const panel = document.querySelector('#chat-search-panel');
      if (panel.hidden) document.querySelector('#chat-search-toggle').click();
      else document.querySelector('#message-keyword').focus();
    });
    document.querySelector('#chat-direct-files').addEventListener('click', () => openGroupFiles().catch(error => {
      connectionState.textContent = error.message;
      connectionState.classList.add('error');
    }));
    document.querySelector('#chat-copy-link').addEventListener('click', () => copyConversationLink());
    document.querySelector('#chat-members').addEventListener('click', () => openMembers().catch(error => {
      connectionState.textContent = error.message;
      connectionState.classList.add('error');
    }));
    document.querySelector('#chat-files').addEventListener('click', () => openGroupFiles().catch(error => {
      connectionState.textContent = error.message;
      connectionState.classList.add('error');
    }));
    document.querySelector('#chat-settings').addEventListener('click', () => openGroupSettings().catch(error => {
      connectionState.textContent = error.message;
      connectionState.classList.add('error');
    }));
    document.querySelector('#chat-back').addEventListener('click', () => setMobileChat(false));
    document.querySelector('#new-group').addEventListener('click', async () => {
      try {
        await loadGroupContacts();
        document.querySelector('#new-group-dialog').showModal();
      } catch (error) {
        connectionState.textContent = error.message;
        connectionState.classList.add('error');
      }
    });
    for (const [searchId, listId] of [
      ['new-group-member-search', 'new-group-members'],
      ['group-add-member-search', 'group-add-members']
    ]) {
      const list = document.getElementById(listId);
      document.getElementById(searchId).addEventListener('input', event =>
        filterGroupMembers(list, event.target.value));
      list.addEventListener('change', () => updateGroupMemberCount(list));
    }
    document.querySelector('#new-group-form').addEventListener('submit', createGroup);
    document.querySelector('#group-settings-form').addEventListener('submit', saveGroupSettings);
    document.querySelector('#group-leave-button').addEventListener('click', () => leaveGroup().catch(error => {
      connectionState.textContent = error.message;
    }));
    document.querySelector('#group-dissolve-button').addEventListener('click', () => dissolveGroup().catch(error => {
      connectionState.textContent = error.message;
    }));
    document.querySelectorAll('[data-close-dialog]').forEach(node =>
      node.addEventListener('click', () => node.closest('dialog').close()));
  }

  async function init() {
    bindEvents();
    window.addEventListener('resize', syncWorkspaceHeight);
    window.visualViewport?.addEventListener('resize', syncWorkspaceHeight);
    window.visualViewport?.addEventListener('scroll', syncWorkspaceHeight);
    requestAnimationFrame(syncWorkspaceHeight);
    await Promise.all([loadContacts(), loadConversations()]);
    const params = new URLSearchParams(location.search);
    const employee = Number(params.get('employee'));
    if (params.has('employee') && Number.isSafeInteger(employee) && employee > 0 && employee !== userId) {
      try {
        const direct = await api('/api/communications/conversations/direct', {
          method: 'POST',
          headers: {'Content-Type': 'application/x-www-form-urlencoded;charset=UTF-8'},
          body: formData({employeeId: employee})
        });
        await loadConversations();
        await openConversation(direct.id);
      } catch (error) {
        connectionState.textContent = error.message;
        connectionState.classList.add('error');
      }
    }
    const selected = Number(params.get('conversation'));
    if (Number.isSafeInteger(selected) && selected > 0 &&
        state.conversations.some(item => Number(item.id) === selected)) {
      await openConversation(selected);
    }
    connectSocket();
    setInterval(() => {
      if (document.visibilityState === 'visible') loadConversations();
    }, 45000);
  }

  init().catch(error => {
    connectionState.textContent = error.message;
    connectionState.classList.add('error');
  });
})();
