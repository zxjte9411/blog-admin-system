import { ChangeDetectionStrategy, Component, inject, OnInit } from '@angular/core';
import { ActivatedRoute, Router } from '@angular/router';
import { AuthenticationApi, InvitationRedeemRequest } from '../core/api';
import { isSupabaseConfigured, SUPABASE_AUTH } from '../core/supabase';
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
export class InvitationRedemptionPage extends FormUseCase implements OnInit {
  private readonly api = inject(AuthenticationApi);
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);
  private readonly supabase = inject(SUPABASE_AUTH);
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

  get googleLoginAvailable() {
    return isSupabaseConfigured();
  }

  ngOnInit() {
    const hasCodeCallback = this.router.parseUrl(this.router.url).queryParams['code'];
    const hasImplicitCallback = window.location.hash.includes('access_token=');
    if (this.token && (hasCodeCallback || hasImplicitCallback)) {
      void this.restoreSupabaseSession();
    }
  }

  async googleLogin() {
    if (this.missingToken || !isSupabaseConfigured()) return;
    this.loading = true;
    this.error = '';
    try {
      const { error } = await this.supabase.signInWithOAuth({
        provider: 'google',
        options: {
          redirectTo: `${window.location.origin}/invite?token=${encodeURIComponent(this.token)}`,
        },
      });
      if (error) this.fail(0);
    } catch {
      this.fail(0);
    }
  }

  submit() {
    if (!this.valid()) return;
    this.api
      .redeemInvitation(this.token, this.form.getRawValue() as InvitationRedeemRequest)
      .subscribe({ next: () => this.done(), error: (error) => this.handleError(error) });
  }

  private async restoreSupabaseSession() {
    try {
      const { data, error } = await this.supabase.getSession();
      if (error) {
        this.fail(0);
      } else if (data.session) {
        this.loading = true;
        this.api.googleLogin(data.session.access_token, this.token).subscribe({
          next: (result) => this.finishLogin(result.accessToken),
          error: (requestError) => this.handleError(requestError),
        });
      }
    } catch {
      this.fail(0);
    }
  }

  private finishLogin(accessToken: string) {
    this.auth.setToken(accessToken);
    this.loading = false;
    this.auth.load().subscribe((user) => {
      if (user) this.language.usePreferred(user.preferredLanguage);
      void this.router.navigateByUrl('/articles');
    });
  }
}
