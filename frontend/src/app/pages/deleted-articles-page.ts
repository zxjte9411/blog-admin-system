import { CommonModule } from '@angular/common';
import { HttpErrorResponse } from '@angular/common/http';
import {
  ChangeDetectionStrategy,
  ChangeDetectorRef,
  Component,
  ElementRef,
  OnInit,
  inject,
  ViewChild,
} from '@angular/core';
import { Article, ArticleApi } from '../core/api';
import { Language } from '../core/language';
import { Auth } from '../core/auth';
import { AppShell } from '../layouts/app-shell';
import {
  getPageNumbers,
  isPageSize,
  PAGE_SIZE,
  PAGE_SIZE_OPTIONS,
  PageSize,
} from '../core/pagination';

type PendingAction = 'restore' | 'purge';

@Component({
  standalone: true,
  imports: [CommonModule, AppShell],
  templateUrl: './deleted-articles-page.html',
  styleUrl: './admin-pages.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class DeletedArticlesPage implements OnInit {
  @ViewChild('confirmDialog') confirmDialog?: ElementRef<HTMLDialogElement>;
  readonly language = inject(Language);
  readonly auth = inject(Auth);
  private readonly api = inject(ArticleApi);
  private readonly cdr = inject(ChangeDetectorRef);
  items: Article[] = [];
  page = 0;
  pageSize: PageSize = PAGE_SIZE;
  readonly pageSizeOptions = PAGE_SIZE_OPTIONS;
  totalPages = 0;
  loading = false;
  error = '';
  message = '';
  pendingActions = new Map<string, PendingAction>();
  modalState: { title: string; message: string; confirmText: string; action: () => void } | null =
    null;
  private triggerElement: HTMLElement | null = null;
  ngOnInit() {
    this.read();
  }
  restore(article: Article) {
    if (this.isPending(article)) return;
    this.setPending(article.id, 'restore');
    this.api.restore(article.id).subscribe({
      next: () => this.read(article.id),
      error: () => {
        this.clearPending(article.id);
        this.fail();
      },
    });
  }
  purge(article: Article, trigger?: EventTarget | null) {
    if (this.isPending(article)) return;
    this.modalState = {
      title: this.language.t.permanentDelete,
      message: this.language.t.confirmPermanentDelete.replace('{title}', article.title),
      confirmText: this.language.t.permanentDelete,
      action: () => this.executePurge(article),
    };
    if (trigger instanceof HTMLElement) this.triggerElement = trigger;
    this.cdr.markForCheck();
    this.confirmDialog?.nativeElement.showModal?.();
  }
  closeModal() {
    this.confirmDialog?.nativeElement.close?.();
    this.modalState = null;
    this.cdr.markForCheck();
    if (this.triggerElement) {
      this.triggerElement.focus();
      this.triggerElement = null;
    }
  }
  confirmModal() {
    const action = this.modalState?.action;
    this.closeModal();
    action?.();
  }
  private executePurge(article: Article) {
    this.setPending(article.id, 'purge');
    this.api.purge(article.id).subscribe({
      next: () => {
        this.message = this.language.t.permanentDeleteSuccess.replace('{title}', article.title);
        this.read(article.id);
      },
      error: (e: HttpErrorResponse) => {
        this.clearPending(article.id);
        this.fail(e.status);
      },
    });
  }
  previousPage() {
    if (!this.loading && this.page > 0) {
      this.page--;
      this.read();
    }
  }
  nextPage() {
    if (!this.loading && this.page + 1 < this.totalPages) {
      this.page++;
      this.read();
    }
  }
  goToPage(page: number) {
    if (!this.loading && page >= 0 && page < this.totalPages && page !== this.page) {
      this.page = page;
      this.read();
    }
  }
  changePageSize(value: number) {
    if (this.loading || !isPageSize(value) || value === this.pageSize) return;
    this.pageSize = value;
    this.page = 0;
    this.read();
  }
  get pageNumbers() {
    return getPageNumbers(this.page, this.totalPages);
  }
  isPending(article: Article) {
    return this.pendingActions.has(article.id);
  }
  pendingAction(article: Article) {
    return this.pendingActions.get(article.id);
  }
  private setPending(id: string, action: PendingAction) {
    this.pendingActions = new Map(this.pendingActions).set(id, action);
    this.cdr.markForCheck();
  }
  private clearPending(id: string) {
    if (!this.pendingActions.has(id)) return;
    this.pendingActions = new Map(this.pendingActions);
    this.pendingActions.delete(id);
    this.cdr.markForCheck();
  }
  private read(releasePendingId?: string) {
    this.loading = true;
    this.error = '';
    this.cdr.markForCheck();
    this.api.deleted(this.page, this.pageSize).subscribe({
      next: (r) => {
        this.items = r.content;
        this.totalPages = r.page?.totalPages ?? r.totalPages;
        if (releasePendingId) this.clearPending(releasePendingId);
        this.loading = false;
        this.cdr.markForCheck();
      },
      error: (e: HttpErrorResponse) => {
        if (releasePendingId) this.clearPending(releasePendingId);
        this.fail(e.status);
      },
    });
  }
  private fail(status = 0) {
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
