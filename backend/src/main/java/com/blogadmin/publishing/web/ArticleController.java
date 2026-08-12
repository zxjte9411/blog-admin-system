package com.blogadmin.publishing.web;

import com.blogadmin.identity.domain.User;
import com.blogadmin.publishing.application.ArticleService;
import com.blogadmin.publishing.domain.PublicationStatus;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.Set;
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
  public ResponseEntity<View> create(
      @AuthenticationPrincipal User u, @Valid @RequestBody Request r) {
    return ResponseEntity.status(HttpStatus.CREATED)
        .body(
            View.of(
                service.create(u, r.title(), r.content(), r.status(), r.tagIds(), r.tagNames())));
  }

  @GetMapping
  public Page<View> list(
      @RequestParam(required = false) String title,
      @RequestParam(required = false) PublicationStatus status,
      @RequestParam(required = false) UUID tagId,
      Pageable p) {
    return service.list(title, status, tagId, p).map(View::of);
  }

  @GetMapping("/{id}")
  public View get(@PathVariable UUID id) {
    return View.of(service.get(id));
  }

  @PutMapping("/{id}")
  public View update(
      @AuthenticationPrincipal User u, @PathVariable UUID id, @Valid @RequestBody UpdateRequest r) {
    return View.of(
        service.update(
            u, id, r.title(), r.content(), r.status(), r.version(), r.tagIds(), r.tagNames()));
  }

  @DeleteMapping("/{id}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void delete(@AuthenticationPrincipal User u, @PathVariable UUID id) {
    service.delete(u, id);
  }

  @GetMapping("/deleted")
  public Page<View> deleted(@AuthenticationPrincipal User u, Pageable p) {
    return service.deleted(u, p).map(View::of);
  }

  @PostMapping("/{id}/restore")
  public View restore(@AuthenticationPrincipal User u, @PathVariable UUID id) {
    return View.of(service.restore(u, id));
  }

  public record Request(
      @NotBlank @Size(max = 200) String title,
      @NotBlank @Size(max = 100000) String content,
      PublicationStatus status,
      Long version,
      @Size(max = 10) Set<UUID> tagIds,
      @Size(max = 10) Set<@NotBlank @Size(max = 100) String> tagNames) {}

  public record UpdateRequest(
      @NotBlank @Size(max = 200) String title,
      @NotBlank @Size(max = 100000) String content,
      @NotNull PublicationStatus status,
      @NotNull Long version,
      @Size(max = 10) Set<UUID> tagIds,
      @Size(max = 10) Set<@NotBlank @Size(max = 100) String> tagNames) {}

  public record View(
      UUID id,
      UUID owner,
      String authorAttribution,
      String title,
      String content,
      PublicationStatus status,
      Instant publishedAt,
      long version,
      Set<UUID> tagIds) {
    static View of(ArticleService.ArticleView a) {
      return new View(
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
}
