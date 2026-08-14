import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { ActivatedRoute, provideRouter } from '@angular/router';
import { of } from 'rxjs';
import { AuthenticationApi, InvitationUser, UserApi } from '../core/api';
import { EmailConfirmationPage } from './email-confirmation-page';
import { EmailVerificationPage } from './email-verification-page';
import { InvitationRedemptionPage } from './invitation-redemption-page';
import { PasswordResetPage } from './password-reset-page';
import { RegistrationPage } from './registration-page';
import { ResendVerificationPage } from './resend-verification-page';
import { ResetPasswordPage } from './reset-password-page';

const routeWithToken = (token: string) => ({
  snapshot: { queryParamMap: { get: () => token } },
});

describe('authentication use-case pages', () => {
  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideRouter([]), provideHttpClient(), provideHttpClientTesting()],
    });
  });

  it('registers through the registration use case', () => {
    const api = TestBed.inject(AuthenticationApi);
    const register = vi.spyOn(api, 'register').mockReturnValue(of());
    const page = TestBed.createComponent(RegistrationPage).componentInstance;
    page.form.patchValue({
      email: 'ada@example.com',
      displayName: 'Ada',
      password: 'secret',
      preferredLanguage: 'en',
    });

    page.submit();

    expect(register).toHaveBeenCalledWith({
      email: 'ada@example.com',
      displayName: 'Ada',
      password: 'secret',
      preferredLanguage: 'en',
    });
  });

  it('verifies email and resends verification through separate pages', () => {
    TestBed.overrideProvider(ActivatedRoute, { useValue: routeWithToken('verify-token') });
    const api = TestBed.inject(AuthenticationApi);
    const verify = vi.spyOn(api, 'verifyEmail').mockReturnValue(of());
    const resend = vi.spyOn(api, 'resendEmailVerification').mockReturnValue(of());

    const verifyPage = TestBed.createComponent(EmailVerificationPage).componentInstance;
    verifyPage.submit();
    const resendPage = TestBed.createComponent(ResendVerificationPage).componentInstance;
    resendPage.form.patchValue({ email: 'ada@example.com' });
    resendPage.submit();

    expect(verify).toHaveBeenCalledWith({ token: 'verify-token' });
    expect(resend).toHaveBeenCalledWith({ email: 'ada@example.com' });
  });

  it('keeps password reset request and confirmation on separate pages', () => {
    TestBed.overrideProvider(ActivatedRoute, { useValue: routeWithToken('reset-token') });
    const userApi = TestBed.inject(UserApi);
    const request = vi.spyOn(userApi, 'requestPasswordReset').mockReturnValue(of());
    const reset = vi.spyOn(userApi, 'resetPassword').mockReturnValue(of());

    const requestPage = TestBed.createComponent(PasswordResetPage).componentInstance;
    requestPage.form.patchValue({ email: 'ada@example.com' });
    requestPage.submit();

    const resetPage = TestBed.createComponent(ResetPasswordPage).componentInstance;
    resetPage.form.patchValue({ password: 'new-secret' });
    resetPage.submit();

    expect(request).toHaveBeenCalledWith('ada@example.com');
    expect(reset).toHaveBeenCalledWith('reset-token', 'new-secret');
  });

  it('confirms email changes and redeems invitations with their own tokens', () => {
    TestBed.overrideProvider(ActivatedRoute, { useValue: routeWithToken('email-token') });
    const authApi = TestBed.inject(AuthenticationApi);
    const userApi = TestBed.inject(UserApi);
    const confirm = vi.spyOn(userApi, 'confirmEmailChange').mockReturnValue(of());
    const redeem = vi.spyOn(authApi, 'redeemInvitation').mockReturnValue(of({} as InvitationUser));

    const confirmPage = TestBed.createComponent(EmailConfirmationPage).componentInstance;
    confirmPage.submit();
    const invitePage = TestBed.createComponent(InvitationRedemptionPage).componentInstance;
    invitePage.form.patchValue({
      displayName: 'Ada',
      password: 'secret',
      preferredLanguage: 'en',
    });
    invitePage.submit();

    expect(confirm).toHaveBeenCalledWith('email-token');
    expect(redeem).toHaveBeenCalledWith('email-token', {
      displayName: 'Ada',
      password: 'secret',
      preferredLanguage: 'en',
    });
  });
});
