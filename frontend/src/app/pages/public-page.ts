import { CommonModule } from '@angular/common';
import { HttpErrorResponse } from '@angular/common/http';
import {
  ChangeDetectionStrategy,
  ChangeDetectorRef,
  Component,
  DestroyRef,
  OnInit,
  inject,
} from '@angular/core';
import { ActivatedRoute, RouterLink } from '@angular/router';
import {
  EMPTY,
  Subject,
  catchError,
  distinctUntilChanged,
  map,
  merge,
  of,
  switchMap,
  tap,
} from 'rxjs';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { Language } from '../core/language';
import { AppShell } from '../layouts/app-shell';
import {
  getPageNumbers,
  isPageSize,
  PAGE_SIZE,
  PAGE_SIZE_OPTIONS,
  PageSize,
} from '../core/pagination';
import { Page, PublicArticle, PublicArticleApi, PublicTag } from '../core/api';

@Component({
  selector: 'app-public-page',
  standalone: true,
  imports: [CommonModule, RouterLink, AppShell],
  templateUrl: './public-page.html',
  styleUrl: './public-page.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class PublicPage implements OnInit {
  private readonly api = inject(PublicArticleApi);
  private readonly route = inject(ActivatedRoute);
  private readonly cdr = inject(ChangeDetectorRef);
  private readonly destroyRef = inject(DestroyRef);
  private readonly articleRequests = new Subject<{
    tagId: string;
    page: number;
    pageSize: PageSize;
  }>();
  readonly language = inject(Language);
  routeKey = '';
  private tagId = '';
  items: (PublicArticle | PublicTag)[] = [];
  detail: PublicArticle | null = null;
  page = 0;
  pageSize: PageSize = PAGE_SIZE;
  readonly pageSizeOptions = PAGE_SIZE_OPTIONS;
  totalPages = 0;
  loading = false;
  readonly articleSkeletons = [0, 1, 2];
  private errorKey: 'forbidden' | 'notFound' | 'error' | '' = '';

  dateValue(row: PublicArticle | PublicTag) {
    return 'createdAt' in row ? row.createdAt : null;
  }

  ngOnInit() {
    this.routeKey = this.route.snapshot.routeConfig?.path ?? '**';
    if (this.routeKey === 'forbidden' || this.routeKey === '**') {
      this.errorKey = this.routeKey === 'forbidden' ? 'forbidden' : 'notFound';
    } else if (this.routeKey === 'public/articles/:id') this.readDetail();
    else if (this.routeKey === 'public/articles') this.watchArticles();
    else this.read();
  }

  get heading() {
    this.language.lang();
    return this.routeKey === 'public/tags'
      ? this.language.t.nav.tags
      : this.language.t.nav.articles;
  }

  get error() {
    this.language.lang();
    return this.errorKey ? this.language.t[this.errorKey] : '';
  }

  hasTagFilter() {
    return this.routeKey === 'public/articles' && Boolean(this.tagId);
  }

  private watchArticles() {
    const tagRequests = (this.route.queryParamMap ?? of(this.route.snapshot.queryParamMap)).pipe(
      map((params) => ({ tagId: params.get('tagId') ?? '', page: 0, pageSize: this.pageSize })),
    );
    merge(tagRequests, this.articleRequests)
      .pipe(
        distinctUntilChanged(
          (previous, current) =>
            previous.tagId === current.tagId &&
            previous.page === current.page &&
            previous.pageSize === current.pageSize,
        ),
        tap(({ tagId, page }) => {
          this.tagId = tagId;
          this.page = page;
          this.loading = true;
          this.errorKey = '';
          this.cdr.markForCheck();
        }),
        switchMap(({ tagId, page, pageSize }) =>
          this.api.list({ page, size: pageSize, ...(tagId ? { tagId } : {}) }).pipe(
            catchError((e: HttpErrorResponse) => {
              this.fail(e.status);
              return EMPTY;
            }),
          ),
        ),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe((value) => this.updateItems(value));
  }

  private read() {
    this.loading = true;
    if (this.routeKey === 'public/articles') this.cdr.markForCheck();
    const next = (value: Page<PublicArticle | PublicTag> | (PublicArticle | PublicTag)[]) =>
      this.updateItems(value);
    const fail = (e: HttpErrorResponse) => this.fail(e.status);
    if (this.routeKey === 'public/tags')
      this.api.tags(this.page, this.pageSize).subscribe({ next, error: fail });
    else
      this.api
        .list({
          page: this.page,
          size: this.pageSize,
          ...(this.tagId ? { tagId: this.tagId } : {}),
        })
        .subscribe({
          next,
          error: fail,
        });
  }

  changePageSize(value: number) {
    if (this.loading || !isPageSize(value) || value === this.pageSize) return;
    this.pageSize = value;
    this.page = 0;
    if (this.routeKey === 'public/articles') this.requestArticlePage(0);
    else this.read();
  }

  private updateItems(value: Page<PublicArticle | PublicTag> | (PublicArticle | PublicTag)[]) {
    const response: Page<PublicArticle | PublicTag> = Array.isArray(value)
      ? { content: value, totalPages: 1 }
      : value;
    this.items = response.content;
    this.totalPages = response.page?.totalPages ?? response.totalPages;
    this.loading = false;
    this.cdr.markForCheck();
  }

  private readDetail() {
    this.loading = true;
    this.api.get(this.route.snapshot.paramMap.get('id')!).subscribe({
      next: (value) => {
        this.detail = value;
        this.loading = false;
        this.cdr.markForCheck();
      },
      error: (e: HttpErrorResponse) => this.fail(e.status),
    });
  }

  previousPage() {
    if (this.page > 0) {
      if (this.routeKey === 'public/articles') this.requestArticlePage(this.page - 1);
      else {
        this.page--;
        this.read();
      }
    }
  }
  nextPage() {
    if (this.page + 1 < this.totalPages) {
      if (this.routeKey === 'public/articles') this.requestArticlePage(this.page + 1);
      else {
        this.page++;
        this.read();
      }
    }
  }
  goToPage(targetPage: number) {
    if (targetPage >= 0 && targetPage < this.totalPages && targetPage !== this.page) {
      if (this.routeKey === 'public/articles') this.requestArticlePage(targetPage);
      else {
        this.page = targetPage;
        this.read();
      }
    }
  }

  private requestArticlePage(page: number) {
    this.articleRequests.next({ tagId: this.tagId, page, pageSize: this.pageSize });
  }
  get pageNumbers(): number[] {
    return getPageNumbers(this.page, this.totalPages);
  }
  tagQuery(row: PublicArticle | PublicTag) {
    return { tagId: row.id };
  }
  title(row: PublicArticle | PublicTag) {
    return 'title' in row ? row.title : row.name;
  }
  id(row: PublicArticle | PublicTag) {
    return row.id;
  }
  author(row: PublicArticle | PublicTag) {
    return 'authorAttribution' in row ? row.authorAttribution : '';
  }
  private fail(status: number) {
    this.loading = false;
    this.errorKey = status === 403 ? 'forbidden' : status === 404 ? 'notFound' : 'error';
    this.cdr.markForCheck();
  }
}
