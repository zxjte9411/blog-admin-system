import { ChangeDetectionStrategy, Component, inject } from '@angular/core';
import { ActivatedRoute } from '@angular/router';
import { AuthenticationApi, EmailVerificationRequest } from '../core/api';
import { AccountLayout } from '../layouts/account-layout';
import { AppShell } from '../layouts/app-shell';
import { AccountForm } from './account-form';
import { AccountLink, AccountLinks } from './account-links';
import { FormUseCase } from './form-use-case';

@Component({
  selector: 'app-email-verification-page',
  standalone: true,
  imports: [AppShell, AccountLayout, AccountForm, AccountLinks],
  templateUrl: './email-verification-page.html',
  styleUrl: './account-use-case.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class EmailVerificationPage extends FormUseCase {
  private readonly api = inject(AuthenticationApi);
  private readonly route = inject(ActivatedRoute);
  readonly token: string;
  missingToken = false;

  constructor() {
    super(['token']);
    this.token = this.route.snapshot.queryParamMap.get('token') ?? '';
    this.form.patchValue({ token: this.token });
    this.missingToken = !this.token;
  }

  get title() {
    this.language.lang();
    return this.language.t.authTitles.verifyEmail;
  }

  get links(): AccountLink[] {
    return [
      { url: '/verify/resend', label: this.language.t.authTitles.verifyResend },
      { url: '/login', label: this.language.t.hasAccount },
    ];
  }

  submit() {
    if (!this.valid()) return;
    this.api.verifyEmail(this.form.getRawValue() as EmailVerificationRequest).subscribe({
      next: () => this.done(),
      error: (error) => this.handleError(error),
    });
  }
}
