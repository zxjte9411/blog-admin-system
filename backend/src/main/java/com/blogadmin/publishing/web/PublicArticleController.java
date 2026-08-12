package com.blogadmin.publishing.web.controller;

import com.blogadmin.publishing.application.ArticleService;
import com.blogadmin.publishing.web.dto.PublicArticleView;
import com.blogadmin.publishing.web.dto.PublicTagView;
import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
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
  ResponseEntity<Page<PublicArticleView>> articles(
      @RequestParam(defaultValue = "") String title,
      @RequestParam(required = false) UUID tagId,
      Pageable p,
      HttpServletRequest req) {
    Pageable page =
        PageRequest.of(
            p.getPageNumber(), p.getPageSize(), Sort.by(Sort.Direction.DESC, "publishedAt"));
    return ResponseEntity.ok(
        service
            .publicArticleViews(title, tagId, page, req.getRemoteAddr())
            .map(PublicArticleView::of));
  }

  @GetMapping("/articles/{id}")
  ResponseEntity<PublicArticleView> article(@PathVariable UUID id, HttpServletRequest req) {
    return ResponseEntity.ok(
        PublicArticleView.of(service.publicArticleView(id, req.getRemoteAddr())));
  }

  @GetMapping("/tags")
  ResponseEntity<Page<PublicTagView>> tagList(Pageable p, HttpServletRequest req) {
    Pageable page = PageRequest.of(p.getPageNumber(), p.getPageSize(), Sort.by("name"));
    return ResponseEntity.ok(
        service
            .publicTags(page, req.getRemoteAddr())
            .map(t -> new PublicTagView(t.id(), t.name())));
  }
}
