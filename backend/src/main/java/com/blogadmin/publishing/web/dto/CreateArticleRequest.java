package com.blogadmin.publishing.web.dto;

import com.blogadmin.publishing.domain.article.PublicationStatus;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.Set;
import java.util.UUID;

public record CreateArticleRequest(
    @NotBlank @Size(max = 200) String title,
    @NotBlank @Size(max = 100000) String content,
    PublicationStatus status,
    Long version,
    @Size(max = 10) Set<UUID> tagIds,
    @Size(max = 10) Set<@NotBlank @Size(max = 100) String> tagNames) {}
