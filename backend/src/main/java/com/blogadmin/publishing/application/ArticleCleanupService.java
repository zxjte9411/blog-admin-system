package com.blogadmin.publishing.application;

import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
public class ArticleCleanupService {
  private final ArticleCleanupExecutor executor;

  public ArticleCleanupService(ArticleCleanupExecutor executor) {
    this.executor = executor;
  }

  @Scheduled(cron = "0 0 3 * * *", zone = "Asia/Taipei")
  public void scheduledCleanup() {
    cleanup();
  }

  @Bean
  public ApplicationRunner startupCleanup() {
    return args -> cleanup();
  }

  public void cleanup() {
    executor.cleanup();
  }
}
