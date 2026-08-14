package com.blogadmin.publishing.domain.tag;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "tags")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Tag {
  @Id private UUID id;
  private String name;

  public Tag(UUID id, String name) {
    this.id = id;
    this.name = name.trim();
  }
}
