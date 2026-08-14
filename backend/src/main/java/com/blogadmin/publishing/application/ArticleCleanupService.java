package com.blogadmin.publishing.application;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class ArticleCleanupService {
  private final ArticleCleanupExecutor articleCleanupExecutor;

  @Scheduled(cron = "0 0 3 * * *", zone = "Asia/Taipei")
  public void scheduledCleanup() {
    cleanup();
  }

  @Bean
  public ApplicationRunner startupCleanup() {
    return args -> cleanup();
  }

  public void cleanup() {
    articleCleanupExecutor.cleanup();
  }
}
