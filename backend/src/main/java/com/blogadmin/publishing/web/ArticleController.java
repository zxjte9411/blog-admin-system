package com.blogadmin.publishing.web;

import com.blogadmin.identity.domain.User;
import com.blogadmin.publishing.domain.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.*;
import org.springframework.data.domain.*;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1/articles")
public class ArticleController {
  private final ArticleRepository articles;
  private final TagRepository tags;

  public ArticleController(ArticleRepository articles, TagRepository tags) {
    this.articles = articles;
    this.tags = tags;
  }

  @PostMapping
  public ResponseEntity<View> create(
      @AuthenticationPrincipal User user, @Valid @RequestBody Request r) {
    Article a = new Article(UUID.randomUUID(), user, r.title(), r.content());
    if (r.status() != null) a.update(r.title(), r.content(), r.status());
    replaceTags(a, r.tagIds());
    return ResponseEntity.status(201).body(View.of(articles.save(a)));
  }

  @GetMapping
  public Page<View> list(
      @RequestParam(required = false) String title,
      @RequestParam(required = false) PublicationStatus status,
      @RequestParam(required = false) UUID tagId,
      Pageable page) {
    return articles
        .search(title == null || title.isBlank() ? "" : title, status, tagId, page)
        .map(View::of);
  }

  @GetMapping("/{id}")
  public View get(@PathVariable UUID id) {
    return View.of(
        articles
            .findById(id)
            .filter(a -> a.getDeletedAt() == null)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND)));
  }

  @PutMapping("/{id}")
  public View update(
      @AuthenticationPrincipal User user,
      @PathVariable UUID id,
      @Valid @RequestBody UpdateRequest r) {
    Article a =
        articles
            .findById(id)
            .filter(x -> x.getDeletedAt() == null)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    if (user.getRole().name().equals("AUTHOR") && !a.getOwner().getId().equals(user.getId()))
      throw new ResponseStatusException(HttpStatus.FORBIDDEN);
    if (!r.version().equals(a.getVersion()))
      throw new ResponseStatusException(HttpStatus.CONFLICT, "Optimistic locking conflict");
    a.update(r.title(), r.content(), r.status());
    replaceTags(a, r.tagIds());
    return View.of(articles.save(a));
  }

  @DeleteMapping("/{id}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void delete(@AuthenticationPrincipal User user, @PathVariable UUID id) {
    Article a =
        articles.findById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    if (user.getRole().name().equals("AUTHOR") && !a.getOwner().getId().equals(user.getId()))
      throw new ResponseStatusException(HttpStatus.FORBIDDEN);
    if (a.getDeletedAt() != null) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
    a.delete();
    articles.save(a);
  }

  private void apply(Article a, Request r) {
    if (r.status() != null) a.update(r.title(), r.content(), r.status());
    replaceTags(a, r.tagIds());
  }

  private void replaceTags(Article a, Set<UUID> ids) {
    if (ids == null) return;
    Set<Tag> found = new LinkedHashSet<>(tags.findAllById(ids));
    if (found.size() != ids.size())
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown tag");
    a.getTags().clear();
    a.getTags().addAll(found);
  }

  public record Request(
      @NotBlank @Size(max = 200) String title,
      @NotBlank @Size(max = 100000) String content,
      PublicationStatus status,
      Long version,
      @Size(max = 10) Set<UUID> tagIds) {}

  public record UpdateRequest(
      @NotBlank @Size(max = 200) String title,
      @NotBlank @Size(max = 100000) String content,
      @NotNull PublicationStatus status,
      @NotNull Long version,
      @Size(max = 10) Set<UUID> tagIds) {}

  public record View(
      UUID id,
      UUID owner,
      String authorAttribution,
      String title,
      String content,
      PublicationStatus status,
      java.time.Instant publishedAt,
      long version,
      Set<UUID> tagIds) {
    static View of(Article a) {
      return new View(
          a.getId(),
          a.getOwner().getId(),
          a.getAuthorAttribution(),
          a.getTitle(),
          a.getContent(),
          a.getStatus(),
          a.getPublishedAt(),
          a.getVersion(),
          a.getTags().stream().map(Tag::getId).collect(java.util.stream.Collectors.toSet()));
    }
  }
}
