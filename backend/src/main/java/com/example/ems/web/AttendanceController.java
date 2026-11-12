package com.example.ems.web;

import com.example.ems.model.SessionUser;
import com.example.ems.service.AttendanceService;
import com.example.ems.service.DepartmentService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.time.LocalTime;
import java.time.LocalDate;

@Controller @RequestMapping("/attendance")
public class AttendanceController {
    private final AttendanceService service; private final DepartmentService departments;
    public AttendanceController(AttendanceService service,DepartmentService departments){this.service=service;this.departments=departments;}

    @GetMapping("/my") public String mine(@RequestParam(required=false)String month,@RequestParam(required=false) Long requestId,HttpSession s,Model m){
        SessionUser u=user(s);String selected=month==null?service.currentMonth():month;m.addAttribute("today",service.today(u));m.addAttribute("rule",service.rule());var records=service.myRecords(u,selected);m.addAttribute("records",records);m.addAttribute("summary",service.summary(records));m.addAttribute("month",selected);m.addAttribute("options",service.punchOptions(u));m.addAttribute("requests",service.myRequests(u,requestId));m.addAttribute("selectedRequestId",requestId);return "attendance-my";
    }
    @PostMapping("/check-in")
    public String checkIn(@RequestParam(required=false) String reason, HttpSession s, HttpServletRequest req, RedirectAttributes ra) {
        return punch(true, reason, s, req, ra);
    }

    @PostMapping("/check-out")
    public String checkOut(@RequestParam(required=false) String reason, HttpSession s, HttpServletRequest req, RedirectAttributes ra) {
        return punch(false, reason, s, req, ra);
    }

    private String punch(boolean in, String reason, HttpSession s, HttpServletRequest req, RedirectAttributes ra) {
        try {
            boolean pending = service.punch(user(s), in, clientIp(req), reason);
            ra.addFlashAttribute("success", pending ? "异常打卡已提交，待核实" : in ? "上班打卡成功" : "下班打卡成功");
        } catch (Exception e) {
            ra.addFlashAttribute("error", UserErrors.message(e));
        }
        return "redirect:/attendance/my";
    }

    @GetMapping("/manage") public String manage(@RequestParam(required=false)String month,@RequestParam(required=false)Long departmentId,@RequestParam(required=false)String keyword,HttpSession s,Model m){SessionUser u=user(s);String selected=month==null?service.currentMonth():month;m.addAttribute("records",service.manage(u,selected,departmentId,keyword));m.addAttribute("departments",departments.listDepartments());m.addAttribute("rule",service.rule());m.addAttribute("month",selected);m.addAttribute("keyword",keyword);m.addAttribute("reviews",service.pendingReviews(u));m.addAttribute("calendarExceptions",service.calendarExceptions());return "attendance-manage";}
    @PostMapping("/{id}/correct") public String correct(@PathVariable Long id,@RequestParam int status,@RequestParam String reason,@RequestParam(required=false)String month,HttpSession s,RedirectAttributes ra){try{service.correct(id,status,reason,user(s));ra.addFlashAttribute("success","考勤记录已修正");}catch(Exception e){ra.addFlashAttribute("error",UserErrors.message(e));}return "redirect:/attendance/manage"+(month==null?"":"?month="+month);}
    @PostMapping("/rule")
    public String rule(@RequestParam @DateTimeFormat(iso=DateTimeFormat.ISO.TIME) LocalTime workStart,
                       @RequestParam @DateTimeFormat(iso=DateTimeFormat.ISO.TIME) LocalTime workEnd,
                       @RequestParam int lateGrace, @RequestParam int earlyGrace,
                       @RequestParam int inException, @RequestParam int outException,
                       @RequestParam @DateTimeFormat(iso=DateTimeFormat.ISO.TIME) LocalTime earliestIn,
                       @RequestParam @DateTimeFormat(iso=DateTimeFormat.ISO.TIME) LocalTime latestOut,
                       HttpSession session, RedirectAttributes flash) {
        try {
            service.updateRule(workStart,workEnd,lateGrace,earlyGrace,inException,outException,earliestIn,latestOut,user(session));
            flash.addFlashAttribute("success","考勤规则将于下一考勤日生效");
        } catch (Exception e) {
            flash.addFlashAttribute("error", UserErrors.message(e));
        }
        return "redirect:/attendance/manage";
    }

    @PostMapping("/calendar")
    public String calendar(@RequestParam @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate date,
                           @RequestParam boolean workday, HttpSession session, RedirectAttributes flash) {
        try {
            service.setWorkday(date, workday, user(session));
            flash.addFlashAttribute("success","日期例外已保存");
        } catch (Exception e) {
            flash.addFlashAttribute("error", UserErrors.message(e));
        }
        return "redirect:/attendance/manage";
    }

    @PostMapping("/requests/{id}/review")
    public String review(@PathVariable long id, @RequestParam String decision,
                         @RequestParam(required=false) String opinion, HttpSession session, RedirectAttributes flash) {
        try {
            service.review(user(session), id, decision, opinion);
            flash.addFlashAttribute("success","异常申请已处理");
        } catch (Exception e) {
            flash.addFlashAttribute("error", UserErrors.message(e));
        }
        return "redirect:/attendance/manage";
    }

    @PostMapping("/requests/{id}/read")
    public String read(@PathVariable long id, HttpSession session) {
        service.readResult(user(session), id);
        return "redirect:/attendance/my?requestId=" + id;
    }

    private SessionUser user(HttpSession s){return (SessionUser)s.getAttribute("user");}
    private String clientIp(HttpServletRequest request) { return request.getRemoteAddr(); }
}
