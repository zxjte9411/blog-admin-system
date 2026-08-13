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
import { ActivatedRoute, Router, RouterLink, RouterLinkActive } from '@angular/router';
import { Auth } from '../core/auth';
import { Language } from '../core/language';
import { ArticleManagementList } from './article-management-list/article-management-list';
import { AdminUserManagement } from './admin-user-management/admin-user-management';

type Row = Record<string, unknown>;
type Method = 'DELETE' | 'GET' | 'PATCH' | 'POST' | 'PUT';

const formFields: Record<string, string[]> = {
  '/login': ['email', 'password'],
  '/register': ['email', 'displayName', 'password', 'preferredLanguage'],
  '/verify-email': ['token'],
  '/verify/resend': ['email'],
  '/password-reset': ['email'],
  '/reset-password': ['password'],
  '/confirm-email': [],
  '/invite': ['displayName', 'password', 'preferredLanguage'],
  '/account/profile': ['displayName', 'preferredLanguage'],
  '/account/password': ['currentPassword', 'newPassword'],
  '/account/email': ['email'],
  '/admin/articles/new': ['title', 'content', 'status', 'tagNames'],
  '/admin/articles/:id': ['title', 'content', 'status', 'tagNames', 'version'],
  '/admin/invitations': ['email'],
  '/admin/settings/password': ['value'],
};

