import { ChangeDetectionStrategy, Component, inject } from '@angular/core';
import { AuthenticationApi, ResendEmailVerificationRequest } from '../core/api';
import { AccountLayout } from '../layouts/account-layout';
import { AppShell } from '../layouts/app-shell';
import { AccountForm } from './account-form';
import { AccountLink, AccountLinks } from './account-links';
import { FormUseCase } from './form-use-case';

@Component({
  selector: 'app-resend-verification-page',
  standalone: true,
  imports: [AppShell, AccountLayout, AccountForm, AccountLinks],
  templateUrl: './resend-verification-page.html',
  styleUrl: './account-use-case.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ResendVerificationPage extends FormUseCase {
  private readonly api = inject(AuthenticationApi);

  constructor() {
    super(['email']);
  }

  get title() {
    this.language.lang();
    return this.language.t.authTitles.verifyResend;
  }

  get links(): AccountLink[] {
    return [
      { url: '/verify-email', label: this.language.t.verifyEmailHelp },
      { url: '/login', label: this.language.t.hasAccount },
    ];
  }

  submit() {
    if (!this.valid()) return;
    this.api
      .resendEmailVerification(this.form.getRawValue() as ResendEmailVerificationRequest)
      .subscribe({ next: () => this.done(), error: (error) => this.handleError(error) });
  }
}
