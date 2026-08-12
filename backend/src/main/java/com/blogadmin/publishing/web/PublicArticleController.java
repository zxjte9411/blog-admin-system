package com.blogadmin.publishing.web;

import com.blogadmin.publishing.application.ArticleService;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/public")
public class PublicArticleController {
  private final ArticleService service;

  public PublicArticleController(ArticleService service) {
    this.service = service;
  }

  @GetMapping("/articles")
  ResponseEntity<Page<PublicView>> articles(
      @RequestParam(defaultValue = "") String title,
      @RequestParam(required = false) UUID tagId,
      Pageable p,
      HttpServletRequest req) {
    Pageable page =
        PageRequest.of(
            p.getPageNumber(), p.getPageSize(), Sort.by(Sort.Direction.DESC, "publishedAt"));
    return ResponseEntity.ok(
        service.publicArticleViews(title, tagId, page, req.getRemoteAddr()).map(PublicView::of));
  }

  @GetMapping("/articles/{id}")
  ResponseEntity<PublicView> article(@PathVariable UUID id, HttpServletRequest req) {
    return ResponseEntity.ok(PublicView.of(service.publicArticleView(id, req.getRemoteAddr())));
  }

  @GetMapping("/tags")
  ResponseEntity<Page<TagView>> tagList(Pageable p, HttpServletRequest req) {
    Pageable page = PageRequest.of(p.getPageNumber(), p.getPageSize(), Sort.by("name"));
    return ResponseEntity.ok(
        service.publicTags(page, req.getRemoteAddr()).map(t -> new TagView(t.id(), t.name())));
  }

  public record PublicView(
      String title,
      String content,
      Set<TagView> tags,
      String authorAttribution,
      Instant publishedAt,
      Instant createdAt,
      Instant updatedAt) {
    static PublicView of(ArticleService.PublicArticle a) {
      return new PublicView(
          a.title(),
          a.content(),
          a.tags().stream().map(t -> new TagView(t.id(), t.name())).collect(Collectors.toSet()),
          a.authorAttribution(),
          a.publishedAt(),
          a.createdAt(),
          a.updatedAt());
    }
  }

  public record TagView(UUID id, String name) {}
}
