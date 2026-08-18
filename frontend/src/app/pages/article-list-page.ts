import { CommonModule } from '@angular/common';
import {
  ChangeDetectionStrategy,
  ChangeDetectorRef,
  Component,
  ElementRef,
  OnInit,
  ViewChild,
  inject,
} from '@angular/core';
import { Router, RouterLink } from '@angular/router';
import { Article, ArticleApi } from '../core/api';
import { Auth } from '../core/auth';
import { Language } from '../core/language';
import {
  getPageNumbers,
  isPageSize,
  PAGE_SIZE,
  PAGE_SIZE_OPTIONS,
  PageSize,
} from '../core/pagination';
import { AppShell } from '../layouts/app-shell';
import { ArticleManagementList } from './article-management-list/article-management-list';
import { ManagementRow } from './article-management-list/article-management-list';

@Component({
  standalone: true,
  imports: [CommonModule, RouterLink, AppShell, ArticleManagementList],
  templateUrl: './article-list-page.html',
  styleUrl: './admin-pages.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ArticleListPage implements OnInit {
  @ViewChild('confirmDialog') confirmDialog?: ElementRef<HTMLDialogElement>;
  readonly language = inject(Language);
  readonly auth = inject(Auth);
  private readonly api = inject(ArticleApi);
  private readonly router = inject(Router);
  private readonly cdr = inject(ChangeDetectorRef);
  items: Article[] = [];
  page = 0;
  pageSize: PageSize = PAGE_SIZE;
  readonly pageSizeOptions = PAGE_SIZE_OPTIONS;
  totalPages = 0;
  searchTitle = '';
  loading = false;
  error = '';
  modalState: { title: string; message: string; confirmText: string; action: () => void } | null =
    null;
  private triggerElement: HTMLElement | null = null;

  ngOnInit() {
    this.read();
  }
  canManageArticle = (row: ManagementRow) =>
    this.auth.user?.role === 'ADMIN' || ('owner' in row && row.owner === this.auth.user?.id);
  open(row: ManagementRow) {
    void this.router.navigate(['/articles', row.id, 'edit']);
  }
  search(value: string) {
    this.searchTitle = value;
    this.page = 0;
    this.read();
  }
  changePageSize(value: number) {
    if (this.loading || !isPageSize(value) || value === this.pageSize) return;
    this.pageSize = value;
    this.page = 0;
    this.read();
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
  goToPage(page: number) {
    if (page >= 0 && page < this.totalPages && page !== this.page) {
      this.page = page;
      this.read();
    }
  }
  get pageNumbers() {
    return getPageNumbers(this.page, this.totalPages);
  }
  deleteArticle(article: ManagementRow, trigger?: EventTarget | null) {
    this.modalState = {
      title: this.language.t.delete,
      message: this.language.t.confirmDelete.replace('{title}', String(article.title)),
      confirmText: this.language.t.delete,
      action: () => this.executeDelete(article),
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
  private executeDelete(article: ManagementRow) {
    this.loading = true;
    this.api.delete(String(article.id)).subscribe({
      next: () => {
        this.message = this.language.t.deleteSuccess.replace('{title}', String(article.title));
        this.deletedSuccess = true;
        this.read();
      },
      error: (e) => this.fail(e.status),
    });
  }
  retry() {
    this.read();
  }
  private read() {
    this.loading = true;
    this.error = '';
    this.api
      .list({
        page: this.page,
        size: this.pageSize,
        ...(this.searchTitle ? { title: this.searchTitle } : {}),
      })
      .subscribe({
        next: (result) => {
          this.items = result.content;
          this.totalPages = result.page?.totalPages ?? result.totalPages;
          this.loading = false;
          this.cdr.markForCheck();
        },
        error: (e) => this.fail(e.status),
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
  message = '';
  deletedSuccess = false;
}
