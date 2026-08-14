import { routes } from './app.routes';

describe('article use-case routes', () => {
  it('lazy-loads each article use case independently', async () => {
    const articleRoutes = routes.filter((route) =>
      ['articles', 'articles/new', 'articles/deleted', 'articles/:id/edit'].includes(
        route.path ?? '',
      ),
    );

    const components = await Promise.all(
      articleRoutes.map((route) => (route.loadComponent as () => Promise<unknown>)()),
    );

    expect(new Set(components).size).toBe(4);
  });

  it('only protects article creation and editing with canDeactivate', () => {
    expect(routes.find((route) => route.path === 'articles')?.canDeactivate).toBeUndefined();
    expect(
      routes.find((route) => route.path === 'articles/deleted')?.canDeactivate,
    ).toBeUndefined();
    expect(routes.find((route) => route.path === 'articles/new')?.canDeactivate).toHaveLength(1);
    expect(routes.find((route) => route.path === 'articles/:id/edit')?.canDeactivate).toHaveLength(
      1,
    );
    expect(routes.find((route) => route.path === 'admin/users')?.canDeactivate).toBeUndefined();
  });
});
