import { Routes } from '@angular/router';
import { authGuard, adminGuard } from './core/auth';
import { canLeaveArticle } from './pages/article-editor-page';

const publicPage = () => import('./pages/public-page').then((m) => m.PublicPage);
const accountPage = () => import('./pages/account-page').then((m) => m.AccountPage);
const adminPage = () => import('./pages/admin-page').then((m) => m.AdminPage);
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
  ...['admin/users', 'admin/invitations', 'admin/settings/password'].map((path) => ({
    path,
    canActivate: [adminGuard],
    loadComponent: adminPage,
  })),
  { path: 'forbidden', loadComponent: publicPage },
  { path: '**', loadComponent: publicPage },
];
