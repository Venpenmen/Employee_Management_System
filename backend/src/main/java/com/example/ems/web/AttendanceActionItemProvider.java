package com.example.ems.web;

import com.example.ems.model.SessionUser;
import com.example.ems.service.AttendanceService;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
class AttendanceActionItemProvider implements HomeActionItemProvider {
    private final AttendanceService attendance;

    AttendanceActionItemProvider(AttendanceService attendance) {
        this.attendance = attendance;
    }

    @Override
    public List<HomeActionItem> items(SessionUser user) {
        var options = attendance.punchOptions(user);
        List<HomeActionItem> items = new ArrayList<>();
        if (!"CLOSED".equals(options.checkIn()))
            items.add(new HomeActionItem("attendance-in-" + options.date(), "ATTENDANCE_IN", "现在可以签到",
                    "/attendance/my", 10, options.checkIn()));
        if (!"CLOSED".equals(options.checkOut()))
            items.add(new HomeActionItem("attendance-out-" + options.date(), "ATTENDANCE_OUT",
                    options.missingCheckIn() ? "今日未签到，可申请异常签退" : "现在可以签退",
                    "/attendance/my", 10, options.checkOut()));
        for (var request : attendance.personalActionRequests(user)) {
            String status = request.get("status").toString();
            String direction = "IN".equals(request.get("direction")) ? "签到" : "签退";
            String message = switch (status) {
                case "PENDING" -> "待核实";
                case "APPROVED" -> "已通过";
                default -> "已驳回";
            };
            items.add(new HomeActionItem("attendance-request-" + request.get("id"), "ATTENDANCE_RESULT",
                    "异常" + direction + message, "/attendance/my?requestId=" + request.get("id") + "#attendance-requests",
                    20, status));
        }
        if (attendance.hasPendingReview(user))
            items.add(new HomeActionItem("attendance-review", "ATTENDANCE_REVIEW", "有异常打卡申请待核实",
                    "/attendance/manage#attendance-reviews", 30, "PENDING"));
        return items;
    }
}
