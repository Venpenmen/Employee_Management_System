package com.example.ems.web;

import com.example.ems.model.SessionUser;
import com.example.ems.service.LeaveService;
import jakarta.servlet.http.HttpSession;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.time.LocalDateTime;

@Controller @RequestMapping("/leaves")
public class LeaveController {
    private final LeaveService service;
    public LeaveController(LeaveService service){this.service=service;}
    @GetMapping("/my") public String mine(HttpSession s,Model m){m.addAttribute("requests",service.mine(user(s)));m.addAttribute("leaveTypes",service.types());return "leaves-my";}
    @PostMapping public String submit(@RequestParam String leaveType,@RequestParam @DateTimeFormat(pattern="yyyy-MM-dd'T'HH:mm") LocalDateTime startTime,@RequestParam @DateTimeFormat(pattern="yyyy-MM-dd'T'HH:mm") LocalDateTime endTime,@RequestParam String reason,HttpSession s,RedirectAttributes ra){try{String no=service.submit(leaveType,startTime,endTime,reason,user(s));ra.addFlashAttribute("success","请假申请已提交，申请编号："+no);}catch(Exception e){ra.addFlashAttribute("error",UserErrors.message(e));}return "redirect:/leaves/my";}
    @PostMapping("/{requestNo}/withdraw") public String withdraw(@PathVariable String requestNo,HttpSession s,RedirectAttributes ra){try{service.withdraw(requestNo,user(s));ra.addFlashAttribute("success","请假申请已撤回");}catch(Exception e){ra.addFlashAttribute("error",UserErrors.message(e));}return "redirect:/leaves/my";}
    @GetMapping("/approvals") public String approvals(HttpSession s,Model m){m.addAttribute("requests",service.pending(user(s)));return "leave-approvals";}
    @PostMapping("/{requestNo}/approve") public String approve(@PathVariable String requestNo,@RequestParam(required=false)String opinion,HttpSession s,RedirectAttributes ra){try{service.decide(requestNo,true,opinion,user(s));ra.addFlashAttribute("success","已通过请假申请");}catch(Exception e){ra.addFlashAttribute("error",UserErrors.message(e));}return "redirect:/leaves/approvals";}
    @PostMapping("/{requestNo}/reject") public String reject(@PathVariable String requestNo,@RequestParam String opinion,HttpSession s,RedirectAttributes ra){try{service.decide(requestNo,false,opinion,user(s));ra.addFlashAttribute("success","已驳回请假申请");}catch(Exception e){ra.addFlashAttribute("error",UserErrors.message(e));}return "redirect:/leaves/approvals";}
    private SessionUser user(HttpSession s){return (SessionUser)s.getAttribute("user");}
}
