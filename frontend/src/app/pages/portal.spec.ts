import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting, HttpTestingController } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { ActivatedRoute, provideRouter } from '@angular/router';
import { FormBuilder, FormControl } from '@angular/forms';
import { Auth } from '../core/auth';
import { Portal } from './portal';

describe('Portal API requests', () => {
  it('recreates the refresh request after the first refresh completes', async () => {
    await TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    }).compileComponents();

    const auth = TestBed.inject(Auth);
    const http = TestBed.inject(HttpTestingController);

    auth.refresh().subscribe();
    http.expectOne('/api/v1/auth/refresh').flush({ accessToken: 'first' });
    auth.refresh().subscribe();

    const second = http.expectOne('/api/v1/auth/refresh');
    expect(second.request.method).toBe('POST');
    second.flush({ accessToken: 'second' });
  });

  it('builds login email validation through ngOnInit and make', async () => {
    await TestBed.configureTestingModule({
      imports: [Portal],
      providers: [
        provideRouter([]),
        provideHttpClient(),
        provideHttpClientTesting(),
        {
          provide: ActivatedRoute,
          useValue: {
            snapshot: {
              routeConfig: { path: 'login' },
              queryParamMap: { get: () => null },
            },
          },
        },
      ],
    }).compileComponents();

    const fixture = TestBed.createComponent(Portal);
    const component = fixture.componentInstance;
    component.routeKey = 'login';
    fixture.detectChanges();
    component.form.patchValue({ email: 'not-an-email', password: 'secret' });

    expect(component.form.get('email')?.hasError('email')).toBe(true);
  });

  it('preserves edit tag ids and sends an empty list only when cleared', async () => {
    await TestBed.configureTestingModule({
      imports: [Portal],
      providers: [
        provideRouter([]),
        provideHttpClient(),
        provideHttpClientTesting(),
        {
          provide: ActivatedRoute,
          useValue: {
            snapshot: {
              routeConfig: { path: 'admin/articles/:id' },
              paramMap: { get: (name: string) => (name === 'id' ? 'article-1' : null) },
              queryParamMap: { get: () => null },
            },
          },
        },
      ],
    }).compileComponents();

    const fixture = TestBed.createComponent(Portal);
    const component = fixture.componentInstance;
    component.routeKey = 'admin/articles/:id';
    component.form = TestBed.inject(FormBuilder).group({
      title: 'Title',
      content: 'Content',
      status: 'draft',
      tagNames: 'tag-1',
      tagIds: new FormControl(['tag-1']),
      version: 2,
    }) as unknown as typeof component.form;
    component.preservedTagIds = ['tag-1'];
    component.submit();

    const keep = TestBed.inject(HttpTestingController).expectOne('/api/v1/articles/article-1');
    expect(keep.request.body.tagIds).toEqual(['tag-1']);
    keep.flush({});

    component.form.patchValue({ tagNames: '', tagIds: [] });
    component.submit();
    const clear = TestBed.inject(HttpTestingController).expectOne('/api/v1/articles/article-1');
    expect(clear.request.body.tagIds).toEqual([]);
    clear.flush({});
  });

  it('loads invitation rows while the invitation form is visible', async () => {
    await TestBed.configureTestingModule({
      imports: [Portal],
      providers: [
        provideRouter([]),
        provideHttpClient(),
        provideHttpClientTesting(),
        {
          provide: ActivatedRoute,
          useValue: {
            snapshot: {
              routeConfig: { path: 'admin/invitations' },
              paramMap: { get: () => null },
              queryParamMap: { get: () => null },
            },
          },
        },
      ],
    }).compileComponents();

    const fixture = TestBed.createComponent(Portal);
    fixture.detectChanges();
    const request = TestBed.inject(HttpTestingController).expectOne(
      '/api/v1/admin/invitations?page=0',
    );
    request.flush([{ email: 'invite@example.com' }]);
    fixture.detectChanges();
    expect(fixture.nativeElement.textContent).toContain('invite@example.com');
  });

  it('renders article title, author attribution, and created time', async () => {
    await TestBed.configureTestingModule({
      imports: [Portal],
      providers: [
        provideRouter([]),
        provideHttpClient(),
        provideHttpClientTesting(),
        {
          provide: ActivatedRoute,
          useValue: {
            snapshot: { routeConfig: { path: 'admin/articles' }, paramMap: { get: () => null } },
          },
        },
      ],
    }).compileComponents();

    const fixture = TestBed.createComponent(Portal);
    const component = fixture.componentInstance;
    TestBed.inject((await import('../core/language')).Language).usePreferred('zh-TW');
    component.routeKey = 'admin/articles';

    fixture.detectChanges();

    const request = TestBed.inject(HttpTestingController).expectOne(
      (pending) => pending.url === '/api/v1/articles',
    );
    request.flush({
      content: [
        {
          title: 'Published title',
          authorAttribution: 'By Ada',
          createdAt: '2026-08-13T10:30:00Z',
        },
      ],
      page: { totalPages: 1 },
    });
    fixture.detectChanges();

    expect(fixture.nativeElement.textContent).toContain('Published title');
    expect(fixture.nativeElement.textContent).toContain('By Ada');
    expect(fixture.nativeElement.textContent).toContain('2026-08-13');
  });

  it('shows article edit and delete only to its owner or an admin', async () => {
    await TestBed.configureTestingModule({
      imports: [Portal],
      providers: [
        provideRouter([]),
        provideHttpClient(),
        provideHttpClientTesting(),
        {
          provide: ActivatedRoute,
          useValue: {
            snapshot: { routeConfig: { path: 'admin/articles' }, paramMap: { get: () => null } },
          },
        },
      ],
    }).compileComponents();

    const fixture = TestBed.createComponent(Portal);
    const component = fixture.componentInstance;
    TestBed.inject((await import('../core/language')).Language).usePreferred('zh-TW');
    component.routeKey = 'admin/articles';
    component.items = [
      { id: 'owned', title: 'Owned', ownerId: 'user-1' },
      { id: 'other', title: 'Other', ownerId: 'user-2' },
    ];
    component.auth.user = {
      id: 'user-1',
      displayName: 'Ada',
      preferredLanguage: 'zh-TW',
      role: 'AUTHOR',
    };
    fixture.detectChanges();

    const rows = [...fixture.nativeElement.querySelectorAll('tr.mat-mdc-row')];
    expect(rows[0].textContent).toContain('Owned');
    expect(rows[0].querySelector('button:last-child')?.textContent).toContain('刪除');
    expect(rows[1].querySelector('button:last-child')).toBeNull();

    fixture.destroy();
    const adminFixture = TestBed.createComponent(Portal);
    adminFixture.componentInstance.routeKey = 'admin/articles';
    adminFixture.componentInstance.items = component.items;
    adminFixture.componentInstance.auth.user = { ...component.auth.user!, role: 'ADMIN' };
    adminFixture.detectChanges();
    expect(
      adminFixture.nativeElement
        .querySelectorAll('tr.mat-mdc-row')[1]
        .querySelector('button:last-child')?.textContent,
    ).toContain('刪除');
  });

  it('routes public article links to the public detail page', async () => {
    await TestBed.configureTestingModule({
      imports: [Portal],
      providers: [
        provideRouter([]),
        provideHttpClient(),
        provideHttpClientTesting(),
        {
          provide: ActivatedRoute,
          useValue: {
            snapshot: {
              routeConfig: { path: 'public/articles' },
              paramMap: { get: () => 'article-1' },
              queryParamMap: { get: () => null },
            },
          },
        },
      ],
    }).compileComponents();

    const fixture = TestBed.createComponent(Portal);
    const component = fixture.componentInstance;
    component.routeKey = 'public/articles';
    component.items = [{ id: 'article-1', title: 'Public title', content: 'Story' }];
    fixture.detectChanges();

    const link = fixture.nativeElement.querySelector('a[href="/public/articles/article-1"]');
    expect(link?.textContent).toContain('Public title');
    expect(fixture.nativeElement.querySelectorAll('button').length).toBe(4);
  });

  it('renders public article details from its HTTP response', async () => {
    await TestBed.configureTestingModule({
      imports: [Portal],
      providers: [
        provideRouter([]),
        provideHttpClient(),
        provideHttpClientTesting(),
        {
          provide: ActivatedRoute,
          useValue: {
            snapshot: {
              routeConfig: { path: 'public/articles/:id' },
              paramMap: { get: () => 'article-1' },
              queryParamMap: { get: () => null },
            },
          },
        },
      ],
    }).compileComponents();

    const fixture = TestBed.createComponent(Portal);
    const component = fixture.componentInstance;
    component.routeKey = 'public/articles/:id';
    fixture.detectChanges();
    TestBed.inject(HttpTestingController)
      .expectOne('/api/v1/public/articles/article-1')
      .flush({ id: 'article-1', title: 'Public title', content: 'Story' });
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('article')?.textContent).toContain('Story');
    expect(fixture.nativeElement.querySelector('button[aria-label="Delete"]')).toBeNull();
  });

  it('paginates public articles and tags with the page parameter', async () => {
    await TestBed.configureTestingModule({
      imports: [Portal],
      providers: [
        provideRouter([]),
        provideHttpClient(),
        provideHttpClientTesting(),
        {
          provide: ActivatedRoute,
          useValue: {
            snapshot: {
              routeConfig: { path: 'admin/articles/:id' },
              paramMap: { get: () => 'article-1' },
              queryParamMap: { get: () => null },
            },
          },
        },
      ],
    }).compileComponents();

    const http = TestBed.inject(HttpTestingController);
    TestBed.inject((await import('../core/language')).Language).usePreferred('zh-TW');
    for (const route of ['public/articles', 'public/tags']) {
      const fixture = TestBed.createComponent(Portal);
      const component = fixture.componentInstance;
      component.routeKey = route;
      fixture.detectChanges();
      http
        .expectOne(
          `${route === 'public/articles' ? '/api/v1/public/articles' : '/api/v1/public/tags'}?page=0`,
        )
        .flush({ content: [{ id: 'one', title: 'One' }], page: { totalPages: 2 } });
      fixture.detectChanges();
      const next = [...fixture.nativeElement.querySelectorAll('button')].find((button) =>
        button.textContent.includes('下一頁'),
      );
      expect(next).toBeTruthy();
      next.click();
      http.expectOne(
        `${route === 'public/articles' ? '/api/v1/public/articles' : '/api/v1/public/tags'}?page=1`,
      );
      fixture.destroy();
    }
  });

  it('sends article status as the backend enum and preserves tag ids', async () => {
    await TestBed.configureTestingModule({
      imports: [Portal],
      providers: [
        provideRouter([{ path: 'admin/articles', component: Portal }]),
        provideHttpClient(),
        provideHttpClientTesting(),
      ],
    }).compileComponents();

    const fixture = TestBed.createComponent(Portal);
    const component = fixture.componentInstance;
    component.routeKey = 'admin/articles/new';
    component.form = TestBed.inject(FormBuilder).group({
      title: 'Title',
      content: 'Content',
      status: 'published',
      tagNames: 'one, two',
      tagIds: new FormControl(['tag-1']),
      version: 4,
    }) as unknown as typeof component.form;
    component.submit();

    const request = TestBed.inject(HttpTestingController).expectOne('/api/v1/articles');
    expect(request.request.body).toEqual(
      expect.objectContaining({
        status: 'PUBLISHED',
        tagIds: ['tag-1'],
      }),
    );
    request.flush({});
  });

  it('sends the real password minimum DTO field', async () => {
    await TestBed.configureTestingModule({
      imports: [Portal],
      providers: [provideRouter([]), provideHttpClient(), provideHttpClientTesting()],
    }).compileComponents();

    const fixture = TestBed.createComponent(Portal);
    const component = fixture.componentInstance;
    TestBed.inject((await import('../core/language')).Language).usePreferred('en');
    component.routeKey = 'admin/settings/password';
    component.form = TestBed.inject(FormBuilder).group({
      value: 12,
    }) as unknown as typeof component.form;
    component.submit();

    const request = TestBed.inject(HttpTestingController).expectOne(
      '/api/v1/admin/settings/password-minimum-length',
    );
    expect(request.request.body).toEqual({ value: 12 });
    request.flush({ value: 12 });
  });

  it('does not read public articles for an unknown route', async () => {
    await TestBed.configureTestingModule({
      imports: [Portal],
      providers: [provideRouter([]), provideHttpClient(), provideHttpClientTesting()],
    }).compileComponents();

    const fixture = TestBed.createComponent(Portal);
    const component = fixture.componentInstance;
    component.routeKey = '**';
    fixture.detectChanges();

    expect(component.error).toBe('Page not found');
    TestBed.inject(HttpTestingController).expectNone('/api/v1/public/articles');
  });

  it('rejects an invalid login email before sending a request', async () => {
    await TestBed.configureTestingModule({
      imports: [Portal],
      providers: [provideRouter([]), provideHttpClient(), provideHttpClientTesting()],
    }).compileComponents();

    const fixture = TestBed.createComponent(Portal);
    const component = fixture.componentInstance;
    component.routeKey = 'login';
    component.ngOnInit();
    component.form.patchValue({ email: 'not-an-email', password: 'secret' });

    component.submit();

    expect(component.form.controls['email'].invalid).toBe(true);
    TestBed.inject(HttpTestingController).expectNone('/api/v1/auth/login');
  });

  it('loads an article editor with GET and prefilled values', async () => {
    await TestBed.configureTestingModule({
      imports: [Portal],
      providers: [
        provideRouter([]),
        provideHttpClient(),
        provideHttpClientTesting(),
        {
          provide: ActivatedRoute,
          useValue: {
            snapshot: {
              routeConfig: { path: 'admin/articles/:id' },
              paramMap: {
                get: (name: string) => (name === 'id' ? 'article-1' : null),
              },
              queryParamMap: { get: () => null },
            },
          },
        },
      ],
    }).compileComponents();

    const fixture = TestBed.createComponent(Portal);
    const component = fixture.componentInstance;
    component.routeKey = 'admin/articles/:id';
    component.ngOnInit();

    const request = TestBed.inject(HttpTestingController).expectOne('/api/v1/articles/article-1');
    expect(request.request.method).toBe('GET');
    request.flush({
      id: 'article-1',
      title: 'Existing title',
      content: 'Existing content',
      status: 'PUBLISHED',
      tagIds: ['tag-1', 'tag-2'],
      version: 3,
    });

    expect(component.form.get('title')?.value).toBe('Existing title');
    expect(component.form.get('tagNames')?.value).toBeUndefined();
    expect(component.preservedTagIds).toEqual(['tag-1', 'tag-2']);
    expect(component.form.valid).toBe(true);
    fixture.detectChanges();
    expect(fixture.nativeElement.textContent).toContain('tag-1');
    component.removeTag('tag-1');
    component.form.patchValue({
      title: 'Existing title',
      content: 'Existing content',
      status: 'PUBLISHED',
      version: 3,
    });
    component.submit();
    const update = TestBed.inject(HttpTestingController).expectOne(
      (request) => request.url === '/api/v1/articles/article-1' && request.method === 'PUT',
    );
    expect(update.request.method).toBe('PUT');
    expect(update.request.body.tagIds).toEqual(['tag-2']);
    update.flush({});
  });

  it('renders editable tag ids and status choices in the editor', async () => {
    await TestBed.configureTestingModule({
      imports: [Portal],
      providers: [provideRouter([]), provideHttpClient(), provideHttpClientTesting()],
    }).compileComponents();

    const fixture = TestBed.createComponent(Portal);
    const component = fixture.componentInstance;
    component.routeKey = 'admin/articles/:id';
    component.fields = ['title', 'content', 'tagNames', 'status', 'version'];
    component.form = TestBed.inject(FormBuilder).group({
      title: 'Title',
      content: 'Content',
      tagNames: 'angular',
      status: 'PUBLISHED',
      version: 1,
    }) as unknown as typeof component.form;
    component.preservedTagIds = ['tag-1', 'tag-2'];
    fixture.detectChanges();

    expect(fixture.nativeElement.textContent).toContain('tag-1');
    expect(fixture.nativeElement.textContent).toContain('PUBLISHED');
    component.removeTag('tag-1');
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('.tag-list')?.textContent).toContain('tag-2');
    expect(fixture.nativeElement.querySelector('.tag-list')?.textContent).not.toContain('tag-1');
  });

  it('only deletes an article after confirmation', async () => {
    await TestBed.configureTestingModule({
      imports: [Portal],
      providers: [provideRouter([]), provideHttpClient(), provideHttpClientTesting()],
    }).compileComponents();

    const component = TestBed.createComponent(Portal).componentInstance;
    component.confirmDelete = () => false;
    component.deleteArticle({ id: 'article-1' });

    TestBed.inject(HttpTestingController).expectNone('/api/v1/articles/article-1');
  });

  it('updates the account profile with PATCH at the account endpoint', async () => {
    await TestBed.configureTestingModule({
      imports: [Portal],
      providers: [
        provideRouter([{ path: 'account/profile', component: Portal }]),
        provideHttpClient(),
        provideHttpClientTesting(),
      ],
    }).compileComponents();

    const fixture = TestBed.createComponent(Portal);
    const component = fixture.componentInstance;
    component.routeKey = 'account/profile';
    component.form = TestBed.inject(FormBuilder).group({
      displayName: 'Ada',
      preferredLanguage: 'en',
    }) as unknown as typeof component.form;
    component.submit();

    const request = TestBed.inject(HttpTestingController).expectOne('/api/v1/account/profile');
    expect(request.request.method).toBe('PATCH');
    request.flush({ displayName: 'Ada', preferredLanguage: 'en' });
  });

  it('renders route-specific bilingual navigation labels and headings', async () => {
    await TestBed.configureTestingModule({
      imports: [Portal],
      providers: [provideRouter([]), provideHttpClient(), provideHttpClientTesting()],
    }).compileComponents();

    const fixture = TestBed.createComponent(Portal);
    const component = fixture.componentInstance;
    const language = TestBed.inject((await import('../core/language')).Language);
    language.usePreferred('en');
    component.routeKey = 'account/password';
    component.ngOnInit();
    fixture.detectChanges();

    expect(fixture.nativeElement.textContent).toContain('Password');
    expect(fixture.nativeElement.textContent).toContain('Tags');
    expect(component.navLabel('/public/tags')).toBe('Tags');
    expect(component.navLabel('/admin/articles/deleted')).toBe('Deleted articles');
    expect(component.navLabel('/admin/settings/password')).toBe('Password settings');
  });

  it('does not offer revoke for the current account session', async () => {
    await TestBed.configureTestingModule({
      imports: [Portal],
      providers: [provideRouter([]), provideHttpClient(), provideHttpClientTesting()],
    }).compileComponents();

    const fixture = TestBed.createComponent(Portal);
    const component = fixture.componentInstance;
    TestBed.inject((await import('../core/language')).Language).usePreferred('en');
    component.routeKey = 'account/sessions';
    component.items = [
      { id: 'current', current: true },
      { id: 'other', current: false },
    ];
    fixture.detectChanges();

    const buttons = [...fixture.nativeElement.querySelectorAll('button')].filter((button) =>
      button.textContent.includes('Revoke'),
    );
    expect(buttons).toHaveLength(1);
    expect(buttons[0].parentElement.textContent).toContain('other');
  });

  it('loads and renders session metadata without exposing refresh tokens', async () => {
    await TestBed.configureTestingModule({
      imports: [Portal],
      providers: [provideRouter([]), provideHttpClient(), provideHttpClientTesting()],
    }).compileComponents();

    const fixture = TestBed.createComponent(Portal);
    const component = fixture.componentInstance;
    TestBed.inject((await import('../core/language')).Language).usePreferred('zh-TW');
    component.routeKey = 'account/sessions';
    fixture.detectChanges();

    TestBed.inject(HttpTestingController)
      .expectOne('/api/v1/auth/sessions?page=0')
      .flush([
        {
          id: 'current',
          current: true,
          createdAt: '2026-08-12T09:00:00Z',
          lastUsedAt: '2026-08-13T10:00:00Z',
          refreshToken: 'must-not-render',
        },
        {
          id: 'other',
          current: false,
          createdAt: '2026-08-11T09:00:00Z',
          lastUsedAt: '2026-08-13T08:00:00Z',
        },
      ]);
    fixture.detectChanges();

    const text = fixture.nativeElement.textContent;
    expect(text).toContain('目前工作階段');
    expect(text).toContain('2026-08-12T09:00:00Z');
    expect(text).toContain('2026-08-13T10:00:00Z');
    expect(text).toContain('2026-08-11T09:00:00Z');
    expect(text).toContain('2026-08-13T08:00:00Z');
    expect(text).not.toContain('must-not-render');
    expect(
      [...fixture.nativeElement.querySelectorAll('button')].filter((button) =>
        button.textContent.includes('撤銷'),
      ),
    ).toHaveLength(1);
  });

  it('updates auth user before language save after profile success', async () => {
    await TestBed.configureTestingModule({
      imports: [Portal],
      providers: [provideRouter([]), provideHttpClient(), provideHttpClientTesting()],
    }).compileComponents();

    const fixture = TestBed.createComponent(Portal);
    const component = fixture.componentInstance;
    const auth = TestBed.inject(Auth);
    const language = TestBed.inject((await import('../core/language')).Language);
    auth.user = { id: 'user-1', displayName: 'Old', preferredLanguage: 'zh-TW', role: 'AUTHOR' };
    component.routeKey = 'account/profile';
    component.form = TestBed.inject(FormBuilder).group({
      displayName: 'New',
      preferredLanguage: 'en',
    }) as unknown as typeof component.form;
    component.submit();
    TestBed.inject(HttpTestingController)
      .expectOne('/api/v1/account/profile')
      .flush({ displayName: 'New', preferredLanguage: 'en' });

    language.set('zh-TW');
    expect(
      TestBed.inject(HttpTestingController).expectOne('/api/v1/account/profile').request.body,
    ).toEqual({
      displayName: 'New',
      preferredLanguage: 'zh-TW',
    });
  });

  it('renders password history backend fields from a real HTTP response', async () => {
    await TestBed.configureTestingModule({
      imports: [Portal],
      providers: [provideRouter([]), provideHttpClient(), provideHttpClientTesting()],
    }).compileComponents();

    const fixture = TestBed.createComponent(Portal);
    const component = fixture.componentInstance;
    component.routeKey = 'admin/settings/password';
    component.ngOnInit();
    const request = TestBed.inject(HttpTestingController).expectOne(
      '/api/v1/admin/settings/password-minimum-length/history?page=0',
    );
    request.flush([
      { operatorId: 'admin-1', previousValue: 10, newValue: 12, changedAt: '2026-08-13T10:00:00Z' },
    ]);
    fixture.detectChanges();

    expect(fixture.nativeElement.textContent).toContain('操作者');
    expect(fixture.nativeElement.textContent).toContain('10');
    expect(fixture.nativeElement.textContent).toContain('12');
    expect(fixture.nativeElement.textContent).toContain('2026-08-13');
  });

  it('requests the next Spring Page using its nested totalPages', async () => {
    await TestBed.configureTestingModule({
      imports: [Portal],
      providers: [provideRouter([]), provideHttpClient(), provideHttpClientTesting()],
    }).compileComponents();

    const fixture = TestBed.createComponent(Portal);
    const component = fixture.componentInstance;
    component.routeKey = 'admin/articles';
    fixture.detectChanges();
    const http = TestBed.inject(HttpTestingController);
    http
      .expectOne(
        (request) => request.url === '/api/v1/articles' && request.params.get('page') === '0',
      )
      .flush({ content: [{ title: 'first' }], page: { totalPages: 2 } });
    fixture.detectChanges();

    component.nextPage();
    const next = http.expectOne('/api/v1/articles?page=1');
    expect(next.request.params.get('page')).toBe('1');
    next.flush({ content: [{ title: 'second' }], page: { totalPages: 2 } });
  });

  it('labels account password and email navigation in both languages', async () => {
    await TestBed.configureTestingModule({
      imports: [Portal],
      providers: [provideRouter([]), provideHttpClient(), provideHttpClientTesting()],
    }).compileComponents();

    const component = TestBed.createComponent(Portal).componentInstance;
    const language = TestBed.inject((await import('../core/language')).Language);
    language.usePreferred('en');
    expect(component.navLabel('/account/password')).toBe('Password');
    expect(component.navLabel('/account/email')).toBe('Email');
    language.usePreferred('zh-TW');
    expect(component.navLabel('/account/password')).toBe('密碼');
    expect(component.navLabel('/account/email')).toBe('電子信箱');
  });

  it('uses bilingual headings for public auth flows', async () => {
    await TestBed.configureTestingModule({
      imports: [Portal],
      providers: [provideRouter([]), provideHttpClient(), provideHttpClientTesting()],
    }).compileComponents();

    const fixture = TestBed.createComponent(Portal);
    const component = fixture.componentInstance;
    const language = TestBed.inject((await import('../core/language')).Language);
    for (const [route, en, zh] of [
      ['register', 'Register', '註冊'],
      ['reset-password', 'Reset password', '重設密碼'],
      ['password-reset', 'Password reset', '申請重設密碼'],
    ]) {
      language.usePreferred('en');
      component.routeKey = route;
      component.ngOnInit();
      expect(component.heading).toBe(en);
      language.usePreferred('zh-TW');
      component.ngOnInit();
      expect(component.heading).toBe(zh);
    }
  });

  it('renders translated password history labels after switching language', async () => {
    await TestBed.configureTestingModule({
      imports: [Portal],
      providers: [provideRouter([]), provideHttpClient(), provideHttpClientTesting()],
    }).compileComponents();

    const fixture = TestBed.createComponent(Portal);
    const component = fixture.componentInstance;
    const language = TestBed.inject((await import('../core/language')).Language);
    language.usePreferred('en');
    component.routeKey = 'admin/settings/password';
    component.ngOnInit();
    TestBed.inject(HttpTestingController)
      .expectOne('/api/v1/admin/settings/password-minimum-length/history?page=0')
      .flush([{ operatorId: 'admin-1', previousValue: 10, newValue: 12, changedAt: '2026-08-13' }]);
    fixture.detectChanges();
    expect(fixture.nativeElement.textContent).not.toContain('operatorId:');
    expect(fixture.nativeElement.textContent).toContain('Operator');
    component.toggleLanguage();
    fixture.detectChanges();
    expect(fixture.nativeElement.textContent).toContain('操作者');
    expect(fixture.nativeElement.textContent).toContain('變更時間');
  });

  it('renders forbidden and conflict messages and switches rendered language', async () => {
    await TestBed.configureTestingModule({
      imports: [Portal],
      providers: [provideRouter([]), provideHttpClient(), provideHttpClientTesting()],
    }).compileComponents();

    const forbidden = TestBed.createComponent(Portal);
    forbidden.componentInstance.routeKey = 'forbidden';
    forbidden.componentInstance.ngOnInit();
    forbidden.detectChanges();
    expect(forbidden.nativeElement.textContent).toContain('您沒有權限');
    const language = TestBed.inject((await import('../core/language')).Language);
    language.usePreferred('zh-TW');
    forbidden.componentInstance.toggleLanguage();
    forbidden.detectChanges();
    expect(language.lang()).toBe('en');
    expect(forbidden.nativeElement.textContent).toContain(language.t.forbidden);

    const conflict = TestBed.createComponent(Portal);
    conflict.componentInstance.routeKey = 'admin/settings/password';
    conflict.componentInstance.form = TestBed.inject(FormBuilder).group({
      value: 12,
    }) as unknown as typeof conflict.componentInstance.form;
    conflict.componentInstance.submit();
    TestBed.inject(HttpTestingController)
      .expectOne('/api/v1/admin/settings/password-minimum-length')
      .flush({}, { status: 409, statusText: 'Conflict' });
    conflict.detectChanges();
    expect(conflict.nativeElement.textContent).toContain('409');
  });

  it('loads the current user before saving language from a public page', async () => {
    await TestBed.configureTestingModule({
      imports: [Portal],
      providers: [provideRouter([]), provideHttpClient(), provideHttpClientTesting()],
    }).compileComponents();

    const fixture = TestBed.createComponent(Portal);
    const component = fixture.componentInstance;
    const auth = TestBed.inject(Auth);
    auth.setToken('token');
    component.routeKey = 'public/articles';

    component.toggleLanguage();

    const http = TestBed.inject(HttpTestingController);
    const me = http.expectOne('/api/v1/account/me');
    me.flush({ id: 'user-1', displayName: 'Ada', preferredLanguage: 'en', role: 'AUTHOR' });
    const profile = http.expectOne('/api/v1/account/profile');
    expect(profile.request.method).toBe('PATCH');
    profile.flush({ preferredLanguage: 'en' });
    auth.clear();
    TestBed.inject((await import('../core/language')).Language).usePreferred('zh-TW');
    fixture.destroy();
  });

  it('uses the ArticleView owner field to allow an author to edit their article', async () => {
    await TestBed.configureTestingModule({
      imports: [Portal],
      providers: [
        provideRouter([]),
        provideHttpClient(),
        provideHttpClientTesting(),
        {
          provide: ActivatedRoute,
          useValue: {
            snapshot: {
              routeConfig: { path: 'admin/articles/:id' },
              paramMap: { get: () => 'article-1' },
              queryParamMap: { get: () => null },
            },
          },
        },
      ],
    }).compileComponents();
    const fixture = TestBed.createComponent(Portal);
    const component = fixture.componentInstance;
    component.routeKey = 'admin/articles/:id';
    component.auth.user = {
      id: 'user-1',
      displayName: 'Ada',
      preferredLanguage: 'en',
      role: 'AUTHOR',
    };
    component.ngOnInit();
    TestBed.inject(HttpTestingController)
      .expectOne('/api/v1/articles/article-1')
      .flush({ owner: 'user-1' });
    expect(component.editorAllowed).toBe(true);
    fixture.destroy();
  });

  it('shows only the navigation allowed for each authenticated role', async () => {
    await TestBed.configureTestingModule({
      imports: [Portal],
      providers: [provideRouter([]), provideHttpClient(), provideHttpClientTesting()],
    }).compileComponents();
    const component = TestBed.createComponent(Portal).componentInstance;
    component.auth.user = null;
    expect(component.canSeeNav('/public/articles')).toBe(true);
    expect(component.canSeeNav('/account/profile')).toBe(false);
    component.auth.user = {
      id: 'user-1',
      displayName: 'Ada',
      preferredLanguage: 'en',
      role: 'AUTHOR',
    };
    expect(component.canSeeNav('/account/profile')).toBe(true);
    expect(component.canSeeNav('/admin/users')).toBe(false);
  });

  it('localizes delete confirmation and role labels', async () => {
    await TestBed.configureTestingModule({
      imports: [Portal],
      providers: [provideRouter([]), provideHttpClient(), provideHttpClientTesting()],
    }).compileComponents();
    const component = TestBed.createComponent(Portal).componentInstance;
    const language = TestBed.inject((await import('../core/language')).Language);
    language.usePreferred('en');
    expect(component.confirmMessage({ title: 'Hello' })).toContain('Delete article "Hello"?');
    expect(component.roleLabel('AUTHOR')).toBe('Author');
    language.usePreferred('zh-TW');
    expect(component.roleLabel('ADMIN')).toBe('管理員');
  });

  it('restores a token user and preferred language without blocking public content', async () => {
    await TestBed.configureTestingModule({
      imports: [Portal],
      providers: [provideRouter([]), provideHttpClient(), provideHttpClientTesting()],
    }).compileComponents();
    const fixture = TestBed.createComponent(Portal);
    const component = fixture.componentInstance;
    const auth = TestBed.inject(Auth);
    const language = TestBed.inject((await import('../core/language')).Language);
    auth.setToken('token');
    component.routeKey = 'public/articles';
    component.ngOnInit();
    const http = TestBed.inject(HttpTestingController);
    http.expectOne('/api/v1/public/articles?page=0').flush([]);
    http.expectOne('/api/v1/account/me').flush({
      id: 'user-1',
      displayName: 'Ada',
      preferredLanguage: 'en',
      role: 'AUTHOR',
    });
    fixture.detectChanges();
    expect(auth.user?.id).toBe('user-1');
    expect(language.lang()).toBe('en');
    expect(component.canSeeNav('/account/profile')).toBe(true);
    auth.clear();
    fixture.destroy();
  });

  it('does not load the current user for a public visitor', async () => {
    await TestBed.configureTestingModule({
      imports: [Portal],
      providers: [provideRouter([]), provideHttpClient(), provideHttpClientTesting()],
    }).compileComponents();
    const fixture = TestBed.createComponent(Portal);
    const component = fixture.componentInstance;
    component.routeKey = 'public/articles';
    component.ngOnInit();
    const http = TestBed.inject(HttpTestingController);
    http.expectOne('/api/v1/public/articles?page=0').flush([]);
    http.expectNone('/api/v1/account/me');
    expect(component.canSeeNav('/account/profile')).toBe(false);
    fixture.destroy();
  });

  it('shows author article routes and refreshes the translated public heading', async () => {
    await TestBed.configureTestingModule({
      imports: [Portal],
      providers: [provideRouter([]), provideHttpClient(), provideHttpClientTesting()],
    }).compileComponents();
    const fixture = TestBed.createComponent(Portal);
    const component = fixture.componentInstance;
    const auth = TestBed.inject(Auth);
    auth.setToken('token');
    component.routeKey = 'public/articles';
    component.ngOnInit();
    expect(component.canSeeNav('/admin/articles')).toBe(false);
    const http = TestBed.inject(HttpTestingController);
    http.expectOne('/api/v1/public/articles?page=0').flush([]);
    http.expectOne('/api/v1/account/me').flush({
      id: 'user-1',
      displayName: 'Ada',
      preferredLanguage: 'zh-TW',
      role: 'AUTHOR',
    });
    fixture.detectChanges();
    expect(component.canSeeNav('/admin/articles')).toBe(true);
    expect(component.canSeeNav('/admin/articles/deleted')).toBe(true);
    expect(component.canSeeNav('/admin/users')).toBe(false);
    expect(component.heading).toBe('文章管理');
    auth.clear();
    fixture.destroy();
  });
});
