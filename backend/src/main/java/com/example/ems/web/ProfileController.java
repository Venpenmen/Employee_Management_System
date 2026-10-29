package com.example.ems.web;

import com.example.ems.model.ProfileForms;
import com.example.ems.model.SessionUser;
import com.example.ems.service.EmployeeService;
import com.example.ems.service.ProfileService;
import com.example.ems.service.ProfileService.Section;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.WebDataBinder;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.ModelAndView;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
@RequestMapping("/profile")
public class ProfileController {
    private final ProfileService profiles;
    private final EmployeeService employees;
    public ProfileController(ProfileService profiles,EmployeeService employees){this.profiles=profiles;this.employees=employees;}

    // Immutable record forms use constructor binding; there is no entity/property mass assignment.
    @InitBinder public void bind(WebDataBinder binder){binder.setDeclarativeBinding(true);}

    @GetMapping
    public String profile(HttpSession session,Model model){
        SessionUser user=user(session);
        model.addAttribute("employee",employees.detail(user.employeeNo(),user));
        model.addAttribute("archive",profiles.view(user.id(),user));
        model.addAttribute("profileEditable",true);
        return "profile";
    }
    @GetMapping("/edit")
    public String edit(HttpSession session,Model model){
        model.addAttribute("form",profiles.basicForm(user(session).id()));
        return "profile-edit";
    }
    @PostMapping
    public String save(@Valid @ModelAttribute("form") ProfileForms.Basic form,BindingResult errors,
                       HttpSession session,RedirectAttributes redirect,jakarta.servlet.http.HttpServletResponse response){
        if(!errors.hasErrors()) {
            try{profiles.saveBasic(form,user(session));}
            catch(IllegalArgumentException ex){if(ex instanceof com.example.ems.service.ProfileConflictException)response.setStatus(409);errors.reject("profile.invalid",ex.getMessage());}
        }
        if(errors.hasErrors())return "profile-edit";
        redirect.addFlashAttribute("success","个人档案已保存");return "redirect:/profile";
    }
    @GetMapping("/{kind}/new")
    public String newEntry(@PathVariable String kind,HttpSession session,Model model){
        Section section=Section.from(kind);
        model.addAttribute("form",profiles.entryForm(section,null,user(session)));
        entryModel(section,null,model);return "profile-entry";
    }
    @GetMapping("/{kind}/{id}/edit")
    public String editEntry(@PathVariable String kind,@PathVariable Long id,HttpSession session,Model model){
        Section section=Section.from(kind);
        model.addAttribute("form",profiles.entryForm(section,id,user(session)));
        entryModel(section,id,model);return "profile-entry";
    }
    @PostMapping({"/education","/education/{id}"})
    public String education(@PathVariable(required=false) Long id,@Valid @ModelAttribute("form") ProfileForms.Education form,
                            BindingResult errors,HttpSession session,Model model,RedirectAttributes redirect){
        return saveEntry(Section.EDUCATION,id,form,errors,session,model,redirect);
    }
    @PostMapping({"/experience","/experience/{id}"})
    public String experience(@PathVariable(required=false) Long id,@Valid @ModelAttribute("form") ProfileForms.Experience form,
                            BindingResult errors,HttpSession session,Model model,RedirectAttributes redirect){
        return saveEntry(Section.EXPERIENCE,id,form,errors,session,model,redirect);
    }
    @PostMapping({"/skills","/skills/{id}"})
    public String skills(@PathVariable(required=false) Long id,@Valid @ModelAttribute("form") ProfileForms.Skill form,
                            BindingResult errors,HttpSession session,Model model,RedirectAttributes redirect){
        return saveEntry(Section.SKILLS,id,form,errors,session,model,redirect);
    }
    @PostMapping({"/certificates","/certificates/{id}"})
    public String certificates(@PathVariable(required=false) Long id,@Valid @ModelAttribute("form") ProfileForms.Certificate form,
                            BindingResult errors,HttpSession session,Model model,RedirectAttributes redirect){
        return saveEntry(Section.CERTIFICATES,id,form,errors,session,model,redirect);
    }
    @PostMapping("/{kind}/{id}/delete")
    public String delete(@PathVariable String kind,@PathVariable Long id,HttpSession session,RedirectAttributes redirect){
        profiles.deleteEntry(Section.from(kind),id,user(session));
        redirect.addFlashAttribute("success","记录已删除");return "redirect:/profile";
    }
    private String saveEntry(Section section,Long id,ProfileForms.Entry form,BindingResult errors,HttpSession session,Model model,RedirectAttributes redirect){
        // Check ownership even when submitted values fail validation.
        if(id!=null)profiles.ownedEntry(section,id,user(session));
        if(!errors.hasErrors()){
            try{profiles.saveEntry(section,id,form,user(session));}
            catch(IllegalArgumentException ex){errors.reject("profile.invalid",ex.getMessage());}
        }
        if(errors.hasErrors()){entryModel(section,id,model);return "profile-entry";}
        redirect.addFlashAttribute("success",section.label+"已保存");return "redirect:/profile";
    }
    private void entryModel(Section section,Long id,Model model){
        model.addAttribute("kind",section.route);model.addAttribute("sectionLabel",section.label);
        model.addAttribute("entryId",id);
        model.addAttribute("saveUrl","/profile/"+section.route+(id==null?"":"/"+id));
    }
    @ExceptionHandler(ResponseStatusException.class)
    public ModelAndView accessError(ResponseStatusException ex){
        ModelAndView result=new ModelAndView("error-page");
        result.setStatus(ex.getStatusCode());result.addObject("message",ex.getReason());
        result.addObject("path","/profile");return result;
    }
    private SessionUser user(HttpSession session){
        SessionUser user=(SessionUser)session.getAttribute("user");
        if(user==null)throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"请先登录");
        return user;
    }
}
