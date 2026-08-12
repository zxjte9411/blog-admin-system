package com.blogadmin.publishing.application;

import com.blogadmin.publishing.domain.ArticleRepository;
import com.blogadmin.publishing.domain.Tag;
import com.blogadmin.publishing.domain.TagRepository;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashSet;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ArticleCleanupService {
  private final ArticleRepository articles;
  private final TagRepository tags;

  public ArticleCleanupService(ArticleRepository a, TagRepository t) {
    articles = a;
    tags = t;
  }

  @Scheduled(cron = "0 0 3 * * *", zone = "Asia/Taipei")
  @Transactional
  public void scheduledCleanup() {
    cleanup();
  }

  @Bean
  public ApplicationRunner startupCleanup() {
    return args -> cleanup();
  }

  @Transactional
  public void cleanup() {
    var expired = articles.findByDeletedAtBefore(Instant.now().minus(30, ChronoUnit.DAYS));
    var candidates = new HashSet<Tag>();
    expired.forEach(
        article -> {
          candidates.addAll(article.getTags());
          article.getTags().clear();
          articles.delete(article);
        });
    candidates.forEach(
        tag -> {
          if (articles.countByTagsId(tag.getId()) == 0) tags.delete(tag);
        });
  }
}
