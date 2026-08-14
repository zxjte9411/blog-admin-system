import { inject, Injectable } from '@angular/core';
import { CanActivateFn, Router } from '@angular/router';
import { HttpInterceptorFn } from '@angular/common/http';
import { catchError, finalize, map, of, shareReplay, switchMap, tap } from 'rxjs';
import { Observable } from 'rxjs';
import { AuthenticationApi, User, UserApi } from './api';
export type { User } from './api';

@Injectable({ providedIn: 'root' })
export class Auth {
  private readonly authenticationApi = inject(AuthenticationApi);
  private readonly userApi = inject(UserApi);
  private refreshRequest?: Observable<{ accessToken: string }>;

  user: User | null = null;

  get token() {
    return localStorage.getItem('blog-admin-token');
  }

  setToken(token: string) {
    localStorage.setItem('blog-admin-token', token);
  }

  clear() {
    localStorage.removeItem('blog-admin-token');
    this.user = null;
  }

  load() {
    return this.userApi.me().pipe(
      tap((user) => (this.user = user)),
      catchError(() => {
        this.clear();
        return of(null);
      }),
    );
  }

  saveLanguage(language: User['preferredLanguage']) {
    if (this.user) {
      this.user.preferredLanguage = language;
    }
    return this.userApi.profile({
      displayName: this.user?.displayName ?? '',
      preferredLanguage: language,
    });
  }

  refresh(): Observable<{ accessToken: string }> {
    this.refreshRequest ??= this.authenticationApi.refresh().pipe(
      tap((result) => this.setToken(result.accessToken)),
      finalize(() => (this.refreshRequest = undefined)),
      shareReplay({ bufferSize: 1, refCount: true }),
    );

    return this.refreshRequest;
  }
}

export const authGuard: CanActivateFn = (_route, state) => {
  const auth = inject(Auth);
  const router = inject(Router);
  const profileRoute = state.url?.split('?')[0] === '/account/profile';

  if (!auth.token) {
    return router.createUrlTree(['/login']);
  }

  return auth.user
    ? profileRoute || !!auth.user.displayName?.trim()
      ? true
      : router.createUrlTree(['/account/profile'])
    : auth
        .load()
        .pipe(
          map((user) =>
            user
              ? profileRoute || !!user.displayName?.trim()
                ? true
                : router.createUrlTree(['/account/profile'])
              : router.createUrlTree(['/login']),
          ),
        );
};

export const adminGuard: CanActivateFn = () => {
  const auth = inject(Auth);
  const router = inject(Router);

  if (!auth.token) {
    return router.createUrlTree(['/login']);
  }

  return (auth.user ? of(auth.user) : auth.load()).pipe(
    map((user) => (user?.role === 'ADMIN' ? true : router.createUrlTree(['/forbidden']))),
  );
};

export const authInterceptor: HttpInterceptorFn = (request, next) => {
  const token = localStorage.getItem('blog-admin-token');
  const auth = inject(Auth);
  const router = inject(Router);

  return next(
    token
      ? request.clone({
          setHeaders: { Authorization: `Bearer ${token}` },
        })
      : request,
  ).pipe(
    catchError((error: { status?: number }) => {
      if (
        error.status !== 401 ||
        request.url.endsWith('/auth/refresh') ||
        request.url.endsWith('/auth/login')
      ) {
        throw error;
      }

      return auth.refresh().pipe(
        catchError((refreshError) => {
          auth.clear();
          void router.navigateByUrl('/login');
          throw refreshError;
        }),
        switchMap((result) =>
          next(
            request.clone({
              setHeaders: { Authorization: `Bearer ${result.accessToken}` },
            }),
          ),
        ),
      );
    }),
  );
};
