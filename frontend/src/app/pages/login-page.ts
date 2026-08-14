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
        this.fail(0);
      } else if (data.session) {
        this.loading = true;
        this.api.googleLogin(data.session.access_token).subscribe({
          next: (result) => this.finishLogin(result.accessToken, true),
          error: (requestError) => this.handleError(requestError),
        });
      }
    } catch {
      this.fail(0);
    }
  }

  private finishLogin(accessToken: string, redirectToProfileIfIncomplete = false) {
    this.auth.setToken(accessToken);
    this.loading = false;
    this.auth.load().subscribe((user) => {
      if (user) this.language.usePreferred(user.preferredLanguage);
      const destination =
        redirectToProfileIfIncomplete && !user?.displayName?.trim()
          ? '/account/profile'
          : '/articles';
      void this.router.navigateByUrl(destination);
    });
  }
}
