package com.blogadmin.publishing.web.controller;

import com.blogadmin.identity.domain.User;
import com.blogadmin.publishing.application.ArticleService;
import com.blogadmin.publishing.domain.PublicationStatus;
import com.blogadmin.publishing.web.dto.ArticleView;
import com.blogadmin.publishing.web.dto.CreateArticleRequest;
import com.blogadmin.publishing.web.dto.UpdateArticleRequest;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/articles")
public class ArticleController {
  private final ArticleService service;

  public ArticleController(ArticleService service) {
    this.service = service;
  }

  @PostMapping
  public ResponseEntity<ArticleView> create(
      @AuthenticationPrincipal User u, @Valid @RequestBody CreateArticleRequest r) {
    return ResponseEntity.status(HttpStatus.CREATED)
        .body(
            ArticleView.of(
                service.create(u, r.title(), r.content(), r.status(), r.tagIds(), r.tagNames())));
  }

  @GetMapping
  public Page<ArticleView> list(
      @RequestParam(required = false) String title,
      @RequestParam(required = false) PublicationStatus status,
      @RequestParam(required = false) UUID tagId,
      Pageable p) {
    return service.list(title, status, tagId, p).map(ArticleView::of);
  }

  @GetMapping("/{id}")
  public ArticleView get(@PathVariable UUID id) {
    return ArticleView.of(service.get(id));
  }

  @PutMapping("/{id}")
  public ArticleView update(
      @AuthenticationPrincipal User u,
      @PathVariable UUID id,
      @Valid @RequestBody UpdateArticleRequest r) {
    return ArticleView.of(
        service.update(
            u, id, r.title(), r.content(), r.status(), r.version(), r.tagIds(), r.tagNames()));
  }

  @DeleteMapping("/{id}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void delete(@AuthenticationPrincipal User u, @PathVariable UUID id) {
    service.delete(u, id);
  }

  @GetMapping("/deleted")
  public Page<ArticleView> deleted(@AuthenticationPrincipal User u, Pageable p) {
    return service.deleted(u, p).map(ArticleView::of);
  }

  @PostMapping("/{id}/restore")
  public ArticleView restore(@AuthenticationPrincipal User u, @PathVariable UUID id) {
    return ArticleView.of(service.restore(u, id));
  }
}
