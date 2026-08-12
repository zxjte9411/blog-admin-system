package com.blogadmin.identity.web.dto;

public record InvitationRedeemRequest(
    String displayName, String password, String preferredLanguage) {}
