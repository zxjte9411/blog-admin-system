import { ChangeDetectionStrategy, Component, OnInit, effect, inject } from '@angular/core';
import { HttpErrorResponse } from '@angular/common/http';
import { PreferredLanguage, ProfileRequest, UserApi } from '../core/api';
import { AccountLayout } from '../layouts/account-layout';
import { AppShell } from '../layouts/app-shell';
import { AccountForm } from './account-form';
import { FormUseCase } from './form-use-case';

@Component({
  selector: 'app-user-profile-page',
  standalone: true,
  imports: [AppShell, AccountLayout, AccountForm],
  templateUrl: './user-profile-page.html',
  styleUrl: './account-use-case.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class UserProfilePage extends FormUseCase implements OnInit {
  private readonly api = inject(UserApi);
  constructor() {
    super(['displayName', 'preferredLanguage']);
    effect(() => {
      const currentLanguage = this.language.lang();
      this.form.get('preferredLanguage')?.setValue(currentLanguage, { emitEvent: false });
      this.cdr.markForCheck();
    });
  }

  get title() {
    this.language.lang();
    return this.language.t.nav.profile;
  }

  ngOnInit() {
    this.loading = true;
    this.api.me().subscribe({
      next: (user) => {
        this.auth.user = user;
        this.form.patchValue({
          displayName: user.displayName,
          preferredLanguage: user.preferredLanguage,
        });
        this.loading = false;
        this.cdr.markForCheck();
      },
      error: (error: HttpErrorResponse) => this.handleError(error),
    });
  }

  onLanguageChange(value: string) {
    if (value === 'zh-TW' || value === 'en') this.language.set(value);
  }

  submit() {
    if (!this.valid()) return;
    this.api.profile(this.form.getRawValue() as ProfileRequest).subscribe({
      next: (result) => {
        if (this.auth.user) this.auth.user = { ...this.auth.user, ...result };
        const preferredLanguage = result.preferredLanguage as PreferredLanguage;
        this.language.usePreferred(preferredLanguage);
        this.done();
      },
      error: (error) => this.handleError(error),
    });
  }
}
