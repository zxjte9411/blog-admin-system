package com.blogadmin.publishing.domain.tag;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;

@Entity
@Table(name = "tags")
public class Tag {
  @Id private UUID id;
  private String name;

  protected Tag() {}

  public Tag(UUID id, String name) {
    this.id = id;
    this.name = name.trim();
  }

  public UUID getId() {
    return id;
  }

  public String getName() {
    return name;
  }
}
