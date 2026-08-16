import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { ActivatedRoute, provideRouter, Router } from '@angular/router';
import { NEVER, of, throwError } from 'rxjs';
import {
  AuthenticationApi,
  InvitationRedemptionContext,
  InvitationUser,
  UserApi,
} from '../core/api';
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
    vi.spyOn(authApi, 'getInvitationContext').mockReturnValue(of(validInvitationContext));

    const confirmPage = TestBed.createComponent(EmailConfirmationPage).componentInstance;
    confirmPage.submit();
    const invitePage = TestBed.createComponent(InvitationRedemptionPage).componentInstance;
    invitePage.ngOnInit();
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

  it('keeps redemption controls hidden while validating', () => {
    TestBed.overrideProvider(ActivatedRoute, { useValue: routeWithToken('invite-token') });
    const api = TestBed.inject(AuthenticationApi);
    vi.spyOn(api, 'getInvitationContext').mockReturnValue(NEVER);

    const fixture = TestBed.createComponent(InvitationRedemptionPage);
    fixture.detectChanges();

    expect(fixture.nativeElement.textContent).toContain(
      fixture.componentInstance.language.t.invitationValidating,
    );
    expect(fixture.nativeElement.querySelector('form')).toBeNull();
    expect(fixture.nativeElement.querySelector('.google-login')).toBeNull();
  });

  it('shows the invited email and redemption controls only for a valid invitation', () => {
    TestBed.overrideProvider(ActivatedRoute, { useValue: routeWithToken('invite-token') });
    (globalThis as typeof globalThis & { __BLOG_ADMIN_CONFIG__?: object }).__BLOG_ADMIN_CONFIG__ = {
      supabaseUrl: 'https://project.supabase.co',
      supabasePublishableKey: 'sb_publishable_test',
    };
    const api = TestBed.inject(AuthenticationApi);
    vi.spyOn(api, 'getInvitationContext').mockReturnValue(
      of({ status: 'valid', email: 'invited@example.com', expiresAt: '2026-08-18T00:00:00Z' }),
    );

    const fixture = TestBed.createComponent(InvitationRedemptionPage);
    fixture.detectChanges();

    expect(fixture.nativeElement.textContent).toContain('invited@example.com');
    expect(fixture.nativeElement.querySelector('form')).not.toBeNull();
    expect(fixture.nativeElement.querySelector('.google-login')).not.toBeNull();
  });

  it.each([
    ['expired', 'invitationExpired'],
    ['alreadyUsed', 'invitationAlreadyUsed'],
    ['invalid', 'invitationInvalid'],
  ] as const)('does not show redemption controls for a %s invitation', (status, copy) => {
    TestBed.overrideProvider(ActivatedRoute, { useValue: routeWithToken('invite-token') });
    const api = TestBed.inject(AuthenticationApi);
    vi.spyOn(api, 'getInvitationContext').mockReturnValue(of({ status }));

    const fixture = TestBed.createComponent(InvitationRedemptionPage);
    fixture.detectChanges();

    expect(fixture.nativeElement.textContent).toContain(fixture.componentInstance.language.t[copy]);
    expect(fixture.nativeElement.querySelector('form')).toBeNull();
    expect(fixture.nativeElement.querySelector('.google-login')).toBeNull();
    expect(fixture.nativeElement.querySelector('a[href="/login"]')).not.toBeNull();
  });

  it('offers retry after a context request failure', () => {
    TestBed.overrideProvider(ActivatedRoute, { useValue: routeWithToken('invite-token') });
    const api = TestBed.inject(AuthenticationApi);
    const getContext = vi
      .spyOn(api, 'getInvitationContext')
      .mockReturnValueOnce(throwError(() => new Error('network')))
      .mockReturnValueOnce(of(validInvitationContext));

    const fixture = TestBed.createComponent(InvitationRedemptionPage);
    fixture.detectChanges();
    expect(fixture.nativeElement.textContent).toContain(
      fixture.componentInstance.language.t.invitationRequestError,
    );

    fixture.nativeElement.querySelector('.retry-action').click();
    fixture.detectChanges();

    expect(getContext).toHaveBeenCalledTimes(2);
    expect(fixture.nativeElement.querySelector('form')).not.toBeNull();
  });

  it('replaces the password form with a login CTA after success without creating a session', () => {
    TestBed.overrideProvider(ActivatedRoute, { useValue: routeWithToken('invite-token') });
    const api = TestBed.inject(AuthenticationApi);
    vi.spyOn(api, 'getInvitationContext').mockReturnValue(of(validInvitationContext));
    const redeem = vi.spyOn(api, 'redeemInvitation').mockReturnValue(of({} as InvitationUser));
    const router = TestBed.inject(Router);
    const navigate = vi.spyOn(router, 'navigateByUrl');

    const fixture = TestBed.createComponent(InvitationRedemptionPage);
    fixture.detectChanges();
    fixture.componentInstance.form.patchValue({
      displayName: 'Ada',
      password: 'secret',
      preferredLanguage: 'en',
    });
    fixture.componentInstance.submit();
    fixture.detectChanges();
    fixture.componentInstance.submit();

    expect(redeem).toHaveBeenCalledTimes(1);
    expect(fixture.nativeElement.textContent).toContain(
      fixture.componentInstance.language.t.invitationAccepted,
    );
    expect(fixture.nativeElement.querySelector('form')).toBeNull();
    expect(fixture.nativeElement.querySelector('a[href="/login"]')).not.toBeNull();
    expect(fixture.componentInstance.auth.token).toBeNull();
    expect(navigate).not.toHaveBeenCalled();
  });

  it('uses the final invitation state after redemption failure', () => {
    TestBed.overrideProvider(ActivatedRoute, { useValue: routeWithToken('invite-token') });
    const api = TestBed.inject(AuthenticationApi);
    vi.spyOn(api, 'getInvitationContext')
      .mockReturnValueOnce(of(validInvitationContext))
      .mockReturnValueOnce(of({ status: 'alreadyUsed' }));
    vi.spyOn(api, 'redeemInvitation').mockReturnValue(
      throwError(() => ({ status: 409, error: { detail: 'Invitation is no longer available' } })),
    );

    const fixture = TestBed.createComponent(InvitationRedemptionPage);
    fixture.detectChanges();
    fixture.componentInstance.form.patchValue({
      displayName: 'Ada',
      password: 'secret',
      preferredLanguage: 'en',
    });
    fixture.componentInstance.submit();
    fixture.detectChanges();

    expect(fixture.nativeElement.textContent).toContain(
      fixture.componentInstance.language.t.invitationAlreadyUsed,
    );
    expect(fixture.nativeElement.querySelector('form')).toBeNull();
  });

  it.each([
    ['the invitation remains valid', of(validInvitationContext)],
    ['the invitation cannot be rechecked', throwError(() => new Error('network'))],
  ] as const)('keeps the original redemption error when %s', (_case, recheck) => {
    TestBed.overrideProvider(ActivatedRoute, { useValue: routeWithToken('invite-token') });
    const api = TestBed.inject(AuthenticationApi);
    vi.spyOn(api, 'getInvitationContext')
      .mockReturnValueOnce(of(validInvitationContext))
      .mockReturnValueOnce(recheck);
    vi.spyOn(api, 'redeemInvitation').mockReturnValue(
      throwError(() => ({ status: 409, error: { detail: 'User already exists' } })),
    );

    const fixture = TestBed.createComponent(InvitationRedemptionPage);
    fixture.detectChanges();
    fixture.componentInstance.form.patchValue({
      displayName: 'Ada',
      password: 'secret',
      preferredLanguage: 'en',
    });
    fixture.componentInstance.submit();
    fixture.detectChanges();

    expect(fixture.nativeElement.textContent).toContain(
      fixture.componentInstance.language.t.conflict,
    );
    expect(fixture.nativeElement.querySelector('form')).not.toBeNull();
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
    fixture.detectChanges();
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
    fixture.detectChanges();
    await Promise.resolve();

    TestBed.inject(HttpTestingController)
      .expectOne('/api/v1/auth/google')
      .flush({}, { status: 401, statusText: 'Unauthorized' });

    expect(fixture.componentInstance.loading).toBe(false);
    expect(fixture.componentInstance.error).toBe(fixture.componentInstance.language.t.unauthorized);
    expect(fixture.componentInstance.auth.token).toBeNull();
    expect(router.navigateByUrl).not.toHaveBeenCalled();
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('[role="status"]')).toBeNull();
    expect(
      [...fixture.nativeElement.querySelectorAll('input, form button, .google-login')].some(
        (control) => (control as HTMLInputElement | HTMLButtonElement).matches(':disabled'),
      ),
    ).toBe(false);
  });

  it('shows callback processing in the page until navigation completes', async () => {
    (globalThis as typeof globalThis & { __BLOG_ADMIN_CONFIG__?: object }).__BLOG_ADMIN_CONFIG__ = {
      supabaseUrl: 'https://project.supabase.co',
      supabasePublishableKey: 'sb_publishable_test',
    };
    const supabase = TestBed.inject(SUPABASE_AUTH) as ReturnType<typeof fakeSupabase>;
    let resolveSession!: (value: {
      data: { session: { access_token: string } };
      error: null;
    }) => void;
    supabase.getSession.mockReturnValue(
      new Promise((resolve) => {
        resolveSession = resolve;
      }),
    );
    const router = TestBed.inject(Router);
    vi.spyOn(router, 'url', 'get').mockReturnValue('/login?code=oauth-code');
    let resolveNavigation!: (value: boolean) => void;
    vi.spyOn(router, 'navigateByUrl').mockReturnValue(
      new Promise((resolve) => {
        resolveNavigation = resolve;
      }),
    );

    const fixture = TestBed.createComponent(LoginPage);
    fixture.detectChanges();

    expect(fixture.nativeElement.querySelector('[role="status"]')?.textContent).toContain(
      fixture.componentInstance.language.t.loginCallbackProcessing,
    );
    expect(fixture.nativeElement.querySelector('[role="status"]')?.getAttribute('aria-busy')).toBe(
      'true',
    );
    expect(
      [...fixture.nativeElement.querySelectorAll('input, form button, .google-login')].every(
        (control) => (control as HTMLInputElement | HTMLButtonElement).matches(':disabled'),
      ),
    ).toBe(true);

    resolveSession({ data: { session: { access_token: 'supabase-token' } }, error: null });
    await Promise.resolve();

    const http = TestBed.inject(HttpTestingController);
    http.expectOne('/api/v1/auth/google').flush({ accessToken: 'local-token' });
    http.expectOne('/api/v1/account/me').flush({
      id: 'user-1',
      displayName: 'Ada',
      preferredLanguage: 'en',
      role: 'AUTHOR',
    });
    fixture.detectChanges();

    expect(fixture.componentInstance.loading).toBe(true);
    expect(fixture.nativeElement.querySelector('[role="status"]')).not.toBeNull();
    resolveNavigation(true);
    await Promise.resolve();
    fixture.detectChanges();

    expect(fixture.nativeElement.querySelector('[role="status"]')).toBeNull();
    expect(
      [...fixture.nativeElement.querySelectorAll('input, form button, .google-login')].some(
        (control) => (control as HTMLInputElement | HTMLButtonElement).disabled,
      ),
    ).toBe(false);
  });

  it('clears callback processing and explains when Supabase has no session', async () => {
    (globalThis as typeof globalThis & { __BLOG_ADMIN_CONFIG__?: object }).__BLOG_ADMIN_CONFIG__ = {
      supabaseUrl: 'https://project.supabase.co',
      supabasePublishableKey: 'sb_publishable_test',
    };
    const supabase = TestBed.inject(SUPABASE_AUTH) as ReturnType<typeof fakeSupabase>;
    supabase.getSession.mockResolvedValue({ data: { session: null }, error: null });
    const router = TestBed.inject(Router);
    vi.spyOn(router, 'url', 'get').mockReturnValue('/login?code=oauth-code');

    const fixture = TestBed.createComponent(LoginPage);
    fixture.detectChanges();
    await Promise.resolve();
    fixture.detectChanges();

    expect(fixture.componentInstance.loading).toBe(false);
    expect(fixture.componentInstance.error).toBe(
      fixture.componentInstance.language.t.loginCallbackNoSession,
    );
    expect(fixture.nativeElement.querySelector('[role="status"]')).toBeNull();
    expect(
      [...fixture.nativeElement.querySelectorAll('input, form button, .google-login')].some(
        (control) => (control as HTMLInputElement | HTMLButtonElement).disabled,
      ),
    ).toBe(false);
  });

  it('clears callback processing and explains a Supabase session error', async () => {
    (globalThis as typeof globalThis & { __BLOG_ADMIN_CONFIG__?: object }).__BLOG_ADMIN_CONFIG__ = {
      supabaseUrl: 'https://project.supabase.co',
      supabasePublishableKey: 'sb_publishable_test',
    };
    const supabase = TestBed.inject(SUPABASE_AUTH) as ReturnType<typeof fakeSupabase>;
    supabase.getSession.mockResolvedValue({
      data: { session: null },
      error: { message: 'Supabase is unavailable' },
    });
    const router = TestBed.inject(Router);
    vi.spyOn(router, 'url', 'get').mockReturnValue('/login?code=oauth-code');

    const fixture = TestBed.createComponent(LoginPage);
    fixture.detectChanges();
    await Promise.resolve();
    fixture.detectChanges();

    expect(fixture.componentInstance.loading).toBe(false);
    expect(fixture.componentInstance.error).toBe(
      fixture.componentInstance.language.t.loginCallbackError,
    );
    expect(fixture.nativeElement.querySelector('[role="status"]')).toBeNull();
    expect(
      [...fixture.nativeElement.querySelectorAll('input, form button, .google-login')].some(
        (control) => (control as HTMLInputElement | HTMLButtonElement).matches(':disabled'),
      ),
    ).toBe(false);
  });

  it('clears callback processing when Auth cannot load the user', async () => {
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
    fixture.detectChanges();
    await Promise.resolve();

    const http = TestBed.inject(HttpTestingController);
    http.expectOne('/api/v1/auth/google').flush({ accessToken: 'local-token' });
    http.expectOne('/api/v1/account/me').flush({}, { status: 500, statusText: 'Server Error' });
    fixture.detectChanges();

    expect(fixture.componentInstance.loading).toBe(false);
    expect(fixture.componentInstance.error).toBe(
      fixture.componentInstance.language.t.loginCallbackError,
    );
    expect(fixture.nativeElement.querySelector('[role="status"]')).toBeNull();
    expect(
      [...fixture.nativeElement.querySelectorAll('input, form button, .google-login')].some(
        (control) => (control as HTMLInputElement | HTMLButtonElement).matches(':disabled'),
      ),
    ).toBe(false);
  });

  it.each([
    ['navigation returns false', 'false'],
    ['navigation rejects', 'rejects'],
  ] as const)('clears callback processing when %s', async (_case, outcome) => {
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
    const navigate = vi.spyOn(router, 'navigateByUrl');
    if (outcome === 'false') navigate.mockResolvedValue(false);
    else navigate.mockRejectedValue(new Error('navigation failed'));

    const fixture = TestBed.createComponent(LoginPage);
    fixture.detectChanges();
    await Promise.resolve();

    const http = TestBed.inject(HttpTestingController);
    http.expectOne('/api/v1/auth/google').flush({ accessToken: 'local-token' });
    http.expectOne('/api/v1/account/me').flush({
      id: 'user-1',
      displayName: 'Ada',
      preferredLanguage: 'en',
      role: 'AUTHOR',
    });
    await Promise.resolve();
    fixture.detectChanges();

    expect(navigate).toHaveBeenCalledWith('/articles');
    expect(fixture.componentInstance.loading).toBe(false);
    expect(fixture.componentInstance.error).toBe(
      fixture.componentInstance.language.t.loginCallbackError,
    );
    expect(fixture.nativeElement.querySelector('[role="status"]')).toBeNull();
    expect(
      [...fixture.nativeElement.querySelectorAll('input, form button, .google-login')].some(
        (control) => (control as HTMLInputElement | HTMLButtonElement).matches(':disabled'),
      ),
    ).toBe(false);
  });

  it('completes an implicit-flow Google callback from the URL hash', async () => {
    const previousHash = window.location.hash;
    window.location.hash = '#access_token=callback-token';
    try {
      (globalThis as typeof globalThis & { __BLOG_ADMIN_CONFIG__?: object }).__BLOG_ADMIN_CONFIG__ =
        {
          supabaseUrl: 'https://project.supabase.co',
          supabasePublishableKey: 'sb_publishable_test',
        };
      const supabase = TestBed.inject(SUPABASE_AUTH) as ReturnType<typeof fakeSupabase>;
      supabase.getSession.mockResolvedValue({
        data: { session: { access_token: 'session-access-token' } },
        error: null,
      });
      const router = TestBed.inject(Router);
      vi.spyOn(router, 'url', 'get').mockReturnValue('/login');
      vi.spyOn(router, 'navigateByUrl').mockResolvedValue(true);

      const fixture = TestBed.createComponent(LoginPage);
      fixture.componentInstance.ngOnInit();
      await Promise.resolve();

      const request = TestBed.inject(HttpTestingController).expectOne('/api/v1/auth/google');
      expect(request.request.body).toEqual({ accessToken: 'session-access-token' });
      request.flush({ accessToken: 'local-token' });
      TestBed.inject(HttpTestingController).expectOne('/api/v1/account/me').flush({
        id: 'user-1',
        displayName: 'Ada',
        preferredLanguage: 'en',
        role: 'AUTHOR',
      });

      expect(router.navigateByUrl).toHaveBeenCalledWith('/articles');
    } finally {
      window.location.hash = previousHash;
    }
  });

  it('exchanges an invitation Google callback with its invitation token', async () => {
    TestBed.overrideProvider(ActivatedRoute, { useValue: routeWithToken('invite-token') });
    vi.spyOn(TestBed.inject(AuthenticationApi), 'getInvitationContext').mockReturnValue(
      of(validInvitationContext),
    );
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

  it('completes an invitation Google implicit callback from the URL hash', async () => {
    const previousHash = window.location.hash;
    window.location.hash = '#access_token=implicit-callback';
    try {
      TestBed.overrideProvider(ActivatedRoute, { useValue: routeWithToken('invite-token') });
      vi.spyOn(TestBed.inject(AuthenticationApi), 'getInvitationContext').mockReturnValue(
        of(validInvitationContext),
      );
      (globalThis as typeof globalThis & { __BLOG_ADMIN_CONFIG__?: object }).__BLOG_ADMIN_CONFIG__ =
        {
          supabaseUrl: 'https://project.supabase.co',
          supabasePublishableKey: 'sb_publishable_test',
        };
      const supabase = TestBed.inject(SUPABASE_AUTH) as ReturnType<typeof fakeSupabase>;
      supabase.getSession.mockResolvedValue({
        data: { session: { access_token: 'session-access-token' } },
        error: null,
      });
      const router = TestBed.inject(Router);
      vi.spyOn(router, 'url', 'get').mockReturnValue('/invite?token=invite-token');
      vi.spyOn(router, 'navigateByUrl').mockResolvedValue(true);

      const fixture = TestBed.createComponent(InvitationRedemptionPage);
      fixture.componentInstance.ngOnInit();
      await Promise.resolve();

      const request = TestBed.inject(HttpTestingController).expectOne('/api/v1/auth/google');
      expect(request.request.body).toEqual({
        accessToken: 'session-access-token',
        invitationToken: 'invite-token',
      });
      request.flush({ accessToken: 'local-token' });
      TestBed.inject(HttpTestingController).expectOne('/api/v1/account/me').flush({
        id: 'user-1',
        displayName: 'Ada',
        preferredLanguage: 'en',
        role: 'AUTHOR',
      });

      expect(router.navigateByUrl).toHaveBeenCalledWith('/articles');
    } finally {
      window.location.hash = previousHash;
    }
  });
});

const validInvitationContext: InvitationRedemptionContext = {
  status: 'valid',
  email: 'invited@example.com',
  expiresAt: '2026-08-18T00:00:00Z',
};

function fakeSupabase() {
  return {
    signInWithOAuth: vi.fn().mockResolvedValue({ error: null }),
    getSession: vi.fn().mockResolvedValue({ data: { session: null }, error: null }),
    signOut: vi.fn().mockResolvedValue({ error: null }),
  };
}
