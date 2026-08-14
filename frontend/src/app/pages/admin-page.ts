import { CommonModule } from '@angular/common';
import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import {
  ChangeDetectionStrategy,
  ChangeDetectorRef,
  Component,
  ElementRef,
  OnInit,
  ViewChild,
  inject,
} from '@angular/core';
import { CanDeactivateFn } from '@angular/router';
import { FormBuilder, FormsModule, ReactiveFormsModule, Validators } from '@angular/forms';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { Auth } from '../core/auth';
import { Language } from '../core/language';
import { AppShell } from '../layouts/app-shell';
import { AdminUserManagement } from './admin-user-management/admin-user-management';
import { ArticleManagementList } from './article-management-list/article-management-list';
import { getPageNumbers } from '../core/pagination';

type Row = Record<string, unknown>;
type Method = 'DELETE' | 'GET' | 'PATCH' | 'POST' | 'PUT';

const fields: Record<string, string[]> = {
  '/articles/new': ['title', 'content', 'status', 'tagNames'],
  '/articles/:id/edit': ['title', 'content', 'status', 'tagNames', 'version'],
  '/admin/invitations': ['email'],
  '/admin/settings/password': ['value'],
};

@Component({
  selector: 'app-admin-page',
  standalone: true,
  imports: [
    CommonModule,
    FormsModule,
    ReactiveFormsModule,
    RouterLink,
    ArticleManagementList,
    AdminUserManagement,
    AppShell,
  ],
  templateUrl: './admin-page.html',
  styleUrl: './admin-page.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class AdminPage implements OnInit {
  @ViewChild('permanentDialog') permanentDialog?: ElementRef<HTMLDialogElement>;
  readonly auth = inject(Auth);
  readonly language = inject(Language);
  private readonly http = inject(HttpClient);
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);
  private readonly fb = inject(FormBuilder);
  private readonly cdr = inject(ChangeDetectorRef);
  routeKey = '';
  form = this.fb.group<Record<string, never>>({});
  items: Row[] = [];
  page = 0;
  totalPages = 0;
  searchTitle = '';
  loading = false;
  submitAttempted = false;
  error = '';
  message = '';
  editorAllowed = false;
  availableTags: { id: string; name: string }[] = [];
  selectedTagIds: Set<string> = new Set();
  currentMinimum: number | null = null;
  deletedSuccess = false;
  selectedDeletedArticle: Row | null = null;
  modalState: {
    title: string;
    message: string;
    confirmText: string;
    isDanger: boolean;
    action: () => void;
  } | null = null;
  private originalUsers: Map<unknown, Row> = new Map();
  readonly confirmDelete = () => true;

  ngOnInit() {
    this.routeKey = this.route.snapshot.routeConfig?.path ?? '';
    const routeFields = fields[`/${this.routeKey}`] ?? [];
    if (routeFields.length) {
      this.make(routeFields);
    }
    if (this.routeKey === 'articles/:id/edit') {
      this.loadAvailableTags();
      this.loadEditor();
    } else if (this.routeKey === 'articles/new') {
      this.loadAvailableTags();
    } else {
      this.read();
    }
  }

  retry() {
    this.error = '';
    this.read();
  }

  confirmMessage(row: Row) {
    return this.language.t.confirmDelete.replace('{title}', String(row['title'] ?? ''));
  }
  private triggerElement: HTMLElement | null = null;
  confirmPermanentDeleteMessage(row: Row) {
    return this.language.t.confirmPermanentDelete.replace('{title}', String(row['title'] ?? ''));
  }
  openModal(
    state: {
      title: string;
      message: string;
      confirmText: string;
      isDanger: boolean;
      action: () => void;
    },
    trigger?: EventTarget | null,
  ) {
    this.modalState = state;
    if (trigger instanceof HTMLElement) {
      this.triggerElement = trigger;
    }
    this.cdr.markForCheck();
    this.permanentDialog?.nativeElement.showModal?.();
  }
  closeModal() {
    this.permanentDialog?.nativeElement.close?.();
    this.modalState = null;
    this.selectedDeletedArticle = null;
    this.cdr.markForCheck();
    if (this.triggerElement) {
      this.triggerElement.focus();
      this.triggerElement = null;
    }
  }
  confirmModal() {
    const action = this.modalState?.action;
    this.closeModal();
    if (action) {
      action();
    }
  }
  openPermanentDeleteDialog(row: Row, trigger?: EventTarget | null) {
    this.selectedDeletedArticle = row;
    this.openModal(
      {
        title: this.language.t.permanentDelete,
        message: this.confirmPermanentDeleteMessage(row),
        confirmText: this.language.t.permanentDelete,
        isDanger: true,
        action: () => this.executePermanentDelete(row),
      },
      trigger,
    );
  }
  closePermanentDeleteDialog() {
    this.closeModal();
  }
  confirmPermanentDelete() {
    if (this.selectedDeletedArticle) {
      this.executePermanentDelete(this.selectedDeletedArticle);
    }
  }
  executePermanentDelete(target: Row) {
    this.loading = true;
    this.http.delete(`/api/v1/articles/deleted/${target['id']}`).subscribe({
      next: () => {
        this.loading = false;
        this.error = '';
        const title = String(target['title'] ?? '');
        this.message = this.language.t.permanentDeleteSuccess.replace('{title}', title);
        this.deletedSuccess = false;
        this.read();
      },
      error: (e: HttpErrorResponse) => this.fail(e.status),
    });
  }
  fieldLabel(field: string) {
    return (this.language.t.field as Record<string, string>)[field] ?? field;
  }
  fieldError(field: string) {
    return this.form.get(field)?.hasError('required')
      ? this.language.t.required
      : this.language.t.error;
  }
  fieldHasError(field: string) {
    const control = this.form.get(field);
    return !!control && control.invalid && (control.touched || this.submitAttempted);
  }
  roleLabel(role: unknown) {
    return (
      this.language.t.roles[String(role) as keyof typeof this.language.t.roles] ??
      String(role ?? '')
    );
  }
  statusLabel(status: unknown) {
    return (
      this.language.t.statuses[String(status) as keyof typeof this.language.t.statuses] ??
      String(status ?? '')
    );
  }
  canManageArticle = (row: Row) =>
    this.auth.user?.role === 'ADMIN' ||
    row['ownerId'] === this.auth.user?.id ||
    row['owner'] === this.auth.user?.id;
  canManageUser = (row: Row) => row['id'] !== this.auth.user?.id;
  toggleTag(id: string, event: Event) {
    const input = event.target as HTMLInputElement;
    if (input.checked) {
      this.selectedTagIds.add(id);
    } else {
      this.selectedTagIds.delete(id);
    }
    this.form.markAsDirty();
    this.cdr.markForCheck();
  }

  submit() {
    if (this.routeKey === 'articles/:id/edit' && !this.editorAllowed) return;
    this.submitAttempted = true;
    this.message = '';
    this.deletedSuccess = false;
    if (this.form.invalid) {
      this.form.markAllAsTouched();
      return;
    }
    this.loading = true;
    this.requestFor(this.payload(this.form.getRawValue() as Row)).subscribe({
      next: () => this.done(),
      error: (e: HttpErrorResponse) => this.fail(e.status),
    });
  }
  open(row: Row) {
    void this.router.navigate(['/articles', row['id'], 'edit']);
  }
  search(value?: string) {
    if (value !== undefined) this.searchTitle = value;
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
  goToPage(targetPage: number) {
    if (targetPage >= 0 && targetPage < this.totalPages && targetPage !== this.page) {
      this.page = targetPage;
      this.read();
    }
  }
  get pageNumbers(): number[] {
    return getPageNumbers(this.page, this.totalPages);
  }
  deleteArticle(row: Row, trigger?: EventTarget | null) {
    this.openModal(
      {
        title: this.language.t.delete,
        message: this.confirmMessage(row),
        confirmText: this.language.t.delete,
        isDanger: true,
        action: () => this.executeDeleteArticle(row),
      },
      trigger,
    );
  }
  executeDeleteArticle(row: Row) {
    this.loading = true;
    this.http.delete(`/api/v1/articles/${row['id']}`).subscribe({
      next: () => {
        this.loading = false;
        this.error = '';
        const title = String(row['title'] ?? '');
        this.message = this.language.t.deleteSuccess.replace('{title}', title);
        this.deletedSuccess = true;
        this.read();
      },
      error: (e: HttpErrorResponse) => this.fail(e.status),
    });
  }
  restoreArticle(row: Row) {
    this.action('POST', `/api/v1/articles/${row['id']}/restore`);
  }
  updateUser(row: Row) {
    const current = this.items.find((item) => item['id'] === row['id']) ?? row;
    const original = this.originalUsers.get(current['id']);
    this.loading = true;
    this.http
      .patch<Row>(`/api/v1/admin/users/${current['id']}`, {
        role: current['role'],
        enabled: current['enabled'],
      })
      .subscribe({
        next: () => {
          this.loading = false;
          if (original) {
            original['role'] = current['role'];
            original['enabled'] = current['enabled'];
          }
          this.cdr.markForCheck();
        },
        error: (e: HttpErrorResponse) => {
          if (original) {
            this.replaceUser(current, { role: original['role'], enabled: original['enabled'] });
          }
          this.fail(e.status);
        },
      });
  }
  updateUserRole(change: { row: Row; value: unknown }) {
    const current = this.items.find((item) => item['id'] === change.row['id']) ?? change.row;
    const updated = { ...current, role: change.value };
    this.replaceUser(change.row, { role: change.value });
    this.updateUser(updated);
  }
  updateUserEnabled(change: { row: Row; value: unknown }) {
    this.replaceUser(change.row, { enabled: change.value });
  }
  toggleUserEnabled(row: Row, trigger?: EventTarget | null) {
    const current = this.items.find((item) => item['id'] === row['id']) ?? row;
    if (current['enabled'] === true) {
      const name = String(current['displayName'] || current['email'] || current['id']);
      this.openModal(
        {
          title: this.language.t.disabled,
          message: this.language.t.confirmDisable.replace('{name}', name),
          confirmText: this.language.t.disabled,
          isDanger: true,
          action: () => {
            const updated = { ...current, enabled: false };
            this.replaceUser(current, { enabled: false });
            this.updateUser(updated);
          },
        },
        trigger,
      );
      return;
    }
    const updated = { ...current, enabled: true };
    this.replaceUser(current, { enabled: true });
    this.updateUser(updated);
  }
  private replaceUser(row: Row, changes: Row) {
    this.items = this.items.map((item) =>
      item['id'] === row['id'] ? { ...item, ...changes } : item,
    );
    this.cdr.markForCheck();
  }
  private read() {
    this.loading = true;
    this.error = '';
    const params: Record<string, string | number> = { page: this.page };
    if (this.routeKey === 'articles' && this.searchTitle) params['title'] = this.searchTitle;
    if (this.routeKey === 'admin/settings/password') {
      this.http.get<Row>('/api/v1/admin/settings/password-minimum-length').subscribe({
        next: (val) => {
          if (val && typeof val['value'] === 'number') {
            this.currentMinimum = val['value'];
            this.cdr.markForCheck();
          }
        },
        error: () => {},
      });
    }
    this.http.get<Row[] | Row>(this.readEndpoint(), { params }).subscribe({
      next: (value) => {
        const page = this.pageResponse(value);
        this.items = page.content;
        if (this.routeKey === 'admin/users') {
          this.originalUsers = new Map(this.items.map((u) => [u['id'], { ...u }]));
        }
        this.totalPages = page.totalPages;
        this.loading = false;
        this.cdr.markForCheck();
      },
      error: (e: HttpErrorResponse) => this.fail(e.status),
    });
  }
  private loadAvailableTags() {
    this.http.get<Row[] | Row>('/api/v1/public/tags?size=100').subscribe({
      next: (res) => {
        const list = this.pageResponse(res).content as { id: string; name: string }[];
        this.availableTags = list;
        this.cdr.markForCheck();
      },
      error: () => {},
    });
  }
  private loadEditor() {
    this.loading = true;
    this.http.get<Row>(`/api/v1/articles/${this.route.snapshot.paramMap.get('id')}`).subscribe({
      next: (article) => {
        if (!this.canManageArticle(article)) return this.fail(403);
        this.editorAllowed = true;
        this.form.patchValue({
          ...article,
          tagNames: Array.isArray(article['tagNames'])
            ? article['tagNames'].join(', ')
            : article['tagNames'],
        });
        this.selectedTagIds = new Set(
          Array.isArray(article['tagIds']) ? article['tagIds'].map(String) : [],
        );
        this.loading = false;
        this.cdr.markForCheck();
      },
      error: (e: HttpErrorResponse) => this.fail(e.status),
    });
  }
  private pageResponse(value: Row[] | Row) {
    if (Array.isArray(value)) return { content: value, totalPages: value.length ? 1 : 0 };
    if (Array.isArray(value['content']))
      return {
        content: value['content'] as Row[],
        totalPages: Number(
          (value['page'] as Row | undefined)?.['totalPages'] ?? value['totalPages'] ?? 0,
        ),
      };
    return { content: [value], totalPages: 1 };
  }
  private readEndpoint() {
    const id = this.route.snapshot.paramMap.get('id');
    return (
      (
        {
          articles: '/api/v1/articles',
          'articles/deleted': '/api/v1/articles/deleted',
          'admin/users': '/api/v1/admin/users',
          'admin/invitations': '/api/v1/admin/invitations',
          'admin/settings/password': '/api/v1/admin/settings/password-minimum-length/history',
        } as Record<string, string>
      )[this.routeKey] ?? `/api/v1/articles/${id}`
    );
  }
  private action(method: Method, url: string, body?: Row) {
    this.loading = true;
    this.http
      .request(method, url, { body })
      .subscribe({ next: () => this.read(), error: (e: HttpErrorResponse) => this.fail(e.status) });
  }
  private requestFor(value: Row) {
    const id = this.route.snapshot.paramMap.get('id');
    const specs: Record<string, { method: Method; url: string; body: Row }> = {
      'articles/new': { method: 'POST', url: '/api/v1/articles', body: value },
      'articles/:id/edit': { method: 'PUT', url: `/api/v1/articles/${id}`, body: value },
      'admin/invitations': { method: 'POST', url: '/api/v1/admin/invitations', body: value },
      'admin/settings/password': {
        method: 'PUT',
        url: '/api/v1/admin/settings/password-minimum-length',
        body: { value: Number(value['value']) },
      },
    };
    const spec = specs[this.routeKey];
    return this.http.request(spec.method, spec.url, { body: spec.body });
  }
  private payload(value: Row) {
    const result = { ...value };
    if (this.routeKey.startsWith('articles/')) {
      result['status'] = String(result['status'] ?? 'DRAFT').toUpperCase();
      result['tagIds'] = Array.from(this.selectedTagIds);
      if (typeof result['tagNames'] === 'string')
        result['tagNames'] = result['tagNames']
          .split(',')
          .map((tag) => tag.trim())
          .filter(Boolean);
    }
    return result;
  }
  private make(names: string[]) {
    this.form = this.fb.group(
      Object.fromEntries(
        names.map((name) => [
          name,
          [
            '',
            name === 'email'
              ? [Validators.required, Validators.email]
              : name === 'value'
                ? [Validators.required, Validators.min(8), Validators.max(128)]
                : ['title', 'content', 'status', 'version'].includes(name)
                  ? Validators.required
                  : [],
          ],
        ]),
      ),
    );
  }
  private done() {
    this.loading = false;
    this.error = '';
    this.message = this.language.t.success;
    this.cdr.markForCheck();
    this.form.markAsPristine();
    if (this.routeKey === 'articles/new' || this.routeKey === 'articles/:id/edit') {
      void this.router.navigateByUrl('/articles');
    }
  }
  get title() {
    this.language.lang();
    const labels: Record<string, string> = {
      articles: this.language.t.nav.articles,
      'articles/new': this.language.t.newArticle,
      'articles/deleted': this.language.t.nav.deletedArticles,
      'admin/users': this.language.t.nav.users,
      'admin/invitations': this.language.t.nav.invitations,
      'admin/settings/password': this.language.t.nav.password,
    };
    return labels[this.routeKey] ?? this.language.t.nav.articles;
  }
  private fail(status: number) {
    this.loading = false;
    this.message = '';
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

export const canLeaveArticle: CanDeactivateFn<AdminPage> = (component) =>
  !component.form.dirty || window.confirm('離開此頁面？未儲存的變更將會遺失。');
