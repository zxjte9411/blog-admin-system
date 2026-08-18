import { HttpClient, HttpParams } from '@angular/common/http';
import { inject, Injectable } from '@angular/core';
import { Observable } from 'rxjs';

export type UserRole = 'AUTHOR' | 'ADMIN';
export type PreferredLanguage = 'zh-TW' | 'en';
export type PublicationStatus = 'DRAFT' | 'PUBLISHED';
export const PAGE_SIZE = 10;

export interface Page<T> {
  content: T[];
  totalPages: number;
  page?: { totalPages: number };
  totalElements?: number;
  number?: number;
  size?: number;
}

export interface Article {
  id: string;
  owner: string;
  authorAttribution: string;
  title: string;
  content: string;
  status: PublicationStatus;
  publishedAt: string | null;
  createdAt: string;
  version: number;
  tagIds: string[];
  tagNames: string[];
}

export interface CreateArticleRequest {
  title: string;
  content: string;
  status?: PublicationStatus;
  version?: number;
  tagIds?: string[];
  tagNames?: string[];
}

export interface UpdateArticleRequest {
  title: string;
  content: string;
  status: PublicationStatus;
  version: number;
  tagIds?: string[];
  tagNames?: string[];
}

export interface PublicTag {
  id: string;
  name: string;
}
export interface PublicArticle {
  id: string;
  title: string;
  content: string;
  tags: PublicTag[];
  authorAttribution: string;
  publishedAt: string;
  createdAt: string;
  updatedAt: string;
}

export interface User {
  id: string;
  displayName: string;
  preferredLanguage: PreferredLanguage;
  role: UserRole;
}

export interface ManagedUser {
  id: string;
  email: string;
  displayName: string;
  role: UserRole;
  enabled: boolean;
  verifiedAt: string | null;
}

export interface Invitation {
  id: string;
  email: string;
  expiresAt: string;
  usedAt: string | null;
}

export type InvitationRedemptionStatus = 'valid' | 'expired' | 'alreadyUsed' | 'invalid';
export interface InvitationRedemptionContext {
  status: InvitationRedemptionStatus;
  email?: string;
  expiresAt?: string;
}

export interface InvitationUser {
  id: string;
  email: string;
  displayName: string;
  role: UserRole;
  enabled: boolean;
  verifiedAt: string | null;
}

export interface PasswordMinimumLength {
  value: number;
}
export interface PasswordSettingChange {
  id: string;
  operatorId: string;
  previousValue: number;
  newValue: number;
  changedAt: string;
}

export interface LoginRequest {
  email: string;
  password: string;
}
export interface LoginResponse {
  accessToken: string;
  accessTokenExpiresAt: string;
}
export interface RegistrationRequest {
  email: string;
  displayName: string;
  password: string;
  preferredLanguage?: PreferredLanguage;
}
export interface ResendEmailVerificationRequest {
  email: string;
}
export interface EmailVerificationRequest {
  token: string;
}
export interface InvitationRedeemRequest {
  displayName: string;
  password: string;
  preferredLanguage: PreferredLanguage;
}
export interface EmailChangeRequest {
  email: string;
}
export interface Session {
  id: string;
  current: boolean;
  createdAt: string;
  lastUsedAt: string;
}
export interface ProfileRequest {
  displayName: string;
  preferredLanguage: PreferredLanguage;
}
export interface ProfileResponse {
  displayName: string;
  preferredLanguage: PreferredLanguage;
}
export interface PasswordRequest {
  currentPassword: string;
  newPassword: string;
  logoutCurrentSession?: boolean;
}
export interface ManagedUserUpdateRequest {
  role?: UserRole;
  enabled?: boolean;
}
export interface InvitationRequest {
  email: string;
}

type ArticleQuery = {
  title?: string;
  status?: PublicationStatus;
  tagId?: string;
  page?: number;
  size?: number;
};
type PublicArticleQuery = { title?: string; tagId?: string; page?: number; size?: number };
type UserQuery = { role?: UserRole; enabled?: boolean; q?: string };

function query(values: ArticleQuery | PublicArticleQuery | UserQuery): HttpParams {
  let result = new HttpParams();
  Object.entries(values).forEach(([key, value]) => {
    if (value !== undefined) result = result.set(key, String(value));
  });
  return result;
}

@Injectable({ providedIn: 'root' })
export class ArticleApi {
  private readonly http = inject(HttpClient);
  create(request: CreateArticleRequest): Observable<Article> {
    return this.http.post<Article>('/api/v1/articles', request);
  }
  list(options: ArticleQuery = {}): Observable<Page<Article>> {
    return this.http.get<Page<Article>>('/api/v1/articles', {
      params: query({ ...options, size: options.size ?? PAGE_SIZE }),
    });
  }
  get(id: string): Observable<Article> {
    return this.http.get<Article>(`/api/v1/articles/${id}`);
  }
  update(id: string, request: UpdateArticleRequest): Observable<Article> {
    return this.http.put<Article>(`/api/v1/articles/${id}`, request);
  }
  delete(id: string): Observable<void> {
    return this.http.delete<void>(`/api/v1/articles/${id}`);
  }
  deleted(page?: number, size = PAGE_SIZE): Observable<Page<Article>> {
    return this.http.get<Page<Article>>('/api/v1/articles/deleted', {
      params: query({ page, size }),
    });
  }
  restore(id: string): Observable<Article> {
    return this.http.post<Article>(`/api/v1/articles/${id}/restore`, {});
  }
  purge(id: string): Observable<void> {
    return this.http.delete<void>(`/api/v1/articles/deleted/${id}`);
  }
}

