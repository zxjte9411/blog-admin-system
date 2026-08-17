import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { of } from 'rxjs';
import { UserApi } from '../core/api';
import { UserEmailPage } from './user-email-page';
import { UserPasswordPage } from './user-password-page';
import { UserProfilePage } from './user-profile-page';
import { UserSessionsPage } from './user-sessions-page';

describe('user account pages', () => {
  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideRouter([]), provideHttpClient(), provideHttpClientTesting()],
    });
  });

  it('updates profile and synchronizes the preferred language', () => {
    const api = TestBed.inject(UserApi);
    vi.spyOn(api, 'me').mockReturnValue(
      of({ id: 'user-1', displayName: 'Ada', preferredLanguage: 'zh-TW', role: 'AUTHOR' }),
    );
    const profile = vi
      .spyOn(api, 'profile')
      .mockReturnValue(of({ displayName: 'Ada Lovelace', preferredLanguage: 'en' }));
    const page = TestBed.createComponent(UserProfilePage).componentInstance;
    page.ngOnInit();
    page.form.patchValue({ displayName: 'Ada Lovelace', preferredLanguage: 'en' });
    page.submit();

    expect(profile).toHaveBeenCalledWith({ displayName: 'Ada Lovelace', preferredLanguage: 'en' });
    expect(page.language.lang()).toBe('en');
    expect(page.auth.user?.displayName).toBe('Ada Lovelace');
  });

  it('submits password and email changes from independent pages', () => {
    const api = TestBed.inject(UserApi);
    const password = vi.spyOn(api, 'password').mockReturnValue(of());
    const email = vi.spyOn(api, 'requestEmailChange').mockReturnValue(of());
    const passwordPage = TestBed.createComponent(UserPasswordPage).componentInstance;
    passwordPage.form.patchValue({ currentPassword: 'old', newPassword: 'new' });
    passwordPage.submit();
    const emailPage = TestBed.createComponent(UserEmailPage).componentInstance;
    emailPage.form.patchValue({ email: 'new@example.com' });
    emailPage.submit();

    expect(password).toHaveBeenCalledWith({ currentPassword: 'old', newPassword: 'new' });
    expect(email).toHaveBeenCalledWith({ email: 'new@example.com' });
  });

  it('renders profile and password controls in separate named fieldsets', () => {
    const api = TestBed.inject(UserApi);
    vi.spyOn(api, 'me').mockReturnValue(
      of({ id: 'user-1', displayName: 'Ada', preferredLanguage: 'zh-TW', role: 'AUTHOR' }),
    );

    const profileFixture = TestBed.createComponent(UserProfilePage);
    profileFixture.detectChanges();
    const passwordFixture = TestBed.createComponent(UserPasswordPage);
    passwordFixture.detectChanges();

    const profileFieldset = profileFixture.nativeElement.querySelector(
      'fieldset.account-fields',
    ) as HTMLFieldSetElement;
    const passwordFieldset = passwordFixture.nativeElement.querySelector(
      'fieldset.account-fields',
    ) as HTMLFieldSetElement;
    expect(profileFieldset).toBeTruthy();
    expect(profileFieldset.querySelector('legend')?.textContent?.trim()).toBeTruthy();
    expect(passwordFieldset).toBeTruthy();
    expect(passwordFieldset.querySelector('legend')?.textContent?.trim()).toBeTruthy();
  });

  it('renders email controls in a named fieldset', () => {
    const fixture = TestBed.createComponent(UserEmailPage);
    fixture.detectChanges();

    const fieldset = fixture.nativeElement.querySelector(
      'fieldset.account-fields',
    ) as HTMLFieldSetElement;
    expect(fieldset).toBeTruthy();
    expect(fieldset.querySelector('legend')?.textContent?.trim()).toBeTruthy();
    expect(fieldset.querySelector('#field-email')).toBeTruthy();
  });

  it('keeps sessions pagination local to UserSessionsPage', () => {
    const page = TestBed.createComponent(UserSessionsPage).componentInstance;
    page.ngOnInit();
    const http = TestBed.inject(HttpTestingController);
    const sessions = Array.from({ length: 11 }, (_, index) => ({
      id: `session-${index}`,
      current: index === 0,
      createdAt: `created-${index}`,
      lastUsedAt: `used-${index}`,
    }));
    http.expectOne('/api/v1/auth/sessions?page=0').flush(sessions);

    expect(page.totalPages).toBe(2);
    expect(page.pagedSessions).toHaveLength(10);
    page.nextPage();
    expect(page.page).toBe(1);
    expect(page.pagedSessions).toHaveLength(1);
    page.previousPage();
    expect(page.page).toBe(0);
  });

  it('renders refresh sessions as labeled records without hiding revoke actions', () => {
    const fixture = TestBed.createComponent(UserSessionsPage);
    fixture.detectChanges();
    const http = TestBed.inject(HttpTestingController);
    http.expectOne('/api/v1/auth/sessions?page=0').flush([
      {
        id: 'session-with-a-long-identifier-that-must-wrap',
        current: false,
        createdAt: '2026-08-14T10:00:00Z',
        lastUsedAt: '2026-08-14T11:00:00Z',
      },
    ]);
    fixture.detectChanges();

    const table = fixture.nativeElement.querySelector('table') as HTMLTableElement;
    expect(table.classList).toContain('responsive-data-table');
    expect(
      [...table.querySelectorAll('tbody td')].every((cell) => cell.getAttribute('data-label')),
    ).toBe(true);
    expect(fixture.nativeElement.querySelector('.danger')?.textContent).toContain(
      fixture.componentInstance.language.t.revoke,
    );
  });

  it('reloads sessions after a successful revoke', () => {
    const page = TestBed.createComponent(UserSessionsPage).componentInstance;
    page.ngOnInit();
    const http = TestBed.inject(HttpTestingController);
    const session = {
      id: 'session-1',
      current: false,
      createdAt: 'created',
      lastUsedAt: 'used',
    };
    http.expectOne('/api/v1/auth/sessions?page=0').flush([session]);

    page.revoke(session);

    http.expectOne('/api/v1/auth/sessions/session-1').flush({});
    const reload = http.expectOne('/api/v1/auth/sessions?page=0');
    reload.flush([]);

    expect(page.items).toEqual([]);
    expect(page.loading).toBe(false);
  });

  it('shows a revoke error without reloading sessions', () => {
    const page = TestBed.createComponent(UserSessionsPage).componentInstance;
    page.ngOnInit();
    const http = TestBed.inject(HttpTestingController);
    const session = {
      id: 'session-1',
      current: false,
      createdAt: 'created',
      lastUsedAt: 'used',
    };
    http.expectOne('/api/v1/auth/sessions?page=0').flush([session]);

    page.revoke(session);
    http.expectOne('/api/v1/auth/sessions/session-1').flush('failed', {
      status: 500,
      statusText: 'Internal Server Error',
    });

    expect(page.error).toBeTruthy();
    expect(page.loading).toBe(false);
    http.expectNone('/api/v1/auth/sessions?page=0');
  });

  it('retries a failed sessions load', () => {
    const page = TestBed.createComponent(UserSessionsPage).componentInstance;
    page.ngOnInit();
    const http = TestBed.inject(HttpTestingController);
    http.expectOne('/api/v1/auth/sessions?page=0').flush('failed', {
      status: 500,
      statusText: 'Internal Server Error',
    });

    page.retrySessions();

    const retry = http.expectOne('/api/v1/auth/sessions?page=0');
    retry.flush([]);
    expect(page.error).toBe('');
    expect(page.loading).toBe(false);
  });
});
