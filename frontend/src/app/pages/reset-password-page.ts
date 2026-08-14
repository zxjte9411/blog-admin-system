import { ChangeDetectionStrategy, Component, inject } from '@angular/core';
import { ActivatedRoute } from '@angular/router';
import { UserApi } from '../core/api';
import { AccountLayout } from '../layouts/account-layout';
import { AppShell } from '../layouts/app-shell';
import { AccountForm } from './account-form';
import { AccountLink, AccountLinks } from './account-links';
import { FormUseCase } from './form-use-case';

@Component({
  selector: 'app-reset-password-page',
  standalone: true,
  imports: [AppShell, AccountLayout, AccountForm, AccountLinks],
  templateUrl: './reset-password-page.html',
  styleUrl: './account-use-case.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ResetPasswordPage extends FormUseCase {
  private readonly api = inject(UserApi);
  private readonly route = inject(ActivatedRoute);
  readonly token: string;
  missingToken = false;

  constructor() {
    super(['password']);
    this.token = this.route.snapshot.queryParamMap.get('token') ?? '';
    this.missingToken = !this.token;
  }

  get title() {
    this.language.lang();
    return this.language.t.authTitles.resetPassword;
  }

  get links(): AccountLink[] {
    return [
      { url: '/login', label: this.language.t.hasAccount },
      { url: '/register', label: this.language.t.needAccount },
    ];
  }

  submit() {
    if (!this.valid()) return;
    this.api.resetPassword(this.token, this.form.getRawValue()['password']).subscribe({
      next: () => this.done(),
      error: (error) => this.handleError(error),
    });
  }
}
