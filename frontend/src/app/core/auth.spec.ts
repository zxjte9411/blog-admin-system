import { HttpClient, provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { EnvironmentInjector, runInInjectionContext } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import {
  ActivatedRouteSnapshot,
  Router,
  RouterStateSnapshot,
  provideRouter,
} from '@angular/router';
import { Auth, adminGuard, authGuard, authInterceptor } from './auth';
import { Language } from './language';
import { firstValueFrom } from 'rxjs';

const user = {
  id: 'user-1',
  displayName: 'Ada',
  preferredLanguage: 'en' as const,
  role: 'AUTHOR' as const,
};

describe('auth seams', () => {
  beforeEach(() => {
    localStorage.clear();
  });

  it('refreshes a 401 and preserves the token on a retry 403 or 409', () => {
    TestBed.configureTestingModule({
      providers: [
        provideRouter([]),
        provideHttpClient(withInterceptors([authInterceptor])),
        provideHttpClientTesting(),
      ],
    });
    const auth = TestBed.inject(Auth);
    const http = TestBed.inject(HttpTestingController);
    auth.setToken('old');
    let status = 0;
    TestBed.inject(HttpClient)
      .get('/api/v1/account/me')
      .subscribe({ error: (error) => (status = error.status) });
    const original = http.expectOne('/api/v1/account/me');
    expect(original.request.headers.get('Authorization')).toBe('Bearer old');
    original.flush({}, { status: 401, statusText: 'Unauthorized' });
    http.expectOne('/api/v1/auth/refresh').flush({ accessToken: 'new' });
    const retry = http.expectOne('/api/v1/account/me');
    expect(retry.request.headers.get('Authorization')).toBe('Bearer new');
    retry.flush({}, { status: 403, statusText: 'Forbidden' });
    expect(status).toBe(403);
    expect(auth.token).toBe('new');
  });

  it('deduplicates concurrent refresh requests', async () => {
    TestBed.configureTestingModule({
      providers: [provideRouter([]), provideHttpClient(), provideHttpClientTesting()],
    });
    const auth = TestBed.inject(Auth);
    const http = TestBed.inject(HttpTestingController);

    const first = firstValueFrom(auth.refresh());
    const second = firstValueFrom(auth.refresh());
    const requests = http.match('/api/v1/auth/refresh');

    expect(requests).toHaveLength(1);
    requests[0].flush({ accessToken: 'new' });
    await expect(Promise.all([first, second])).resolves.toEqual([
      { accessToken: 'new' },
      { accessToken: 'new' },
    ]);
  });

  it('clears and navigates only when refresh fails', async () => {
    TestBed.configureTestingModule({
      providers: [
        provideRouter([]),
        provideHttpClient(withInterceptors([authInterceptor])),
        provideHttpClientTesting(),
      ],
    });
    const auth = TestBed.inject(Auth);
    const router = TestBed.inject(Router);
    vi.spyOn(router, 'navigateByUrl');
    const http = TestBed.inject(HttpTestingController);
    auth.setToken('old');
    TestBed.inject(HttpClient)
      .get('/api/v1/account/me')
      .subscribe({ error: () => undefined });
    http.expectOne('/api/v1/account/me').flush({}, { status: 401, statusText: 'Unauthorized' });
    http.expectOne('/api/v1/auth/refresh').flush({}, { status: 401, statusText: 'Unauthorized' });
    await new Promise((resolve) => setTimeout(resolve));
    expect(auth.token).toBeNull();
    expect(router.navigateByUrl).toHaveBeenCalledWith('/login');
  });

  it('guards missing tokens and non-admin users', async () => {
    TestBed.configureTestingModule({
      providers: [provideRouter([]), provideHttpClient(), provideHttpClientTesting()],
    });
    const router = TestBed.inject(Router);
    const route = {} as ActivatedRouteSnapshot;
    const state = {} as RouterStateSnapshot;
    const injector = TestBed.inject(EnvironmentInjector);
    expect(runInInjectionContext(injector, () => authGuard(route, state))).toEqual(
      router.createUrlTree(['/login']),
    );
    const auth = TestBed.inject(Auth);
    auth.setToken('token');
    auth.user = user;
    expect(
      await firstValueFrom(
        runInInjectionContext(injector, () => adminGuard(route, state)) as never,
      ),
    ).toEqual(router.createUrlTree(['/forbidden']));
  });

  it('persists guest and preferred language choices', () => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    const language = TestBed.inject(Language);
    language.set('en');
    expect(localStorage.getItem('blog-admin-language')).toBe('en');
    language.usePreferred('en');
    expect(localStorage.getItem('blog-admin-language')).toBe('en');
  });

  it('synchronizes a logged-in user language change with Auth and the API', () => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    const auth = TestBed.inject(Auth);
    const language = TestBed.inject(Language);
    const http = TestBed.inject(HttpTestingController);
    auth.user = { ...user, preferredLanguage: 'zh-TW' };

    language.set('en');

    expect(auth.user.preferredLanguage).toBe('en');
    const request = http.expectOne('/api/v1/account/profile');
    expect(request.request.body).toEqual({ displayName: 'Ada', preferredLanguage: 'en' });
    request.flush({ displayName: 'Ada', preferredLanguage: 'en' });
    expect(localStorage.getItem('blog-admin-language')).toBe('en');
  });
});
