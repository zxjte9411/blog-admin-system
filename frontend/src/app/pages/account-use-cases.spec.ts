import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter, Router } from '@angular/router';
import { routes } from '../app.routes';
import { LoginPage } from './login-page';

describe('account use cases', () => {
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
    await TestBed.configureTestingModule({
      imports: [LoginPage],
      providers: [provideRouter([]), provideHttpClient(), provideHttpClientTesting()],
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
});
