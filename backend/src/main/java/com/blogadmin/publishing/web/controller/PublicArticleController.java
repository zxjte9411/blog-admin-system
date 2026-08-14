package com.blogadmin.publishing.web.controller;

import com.blogadmin.publishing.application.ArticleService;
import com.blogadmin.publishing.web.dto.PublicArticleView;
import com.blogadmin.publishing.web.dto.PublicTagView;
import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
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
@RequiredArgsConstructor
@RequestMapping("/api/v1/public")
public class PublicArticleController {
  private final ArticleService articleService;

  @GetMapping("/articles")
  ResponseEntity<Page<PublicArticleView>> articles(
      @RequestParam(defaultValue = "") String title,
      @RequestParam(required = false) UUID tagId,
      Pageable pageable,
      HttpServletRequest request) {
    Pageable page =
        PageRequest.of(
            pageable.getPageNumber(),
            pageable.getPageSize(),
            Sort.by(Sort.Direction.DESC, "publishedAt"));
    return ResponseEntity.ok(
        articleService
            .publicArticleViews(title, tagId, page, request.getRemoteAddr())
            .map(PublicArticleView::of));
  }

  @GetMapping("/articles/{id}")
  ResponseEntity<PublicArticleView> article(@PathVariable UUID id, HttpServletRequest request) {
    return ResponseEntity.ok(
        PublicArticleView.of(articleService.publicArticleView(id, request.getRemoteAddr())));
  }

  @GetMapping("/tags")
  ResponseEntity<Page<PublicTagView>> tagList(Pageable pageable, HttpServletRequest request) {
    Pageable page =
        PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(), Sort.by("name"));
    return ResponseEntity.ok(
        articleService
            .publicTags(page, request.getRemoteAddr())
            .map(tag -> new PublicTagView(tag.id(), tag.name())));
  }
}
