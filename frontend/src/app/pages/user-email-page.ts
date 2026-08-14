import { ChangeDetectionStrategy, Component, inject } from '@angular/core';
import { EmailChangeRequest, UserApi } from '../core/api';
import { AccountLayout } from '../layouts/account-layout';
import { AppShell } from '../layouts/app-shell';
import { AccountForm } from './account-form';
import { FormUseCase } from './form-use-case';

@Component({
  selector: 'app-user-email-page',
  standalone: true,
  imports: [AppShell, AccountLayout, AccountForm],
  templateUrl: './user-email-page.html',
  styleUrl: './account-use-case.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class UserEmailPage extends FormUseCase {
  private readonly api = inject(UserApi);

  constructor() {
    super(['email']);
  }

  get title() {
    this.language.lang();
    return this.language.t.nav.email;
  }

  submit() {
    if (!this.valid()) return;
    this.api.requestEmailChange(this.form.getRawValue() as EmailChangeRequest).subscribe({
      next: () => this.done(),
      error: (error) => this.handleError(error),
    });
  }
}
