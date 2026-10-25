package com.example.ems;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class EmsApplication {
    /** 当前发行版本，须与 pom.xml 及启动器保持一致。 */
    public static final String VERSION = "1.1.3";

    public static void main(String[] args) {
        if (java.util.Arrays.stream(args).noneMatch(arg -> arg.contains("spring.profiles.active=portable"))) {
            DevelopmentBootstrap.prepare();
        }
        SpringApplication.run(EmsApplication.class, args);
    }
}
