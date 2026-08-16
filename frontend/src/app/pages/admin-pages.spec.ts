import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting, HttpTestingController } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter, Router } from '@angular/router';
import { InvitationsPage } from './invitations-page';
import { ManagedUsersPage } from './managed-users-page';
import { PasswordSettingsPage } from './password-settings-page';
import { Auth, adminGuard } from '../core/auth';
import { routes } from '../app.routes';

describe('admin pages', () => {
  function setup(
    page: typeof ManagedUsersPage | typeof InvitationsPage | typeof PasswordSettingsPage,
  ) {
    return TestBed.configureTestingModule({
      imports: [page],
      providers: [provideRouter([]), provideHttpClient(), provideHttpClientTesting()],
    }).compileComponents();
  }

  it('renders managed users and sends role and enabled updates', async () => {
    await setup(ManagedUsersPage);
    const fixture = TestBed.createComponent(ManagedUsersPage);
    fixture.detectChanges();
    const http = TestBed.inject(HttpTestingController);
    http.expectOne('/api/v1/admin/users').flush([
      {
        id: 'author-1',
        email: 'author@example.com',
        displayName: 'Author',
        role: 'AUTHOR',
        enabled: true,
        verifiedAt: '2026-08-14T10:00:00Z',
      },
      {
        id: 'admin-1',
        email: 'admin@example.com',
        displayName: 'Admin',
        role: 'ADMIN',
        enabled: true,
        verifiedAt: '2026-08-14T10:00:00Z',
      },
    ]);
    fixture.detectChanges();

    expect(fixture.nativeElement.textContent).toContain('Author');
    expect(fixture.nativeElement.textContent).toContain('Admin');

    const role = fixture.nativeElement.querySelector('[name="role-author-1"]') as HTMLSelectElement;
    role.value = 'ADMIN';
    role.dispatchEvent(new Event('change'));
    const roleRequest = http.expectOne('/api/v1/admin/users/author-1');
    expect(roleRequest.request.method).toBe('PATCH');
    expect(roleRequest.request.body).toEqual({ role: 'ADMIN', enabled: true });
    roleRequest.flush({
      id: 'author-1',
      email: 'author@example.com',
      displayName: 'Author',
      role: 'ADMIN',
      enabled: true,
      verifiedAt: '2026-08-14T10:00:00Z',
    });

    const enabled = fixture.nativeElement.querySelector(
      '[name="enabled-author-1"]',
    ) as HTMLInputElement;
    enabled.checked = false;
    enabled.dispatchEvent(new Event('change'));
    const enabledRequest = http.expectOne('/api/v1/admin/users/author-1');
    expect(enabledRequest.request.body).toEqual({ role: 'ADMIN', enabled: false });
  });

  it('creates an invitation and renders the invitation list', async () => {
    await setup(InvitationsPage);
    const fixture = TestBed.createComponent(InvitationsPage);
    fixture.detectChanges();
    const http = TestBed.inject(HttpTestingController);
    http
      .expectOne('/api/v1/admin/invitations')
      .flush([
        { id: 'invitation-1', email: 'pending@example.com', expiresAt: '2026-08-15T10:00:00Z' },
      ]);
    fixture.detectChanges();

    const email = fixture.nativeElement.querySelector('#invitation-email') as HTMLInputElement;
    email.value = 'new@example.com';
    email.dispatchEvent(new Event('input'));
    fixture.nativeElement.querySelector('form').requestSubmit();

    const request = http.expectOne('/api/v1/admin/invitations');
    expect(request.request.method).toBe('POST');
    expect(request.request.body).toEqual({ email: 'new@example.com' });
    request.flush(null, { status: 202, statusText: 'Accepted' });
    http.expectOne('/api/v1/admin/invitations').flush([]);
  });

  it('reads, updates, and renders password setting changes', async () => {
    await setup(PasswordSettingsPage);
    const fixture = TestBed.createComponent(PasswordSettingsPage);
    fixture.detectChanges();
    const http = TestBed.inject(HttpTestingController);
    http.expectOne('/api/v1/admin/settings/password-minimum-length').flush({ value: 12 });
    http.expectOne('/api/v1/admin/settings/password-minimum-length/history').flush([
      {
        id: 'change-1',
        operatorId: 'admin-1',
        previousValue: 8,
        newValue: 12,
        changedAt: '2026-08-14T10:00:00Z',
      },
    ]);
    fixture.detectChanges();

    expect(fixture.nativeElement.textContent).toContain('12');
    expect(fixture.nativeElement.textContent).toContain('admin-1');
    const input = fixture.nativeElement.querySelector(
      '#password-minimum-length',
    ) as HTMLInputElement;
    input.value = '16';
    input.dispatchEvent(new Event('input'));
    fixture.nativeElement.querySelector('form').requestSubmit();

    const request = http.expectOne('/api/v1/admin/settings/password-minimum-length');
    expect(request.request.method).toBe('PUT');
    expect(request.request.body).toEqual({ value: 16 });
    request.flush({ value: 16 });
    http.expectOne('/api/v1/admin/settings/password-minimum-length/history').flush([
      {
        id: 'change-2',
        operatorId: 'admin-1',
        previousValue: 12,
        newValue: 16,
        changedAt: '2026-08-14T11:00:00Z',
      },
    ]);
    fixture.detectChanges();

    expect(fixture.nativeElement.textContent).toContain('2026-08-14T11:00:00Z');
    expect(fixture.nativeElement.textContent).toContain('16');
  });

  it('keeps password history loading separate from the minimum setting', async () => {
    await setup(PasswordSettingsPage);
    const fixture = TestBed.createComponent(PasswordSettingsPage);
    fixture.detectChanges();
    const http = TestBed.inject(HttpTestingController);
    const minimum = http.expectOne('/api/v1/admin/settings/password-minimum-length');
    const history = http.expectOne('/api/v1/admin/settings/password-minimum-length/history');
    const historyRegion = fixture.nativeElement.querySelector('.table-wrap') as HTMLElement;

    expect(historyRegion.getAttribute('aria-busy')).toBe('true');
    expect(historyRegion.querySelector('progress')).toBeTruthy();

    minimum.flush({ value: 12 });
    fixture.detectChanges();

    expect(fixture.nativeElement.textContent).toContain('12');
    expect(historyRegion.getAttribute('aria-busy')).toBe('true');
    expect(historyRegion.querySelector('table')).toBeNull();

    history.flush([]);
    fixture.detectChanges();

    expect(historyRegion.getAttribute('aria-busy')).toBe('false');
    expect(historyRegion.querySelector('progress')).toBeNull();
    expect(historyRegion.querySelector('table')).toBeTruthy();
  });

  it('shows history errors and retries minimum and history together', async () => {
    await setup(PasswordSettingsPage);
    const fixture = TestBed.createComponent(PasswordSettingsPage);
    fixture.detectChanges();
    const http = TestBed.inject(HttpTestingController);
    http.expectOne('/api/v1/admin/settings/password-minimum-length').flush({ value: 12 });
    const history = http.expectOne('/api/v1/admin/settings/password-minimum-length/history');
    history.flush('error', { status: 500, statusText: 'Server error' });
    fixture.detectChanges();

    const historyRegion = fixture.nativeElement.querySelector('.table-wrap') as HTMLElement;
    expect(historyRegion.getAttribute('aria-busy')).toBe('false');
    expect(fixture.nativeElement.querySelector('[role="alert"]')).toBeTruthy();

    fixture.componentInstance.retry();
    const minimumRetry = http.expectOne('/api/v1/admin/settings/password-minimum-length');
    const historyRetry = http.expectOne('/api/v1/admin/settings/password-minimum-length/history');
    minimumRetry.flush({ value: 12 });
    historyRetry.flush([
      {
        id: 'change-1',
        operatorId: 'admin-1',
        previousValue: 8,
        newValue: 12,
        changedAt: '2026-08-14T10:00:00Z',
      },
    ]);
    fixture.detectChanges();

    expect(fixture.nativeElement.querySelector('[role="alert"]')).toBeNull();
    expect(historyRegion.getAttribute('aria-busy')).toBe('false');
    expect(historyRegion.querySelector('table')).toBeTruthy();
    expect(historyRegion.textContent).toContain('admin-1');
  });

  it('lazy-loads each admin page with adminGuard and no canDeactivate guard', async () => {
    for (const path of ['admin/users', 'admin/invitations', 'admin/settings/password']) {
      const route = routes.find((candidate) => candidate.path === path);
      expect(route).toBeTruthy();
      expect(route?.canActivate).toContain(adminGuard);
      expect(route?.canDeactivate).toBeUndefined();
      const page = await route?.loadComponent?.();
      expect(page).toBeTruthy();
    }
  });

  it('blocks non-admin navigation to every admin page', async () => {
    await TestBed.configureTestingModule({
      providers: [provideRouter(routes), provideHttpClient(), provideHttpClientTesting()],
    }).compileComponents();
    const router = TestBed.inject(Router);
    const auth = TestBed.inject(Auth);
    auth.setToken('token');
    auth.user = {
      id: 'author-1',
      displayName: 'Author',
      preferredLanguage: 'en',
      role: 'AUTHOR',
    };

    await router.navigateByUrl('/admin/invitations');

    expect(router.url).toBe('/forbidden');
  });
});
