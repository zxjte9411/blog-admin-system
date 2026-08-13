import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting, HttpTestingController } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { ActivatedRoute, provideRouter, Router } from '@angular/router';
import { Location } from '@angular/common';
import { App } from '../app';
import { routes } from '../app.routes';
import { AccountPage } from './account-page';

describe('AccountPage', () => {
  it('submits login without requesting portal data', async () => {
    await TestBed.configureTestingModule({
      imports: [AccountPage],
      providers: [
        provideRouter([]),
        provideHttpClient(),
        provideHttpClientTesting(),
        {
          provide: ActivatedRoute,
          useValue: {
            snapshot: {
              routeConfig: { path: 'login' },
              queryParamMap: { get: () => null },
              paramMap: { get: () => null },
            },
          },
        },
      ],
    }).compileComponents();

    const fixture = TestBed.createComponent(AccountPage);
    fixture.componentInstance.ngOnInit();
    fixture.componentInstance.form.patchValue({ email: 'ada@example.com', password: 'secret' });
    vi.spyOn(TestBed.inject(Router), 'navigateByUrl').mockResolvedValue(true);
    fixture.componentInstance.submit();

    const http = TestBed.inject(HttpTestingController);
    const request = http.expectOne('/api/v1/auth/login');
    expect(request.request.body).toEqual({ email: 'ada@example.com', password: 'secret' });
    http.expectNone('/api/v1/articles');
    request.flush({ accessToken: 'token' });
  });

  it('restores the logged-in user after a successful login', async () => {
    await TestBed.configureTestingModule({
      imports: [AccountPage],
      providers: [provideRouter([]), provideHttpClient(), provideHttpClientTesting()],
    }).compileComponents();

    const fixture = TestBed.createComponent(AccountPage);
    fixture.componentInstance.routeKey = 'login';
    fixture.componentInstance.ngOnInit();
    fixture.componentInstance.form.patchValue({ email: 'ada@example.com', password: 'secret' });
    vi.spyOn(TestBed.inject(Router), 'navigateByUrl').mockResolvedValue(true);
    fixture.componentInstance.submit();

    const http = TestBed.inject(HttpTestingController);
    http.expectOne('/api/v1/auth/login').flush({ accessToken: 'token' });
    http.expectOne('/api/v1/account/me').flush({
      id: 'user-1',
      displayName: 'Ada',
      preferredLanguage: 'en',
      role: 'AUTHOR',
    });

    expect(localStorage.getItem('blog-admin-token')).toBe('token');
    expect(fixture.componentInstance.auth.user?.id).toBe('user-1');
  });

  it('uses current-password autocomplete for login', async () => {
    await TestBed.configureTestingModule({
      imports: [AccountPage],
      providers: [provideRouter([]), provideHttpClient(), provideHttpClientTesting()],
    }).compileComponents();

    const fixture = TestBed.createComponent(AccountPage);
    fixture.componentInstance.routeKey = 'login';
    fixture.componentInstance.ngOnInit();
    fixture.detectChanges();

    expect(fixture.nativeElement.querySelector('#field-password').autocomplete).toBe(
      'current-password',
    );
  });

  it('shows the account journey links and a named preferred language select', async () => {
    await TestBed.configureTestingModule({
      imports: [AccountPage],
      providers: [provideRouter([]), provideHttpClient(), provideHttpClientTesting()],
    }).compileComponents();

    const fixture = TestBed.createComponent(AccountPage);
    fixture.componentInstance.routeKey = 'register';
    fixture.componentInstance.ngOnInit();
    fixture.detectChanges();

    expect(fixture.nativeElement.querySelector('a[href="/login"]')).toBeTruthy();
    expect(fixture.nativeElement.querySelector('a[href="/verify-email"]')).toBeTruthy();
    expect(fixture.nativeElement.querySelector('a[href="/verify/resend"]')).toBeTruthy();
    expect(fixture.nativeElement.querySelector('a[href="/password-reset"]')).toBeTruthy();
    expect(fixture.nativeElement.querySelector('select[name="preferredLanguage"]')).toBeTruthy();
    const languageSelect = fixture.nativeElement.querySelector('select[name="preferredLanguage"]');
    expect(languageSelect.getAttribute('aria-invalid')).toBeNull();
    expect(languageSelect.getAttribute('aria-describedby')).toBe('field-preferredLanguage-error');
    expect(fixture.nativeElement.querySelector('#field-preferredLanguage-error')).toBe(
      languageSelect.parentElement.querySelector('.field-error'),
    );
    expect(fixture.nativeElement.textContent).toContain('1–100');
    expect(
      fixture.nativeElement.querySelector('#field-password').getAttribute('aria-describedby'),
    ).toContain('field-password-hint');
  });

  it('navigates account entry links with the Angular Router', async () => {
    await TestBed.configureTestingModule({
      imports: [App],
      providers: [provideRouter(routes), provideHttpClient(), provideHttpClientTesting()],
    }).compileComponents();

    const fixture = TestBed.createComponent(App);
    await TestBed.inject(Router).navigateByUrl('/register');
    fixture.detectChanges();

    const verifyEmailLink = fixture.nativeElement.querySelector('a[href="/verify-email"]');
    verifyEmailLink.click();
    await fixture.whenStable();
    expect(TestBed.inject(Location).path()).toBe('/verify-email');

    fixture.detectChanges();
    const loginLink = fixture.nativeElement.querySelector('a[href="/login"]');
    loginLink.click();
    await fixture.whenStable();
    expect(TestBed.inject(Location).path()).toBe('/login');
  });

  it('updates the account title when the language changes', async () => {
    await TestBed.configureTestingModule({
      imports: [AccountPage],
      providers: [provideRouter([]), provideHttpClient(), provideHttpClientTesting()],
    }).compileComponents();

    const fixture = TestBed.createComponent(AccountPage);
    fixture.componentInstance.routeKey = 'login';
    fixture.componentInstance.ngOnInit();
    fixture.detectChanges();
    const englishTitle = fixture.nativeElement.querySelector('h1').textContent.trim();

    fixture.componentInstance.language.set('zh-TW');
    fixture.detectChanges();

    expect(fixture.nativeElement.querySelector('h1').textContent.trim()).not.toBe(englishTitle);
  });

  it('projects account content through AppShell and AccountLayout', async () => {
    await TestBed.configureTestingModule({
      imports: [AccountPage],
      providers: [provideRouter([]), provideHttpClient(), provideHttpClientTesting()],
    }).compileComponents();

    const fixture = TestBed.createComponent(AccountPage);
    fixture.componentInstance.routeKey = 'login';
    fixture.componentInstance.fields = ['email'];
    fixture.detectChanges();

    expect(fixture.nativeElement.querySelector('app-shell')).toBeTruthy();
    expect(fixture.nativeElement.querySelector('app-account-layout')).toBeTruthy();
  });

  it('marks invalid fields and describes their validation feedback', async () => {
    await TestBed.configureTestingModule({
      imports: [AccountPage],
      providers: [provideRouter([]), provideHttpClient(), provideHttpClientTesting()],
    }).compileComponents();

    const fixture = TestBed.createComponent(AccountPage);
    fixture.componentInstance.routeKey = 'login';
    fixture.componentInstance.ngOnInit();
    fixture.componentInstance.submit();
    fixture.detectChanges();

    const input = fixture.nativeElement.querySelector('input');
    const error = fixture.nativeElement.querySelector('.field-error');
    expect(input.getAttribute('aria-invalid')).toBe('true');
    expect(input.getAttribute('aria-describedby')).toBe(error.id);
    expect(error.id).toBe('field-email-error');
    expect(error.textContent.trim()).toBeTruthy();
  });

  it('marks the preferred language select invalid with its field error', async () => {
    await TestBed.configureTestingModule({
      imports: [AccountPage],
      providers: [provideRouter([]), provideHttpClient(), provideHttpClientTesting()],
    }).compileComponents();

    const fixture = TestBed.createComponent(AccountPage);
    fixture.componentInstance.routeKey = 'register';
    fixture.componentInstance.ngOnInit();
    fixture.componentInstance.submit();
    fixture.detectChanges();

    const select = fixture.nativeElement.querySelector('select[name="preferredLanguage"]');
    expect(select.getAttribute('aria-invalid')).toBe('true');
    expect(select.getAttribute('aria-describedby')).toBe('field-preferredLanguage-error');
  });

  it('synchronizes the preferred language after a profile update', async () => {
    await TestBed.configureTestingModule({
      imports: [AccountPage],
      providers: [provideRouter([]), provideHttpClient(), provideHttpClientTesting()],
    }).compileComponents();

    localStorage.clear();
    const fixture = TestBed.createComponent(AccountPage);
    fixture.componentInstance.routeKey = 'account/profile';
    fixture.componentInstance.ngOnInit();
    const http = TestBed.inject(HttpTestingController);
    http.expectOne('/api/v1/account/me').flush({
      id: 'user-1',
      displayName: 'Ada',
      preferredLanguage: 'zh-TW',
    });
    const usePreferred = vi.spyOn(fixture.componentInstance.language, 'usePreferred');
    fixture.componentInstance.form.patchValue({
      displayName: 'Ada Lovelace',
      preferredLanguage: 'en',
    });
    fixture.componentInstance.submit();

    const request = http.expectOne('/api/v1/account/profile');
    request.flush({
      id: 'user-1',
      displayName: 'Ada Lovelace',
      preferredLanguage: 'en',
    });

    expect(usePreferred).toHaveBeenCalledWith('en');
  });

  it('shows session metadata and only offers revoke for other sessions', async () => {
    await TestBed.configureTestingModule({
      imports: [AccountPage],
      providers: [provideRouter([]), provideHttpClient(), provideHttpClientTesting()],
    }).compileComponents();

    const fixture = TestBed.createComponent(AccountPage);
    fixture.componentInstance.routeKey = 'account/sessions';
    fixture.detectChanges();
    TestBed.inject(HttpTestingController)
      .expectOne('/api/v1/auth/sessions?page=0')
      .flush([
        { id: 'current', current: true, createdAt: '2026-08-12T09:00:00Z' },
        { id: 'other', current: false, createdAt: '2026-08-11T09:00:00Z' },
      ]);
    fixture.detectChanges();

    expect(fixture.nativeElement.textContent).toContain('2026-08-12T09:00:00Z');
    expect(fixture.nativeElement.textContent).toContain('2026-08-11T09:00:00Z');
    expect(
      [...fixture.nativeElement.querySelectorAll('button')].filter((button) =>
        button.textContent.includes('Revoke'),
      ),
    ).toHaveLength(1);
  });
});
