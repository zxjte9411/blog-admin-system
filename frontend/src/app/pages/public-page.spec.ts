import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting, HttpTestingController } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { ActivatedRoute, provideRouter } from '@angular/router';
import { PublicPage } from './public-page';

describe('PublicPage', () => {
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
      .expectOne('/api/v1/public/articles?page=0')
      .flush([{ id: 'public-1', title: 'Public article' }]);
    fixture.detectChanges();

    expect(fixture.nativeElement.querySelector('.row-title')?.textContent).toContain(
      'Public article',
    );
    expect(fixture.nativeElement.querySelector('app-article-management-list')).toBeNull();
    expect(fixture.nativeElement.querySelector('form')).toBeNull();
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
    http.expectOne('/api/v1/public/articles?page=0').flush({
      content: [{ id: 'one', title: 'First article', authorAttribution: 'By Ada' }],
      page: { totalPages: 1 },
    });
    fixture.detectChanges();

    expect(fixture.nativeElement.textContent).toContain('First article');
    expect(fixture.nativeElement.querySelector('a[href="/public/articles/one"]')).toBeTruthy();
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
      .expectOne('/api/v1/public/articles?page=0&tagId=tag-1')
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
      .flush({ title: 'Public title', content: 'Story' });
    fixture.detectChanges();

    expect(fixture.nativeElement.querySelector('article').textContent).toContain('Story');
    expect(fixture.nativeElement.querySelector('button[aria-label="Delete"]')).toBeNull();
  });
});
