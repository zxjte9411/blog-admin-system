package com.blogadmin.publishing.web.dto;

import com.blogadmin.publishing.application.ArticleService;
import com.blogadmin.publishing.domain.PublicationStatus;
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
    long version,
    Set<UUID> tagIds) {
  public static ArticleView of(ArticleService.ArticleView a) {
    return new ArticleView(
        a.id(),
        a.owner(),
        a.authorAttribution(),
        a.title(),
        a.content(),
        a.status(),
        a.publishedAt(),
        a.version(),
        a.tagIds());
  }
}
