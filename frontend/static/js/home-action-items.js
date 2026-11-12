(() => {
  const root = document.getElementById("home-action-items");
  if (!root) return;
  const csrf = document.querySelector('meta[name="ems-csrf"]')?.content;
  let busy = false;
  let editing = false;
  let cached = [];
  const labels = {NORMAL:"正常打卡",WARNING:"请注意打卡时间",EXCEPTION:"需要填写异常原因"};
  function action(item) {
    const isIn = item.type === "ATTENDANCE_IN";
    const exception = item.status === "EXCEPTION";
    const card = document.createElement("article");
    card.className = "ems-todo-card";
    const info = document.createElement("div");
    info.className = "ems-todo-info";
    const title = document.createElement("strong");
    title.textContent = isIn ? "上班签到" : "下班签退";
    const detail = document.createElement("p");
    detail.textContent = (item.id.split("attendance-"+(isIn?"in":"out")+"-")[1] || "") + " · " + (labels[item.status] || "待处理");
    info.append(title,detail);
    const form = document.createElement("form");
    form.method = "post";
    form.action = isIn ? "/attendance/check-in" : "/attendance/check-out";
    const token = document.createElement("input");
    token.type = "hidden"; token.name = "_csrf"; token.value = csrf || "";
    form.append(token);
    const button = document.createElement("button");
    button.type = "button";
    button.className = "ems-todo-button";
    button.textContent = isIn ? "立即签到" : "立即签退";
    const panel = document.createElement("div");
    panel.className = "ems-todo-confirm";
    panel.hidden = true;
    const prompt = document.createElement("p");
    prompt.textContent = exception ? (isIn ? "本次为异常签到，请填写原因并提交审核。" : "本次为异常签退，请填写原因并提交审核。") : (isIn ? "确认立即记录本次上班签到？" : "确认立即记录本次下班签退？");
    panel.append(prompt);
    let reason;
    if (exception) {
      reason = document.createElement("textarea");
      reason.name = "reason"; reason.required = true; reason.maxLength = 500;
      reason.rows = 3; reason.placeholder = "请填写异常打卡原因（必填）";
      panel.append(reason);
    }
    const controls = document.createElement("div");
    controls.className = "ems-todo-controls";
    const cancel = document.createElement("button");
    cancel.type = "button"; cancel.textContent = "取消";
    cancel.addEventListener("click", () => { panel.hidden=true; button.hidden=false; editing=false; });
    const confirm = document.createElement("button");
    confirm.type = "submit"; confirm.className = "ems-todo-submit";
    confirm.textContent = exception ? "提交异常申请" : "确认打卡";
    controls.append(cancel,confirm);
    panel.append(controls);
    button.addEventListener("click", () => {
      if (!csrf) { window.location.href="/attendance/my"; return; }
      button.hidden=true; panel.hidden=false; editing=true;
      if (reason) reason.focus();
    });
    form.addEventListener("submit", e => {
      if (busy || (reason && !reason.value.trim())) {
        e.preventDefault(); if(reason)reason.reportValidity(); return;
      }
      busy=true; confirm.disabled=true;
    });
    form.append(button,panel);
    card.append(info,form);
    return card;
  }
  function render(items) {
    if (editing || busy) return;
    cached = items;
    root.replaceChildren();
    if (!items.length) {root.hidden=true;return;}
    root.hidden=false;
    root.className="ems-todo-section";
    const header=document.createElement("div");header.className="ems-todo-header";
    const h=document.createElement("h2");h.textContent="待办项目";
    const count=document.createElement("span");count.textContent=items.length+" 项待处理";
    header.append(h,count);root.append(header);
    const grid=document.createElement("div");grid.className="ems-todo-grid";
    for(const item of items) {
      if(item.type==="ATTENDANCE_IN" || item.type==="ATTENDANCE_OUT") grid.append(action(item));
      else {
        const link=document.createElement("a");
        link.className="ems-todo-card ems-todo-link";
        link.href=item.href;link.textContent=item.message;grid.append(link);
      }
    }
    root.append(grid);
  }
  async function refresh() {
    if(document.hidden || busy || editing)return;
    try {const r=await fetch("/api/home/action-items",{cache:"no-store"});
      if(r.ok)render(await r.json());
    } catch(e) {console.warn("待办刷新失败",e);}
  }
  document.addEventListener("visibilitychange",refresh);
  window.addEventListener("pageshow",refresh);
  window.addEventListener("ems:attendance-changed",refresh);
  window.setInterval(refresh,60000);
  refresh();
})();