@Injectable({ providedIn: 'root' })
export class PublicArticleApi {
  private readonly http = inject(HttpClient);
  list(options: PublicArticleQuery = {}): Observable<Page<PublicArticle>> {
    return this.http.get<Page<PublicArticle>>('/api/v1/public/articles', {
      params: query({ ...options, size: options.size ?? PAGE_SIZE }),
    });
  }
  get(id: string): Observable<PublicArticle> {
    return this.http.get<PublicArticle>(`/api/v1/public/articles/${id}`);
  }
  tags(page?: number, size = PAGE_SIZE): Observable<Page<PublicTag>> {
    return this.http.get<Page<PublicTag>>('/api/v1/public/tags', { params: query({ page, size }) });
  }
}

@Injectable({ providedIn: 'root' })
export class AuthenticationApi {
  private readonly http = inject(HttpClient);
  login(request: LoginRequest): Observable<LoginResponse> {
    return this.http.post<LoginResponse>('/api/v1/auth/login', request);
  }
  googleLogin(accessToken: string, invitationToken?: string): Observable<LoginResponse> {
    return this.http.post<LoginResponse>('/api/v1/auth/google', {
      accessToken,
      ...(invitationToken ? { invitationToken } : {}),
    });
  }
  refresh(): Observable<LoginResponse> {
    return this.http.post<LoginResponse>('/api/v1/auth/refresh', {});
  }
  sessions(): Observable<Session[]> {
    return this.http.get<Session[]>('/api/v1/auth/sessions');
  }
  deleteSession(id: string): Observable<void> {
    return this.http.delete<void>(`/api/v1/auth/sessions/${id}`);
  }
  logout(): Observable<void> {
    return this.http.post<void>('/api/v1/auth/logout', {});
  }
  register(request: RegistrationRequest): Observable<void> {
    return this.http.post<void>('/api/v1/auth/registrations', request);
  }
  resendEmailVerification(request: ResendEmailVerificationRequest): Observable<void> {
    return this.http.post<void>('/api/v1/auth/email-verifications/resend', request);
  }
  verifyEmail(request: EmailVerificationRequest): Observable<void> {
    return this.http.post<void>('/api/v1/auth/email-verifications', request);
  }
  getInvitationContext(token: string): Observable<InvitationRedemptionContext> {
    return this.http.get<InvitationRedemptionContext>(`/api/v1/auth/invitations/${token}/context`);
  }
  redeemInvitation(token: string, request: InvitationRedeemRequest): Observable<InvitationUser> {
    return this.http.post<InvitationUser>(`/api/v1/auth/invitations/${token}/redeem`, request);
  }
}

@Injectable({ providedIn: 'root' })
export class UserApi {
  private readonly http = inject(HttpClient);
  me(): Observable<User> {
    return this.http.get<User>('/api/v1/account/me');
  }
  profile(request: ProfileRequest): Observable<ProfileResponse> {
    return this.http.patch<ProfileResponse>('/api/v1/account/profile', request);
  }
  password(request: PasswordRequest): Observable<void> {
    return this.http.put<void>('/api/v1/account/password', request);
  }
  requestPasswordReset(email: string): Observable<void> {
    return this.http.post<void>('/api/v1/auth/password-resets', { email });
  }
  resetPassword(token: string, password: string): Observable<void> {
    return this.http.post<void>(`/api/v1/auth/password-resets/${token}`, { password });
  }
  requestEmailChange(request: EmailChangeRequest): Observable<void> {
    return this.http.post<void>('/api/v1/account/email', request);
  }
  confirmEmailChange(token: string): Observable<void> {
    return this.http.post<void>(`/api/v1/auth/email-changes/${token}`, null);
  }
}

@Injectable({ providedIn: 'root' })
export class AdminUserApi {
  private readonly http = inject(HttpClient);
  list(options: UserQuery = {}): Observable<ManagedUser[]> {
    return this.http.get<ManagedUser[]>('/api/v1/admin/users', { params: query(options) });
  }
  update(id: string, request: ManagedUserUpdateRequest): Observable<ManagedUser> {
    return this.http.patch<ManagedUser>(`/api/v1/admin/users/${id}`, request);
  }
  invite(request: InvitationRequest): Observable<void> {
    return this.http.post<void>('/api/v1/admin/invitations', request);
  }
  invitations(): Observable<Invitation[]> {
    return this.http.get<Invitation[]>('/api/v1/admin/invitations');
  }
}

@Injectable({ providedIn: 'root' })
export class AdminSettingsApi {
  private readonly http = inject(HttpClient);
  passwordMinimumLength(): Observable<PasswordMinimumLength> {
    return this.http.get<PasswordMinimumLength>('/api/v1/admin/settings/password-minimum-length');
  }
  setPasswordMinimumLength(value: number): Observable<PasswordMinimumLength> {
    return this.http.put<PasswordMinimumLength>('/api/v1/admin/settings/password-minimum-length', {
      value,
    });
  }
  passwordMinimumLengthHistory(): Observable<PasswordSettingChange[]> {
    return this.http.get<PasswordSettingChange[]>(
      '/api/v1/admin/settings/password-minimum-length/history',
    );
  }
}
