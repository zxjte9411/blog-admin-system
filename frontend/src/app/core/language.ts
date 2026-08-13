import { inject, Injectable, signal } from '@angular/core';
import { Auth } from './auth';

const dictionary = {
  'zh-TW': {
    title: '部落格管理',
    login: '登入',
    logout: '登出',
    submit: '送出',
    loading: '載入中…',
    empty: '目前沒有資料',
    forbidden: '您沒有權限',
    notFound: '找不到頁面',
    error: '目前無法完成操作',
    success: '已完成',
    nav: {
      articles: '文章管理',
      invitations: '邀請管理',
      password: '密碼設定',
      users: '使用者',
      profile: '個人資料',
      sessions: '登入工作階段',
      tags: '標籤',
      deletedArticles: '已刪除文章',
      email: '電子信箱',
    },
    authTitles: {
      register: '註冊',
      verifyEmail: '驗證電子信箱',
      verifyResend: '重新寄送驗證信',
      passwordReset: '申請重設密碼',
      resetPassword: '重設密碼',
      confirmEmail: '確認電子信箱',
      invite: '接受邀請',
    },
    history: {
      operatorId: '操作者',
      previousValue: '先前數值',
      newValue: '新數值',
      changedAt: '變更時間',
    },
    field: {
      email: '電子信箱',
      password: '密碼',
      displayName: '顯示名稱',
      preferredLanguage: '偏好語言',
      token: '驗證碼',
      currentPassword: '目前密碼',
      newPassword: '新密碼',
      title: '標題',
      content: '內容',
      status: '狀態',
      tagNames: '標籤',
      version: '版本',
      value: '數值',
      role: '角色',
      enabled: '啟用',
      minimumLength: '最小長度',
    },
    search: '搜尋標題',
    searchAction: '搜尋',
    invitationsHeading: '邀請',
    historyHeading: '密碼歷程',
    data: '資料',
    required: '必填',
    draft: '草稿',
    draftCode: '草稿',
    published: '已發布',
    publishedCode: '已發布',
    newArticle: '新增文章',
    revoke: '撤銷',
    sessionCurrent: '目前工作階段',
    sessionCreatedAt: '建立時間',
    sessionLastUsedAt: '最後使用時間',
    delete: '刪除',
    restore: '還原',
    role: '角色',
    enabled: '啟用',
    disabled: '停用',
    update: '更新',
    toggle: '切換啟用狀態',
    previous: '上一頁',
    next: '下一頁',
    page: '第',
    of: '頁，共',
    remove: '移除',
    filterArticles: '篩選文章',
    clearFilter: '清除篩選',
    authorAttribution: '作者署名',
    actions: '操作',
    edit: '編輯',
    conflict: '409 — 資料衝突',
    unauthorized: '401 — 請重新登入',
    confirmDelete: '刪除文章「{title}」？',
    roles: { AUTHOR: '作者', ADMIN: '管理員' },
    statuses: { DRAFT: '草稿', PUBLISHED: '已發布' },
  },
  en: {
    title: 'Blog Admin',
    login: 'Sign in',
    logout: 'Sign out',
    submit: 'Submit',
    loading: 'Loading…',
    empty: 'Nothing here yet',
    forbidden: 'You do not have permission',
    notFound: 'Page not found',
    error: 'Something went wrong',
    success: 'Done',
    nav: {
      articles: 'Articles',
      invitations: 'Invitations',
      password: 'Password settings',
      users: 'Users',
      profile: 'Profile',
      sessions: 'Sessions',
      tags: 'Tags',
      deletedArticles: 'Deleted articles',
      email: 'Email',
    },
    authTitles: {
      register: 'Register',
      verifyEmail: 'Verify email',
      verifyResend: 'Resend verification',
      passwordReset: 'Password reset',
      resetPassword: 'Reset password',
      confirmEmail: 'Confirm email',
      invite: 'Accept invitation',
    },
    history: {
      operatorId: 'Operator',
      previousValue: 'Previous value',
      newValue: 'New value',
      changedAt: 'Changed at',
    },
    field: {
      email: 'Email',
      password: 'Password',
      displayName: 'Display name',
      preferredLanguage: 'Preferred language',
      token: 'Token',
      currentPassword: 'Current password',
      newPassword: 'New password',
      title: 'Title',
      content: 'Content',
      status: 'Status',
      tagNames: 'Tags',
      version: 'Version',
      value: 'Value',
      role: 'Role',
      enabled: 'Enabled',
      minimumLength: 'Minimum length',
    },
    search: 'Search title',
    searchAction: 'Search',
    invitationsHeading: 'Invitations',
    historyHeading: 'Password history',
    data: 'Data',
    required: 'Required',
    draft: 'Draft',
    draftCode: 'DRAFT',
    published: 'Published',
    publishedCode: 'PUBLISHED',
    newArticle: 'New article',
    revoke: 'Revoke',
    sessionCurrent: 'Current session',
    sessionCreatedAt: 'Created at',
    sessionLastUsedAt: 'Last used at',
    delete: 'Delete',
    restore: 'Restore',
    role: 'Role',
    enabled: 'Enabled',
    disabled: 'Disabled',
    update: 'Update',
    toggle: 'Toggle enabled',
    previous: 'Previous',
    next: 'Next',
    page: 'Page',
    of: 'of',
    remove: 'Remove',
    filterArticles: 'Filter articles',
    clearFilter: 'Clear filter',
    authorAttribution: 'Author Attribution',
    actions: 'Actions',
    edit: 'Edit',
    conflict: '409 — Data conflict',
    unauthorized: '401 — Please sign in again',
    confirmDelete: 'Delete article "{title}"?',
    roles: { AUTHOR: 'Author', ADMIN: 'Administrator' },
    statuses: { DRAFT: 'Draft', PUBLISHED: 'Published' },
  },
} as const;

@Injectable({ providedIn: 'root' })
export class Language {
  private readonly auth = inject(Auth);

  readonly lang = signal<'zh-TW' | 'en'>(this.initial());
  readonly error = signal('');

  get t() {
    return dictionary[this.lang()];
  }

  set(lang: 'zh-TW' | 'en') {
    this.error.set('');
    this.lang.set(lang);
    localStorage.setItem('blog-admin-language', lang);

    if (this.auth.user) {
      this.auth.saveLanguage(lang).subscribe({ error: () => this.error.set(this.t.error) });
    }
  }

  usePreferred(lang: 'zh-TW' | 'en') {
    this.lang.set(lang);
    localStorage.setItem('blog-admin-language', lang);
  }

  private initial(): 'zh-TW' | 'en' {
    const value = localStorage.getItem('blog-admin-language');

    if (value === 'en' || value === 'zh-TW') {
      return value;
    }

    return navigator.language.toLowerCase().startsWith('en') ? 'en' : 'zh-TW';
  }
}
