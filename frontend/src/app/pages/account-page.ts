import { CommonModule } from '@angular/common';
import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import {
  ChangeDetectionStrategy,
  ChangeDetectorRef,
  Component,
  OnInit,
  effect,
  inject,
} from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { Auth } from '../core/auth';
import { Language } from '../core/language';
import { AccountLayout } from '../layouts/account-layout';
import { AppShell } from '../layouts/app-shell';
import { getPageNumbers } from '../core/pagination';

type Row = Record<string, unknown>;
type Method = 'DELETE' | 'GET' | 'PATCH' | 'POST' | 'PUT';

const fields: Record<string, string[]> = {
  login: ['email', 'password'],
  register: ['email', 'displayName', 'password', 'preferredLanguage'],
  'verify-email': ['token'],
  'verify/resend': ['email'],
  'password-reset': ['email'],
  'reset-password': ['password'],
  invite: ['displayName', 'password', 'preferredLanguage'],
  'account/profile': ['displayName', 'preferredLanguage'],
  'account/password': ['currentPassword', 'newPassword'],
  'account/email': ['email'],
};

@Component({
  selector: 'app-account-page',
  standalone: true,
  imports: [CommonModule, ReactiveFormsModule, RouterLink, AppShell, AccountLayout],
  changeDetection: ChangeDetectionStrategy.OnPush,
  styleUrl: './account-page.scss',
  templateUrl: './account-page.html',
})
export class AccountPage implements OnInit {
  readonly auth = inject(Auth);
  readonly language = inject(Language);
  private readonly http = inject(HttpClient);
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);
  private readonly fb = inject(FormBuilder);
  private readonly cdr = inject(ChangeDetectorRef);
  routeKey = '';
  fields: string[] = [];
  loading = false;
  submitAttempted = false;
  error = '';
  message = '';
  items: Row[] = [];
  page = 0;
  readonly pageSize = 10;
  totalPages = 0;
  form = this.fb.group<Record<string, never>>({});
  missingToken = false;

  constructor() {
    effect(() => {
      const currentLang = this.language.lang();
      if (this.routeKey === 'account/profile' && this.form.get('preferredLanguage')) {
        this.form.get('preferredLanguage')?.setValue(currentLang, { emitEvent: false });
        this.cdr.markForCheck();
      }
    });
  }

  ngOnInit() {
    this.routeKey = this.routeKey || this.route.snapshot.routeConfig?.path || '';
    this.fields = fields[this.routeKey] ?? [];
    if (['reset-password', 'invite', 'confirm-email'].includes(this.routeKey)) {
      const token = this.route.snapshot.queryParamMap.get('token');
      if (!token) {
        this.missingToken = true;
      }
    }
    this.makeForm();
    if (this.routeKey === 'verify-email') {
      this.form.patchValue({ token: this.route.snapshot.queryParamMap.get('token') ?? '' });
    }
    if (this.routeKey === 'account/profile') {
      this.loadProfile();
    }
    if (this.routeKey === 'account/sessions') {
      this.readSessions();
    }
  }

  get isAccountRoute() {
    return this.routeKey.startsWith('account/');
  }

  fieldLabel(field: string) {
    return (this.language.t.field as Record<string, string>)[field] ?? field;
  }
  fieldErrorId(field: string) {
    return `field-${field}-error`;
  }
  fieldInvalid(field: string) {
    const control = this.form.controls[field];
    return !!control && (control.touched || this.submitAttempted) && control.invalid;
  }
  autocomplete(field: string) {
    if (field === 'currentPassword') {
      return 'current-password';
    }
    if (
      field === 'newPassword' ||
      (field === 'password' && this.routeKey !== 'login' && this.routeKey !== 'account/password')
    ) {
      return 'new-password';
    }
    if (field === 'password') {
      return 'current-password';
    }
    return field === 'email' ? (this.routeKey === 'login' ? 'username' : 'email') : null;
  }

  onLanguageFieldChange(event: Event) {
    if (this.routeKey === 'account/profile') {
      const value = (event.target as HTMLSelectElement).value;
      if (value === 'zh-TW' || value === 'en') {
        this.language.set(value);
      }
    }
  }

  submit() {
    this.submitAttempted = true;
    this.message = '';
    if (this.form.invalid) {
      this.form.markAllAsTouched();
      return;
    }
    const value = this.form.getRawValue() as Row;
    const request = this.request(value);
    this.loading = true;
    this.error = '';
    this.http.request(request.method, request.url, { body: request.body }).subscribe({
      next: (result) => this.done(result),
      error: (e: HttpErrorResponse) => this.fail(e.status),
    });
  }

  revoke(row: Row) {
    this.action('DELETE', `/api/v1/auth/sessions/${row['id']}`);
  }

  retrySessions() {
    this.error = '';
    this.readSessions();
  }

  private request(value: Row) {
    const token = this.route.snapshot.queryParamMap.get('token');
    if (this.routeKey === 'login') {
      return { method: 'POST' as Method, url: '/api/v1/auth/login', body: value };
    }
    const body = this.routeKey === 'verify-email' && !value['token'] ? { ...value, token } : value;
    const specs: Record<string, { method: Method; url: string; body?: Row }> = {
      register: { method: 'POST', url: '/api/v1/auth/registrations', body },
      'verify-email': { method: 'POST', url: '/api/v1/auth/email-verifications', body },
      'verify/resend': { method: 'POST', url: '/api/v1/auth/email-verifications/resend', body },
      'password-reset': { method: 'POST', url: '/api/v1/auth/password-resets', body },
      'reset-password': { method: 'POST', url: `/api/v1/auth/password-resets/${token}`, body },
      'confirm-email': { method: 'POST', url: `/api/v1/auth/email-changes/${token}` },
      invite: { method: 'POST', url: `/api/v1/auth/invitations/${token}/redeem`, body },
      'account/profile': { method: 'PATCH', url: '/api/v1/account/profile', body },
      'account/password': { method: 'PUT', url: '/api/v1/account/password', body },
      'account/email': { method: 'POST', url: '/api/v1/account/email', body },
    };
    return specs[this.routeKey] ?? { method: 'GET', url: '', body: undefined };
  }

  private makeForm() {
    this.form = this.fb.group(
      Object.fromEntries(
        this.fields.map((field) => [
          field,
          ['', field === 'email' ? [Validators.required, Validators.email] : Validators.required],
        ]),
      ),
    );
  }
  private loadProfile() {
    this.loading = true;
    this.http.get<Row>('/api/v1/account/me').subscribe({
      next: (user) => {
        this.auth.user = user as typeof this.auth.user;
        this.form.patchValue(user);
        this.loading = false;
        this.cdr.markForCheck();
      },
      error: (e: HttpErrorResponse) => this.fail(e.status),
    });
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
        if (this.page >= this.totalPages) {
          this.page = Math.max(0, this.totalPages - 1);
        }
        this.loading = false;
        this.cdr.markForCheck();
      },
      error: (e: HttpErrorResponse) => this.fail(e.status),
    });
  }
  private action(method: Method, url: string) {
    this.loading = true;
    this.http.request(method, url).subscribe({
      next: () => this.readSessions(),
      error: (e: HttpErrorResponse) => this.fail(e.status),
    });
  }
  private done(result: unknown) {
    this.loading = false;
    this.error = '';
    this.message = this.language.t.success;
    if (this.routeKey === 'account/profile' && result) {
      if (this.auth.user)
        this.auth.user = { ...this.auth.user, ...(result as Partial<typeof this.auth.user>) };
      const preferredLanguage = (result as Row)['preferredLanguage'];
      if (preferredLanguage === 'zh-TW' || preferredLanguage === 'en')
        this.language.usePreferred(preferredLanguage);
    }
    if (
      this.routeKey === 'login' &&
      result &&
      typeof result === 'object' &&
      'accessToken' in result
    ) {
      this.auth.setToken(result['accessToken'] as string);
      this.auth.load().subscribe((user) => {
        if (user) this.language.usePreferred(user.preferredLanguage);
        void this.router.navigateByUrl('/articles');
      });
    }
    this.cdr.markForCheck();
  }
  get title() {
    this.language.lang();
    const labels: Record<string, string> = {
      login: this.language.t.login,
      register: this.language.t.authTitles.register,
      'verify-email': this.language.t.authTitles.verifyEmail,
      'verify/resend': this.language.t.authTitles.verifyResend,
      'password-reset': this.language.t.authTitles.passwordReset,
      'reset-password': this.language.t.authTitles.resetPassword,
      'confirm-email': this.language.t.authTitles.confirmEmail,
      invite: this.language.t.authTitles.invite,
      'account/profile': this.language.t.nav.profile,
      'account/password': this.language.t.nav.password,
      'account/email': this.language.t.nav.email,
      'account/sessions': this.language.t.nav.sessions,
    };
    return labels[this.routeKey] ?? this.language.t.login;
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
