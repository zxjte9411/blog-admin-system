import { ChangeDetectionStrategy, Component, inject } from '@angular/core';
import { ActivatedRoute } from '@angular/router';
import { AuthenticationApi, InvitationRedeemRequest } from '../core/api';
import { AccountLayout } from '../layouts/account-layout';
import { AppShell } from '../layouts/app-shell';
import { AccountForm } from './account-form';
import { AccountLink, AccountLinks } from './account-links';
import { FormUseCase } from './form-use-case';

@Component({
  selector: 'app-invitation-redemption-page',
  standalone: true,
  imports: [AppShell, AccountLayout, AccountForm, AccountLinks],
  templateUrl: './invitation-redemption-page.html',
  styleUrl: './account-use-case.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class InvitationRedemptionPage extends FormUseCase {
  private readonly api = inject(AuthenticationApi);
  private readonly route = inject(ActivatedRoute);
  readonly token: string;
  missingToken = false;

  constructor() {
    super(['displayName', 'password', 'preferredLanguage']);
    this.token = this.route.snapshot.queryParamMap.get('token') ?? '';
    this.missingToken = !this.token;
  }

  get title() {
    this.language.lang();
    return this.language.t.authTitles.invite;
  }

  get links(): AccountLink[] {
    return [{ url: '/login', label: this.language.t.hasAccount }];
  }

  submit() {
    if (!this.valid()) return;
    this.api
      .redeemInvitation(this.token, this.form.getRawValue() as InvitationRedeemRequest)
      .subscribe({ next: () => this.done(), error: (error) => this.handleError(error) });
  }
}
