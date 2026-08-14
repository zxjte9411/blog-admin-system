package com.blogadmin.publishing.web.controller;

import com.blogadmin.identity.domain.user.User;
import com.blogadmin.publishing.application.ArticleService;
import com.blogadmin.publishing.domain.article.PublicationStatus;
import com.blogadmin.publishing.web.dto.ArticleView;
import com.blogadmin.publishing.web.dto.CreateArticleRequest;
import com.blogadmin.publishing.web.dto.UpdateArticleRequest;
import jakarta.validation.Valid;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
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
@RequiredArgsConstructor
@RequestMapping("/api/v1/articles")
public class ArticleController {
  private final ArticleService articleService;

  @PostMapping
  public ResponseEntity<ArticleView> create(
      @AuthenticationPrincipal User user, @Valid @RequestBody CreateArticleRequest request) {
    return ResponseEntity.status(HttpStatus.CREATED)
        .body(
            ArticleView.of(
                articleService.create(
                    user,
                    request.title(),
                    request.content(),
                    request.status(),
                    request.tagIds(),
                    request.tagNames())));
  }

  @GetMapping
  public Page<ArticleView> list(
      @RequestParam(required = false) String title,
      @RequestParam(required = false) PublicationStatus status,
      @RequestParam(required = false) UUID tagId,
      Pageable pageable,
      @AuthenticationPrincipal User user) {
    return articleService.list(user, title, status, tagId, pageable).map(ArticleView::of);
  }

  @GetMapping("/{id}")
  public ArticleView get(@PathVariable UUID id, @AuthenticationPrincipal User user) {
    return ArticleView.of(articleService.get(user, id));
  }

  @PutMapping("/{id}")
  public ArticleView update(
      @AuthenticationPrincipal User user,
      @PathVariable UUID id,
      @Valid @RequestBody UpdateArticleRequest request) {
    return ArticleView.of(
        articleService.update(
            user,
            id,
            request.title(),
            request.content(),
            request.status(),
            request.version(),
            request.tagIds(),
            request.tagNames()));
  }

  @DeleteMapping("/{id}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void delete(@AuthenticationPrincipal User user, @PathVariable UUID id) {
    articleService.delete(user, id);
  }

  @GetMapping("/deleted")
  public Page<ArticleView> deleted(@AuthenticationPrincipal User user, Pageable pageable) {
    return articleService.deleted(user, pageable).map(ArticleView::of);
  }

  @PostMapping("/{id}/restore")
  public ArticleView restore(@AuthenticationPrincipal User user, @PathVariable UUID id) {
    return ArticleView.of(articleService.restore(user, id));
  }

  @DeleteMapping("/deleted/{id}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void purge(@AuthenticationPrincipal User user, @PathVariable UUID id) {
    articleService.purge(user, id);
  }
}
