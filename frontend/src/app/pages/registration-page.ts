import { ChangeDetectionStrategy, Component, inject } from '@angular/core';
import { AuthenticationApi, RegistrationRequest } from '../core/api';
import { AccountLayout } from '../layouts/account-layout';
import { AppShell } from '../layouts/app-shell';
import { AccountForm } from './account-form';
import { AccountLink, AccountLinks } from './account-links';
import { FormUseCase } from './form-use-case';

@Component({
  selector: 'app-registration-page',
  standalone: true,
  imports: [AppShell, AccountLayout, AccountForm, AccountLinks],
  templateUrl: './registration-page.html',
  styleUrl: './account-use-case.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class RegistrationPage extends FormUseCase {
  private readonly api = inject(AuthenticationApi);

  constructor() {
    super(['email', 'displayName', 'password', 'preferredLanguage']);
  }

  get title() {
    this.language.lang();
    return this.language.t.authTitles.register;
  }

  get links(): AccountLink[] {
    return [
      { url: '/login', label: this.language.t.hasAccount },
      { url: '/verify/resend', label: this.language.t.authTitles.verifyResend },
      { url: '/password-reset', label: this.language.t.authTitles.passwordReset },
      { url: '/verify-email', label: this.language.t.authTitles.verifyEmail },
    ];
  }

  submit() {
    if (!this.valid()) return;
    this.api.register(this.form.getRawValue() as RegistrationRequest).subscribe({
      next: () => this.done(),
      error: (error) => this.handleError(error),
    });
  }
}
