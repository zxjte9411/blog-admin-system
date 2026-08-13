import { Routes } from '@angular/router';
import { authGuard, adminGuard } from './core/auth';

const page = () => import('./pages/portal').then((m) => m.Portal);
export const routes: Routes = [
  { path: '', redirectTo: 'public/articles', pathMatch: 'full' },
  ...[
    'login',
    'register',
    'verify-email',
    'verify/resend',
    'password-reset',
    'reset-password',
    'confirm-email',
    'invite',
    'public/articles',
    'public/articles/:id',
    'public/tags',
  ].map((path) => ({ path, loadComponent: page })),
  ...[
    'account/profile',
    'account/password',
    'account/email',
    'account/sessions',
    'admin/articles',
    'admin/articles/new',
    'admin/articles/deleted',
    'admin/articles/:id',
  ].map((path) => ({ path, canActivate: [authGuard], loadComponent: page })),
  ...['admin/users', 'admin/invitations', 'admin/settings/password'].map((path) => ({
    path,
    canActivate: [adminGuard],
    loadComponent: page,
  })),
  { path: 'forbidden', loadComponent: page },
  { path: '**', loadComponent: page },
];
