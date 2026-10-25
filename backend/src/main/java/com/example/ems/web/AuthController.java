package com.example.ems.web;

import com.example.ems.model.SessionUser;
import com.example.ems.service.AuthService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

@Controller
public class AuthController {
    private final AuthService authService;
    public AuthController(AuthService authService){this.authService=authService;}

    @GetMapping("/") public String root(HttpSession session){return session.getAttribute("user")==null?"redirect:/login":"redirect:/dashboard";}
    @GetMapping("/login") public String loginPage(){return "login";}

    @PostMapping("/login")
    public String login(@RequestParam(required=false) String employeeNo,@RequestParam(required=false) String password,HttpServletRequest request,Model model){
        try{
            SessionUser user=authService.login(employeeNo,password);
            // 登录成功后轮换会话 ID，防止会话固定攻击
            request.getSession(true);
            request.changeSessionId();
            request.getSession().setAttribute("user",user);
            return "redirect:/dashboard";
        }catch(IllegalArgumentException e){
            model.addAttribute("error",e.getMessage());
            model.addAttribute("employeeNo",employeeNo==null?null:employeeNo.strip());
            return "login";
        }
    }

    @GetMapping("/register") public String registerPage(){return "register";}

    @PostMapping("/register")
    public String register(@RequestParam(required=false) String employeeNo,@RequestParam(required=false) String name,@RequestParam(required=false) String identitySuffix,
                           @RequestParam(required=false) String phone,@RequestParam(required=false) String password,@RequestParam(required=false) String confirmPassword,Model model){
        try{
            if(password==null||!password.equals(confirmPassword))throw new IllegalArgumentException("两次密码输入不一致");
            authService.register(employeeNo,name,identitySuffix,phone,password);
            model.addAttribute("success","注册成功，请登录");
            return "login";
        }catch(IllegalArgumentException e){
            model.addAttribute("error",e.getMessage());
            return "register";
        }
    }

    @PostMapping("/logout") public String logout(HttpSession session){session.invalidate();return "redirect:/login";}
    @GetMapping("/password") public String password(){return "password";}

    @PostMapping("/password")
    public String password(@RequestParam(required=false) String oldPassword,@RequestParam(required=false) String newPassword,@RequestParam(required=false) String confirmPassword,HttpSession session,Model model){
        try{
            if(newPassword==null||!newPassword.equals(confirmPassword))throw new IllegalArgumentException("两次新密码不一致");
            SessionUser u=(SessionUser)session.getAttribute("user");
            authService.changePassword(u.id(),oldPassword,newPassword);
            model.addAttribute("success","密码修改成功");
        }catch(IllegalArgumentException e){model.addAttribute("error",e.getMessage());}
        return "password";
    }
}
