package com.blogadmin.publishing.web.dto;

import com.blogadmin.publishing.domain.PublicationStatus;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.Set;
import java.util.UUID;

public record UpdateArticleRequest(
    @NotBlank @Size(max = 200) String title,
    @NotBlank @Size(max = 100000) String content,
    @NotNull PublicationStatus status,
    @NotNull Long version,
    @Size(max = 10) Set<UUID> tagIds,
    @Size(max = 10) Set<@NotBlank @Size(max = 100) String> tagNames) {}
