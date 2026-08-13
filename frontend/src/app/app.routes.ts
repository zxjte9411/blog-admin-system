import { Routes } from '@angular/router';
import { authGuard, adminGuard } from './core/auth';

const publicPage = () => import('./pages/public-page').then((m) => m.PublicPage);
const accountPage = () => import('./pages/account-page').then((m) => m.AccountPage);
const adminPage = () => import('./pages/admin-page').then((m) => m.AdminPage);
export const routes: Routes = [
  { path: '', redirectTo: 'public/articles', pathMatch: 'full' },
  ...['public/articles', 'public/articles/:id', 'public/tags'].map((path) => ({
    path,
    loadComponent: publicPage,
  })),
  ...[
    'login',
    'register',
    'verify-email',
    'verify/resend',
    'password-reset',
    'reset-password',
    'confirm-email',
    'invite',
  ].map((path) => ({ path, loadComponent: accountPage })),
  ...['account/profile', 'account/password', 'account/email', 'account/sessions'].map((path) => ({
    path,
    canActivate: [authGuard],
    loadComponent: accountPage,
  })),
  ...['admin/articles', 'admin/articles/new', 'admin/articles/deleted', 'admin/articles/:id'].map(
    (path) => ({ path, canActivate: [authGuard], loadComponent: adminPage }),
  ),
  ...['admin/users', 'admin/invitations', 'admin/settings/password'].map((path) => ({
    path,
    canActivate: [adminGuard],
    loadComponent: adminPage,
  })),
  { path: 'forbidden', loadComponent: publicPage },
  { path: '**', loadComponent: publicPage },
];