@Component({
  selector: 'app-portal',
  standalone: true,
  imports: [
    CommonModule,
    FormsModule,
    ReactiveFormsModule,
    RouterLink,
    RouterLinkActive,
    ArticleManagementList,
    AdminUserManagement,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  styleUrl: './portal.scss',
  templateUrl: './portal.html',
})
export class Portal implements OnInit {
  readonly auth = inject(Auth);
  readonly language = inject(Language);
  private readonly http = inject(HttpClient);
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);
  private readonly fb = inject(FormBuilder);
  private readonly cdr = inject(ChangeDetectorRef);

  routePath = '';
  routeKey = '';
  heading = '';
  fields: string[] = [];
  form = this.fb.group<Record<string, never>>({});
  items: Row[] = [];
  totalPages = 0;
  page = 0;
  searchTitle = '';
  loading = false;
  error = '';
  message = '';
  detail: Row | null = null;
  editorAllowed = false;
  preservedTagIds: unknown[] = [];
  removedTagIds: unknown[] = [];
  confirmDelete = (row: Row) => window.confirm(this.confirmMessage(row));

  readonly nav = [
    '/public/articles',
    '/public/tags',
    '/admin/articles',
    '/admin/articles/deleted',
    '/admin/users',
    '/admin/invitations',
    '/admin/settings/password',
    '/account/profile',
    '/account/password',
    '/account/email',
    '/account/sessions',
  ];

  readonly navGroups = [
    { label: 'public', links: ['/public/articles', '/public/tags'] },
    {
      label: 'manage',
      links: [
        '/admin/articles',
        '/admin/articles/deleted',
        '/admin/users',
        '/admin/invitations',
        '/admin/settings/password',
      ],
    },
    {
      label: 'account',
      links: ['/account/profile', '/account/password', '/account/email', '/account/sessions'],
    },
  ];

  navGroupLabel(group: string) {
    const labels =
      this.language.lang() === 'en'
        ? { public: 'Public content', manage: 'Management', account: 'Your account' }
        : { public: '公開內容', manage: '管理工作區', account: '個人設定' };
    return labels[group as keyof typeof labels] ?? group;
  }

  ngOnInit() {
    this.routePath = this.router.url.split('?')[0];
    this.routeKey = this.routeKey || this.route.snapshot.routeConfig?.path || '';
    this.heading = this.title();
    this.fields = formFields[`/${this.routeKey}`] ?? [];

    if (this.auth.token && !this.auth.user) {
      this.auth.load().subscribe((user) => {
        if (user) {
          this.language.usePreferred(user.preferredLanguage);
          this.heading = this.title();
          this.cdr.markForCheck();
        }
      });
    }

    if (this.routeKey === '**') {
      this.fail(404);
      return;
    }

    if (this.routeKey === 'forbidden') {
      this.fail(403);
      return;
    }

    if (this.routeKey === 'admin/articles/:id') {
      this.make(this.fields);
      this.loadEditor();
    } else if (this.routeKey === 'public/articles/:id') {
      this.loadPublicArticle();
    } else if (this.fields.length > 0 || this.routeKey === 'confirm-email') {
      this.make(this.fields);
      if (this.routeKey === 'verify-email') {
        this.form.patchValue({ token: this.route.snapshot.queryParamMap.get('token') ?? '' });
      }
      if (this.routeKey === 'admin/invitations' || this.routeKey === 'admin/settings/password') {
        this.read();
      } else if (this.routeKey === 'account/profile') {
        this.loadProfile();
      }
    } else {
      this.read();
    }
  }

  toggleLanguage() {
    const next = this.language.lang() === 'en' ? 'zh-TW' : 'en';
    if (this.auth.token && !this.auth.user) {
      this.language.usePreferred(next);
      this.auth.load().subscribe((user) => user && this.language.set(next));
    } else {
      this.language.set(next);
    }
    this.heading = this.title();
    if (this.routeKey === 'forbidden') this.error = this.language.t.forbidden;
    this.cdr.markForCheck();
  }

  navLabel(link: string) {
    if (link.includes('/public/tags')) return this.language.t.nav.tags;
    if (link.includes('/articles/deleted')) return this.language.t.nav.deletedArticles;
    if (link.includes('invitations')) return this.language.t.nav.invitations;
    if (link.includes('settings/password')) return this.language.t.nav.password;
    if (link.includes('users')) return this.language.t.nav.users;
    if (link.includes('articles')) return this.language.t.nav.articles;
    if (link.includes('profile')) return this.language.t.nav.profile;
    if (link.includes('account/password')) return this.language.t.field.password;
    if (link.includes('account/email')) return this.language.t.field.email;
    return this.language.t.nav.sessions;
  }

  canSeeNav(link: string) {
    if (link === '/public/articles' || link === '/public/tags') return true;
    if (!this.auth.user) return false;
    if (['/admin/users', '/admin/invitations', '/admin/settings/password'].includes(link)) {
      return this.auth.user.role === 'ADMIN';
    }
    return true;
  }

  confirmMessage(row: Row) {
    return this.language.t.confirmDelete.replace('{title}', String(row['title'] ?? ''));
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

  fieldLabel(field: string) {
    return (this.language.t.field as Record<string, string>)[field] ?? field;
  }

  removeTag(id: unknown) {
    this.removedTagIds = [...this.removedTagIds, id];
    this.cdr.markForCheck();
  }
  visibleTagIds() {
    return this.preservedTagIds.filter((id) => !this.removedTagIds.includes(id));
  }

  logout() {
    this.http.post('/api/v1/auth/logout', {}).subscribe(() => {
      this.auth.clear();
      void this.router.navigateByUrl('/login');
    });
  }

  submit() {
    if (this.routeKey === 'admin/articles/:id' && this.auth.user && !this.editorAllowed) return;
    if (this.form.invalid) {
      this.form.markAllAsTouched();
      return;
    }

    const value = this.form.getRawValue() as Row;
    const request = this.requestFor(value);

    this.loading = true;
    this.error = '';
    request.subscribe({
      next: (result) => this.done(result),
      error: (error: HttpErrorResponse) => this.fail(error.status),
    });
  }

  open(row: Row) {
    const id = String(row['id'] ?? '');

    if (id && this.routeKey === 'admin/articles') {
      void this.router.navigate(['/admin/articles', id]);
      return;
    }

    this.detail = row;
  }

  canManageArticle = (row: Row) => {
    return (
      this.auth.user?.role === 'ADMIN' ||
      row['ownerId'] === this.auth.user?.id ||
      row['owner'] === this.auth.user?.id
    );
  };

  canManageUser = (row: Row) => row['id'] !== this.auth.user?.id;

  publicArticleLink(row: Row) {
    return row['id'] ? ['/public/articles', row['id']] : null;
  }

  tagQuery(row: Row) {
    return { tagId: String(row['id']) };
  }

  get selectedTagId() {
    return this.route.snapshot.queryParamMap.get('tagId');
  }

  revoke(row: Row) {
    this.action('DELETE', `/api/v1/auth/sessions/${row['id']}`);
  }

  deleteArticle(row: Row) {
    if (!this.confirmDelete(row)) {
      return;
    }

    this.action('DELETE', `/api/v1/articles/${row['id']}`);
  }

  search(searchTitle?: string) {
    if (searchTitle !== undefined) {
      this.searchTitle = searchTitle;
    }
    this.page = 0;
    this.read();
  }

  previousPage() {
    if (this.page > 0) {
      this.page -= 1;
      this.read();
    }
  }

  nextPage() {
    if (this.page + 1 < this.totalPages) {
      this.page += 1;
      this.read();
    }
  }

  restoreArticle(row: Row) {
    this.action('POST', `/api/v1/articles/${row['id']}/restore`);
  }

  updateUser(row: Row) {
    this.action('PATCH', `/api/v1/admin/users/${row['id']}`, {
      role: row['role'],
      enabled: row['enabled'],
    });
  }

  toggleUserEnabled(row: Row) {
    row['enabled'] = !row['enabled'];
    this.updateUser(row);
  }

  private requestFor(value: Row) {
    if (this.routeKey === 'login') {
      return this.http.post<{ accessToken: string }>('/api/v1/auth/login', value);
    }

    const request = this.requestSpec(value);
    return this.http.request(request.method, request.url, { body: request.body });
  }

  private requestSpec(value: Row) {
    const token = this.route.snapshot.queryParamMap.get('token');
    const body = this.payload(value, token);
    const id = this.route.snapshot.paramMap.get('id');

    switch (this.routeKey) {
      case 'register':
        return { method: 'POST' as Method, url: '/api/v1/auth/registrations', body };
      case 'verify-email':
        return { method: 'POST' as Method, url: '/api/v1/auth/email-verifications', body };
      case 'verify/resend':
        return { method: 'POST' as Method, url: '/api/v1/auth/email-verifications/resend', body };
      case 'password-reset':
        return { method: 'POST' as Method, url: '/api/v1/auth/password-resets', body };
      case 'reset-password':
        return { method: 'POST' as Method, url: `/api/v1/auth/password-resets/${token}`, body };
      case 'confirm-email':
        return {
          method: 'POST' as Method,
          url: `/api/v1/auth/email-changes/${token}`,
          body: undefined,
        };
      case 'invite':
        return { method: 'POST' as Method, url: `/api/v1/auth/invitations/${token}/redeem`, body };
      case 'account/profile':
        return { method: 'PATCH' as Method, url: '/api/v1/account/profile', body };
      case 'account/password':
        return { method: 'PUT' as Method, url: '/api/v1/account/password', body };
      case 'account/email':
        return { method: 'POST' as Method, url: '/api/v1/account/email', body };
      case 'admin/articles/new':
        return { method: 'POST' as Method, url: '/api/v1/articles', body };
      case 'admin/articles/:id':
        return { method: 'PUT' as Method, url: `/api/v1/articles/${id}`, body };
      case 'admin/invitations':
        return { method: 'POST' as Method, url: '/api/v1/admin/invitations', body };
      case 'admin/settings/password':
        return {
          method: 'PUT' as Method,
          url: '/api/v1/admin/settings/password-minimum-length',
          body,
        };
      default:
        return { method: 'GET' as Method, url: this.readEndpoint(), body: undefined };
    }
  }

  private read() {
    this.loading = true;
    const params: Record<string, string | number> = {
      page: this.page,
    };

    if (this.routeKey === 'admin/articles' && this.searchTitle) {
      params['title'] = this.searchTitle;
    }
    if (this.routeKey === 'public/articles' && this.selectedTagId) {
      params['tagId'] = this.selectedTagId;
    }

    this.http.get<Row[] | Row>(this.readEndpoint(), { params }).subscribe({
      next: (value) => {
        const page = this.pageResponse(value);
        this.items = page.content;
        this.totalPages = page.totalPages;
        this.loading = false;
        this.cdr.markForCheck();
      },
      error: (error: HttpErrorResponse) => this.fail(error.status),
    });
  }

  loadEditor() {
    this.loading = true;
    this.http.get<Row>(`/api/v1/articles/${this.route.snapshot.paramMap.get('id')}`).subscribe({
      next: (article) => {
        if (this.auth.user?.role === 'AUTHOR' && article['owner'] !== this.auth.user.id) {
          this.fail(403);
          return;
        }
        this.editorAllowed = true;
        this.form.patchValue({
          ...article,
          tagNames: Array.isArray(article['tagNames'])
            ? article['tagNames'].join(', ')
            : article['tagNames'],
        });
        this.preservedTagIds = Array.isArray(article['tagIds']) ? article['tagIds'] : [];
        this.removedTagIds = [];
        this.loading = false;
        this.cdr.markForCheck();
      },
      error: (error: HttpErrorResponse) => this.fail(error.status),
    });
  }

  private loadProfile() {
    this.loading = true;
    this.http.get<Row>('/api/v1/account/me').subscribe({
      next: (user) => {
        this.auth.user = user as typeof this.auth.user;
        this.form.patchValue(user);
        this.language.usePreferred(user['preferredLanguage'] as 'zh-TW' | 'en');
        this.loading = false;
        this.cdr.markForCheck();
      },
      error: (error: HttpErrorResponse) => this.fail(error.status),
    });
  }

  private loadPublicArticle() {
    this.loading = true;
    this.http.get<Row>(this.readEndpoint()).subscribe({
      next: (article) => {
        this.detail = article;
        this.loading = false;
        this.cdr.markForCheck();
      },
      error: (error: HttpErrorResponse) => this.fail(error.status),
    });
  }

  private pageResponse(value: Row[] | Row) {
    if (Array.isArray(value)) {
      return { content: value, totalPages: value.length ? 1 : 0 };
    }

    if (Array.isArray(value['content'])) {
      return {
        content: value['content'] as Row[],
        totalPages: Number(
          (value['page'] as Row | undefined)?.['totalPages'] ?? value['totalPages'] ?? 0,
        ),
      };
    }

    return { content: [value], totalPages: 1 };
  }

  private readEndpoint() {
    const id = this.route.snapshot.paramMap.get('id');

    switch (this.routeKey) {
      case 'public/articles':
        return '/api/v1/public/articles';
      case 'public/articles/:id':
        return `/api/v1/public/articles/${id}`;
      case 'public/tags':
        return '/api/v1/public/tags';
      case 'account/profile':
        return '/api/v1/account/me';
      case 'admin/articles':
        return '/api/v1/articles';
      case 'admin/articles/deleted':
        return '/api/v1/articles/deleted';
      case 'admin/users':
        return '/api/v1/admin/users';
      case 'admin/settings/password':
        return '/api/v1/admin/settings/password-minimum-length/history';
      case 'account/sessions':
        return '/api/v1/auth/sessions';
      case 'admin/invitations':
        return '/api/v1/admin/invitations';
      default:
        return '';
    }
  }

  private action(method: Method, url: string, body?: Row) {
    this.loading = true;
    this.http.request(method, url, { body }).subscribe({
      next: () => this.read(),
      error: (error: HttpErrorResponse) => this.fail(error.status),
    });
  }

  private make(fields: string[]) {
    this.form = this.fb.group(
      Object.fromEntries(
        fields.map((field) => [
          field,
          [
            '',
            field === 'email'
              ? [Validators.required, Validators.email]
              : this.requiredFields().includes(field)
                ? Validators.required
                : [],
          ],
        ]),
      ),
    );
  }

  private requiredFields() {
    if (this.routeKey === 'admin/articles/new') {
      return ['title', 'content', 'status'];
    }

    if (this.routeKey === 'admin/articles/:id') {
      return ['title', 'content', 'status', 'version'];
    }

    return this.fields;
  }

  private payload(value: Row, token: string | null) {
    const result = { ...value };

    if (this.routeKey === 'verify-email' && !result['token']) {
      result['token'] = token;
    }

    if (this.routeKey === 'admin/articles/new' || this.routeKey === 'admin/articles/:id') {
      result['status'] = String(result['status'] ?? 'DRAFT').toUpperCase();
      const tags = this.form.get('tagNames');
      const tagIds = Array.isArray(result['tagIds']) ? result['tagIds'] : this.preservedTagIds;
      result['tagIds'] =
        tags?.dirty && !String(tags.value ?? '').trim()
          ? []
          : tagIds.filter((id: unknown) => !this.removedTagIds.includes(id));
    }

    if (this.routeKey === 'admin/settings/password') {
      return { value: Number(result['value']) };
    }

    if (typeof result['tagNames'] === 'string') {
      result['tagNames'] = String(result['tagNames'])
        .split(',')
        .map((tag) => tag.trim())
        .filter(Boolean);
    }

    if (this.routeKey === 'admin/articles/new' && !result['status']) {
      result['status'] = 'draft';
    }

    return result;
  }

  private done(result: unknown) {
    this.loading = false;
    this.message = this.language.t.success;
    this.cdr.markForCheck();

    if (
      this.routeKey === 'account/profile' &&
      result &&
      typeof result === 'object' &&
      this.auth.user
    ) {
      this.auth.user = { ...this.auth.user, ...(result as Partial<typeof this.auth.user>) };
      const updatedLanguage = (result as Row)['preferredLanguage'];
      if (updatedLanguage) {
        this.language.usePreferred(updatedLanguage as 'zh-TW' | 'en');
      }
    }

    if (
      this.routeKey === 'login' &&
      result &&
      typeof result === 'object' &&
      'accessToken' in result
    ) {
      this.auth.setToken(result['accessToken'] as string);
      this.auth.load().subscribe((user) => {
        if (user) {
          this.language.usePreferred(user.preferredLanguage);
        }
        void this.router.navigateByUrl('/admin/articles');
      });
    }

    if (this.routeKey === 'admin/articles/new') {
      void this.router.navigateByUrl('/admin/articles');
    }
  }

  private title() {
    if (this.routeKey === 'login') return this.language.t.login;
    const labels: Record<string, string> = {
      'public/articles': this.language.t.nav.articles,
      'public/tags': this.language.t.nav.tags,
      'admin/articles': this.language.t.nav.articles,
      'admin/articles/new': this.language.t.newArticle,
      'admin/articles/deleted': this.language.t.nav.deletedArticles,
      'admin/users': this.language.t.nav.users,
      'admin/invitations': this.language.t.nav.invitations,
      'admin/settings/password': this.language.t.nav.password,
      'account/profile': this.language.t.nav.profile,
      'account/password': this.language.t.nav.password,
      'account/email': this.language.t.nav.email,
      register: this.language.t.authTitles.register,
      'verify-email': this.language.t.authTitles.verifyEmail,
      'verify/resend': this.language.t.authTitles.verifyResend,
      'password-reset': this.language.t.authTitles.passwordReset,
      'reset-password': this.language.t.authTitles.resetPassword,
      'confirm-email': this.language.t.authTitles.confirmEmail,
      invite: this.language.t.authTitles.invite,
      'account/sessions': this.language.t.nav.sessions,
    };
    return labels[this.routeKey] ?? this.language.t.nav.articles;
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
