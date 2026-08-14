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
import { getPageNumbers } from '../core/pagination';

@Component({
  standalone: true,
  imports: [CommonModule, AppShell],
  templateUrl: './deleted-articles-page.html',
  styleUrl: './admin-page.scss',
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
  totalPages = 0;
  loading = false;
  error = '';
  message = '';
  modalState: { title: string; message: string; confirmText: string; action: () => void } | null =
    null;
  private triggerElement: HTMLElement | null = null;
  ngOnInit() {
    this.read();
  }
  restore(article: Article) {
    this.api.restore(article.id).subscribe({ next: () => this.read(), error: () => this.fail() });
  }
  purge(article: Article, trigger?: EventTarget | null) {
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
    this.api.purge(article.id).subscribe({
      next: () => {
        this.message = this.language.t.permanentDeleteSuccess.replace('{title}', article.title);
        this.read();
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
  goToPage(page: number) {
    if (page >= 0 && page < this.totalPages && page !== this.page) {
      this.page = page;
      this.read();
    }
  }
  get pageNumbers() {
    return getPageNumbers(this.page, this.totalPages);
  }
  private read() {
    this.loading = true;
    this.api.deleted(this.page).subscribe({
      next: (r) => {
        this.items = r.content;
        this.totalPages = r.page?.totalPages ?? r.totalPages;
        this.loading = false;
        this.cdr.markForCheck();
      },
      error: (e: HttpErrorResponse) => this.fail(e.status),
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
