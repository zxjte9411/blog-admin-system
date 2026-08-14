import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting, HttpTestingController } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { Location } from '@angular/common';
import { provideRouter, Router } from '@angular/router';
import { App } from './app';
import { routes } from './app.routes';
import { Auth } from './core/auth';

describe('App', () => {
  it('creates the application shell', async () => {
    await TestBed.configureTestingModule({
      imports: [App],
      providers: [provideRouter(routes)],
    }).compileComponents();

    expect(TestBed.createComponent(App).componentInstance).toBeTruthy();
  });

  it('redirects anonymous protected navigation to login', async () => {
    await TestBed.configureTestingModule({
      imports: [App],
      providers: [provideRouter(routes), provideHttpClient(), provideHttpClientTesting()],
    }).compileComponents();

    TestBed.inject(Auth).clear();
    await TestBed.inject(Router).navigateByUrl('/account/profile');

    expect(TestBed.inject(Location).path()).toBe('/login');
  });

  it('navigates the root to public articles', async () => {
    await TestBed.configureTestingModule({
      imports: [App],
      providers: [provideRouter(routes), provideHttpClient(), provideHttpClientTesting()],
    }).compileComponents();

    await TestBed.inject(Router).navigateByUrl('/');

    expect(TestBed.inject(Location).path()).toBe('/public/articles');
  });

  it('exposes account entry links through the rendered account page', async () => {
    await TestBed.configureTestingModule({
      imports: [App],
      providers: [provideRouter(routes), provideHttpClient(), provideHttpClientTesting()],
    }).compileComponents();

    const fixture = TestBed.createComponent(App);
    await TestBed.inject(Router).navigateByUrl('/register');
    fixture.detectChanges();
    const links = [...fixture.nativeElement.querySelectorAll('a')].map((link) =>
      link.getAttribute('href'),
    );

    expect(links).toEqual(expect.arrayContaining(['/login', '/verify-email']));
  });

  it('redirects a non-admin away from administration navigation', async () => {
    await TestBed.configureTestingModule({
      imports: [App],
      providers: [provideRouter(routes), provideHttpClient(), provideHttpClientTesting()],
    }).compileComponents();

    const auth = TestBed.inject(Auth);
    auth.setToken('token');
    auth.user = {
      id: 'author-1',
      displayName: 'Ada',
      preferredLanguage: 'en',
      role: 'AUTHOR',
    };

    await TestBed.inject(Router).navigateByUrl('/admin/users');

    expect(TestBed.inject(Location).path()).toBe('/forbidden');
    TestBed.inject(HttpTestingController).expectNone('/api/v1/account/me');
  });

  it('exposes shared article routes to authenticated authors and removes admin article routes', () => {
    expect(routes.map((route) => route.path)).toEqual(
      expect.arrayContaining(['articles', 'articles/new', 'articles/:id/edit', 'articles/deleted']),
    );
    expect(routes.map((route) => route.path)).not.toEqual(
      expect.arrayContaining([
        'admin/articles',
        'admin/articles/new',
        'admin/articles/deleted',
        'admin/articles/:id',
      ]),
    );
  });
});
