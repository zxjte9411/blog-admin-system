import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting, HttpTestingController } from '@angular/common/http/testing';
import { Component } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import {
  ActivatedRoute,
  Router,
  RouterOutlet,
  convertToParamMap,
  provideRouter,
} from '@angular/router';
import { BehaviorSubject } from 'rxjs';
import { PublicPage } from './public-page';
import { Language } from '../core/language';

@Component({ standalone: true, imports: [RouterOutlet], template: '<router-outlet />' })
class RouterHost {}

describe('PublicPage', () => {
  beforeEach(() => {
    localStorage.removeItem('blog-admin-token');
  });

  it('shows three accessible article skeletons while the initial empty list is pending', async () => {
    await TestBed.configureTestingModule({
      imports: [PublicPage],
      providers: [
        provideRouter([]),
        provideHttpClient(),
        provideHttpClientTesting(),
        {
          provide: ActivatedRoute,
          useValue: {
            snapshot: {
              routeConfig: { path: 'public/articles' },
              paramMap: { get: () => null },
              queryParamMap: { get: () => null },
            },
          },
        },
      ],
    }).compileComponents();

    const fixture = TestBed.createComponent(PublicPage);
    fixture.detectChanges();

    const region = fixture.nativeElement.querySelector('.article-list-region');
    const status = region.querySelector('[role="status"]');
    const placeholders = region.querySelectorAll('.article-skeleton-card');

    expect(region.getAttribute('aria-busy')).toBe('true');
    expect(fixture.nativeElement.querySelector('.loading-bar')).toBeNull();
    expect(status?.getAttribute('aria-label')).toBe(fixture.componentInstance.language.t.loading);
    expect(region.querySelectorAll('[role="status"]')).toHaveLength(1);
    expect(placeholders).toHaveLength(3);
    placeholders.forEach((placeholder: HTMLElement) => {
      expect(placeholder.getAttribute('aria-hidden')).toBe('true');
      expect(placeholder.getAttribute('role')).toBeNull();
      expect(placeholder.getAttribute('aria-live')).toBeNull();
    });
  });

  it('keeps public tag pagination without showing article skeletons', async () => {
    await TestBed.configureTestingModule({
      imports: [PublicPage],
      providers: [
        provideRouter([]),
        provideHttpClient(),
        provideHttpClientTesting(),
        {
          provide: ActivatedRoute,
          useValue: {
            snapshot: {
              routeConfig: { path: 'public/tags' },
              paramMap: { get: () => null },
              queryParamMap: { get: () => null },
            },
          },
        },
      ],
    }).compileComponents();

    const fixture = TestBed.createComponent(PublicPage);
    fixture.detectChanges();
    const http = TestBed.inject(HttpTestingController);
    expect(fixture.nativeElement.querySelector('.article-skeleton')).toBeNull();
    http.expectOne('/api/v1/public/tags?page=0&size=10').flush({
      content: [{ id: 'tag-1', name: 'News' }],
      page: { totalPages: 2 },
    });
    fixture.detectChanges();

    expect(fixture.nativeElement.querySelector('.pagination')).toBeTruthy();
    fixture.componentInstance.nextPage();
    fixture.detectChanges();

    http.expectOne('/api/v1/public/tags?page=1&size=10');
  });

  it('changes public tag page size, resets to page one, and updates controls', async () => {
    await TestBed.configureTestingModule({
      imports: [PublicPage],
      providers: [
        provideRouter([]),
        provideHttpClient(),
        provideHttpClientTesting(),
        {
          provide: ActivatedRoute,
          useValue: {
            snapshot: {
              routeConfig: { path: 'public/tags' },
              paramMap: { get: () => null },
              queryParamMap: { get: () => null },
            },
          },
        },
      ],
    }).compileComponents();

    const fixture = TestBed.createComponent(PublicPage);
    TestBed.inject(Language).usePreferred('en');
    fixture.detectChanges();
    const http = TestBed.inject(HttpTestingController);
    http.expectOne('/api/v1/public/tags?page=0&size=10').flush({
      content: [{ id: 'tag-1', name: 'News' }],
      page: { totalPages: 3 },
    });
    fixture.componentInstance.nextPage();
    http.expectOne('/api/v1/public/tags?page=1&size=10').flush({
      content: [{ id: 'tag-2', name: 'Spring' }],
      page: { totalPages: 3 },
    });
    fixture.detectChanges();

    const select = fixture.nativeElement.querySelector(
      'select#public-page-size',
    ) as HTMLSelectElement;
    expect(select.getAttribute('aria-label')).toBe('Items per page');
    select.value = '100';
    select.dispatchEvent(new Event('change'));
    const requests = http.match('/api/v1/public/tags?page=0&size=100');
    expect(requests).toHaveLength(1);
    requests[0].flush({ content: [{ id: 'tag-3', name: 'Release' }], page: { totalPages: 1 } });
    fixture.detectChanges();

    expect(fixture.componentInstance.page).toBe(0);
    expect(fixture.componentInstance.totalPages).toBe(1);
    expect(fixture.nativeElement.querySelector('.page-indicator')?.textContent).toContain('1');
  });

  it('removes article skeletons and shows the empty state after an empty response', async () => {
    await TestBed.configureTestingModule({
      imports: [PublicPage],
      providers: [
        provideRouter([]),
        provideHttpClient(),
        provideHttpClientTesting(),
        {
          provide: ActivatedRoute,
          useValue: {
            snapshot: {
              routeConfig: { path: 'public/articles' },
              paramMap: { get: () => null },
              queryParamMap: { get: () => null },
            },
          },
        },
      ],
    }).compileComponents();

    const fixture = TestBed.createComponent(PublicPage);
    fixture.detectChanges();
    TestBed.inject(HttpTestingController)
      .expectOne('/api/v1/public/articles?page=0&size=10')
      .flush({ content: [], page: { totalPages: 0 } });
    fixture.detectChanges();

    expect(fixture.nativeElement.querySelector('.article-skeleton')).toBeNull();
    expect(fixture.nativeElement.querySelector('.empty')).toBeTruthy();
    expect(
      fixture.nativeElement.querySelector('.article-list-region').getAttribute('aria-busy'),
    ).toBe('false');
  });

  it('does not show skeletons or empty state when the article request fails', async () => {
    await TestBed.configureTestingModule({
      imports: [PublicPage],
      providers: [
        provideRouter([]),
        provideHttpClient(),
        provideHttpClientTesting(),
        {
          provide: ActivatedRoute,
          useValue: {
            snapshot: {
              routeConfig: { path: 'public/articles' },
              paramMap: { get: () => null },
              queryParamMap: { get: () => null },
            },
          },
        },
      ],
    }).compileComponents();

    const fixture = TestBed.createComponent(PublicPage);
    fixture.detectChanges();
    TestBed.inject(HttpTestingController)
      .expectOne('/api/v1/public/articles?page=0&size=10')
      .flush({}, { status: 500, statusText: 'Server Error' });
    fixture.detectChanges();

    expect(fixture.nativeElement.querySelector('.article-skeleton')).toBeNull();
    expect(fixture.nativeElement.querySelector('.empty')).toBeNull();
    expect(fixture.nativeElement.querySelector('.error[role="alert"]')).toBeTruthy();
    expect(
      fixture.nativeElement.querySelector('.article-list-region').getAttribute('aria-busy'),
    ).toBe('false');
  });

  it('keeps rendered articles while a filtered pagination request is pending', async () => {
    await TestBed.configureTestingModule({
      imports: [PublicPage],
      providers: [
        provideRouter([]),
        provideHttpClient(),
        provideHttpClientTesting(),
        {
          provide: ActivatedRoute,
          useValue: {
            snapshot: {
              routeConfig: { path: 'public/articles' },
              paramMap: { get: () => null },
              queryParamMap: { get: (key: string) => (key === 'tagId' ? 'tag-1' : null) },
            },
          },
        },
      ],
    }).compileComponents();

    const fixture = TestBed.createComponent(PublicPage);
    fixture.detectChanges();
    const http = TestBed.inject(HttpTestingController);
    http.expectOne('/api/v1/public/articles?page=0&size=10&tagId=tag-1').flush({
      content: [{ id: 'one', title: 'First article', authorAttribution: 'By Ada' }],
      page: { totalPages: 2 },
    });
    fixture.detectChanges();

    fixture.componentInstance.nextPage();
    fixture.detectChanges();

    expect(fixture.componentInstance.loading).toBe(true);
    expect(fixture.nativeElement.querySelector('.items')).toBeTruthy();
    expect(fixture.nativeElement.querySelector('.row-title')?.textContent).toContain(
      'First article',
    );
    expect(fixture.nativeElement.querySelector('.article-skeleton')).toBeNull();
    expect(
      fixture.nativeElement.querySelector('.article-list-region').getAttribute('aria-busy'),
    ).toBe('true');
    http.expectOne('/api/v1/public/articles?page=1&size=10&tagId=tag-1');
  });

  it('changes filtered public article page size, keeps tagId, and sends one reset request', async () => {
    await TestBed.configureTestingModule({
      imports: [PublicPage],
      providers: [
        provideRouter([]),
        provideHttpClient(),
        provideHttpClientTesting(),
        {
          provide: ActivatedRoute,
          useValue: {
            snapshot: {
              routeConfig: { path: 'public/articles' },
              paramMap: { get: () => null },
              queryParamMap: { get: (key: string) => (key === 'tagId' ? 'tag-1' : null) },
            },
          },
        },
      ],
    }).compileComponents();

    const fixture = TestBed.createComponent(PublicPage);
    TestBed.inject(Language).usePreferred('en');
    fixture.detectChanges();
    const http = TestBed.inject(HttpTestingController);
    http.expectOne('/api/v1/public/articles?page=0&size=10&tagId=tag-1').flush({
      content: [{ id: 'one', title: 'First article', authorAttribution: 'By Ada' }],
      page: { totalPages: 3 },
    });
    fixture.componentInstance.nextPage();
    http.expectOne('/api/v1/public/articles?page=1&size=10&tagId=tag-1').flush({
      content: [{ id: 'two', title: 'Second article', authorAttribution: 'By Ada' }],
      page: { totalPages: 3 },
    });
    fixture.detectChanges();

    const select = fixture.nativeElement.querySelector(
      'select#public-page-size',
    ) as HTMLSelectElement;
    expect(select).toBeTruthy();
    expect(select.getAttribute('aria-label')).toBe('Items per page');
    select.value = '20';
    select.dispatchEvent(new Event('change'));
    const requests = http.match('/api/v1/public/articles?page=0&size=20&tagId=tag-1');
    expect(requests).toHaveLength(1);
    requests[0].flush({ content: [], page: { totalPages: 1 } });
    fixture.detectChanges();

    expect(fixture.componentInstance.page).toBe(0);
    expect(fixture.componentInstance.totalPages).toBe(1);
  });

  it('renders public content without account or administration actions', async () => {
    await TestBed.configureTestingModule({
      imports: [PublicPage],
      providers: [
        provideRouter([]),
        provideHttpClient(),
        provideHttpClientTesting(),
        {
          provide: ActivatedRoute,
          useValue: {
            snapshot: {
              routeConfig: { path: 'public/articles' },
              paramMap: { get: () => null },
              queryParamMap: { get: () => null },
            },
          },
        },
      ],
    }).compileComponents();

    const fixture = TestBed.createComponent(PublicPage);
    fixture.detectChanges();
    TestBed.inject(HttpTestingController)
      .expectOne('/api/v1/public/articles?page=0&size=10')
      .flush([{ id: 'public-1', title: 'Public article' }]);
    fixture.detectChanges();

    expect(fixture.nativeElement.querySelector('.row-title')?.textContent).toContain(
      'Public article',
    );
    expect(fixture.nativeElement.querySelector('app-article-management-list')).toBeNull();
    expect(fixture.nativeElement.querySelector('.article-search')).toBeTruthy();
    expect(fixture.nativeElement.querySelector('button[aria-label="Delete"]')).toBeNull();
  });

  it('loads public articles and renders a public detail link', async () => {
    await TestBed.configureTestingModule({
      imports: [PublicPage],
      providers: [
        provideRouter([]),
        provideHttpClient(),
        provideHttpClientTesting(),
        {
          provide: ActivatedRoute,
          useValue: {
            snapshot: {
              routeConfig: { path: 'public/articles' },
              paramMap: { get: () => null },
              queryParamMap: { get: () => null },
            },
          },
        },
      ],
    }).compileComponents();

    const fixture = TestBed.createComponent(PublicPage);
    fixture.detectChanges();
    const http = TestBed.inject(HttpTestingController);
    http.expectOne('/api/v1/public/articles?page=0&size=10').flush({
      content: [
        {
          id: 'one',
          title: 'First article',
          authorAttribution: 'By Ada',
          tags: [{ id: 'tag-1', name: 'News' }],
        },
      ],
      page: { totalPages: 1 },
    });
    fixture.detectChanges();

    expect(fixture.nativeElement.textContent).toContain('First article');
    expect(fixture.nativeElement.querySelector('a[href="/public/articles/one"]')).toBeTruthy();
    expect(fixture.nativeElement.querySelector('.article-tags')).toBeTruthy();
    expect(
      fixture.nativeElement.querySelector('.article-tags .tag-pill-link')?.getAttribute('href'),
    ).toContain('tagId=tag-1');
  });

  it('uses nested Page metadata for public article pagination', async () => {
    await TestBed.configureTestingModule({
      imports: [PublicPage],
      providers: [
        provideRouter([]),
        provideHttpClient(),
        provideHttpClientTesting(),
        {
          provide: ActivatedRoute,
          useValue: {
            snapshot: {
              routeConfig: { path: 'public/articles' },
              paramMap: { get: () => null },
              queryParamMap: { get: () => null },
            },
          },
        },
      ],
    }).compileComponents();
    const fixture = TestBed.createComponent(PublicPage);
    fixture.detectChanges();
    TestBed.inject(HttpTestingController)
      .expectOne('/api/v1/public/articles?page=0&size=10')
      .flush({ content: [], page: { totalPages: 4 } });
    expect(fixture.componentInstance.totalPages).toBe(4);
  });

  it('offers a clear-filter link when a tag filter is active', async () => {
    await TestBed.configureTestingModule({
      imports: [PublicPage],
      providers: [
        provideRouter([]),
        provideHttpClient(),
        provideHttpClientTesting(),
        {
          provide: ActivatedRoute,
          useValue: {
            snapshot: {
              routeConfig: { path: 'public/articles' },
              paramMap: { get: () => null },
              queryParamMap: { get: (key: string) => (key === 'tagId' ? 'tag-1' : null) },
            },
          },
        },
      ],
    }).compileComponents();

    const fixture = TestBed.createComponent(PublicPage);
    fixture.detectChanges();
    TestBed.inject(HttpTestingController)
      .expectOne('/api/v1/public/articles?page=0&size=10&tagId=tag-1')
      .flush([]);
    fixture.detectChanges();

    expect(fixture.nativeElement.querySelector('.clear-filter')).toBeTruthy();
  });

  it('renders a public article detail without account actions', async () => {
    await TestBed.configureTestingModule({
      imports: [PublicPage],
      providers: [
        provideRouter([]),
        provideHttpClient(),
        provideHttpClientTesting(),
        {
          provide: ActivatedRoute,
          useValue: {
            snapshot: {
              routeConfig: { path: 'public/articles/:id' },
              paramMap: { get: (key: string) => (key === 'id' ? 'article-1' : null) },
              queryParamMap: { get: () => null },
            },
          },
        },
      ],
    }).compileComponents();

    const fixture = TestBed.createComponent(PublicPage);
    fixture.detectChanges();
    TestBed.inject(HttpTestingController)
      .expectOne('/api/v1/public/articles/article-1')
      .flush({
        title: 'Public title',
        content: 'Story',
        tags: [{ id: 'tag-1', name: 'News' }],
      });
    fixture.detectChanges();

    expect(fixture.nativeElement.querySelector('article').textContent).toContain('Story');
    expect(fixture.nativeElement.querySelector('.article-tags')).toBeTruthy();
    expect(fixture.nativeElement.querySelector('button[aria-label="Delete"]')).toBeNull();
  });

  it('reloads articles from reactive tag changes and resets to the first page', async () => {
    const queryParamMap = new BehaviorSubject(convertToParamMap({}));
    await TestBed.configureTestingModule({
      imports: [PublicPage],
      providers: [
        provideRouter([]),
        provideHttpClient(),
        provideHttpClientTesting(),
        {
          provide: ActivatedRoute,
          useValue: {
            snapshot: {
              routeConfig: { path: 'public/articles' },
              paramMap: { get: () => null },
              queryParamMap: queryParamMap.value,
            },
            queryParamMap: queryParamMap.asObservable(),
          },
        },
      ],
    }).compileComponents();

    const fixture = TestBed.createComponent(PublicPage);
    const http = TestBed.inject(HttpTestingController);
    fixture.detectChanges();
    http.expectOne('/api/v1/public/articles?page=0&size=10').flush({
      content: [{ id: 'one', title: 'First article', authorAttribution: 'By Ada' }],
      page: { totalPages: 3 },
    });
    fixture.componentInstance.nextPage();
    http.expectOne('/api/v1/public/articles?page=1&size=10').flush({
      content: [{ id: 'two', title: 'Second article', authorAttribution: 'By Ada' }],
      page: { totalPages: 3 },
    });

    queryParamMap.next(convertToParamMap({ tagId: 'tag-a' }));
    http.expectOne('/api/v1/public/articles?page=0&size=10&tagId=tag-a').flush({
      content: [{ id: 'tag-a-article', title: 'Tag A article', authorAttribution: 'By Ada' }],
      page: { totalPages: 2 },
    });
    queryParamMap.next(convertToParamMap({ tagId: 'tag-a' }));
    http.expectNone('/api/v1/public/articles?page=0&size=10&tagId=tag-a');
    fixture.componentInstance.nextPage();
    http.expectOne('/api/v1/public/articles?page=1&size=10&tagId=tag-a').flush({
      content: [{ id: 'tag-a-second', title: 'Tag A second', authorAttribution: 'By Ada' }],
      page: { totalPages: 2 },
    });

    queryParamMap.next(convertToParamMap({ tagId: 'tag-b' }));
    http.expectOne('/api/v1/public/articles?page=0&size=10&tagId=tag-b').flush({
      content: [{ id: 'tag-b-article', title: 'Tag B article', authorAttribution: 'By Ada' }],
      page: { totalPages: 1 },
    });

    queryParamMap.next(convertToParamMap({}));
    http.expectOne('/api/v1/public/articles?page=0&size=10').flush({
      content: [{ id: 'unfiltered', title: 'Unfiltered article', authorAttribution: 'By Ada' }],
      page: { totalPages: 1 },
    });

    expect(fixture.componentInstance.page).toBe(0);
    fixture.destroy();
    queryParamMap.next(convertToParamMap({ tagId: 'tag-c' }));
    http.expectNone('/api/v1/public/articles?page=0&size=10&tagId=tag-c');
    http.verify();
  });

  it('cancels a pending page request when the tag changes', async () => {
    const queryParamMap = new BehaviorSubject(convertToParamMap({}));
    await TestBed.configureTestingModule({
      imports: [PublicPage],
      providers: [
        provideRouter([]),
        provideHttpClient(),
        provideHttpClientTesting(),
        {
          provide: ActivatedRoute,
          useValue: {
            snapshot: {
              routeConfig: { path: 'public/articles' },
              paramMap: { get: () => null },
              queryParamMap: queryParamMap.value,
            },
            queryParamMap: queryParamMap.asObservable(),
          },
        },
      ],
    }).compileComponents();

    const fixture = TestBed.createComponent(PublicPage);
    const http = TestBed.inject(HttpTestingController);
    fixture.detectChanges();
    http.expectOne('/api/v1/public/articles?page=0&size=10').flush({
      content: [{ id: 'one', title: 'First article', authorAttribution: 'By Ada' }],
      page: { totalPages: 2 },
    });
    fixture.componentInstance.nextPage();
    const pendingPage = http.expectOne('/api/v1/public/articles?page=1&size=10');

    queryParamMap.next(convertToParamMap({ tagId: 'tag-a' }));
    expect(pendingPage.cancelled).toBe(true);
    http.expectOne('/api/v1/public/articles?page=0&size=10&tagId=tag-a').flush({
      content: [{ id: 'tag-a-article', title: 'Tag A article', authorAttribution: 'By Ada' }],
      page: { totalPages: 1 },
    });

    expect(fixture.componentInstance.items[0].id).toBe('tag-a-article');
    http.verify();
  });

  it('cancels a pending page request when the page is destroyed', async () => {
    const queryParamMap = new BehaviorSubject(convertToParamMap({}));
    await TestBed.configureTestingModule({
      imports: [PublicPage],
      providers: [
        provideRouter([]),
        provideHttpClient(),
        provideHttpClientTesting(),
        {
          provide: ActivatedRoute,
          useValue: {
            snapshot: {
              routeConfig: { path: 'public/articles' },
              paramMap: { get: () => null },
              queryParamMap: queryParamMap.value,
            },
            queryParamMap: queryParamMap.asObservable(),
          },
        },
      ],
    }).compileComponents();

    const fixture = TestBed.createComponent(PublicPage);
    const http = TestBed.inject(HttpTestingController);
    fixture.detectChanges();
    http.expectOne('/api/v1/public/articles?page=0&size=10').flush({
      content: [{ id: 'one', title: 'First article', authorAttribution: 'By Ada' }],
      page: { totalPages: 2 },
    });
    fixture.componentInstance.nextPage();
    const pendingPage = http.expectOne('/api/v1/public/articles?page=1&size=10');

    fixture.destroy();

    expect(pendingPage.cancelled).toBe(true);
    http.verify();
  });

  it('loads public article filters and one-based page state from the initial URL', async () => {
    await TestBed.configureTestingModule({
      imports: [PublicPage],
      providers: [
        provideRouter([]),
        provideHttpClient(),
        provideHttpClientTesting(),
        {
          provide: ActivatedRoute,
          useValue: {
            snapshot: {
              routeConfig: { path: 'public/articles' },
              paramMap: { get: () => null },
              queryParamMap: convertToParamMap({
                title: 'Angular',
                tagId: 'tag-1',
                page: '3',
                pageSize: '20',
              }),
            },
          },
        },
      ],
    }).compileComponents();

    const fixture = TestBed.createComponent(PublicPage);
    fixture.detectChanges();

    const request = TestBed.inject(HttpTestingController).expectOne(
      (req) => req.url === '/api/v1/public/articles',
    );
    expect(request.request.params.get('title')).toBe('Angular');
    expect(request.request.params.get('tagId')).toBe('tag-1');
    expect(request.request.params.get('page')).toBe('2');
    expect(request.request.params.get('size')).toBe('20');
  });

  it('submits trimmed search and keeps filters while resetting URL pagination', async () => {
    await TestBed.configureTestingModule({
      imports: [PublicPage],
      providers: [
        provideRouter([]),
        provideHttpClient(),
        provideHttpClientTesting(),
        {
          provide: ActivatedRoute,
          useValue: {
            snapshot: {
              routeConfig: { path: 'public/articles' },
              paramMap: { get: () => null },
              queryParamMap: convertToParamMap({ tagId: 'tag-1', page: '2', pageSize: '20' }),
            },
          },
        },
      ],
    }).compileComponents();

    const fixture = TestBed.createComponent(PublicPage);
    fixture.detectChanges();
    const http = TestBed.inject(HttpTestingController);
    http.expectOne('/api/v1/public/articles?page=1&size=20&tagId=tag-1').flush({
      content: [{ id: 'one', title: 'Old', authorAttribution: 'By Ada' }],
      page: { totalPages: 3 },
    });
    const navigate = vi.spyOn(TestBed.inject(Router), 'navigate').mockResolvedValue(true);
    const input = fixture.nativeElement.querySelector('#public-article-search') as HTMLInputElement;
    input.value = '  Angular  ';
    (fixture.nativeElement.querySelector('.article-search') as HTMLFormElement).requestSubmit();

    const searchRequest = http.expectOne(
      '/api/v1/public/articles?page=0&size=20&title=Angular&tagId=tag-1',
    );
    expect(searchRequest.request.params.get('title')).toBe('Angular');
    expect(navigate).toHaveBeenLastCalledWith(
      [],
      expect.objectContaining({
        queryParams: { title: 'Angular', page: null },
        queryParamsHandling: 'merge',
      }),
    );
    searchRequest.flush({ content: [], page: { totalPages: 0 } });

    fixture.componentInstance.clearSearch();
    http.expectOne('/api/v1/public/articles?page=0&size=20&tagId=tag-1').flush({
      content: [],
      page: { totalPages: 0 },
    });
    fixture.componentInstance.clearTagFilter();
    http.expectOne('/api/v1/public/articles?page=0&size=20').flush({
      content: [],
      page: { totalPages: 0 },
    });
    expect(navigate).toHaveBeenLastCalledWith(
      [],
      expect.objectContaining({ queryParams: { page: null, tagId: null } }),
    );
  });

  it('corrects an out-of-range URL page with replaceUrl and keeps filters', async () => {
    await TestBed.configureTestingModule({
      imports: [PublicPage],
      providers: [
        provideRouter([]),
        provideHttpClient(),
        provideHttpClientTesting(),
        {
          provide: ActivatedRoute,
          useValue: {
            snapshot: {
              routeConfig: { path: 'public/articles' },
              paramMap: { get: () => null },
              queryParamMap: convertToParamMap({
                title: 'Angular',
                tagId: 'tag-1',
                page: '5',
                pageSize: '20',
                keep: 'yes',
              }),
            },
          },
        },
      ],
    }).compileComponents();

    const navigate = vi.spyOn(TestBed.inject(Router), 'navigate').mockResolvedValue(true);
    const fixture = TestBed.createComponent(PublicPage);
    fixture.detectChanges();
    const http = TestBed.inject(HttpTestingController);
    http.expectOne('/api/v1/public/articles?page=4&size=20&title=Angular&tagId=tag-1').flush({
      content: [],
      page: { totalPages: 2 },
    });

    expect(navigate).toHaveBeenLastCalledWith(
      [],
      expect.objectContaining({
        queryParams: { page: 2 },
        queryParamsHandling: 'merge',
        replaceUrl: true,
      }),
    );
    http.expectOne('/api/v1/public/articles?page=1&size=20&title=Angular&tagId=tag-1').flush({
      content: [{ id: 'last', title: 'Last page', authorAttribution: 'By Ada' }],
      page: { totalPages: 2 },
    });
    expect(fixture.componentInstance.items[0].id).toBe('last');
    http.verify();
  });

  it('reads detail URL state and preserves it in detail tag navigation', async () => {
    await TestBed.configureTestingModule({
      imports: [PublicPage],
      providers: [
        provideRouter([]),
        provideHttpClient(),
        provideHttpClientTesting(),
        {
          provide: ActivatedRoute,
          useValue: {
            snapshot: {
              routeConfig: { path: 'public/articles/:id' },
              paramMap: { get: (key: string) => (key === 'id' ? 'article-1' : null) },
              queryParamMap: convertToParamMap({
                title: 'Angular',
                tagId: 'tag-1',
                page: '3',
                pageSize: '20',
              }),
            },
          },
        },
      ],
    }).compileComponents();

    const fixture = TestBed.createComponent(PublicPage);
    fixture.detectChanges();
    TestBed.inject(HttpTestingController)
      .expectOne('/api/v1/public/articles/article-1')
      .flush({
        title: 'Public title',
        content: 'Story',
        tags: [{ id: 'tag-2', name: 'News' }],
      });
    fixture.detectChanges();

    expect(fixture.componentInstance.searchTitle).toBe('Angular');
    expect(fixture.componentInstance.isActiveTag({ id: 'tag-1', name: 'Current' })).toBe(true);
    expect(fixture.componentInstance.page).toBe(2);
    expect(fixture.componentInstance.pageSize).toBe(20);
    expect(fixture.componentInstance.detailQuery()).toEqual({
      title: 'Angular',
      tagId: 'tag-1',
      page: 3,
      pageSize: 20,
    });
    const detailTagHref = fixture.nativeElement
      .querySelector('.article-detail .tag-pill-link')
      ?.getAttribute('href');
    expect(detailTagHref).toContain('title=Angular');
    expect(detailTagHref).toContain('tagId=tag-2');
    expect(detailTagHref).toContain('pageSize=20');
    expect(detailTagHref).not.toContain('page=');
  });

  it('does not duplicate the request when router navigation re-emits the same query state', async () => {
    await TestBed.configureTestingModule({
      imports: [RouterHost],
      providers: [
        provideRouter([{ path: 'public/articles', component: PublicPage }]),
        provideHttpClient(),
        provideHttpClientTesting(),
      ],
    }).compileComponents();

    const host = TestBed.createComponent(RouterHost);
    const router = TestBed.inject(Router);
    const http = TestBed.inject(HttpTestingController);
    host.detectChanges();
    await router.navigateByUrl('/public/articles?title=Angular&page=2&pageSize=20');
    host.detectChanges();
    http.expectOne('/api/v1/public/articles?page=1&size=20&title=Angular').flush({
      content: [{ id: 'one', title: 'First', authorAttribution: 'By Ada' }],
      page: { totalPages: 2 },
    });

    const input = host.nativeElement.querySelector('#public-article-search') as HTMLInputElement;
    input.value = 'Angular';
    (host.nativeElement.querySelector('.article-search') as HTMLFormElement).requestSubmit();
    const requests = http.match('/api/v1/public/articles?page=0&size=20&title=Angular');
    expect(requests).toHaveLength(1);
    requests[0].flush({ content: [], page: { totalPages: 0 } });
    await host.whenStable();
    http.expectNone('/api/v1/public/articles?page=0&size=20&title=Angular');
  });

  it('falls back invalid URL pagination without rewriting it and does not correct empty pages', async () => {
    await TestBed.configureTestingModule({
      imports: [PublicPage],
      providers: [
        provideRouter([]),
        provideHttpClient(),
        provideHttpClientTesting(),
        {
          provide: ActivatedRoute,
          useValue: {
            snapshot: {
              routeConfig: { path: 'public/articles' },
              paramMap: { get: () => null },
              queryParamMap: convertToParamMap({ page: 'invalid', pageSize: '15' }),
            },
          },
        },
      ],
    }).compileComponents();

    const router = TestBed.inject(Router);
    const navigate = vi.spyOn(router, 'navigate');
    const fixture = TestBed.createComponent(PublicPage);
    fixture.detectChanges();
    TestBed.inject(HttpTestingController)
      .expectOne('/api/v1/public/articles?page=0&size=10')
      .flush({ content: [], page: { totalPages: 0 } });

    expect(fixture.componentInstance.page).toBe(0);
    expect(fixture.componentInstance.pageSize).toBe(10);
    expect(navigate).not.toHaveBeenCalled();
  });

  it('keeps title and page size in tag directory links while removing page', async () => {
    await TestBed.configureTestingModule({
      imports: [PublicPage],
      providers: [
        provideRouter([]),
        provideHttpClient(),
        provideHttpClientTesting(),
        {
          provide: ActivatedRoute,
          useValue: {
            snapshot: {
              routeConfig: { path: 'public/tags' },
              paramMap: { get: () => null },
              queryParamMap: convertToParamMap({ title: 'Angular', page: '1', pageSize: '20' }),
            },
          },
        },
      ],
    }).compileComponents();

    const fixture = TestBed.createComponent(PublicPage);
    fixture.detectChanges();
    TestBed.inject(HttpTestingController)
      .expectOne('/api/v1/public/tags?page=0&size=20')
      .flush({ content: [{ id: 'tag-2', name: 'News' }], page: { totalPages: 1 } });
    fixture.detectChanges();

    const href = fixture.nativeElement
      .querySelector('.tag-pill-link')
      ?.getAttribute('href') as string;
    expect(href).toContain('title=Angular');
    expect(href).toContain('tagId=tag-2');
    expect(href).toContain('pageSize=20');
    expect(href).not.toContain('page=');
  });
});
