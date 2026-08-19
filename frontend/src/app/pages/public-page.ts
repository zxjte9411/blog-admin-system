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
import { ActivatedRoute, ParamMap, Router, RouterLink } from '@angular/router';
import { EMPTY, Observable, catchError, distinctUntilChanged, map, of, switchMap, tap } from 'rxjs';
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

type PublicListState = {
  title: string;
  tagId: string;
  page: number;
  pageSize: PageSize;
};

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
  private readonly router = inject(Router);
  private readonly cdr = inject(ChangeDetectorRef);
  private readonly destroyRef = inject(DestroyRef);
  readonly language = inject(Language);
  routeKey = '';
  private tagId = '';
  searchTitle = '';
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
    } else if (this.routeKey === 'public/articles/:id') {
      this.applyState(this.stateFromParams(this.route.snapshot.queryParamMap));
      this.readDetail();
    } else if (this.routeKey === 'public/articles' || this.routeKey === 'public/tags')
      this.watchList();
    else this.errorKey = 'notFound';
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

  private watchList() {
    const queryRequests = (this.route.queryParamMap ?? of(this.route.snapshot.queryParamMap)).pipe(
      map((params) => this.stateFromParams(params)),
    );
    queryRequests
      .pipe(
        distinctUntilChanged(
          (previous, current) =>
            previous.title === current.title &&
            previous.tagId === current.tagId &&
            previous.page === current.page &&
            previous.pageSize === current.pageSize,
        ),
        tap((state) => {
          this.applyState(state);
          this.loading = true;
          this.errorKey = '';
          this.cdr.markForCheck();
        }),
        switchMap(({ title, tagId, page, pageSize }) => {
          const request: Observable<Page<PublicArticle | PublicTag>> =
            this.routeKey === 'public/tags'
              ? this.api
                  .tags(page, pageSize)
                  .pipe(map((result) => result as Page<PublicArticle | PublicTag>))
              : this.api.list({
                  page,
                  size: pageSize,
                  ...(title ? { title } : {}),
                  ...(tagId ? { tagId } : {}),
                });
          return request.pipe(
            catchError((e: HttpErrorResponse) => {
              this.fail(e.status);
              return EMPTY;
            }),
          );
        }),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe((value) => this.updateItems(value));
  }

  private stateFromParams(params: ParamMap): PublicListState {
    const page = Number(params.get('page'));
    const pageSize = Number(params.get('pageSize'));
    const isTags = this.routeKey === 'public/tags';
    return {
      title: isTags ? '' : (params.get('title') ?? ''),
      tagId: isTags ? '' : (params.get('tagId') ?? ''),
      page: Number.isInteger(page) && page > 0 ? page - 1 : 0,
      pageSize: isPageSize(pageSize) ? pageSize : PAGE_SIZE,
    };
  }

  private applyState(state: PublicListState) {
    this.searchTitle = state.title;
    this.tagId = state.tagId;
    this.page = state.page;
    this.pageSize = state.pageSize;
  }

  changePageSize(value: number) {
    if (!isPageSize(value) || value === this.pageSize) return;
    this.navigateQuery({
      page: null,
      pageSize: value === PAGE_SIZE ? null : value,
      ...(this.routeKey === 'public/tags' ? { title: null, tagId: null } : {}),
    });
  }

  private updateItems(value: Page<PublicArticle | PublicTag> | (PublicArticle | PublicTag)[]) {
    const response: Page<PublicArticle | PublicTag> = Array.isArray(value)
      ? { content: value, totalPages: 1 }
      : value;
    this.totalPages = response.page?.totalPages ?? response.totalPages;
    if (this.totalPages > 0 && this.page >= this.totalPages) {
      const lastPage = this.totalPages - 1;
      this.navigateQuery(
        {
          page: lastPage === 0 ? null : lastPage + 1,
          ...(this.routeKey === 'public/tags' ? { title: null, tagId: null } : {}),
        },
        true,
      );
      return;
    }
    this.items = response.content;
    this.loading = false;
    this.cdr.markForCheck();
  }

  private readDetail() {
    this.loading = true;
    this.api.get(this.route.snapshot.paramMap.get('id')!).subscribe({
      next: (value) => {
        this.detail = { ...value, tags: value.tags ?? [] };
        this.loading = false;
        this.cdr.markForCheck();
      },
      error: (e: HttpErrorResponse) => this.fail(e.status),
    });
  }

  previousPage() {
    if (this.page > 0) {
      this.navigatePage(this.page - 1);
    }
  }
  nextPage() {
    if (this.page + 1 < this.totalPages) {
      this.navigatePage(this.page + 1);
    }
  }
  goToPage(targetPage: number) {
    if (targetPage >= 0 && targetPage < this.totalPages && targetPage !== this.page) {
      this.navigatePage(targetPage);
    }
  }

  private navigatePage(page: number) {
    this.navigateQuery({
      page: page === 0 ? null : page + 1,
      ...(this.routeKey === 'public/tags' ? { title: null, tagId: null } : {}),
    });
  }

  private navigateQuery(
    queryParams: {
      page?: number | null;
      pageSize?: number | null;
      title?: string | null;
      tagId?: string | null;
    },
    replaceUrl = false,
  ) {
    void this.router
      .navigate([], {
        relativeTo: this.route,
        queryParams,
        queryParamsHandling: 'merge',
        replaceUrl,
      })
      .catch(() => undefined);
  }

  search(title: string) {
    const value = title.trim();
    this.navigateQuery({ title: value || null, page: null });
  }

  clearSearch() {
    this.search('');
  }

  clearTagFilter() {
    this.navigateQuery({ page: null, tagId: null });
  }
  get pageNumbers(): number[] {
    return getPageNumbers(this.page, this.totalPages);
  }
  tagQuery(row: PublicArticle | PublicTag) {
    return {
      tagId: row.id,
      page: null,
      ...(this.pageSize !== PAGE_SIZE ? { pageSize: this.pageSize } : {}),
    };
  }
  isActiveTag(tag: PublicTag) {
    return this.tagId === tag.id;
  }
  articleTagQuery(tag: PublicTag) {
    return {
      title: this.searchTitle || null,
      tagId: tag.id,
      page: null,
      pageSize: this.pageSize === PAGE_SIZE ? null : this.pageSize,
    };
  }
  detailQuery() {
    return {
      ...(this.searchTitle ? { title: this.searchTitle } : {}),
      ...(this.tagId ? { tagId: this.tagId } : {}),
      ...(this.page > 0 ? { page: this.page + 1 } : {}),
      ...(this.pageSize !== PAGE_SIZE ? { pageSize: this.pageSize } : {}),
    };
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
