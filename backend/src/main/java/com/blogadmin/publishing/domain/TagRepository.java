package com.blogadmin.publishing.domain;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface TagRepository extends JpaRepository<Tag, UUID> {
  Optional<Tag> findByNameIgnoreCase(String name);

  default Optional<Tag> findByNormalizedName(String name) {
    return findByNameIgnoreCase(name.trim());
  }

  @Query(
      "select distinct t from Tag t join Article a on t member of a.tags where a.deletedAt is null and a.status = com.blogadmin.publishing.domain.PublicationStatus.PUBLISHED")
  Page<Tag> findPublic(Pageable pageable);
}
