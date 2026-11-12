package com.example.ems.communications;

import com.example.ems.model.SessionUser;
import jakarta.servlet.http.HttpSession;
import org.springframework.core.io.Resource;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

@Controller
public class CommunicationPageController {
    private final CommunicationAttachmentService attachments;

    public CommunicationPageController(CommunicationAttachmentService attachments) {
        this.attachments = attachments;
    }

    @GetMapping("/communications")
    public String page(HttpSession session, Model model) {
        SessionUser user = (SessionUser) session.getAttribute("user");
        model.addAttribute("currentUser", user);
        return "communications";
    }

    @GetMapping("/communications/attachments/{id}")
    public ResponseEntity<Resource> attachment(HttpSession session, @PathVariable long id) {
        SessionUser user = (SessionUser) session.getAttribute("user");
        return attachments.download(id, user.id());
    }
}
