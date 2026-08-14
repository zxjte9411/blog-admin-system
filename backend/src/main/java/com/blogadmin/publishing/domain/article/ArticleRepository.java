package com.blogadmin.publishing.domain.article;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ArticleRepository extends JpaRepository<Article, UUID> {
  @Override
  @EntityGraph(attributePaths = {"tags", "owner"})
  Optional<Article> findById(UUID id);

  Page<Article> findByDeletedAtIsNull(Pageable pageable);

  Page<Article> findByDeletedAtIsNullAndStatus(PublicationStatus status, Pageable pageable);

  Page<Article> findByDeletedAtIsNullAndTagsId(UUID tagId, Pageable pageable);

  Page<Article> findByDeletedAtNotNull(Pageable pageable);

  Page<Article> findByDeletedAtNotNullAndOwnerId(UUID ownerId, Pageable pageable);

  long countByTagsId(UUID tagId);

  List<Article> findByDeletedAtBefore(Instant deletedAt);

  @Query(
      "select distinct a from Article a left join a.tags t where a.deletedAt is null and lower(a.title) like lower(concat('%', :title, '%')) and (:status is null or a.status = :status) and (:tagId is null or t.id = :tagId)")
  Page<Article> search(
      @Param("title") String title,
      @Param("status") PublicationStatus status,
      @Param("tagId") UUID tagId,
      Pageable pageable);

  @Query(
      "select distinct a from Article a left join a.tags t where a.deletedAt is null and a.owner.id = :ownerId and lower(a.title) like lower(concat('%', :title, '%')) and (:status is null or a.status = :status) and (:tagId is null or t.id = :tagId)")
  Page<Article> searchByOwner(
      @Param("ownerId") UUID ownerId,
      @Param("title") String title,
      @Param("status") PublicationStatus status,
      @Param("tagId") UUID tagId,
      Pageable pageable);

  @Query(
      "select distinct a from Article a left join a.tags t where a.deletedAt is null and a.status = com.blogadmin.publishing.domain.article.PublicationStatus.PUBLISHED and lower(a.title) like lower(concat('%', :title, '%')) and (:tagId is null or t.id = :tagId)")
  Page<Article> searchPublic(
      @Param("title") String title, @Param("tagId") UUID tagId, Pageable pageable);
}
