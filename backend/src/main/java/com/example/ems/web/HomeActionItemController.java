package com.example.ems.web;

import com.example.ems.model.SessionUser;
import jakarta.servlet.http.HttpSession;
import java.util.Comparator;
import java.util.List;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
class HomeActionItemController {
    private final List<HomeActionItemProvider> providers;

    HomeActionItemController(List<HomeActionItemProvider> providers) {
        this.providers = providers;
    }

    @GetMapping("/api/home/action-items")
    ResponseEntity<List<HomeActionItem>> items(HttpSession session) {
        if (!(session.getAttribute("user") instanceof SessionUser user))
            return ResponseEntity.status(401).build();
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(providers.stream()
                .flatMap(provider -> provider.items(user).stream())
                .sorted(Comparator.comparingInt(HomeActionItem::priority).thenComparing(HomeActionItem::id))
                .toList());
    }
}
