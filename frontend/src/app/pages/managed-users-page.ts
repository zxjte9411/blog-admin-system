import { CommonModule } from '@angular/common';
import {
  ChangeDetectionStrategy,
  ChangeDetectorRef,
  Component,
  OnInit,
  inject,
} from '@angular/core';
import { HttpErrorResponse } from '@angular/common/http';
import { AdminUserApi, ManagedUser } from '../core/api';
import { Auth } from '../core/auth';
import { Language } from '../core/language';
import { AppShell } from '../layouts/app-shell';
import { AdminUserManagement } from './admin-user-management/admin-user-management';

type Row = Record<string, unknown>;

@Component({
  selector: 'app-managed-users-page',
  standalone: true,
  imports: [CommonModule, AppShell, AdminUserManagement],
  templateUrl: './managed-users-page.html',
  styleUrl: './admin-pages.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ManagedUsersPage implements OnInit {
  readonly auth = inject(Auth);
  readonly language = inject(Language);
  private readonly api = inject(AdminUserApi);
  private readonly cdr = inject(ChangeDetectorRef);
  items: Row[] = [];
  listLoading = false;
  updatingRowId: string | null = null;
  error = '';
  message = '';

  ngOnInit() {
    this.read();
  }

  canEditRow = (row: Row) => row['id'] !== this.auth.user?.id;

  updateUserRole(change: { row: Row; value: unknown }) {
    if (change.value !== 'AUTHOR' && change.value !== 'ADMIN') return;
    const current = this.current(change.row);
    const previous = { ...current };
    const updated = { ...current, role: change.value };
    this.replace(current, updated);
    this.update(updated, previous);
  }

  updateUserEnabled(change: { row: Row; value: unknown }) {
    if (typeof change.value !== 'boolean') return;
    const current = this.current(change.row);
    const previous = { ...current };
    const updated = { ...current, enabled: change.value };
    this.replace(current, updated);
    this.update(updated, previous);
  }

  toggleUserEnabled(row: Row) {
    const current = this.current(row);
    if (current['enabled'] === true) {
      const name = String(current['displayName'] || current['email'] || current['id']);
      if (!window.confirm(this.language.t.confirmDisable.replace('{name}', name))) return;
    }
    this.updateUserEnabled({ row: current, value: current['enabled'] !== true });
  }

  retry() {
    this.error = '';
    this.read();
  }

  private current(row: Row) {
    return this.items.find((item) => item['id'] === row['id']) ?? row;
  }

  private replace(row: Row, changes: Row) {
    this.items = this.items.map((item) => (item['id'] === row['id'] ? changes : item));
    this.cdr.markForCheck();
  }

  private update(row: Row, previous: Row) {
    const id = String(row['id']);
    this.updatingRowId = id;
    this.error = '';
    this.api
      .update(id, {
        role: row['role'] as ManagedUser['role'],
        enabled: row['enabled'] as boolean,
      })
      .subscribe({
        next: (updated) => {
          this.replace(row, (updated as unknown as Row) || row);
          this.updatingRowId = null;
          this.message = this.language.t.success;
          this.cdr.markForCheck();
        },
        error: (e: HttpErrorResponse) => {
          this.replace(row, previous);
          this.fail(e.status, 'row');
        },
      });
  }

  private read() {
    this.listLoading = true;
    this.error = '';
    this.cdr.markForCheck();
    this.api.list().subscribe({
      next: (users) => {
        this.items = users as unknown as Row[];
        this.listLoading = false;
        this.cdr.markForCheck();
      },
      error: (e: HttpErrorResponse) => this.fail(e.status, 'list'),
    });
  }

  private fail(status: number, source: 'list' | 'row') {
    if (source === 'list') this.listLoading = false;
    else this.updatingRowId = null;
    this.message = '';
    this.error =
      status === 401
        ? this.language.t.unauthorized
        : status === 403
          ? this.language.t.forbidden
          : status === 409
            ? this.language.t.conflict
            : this.language.t.error;
    this.cdr.markForCheck();
  }
}
