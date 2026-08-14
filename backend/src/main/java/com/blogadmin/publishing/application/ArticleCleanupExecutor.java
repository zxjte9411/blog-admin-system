package com.blogadmin.publishing.application;

import com.blogadmin.publishing.domain.article.ArticleRepository;
import com.blogadmin.publishing.domain.tag.TagRepository;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ArticleCleanupExecutor {
  private final ArticleRepository articleRepository;
  private final TagRepository tagRepository;

  @Transactional
  public void cleanup() {
    var expiredArticles =
        articleRepository.findByDeletedAtBefore(Instant.now().minus(30, ChronoUnit.DAYS));
    expiredArticles.forEach(
        article -> {
          article.getTags().clear();
          articleRepository.delete(article);
        });
    articleRepository.flush();
    List<String> candidateNames = tagRepository.findCandidateOrphanTagNames();
    List<String> sortedNormalizedNames =
        candidateNames.stream()
            .map(String::trim)
            .filter(name -> !name.isBlank())
            .map(name -> name.toLowerCase(Locale.ROOT))
            .distinct()
            .sorted(Comparator.naturalOrder())
            .toList();
    for (String normalized : sortedNormalizedNames) {
      tagRepository.lockNormalizedName(normalized);
      tagRepository.deleteIfOrphan(normalized);
    }
  }
}
