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
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
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

  private final ArticleRepository articleRepository;
  private final TagRepository tagRepository;
  private final Map<String, Deque<Instant>> publicLimits = new ConcurrentHashMap<>();

  @Transactional(readOnly = true)
  public Page<Article> publicArticles(String title, UUID tagId, Pageable pageable, String ip) {
    checkPublicLimit(ip);
    return articleRepository.searchPublic(title == null ? "" : title, tagId, pageable);
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

  @Transactional(readOnly = true)
  public Page<PublicArticle> publicArticleViews(
      String title, UUID tagId, Pageable pageable, String ip) {
    return publicArticles(title, tagId, pageable, ip)
        .map(
            article ->
                new PublicArticle(
                    article.getId(),
                    article.getTitle(),
                    article.getContent(),
                    article.getTags().stream()
                        .map(tag -> new PublicTag(tag.getId(), tag.getName()))
                        .collect(Collectors.toSet()),
                    article.getAuthorAttribution(),
                    article.getPublishedAt(),
                    article.getCreatedAt(),
                    article.getUpdatedAt()));
  }

  @Transactional(readOnly = true)
  public PublicArticle publicArticleView(UUID id, String ip) {
    Article article = publicArticle(id, ip);
    return new PublicArticle(
        article.getId(),
        article.getTitle(),
        article.getContent(),
        article.getTags().stream()
            .map(tag -> new PublicTag(tag.getId(), tag.getName()))
            .collect(Collectors.toSet()),
        article.getAuthorAttribution(),
        article.getPublishedAt(),
        article.getCreatedAt(),
        article.getUpdatedAt());
  }

  @Transactional(readOnly = true)
  public Page<PublicTag> publicTags(Pageable pageable, String ip) {
    checkPublicLimit(ip);
    return tagRepository.findPublic(pageable).map(tag -> new PublicTag(tag.getId(), tag.getName()));
  }

  @Transactional(readOnly = true)
  public Article publicArticle(UUID id, String ip) {
    checkPublicLimit(ip);
    return articleRepository
        .findById(id)
        .filter(
            article ->
                article.getDeletedAt() == null
                    && article.getStatus() == PublicationStatus.PUBLISHED)
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
                while (!deque.isEmpty() && deque.peekFirst().isBefore(cutoff)) {
                  deque.removeFirst();
                }
                return deque.isEmpty() && publicLimits.remove(entry.getKey(), deque);
              });
      Deque<Instant> hits = publicLimits.computeIfAbsent(ip, key -> new ArrayDeque<>());
      if (hits.size() >= 60) {
        throw new ArticleException(ArticleException.Code.RATE_LIMITED, "Too many requests");
      }
      hits.addLast(now);
    }
  }

  @Transactional
  public ArticleView create(
      User user,
      String title,
      String content,
      PublicationStatus status,
      Set<UUID> tagIds,
      Set<String> tagNames) {
    validateContent(content);
    Article article = new Article(UUID.randomUUID(), user, title, content);
    if (status != null) {
      article.update(title, content, status);
    }
    replaceTags(article, tagIds, tagNames);
    return view(articleRepository.save(article));
  }

  @Transactional(readOnly = true)
  public Page<ArticleView> list(
      User user, String title, PublicationStatus status, UUID tagId, Pageable pageable) {
    String searchTitle = title == null || title.isBlank() ? "" : title;
    return (user.getRole() == UserRole.ADMIN
            ? articleRepository.search(searchTitle, status, tagId, pageable)
            : articleRepository.searchByOwner(user.getId(), searchTitle, status, tagId, pageable))
        .map(this::view);
  }

  @Transactional(readOnly = true)
  public ArticleView get(User user, UUID id) {
    Article article =
        articleRepository
            .findById(id)
            .filter(candidate -> candidate.getDeletedAt() == null)
            .orElseThrow(() -> new ArticleException(ArticleException.Code.NOT_FOUND, "Not found"));
    checkOwnershipOrAdmin(user, article);
    return view(article);
  }

  @Transactional
  public ArticleView update(
      User user,
      UUID id,
      String title,
      String content,
      PublicationStatus status,
      long version,
      Set<UUID> tagIds,
      Set<String> tagNames) {
    validateContent(content);
    Article article =
        articleRepository
            .findById(id)
            .filter(candidate -> candidate.getDeletedAt() == null)
            .orElseThrow(() -> new ArticleException(ArticleException.Code.NOT_FOUND, "Not found"));
    checkOwnershipOrAdmin(user, article);
    if (version != article.getVersion()) {
      throw new ArticleException(ArticleException.Code.CONFLICT, "Optimistic locking conflict");
    }
    article.update(title, content, status);
    replaceTags(article, tagIds, tagNames);
    Article savedArticle = articleRepository.saveAndFlush(article);
    return view(savedArticle);
  }

  @Transactional
  public void delete(User user, UUID id) {
    Article article =
        articleRepository
            .findById(id)
            .orElseThrow(() -> new ArticleException(ArticleException.Code.NOT_FOUND, "Not found"));
    checkOwnershipOrAdmin(user, article);
    if (article.getDeletedAt() != null) {
      throw new ArticleException(ArticleException.Code.NOT_FOUND, "Not found");
    }
    article.delete();
    articleRepository.saveAndFlush(article);
  }

  @Transactional(readOnly = true)
  public Page<ArticleView> deleted(User user, Pageable pageable) {
    return user.getRole().name().equals("ADMIN")
        ? articleRepository.findByDeletedAtNotNull(pageable).map(this::view)
        : articleRepository
            .findByDeletedAtNotNullAndOwnerId(user.getId(), pageable)
            .map(this::view);
  }

  @Transactional
  public ArticleView restore(User user, UUID id) {
    Article article =
        articleRepository
            .findById(id)
            .orElseThrow(() -> new ArticleException(ArticleException.Code.NOT_FOUND, "Not found"));
    checkOwnershipOrAdmin(user, article);
    if (article.getDeletedAt() == null
        || article.getDeletedAt().isBefore(Instant.now().minus(30, ChronoUnit.DAYS))) {
      throw new ArticleException(ArticleException.Code.NOT_FOUND, "Not found");
    }
    article.restore();
    return view(articleRepository.save(article));
  }

  @Transactional
  public void purge(User user, UUID id) {
    if (user.getRole() != UserRole.ADMIN) {
      throw new ArticleException(ArticleException.Code.FORBIDDEN, "Operation not allowed");
    }
    Article article =
        articleRepository
            .findById(id)
            .orElseThrow(() -> new ArticleException(ArticleException.Code.NOT_FOUND, "Not found"));
    if (article.getDeletedAt() == null) {
      throw new ArticleException(ArticleException.Code.NOT_FOUND, "Not found");
    }
    article.getTags().clear();
    articleRepository.delete(article);
  }

  private ArticleView view(Article article) {
    return new ArticleView(
        article.getId(),
        article.getOwner().getId(),
        article.getAuthorAttribution(),
        article.getTitle(),
        article.getContent(),
        article.getStatus(),
        article.getPublishedAt(),
        article.getCreatedAt(),
        article.getVersion(),
        article.getTags().stream().map(Tag::getId).collect(Collectors.toSet()),
        article.getTags().stream().map(Tag::getName).collect(Collectors.toSet()));
  }

  private void checkOwnershipOrAdmin(User user, Article article) {
    if (user.getRole().name().equals("AUTHOR")
        && !article.getOwner().getId().equals(user.getId())) {
      throw new ArticleException(ArticleException.Code.FORBIDDEN, "Operation not allowed");
    }
  }

  private void replaceTags(Article article, Set<UUID> tagIds, Set<String> tagNames) {
    if (tagIds == null && tagNames == null) {
      return;
    }
    Set<Tag> mergedTags = new LinkedHashSet<>();
    if (tagIds != null) {
      mergedTags.addAll(tagRepository.findAllById(tagIds));
      if (mergedTags.size() != tagIds.size()) {
        throw new ArticleException(ArticleException.Code.BAD_REQUEST, "Unknown tag");
      }
    }
    if (tagNames != null) {
      tagNames.stream()
          .map(String::trim)
          .filter(name -> !name.isBlank())
          .sorted(Comparator.comparing(name -> name.toLowerCase(Locale.ROOT)))
          .forEach(name -> mergedTags.add(tagRepository.getOrCreate(name)));
    }
    article.getTags().clear();
    article.getTags().addAll(mergedTags);
  }

  private void validateContent(String content) {
    if (content.matches(".*<\\s*/?\\s*[A-Za-z][^>]*>.*")) {
      throw new ArticleException(ArticleException.Code.BAD_REQUEST, "Content must be plain text");
    }
  }
}
