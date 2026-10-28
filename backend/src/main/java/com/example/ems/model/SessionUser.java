package com.example.ems.model;

import java.io.Serializable;

public record SessionUser(Long id, String employeeNo, String name, int userType,
                          Long departmentId, String departmentName) implements Serializable {
    public boolean isExecutive() { return userType == 2; }
    public boolean isManager() { return userType == 1; }
    public String roleName() {
        return switch (userType) { case 2 -> "高层"; case 1 -> "经理"; default -> "员工"; };
    }
}
