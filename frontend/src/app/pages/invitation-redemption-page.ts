import { HttpErrorResponse } from '@angular/common/http';
import { ChangeDetectionStrategy, Component, inject, OnInit } from '@angular/core';
import { ActivatedRoute, Router } from '@angular/router';
import {
  AuthenticationApi,
  InvitationRedemptionContext,
  InvitationRedemptionStatus,
  InvitationRedeemRequest,
} from '../core/api';
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
  invitationContext: InvitationRedemptionContext | null = null;
  invitationStatus: InvitationRedemptionStatus | 'validating' | 'accepted' | 'requestError';

  constructor() {
    super(['displayName', 'password', 'preferredLanguage']);
    this.token = this.route.snapshot.queryParamMap.get('token') ?? '';
    this.missingToken = !this.token;
    this.invitationStatus = this.token ? 'validating' : 'invalid';
  }

  get title() {
    this.language.lang();
    return this.language.t.authTitles.invite;
  }

  get links(): AccountLink[] {
    return [{ url: '/login', label: this.language.t.hasAccount }];
  }

  get googleLoginAvailable() {
    return this.invitationStatus === 'valid' && isSupabaseConfigured();
  }

  ngOnInit() {
    if (this.token) this.readContext();
  }

  async googleLogin() {
    if (this.invitationStatus !== 'valid' || !isSupabaseConfigured()) return;
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
    if (this.invitationStatus !== 'valid' || this.message || !this.valid()) return;
    this.api
      .redeemInvitation(this.token, this.form.getRawValue() as InvitationRedeemRequest)
      .subscribe({
        next: () => this.accept(),
        error: (error: HttpErrorResponse) => this.reinspectAfterFailure(error),
      });
  }

  retry() {
    if (this.invitationStatus === 'requestError') this.readContext();
  }

  private readContext() {
    this.invitationStatus = 'validating';
    this.invitationContext = null;
    this.loading = true;
    this.error = '';
    this.message = '';
    this.api.getInvitationContext(this.token).subscribe({
      next: (context) => {
        this.invitationContext = context;
        this.invitationStatus = context.status;
        this.loading = false;
        this.cdr.markForCheck();
        if (context.status === 'valid' && this.hasGoogleCallback()) {
          void this.restoreSupabaseSession();
        }
      },
      error: () => this.contextRequestFailed(),
    });
  }

  private contextRequestFailed() {
    this.loading = false;
    this.error = '';
    this.message = '';
    this.invitationStatus = 'requestError';
    this.cdr.markForCheck();
  }

  private accept() {
    this.loading = false;
    this.error = '';
    this.message = this.language.t.invitationAccepted;
    this.invitationStatus = 'accepted';
    this.cdr.markForCheck();
  }

  private reinspectAfterFailure(error: HttpErrorResponse) {
    this.loading = true;
    this.error = '';
    this.message = '';
    this.api.getInvitationContext(this.token).subscribe({
      next: (context) => {
        this.invitationContext = context;
        this.invitationStatus = context.status;
        this.loading = false;
        if (context.status === 'valid') this.handleError(error);
        else this.cdr.markForCheck();
      },
      error: () => {
        this.invitationStatus = 'valid';
        this.loading = false;
        this.handleError(error);
      },
    });
  }

  private hasGoogleCallback() {
    const hasCodeCallback = this.router.parseUrl(this.router.url).queryParams['code'];
    return hasCodeCallback || window.location.hash.includes('access_token=');
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
          error: (requestError) => this.reinspectAfterFailure(requestError),
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
