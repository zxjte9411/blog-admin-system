import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter, Router } from '@angular/router';
import { SUPABASE_AUTH } from '../core/supabase';
import { AppShell } from './app-shell';

describe('AppShell', () => {
  let supabase: ReturnType<typeof fakeSupabase>;

  const fakeSupabase = () => ({
    signInWithOAuth: vi.fn().mockResolvedValue({ error: null }),
    getSession: vi.fn().mockResolvedValue({ data: { session: null }, error: null }),
    signOut: vi.fn().mockResolvedValue({ error: null }),
  });

  beforeEach(async () => {
    supabase = fakeSupabase();
    localStorage.removeItem('blog-admin-token');
    await TestBed.configureTestingModule({
      imports: [AppShell],
      providers: [
        provideRouter([]),
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: SUPABASE_AUTH, useValue: supabase },
      ],
    }).compileComponents();
  });

  it('provides the shell landmarks and skip link', () => {
    const fixture = TestBed.createComponent(AppShell);
    fixture.detectChanges();

    expect(fixture.nativeElement.querySelectorAll('header')).toHaveLength(1);
    expect(fixture.nativeElement.querySelectorAll('aside')).toHaveLength(1);
    expect(fixture.nativeElement.querySelectorAll('main')).toHaveLength(1);
    expect(fixture.nativeElement.querySelector('.skip-link').getAttribute('href')).toBe('#content');
    expect(fixture.nativeElement.querySelector('main').id).toBe('content');
  });

  it('opens the mobile navigation drawer and restores focus when dismissed', () => {
    const fixture = TestBed.createComponent(AppShell);
    fixture.detectChanges();

    const menuButton = fixture.nativeElement.querySelector('.menu-toggle') as HTMLButtonElement;
    expect(menuButton.getAttribute('aria-label')).toBe('Open navigation menu');
    expect(menuButton.getAttribute('aria-expanded')).toBe('false');
    expect(menuButton.getAttribute('aria-controls')).toBe('mobile-navigation');

    menuButton.click();
    fixture.detectChanges();

    const drawer = fixture.nativeElement.querySelector('#mobile-navigation') as HTMLElement;
    const backdrop = fixture.nativeElement.querySelector('.drawer-backdrop') as HTMLButtonElement;
    expect(menuButton.getAttribute('aria-expanded')).toBe('true');
    expect(drawer.classList.contains('is-open')).toBe(true);
    expect(document.body.style.overflow).toBe('hidden');
    expect(drawer.contains(document.activeElement)).toBe(true);

    const links = Array.from(drawer.querySelectorAll<HTMLAnchorElement>('a.nav-link')).filter(
      (link) => !link.closest('li')?.hidden,
    );
    links.at(-1)?.focus();
    drawer.dispatchEvent(new KeyboardEvent('keydown', { key: 'Tab', bubbles: true }));
    expect(document.activeElement).toBe(links[0]);

    drawer.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true }));
    fixture.detectChanges();
    expect(menuButton.getAttribute('aria-expanded')).toBe('false');
    expect(document.body.style.overflow).toBe('');
    expect(document.activeElement).toBe(menuButton);

    menuButton.click();
    fixture.detectChanges();
    backdrop.click();
    fixture.detectChanges();
    expect(menuButton.getAttribute('aria-expanded')).toBe('false');
  });

  it('keeps focus within visible anonymous navigation links', () => {
    const fixture = TestBed.createComponent(AppShell);
    fixture.detectChanges();

    const menuButton = fixture.nativeElement.querySelector('.menu-toggle') as HTMLButtonElement;
    menuButton.click();
    fixture.detectChanges();

    const drawer = fixture.nativeElement.querySelector('#mobile-navigation') as HTMLElement;
    const visibleLinks = Array.from(
      drawer.querySelectorAll<HTMLAnchorElement>('a.nav-link'),
    ).filter((link) => !link.closest('li')?.hidden);
    visibleLinks.at(-1)?.focus();
    drawer.dispatchEvent(new KeyboardEvent('keydown', { key: 'Tab', bubbles: true }));

    expect(document.activeElement).toBe(visibleLinks[0]);
  });

  it('toggles the drawer from the menu button and restores focus', () => {
    const fixture = TestBed.createComponent(AppShell);
    fixture.detectChanges();

    const menuButton = fixture.nativeElement.querySelector('.menu-toggle') as HTMLButtonElement;
    menuButton.click();
    fixture.detectChanges();
    expect(menuButton.getAttribute('aria-label')).toBe('Close navigation menu');

    menuButton.click();
    fixture.detectChanges();

    expect(menuButton.getAttribute('aria-expanded')).toBe('false');
    expect(document.activeElement).toBe(menuButton);
  });

  it('closes when a navigation link is activated', () => {
    const fixture = TestBed.createComponent(AppShell);
    fixture.detectChanges();
    vi.spyOn(TestBed.inject(Router), 'navigateByUrl').mockResolvedValue(true);

    const menuButton = fixture.nativeElement.querySelector('.menu-toggle') as HTMLButtonElement;
    menuButton.click();
    fixture.detectChanges();
    (fixture.nativeElement.querySelector('a[href="/public/tags"]') as HTMLAnchorElement).click();
    fixture.detectChanges();

    expect(menuButton.getAttribute('aria-expanded')).toBe('false');
    expect(document.activeElement).toBe(menuButton);
  });

  it('closes when router navigation starts', async () => {
    const fixture = TestBed.createComponent(AppShell);
    fixture.detectChanges();

    const menuButton = fixture.nativeElement.querySelector('.menu-toggle') as HTMLButtonElement;
    menuButton.click();
    fixture.detectChanges();

    await TestBed.inject(Router)
      .navigateByUrl('/public/articles')
      .catch(() => false);
    fixture.detectChanges();

    expect(menuButton.getAttribute('aria-expanded')).toBe('false');
    expect(document.activeElement).toBe(menuButton);
  });

  it('shows public navigation and hides protected navigation anonymously', () => {
    const fixture = TestBed.createComponent(AppShell);
    fixture.detectChanges();

    expect(fixture.nativeElement.querySelector('a[href="/public/articles"]')).not.toBeNull();
    expect(fixture.nativeElement.querySelector('a[href="/public/tags"]')).not.toBeNull();
    expect(
      fixture.nativeElement.querySelector('a[href="/account/profile"]').parentElement.hidden,
    ).toBe(true);
    expect(fixture.nativeElement.querySelector('a[href="/articles"]').parentElement.hidden).toBe(
      true,
    );
    expect(fixture.nativeElement.querySelector('a[href="/login"]')).not.toBeNull();
  });

  it('logs out from the local session and Supabase', async () => {
    const fixture = TestBed.createComponent(AppShell);
    vi.spyOn(TestBed.inject(Router), 'navigateByUrl').mockResolvedValue(true);
    fixture.componentInstance.auth.setToken('local-token');
    fixture.componentInstance.logout();

    expect(supabase.signOut).toHaveBeenCalledWith({ scope: 'local' });
    TestBed.inject(HttpTestingController).expectOne('/api/v1/auth/logout').flush({});
    await Promise.resolve();

    expect(fixture.componentInstance.auth.token).toBeNull();
  });
});
