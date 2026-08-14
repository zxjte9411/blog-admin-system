package com.blogadmin.publishing.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.blogadmin.publishing.domain.article.ArticleRepository;
import com.blogadmin.publishing.domain.tag.TagRepository;
import java.lang.reflect.Field;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageRequest;

class ArticleServiceRateLimitTest {

  private ArticleService service;

  @BeforeEach
  void setUp() {
    ArticleRepository articleRepository = mock(ArticleRepository.class);
    TagRepository tagRepository = mock(TagRepository.class);
    service = new ArticleService(articleRepository, tagRepository);
  }

  @Test
  void rateLimitEnforcesMax60RequestsPerMinutePerIp() {
    String ip = "192.168.1.100";
    for (int i = 0; i < 60; i++) {
      service.publicArticles("", null, PageRequest.of(0, 10), ip);
    }

    assertThatThrownBy(() -> service.publicArticles("", null, PageRequest.of(0, 10), ip))
        .isInstanceOf(ArticleException.class)
        .hasFieldOrPropertyWithValue("code", ArticleException.Code.RATE_LIMITED);
  }

  @Test
  void rateLimiterRemovesExpiredIdleIpEntries() throws Exception {
    Field field = ArticleService.class.getDeclaredField("publicLimits");
    field.setAccessible(true);
    @SuppressWarnings("unchecked")
    Map<String, Deque<Instant>> limits =
        (Map<String, Deque<Instant>>) (Map<?, ?>) field.get(service);

    limits.put("expired-ip-1", new ArrayDeque<>(Set.of(Instant.now().minusSeconds(61))));
    limits.put("active-ip-2", new ArrayDeque<>(Set.of(Instant.now())));

    service.publicArticles("", null, PageRequest.of(0, 10), "new-query-ip");

    assertThat(limits).doesNotContainKey("expired-ip-1");
    assertThat(limits).containsKey("active-ip-2");
    assertThat(limits).containsKey("new-query-ip");
  }
}
