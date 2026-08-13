package com.blogadmin.publishing.application;

import com.blogadmin.identity.domain.user.User;
import com.blogadmin.identity.domain.user.UserRole;
import com.blogadmin.publishing.domain.article.Article;
import com.blogadmin.publishing.domain.article.ArticleRepository;
import com.blogadmin.publishing.domain.article.PublicationStatus;
import com.blogadmin.publishing.domain.tag.Tag;
import com.blogadmin.publishing.domain.tag.TagRepository;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ArticleService {
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
      Set<String> tagNames) {}

  private final ArticleRepository articles;
  private final TagRepository tags;
  private final Map<String, Deque<Instant>> publicLimits = new ConcurrentHashMap<>();

  public ArticleService(ArticleRepository articles, TagRepository tags) {
    this.articles = articles;
    this.tags = tags;
  }

  public Page<Article> publicArticles(String title, UUID tagId, Pageable page, String ip) {
    checkPublicLimit(ip);
    return articles.searchPublic(title == null ? "" : title, tagId, page);
  }

  public record PublicTag(UUID id, String name) {}

  public record PublicArticle(
      UUID id,
      String title,
      String content,
      Set<PublicTag> tags,
      String authorAttribution,
      Instant publishedAt,
      Instant createdAt,
      Instant updatedAt) {}

  public Page<PublicArticle> publicArticleViews(
      String title, UUID tagId, Pageable page, String ip) {
    return publicArticles(title, tagId, page, ip)
        .map(
            a ->
                new PublicArticle(
                    a.getId(),
                    a.getTitle(),
                    a.getContent(),
                    a.getTags().stream()
                        .map(t -> new PublicTag(t.getId(), t.getName()))
                        .collect(java.util.stream.Collectors.toSet()),
                    a.getAuthorAttribution(),
                    a.getPublishedAt(),
                    a.getCreatedAt(),
                    a.getUpdatedAt()));
  }

  public PublicArticle publicArticleView(UUID id, String ip) {
    Article a = publicArticle(id, ip);
    return new PublicArticle(
        a.getId(),
        a.getTitle(),
        a.getContent(),
        a.getTags().stream()
            .map(t -> new PublicTag(t.getId(), t.getName()))
            .collect(java.util.stream.Collectors.toSet()),
        a.getAuthorAttribution(),
        a.getPublishedAt(),
        a.getCreatedAt(),
        a.getUpdatedAt());
  }

  public Page<PublicTag> publicTags(Pageable page, String ip) {
    checkPublicLimit(ip);
    return tags.findPublic(page).map(t -> new PublicTag(t.getId(), t.getName()));
  }

  public Article publicArticle(UUID id, String ip) {
    checkPublicLimit(ip);
    return articles
        .findById(id)
        .filter(a -> a.getDeletedAt() == null && a.getStatus() == PublicationStatus.PUBLISHED)
        .orElseThrow(() -> new ArticleException(ArticleException.Code.NOT_FOUND, "Not found"));
  }

  private void checkPublicLimit(String ip) {
    Instant now = Instant.now();
    synchronized (publicLimits) {
      // ponytail: per-instance limiter; use shared/distributed storage when running multiple
      // instances.
      Instant cutoff = now.minusSeconds(60);
      publicLimits
          .entrySet()
          .removeIf(
              entry -> {
                Deque<Instant> deque = entry.getValue();
                while (!deque.isEmpty() && deque.peekFirst().isBefore(cutoff)) deque.removeFirst();
                return deque.isEmpty() && publicLimits.remove(entry.getKey(), deque);
              });
      Deque<Instant> hits = publicLimits.computeIfAbsent(ip, key -> new ArrayDeque<>());
      if (hits.size() >= 60)
        throw new ArticleException(ArticleException.Code.RATE_LIMITED, "Too many requests");
      hits.addLast(now);
    }
  }

  @Transactional
  public ArticleView create(
      User u, String t, String c, PublicationStatus s, Set<UUID> ids, Set<String> names) {
    validateContent(c);
    Article a = new Article(UUID.randomUUID(), u, t, c);
    if (s != null) a.update(t, c, s);
    replace(a, ids, names);
    return view(articles.save(a));
  }

  public Page<ArticleView> list(User u, String t, PublicationStatus s, UUID tag, Pageable p) {
    String title = t == null || t.isBlank() ? "" : t;
    return (u.getRole() == UserRole.ADMIN
            ? articles.search(title, s, tag, p)
            : articles.searchByOwner(u.getId(), title, s, tag, p))
        .map(this::view);
  }

  public ArticleView get(User u, UUID id) {
    Article a =
        articles
            .findById(id)
            .filter(x -> x.getDeletedAt() == null)
            .orElseThrow(() -> new ArticleException(ArticleException.Code.NOT_FOUND, "Not found"));
    check(u, a);
    return view(a);
  }

  @Transactional
  public ArticleView update(
      User u,
      UUID id,
      String t,
      String c,
      PublicationStatus s,
      long v,
      Set<UUID> ids,
      Set<String> names) {
    validateContent(c);
    Article a =
        articles
            .findById(id)
            .filter(x -> x.getDeletedAt() == null)
            .orElseThrow(() -> new ArticleException(ArticleException.Code.NOT_FOUND, "Not found"));
    check(u, a);
    if (v != a.getVersion())
      throw new ArticleException(ArticleException.Code.CONFLICT, "Optimistic locking conflict");
    Set<Tag> old = new HashSet<>(a.getTags());
    a.update(t, c, s);
    replace(a, ids, names);
    Article r = articles.saveAndFlush(a);
    cleanup(old);
    return view(r);
  }

  @Transactional
  public void delete(User u, UUID id) {
    Article a =
        articles
            .findById(id)
            .orElseThrow(() -> new ArticleException(ArticleException.Code.NOT_FOUND, "Not found"));
    check(u, a);
    if (a.getDeletedAt() != null)
      throw new ArticleException(ArticleException.Code.NOT_FOUND, "Not found");
    Set<Tag> old = new HashSet<>(a.getTags());
    a.delete();
    articles.saveAndFlush(a);
    cleanup(old);
  }

  public Page<ArticleView> deleted(User u, Pageable p) {
    return u.getRole().name().equals("ADMIN")
        ? articles.findByDeletedAtNotNull(p).map(this::view)
        : articles.findByDeletedAtNotNullAndOwnerId(u.getId(), p).map(this::view);
  }

  @Transactional
  public ArticleView restore(User u, UUID id) {
    Article a =
        articles
            .findById(id)
            .orElseThrow(() -> new ArticleException(ArticleException.Code.NOT_FOUND, "Not found"));
    check(u, a);
    if (a.getDeletedAt() == null
        || a.getDeletedAt().isBefore(Instant.now().minus(30, ChronoUnit.DAYS)))
      throw new ArticleException(ArticleException.Code.NOT_FOUND, "Not found");
    a.restore();
    return view(articles.save(a));
  }

  @Transactional
  public void purge(User u, UUID id) {
    if (u.getRole() != UserRole.ADMIN)
      throw new ArticleException(ArticleException.Code.FORBIDDEN, "Operation not allowed");
    Article a =
        articles
            .findById(id)
            .orElseThrow(() -> new ArticleException(ArticleException.Code.NOT_FOUND, "Not found"));
    if (a.getDeletedAt() == null)
      throw new ArticleException(ArticleException.Code.NOT_FOUND, "Not found");
    Set<Tag> old = new HashSet<>(a.getTags());
    a.getTags().clear();
    articles.delete(a);
    cleanup(old);
  }

  private ArticleView view(Article a) {
    return new ArticleView(
        a.getId(),
        a.getOwner().getId(),
        a.getAuthorAttribution(),
        a.getTitle(),
        a.getContent(),
        a.getStatus(),
        a.getPublishedAt(),
        a.getCreatedAt(),
        a.getVersion(),
        a.getTags().stream().map(Tag::getId).collect(Collectors.toSet()),
        a.getTags().stream().map(Tag::getName).collect(Collectors.toSet()));
  }

  private void check(User u, Article a) {
    if (u.getRole().name().equals("AUTHOR") && !a.getOwner().getId().equals(u.getId()))
      throw new ArticleException(ArticleException.Code.FORBIDDEN, "Operation not allowed");
  }

  private void replace(Article a, Set<UUID> ids, Set<String> names) {
    if (ids == null && names == null) return;
    Set<Tag> f = new LinkedHashSet<>();
    if (ids != null) {
      f.addAll(tags.findAllById(ids));
      if (f.size() != ids.size())
        throw new ArticleException(ArticleException.Code.BAD_REQUEST, "Unknown tag");
    }
    if (names != null)
      names.stream()
          .map(String::trim)
          .filter(n -> !n.isBlank())
          .forEach(
              n ->
                  f.add(
                      tags.findByNormalizedName(n)
                          .orElseGet(() -> tags.save(new Tag(UUID.randomUUID(), n)))));
    a.getTags().clear();
    a.getTags().addAll(f);
  }

  private void cleanup(Set<Tag> ts) {
    articles.flush();
    ts.forEach(
        t -> {
          if (articles.countByTagsId(t.getId()) == 0) {
            tags.delete(t);
            tags.flush();
          }
        });
  }

  private void validateContent(String content) {
    if (content.matches(".*<\\s*/?\\s*[A-Za-z][^>]*>.*"))
      throw new ArticleException(ArticleException.Code.BAD_REQUEST, "Content must be plain text");
  }
}
