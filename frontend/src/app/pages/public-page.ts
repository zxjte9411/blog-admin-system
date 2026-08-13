import { CommonModule } from '@angular/common';
import { HttpClient, HttpErrorResponse } from '@angular/common/http';
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

type Row = Record<string, unknown>;

@Component({
  selector: 'app-public-page',
  standalone: true,
  imports: [CommonModule, RouterLink, AppShell],
  templateUrl: './public-page.html',
  styleUrl: './public-page.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class PublicPage implements OnInit {
  private readonly http = inject(HttpClient);
  private readonly route = inject(ActivatedRoute);
  private readonly cdr = inject(ChangeDetectorRef);
  readonly language = inject(Language);
  routeKey = '';
  items: Row[] = [];
  detail: Row | null = null;
  page = 0;
  totalPages = 0;
  loading = false;
  private errorKey: 'forbidden' | 'notFound' | 'error' | '' = '';

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

  private endpoint() {
    if (this.routeKey === 'public/tags') return '/api/v1/public/tags';
    const id = this.route.snapshot.paramMap.get('id');
    return id ? `/api/v1/public/articles/${id}` : '/api/v1/public/articles';
  }

  private read() {
    this.loading = true;
    const params: Record<string, string | number> = { page: this.page };
    const tagId = this.route.snapshot.queryParamMap.get('tagId');
    if (tagId && this.routeKey === 'public/articles') params['tagId'] = tagId;
    this.http.get<Row[] | Row>(this.endpoint(), { params }).subscribe({
      next: (value) => {
        const response = Array.isArray(value) ? { content: value, totalPages: 1 } : value;
        this.items = Array.isArray(response['content'])
          ? (response['content'] as Row[])
          : [response];
        this.totalPages = Number(
          (response['page'] as Row | undefined)?.['totalPages'] ?? response['totalPages'] ?? 0,
        );
        this.loading = false;
        this.cdr.markForCheck();
      },
      error: (e: HttpErrorResponse) => this.fail(e.status),
    });
  }

  private readDetail() {
    this.loading = true;
    this.http.get<Row>(this.endpoint()).subscribe({
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
  tagQuery(row: Row) {
    return { tagId: String(row['id']) };
  }
  private fail(status: number) {
    this.loading = false;
    this.errorKey = status === 403 ? 'forbidden' : status === 404 ? 'notFound' : 'error';
    this.cdr.markForCheck();
  }
}
