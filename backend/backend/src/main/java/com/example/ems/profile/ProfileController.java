package com.example.ems.profile;

import com.example.ems.common.ConflictException;
import com.example.ems.model.SessionUser;
import com.example.ems.service.EmployeeService;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.WebDataBinder;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.List;

/**
 * 本人个人档案。所有路径都只操作当前登录用户自己的档案：
 * 不存在“按员工 ID 编辑”的入口，明细的编辑和删除都校验记录归属。
 */
@Controller
@RequestMapping("/profile")
public class ProfileController {
    private static final String[] BASIC_FIELDS = {"version", "phone", "email", "address", "emergencyName",
            "emergencyRelation", "emergencyPhone", "gender", "biography"};
    private static final String[] ENTRY_FIELDS = {"school", "degree", "major", "startMonth", "endMonth", "currentEntry",
            "company", "department", "position", "description", "name", "proficiency", "notes", "issuer",
            "certificateNumber", "obtainedDate", "expiryDate"};

    private final ProfileService profiles;
    private final EmployeeService employees;
    private final ProfileCsrf csrf;

    public ProfileController(ProfileService profiles, EmployeeService employees, ProfileCsrf csrf) {
        this.profiles = profiles; this.employees = employees; this.csrf = csrf;
    }

    /**
     * 只绑定白名单字段：请求中额外的员工 ID、角色、部门、工资等参数一律忽略。
     * （@InitBinder 执行时表单对象可能尚未创建，因此使用两类表单字段的并集；两类表单本身也只有各自字段。）
     */
    @InitBinder("form")
    void allowFields(WebDataBinder binder) {
        String[] allowed = new String[BASIC_FIELDS.length + ENTRY_FIELDS.length];
        System.arraycopy(BASIC_FIELDS, 0, allowed, 0, BASIC_FIELDS.length);
        System.arraycopy(ENTRY_FIELDS, 0, allowed, BASIC_FIELDS.length, ENTRY_FIELDS.length);
        binder.setAllowedFields(allowed);
    }

    @GetMapping
    public String profile(HttpSession session, Model model) {
        SessionUser u = user(session);
        model.addAttribute("employee", employees.detail(u.employeeNo(), u));
        model.addAttribute("archive", profiles.archive(u.id()));
        model.addAttribute("profileEditable", true);
        model.addAttribute("profileCsrf", csrf.token(session));
        return "profile";
    }

    // ------------------------------------------------------------ 基本档案

    @GetMapping("/edit")
    public String editBasic(HttpSession session, Model model) {
        model.addAttribute("form", profiles.basicForm(user(session).id()));
        model.addAttribute("profileCsrf", csrf.token(session));
        return "profile-edit";
    }

    @PostMapping
    public String saveBasic(@ModelAttribute("form") BasicProfileForm form, BindingResult result,
                            @RequestParam(name = ProfileCsrf.PARAMETER, required = false) String token,
                            HttpSession session, Model model, HttpServletResponse response, RedirectAttributes ra) {
        csrf.verify(session, token);
        model.addAttribute("profileCsrf", csrf.token(session));
        if (result.hasErrors()) { // 类型转换失败（如版本号被篡改）
            response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            return "profile-edit";
        }
        List<String> changed;
        try {
            changed = profiles.saveBasic(user(session).id(), form, result);
        } catch (ConflictException ex) {
            result.reject("conflict", ex.getMessage());
            response.setStatus(HttpServletResponse.SC_CONFLICT);
            return "profile-edit";
        } catch (ProfileService.ProfileRejectedException ex) {
            response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            return "profile-edit";
        }
        if (result.hasErrors()) {
            response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            return "profile-edit";
        }
        ra.addFlashAttribute("success", changed.isEmpty() ? "资料没有变化" : "个人资料已保存");
        return "redirect:/profile#contact";
    }

    // ------------------------------------------------------------ 明细

    @GetMapping("/{kind}/new")
    public String newEntry(@PathVariable String kind, HttpSession session, Model model) {
        ProfileSection section = ProfileSection.fromKind(kind);
        return entryPage(section, null, new ProfileEntryForm(), session, model);
    }

    @PostMapping("/{kind}")
    public String createEntry(@PathVariable String kind, @ModelAttribute("form") ProfileEntryForm form, BindingResult result,
                              @RequestParam(name = ProfileCsrf.PARAMETER, required = false) String token,
                              HttpSession session, Model model, HttpServletResponse response, RedirectAttributes ra) {
        ProfileSection section = ProfileSection.fromKind(kind);
        csrf.verify(session, token);
        if (!result.hasErrors()) profiles.createEntry(section, user(session).id(), form, result);
        if (result.hasErrors()) {
            response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            return entryPage(section, null, form, session, model);
        }
        ra.addFlashAttribute("success", section.label() + "已添加");
        return "redirect:/profile#" + section.kind();
    }

    @GetMapping("/{kind}/{id}/edit")
    public String editEntry(@PathVariable String kind, @PathVariable Long id, HttpSession session, Model model) {
        ProfileSection section = ProfileSection.fromKind(kind);
        return entryPage(section, id, profiles.entryForm(section, id, user(session).id()), session, model);
    }

    @PostMapping("/{kind}/{id}")
    public String updateEntry(@PathVariable String kind, @PathVariable Long id,
                              @ModelAttribute("form") ProfileEntryForm form, BindingResult result,
                              @RequestParam(name = ProfileCsrf.PARAMETER, required = false) String token,
                              HttpSession session, Model model, HttpServletResponse response, RedirectAttributes ra) {
        ProfileSection section = ProfileSection.fromKind(kind);
        csrf.verify(session, token);
        if (!result.hasErrors()) profiles.updateEntry(section, id, user(session).id(), form, result);
        if (result.hasErrors()) {
            response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            return entryPage(section, id, form, session, model);
        }
        ra.addFlashAttribute("success", section.label() + "已保存");
        return "redirect:/profile#" + section.kind();
    }

    @PostMapping("/{kind}/{id}/delete")
    public String deleteEntry(@PathVariable String kind, @PathVariable Long id,
                              @RequestParam(name = ProfileCsrf.PARAMETER, required = false) String token,
                              HttpSession session, RedirectAttributes ra) {
        ProfileSection section = ProfileSection.fromKind(kind);
        csrf.verify(session, token);
        profiles.deleteEntry(section, id, user(session).id());
        ra.addFlashAttribute("success", section.label() + "已删除");
        return "redirect:/profile#" + section.kind();
    }

    private String entryPage(ProfileSection section, Long id, ProfileEntryForm form, HttpSession session, Model model) {
        model.addAttribute("form", form);
        model.addAttribute("kind", section.kind());
        model.addAttribute("sectionLabel", section.label());
        model.addAttribute("entryId", id);
        model.addAttribute("saveUrl", id == null ? "/profile/" + section.kind() : "/profile/" + section.kind() + "/" + id);
        model.addAttribute("profileCsrf", csrf.token(session));
        return "profile-entry";
    }

    private SessionUser user(HttpSession session) { return (SessionUser) session.getAttribute("user"); }
}
