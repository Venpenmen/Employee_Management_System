package com.example.ems.web;

import com.example.ems.model.SessionUser;
import com.example.ems.service.SalaryService;
import jakarta.servlet.http.HttpSession;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

@Controller
public class SalaryController {
    private final SalaryService service; public SalaryController(SalaryService service){this.service=service;}
    @GetMapping("/my-salaries") public String mine(@RequestParam(required=false)String month,HttpSession session,Model model){model.addAttribute("records",service.list(month,(SessionUser)session.getAttribute("user")));model.addAttribute("month",month);return "my-salaries";}
}
