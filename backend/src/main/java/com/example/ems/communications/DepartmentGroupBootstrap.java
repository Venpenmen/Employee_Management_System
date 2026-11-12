package com.example.ems.communications;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

@Component
@Order(20)
public class DepartmentGroupBootstrap implements ApplicationRunner {
    private final CommunicationOrganizationService organizations;

    public DepartmentGroupBootstrap(CommunicationOrganizationService organizations) {
        this.organizations = organizations;
    }

    @Override
    public void run(ApplicationArguments args) {
        organizations.bootstrapAll();
    }
}
