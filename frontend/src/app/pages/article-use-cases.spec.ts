import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting, HttpTestingController } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { ActivatedRoute, provideRouter } from '@angular/router';
import { ArticleCreatePage } from './article-create-page';
import { ArticleEditPage } from './article-edit-page';
import { ArticleListPage } from './article-list-page';
import { canLeaveArticle } from './article-editor-page';
import { DeletedArticlesPage } from './deleted-articles-page';
import { Language } from '../core/language';
import { Auth } from '../core/auth';
import { Article, Page } from '../core/api';

function setup(
  component:
    | typeof ArticleCreatePage
    | typeof ArticleEditPage
    | typeof ArticleListPage
    | typeof DeletedArticlesPage,
  path: string,
  id: string | null = null,
) {
  return TestBed.configureTestingModule({
    imports: [component],
    providers: [
      provideRouter([{ path: 'articles', component: ArticleCreatePage }]),
      provideHttpClient(),
      provideHttpClientTesting(),
      {
        provide: ActivatedRoute,
        useValue: {
          snapshot: {
            routeConfig: { path },
            paramMap: { get: () => id },
            queryParamMap: { get: () => null },
          },
        },
      },
    ],
  }).compileComponents();
}

describe('article use-case pages', () => {
  afterEach(() => vi.restoreAllMocks());

  it('models the backend nested page metadata in the shared Page DTO', () => {
    const page: Page<Article> = {
      content: [],
      totalPages: 0,
      page: { totalPages: 3 },
    };
    expect(page.page?.totalPages).toBe(3);
  });

  it('shows required field errors and submits a new article', async () => {
    await setup(ArticleCreatePage, 'articles/new');
    const fixture = TestBed.createComponent(ArticleCreatePage);
    fixture.detectChanges();
    const http = TestBed.inject(HttpTestingController);
    http.expectOne('/api/v1/public/tags?size=100').flush([]);
    fixture.componentInstance.submit();
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('.field-error')).toBeTruthy();
    fixture.componentInstance.form.patchValue({ title: 'Title', content: 'Content' });
    fixture.componentInstance.submit();
    const request = http.expectOne('/api/v1/articles');
    expect(request.request.method).toBe('POST');
    request.flush({});
  });

  it('loads and updates an existing article', async () => {
    await setup(ArticleEditPage, 'articles/:id/edit', 'article-1');
    const fixture = TestBed.createComponent(ArticleEditPage);
    fixture.detectChanges();
    const http = TestBed.inject(HttpTestingController);
    http.expectOne('/api/v1/public/tags?size=100').flush([]);
    http.expectOne('/api/v1/articles/article-1').flush({
      title: 'Old',
      content: 'Body',
      status: 'DRAFT',
      version: 2,
      tagIds: [],
      tagNames: [],
    });
    fixture.componentInstance.form.patchValue({ title: 'New' });
    fixture.componentInstance.submit();
    const request = http.expectOne('/api/v1/articles/article-1');
    expect(request.request.method).toBe('PUT');
    request.flush({});
  });

  it('prefills the article editor and selected tags from an existing article', async () => {
    await setup(ArticleEditPage, 'articles/:id/edit', 'article-1');
    const fixture = TestBed.createComponent(ArticleEditPage);
    fixture.detectChanges();
    const http = TestBed.inject(HttpTestingController);
    http.expectOne('/api/v1/public/tags?size=100').flush({
      content: [
        { id: 'tag-1', name: 'Angular' },
        { id: 'tag-2', name: 'Spring' },
      ],
    });
    http.expectOne('/api/v1/articles/article-1').flush({
      title: 'Existing title',
      content: 'Existing content',
      status: 'PUBLISHED',
      tagIds: ['tag-1', 'tag-2'],
      tagNames: ['Angular', 'Spring'],
      version: 3,
    });
    fixture.detectChanges();

    expect((fixture.nativeElement.querySelector('#field-title') as HTMLInputElement).value).toBe(
      'Existing title',
    );
    expect((fixture.nativeElement.querySelector('#tag-tag-1') as HTMLInputElement).checked).toBe(
      true,
    );
    expect((fixture.nativeElement.querySelector('#tag-tag-2') as HTMLInputElement).checked).toBe(
      true,
    );
  });

  it('renders article status as two selectable radio options', async () => {
    await setup(ArticleCreatePage, 'articles/new');
    const fixture = TestBed.createComponent(ArticleCreatePage);
    fixture.detectChanges();
    TestBed.inject(HttpTestingController).expectOne('/api/v1/public/tags?size=100').flush([]);

    const radios = fixture.nativeElement.querySelectorAll('input[type="radio"]');
    expect(radios).toHaveLength(2);
    expect([...radios].map((radio: HTMLInputElement) => radio.value)).toEqual([
      'DRAFT',
      'PUBLISHED',
    ]);
  });

  it('renders new article controls in the intended order', async () => {
    await setup(ArticleCreatePage, 'articles/new');
    const fixture = TestBed.createComponent(ArticleCreatePage);
    fixture.detectChanges();
    TestBed.inject(HttpTestingController)
      .expectOne('/api/v1/public/tags?size=100')
      .flush({ content: [{ id: 'tag-1', name: 'Tech' }] });
    fixture.detectChanges();

    const form = fixture.nativeElement.querySelector('.article-form') as HTMLFormElement;
    const controls = [...form.children]
      .map((element: Element) => ({
        name: element.matches('.status-options')
          ? 'status'
          : element.matches('.tag-field')
            ? 'availableTags'
            : element.querySelector('.control')?.id?.replace('field-', ''),
        order: Number(getComputedStyle(element).order),
      }))
      .sort((left, right) => left.order - right.order)
      .map(({ name }) => name)
      .filter(Boolean);

    expect(controls).toEqual(['title', 'content', 'availableTags', 'tagNames', 'status']);
  });

  it('sends the article update with enum status and remaining tags', async () => {
    await setup(ArticleEditPage, 'articles/:id/edit', 'article-1');
    const fixture = TestBed.createComponent(ArticleEditPage);
    fixture.detectChanges();
    const http = TestBed.inject(HttpTestingController);
    http.expectOne('/api/v1/public/tags?size=100').flush({
      content: [
        { id: 'tag-1', name: 'Angular' },
        { id: 'tag-2', name: 'Spring' },
      ],
    });
    http.expectOne('/api/v1/articles/article-1').flush({
      title: 'Title',
      content: 'Content',
      status: 'DRAFT',
      tagIds: ['tag-1', 'tag-2'],
      tagNames: [],
      version: 3,
    });
    fixture.detectChanges();

    const tag1 = fixture.nativeElement.querySelector('#tag-tag-1') as HTMLInputElement;
    tag1.checked = false;
    tag1.dispatchEvent(new Event('change'));
    fixture.componentInstance.submit();

    const request = http.expectOne('/api/v1/articles/article-1');
    expect(request.request.method).toBe('PUT');
    expect(request.request.body).toEqual(
      expect.objectContaining({ status: 'DRAFT', tagIds: ['tag-2'], version: 3 }),
    );
  });

  it('uses nested Page metadata and reports list delete success', async () => {
    await setup(ArticleListPage, 'articles');
    const fixture = TestBed.createComponent(ArticleListPage);
    fixture.componentInstance.auth.user = {
      id: 'admin',
      displayName: 'Admin',
      preferredLanguage: 'zh-TW',
      role: 'ADMIN',
    };
    fixture.detectChanges();
    const http = TestBed.inject(HttpTestingController);
    http
      .expectOne('/api/v1/articles?page=0')
      .flush({ content: [{ id: 'a1', title: 'Title' }], page: { totalPages: 3 } });
    expect(fixture.componentInstance.totalPages).toBe(3);
    fixture.componentInstance.deleteArticle({ id: 'a1', title: 'Title' } as never);
    fixture.componentInstance.confirmModal();
    http.expectOne('/api/v1/articles/a1').flush(null);
    http.expectOne('/api/v1/articles?page=0').flush({ content: [], page: { totalPages: 0 } });
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('[role="status"]')).toBeTruthy();
  });

  it('searches articles from the first page and requests the next result page', async () => {
    await setup(ArticleListPage, 'articles');
    const fixture = TestBed.createComponent(ArticleListPage);
    fixture.detectChanges();
    const http = TestBed.inject(HttpTestingController);
    http.expectOne('/api/v1/articles?page=0').flush({ content: [], totalPages: 2 });

    fixture.componentInstance.search('needle');
    http.expectOne('/api/v1/articles?page=0&title=needle').flush({ content: [], totalPages: 2 });
    fixture.componentInstance.nextPage();
    expect(http.expectOne('/api/v1/articles?page=1&title=needle').request.params.get('page')).toBe(
      '1',
    );
  });

  it('allows authors to manage only their own articles', async () => {
    await setup(ArticleListPage, 'articles');
    const page = TestBed.createComponent(ArticleListPage).componentInstance;
    page.auth.user = {
      id: 'author-1',
      displayName: 'Ada',
      preferredLanguage: 'en',
      role: 'AUTHOR',
    };

    expect(page.canManageArticle({ owner: 'author-1' })).toBe(true);
    expect(page.canManageArticle({ owner: 'author-2' })).toBe(false);
    page.auth.user = { ...page.auth.user, role: 'ADMIN' };
    expect(page.canManageArticle({ owner: 'author-2' })).toBe(true);
  });

  it('renders the article management list for the article route', async () => {
    await setup(ArticleListPage, 'articles');
    const fixture = TestBed.createComponent(ArticleListPage);
    fixture.detectChanges();
    TestBed.inject(HttpTestingController)
      .expectOne('/api/v1/articles?page=0')
      .flush({ content: [], totalPages: 0 });
    fixture.detectChanges();

    expect(fixture.nativeElement.querySelector('app-article-management-list')).toBeTruthy();
  });

  it("prevents an author from opening another author's article editor", async () => {
    await setup(ArticleEditPage, 'articles/:id/edit', 'article-1');
    const fixture = TestBed.createComponent(ArticleEditPage);
    TestBed.inject(Auth).user = {
      id: 'author-1',
      displayName: 'Ada',
      preferredLanguage: 'en',
      role: 'AUTHOR',
    };
    fixture.detectChanges();
    const http = TestBed.inject(HttpTestingController);
    http.expectOne('/api/v1/public/tags?size=100').flush([]);
    http.expectOne('/api/v1/articles/article-1').flush({ owner: 'author-2' });
    fixture.detectChanges();

    expect(fixture.nativeElement.querySelector('[role="alert"]')).toBeTruthy();
  });

  it.each([401, 403, 404, 409])('maps article list status %s', async (status) => {
    await setup(ArticleListPage, 'articles');
    const fixture = TestBed.createComponent(ArticleListPage);
    fixture.detectChanges();
    TestBed.inject(HttpTestingController)
      .expectOne('/api/v1/articles?page=0')
      .flush('error', { status, statusText: 'error' });
    const language = TestBed.inject(Language);
    const expected =
      status === 401
        ? language.t.unauthorized
        : status === 403
          ? language.t.forbidden
          : status === 404
            ? language.t.notFound
            : language.t.conflict;
    expect(fixture.componentInstance.error).toBe(expected);
  });

  it.each([401, 403, 404, 409])('maps deleted article status %s', async (status) => {
    await setup(DeletedArticlesPage, 'articles/deleted');
    const fixture = TestBed.createComponent(DeletedArticlesPage);
    fixture.detectChanges();
    TestBed.inject(HttpTestingController)
      .expectOne('/api/v1/articles/deleted?page=0')
      .flush('error', { status, statusText: 'error' });
    const language = TestBed.inject(Language);
    const expected =
      status === 401
        ? language.t.unauthorized
        : status === 403
          ? language.t.forbidden
          : status === 404
            ? language.t.notFound
            : language.t.conflict;
    expect(fixture.componentInstance.error).toBe(expected);
  });

  it('retains editor placeholders and links status errors to its controls', async () => {
    await setup(ArticleCreatePage, 'articles/new');
    const fixture = TestBed.createComponent(ArticleCreatePage);
    fixture.detectChanges();
    TestBed.inject(HttpTestingController).expectOne('/api/v1/public/tags?size=100').flush([]);
    const language = TestBed.inject(Language);
    expect(fixture.nativeElement.querySelector('#field-title').getAttribute('placeholder')).toBe(
      language.t.titlePlaceholder,
    );
    expect(fixture.nativeElement.querySelector('#field-content').getAttribute('placeholder')).toBe(
      language.t.contentPlaceholder,
    );
    fixture.componentInstance.form.controls.status.setValue('' as never);
    fixture.componentInstance.submit();
    fixture.detectChanges();
    expect(
      fixture.nativeElement.querySelector('#status-draft').getAttribute('aria-describedby'),
    ).toBe('field-status-error');
  });

  it('uses a native dialog for list deletion and returns focus to its trigger', async () => {
    await setup(ArticleListPage, 'articles');
    const fixture = TestBed.createComponent(ArticleListPage);
    fixture.componentInstance.auth.user = {
      id: 'admin',
      displayName: 'Admin',
      preferredLanguage: 'zh-TW',
      role: 'ADMIN',
    };
    fixture.detectChanges();
    const http = TestBed.inject(HttpTestingController);
    http
      .expectOne('/api/v1/articles?page=0')
      .flush({ content: [{ id: 'a1', title: 'Title' }], page: { totalPages: 1 } });
    fixture.detectChanges();
    const dialog = fixture.nativeElement.querySelector('dialog') as HTMLDialogElement;
    dialog.showModal = vi.fn();
    dialog.close = vi.fn();
    const trigger = fixture.nativeElement.querySelector(
      'button[aria-label*="Delete"]',
    ) as HTMLButtonElement;
    trigger.click();
    fixture.detectChanges();
    expect(dialog.showModal).toHaveBeenCalled();
    expect(dialog.textContent).toContain(
      fixture.componentInstance.language.t.confirmDelete.replace('{title}', 'Title'),
    );
    (dialog.querySelector('.ui-outline') as HTMLButtonElement).click();
    expect(document.activeElement).toBe(trigger);
  });

  it('restores and purges deleted articles', async () => {
    await setup(DeletedArticlesPage, 'articles/deleted');
    const fixture = TestBed.createComponent(DeletedArticlesPage);
    fixture.componentInstance.auth.user = {
      id: 'admin',
      displayName: 'Admin',
      preferredLanguage: 'zh-TW',
      role: 'ADMIN',
    };
    fixture.detectChanges();
    const http = TestBed.inject(HttpTestingController);
    http
      .expectOne('/api/v1/articles/deleted?page=0')
      .flush({ content: [{ id: 'a1', title: 'Deleted' }], page: { totalPages: 1 } });
    fixture.componentInstance.restore(fixture.componentInstance.items[0]);
    http.expectOne('/api/v1/articles/a1/restore').flush({});
    http
      .expectOne('/api/v1/articles/deleted?page=0')
      .flush({ content: [], page: { totalPages: 0 } });
    fixture.componentInstance.purge({ id: 'a2', title: 'Gone' } as never);
    fixture.componentInstance.confirmModal();
    http.expectOne('/api/v1/articles/deleted/a2').flush(null);
    http
      .expectOne('/api/v1/articles/deleted?page=0')
      .flush({ content: [], page: { totalPages: 0 } });
  });

  it('uses a native dialog for permanent deletion and returns focus', async () => {
    await setup(DeletedArticlesPage, 'articles/deleted');
    const fixture = TestBed.createComponent(DeletedArticlesPage);
    fixture.componentInstance.auth.user = {
      id: 'admin',
      displayName: 'Admin',
      preferredLanguage: 'zh-TW',
      role: 'ADMIN',
    };
    fixture.detectChanges();
    const http = TestBed.inject(HttpTestingController);
    http
      .expectOne('/api/v1/articles/deleted?page=0')
      .flush({ content: [{ id: 'a1', title: 'Gone' }], page: { totalPages: 1 } });
    fixture.detectChanges();
    const dialog = fixture.nativeElement.querySelector('dialog') as HTMLDialogElement;
    dialog.showModal = vi.fn();
    dialog.close = vi.fn();
    const trigger = fixture.nativeElement.querySelector('button.danger') as HTMLButtonElement;
    trigger.click();
    fixture.detectChanges();
    expect(dialog.showModal).toHaveBeenCalled();
    expect(dialog.textContent).toContain(
      fixture.componentInstance.language.t.confirmPermanentDelete.replace('{title}', 'Gone'),
    );
    (dialog.querySelector('.ui-outline') as HTMLButtonElement).click();
    expect(document.activeElement).toBe(trigger);
  });

  it('paginates deleted articles and preserves the retention notice', async () => {
    await setup(DeletedArticlesPage, 'articles/deleted');
    const fixture = TestBed.createComponent(DeletedArticlesPage);
    fixture.detectChanges();
    const http = TestBed.inject(HttpTestingController);
    http
      .expectOne('/api/v1/articles/deleted?page=0')
      .flush({ content: [{ id: 'a1', title: 'Deleted' }], page: { totalPages: 3 } });
    const page = fixture.componentInstance as DeletedArticlesPage & {
      totalPages: number;
      nextPage(): void;
      goToPage(page: number): void;
      previousPage(): void;
    };
    expect(page.totalPages).toBe(3);
    expect(fixture.nativeElement.textContent).toContain(
      fixture.componentInstance.language.t.deletedNotice,
    );
    page.nextPage();
    http
      .expectOne('/api/v1/articles/deleted?page=1')
      .flush({ content: [], page: { totalPages: 3 } });
    page.goToPage(2);
    http
      .expectOne('/api/v1/articles/deleted?page=2')
      .flush({ content: [], page: { totalPages: 3 } });
    page.previousPage();
    http
      .expectOne('/api/v1/articles/deleted?page=1')
      .flush({ content: [], page: { totalPages: 3 } });
  });

  it.each([401, 403, 409])('maps article editor load status %s', async (status) => {
    await setup(ArticleEditPage, 'articles/:id/edit', 'article-1');
    const fixture = TestBed.createComponent(ArticleEditPage);
    fixture.detectChanges();
    const http = TestBed.inject(HttpTestingController);
    http.expectOne('/api/v1/public/tags?size=100').flush([]);
    http.expectOne('/api/v1/articles/article-1').flush('error', { status, statusText: 'error' });
    fixture.detectChanges();
    const language = TestBed.inject(Language);
    const expected =
      status === 401
        ? language.t.unauthorized
        : status === 403
          ? language.t.forbidden
          : language.t.conflict;
    expect(fixture.componentInstance.error).toBe(expected);
  });

  it.each([401, 403, 409])('maps article editor submit status %s', async (status) => {
    await setup(ArticleCreatePage, 'articles/new');
    const fixture = TestBed.createComponent(ArticleCreatePage);
    fixture.detectChanges();
    const http = TestBed.inject(HttpTestingController);
    http.expectOne('/api/v1/public/tags?size=100').flush([]);
    fixture.componentInstance.form.patchValue({ title: 'Title', content: 'Content' });
    fixture.componentInstance.submit();
    http.expectOne('/api/v1/articles').flush('error', { status, statusText: 'error' });
    fixture.detectChanges();
    const language = TestBed.inject(Language);
    const expected =
      status === 401
        ? language.t.unauthorized
        : status === 403
          ? language.t.forbidden
          : language.t.conflict;
    expect(fixture.componentInstance.error).toBe(expected);
  });

  it('guards only dirty editor forms', () => {
    const confirm = vi.spyOn(window, 'confirm').mockReturnValue(false);
    expect(
      canLeaveArticle(
        { form: { dirty: false } } as never,
        undefined as never,
        undefined as never,
        undefined as never,
      ),
    ).toBe(true);
    expect(
      canLeaveArticle(
        { form: { dirty: true } } as never,
        undefined as never,
        undefined as never,
        undefined as never,
      ),
    ).toBe(false);
    expect(confirm).toHaveBeenCalledOnce();
  });
});
