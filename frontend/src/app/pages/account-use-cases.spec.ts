import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter, Router } from '@angular/router';
import { routes } from '../app.routes';
import { SUPABASE_AUTH } from '../core/supabase';
import { LoginPage } from './login-page';

describe('account use cases', () => {
  const setSupabaseConfig = (
    configured: boolean | { supabaseUrl?: string; supabasePublishableKey?: string },
  ) => {
    if (configured) {
      (globalThis as typeof globalThis & { __BLOG_ADMIN_CONFIG__?: object }).__BLOG_ADMIN_CONFIG__ =
        configured === true
          ? {
              supabaseUrl: 'https://project.supabase.co',
              supabasePublishableKey: 'sb_publishable_test',
            }
          : configured;
    } else {
      delete (globalThis as typeof globalThis & { __BLOG_ADMIN_CONFIG__?: object })
        .__BLOG_ADMIN_CONFIG__;
    }
  };

  afterEach(() => setSupabaseConfig(false));

  const fakeSupabase = () => ({
    signInWithOAuth: vi.fn().mockResolvedValue({ error: null }),
    getSession: vi.fn().mockResolvedValue({ data: { session: null }, error: null }),
    signOut: vi.fn().mockResolvedValue({ error: null }),
  });

  it('loads each account use case as its own route component', async () => {
    const paths = [
      'login',
      'register',
      'verify-email',
      'verify/resend',
      'password-reset',
      'reset-password',
      'confirm-email',
      'invite',
      'account/profile',
      'account/password',
      'account/email',
      'account/sessions',
    ];
    const accountRoutes = routes.filter((route) => paths.includes(route.path ?? ''));
    const components = await Promise.all(accountRoutes.map((route) => route.loadComponent?.()));

    expect(accountRoutes).toHaveLength(paths.length);
    const names = components.map((component) => (component as { name?: string })?.name);
    expect(new Set(names).size).toBe(paths.length);
  });

  it('submits login from LoginPage and restores the logged-in user', async () => {
    const supabase = fakeSupabase();
    await TestBed.configureTestingModule({
      imports: [LoginPage],
      providers: [
        provideRouter([]),
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: SUPABASE_AUTH, useValue: supabase },
      ],
    }).compileComponents();

    const fixture = TestBed.createComponent(LoginPage);
    vi.spyOn(TestBed.inject(Router), 'navigateByUrl').mockResolvedValue(true);
    fixture.componentInstance.form.patchValue({ email: 'ada@example.com', password: 'secret' });
    fixture.componentInstance.submit();

    const http = TestBed.inject(HttpTestingController);
    http.expectOne('/api/v1/auth/login').flush({ accessToken: 'token' });
    http.expectOne('/api/v1/account/me').flush({
      id: 'user-1',
      displayName: 'Ada',
      preferredLanguage: 'en',
      role: 'AUTHOR',
    });

    expect(fixture.componentInstance.auth.user?.id).toBe('user-1');
  });

  it('starts Google OAuth and exchanges the callback session for a local session', async () => {
    const supabase = fakeSupabase();
    setSupabaseConfig(true);
    supabase.getSession.mockResolvedValue({
      data: { session: { access_token: 'supabase-token' } },
      error: null,
    });
    await TestBed.configureTestingModule({
      imports: [LoginPage],
      providers: [
        provideRouter([]),
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: SUPABASE_AUTH, useValue: supabase },
      ],
    }).compileComponents();

    const fixture = TestBed.createComponent(LoginPage);
    const router = TestBed.inject(Router);
    vi.spyOn(router, 'navigateByUrl').mockResolvedValue(true);
    vi.spyOn(router, 'url', 'get').mockReturnValue('/login?code=oauth-code');
    await fixture.componentInstance.googleLogin();

    expect(supabase.signInWithOAuth).toHaveBeenCalledWith({
      provider: 'google',
      options: { redirectTo: `${window.location.origin}/login` },
    });

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
  });

  it('does not exchange an existing Supabase session on an ordinary login load', async () => {
    const supabase = fakeSupabase();
    setSupabaseConfig(true);
    supabase.getSession.mockResolvedValue({
      data: { session: { access_token: 'stale-session-token' } },
      error: null,
    });
    await TestBed.configureTestingModule({
      imports: [LoginPage],
      providers: [
        provideRouter([]),
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: SUPABASE_AUTH, useValue: supabase },
      ],
    }).compileComponents();

    const fixture = TestBed.createComponent(LoginPage);
    fixture.detectChanges();
    await Promise.resolve();

    expect(supabase.getSession).not.toHaveBeenCalled();
    TestBed.inject(HttpTestingController).expectNone('/api/v1/auth/google');
  });

  it('hides Google Login when Supabase runtime configuration is incomplete', async () => {
    const supabase = fakeSupabase();
    setSupabaseConfig({ supabaseUrl: '', supabasePublishableKey: 'sb_publishable_test' });
    await TestBed.configureTestingModule({
      imports: [LoginPage],
      providers: [
        provideRouter([]),
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: SUPABASE_AUTH, useValue: supabase },
      ],
    }).compileComponents();

    const fixture = TestBed.createComponent(LoginPage);
    fixture.detectChanges();

    expect(fixture.nativeElement.querySelector('.google-login')).toBeNull();
    expect(fixture.nativeElement.querySelector('input[type="email"]')).not.toBeNull();

    setSupabaseConfig({ supabaseUrl: 'https://project.supabase.co', supabasePublishableKey: '' });
    fixture.detectChanges();

    expect(fixture.nativeElement.querySelector('.google-login')).toBeNull();
  });
});
