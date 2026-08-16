import { HttpErrorResponse } from '@angular/common/http';
import { ChangeDetectionStrategy, Component, inject, OnInit } from '@angular/core';
import { Router } from '@angular/router';
import { AuthenticationApi } from '../core/api';
import { isSupabaseConfigured, SUPABASE_AUTH } from '../core/supabase';
import { AccountLayout } from '../layouts/account-layout';
import { AppShell } from '../layouts/app-shell';
import { AccountForm } from './account-form';
import { AccountLink, AccountLinks } from './account-links';
import { FormUseCase } from './form-use-case';

@Component({
  selector: 'app-login-page',
  standalone: true,
  imports: [AppShell, AccountLayout, AccountForm, AccountLinks],
  templateUrl: './login-page.html',
  styleUrl: './account-use-case.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class LoginPage extends FormUseCase implements OnInit {
  private readonly api = inject(AuthenticationApi);
  private readonly router = inject(Router);
  private readonly supabase = inject(SUPABASE_AUTH);
  callbackProcessing = false;

  constructor() {
    super(['email', 'password']);
  }

  get title() {
    this.language.lang();
    return this.language.t.login;
  }

  get googleLoginAvailable() {
    return isSupabaseConfigured();
  }

  get links(): AccountLink[] {
    return [
      { url: '/register', label: this.language.t.needAccount },
      { url: '/password-reset', label: this.language.t.forgotPassword },
    ];
  }

  ngOnInit() {
    const hasCodeCallback = this.router.parseUrl(this.router.url).queryParams['code'];
    const hasImplicitCallback = window.location.hash.includes('access_token=');
    if (hasCodeCallback || hasImplicitCallback) {
      this.callbackProcessing = true;
      this.loading = true;
      this.error = '';
      this.cdr.markForCheck();
      void this.restoreSupabaseSession();
    }
  }

  async googleLogin() {
    if (!isSupabaseConfigured()) return;
    this.loading = true;
    this.error = '';
    try {
      const { error } = await this.supabase.signInWithOAuth({
        provider: 'google',
        options: { redirectTo: `${window.location.origin}/login` },
      });
      if (error) this.fail(0);
    } catch {
      this.fail(0);
    }
  }

  submit() {
    if (!this.valid()) return;
    this.api.login(this.form.getRawValue() as { email: string; password: string }).subscribe({
      next: (result) => this.finishLogin(result.accessToken),
      error: (error) => this.handleError(error),
    });
  }

  private async restoreSupabaseSession() {
    try {
      const { data, error } = await this.supabase.getSession();
      if (error) {
        this.callbackFail(this.language.t.loginCallbackError);
      } else if (data.session) {
        this.api.googleLogin(data.session.access_token).subscribe({
          next: (result) => this.finishLogin(result.accessToken, true),
          error: (requestError: HttpErrorResponse) => this.callbackRequestFailed(requestError),
        });
      } else this.callbackFail(this.language.t.loginCallbackNoSession);
    } catch {
      this.callbackFail(this.language.t.loginCallbackError);
    }
  }

  private finishLogin(accessToken: string, redirectToProfileIfIncomplete = false) {
    const isCallback = this.callbackProcessing;
    this.auth.setToken(accessToken);
    if (!isCallback) this.loading = false;
    this.auth.load().subscribe(
      (user) => {
        if (!user && isCallback) {
          this.callbackFail(this.language.t.loginCallbackError);
          return;
        }
        if (user) this.language.usePreferred(user.preferredLanguage);
        const destination =
          redirectToProfileIfIncomplete && !user?.displayName?.trim()
            ? '/account/profile'
            : '/articles';
        if (!isCallback) {
          void this.router.navigateByUrl(destination);
          return;
        }
        void this.router.navigateByUrl(destination).then(
          (navigated) =>
            navigated
              ? this.callbackComplete()
              : this.callbackFail(this.language.t.loginCallbackError),
          () => this.callbackFail(this.language.t.loginCallbackError),
        );
      },
      () => {
        if (isCallback) this.callbackFail(this.language.t.loginCallbackError);
      },
    );
  }

  private callbackRequestFailed(error: HttpErrorResponse) {
    this.callbackProcessing = false;
    this.handleError(error);
  }

  private callbackComplete() {
    this.callbackProcessing = false;
    this.loading = false;
    this.cdr.markForCheck();
  }

  private callbackFail(error: string) {
    this.callbackProcessing = false;
    this.loading = false;
    this.message = '';
    this.error = error;
    this.cdr.markForCheck();
  }
}
