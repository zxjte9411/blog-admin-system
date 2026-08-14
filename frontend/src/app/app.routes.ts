import { Routes } from '@angular/router';
import { authGuard, adminGuard } from './core/auth';
import { canLeaveArticle } from './pages/article-editor-page';

const publicPage = () => import('./pages/public-page').then((m) => m.PublicPage);
const loginPage = () => import('./pages/login-page').then((m) => m.LoginPage);
const registrationPage = () => import('./pages/registration-page').then((m) => m.RegistrationPage);
const emailVerificationPage = () =>
  import('./pages/email-verification-page').then((m) => m.EmailVerificationPage);
const resendVerificationPage = () =>
  import('./pages/resend-verification-page').then((m) => m.ResendVerificationPage);
const passwordResetPage = () =>
  import('./pages/password-reset-page').then((m) => m.PasswordResetPage);
const resetPasswordPage = () =>
  import('./pages/reset-password-page').then((m) => m.ResetPasswordPage);
const emailConfirmationPage = () =>
  import('./pages/email-confirmation-page').then((m) => m.EmailConfirmationPage);
const invitationRedemptionPage = () =>
  import('./pages/invitation-redemption-page').then((m) => m.InvitationRedemptionPage);
const userProfilePage = () => import('./pages/user-profile-page').then((m) => m.UserProfilePage);
const userPasswordPage = () => import('./pages/user-password-page').then((m) => m.UserPasswordPage);
const userEmailPage = () => import('./pages/user-email-page').then((m) => m.UserEmailPage);
const userSessionsPage = () => import('./pages/user-sessions-page').then((m) => m.UserSessionsPage);
const managedUsersPage = () => import('./pages/managed-users-page').then((m) => m.ManagedUsersPage);
const invitationsPage = () => import('./pages/invitations-page').then((m) => m.InvitationsPage);
const passwordSettingsPage = () =>
  import('./pages/password-settings-page').then((m) => m.PasswordSettingsPage);
const articleListPage = () => import('./pages/article-list-page').then((m) => m.ArticleListPage);
const articleCreatePage = () =>
  import('./pages/article-create-page').then((m) => m.ArticleCreatePage);
const articleEditPage = () => import('./pages/article-edit-page').then((m) => m.ArticleEditPage);
const deletedArticlesPage = () =>
  import('./pages/deleted-articles-page').then((m) => m.DeletedArticlesPage);
export const routes: Routes = [
  { path: '', redirectTo: 'public/articles', pathMatch: 'full' },
  ...['public/articles', 'public/articles/:id', 'public/tags'].map((path) => ({
    path,
    loadComponent: publicPage,
  })),
  { path: 'login', loadComponent: loginPage },
  { path: 'register', loadComponent: registrationPage },
  { path: 'verify-email', loadComponent: emailVerificationPage },
  { path: 'verify/resend', loadComponent: resendVerificationPage },
  { path: 'password-reset', loadComponent: passwordResetPage },
  { path: 'reset-password', loadComponent: resetPasswordPage },
  { path: 'confirm-email', loadComponent: emailConfirmationPage },
  { path: 'invite', loadComponent: invitationRedemptionPage },
  { path: 'account/profile', canActivate: [authGuard], loadComponent: userProfilePage },
  { path: 'account/password', canActivate: [authGuard], loadComponent: userPasswordPage },
  { path: 'account/email', canActivate: [authGuard], loadComponent: userEmailPage },
  { path: 'account/sessions', canActivate: [authGuard], loadComponent: userSessionsPage },
  { path: 'articles', canActivate: [authGuard], loadComponent: articleListPage },
  {
    path: 'articles/new',
    canActivate: [authGuard],
    canDeactivate: [canLeaveArticle],
    loadComponent: articleCreatePage,
  },
  { path: 'articles/deleted', canActivate: [authGuard], loadComponent: deletedArticlesPage },
  {
    path: 'articles/:id/edit',
    canActivate: [authGuard],
    canDeactivate: [canLeaveArticle],
    loadComponent: articleEditPage,
  },
  { path: 'admin/users', canActivate: [adminGuard], loadComponent: managedUsersPage },
  { path: 'admin/invitations', canActivate: [adminGuard], loadComponent: invitationsPage },
  {
    path: 'admin/settings/password',
    canActivate: [adminGuard],
    loadComponent: passwordSettingsPage,
  },
  { path: 'forbidden', loadComponent: publicPage },
  { path: '**', loadComponent: publicPage },
];
