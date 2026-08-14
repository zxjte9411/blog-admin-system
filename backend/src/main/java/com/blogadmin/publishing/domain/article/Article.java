package com.blogadmin.publishing.domain.article;

import com.blogadmin.identity.domain.user.User;
import com.blogadmin.publishing.domain.tag.Tag;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinTable;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;
import org.hibernate.annotations.BatchSize;

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

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  @Version private long version;

  @ManyToMany(fetch = FetchType.LAZY)
  @BatchSize(size = 50)
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
    this.createdAt = Instant.now();
    this.updatedAt = this.createdAt;
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

  public Instant getCreatedAt() {
    return createdAt;
  }

  public Instant getUpdatedAt() {
    return updatedAt;
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
    updatedAt = Instant.now();
  }

  public void delete() {
    deletedAt = Instant.now();
  }

  public void restore() {
    deletedAt = null;
  }
}
