package com.blogadmin.publishing.application;

import com.blogadmin.publishing.domain.article.ArticleRepository;
import com.blogadmin.publishing.domain.tag.TagRepository;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ArticleCleanupExecutor {
  private final ArticleRepository articles;
  private final TagRepository tags;

  public ArticleCleanupExecutor(ArticleRepository articles, TagRepository tags) {
    this.articles = articles;
    this.tags = tags;
  }

  @Transactional
  public void cleanup() {
    var expired = articles.findByDeletedAtBefore(Instant.now().minus(30, ChronoUnit.DAYS));
    expired.forEach(
        article -> {
          article.getTags().clear();
          articles.delete(article);
        });
    articles.flush();
    List<String> candidateNames = tags.findCandidateOrphanTagNames();
    List<String> sortedNormalizedNames =
        candidateNames.stream()
            .map(String::trim)
            .filter(n -> !n.isBlank())
            .map(n -> n.toLowerCase(Locale.ROOT))
            .distinct()
            .sorted(Comparator.naturalOrder())
            .toList();
    for (String normalized : sortedNormalizedNames) {
      tags.lockNormalizedName(normalized);
      tags.deleteIfOrphan(normalized);
    }
  }
}
