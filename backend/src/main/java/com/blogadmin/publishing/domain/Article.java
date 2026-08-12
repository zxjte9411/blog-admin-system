package com.blogadmin.publishing.domain;

import com.blogadmin.identity.domain.User;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.*;

@Entity
@Table(name = "articles")
public class Article {
  @Id private UUID id;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "owner_id")
  private User owner;

  @Column(name = "author_attribution", nullable = false, updatable = false)
  private String authorAttribution;

  private String title;

  @Column(columnDefinition = "TEXT")
  private String content;

  @Enumerated(EnumType.STRING)
  private PublicationStatus status;

  private Instant publishedAt;

  @Column(name = "deleted_at")
  private Instant deletedAt;

  @Version private long version;

  @ManyToMany(fetch = FetchType.EAGER)
  @JoinTable(
      name = "article_tags",
      joinColumns = @JoinColumn(name = "article_id"),
      inverseJoinColumns = @JoinColumn(name = "tag_id"))
  private Set<Tag> tags = new LinkedHashSet<>();

  protected Article() {}

  public Article(UUID id, User owner, String title, String content) {
    this.id = id;
    this.owner = owner;
    this.authorAttribution = owner.getDisplayName();
    this.title = title;
    this.content = content;
    this.status = PublicationStatus.DRAFT;
  }

  public UUID getId() {
    return id;
  }

  public User getOwner() {
    return owner;
  }

  public String getAuthorAttribution() {
    return authorAttribution;
  }

  public String getTitle() {
    return title;
  }

  public String getContent() {
    return content;
  }

  public PublicationStatus getStatus() {
    return status;
  }

  public Instant getPublishedAt() {
    return publishedAt;
  }

  public Instant getDeletedAt() {
    return deletedAt;
  }

  public long getVersion() {
    return version;
  }

  public Set<Tag> getTags() {
    return tags;
  }

  public void update(String title, String content, PublicationStatus status) {
    if (publishedAt == null && status == PublicationStatus.PUBLISHED) publishedAt = Instant.now();
    this.title = title;
    this.content = content;
    this.status = status;
  }

  public void delete() {
    deletedAt = Instant.now();
  }
}
