(() => {
    const checkout = document.querySelector('form[action="/attendance/check-out"]');
    if (checkout) {
        checkout.addEventListener("submit", event => {
            const warning = checkout.dataset.missingCheckIn === "true";
            const message = warning
                ? "今日尚未签到。确认记录实际签退时间、标记缺卡并将异常原因提交高层审核？"
                : "确认进行下班打卡？";
            if (!window.confirm(message)) event.preventDefault();
        });
        const button = checkout.querySelector("button");
        async function syncWindow() {
            if (document.hidden || !button || document.querySelector("textarea:focus")) return;
            try {
                const response = await fetch("/api/home/action-items", { cache: "no-store" });
                if (!response.ok) return;
                const items = await response.json();
                const enabled = items.some(item => item.type === "ATTENDANCE_OUT");
                if (enabled === button.disabled) window.location.reload();
            } catch {
                // A refresh failure must not block the punch form.
            }
        }
        window.setInterval(syncWindow, 60_000);
        document.addEventListener("visibilitychange", syncWindow);
    }
    const form = document.querySelector('form[data-auto-read="true"]');
    if (!form) return;
    fetch(form.action, {
        method: "POST",
        body: new URLSearchParams(new FormData(form))
    }).then(response => {
        if (response.ok && new URL(response.url).pathname === "/attendance/my") form.hidden = true;
    }).catch(() => {
        // Keep the manual mark-as-read form available.
    });
})();
