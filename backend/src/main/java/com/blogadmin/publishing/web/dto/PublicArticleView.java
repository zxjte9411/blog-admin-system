package com.blogadmin.publishing.web.dto;

import com.blogadmin.publishing.application.ArticleService;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

public record PublicArticleView(
    UUID id,
    String title,
    String content,
    Set<PublicTagView> tags,
    String authorAttribution,
    Instant publishedAt,
    Instant createdAt,
    Instant updatedAt) {
  public static PublicArticleView of(ArticleService.PublicArticle a) {
    return new PublicArticleView(
        a.id(),
        a.title(),
        a.content(),
        a.tags().stream().map(t -> new PublicTagView(t.id(), t.name())).collect(Collectors.toSet()),
        a.authorAttribution(),
        a.publishedAt(),
        a.createdAt(),
        a.updatedAt());
  }
}
