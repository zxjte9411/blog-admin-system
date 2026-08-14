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
    vi.spyOn(TestBed.inject(Router), 'navigateByUrl').mockResolvedValue(true);
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
    fixture.componentInstance.auth.setToken('local-token');
    fixture.componentInstance.logout();

    expect(supabase.signOut).toHaveBeenCalledWith({ scope: 'local' });
    TestBed.inject(HttpTestingController).expectOne('/api/v1/auth/logout').flush({});
    await Promise.resolve();

    expect(fixture.componentInstance.auth.token).toBeNull();
  });
});
