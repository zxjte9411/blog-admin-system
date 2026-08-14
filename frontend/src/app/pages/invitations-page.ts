import { CommonModule } from '@angular/common';
import { HttpErrorResponse } from '@angular/common/http';
import {
  ChangeDetectionStrategy,
  ChangeDetectorRef,
  Component,
  OnInit,
  inject,
} from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { AdminUserApi, Invitation } from '../core/api';
import { Language } from '../core/language';
import { AppShell } from '../layouts/app-shell';

@Component({
  selector: 'app-invitations-page',
  standalone: true,
  imports: [CommonModule, ReactiveFormsModule, AppShell],
  templateUrl: './invitations-page.html',
  styleUrl: './admin-pages.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class InvitationsPage implements OnInit {
  readonly language = inject(Language);
  readonly form = inject(FormBuilder).nonNullable.group({
    email: ['', [Validators.required, Validators.email]],
  });
  private readonly api = inject(AdminUserApi);
  private readonly cdr = inject(ChangeDetectorRef);
  items: Invitation[] = [];
  loading = false;
  submitted = false;
  error = '';
  message = '';

  ngOnInit() {
    this.read();
  }

  submit() {
    this.submitted = true;
    this.message = '';
    if (this.form.invalid) {
      this.form.markAllAsTouched();
      return;
    }
    this.loading = true;
    this.error = '';
    this.api.invite(this.form.getRawValue()).subscribe({
      next: () => {
        this.form.reset();
        this.submitted = false;
        this.message = this.language.t.success;
        this.read();
      },
      error: (e: HttpErrorResponse) => this.fail(e.status),
    });
  }

  fieldHasError() {
    const control = this.form.controls.email;
    return control.invalid && (control.touched || this.submitted);
  }

  retry() {
    this.error = '';
    this.read();
  }

  private read() {
    this.loading = true;
    this.api.invitations().subscribe({
      next: (items) => {
        this.items = items;
        this.loading = false;
        this.cdr.markForCheck();
      },
      error: (e: HttpErrorResponse) => this.fail(e.status),
    });
  }

  private fail(status: number) {
    this.loading = false;
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
