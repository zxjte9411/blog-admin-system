import { CommonModule } from '@angular/common';
import { HttpErrorResponse } from '@angular/common/http';
import {
  ChangeDetectionStrategy,
  ChangeDetectorRef,
  Component,
  OnInit,
  inject,
} from '@angular/core';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { Language } from '../core/language';
import { AppShell } from '../layouts/app-shell';
import { getPageNumbers } from '../core/pagination';
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
  readonly language = inject(Language);
  routeKey = '';
  items: (PublicArticle | PublicTag)[] = [];
  detail: PublicArticle | null = null;
  page = 0;
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
    return (
      this.routeKey === 'public/articles' && Boolean(this.route.snapshot.queryParamMap.get('tagId'))
    );
  }

  private read() {
    this.loading = true;
    if (this.routeKey === 'public/articles') this.cdr.markForCheck();
    const tagId = this.route.snapshot.queryParamMap.get('tagId');
    const next = (value: Page<PublicArticle | PublicTag> | (PublicArticle | PublicTag)[]) => {
      const response: Page<PublicArticle | PublicTag> = Array.isArray(value)
        ? { content: value, totalPages: 1 }
        : value;
      this.items = response.content;
      this.totalPages = response.page?.totalPages ?? response.totalPages;
      this.loading = false;
      this.cdr.markForCheck();
    };
    const fail = (e: HttpErrorResponse) => this.fail(e.status);
    if (this.routeKey === 'public/tags') this.api.tags(this.page).subscribe({ next, error: fail });
    else
      this.api.list({ page: this.page, ...(tagId ? { tagId } : {}) }).subscribe({
        next,
        error: fail,
      });
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
      this.page--;
      this.read();
    }
  }
  nextPage() {
    if (this.page + 1 < this.totalPages) {
      this.page++;
      this.read();
    }
  }
  goToPage(targetPage: number) {
    if (targetPage >= 0 && targetPage < this.totalPages && targetPage !== this.page) {
      this.page = targetPage;
      this.read();
    }
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
