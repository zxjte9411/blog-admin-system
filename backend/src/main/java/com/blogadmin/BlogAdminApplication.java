package com.blogadmin;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class BlogAdminApplication {
  public static void main(String[] args) {
    SpringApplication.run(BlogAdminApplication.class, args);
  }
}
