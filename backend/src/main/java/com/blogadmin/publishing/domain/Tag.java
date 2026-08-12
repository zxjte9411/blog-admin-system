package com.blogadmin.publishing.domain;

import jakarta.persistence.*;
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
