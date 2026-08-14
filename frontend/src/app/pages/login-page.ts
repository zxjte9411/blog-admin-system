import { ChangeDetectionStrategy, Component, inject } from '@angular/core';
import { Router } from '@angular/router';
import { AuthenticationApi } from '../core/api';
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
export class LoginPage extends FormUseCase {
  private readonly api = inject(AuthenticationApi);
  private readonly router = inject(Router);

  constructor() {
    super(['email', 'password']);
  }

  get title() {
    this.language.lang();
    return this.language.t.login;
  }

  get links(): AccountLink[] {
    return [
      { url: '/register', label: this.language.t.needAccount },
      { url: '/password-reset', label: this.language.t.forgotPassword },
    ];
  }

  submit() {
    if (!this.valid()) return;
    this.api.login(this.form.getRawValue() as { email: string; password: string }).subscribe({
      next: (result) => {
        this.auth.setToken(result.accessToken);
        this.loading = false;
        this.auth.load().subscribe((user) => {
          if (user) this.language.usePreferred(user.preferredLanguage);
          void this.router.navigateByUrl('/articles');
        });
      },
      error: (error) => this.handleError(error),
    });
  }
}
