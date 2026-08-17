import {
  ChangeDetectionStrategy,
  ChangeDetectorRef,
  Component,
  ElementRef,
  HostListener,
  Input,
  OnDestroy,
  OnInit,
  ViewChild,
  inject,
} from '@angular/core';
import { DOCUMENT } from '@angular/common';
import { HttpClient } from '@angular/common/http';
import { NavigationStart, Router, RouterLink, RouterLinkActive } from '@angular/router';
import { Subscription, catchError, defer, filter, forkJoin, of } from 'rxjs';
import { Auth } from '../core/auth';
import { Language } from '../core/language';
import { SUPABASE_AUTH } from '../core/supabase';

@Component({
  selector: 'app-shell',
  standalone: true,
  imports: [RouterLink, RouterLinkActive],
  templateUrl: './app-shell.html',
  changeDetection: ChangeDetectionStrategy.OnPush,
  styleUrl: './app-shell.scss',
})
export class AppShell implements OnInit, OnDestroy {
  readonly auth = inject(Auth);
  readonly language = inject(Language);
  private readonly http = inject(HttpClient);
  private readonly router = inject(Router);
  private readonly cdr = inject(ChangeDetectorRef);
  private readonly supabase = inject(SUPABASE_AUTH);
  private readonly document = inject(DOCUMENT);

  @ViewChild('menuButton') private menuButton?: ElementRef<HTMLButtonElement>;
  @ViewChild('mobileNavigation') private mobileNavigation?: ElementRef<HTMLElement>;

  @Input() loading = false;

  isMenuOpen = false;
  private previousFocusedElement: HTMLElement | null = null;
  private previousBodyOverflow = '';
  private readonly routerEventsSubscription = new Subscription();

  readonly navGroups = [
    { label: 'public', links: ['/public/articles', '/public/tags'] },
    {
      label: 'manage',
      links: [
        '/articles',
        '/articles/deleted',
        '/admin/users',
        '/admin/invitations',
        '/admin/settings/password',
      ],
    },
    {
      label: 'account',
      links: ['/account/profile', '/account/password', '/account/email', '/account/sessions'],
    },
  ];

  ngOnInit() {
    this.routerEventsSubscription.add(
      this.router.events
        .pipe(filter((event) => event instanceof NavigationStart))
        .subscribe(() => this.closeMenu()),
    );

    if (this.auth.token && !this.auth.user) {
      this.auth.load().subscribe(() => {
        this.cdr.markForCheck();
      });
    }
  }

  ngOnDestroy() {
    this.routerEventsSubscription.unsubscribe();
    if (this.isMenuOpen) {
      this.document.body.style.overflow = this.previousBodyOverflow;
    }
  }

  openMenu() {
    if (this.isMenuOpen) return;

    const activeElement = this.document.activeElement;
    this.previousFocusedElement =
      activeElement instanceof HTMLElement && activeElement !== this.document.body
        ? activeElement
        : (this.menuButton?.nativeElement ?? null);
    this.previousBodyOverflow = this.document.body.style.overflow;
    this.document.body.style.overflow = 'hidden';
    this.isMenuOpen = true;
    this.cdr.markForCheck();
    this.mobileNavigation?.nativeElement.focus();
  }

  closeMenu() {
    if (!this.isMenuOpen) return;

    this.isMenuOpen = false;
    this.document.body.style.overflow = this.previousBodyOverflow;
    this.cdr.markForCheck();

    const elementToFocus = this.previousFocusedElement;
    this.previousFocusedElement = null;
    elementToFocus?.focus();
  }

  toggleMenu() {
    if (this.isMenuOpen) {
      this.closeMenu();
    } else {
      this.openMenu();
    }
  }

  @HostListener('document:keydown', ['$event'])
  onDocumentKeydown(event: KeyboardEvent) {
    if (this.isMenuOpen && event.key === 'Escape') {
      event.preventDefault();
      this.closeMenu();
    }
  }

  onDrawerKeydown(event: KeyboardEvent) {
    if (event.key !== 'Tab' || !this.mobileNavigation) return;

    const focusableElements = Array.from(
      this.mobileNavigation.nativeElement.querySelectorAll<HTMLElement>(
        'a[href], button:not([disabled]), [tabindex]:not([tabindex="-1"])',
      ),
    ).filter((element) => !element.hidden && !element.closest('[hidden]'));
    if (!focusableElements.length) {
      event.preventDefault();
      return;
    }

    const first = focusableElements[0];
    const last = focusableElements[focusableElements.length - 1];
    const activeElement = this.document.activeElement;
    if (
      event.shiftKey &&
      (activeElement === first || activeElement === this.mobileNavigation.nativeElement)
    ) {
      event.preventDefault();
      last.focus();
    } else if (
      !event.shiftKey &&
      (activeElement === last || activeElement === this.mobileNavigation.nativeElement)
    ) {
      event.preventDefault();
      first.focus();
    }
  }

  toggleLanguage() {
    const next = this.language.lang() === 'en' ? 'zh-TW' : 'en';
    this.language.set(next);
  }

  navGroupLabel(group: string) {
    const labels =
      this.language.lang() === 'en'
        ? { public: 'Public content', manage: 'Management', account: 'Your account' }
        : { public: '公開內容', manage: '管理工作區', account: '個人設定' };
    return labels[group as keyof typeof labels] ?? group;
  }

  navLabel(link: string) {
    if (link === '/public/articles') return this.language.t.nav.publicArticles;
    if (link.includes('/public/tags')) return this.language.t.nav.tags;
    if (link.includes('/articles/deleted')) return this.language.t.nav.deletedArticles;
    if (link.includes('invitations')) return this.language.t.nav.invitations;
    if (link.includes('settings/password')) return this.language.t.nav.password;
    if (link.includes('users')) return this.language.t.nav.users;
    if (link.includes('articles')) return this.language.t.nav.articles;
    if (link.includes('profile')) return this.language.t.nav.profile;
    if (link.includes('account/password')) return this.language.t.field.password;
    if (link.includes('account/email')) return this.language.t.field.email;
    return this.language.t.nav.sessions;
  }

  canSeeNav(link: string) {
    if (link === '/public/articles' || link === '/public/tags') return true;
    if (!this.auth.user) return false;
    return (
      !['/admin/users', '/admin/invitations', '/admin/settings/password'].includes(link) ||
      this.auth.user.role === 'ADMIN'
    );
  }

  hasVisibleLinks(group: { links: string[] }) {
    return group.links.some((link) => this.canSeeNav(link));
  }

  onNavLinkClick() {
    this.closeMenu();
  }

  logout() {
    forkJoin([
      this.http
        .post('/api/v1/auth/logout', {}, { withCredentials: true })
        .pipe(catchError(() => of(null))),
      defer(() => this.supabase.signOut({ scope: 'local' })).pipe(catchError(() => of(null))),
    ]).subscribe(() => {
      this.auth.clear();
      void this.router.navigateByUrl('/login');
    });
  }
}
