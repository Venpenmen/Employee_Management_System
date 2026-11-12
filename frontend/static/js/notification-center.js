(() => {
  'use strict';
  const shell = document.querySelector('#ems-notification-center');
  if (!shell) return;
  const toggle = shell.querySelector('#notification-toggle');
  const panel = shell.querySelector('#notification-panel');
  const itemsNode = shell.querySelector('#notification-items');
  const badge = shell.querySelector('#notification-count');
  const status = shell.querySelector('#notification-status');
  let version = 0;
  let timer = null;
  let busy = false;

  const node = (tag, cls, value) => {
    const element = document.createElement(tag);
    if (cls) element.className = cls;
    if (value != null) element.textContent = String(value);
    return element;
  };
  const validId = id => Number.isSafeInteger(Number(id)) && Number(id) > 0;
  const numeral = value => Math.min(999, Math.max(0, Number(value) || 0));
  const read = async path => {
    const response = await fetch(path, {
      credentials: 'same-origin', cache: 'no-store',
      headers: {Accept: 'application/json'}
    });
    if (response.status === 401) {
      location.assign('/login?reason=expired');
      throw new Error('登录已失效');
    }
    const payload = await response.json();
    if (!response.ok || payload.code !== 200 || !Array.isArray(payload.data))
      throw new Error(payload.msg || '通知加载失败');
    return payload.data;
  };
  const show = open => {
    panel.hidden = !open;
    toggle.setAttribute('aria-expanded', String(open));
  };
  const link = (kind, id, title, description, date, count) => {
    const href = kind === '公告' ? '/dashboard?announcement=' + id
      : '/communications?conversation=' + id;
    const a = node('a', 'notification-item');
    a.href = href;
    a.append(node('span', 'notification-kind', kind === '公告' ? '公告' : '消息'));
    const copy = node('span', 'notification-copy');
    copy.append(node('strong', '', title));
    copy.append(node('small', '', description));
    if (date) {
      const time = new Date(date);
      if (!Number.isNaN(time.getTime())) copy.append(node('time', '',
        time.toLocaleString('zh-CN', {month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit'})));
    }
    a.append(copy);
    if (count) a.append(node('span', 'notification-item-count', count > 99 ? '99+' : count));
    return a;
  };
  async function refresh() {
    if (busy || document.visibilityState === 'hidden') return;
    busy = true;
    const request = ++version;
    try {
      const [conversations, announcements] = await Promise.allSettled([
        read('/api/communications/conversations'),
        read('/api/communications/announcements?mode=inbox&limit=100')
      ]);
      if (request !== version) return;
      const chatItems = conversations.status === 'fulfilled' ? conversations.value : [];
      const inbox = announcements.status === 'fulfilled' ? announcements.value : [];
      const notificationRows = [];
      let total = 0;
      for (const c of chatItems) {
        if (!validId(c.id)) continue;
        const unread = numeral(c.unread_count);
        if (!unread) continue;
        total += unread;
        const latest = c.latest || {};
        notificationRows.push({
          kind: '消息', id: Number(c.id),
          title: c.title || (Number(c.conversation_type) === 1 ? '群聊' : '私聊'),
          description: (latest.sender_name ? latest.sender_name + '：' : '') +
            (latest.body_text || '[附件或新消息]'),
          date: latest.sent_at, count: unread
        });
      }
      for (const a of inbox) {
        if (!validId(a.id)) continue;
        const requiresAck = Number(a.display_ack ?? a.version_ack ?? a.requires_ack) === 1;
        if (a.read_at && (!requiresAck || a.confirmed_at)) continue;
        total++;
        notificationRows.push({
          kind: '公告', id: Number(a.id), title: a.display_title || a.title || '新公告',
          description: requiresAck && !a.confirmed_at ? '待阅读确认' : '尚未阅读',
          date: a.published_at, count: 0
        });
      }
      if (conversations.status === 'rejected' && announcements.status === 'rejected') {
        throw new Error('通知获取失败，请重试');
      }
      badge.textContent = total > 99 ? '99+' : String(total);
      badge.hidden = total === 0;
      toggle.setAttribute('aria-label', total ? '通知中心，' + total + ' 条未处理' : '通知中心，没有未处理通知');
      notificationRows.sort((a,b) => (Date.parse(b.date || 0) || 0) - (Date.parse(a.date || 0) || 0));
      itemsNode.replaceChildren();
      for (const row of notificationRows.slice(0, 8)) {
        itemsNode.append(link(row.kind, row.id, row.title, row.description, row.date, row.count));
      }
      if (!itemsNode.childElementCount) itemsNode.append(node('p', 'notification-empty', '暂时没有需要处理的消息'));
      status.textContent = conversations.status === 'rejected' || announcements.status === 'rejected'
        ? '部分通知暂不可用，稍后自动重试' : '仅汇总消息与公告；打开通知不会自动标记已读';
    } catch (err) {
      status.textContent = err.message || '通知获取失败';
      if (panel.hidden) return;
      itemsNode.replaceChildren(node('p', 'notification-empty', '加载失败，请重新打开通知中心重试'));
    } finally {
      busy = false;
    }
  }
  const schedule = () => {
    clearTimeout(timer);
    timer = setTimeout(refresh, 180);
  };
  toggle.addEventListener('click', event => {
    event.stopPropagation();
    const open = panel.hidden;
    show(open);
    if (open) refresh();
  });
  document.addEventListener('click', event => {
    if (!shell.contains(event.target)) show(false);
  });
  document.addEventListener('keydown', event => {
    if (event.key === 'Escape') show(false);
  });
  window.addEventListener('focus', schedule);
  document.addEventListener('visibilitychange', () => {
    if (document.visibilityState === 'visible') schedule();
  });
  window.addEventListener('ems:communications-changed', schedule);
  setInterval(() => { if (document.visibilityState === 'visible') refresh(); }, 60000);
  refresh();
})();