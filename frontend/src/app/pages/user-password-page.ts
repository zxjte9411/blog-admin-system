import { ChangeDetectionStrategy, Component, inject } from '@angular/core';
import { PasswordRequest, UserApi } from '../core/api';
import { AccountLayout } from '../layouts/account-layout';
import { AppShell } from '../layouts/app-shell';
import { AccountForm } from './account-form';
import { FormUseCase } from './form-use-case';

@Component({
  selector: 'app-user-password-page',
  standalone: true,
  imports: [AppShell, AccountLayout, AccountForm],
  templateUrl: './user-password-page.html',
  styleUrl: './account-use-case.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class UserPasswordPage extends FormUseCase {
  private readonly api = inject(UserApi);

  constructor() {
    super(['currentPassword', 'newPassword']);
  }

  get title() {
    this.language.lang();
    return this.language.t.nav.password;
  }

  submit() {
    if (!this.valid()) return;
    this.api.password(this.form.getRawValue() as PasswordRequest).subscribe({
      next: () => this.done(),
      error: (error) => this.handleError(error),
    });
  }
}
