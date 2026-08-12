package com.blogadmin.publishing.application;

public class ArticleException extends RuntimeException {
  public enum Code {
    BAD_REQUEST,
    FORBIDDEN,
    NOT_FOUND,
    CONFLICT,
    RATE_LIMITED
  }

  private final Code code;

  public ArticleException(Code code, String message) {
    super(message);
    this.code = code;
  }

  public Code code() {
    return code;
  }
}
