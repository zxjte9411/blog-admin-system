import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { ActivatedRoute, provideRouter, Router } from '@angular/router';
import { of } from 'rxjs';
import { AuthenticationApi, InvitationUser, UserApi } from '../core/api';
import { SUPABASE_AUTH } from '../core/supabase';
import { EmailConfirmationPage } from './email-confirmation-page';
import { EmailVerificationPage } from './email-verification-page';
import { InvitationRedemptionPage } from './invitation-redemption-page';
import { LoginPage } from './login-page';
import { PasswordResetPage } from './password-reset-page';
import { RegistrationPage } from './registration-page';
import { ResendVerificationPage } from './resend-verification-page';
import { ResetPasswordPage } from './reset-password-page';

const routeWithToken = (token: string) => ({
  snapshot: { queryParamMap: { get: () => token } },
});

describe('authentication use-case pages', () => {
  beforeEach(() => {
    TestBed.configureTestingModule({
      imports: [LoginPage],
      providers: [
        provideRouter([]),
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: SUPABASE_AUTH, useFactory: fakeSupabase },
      ],
    });
    localStorage.clear();
  });

  afterEach(() => {
    delete (globalThis as typeof globalThis & { __BLOG_ADMIN_CONFIG__?: object })
      .__BLOG_ADMIN_CONFIG__;
  });

  it('registers through the registration use case', () => {
    const api = TestBed.inject(AuthenticationApi);
    const register = vi.spyOn(api, 'register').mockReturnValue(of());
    const page = TestBed.createComponent(RegistrationPage).componentInstance;
    page.form.patchValue({
      email: 'ada@example.com',
      displayName: 'Ada',
      password: 'secret',
      preferredLanguage: 'en',
    });

    page.submit();

    expect(register).toHaveBeenCalledWith({
      email: 'ada@example.com',
      displayName: 'Ada',
      password: 'secret',
      preferredLanguage: 'en',
    });
  });

  it('verifies email and resends verification through separate pages', () => {
    TestBed.overrideProvider(ActivatedRoute, { useValue: routeWithToken('verify-token') });
    const api = TestBed.inject(AuthenticationApi);
    const verify = vi.spyOn(api, 'verifyEmail').mockReturnValue(of());
    const resend = vi.spyOn(api, 'resendEmailVerification').mockReturnValue(of());

    const verifyPage = TestBed.createComponent(EmailVerificationPage).componentInstance;
    verifyPage.submit();
    const resendPage = TestBed.createComponent(ResendVerificationPage).componentInstance;
    resendPage.form.patchValue({ email: 'ada@example.com' });
    resendPage.submit();

    expect(verify).toHaveBeenCalledWith({ token: 'verify-token' });
    expect(resend).toHaveBeenCalledWith({ email: 'ada@example.com' });
  });

  it('keeps password reset request and confirmation on separate pages', () => {
    TestBed.overrideProvider(ActivatedRoute, { useValue: routeWithToken('reset-token') });
    const userApi = TestBed.inject(UserApi);
    const request = vi.spyOn(userApi, 'requestPasswordReset').mockReturnValue(of());
    const reset = vi.spyOn(userApi, 'resetPassword').mockReturnValue(of());

    const requestPage = TestBed.createComponent(PasswordResetPage).componentInstance;
    requestPage.form.patchValue({ email: 'ada@example.com' });
    requestPage.submit();

    const resetPage = TestBed.createComponent(ResetPasswordPage).componentInstance;
    resetPage.form.patchValue({ password: 'new-secret' });
    resetPage.submit();

    expect(request).toHaveBeenCalledWith('ada@example.com');
    expect(reset).toHaveBeenCalledWith('reset-token', 'new-secret');
  });

  it('confirms email changes and redeems invitations with their own tokens', () => {
    TestBed.overrideProvider(ActivatedRoute, { useValue: routeWithToken('email-token') });
    const authApi = TestBed.inject(AuthenticationApi);
    const userApi = TestBed.inject(UserApi);
    const confirm = vi.spyOn(userApi, 'confirmEmailChange').mockReturnValue(of());
    const redeem = vi.spyOn(authApi, 'redeemInvitation').mockReturnValue(of({} as InvitationUser));

    const confirmPage = TestBed.createComponent(EmailConfirmationPage).componentInstance;
    confirmPage.submit();
    const invitePage = TestBed.createComponent(InvitationRedemptionPage).componentInstance;
    invitePage.form.patchValue({
      displayName: 'Ada',
      password: 'secret',
      preferredLanguage: 'en',
    });
    invitePage.submit();

    expect(confirm).toHaveBeenCalledWith('email-token');
    expect(redeem).toHaveBeenCalledWith('email-token', {
      displayName: 'Ada',
      password: 'secret',
      preferredLanguage: 'en',
    });
  });

  it('completes the Google callback through the backend Google Login use case', async () => {
    (globalThis as typeof globalThis & { __BLOG_ADMIN_CONFIG__?: object }).__BLOG_ADMIN_CONFIG__ = {
      supabaseUrl: 'https://project.supabase.co',
      supabasePublishableKey: 'sb_publishable_test',
    };
    const supabase = TestBed.inject(SUPABASE_AUTH) as ReturnType<typeof fakeSupabase>;
    supabase.getSession.mockResolvedValue({
      data: { session: { access_token: 'supabase-token' } },
      error: null,
    });
    const router = TestBed.inject(Router);
    vi.spyOn(router, 'url', 'get').mockReturnValue('/login?code=oauth-code');
    vi.spyOn(router, 'navigateByUrl').mockResolvedValue(true);

    const fixture = TestBed.createComponent(LoginPage);
    fixture.componentInstance.ngOnInit();
    await Promise.resolve();

    const http = TestBed.inject(HttpTestingController);
    const request = http.expectOne('/api/v1/auth/google');
    expect(request.request.body).toEqual({ accessToken: 'supabase-token' });
    request.flush({ accessToken: 'local-token' });
    http.expectOne('/api/v1/account/me').flush({
      id: 'user-1',
      displayName: 'Ada',
      preferredLanguage: 'en',
      role: 'AUTHOR',
    });

    expect(fixture.componentInstance.auth.token).toBe('local-token');
    expect(router.navigateByUrl).toHaveBeenCalledWith('/articles');
  });

  it('shows the backend rejection when Google callback exchange is refused', async () => {
    (globalThis as typeof globalThis & { __BLOG_ADMIN_CONFIG__?: object }).__BLOG_ADMIN_CONFIG__ = {
      supabaseUrl: 'https://project.supabase.co',
      supabasePublishableKey: 'sb_publishable_test',
    };
    const supabase = TestBed.inject(SUPABASE_AUTH) as ReturnType<typeof fakeSupabase>;
    supabase.getSession.mockResolvedValue({
      data: { session: { access_token: 'rejected-supabase-token' } },
      error: null,
    });
    const router = TestBed.inject(Router);
    vi.spyOn(router, 'url', 'get').mockReturnValue('/login?code=oauth-code');
    vi.spyOn(router, 'navigateByUrl').mockResolvedValue(true);

    const fixture = TestBed.createComponent(LoginPage);
    fixture.componentInstance.ngOnInit();
    await Promise.resolve();

    TestBed.inject(HttpTestingController)
      .expectOne('/api/v1/auth/google')
      .flush({}, { status: 401, statusText: 'Unauthorized' });

    expect(fixture.componentInstance.loading).toBe(false);
    expect(fixture.componentInstance.error).toBe(fixture.componentInstance.language.t.unauthorized);
    expect(fixture.componentInstance.auth.token).toBeNull();
    expect(router.navigateByUrl).not.toHaveBeenCalled();
  });

  it('exchanges an invitation Google callback with its invitation token', async () => {
    TestBed.overrideProvider(ActivatedRoute, { useValue: routeWithToken('invite-token') });
    (globalThis as typeof globalThis & { __BLOG_ADMIN_CONFIG__?: object }).__BLOG_ADMIN_CONFIG__ = {
      supabaseUrl: 'https://project.supabase.co',
      supabasePublishableKey: 'sb_publishable_test',
    };
    const supabase = TestBed.inject(SUPABASE_AUTH) as ReturnType<typeof fakeSupabase>;
    supabase.getSession.mockResolvedValue({
      data: { session: { access_token: 'supabase-token' } },
      error: null,
    });
    const router = TestBed.inject(Router);
    vi.spyOn(router, 'url', 'get').mockReturnValue('/invite?code=oauth-code&token=invite-token');
    vi.spyOn(router, 'navigateByUrl').mockResolvedValue(true);

    const fixture = TestBed.createComponent(InvitationRedemptionPage);
    fixture.componentInstance.ngOnInit();
    await Promise.resolve();

    const request = TestBed.inject(HttpTestingController).expectOne('/api/v1/auth/google');
    expect(request.request.body).toEqual({
      accessToken: 'supabase-token',
      invitationToken: 'invite-token',
    });
    request.flush({ accessToken: 'local-token' });
    TestBed.inject(HttpTestingController).expectOne('/api/v1/account/me').flush({
      id: 'user-1',
      displayName: 'Ada',
      preferredLanguage: 'en',
      role: 'AUTHOR',
    });

    expect(fixture.componentInstance.auth.token).toBe('local-token');
    expect(router.navigateByUrl).toHaveBeenCalledWith('/articles');
  });
});

function fakeSupabase() {
  return {
    signInWithOAuth: vi.fn().mockResolvedValue({ error: null }),
    getSession: vi.fn().mockResolvedValue({ data: { session: null }, error: null }),
    signOut: vi.fn().mockResolvedValue({ error: null }),
  };
}
