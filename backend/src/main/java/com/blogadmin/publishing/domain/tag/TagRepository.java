package com.blogadmin.publishing.domain.tag;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TagRepository extends JpaRepository<Tag, UUID> {
  Optional<Tag> findByNameIgnoreCase(String name);

  default Optional<Tag> findByNormalizedName(String name) {
    return findByNameIgnoreCase(name.trim());
  }

  @Query(
      value =
          "SELECT 1 FROM (SELECT pg_advisory_xact_lock(hashtextextended(lower(:name), 0))) locked",
      nativeQuery = true)
  Integer lockNormalizedName(@Param("name") String name);

  default Tag getOrCreate(String rawName) {
    String name = rawName.trim();
    lockNormalizedName(name.toLowerCase(Locale.ROOT));
    return findByNameIgnoreCase(name)
        .orElseGet(() -> saveAndFlush(new Tag(UUID.randomUUID(), name)));
  }

  @Query(
      value = "SELECT name FROM tags WHERE id NOT IN (SELECT tag_id FROM article_tags)",
      nativeQuery = true)
  List<String> findCandidateOrphanTagNames();

  @Modifying
  @Query(
      value =
          "DELETE FROM tags WHERE lower(name) = :lowerName AND id NOT IN (SELECT tag_id FROM article_tags)",
      nativeQuery = true)
  int deleteIfOrphan(@Param("lowerName") String lowerName);

  @Query(
      "select distinct t from Tag t join Article a on t member of a.tags where a.deletedAt is null and a.status = com.blogadmin.publishing.domain.article.PublicationStatus.PUBLISHED")
  Page<Tag> findPublic(Pageable pageable);
}
