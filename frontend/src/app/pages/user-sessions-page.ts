import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import {
  ChangeDetectionStrategy,
  ChangeDetectorRef,
  Component,
  OnInit,
  inject,
} from '@angular/core';
import { Language } from '../core/language';
import { getPageNumbers } from '../core/pagination';
import { AccountLayout } from '../layouts/account-layout';
import { AppShell } from '../layouts/app-shell';

type Row = Record<string, unknown>;

@Component({
  selector: 'app-user-sessions-page',
  standalone: true,
  imports: [AppShell, AccountLayout],
  templateUrl: './user-sessions-page.html',
  styleUrl: './account-use-case.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class UserSessionsPage implements OnInit {
  readonly language = inject(Language);
  private readonly http = inject(HttpClient);
  private readonly cdr = inject(ChangeDetectorRef);
  loading = false;
  error = '';
  items: Row[] = [];
  page = 0;
  readonly pageSize = 10;
  totalPages = 0;

  get title() {
    this.language.lang();
    return this.language.t.nav.sessions;
  }

  ngOnInit() {
    this.readSessions();
  }

  get pagedSessions(): Row[] {
    const start = this.page * this.pageSize;
    return this.items.slice(start, start + this.pageSize);
  }

  previousPage() {
    if (this.page > 0) {
      this.page--;
      this.cdr.markForCheck();
    }
  }

  nextPage() {
    if (this.page + 1 < this.totalPages) {
      this.page++;
      this.cdr.markForCheck();
    }
  }

  goToPage(targetPage: number) {
    if (targetPage >= 0 && targetPage < this.totalPages && targetPage !== this.page) {
      this.page = targetPage;
      this.cdr.markForCheck();
    }
  }

  get pageNumbers(): number[] {
    return getPageNumbers(this.page, this.totalPages);
  }

  revoke(row: Row) {
    this.loading = true;
    this.http.delete(`/api/v1/auth/sessions/${row['id']}`).subscribe({
      next: () => this.readSessions(),
      error: (error: HttpErrorResponse) => this.fail(error.status),
    });
  }

  retrySessions() {
    this.error = '';
    this.readSessions();
  }

  private readSessions() {
    this.loading = true;
    this.error = '';
    this.http.get<Row[] | Row>('/api/v1/auth/sessions', { params: { page: 0 } }).subscribe({
      next: (res) => {
        this.items = Array.isArray(res)
          ? res
          : Array.isArray(res?.['content'])
            ? (res['content'] as Row[])
            : [];
        this.totalPages = Math.ceil(this.items.length / this.pageSize) || 1;
        if (this.page >= this.totalPages) this.page = Math.max(0, this.totalPages - 1);
        this.loading = false;
        this.cdr.markForCheck();
      },
      error: (error: HttpErrorResponse) => this.fail(error.status),
    });
  }

  private fail(status: number) {
    this.loading = false;
    this.error =
      status === 401
        ? this.language.t.unauthorized
        : status === 403
          ? this.language.t.forbidden
          : status === 404
            ? this.language.t.notFound
            : status === 409
              ? this.language.t.conflict
              : this.language.t.error;
    this.cdr.markForCheck();
  }
}
