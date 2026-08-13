import { CommonModule } from '@angular/common';
import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import {
  ChangeDetectionStrategy,
  ChangeDetectorRef,
  Component,
  OnInit,
  inject,
} from '@angular/core';
import { FormBuilder, FormsModule, ReactiveFormsModule, Validators } from '@angular/forms';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { Auth } from '../core/auth';
import { Language } from '../core/language';
import { AppShell } from '../layouts/app-shell';
import { AdminUserManagement } from './admin-user-management/admin-user-management';
import { ArticleManagementList } from './article-management-list/article-management-list';

type Row = Record<string, unknown>;
type Method = 'DELETE' | 'GET' | 'PATCH' | 'POST' | 'PUT';

const fields: Record<string, string[]> = {
  '/admin/articles/new': ['title', 'content', 'status', 'tagNames'],
  '/admin/articles/:id': ['title', 'content', 'status', 'tagNames', 'version'],
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
  preservedTagIds: unknown[] = [];
  removedTagIds: unknown[] = [];
  readonly confirmDelete = (row: Row) => window.confirm(this.confirmMessage(row));

  ngOnInit() {
    this.routeKey = this.route.snapshot.routeConfig?.path ?? '';
    const routeFields = fields[`/${this.routeKey}`] ?? [];
    if (routeFields.length) {
      this.make(routeFields);
    }
    if (this.routeKey === 'admin/articles/:id') {
      this.loadEditor();
    } else if (this.routeKey !== 'admin/articles/new') {
      this.read();
    }
  }

  confirmMessage(row: Row) {
    return this.language.t.confirmDelete.replace('{title}', String(row['title'] ?? ''));
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
  visibleTagIds() {
    return this.preservedTagIds.filter((id) => !this.removedTagIds.includes(id));
  }
  removeTag(id: unknown) {
    this.removedTagIds = [...this.removedTagIds, id];
  }

  submit() {
    if (this.routeKey === 'admin/articles/:id' && !this.editorAllowed) return;
    this.submitAttempted = true;
    this.message = '';
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
    void this.router.navigate(['/admin/articles', row['id']]);
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
  deleteArticle(row: Row) {
    if (this.confirmDelete(row)) this.action('DELETE', `/api/v1/articles/${row['id']}`);
  }
  restoreArticle(row: Row) {
    this.action('POST', `/api/v1/articles/${row['id']}/restore`);
  }
  updateUser(row: Row) {
    const current = this.items.find((item) => item['id'] === row['id']) ?? row;
    this.action('PATCH', `/api/v1/admin/users/${current['id']}`, {
      role: current['role'],
      enabled: current['enabled'],
    });
  }
  updateUserRole(change: { row: Row; value: unknown }) {
    this.replaceUser(change.row, { role: change.value });
  }
  updateUserEnabled(change: { row: Row; value: unknown }) {
    this.replaceUser(change.row, { enabled: change.value });
  }
  toggleUserEnabled(row: Row) {
    const updated = { ...row, enabled: !row['enabled'] };
    this.replaceUser(row, { enabled: updated['enabled'] });
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
    const params: Record<string, string | number> = { page: this.page };
    if (this.routeKey === 'admin/articles' && this.searchTitle) params['title'] = this.searchTitle;
    this.http.get<Row[] | Row>(this.readEndpoint(), { params }).subscribe({
      next: (value) => {
        const page = this.pageResponse(value);
        this.items = page.content;
        this.totalPages = page.totalPages;
        this.loading = false;
        this.cdr.markForCheck();
      },
      error: (e: HttpErrorResponse) => this.fail(e.status),
    });
  }
  private loadEditor() {
    this.loading = true;
    this.http.get<Row>(`/api/v1/articles/${this.route.snapshot.paramMap.get('id')}`).subscribe({
      next: (article) => {
        if (this.auth.user?.role === 'AUTHOR' && article['ownerId'] !== this.auth.user.id)
          return this.fail(403);
        this.editorAllowed = true;
        this.form.patchValue({
          ...article,
          tagNames: Array.isArray(article['tagNames'])
            ? article['tagNames'].join(', ')
            : article['tagNames'],
        });
        this.preservedTagIds = Array.isArray(article['tagIds']) ? article['tagIds'] : [];
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
          'admin/articles': '/api/v1/articles',
          'admin/articles/deleted': '/api/v1/articles/deleted',
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
      'admin/articles/new': { method: 'POST', url: '/api/v1/articles', body: value },
      'admin/articles/:id': { method: 'PUT', url: `/api/v1/articles/${id}`, body: value },
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
    if (this.routeKey.startsWith('admin/articles/')) {
      result['status'] = String(result['status'] ?? 'DRAFT').toUpperCase();
      const tags = this.form.get('tagNames');
      result['tagIds'] =
        tags?.dirty && !String(tags.value ?? '').trim()
          ? []
          : (Array.isArray(result['tagIds']) ? result['tagIds'] : this.preservedTagIds).filter(
              (id) => !this.removedTagIds.includes(id),
            );
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
              : ['title', 'content', 'status', 'version', 'value'].includes(name)
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
    if (this.routeKey === 'admin/articles/new') void this.router.navigateByUrl('/admin/articles');
  }
  get title() {
    this.language.lang();
    const labels: Record<string, string> = {
      'admin/articles': this.language.t.nav.articles,
      'admin/articles/new': this.language.t.newArticle,
      'admin/articles/deleted': this.language.t.nav.deletedArticles,
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
