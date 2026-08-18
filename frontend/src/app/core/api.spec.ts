import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting, HttpTestingController } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import {
  ArticleApi,
  Article,
  AuthenticationApi,
  CreateArticleRequest,
  InvitationUser,
  LoginResponse,
  Page,
  PublicArticleApi,
  RegistrationRequest,
  UserApi,
} from './api';
import { PAGE_SIZE_OPTIONS } from './pagination';

const pageSizes = PAGE_SIZE_OPTIONS;

describe('ArticleApi', () => {
  let http: HttpTestingController;
  let api: ArticleApi;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    http = TestBed.inject(HttpTestingController);
    api = TestBed.inject(ArticleApi);
  });

  afterEach(() => http.verify());

  it('uses the article request and response contract', () => {
    const request: CreateArticleRequest = {
      title: 'Title',
      content: 'Content',
      status: 'DRAFT',
      tagIds: ['tag-id'],
      tagNames: ['tag'],
    };
    const response: Article = {
      id: 'article-id',
      owner: 'user-id',
      authorAttribution: 'Author',
      title: request.title,
      content: request.content,
      status: 'DRAFT',
      publishedAt: null,
      createdAt: '2026-01-01T00:00:00Z',
      version: 0,
      tagIds: request.tagIds ?? [],
      tagNames: request.tagNames ?? [],
    };

    api.create(request).subscribe((article) => expect(article).toEqual(response));

    const req = http.expectOne('/api/v1/articles');
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual(request);
    req.flush(response);
  });

  it('lists articles with a typed page response', () => {
    const page: Page<Article> = {
      content: [],
      totalPages: 0,
      totalElements: 0,
      number: 0,
      size: 20,
    };
    api.list({ title: 'needle', status: 'PUBLISHED' }).subscribe((result) => {
      expect(result).toEqual(page);
    });

    const req = http.expectOne('/api/v1/articles?title=needle&status=PUBLISHED&size=10');
    expect(req.request.method).toBe('GET');
    req.flush(page);
  });

  it.each(pageSizes)('sends caller page size %s for article lists', (size) => {
    api.list({ page: 1, size }).subscribe();

    const req = http.expectOne(`/api/v1/articles?page=1&size=${size}`);
    expect(req.request.params.get('size')).toBe(String(size));
    req.flush({ content: [], totalPages: 0 });
  });

  it('defaults deleted article pagination to ten items', () => {
    api.deleted(2).subscribe();

    const req = http.expectOne('/api/v1/articles/deleted?page=2&size=10');
    req.flush({ content: [], totalPages: 0 });
  });

  it.each(pageSizes)('sends caller page size %s for deleted articles', (size) => {
    api.deleted(2, size).subscribe();

    const req = http.expectOne(`/api/v1/articles/deleted?page=2&size=${size}`);
    expect(req.request.params.get('size')).toBe(String(size));
    req.flush({ content: [], totalPages: 0 });
  });
});

describe('PublicArticleApi pagination contract', () => {
  let http: HttpTestingController;
  let api: PublicArticleApi;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    http = TestBed.inject(HttpTestingController);
    api = TestBed.inject(PublicArticleApi);
  });

  afterEach(() => http.verify());

  it.each(pageSizes)('sends caller page size %s for public tags', (size) => {
    api.tags(3, size).subscribe();

    const req = http.expectOne(`/api/v1/public/tags?page=3&size=${size}`);
    expect(req.request.params.get('page')).toBe('3');
    expect(req.request.params.get('size')).toBe(String(size));
    req.flush({ content: [], totalPages: 0 });
  });

  it('defaults public tag pagination to ten items', () => {
    api.tags(3).subscribe();

    const req = http.expectOne('/api/v1/public/tags?page=3&size=10');
    req.flush({ content: [], totalPages: 0 });
  });
});

describe('LoginResponse', () => {
  it('requires the backend expiry value', () => {
    expectTypeOf<LoginResponse>().toEqualTypeOf<{
      accessToken: string;
      accessTokenExpiresAt: string;
    }>();
  });
});

describe('AuthenticationApi account onboarding contracts', () => {
  let http: HttpTestingController;
  let api: AuthenticationApi;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    http = TestBed.inject(HttpTestingController);
    api = TestBed.inject(AuthenticationApi);
  });

  afterEach(() => http.verify());

  it('submits registration, resend, verification, and invitation redemption contracts', () => {
    const registration: RegistrationRequest = {
      email: 'ada@example.com',
      displayName: 'Ada',
      password: 'secret',
      preferredLanguage: 'en',
    };
    api.register(registration).subscribe();
    let request = http.expectOne('/api/v1/auth/registrations');
    expect(request.request.method).toBe('POST');
    expect(request.request.body).toEqual(registration);
    request.flush(null);

    api.resendEmailVerification({ email: registration.email }).subscribe();
    request = http.expectOne('/api/v1/auth/email-verifications/resend');
    expect(request.request.body).toEqual({ email: registration.email });
    request.flush(null);

    api.verifyEmail({ token: 'verification-token' }).subscribe();
    request = http.expectOne('/api/v1/auth/email-verifications');
    expect(request.request.body).toEqual({ token: 'verification-token' });
    request.flush(null);

    const invitation: InvitationUser = {
      id: 'user-id',
      email: registration.email,
      displayName: 'Ada',
      role: 'AUTHOR',
      enabled: true,
      verifiedAt: '2026-01-01T00:00:00Z',
    };
    api
      .redeemInvitation('invitation-token', {
        displayName: 'Ada',
        password: 'secret',
        preferredLanguage: 'en',
      })
      .subscribe((result) => expect(result).toEqual(invitation));
    request = http.expectOne('/api/v1/auth/invitations/invitation-token/redeem');
    expect(request.request.body).toEqual({
      displayName: 'Ada',
      password: 'secret',
      preferredLanguage: 'en',
    });
    request.flush(invitation);
  });
});

describe('UserApi email change contracts', () => {
  let http: HttpTestingController;
  let api: UserApi;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    http = TestBed.inject(HttpTestingController);
    api = TestBed.inject(UserApi);
  });

  afterEach(() => http.verify());

  it('submits email change and confirmation contracts', () => {
    api.requestEmailChange({ email: 'new@example.com' }).subscribe();
    let request = http.expectOne('/api/v1/account/email');
    expect(request.request.method).toBe('POST');
    expect(request.request.body).toEqual({ email: 'new@example.com' });
    request.flush(null);

    api.confirmEmailChange('email-token').subscribe();
    request = http.expectOne('/api/v1/auth/email-changes/email-token');
    expect(request.request.method).toBe('POST');
    expect(request.request.body).toBeNull();
    request.flush(null);
  });
});
