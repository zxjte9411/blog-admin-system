package com.blogadmin;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.data.web.config.EnableSpringDataWebSupport.PageSerializationMode;
import org.springframework.data.web.config.SpringDataWebSettings;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class BlogAdminApplication {
  public static void main(String[] args) {
    SpringApplication.run(BlogAdminApplication.class, args);
  }

  @Bean
  SpringDataWebSettings springDataWebSettings() {
    return new SpringDataWebSettings(PageSerializationMode.VIA_DTO);
  }
}
