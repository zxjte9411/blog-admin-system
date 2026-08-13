import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting, HttpTestingController } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { ActivatedRoute, provideRouter } from '@angular/router';
import { AdminPage, canLeaveArticle } from './admin-page';

describe('AdminPage', () => {
  afterEach(() => vi.restoreAllMocks());

  it('allows leaving a clean article form without confirmation', () => {
    const component = { form: { dirty: false } } as AdminPage;
    const confirm = vi.spyOn(window, 'confirm');

    expect(
      canLeaveArticle(component, undefined as never, undefined as never, undefined as never),
    ).toBe(true);
    expect(confirm).not.toHaveBeenCalled();
  });

  it.each([true, false])('uses native confirmation result for a dirty form: %s', (answer) => {
    const component = { form: { dirty: true } } as AdminPage;
    const confirm = vi.spyOn(window, 'confirm').mockReturnValue(answer);

    expect(
      canLeaveArticle(component, undefined as never, undefined as never, undefined as never),
    ).toBe(answer);
    expect(confirm).toHaveBeenCalledOnce();
  });

  function setup(routeKey: string, id: string | null = null) {
    return TestBed.configureTestingModule({
      imports: [AdminPage],
      providers: [
        provideRouter([]),
        provideHttpClient(),
        provideHttpClientTesting(),
        {
          provide: ActivatedRoute,
          useValue: {
            snapshot: {
              routeConfig: { path: routeKey },
              paramMap: { get: (key: string) => (key === 'id' ? id : null) },
              queryParamMap: { get: () => null },
            },
          },
        },
      ],
    }).compileComponents();
  }

  it('prefills an article editor from its existing article', async () => {
    await setup('articles/:id/edit', 'article-1');
    const fixture = TestBed.createComponent(AdminPage);
    fixture.detectChanges();

    TestBed.inject(HttpTestingController)
      .expectOne('/api/v1/articles/article-1')
      .flush({
        id: 'article-1',
        title: 'Existing title',
        content: 'Existing content',
        status: 'PUBLISHED',
        tagIds: ['tag-1', 'tag-2'],
        version: 3,
      });
    fixture.detectChanges();

    expect((fixture.nativeElement.querySelector('#title') as HTMLInputElement).value).toBe(
      'Existing title',
    );
    expect(fixture.nativeElement.querySelector('.preserved-tags')?.textContent).toContain('tag-1');
    expect(fixture.nativeElement.querySelector('.preserved-tags')?.textContent).toContain('tag-2');
  });

  it('renders article status as selectable radio options', async () => {
    await setup('articles/new');
    const fixture = TestBed.createComponent(AdminPage);
    fixture.detectChanges();

    const radios = fixture.nativeElement.querySelectorAll(
      'input[type="radio"][name="status"]',
    ) as NodeListOf<HTMLInputElement>;

    expect(radios).toHaveLength(2);
    expect([...radios].map((radio) => radio.value)).toEqual(['DRAFT', 'PUBLISHED']);

    const publishedLabel = fixture.nativeElement.querySelector(
      'label.status-option:nth-of-type(2)',
    ) as HTMLLabelElement;
    publishedLabel.click();

    expect(
      (fixture.nativeElement.querySelector('input[name="status"]:checked') as HTMLInputElement)
        .value,
    ).toBe('PUBLISHED');
    const publishedRadio = fixture.nativeElement.querySelector(
      'input[name="status"][value="PUBLISHED"]',
    ) as HTMLInputElement;
    publishedRadio.focus();
    expect(document.activeElement).toBe(publishedRadio);
    publishedRadio.dispatchEvent(new KeyboardEvent('keydown', { key: ' ', bubbles: true }));
    expect(publishedRadio.checked).toBe(true);
  });

  it('renders new article controls in the PRD order', async () => {
    await setup('articles/new');
    const fixture = TestBed.createComponent(AdminPage);
    fixture.detectChanges();

    const form = fixture.nativeElement.querySelector('.article-form') as HTMLFormElement;
    const controls = [...form.children]
      .map((element: Element) => ({
        name: element.matches('.status-field') ? 'status' : element.querySelector('.control')?.id,
        order: Number(getComputedStyle(element).order),
      }))
      .sort((left, right) => left.order - right.order)
      .map(({ name }) => name)
      .filter(Boolean);

    expect(controls).toEqual(['title', 'content', 'tagNames', 'status']);
  });

  it('sends the article editor payload with enum status and remaining tags', async () => {
    await setup('articles/:id/edit', 'article-1');
    const fixture = TestBed.createComponent(AdminPage);
    fixture.detectChanges();
    const http = TestBed.inject(HttpTestingController);
    http.expectOne('/api/v1/articles/article-1').flush({
      owner: 'author-1',
      title: 'Title',
      content: 'Content',
      status: 'DRAFT',
      tagIds: ['tag-1', 'tag-2'],
      version: 3,
    });
    fixture.componentInstance.removeTag('tag-1');
    fixture.componentInstance.submit();

    const request = http.expectOne('/api/v1/articles/article-1');
    expect(request.request.method).toBe('PUT');
    expect(request.request.body).toEqual(
      expect.objectContaining({ status: 'DRAFT', tagIds: ['tag-2'], version: 3 }),
    );
  });

  it('allows an author to edit their own article and rejects another owner', async () => {
    await setup('articles/:id/edit', 'article-1');
    const fixture = TestBed.createComponent(AdminPage);
    const page = fixture.componentInstance;
    page.auth.user = {
      id: 'author-1',
      displayName: 'Ada',
      preferredLanguage: 'en',
      role: 'AUTHOR',
    };
    fixture.detectChanges();

    const http = TestBed.inject(HttpTestingController);
    http.expectOne('/api/v1/articles/article-1').flush({
      owner: 'author-1',
      title: 'Owned article',
      content: 'Content',
      status: 'DRAFT',
      tagIds: [],
      version: 1,
    });
    fixture.detectChanges();
    expect(page.editorAllowed).toBe(true);
    expect(page.error).toBe('');
    expect(fixture.nativeElement.querySelector('[role="alert"]')).toBeNull();

    fixture.destroy();
    const otherFixture = TestBed.createComponent(AdminPage);
    otherFixture.componentInstance.auth.user = { ...page.auth.user };
    otherFixture.detectChanges();
    http.expectOne('/api/v1/articles/article-1').flush({ owner: 'author-2' });
    otherFixture.detectChanges();

    expect(otherFixture.componentInstance.editorAllowed).toBe(false);
    expect(otherFixture.nativeElement.querySelector('[role="alert"]')).toBeTruthy();
  });

  it('only deletes an article after confirmation', async () => {
    await setup('articles');
    const page = TestBed.createComponent(AdminPage).componentInstance;
    vi.spyOn(page, 'confirmDelete').mockReturnValue(false);
    page.deleteArticle({ id: 'article-1', title: 'Title' });
    TestBed.inject(HttpTestingController).expectNone('/api/v1/articles/article-1');
  });

  it('searches from the first page and requests the next result page', async () => {
    await setup('articles');
    const fixture = TestBed.createComponent(AdminPage);
    fixture.detectChanges();
    const http = TestBed.inject(HttpTestingController);
    http.expectOne('/api/v1/articles?page=0').flush({ content: [], totalPages: 2 });

    fixture.componentInstance.search('needle');
    http.expectOne('/api/v1/articles?page=0&title=needle').flush({ content: [], totalPages: 2 });
    fixture.componentInstance.nextPage();
    const next = http.expectOne('/api/v1/articles?page=1&title=needle');
    expect(next.request.params.get('page')).toBe('1');
  });

  it('renders the article management list for the administration route', async () => {
    await setup('articles');
    const fixture = TestBed.createComponent(AdminPage);
    fixture.detectChanges();

    const http = TestBed.inject(HttpTestingController);
    http.expectOne('/api/v1/articles?page=0').flush({ content: [], totalPages: 0 });
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('app-article-management-list')).toBeTruthy();
  });

  it('uses the administration API for deleted articles', async () => {
    await setup('articles/deleted');
    const fixture = TestBed.createComponent(AdminPage);
    fixture.componentInstance.routeKey = 'articles/deleted';
    fixture.detectChanges();

    TestBed.inject(HttpTestingController).expectOne('/api/v1/articles/deleted?page=0').flush([]);
  });

  it('allows article management only to its owner or an administrator', async () => {
    await setup('articles');
    const page = TestBed.createComponent(AdminPage).componentInstance;
    page.auth.user = {
      id: 'author-1',
      displayName: 'Ada',
      preferredLanguage: 'en',
      role: 'AUTHOR',
    };

    expect(page.canManageArticle({ owner: 'author-1' })).toBe(true);
    expect(page.canManageArticle({ owner: 'author-2' })).toBe(false);
    page.auth.user = { ...page.auth.user, role: 'ADMIN' };
    expect(page.canManageArticle({ ownerId: 'author-2' })).toBe(true);
  });

  it('renders password history audit fields', async () => {
    await setup('admin/settings/password');
    const fixture = TestBed.createComponent(AdminPage);
    fixture.detectChanges();

    TestBed.inject(HttpTestingController)
      .expectOne('/api/v1/admin/settings/password-minimum-length/history?page=0')
      .flush([
        {
          operatorId: 'operator-1',
          previousValue: 8,
          newValue: 12,
          changedAt: '2026-08-14T10:00:00Z',
        },
      ]);
    fixture.detectChanges();

    const table = fixture.nativeElement.querySelector('.password-history');
    expect(table.textContent).toContain('operator-1');
    expect(table.textContent).toContain('8');
    expect(table.textContent).toContain('12');
    expect(table.textContent).toContain('2026-08-14T10:00:00Z');
  });

  it('does not submit an invalid invitation email', async () => {
    await setup('admin/invitations');
    const fixture = TestBed.createComponent(AdminPage);
    const page = fixture.componentInstance;
    fixture.detectChanges();

    const http = TestBed.inject(HttpTestingController);
    http.expectOne('/api/v1/admin/invitations?page=0').flush([]);
    page.form.patchValue({ email: 'invalid-email' });
    page.submit();

    http.expectNone('/api/v1/admin/invitations');
  });

  it('renders admin users with role and enabled controls', async () => {
    await setup('admin/users');
    const fixture = TestBed.createComponent(AdminPage);
    fixture.detectChanges();

    const http = TestBed.inject(HttpTestingController);
    http
      .expectOne('/api/v1/admin/users?page=0')
      .flush([{ id: 'user-2', displayName: 'Mina', role: 'AUTHOR', enabled: true }]);
    fixture.detectChanges();

    expect(fixture.nativeElement.querySelector('table')).toBeTruthy();
    expect(fixture.nativeElement.querySelector('select[aria-label="Role: Mina"]')).toBeTruthy();
    expect(
      fixture.nativeElement.querySelector('input[type="checkbox"][aria-label="Enabled: Mina"]'),
    ).toBeTruthy();
  });

  it('sends an admin user role change without changing the endpoint contract', async () => {
    await setup('admin/users');
    const fixture = TestBed.createComponent(AdminPage);
    fixture.detectChanges();

    const http = TestBed.inject(HttpTestingController);
    http
      .expectOne('/api/v1/admin/users?page=0')
      .flush([{ id: 'user-2', displayName: 'Mina', role: 'AUTHOR', enabled: true }]);
    fixture.detectChanges();

    const select = fixture.nativeElement.querySelector(
      'select[aria-label="Role: Mina"]',
    ) as HTMLSelectElement;
    select.value = 'ADMIN';
    select.dispatchEvent(new Event('change'));
    fixture.nativeElement.querySelector('button[aria-label="Update Mina"]').click();

    const request = http.expectOne('/api/v1/admin/users/user-2');
    expect(request.request.method).toBe('PATCH');
    expect(request.request.body).toEqual({ role: 'ADMIN', enabled: true });
  });

  it('updates the administration title when the language changes', async () => {
    await setup('articles');
    const fixture = TestBed.createComponent(AdminPage);
    const page = fixture.componentInstance;
    page.routeKey = 'articles';
    fixture.detectChanges();
    TestBed.inject(HttpTestingController).expectOne('/api/v1/articles?page=0').flush([]);
    fixture.detectChanges();
    const englishTitle = fixture.nativeElement.querySelector('h1').textContent.trim();

    page.language.set('zh-TW');
    fixture.detectChanges();

    expect(fixture.nativeElement.querySelector('h1').textContent.trim()).not.toBe(englishTitle);
  });

  it('shows error with retry when article list fails to load', async () => {
    await setup('articles');
    const fixture = TestBed.createComponent(AdminPage);
    fixture.detectChanges();

    const http = TestBed.inject(HttpTestingController);
    http
      .expectOne('/api/v1/articles?page=0')
      .flush('Error', { status: 500, statusText: 'Server Error' });
    fixture.detectChanges();

    const alert = fixture.nativeElement.querySelector('[role="alert"]');
    expect(alert).toBeTruthy();
    const retryBtn = fixture.nativeElement.querySelector('.error button, button.retry-button');
    expect(retryBtn).toBeTruthy();

    retryBtn.click();
    http.expectOne('/api/v1/articles?page=0').flush({ content: [], totalPages: 0 });
  });

  it('shows deleted article notice in deleted articles view', async () => {
    await setup('articles/deleted');
    const fixture = TestBed.createComponent(AdminPage);
    fixture.componentInstance.routeKey = 'articles/deleted';
    fixture.detectChanges();

    TestBed.inject(HttpTestingController).expectOne('/api/v1/articles/deleted?page=0').flush([]);
    fixture.detectChanges();

    expect(fixture.nativeElement.textContent).toContain(
      fixture.componentInstance.language.t.deletedNotice,
    );
  });

  it('shows delete success with link to deleted articles', async () => {
    await setup('articles');
    const fixture = TestBed.createComponent(AdminPage);
    fixture.detectChanges();

    const http = TestBed.inject(HttpTestingController);
    http.expectOne('/api/v1/articles?page=0').flush([{ id: 'art-1', title: 'Test Article' }]);
    fixture.detectChanges();

    vi.spyOn(fixture.componentInstance, 'confirmDelete').mockReturnValue(true);
    fixture.componentInstance.deleteArticle({ id: 'art-1', title: 'Test Article' });

    http.expectOne('/api/v1/articles/art-1').flush({});
    http.expectOne('/api/v1/articles?page=0').flush([]);
    fixture.detectChanges();

    const statusEl = fixture.nativeElement.querySelector('[role="status"]');
    expect(statusEl).toBeTruthy();
    expect(statusEl.textContent).toContain(
      fixture.componentInstance.language.t.deleteSuccess.replace('{title}', 'Test Article'),
    );
    const link = statusEl.querySelector(
      'a[routerLink="/articles/deleted"], a[href="/articles/deleted"]',
    );
    expect(link).toBeTruthy();
  });

  it('confirms before disabling a user', async () => {
    await setup('admin/users');
    const fixture = TestBed.createComponent(AdminPage);
    fixture.detectChanges();

    const http = TestBed.inject(HttpTestingController);
    http
      .expectOne('/api/v1/admin/users?page=0')
      .flush([{ id: 'user-1', displayName: 'Mina', role: 'AUTHOR', enabled: true }]);
    fixture.detectChanges();

    const confirmSpy = vi.spyOn(window, 'confirm').mockReturnValue(false);
    fixture.componentInstance.toggleUserEnabled(fixture.componentInstance.items[0]);

    expect(confirmSpy).toHaveBeenCalledOnce();
    http.expectNone('/api/v1/admin/users/user-1');
  });

  it('reverts user role on update failure', async () => {
    await setup('admin/users');
    const fixture = TestBed.createComponent(AdminPage);
    fixture.detectChanges();

    const http = TestBed.inject(HttpTestingController);
    http
      .expectOne('/api/v1/admin/users?page=0')
      .flush([{ id: 'user-1', displayName: 'Mina', role: 'AUTHOR', enabled: true }]);
    fixture.detectChanges();

    fixture.componentInstance.updateUserRole({
      row: fixture.componentInstance.items[0],
      value: 'ADMIN',
    });
    fixture.componentInstance.updateUser(fixture.componentInstance.items[0]);

    http
      .expectOne('/api/v1/admin/users/user-1')
      .flush('Error', { status: 500, statusText: 'Server Error' });
    fixture.detectChanges();

    expect(fixture.componentInstance.items[0]['role']).toBe('AUTHOR');
  });

  it('shows error with retry when user list fails to load', async () => {
    await setup('admin/users');
    const fixture = TestBed.createComponent(AdminPage);
    fixture.detectChanges();

    const http = TestBed.inject(HttpTestingController);
    http
      .expectOne('/api/v1/admin/users?page=0')
      .flush('Error', { status: 500, statusText: 'Server Error' });
    fixture.detectChanges();

    const alert = fixture.nativeElement.querySelector('[role="alert"]');
    expect(alert).toBeTruthy();
    const retryBtn = fixture.nativeElement.querySelector('.error button, button.retry-button');
    expect(retryBtn).toBeTruthy();

    retryBtn.click();
    http.expectOne('/api/v1/admin/users?page=0').flush({ content: [], totalPages: 0 });
  });

  it('displays current password minimum length alongside history', async () => {
    await setup('admin/settings/password');
    const fixture = TestBed.createComponent(AdminPage);
    fixture.detectChanges();

    const http = TestBed.inject(HttpTestingController);
    http.expectOne('/api/v1/admin/settings/password-minimum-length').flush({ value: 12 });
    http.expectOne('/api/v1/admin/settings/password-minimum-length/history?page=0').flush([]);
    fixture.detectChanges();

    expect(fixture.nativeElement.textContent).toContain('12');
  });

  it('shows restore but not permanent delete button to author on deleted articles', async () => {
    await setup('articles/deleted');
    const fixture = TestBed.createComponent(AdminPage);
    fixture.componentInstance.auth.user = {
      id: 'author-1',
      displayName: 'Author',
      preferredLanguage: 'zh-TW',
      role: 'AUTHOR',
    };
    fixture.detectChanges();

    const http = TestBed.inject(HttpTestingController);
    http
      .expectOne('/api/v1/articles/deleted?page=0')
      .flush([{ id: 'art-1', title: 'Deleted Post' }]);
    fixture.detectChanges();

    const buttons = fixture.nativeElement.querySelectorAll('td button');
    expect(buttons).toHaveLength(1);
    expect(buttons[0].textContent.trim()).toBe(fixture.componentInstance.language.t.restore);
  });

  it('allows admin to open confirmation dialog and permanently delete a deleted article', async () => {
    await setup('articles/deleted');
    const fixture = TestBed.createComponent(AdminPage);
    fixture.componentInstance.auth.user = {
      id: 'admin-1',
      displayName: 'Admin',
      preferredLanguage: 'zh-TW',
      role: 'ADMIN',
    };
    fixture.detectChanges();

    const http = TestBed.inject(HttpTestingController);
    http
      .expectOne('/api/v1/articles/deleted?page=0')
      .flush([{ id: 'art-1', title: 'Deleted Post' }]);
    fixture.detectChanges();

    const deleteBtn = fixture.nativeElement.querySelector('button.danger');
    expect(deleteBtn).toBeTruthy();
    expect(deleteBtn.textContent.trim()).toBe(fixture.componentInstance.language.t.permanentDelete);

    const dialog = fixture.nativeElement.querySelector('dialog');
    if (!dialog.showModal) dialog.showModal = vi.fn();
    if (!dialog.close) dialog.close = vi.fn();
    const showModalSpy = vi.spyOn(dialog, 'showModal');

    deleteBtn.click();
    fixture.detectChanges();

    expect(showModalSpy).toHaveBeenCalled();
    expect(dialog.textContent).toContain(
      fixture.componentInstance.language.t.confirmPermanentDelete.replace(
        '{title}',
        'Deleted Post',
      ),
    );

    // Cancel closes dialog without deleting
    const cancelBtn = dialog.querySelector('button.ui-outline');
    cancelBtn.click();
    fixture.detectChanges();
    http.expectNone('/api/v1/articles/deleted/art-1');

    // Open again and confirm
    deleteBtn.click();
    fixture.detectChanges();

    const confirmBtn = dialog.querySelector('button.danger-btn');
    confirmBtn.click();

    const deleteReq = http.expectOne('/api/v1/articles/deleted/art-1');
    expect(deleteReq.request.method).toBe('DELETE');
    deleteReq.flush(null, { status: 204, statusText: 'No Content' });

    http.expectOne('/api/v1/articles/deleted?page=0').flush([]);
    fixture.detectChanges();

    expect(fixture.nativeElement.textContent).toContain(
      fixture.componentInstance.language.t.permanentDeleteSuccess.replace(
        '{title}',
        'Deleted Post',
      ),
    );
  });

  it('handles permanent delete failure by displaying error', async () => {
    await setup('articles/deleted');
    const fixture = TestBed.createComponent(AdminPage);
    fixture.componentInstance.auth.user = {
      id: 'admin-1',
      displayName: 'Admin',
      preferredLanguage: 'zh-TW',
      role: 'ADMIN',
    };
    fixture.detectChanges();

    const http = TestBed.inject(HttpTestingController);
    http
      .expectOne('/api/v1/articles/deleted?page=0')
      .flush([{ id: 'art-1', title: 'Deleted Post' }]);
    fixture.detectChanges();

    const deleteBtn = fixture.nativeElement.querySelector('button.danger');
    const dialog = fixture.nativeElement.querySelector('dialog');
    if (!dialog.showModal) dialog.showModal = vi.fn();
    if (!dialog.close) dialog.close = vi.fn();

    deleteBtn.click();
    fixture.detectChanges();

    const confirmBtn = dialog.querySelector('button.danger-btn');
    confirmBtn.click();

    http
      .expectOne('/api/v1/articles/deleted/art-1')
      .flush('Forbidden', { status: 403, statusText: 'Forbidden' });
    fixture.detectChanges();

    expect(fixture.nativeElement.querySelector('.error')).toBeTruthy();
  });
});
