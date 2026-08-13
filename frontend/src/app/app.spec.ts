import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { App } from './app';
import { routes } from './app.routes';

describe('App', () => {
  it('creates the application shell', async () => {
    await TestBed.configureTestingModule({
      imports: [App],
      providers: [provideRouter(routes)],
    }).compileComponents();

    expect(TestBed.createComponent(App).componentInstance).toBeTruthy();
  });

  it('exposes public and authenticated feature routes', () => {
    const paths = routes.map((route) => route.path);

    expect(paths).toEqual(
      expect.arrayContaining(['login', 'public/articles', 'admin/articles', 'account/profile']),
    );
  });

  it('provides a revoke action for account sessions', async () => {
    const sessionRoute = routes.find((route) => route.path === 'account/sessions');
    expect(sessionRoute?.loadComponent).toBeDefined();
    expect(await sessionRoute!.loadComponent!()).toBeTruthy();
  });
});
