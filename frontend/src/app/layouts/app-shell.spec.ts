import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { AppShell } from './app-shell';

describe('AppShell', () => {
  beforeEach(async () => {
    localStorage.removeItem('blog-admin-token');
    await TestBed.configureTestingModule({
      imports: [AppShell],
      providers: [provideRouter([]), provideHttpClient(), provideHttpClientTesting()],
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

  it('shows public navigation and hides protected navigation anonymously', () => {
    const fixture = TestBed.createComponent(AppShell);
    fixture.detectChanges();

    expect(fixture.nativeElement.querySelector('a[href="/public/articles"]')).not.toBeNull();
    expect(fixture.nativeElement.querySelector('a[href="/public/tags"]')).not.toBeNull();
    expect(
      fixture.nativeElement.querySelector('a[href="/account/profile"]').parentElement.hidden,
    ).toBe(true);
    expect(
      fixture.nativeElement.querySelector('a[href="/admin/articles"]').parentElement.hidden,
    ).toBe(true);
    expect(fixture.nativeElement.querySelector('a[href="/login"]')).not.toBeNull();
  });
});
