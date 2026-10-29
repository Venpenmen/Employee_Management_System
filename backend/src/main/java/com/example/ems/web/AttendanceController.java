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
import java.time.YearMonth;

@Controller @RequestMapping("/attendance")
public class AttendanceController {
    private final AttendanceService service; private final DepartmentService departments;
    public AttendanceController(AttendanceService service,DepartmentService departments){this.service=service;this.departments=departments;}

    @GetMapping("/my") public String mine(@RequestParam(required=false)String month,HttpSession s,Model m){
        SessionUser u=user(s);String selected=month==null?YearMonth.now().toString():month;m.addAttribute("today",service.today(u));m.addAttribute("rule",service.rule());m.addAttribute("records",service.myRecords(u,selected));m.addAttribute("summary",service.mySummary(u,selected));m.addAttribute("month",selected);return "attendance-my";
    }
    @PostMapping("/check-in") public String checkIn(HttpSession s,HttpServletRequest req,RedirectAttributes ra){try{service.checkIn(user(s),clientIp(req));ra.addFlashAttribute("success","上班打卡成功");}catch(Exception e){ra.addFlashAttribute("error",UserErrors.message(e));}return "redirect:/attendance/my";}
    @PostMapping("/check-out") public String checkOut(HttpSession s,HttpServletRequest req,RedirectAttributes ra){try{service.checkOut(user(s),clientIp(req));ra.addFlashAttribute("success","下班打卡成功");}catch(Exception e){ra.addFlashAttribute("error",UserErrors.message(e));}return "redirect:/attendance/my";}
    @GetMapping("/manage") public String manage(@RequestParam(required=false)String month,@RequestParam(required=false)Long departmentId,@RequestParam(required=false)String keyword,HttpSession s,Model m){SessionUser u=user(s);String selected=month==null?YearMonth.now().toString():month;m.addAttribute("records",service.manage(u,selected,departmentId,keyword));m.addAttribute("departments",departments.listDepartments());m.addAttribute("rule",service.rule());m.addAttribute("month",selected);m.addAttribute("keyword",keyword);return "attendance-manage";}
    @PostMapping("/{id}/correct") public String correct(@PathVariable Long id,@RequestParam int status,@RequestParam String reason,@RequestParam(required=false)String month,HttpSession s,RedirectAttributes ra){try{service.correct(id,status,reason,user(s));ra.addFlashAttribute("success","考勤记录已修正");}catch(Exception e){ra.addFlashAttribute("error",UserErrors.message(e));}return "redirect:/attendance/manage"+(month==null?"":"?month="+month);}
    @PostMapping("/rule") public String rule(@RequestParam @DateTimeFormat(pattern="HH:mm") LocalTime workStart,@RequestParam @DateTimeFormat(pattern="HH:mm") LocalTime workEnd,@RequestParam int lateGrace,@RequestParam int earlyGrace,HttpSession s,RedirectAttributes ra){try{service.updateRule(workStart,workEnd,lateGrace,earlyGrace,user(s));ra.addFlashAttribute("success","考勤规则已更新");}catch(Exception e){ra.addFlashAttribute("error",UserErrors.message(e));}return "redirect:/attendance/manage";}
    private SessionUser user(HttpSession s){return (SessionUser)s.getAttribute("user");}
    private String clientIp(HttpServletRequest r){String forwarded=r.getHeader("X-Forwarded-For");return forwarded==null||forwarded.isBlank()?r.getRemoteAddr():forwarded.split(",")[0].trim();}
}
