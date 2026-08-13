package com.blogadmin.publishing.web.dto;

import com.blogadmin.publishing.application.ArticleService;
import com.blogadmin.publishing.domain.article.PublicationStatus;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;

public record ArticleView(
    UUID id,
    UUID owner,
    String authorAttribution,
    String title,
    String content,
    PublicationStatus status,
    Instant publishedAt,
    Instant createdAt,
    long version,
    Set<UUID> tagIds,
    Set<String> tagNames) {
  public static ArticleView of(ArticleService.ArticleView a) {
    return new ArticleView(
        a.id(),
        a.owner(),
        a.authorAttribution(),
        a.title(),
        a.content(),
        a.status(),
        a.publishedAt(),
        a.createdAt(),
        a.version(),
        a.tagIds(),
        a.tagNames());
  }
}
