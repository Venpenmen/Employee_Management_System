package com.example.ems.web;

import com.example.ems.model.SessionUser;
import java.util.List;

public interface HomeActionItemProvider {
    List<HomeActionItem> items(SessionUser user);
}
