import { ChangeDetectionStrategy, Component, inject } from '@angular/core';
import { UserApi } from '../core/api';
import { AccountLayout } from '../layouts/account-layout';
import { AppShell } from '../layouts/app-shell';
import { AccountForm } from './account-form';
import { AccountLink, AccountLinks } from './account-links';
import { FormUseCase } from './form-use-case';

@Component({
  selector: 'app-password-reset-page',
  standalone: true,
  imports: [AppShell, AccountLayout, AccountForm, AccountLinks],
  templateUrl: './password-reset-page.html',
  styleUrl: './account-use-case.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class PasswordResetPage extends FormUseCase {
  private readonly api = inject(UserApi);

  constructor() {
    super(['email']);
  }

  get title() {
    this.language.lang();
    return this.language.t.authTitles.passwordReset;
  }

  get links(): AccountLink[] {
    return [
      { url: '/login', label: this.language.t.hasAccount },
      { url: '/register', label: this.language.t.needAccount },
    ];
  }

  submit() {
    if (!this.valid()) return;
    this.api.requestPasswordReset(this.form.getRawValue()['email']).subscribe({
      next: () => this.done(),
      error: (error) => this.handleError(error),
    });
  }
}
