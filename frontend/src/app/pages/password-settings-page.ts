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
import { AdminSettingsApi, PasswordSettingChange } from '../core/api';
import { Language } from '../core/language';
import { AppShell } from '../layouts/app-shell';

@Component({
  selector: 'app-password-settings-page',
  standalone: true,
  imports: [CommonModule, ReactiveFormsModule, AppShell],
  templateUrl: './password-settings-page.html',
  styleUrl: './admin-pages.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class PasswordSettingsPage implements OnInit {
  readonly language = inject(Language);
  readonly form = inject(FormBuilder).nonNullable.group({
    value: [8, [Validators.required, Validators.min(8), Validators.max(128)]],
  });
  private readonly api = inject(AdminSettingsApi);
  private readonly cdr = inject(ChangeDetectorRef);
  items: PasswordSettingChange[] = [];
  currentMinimum: number | null = null;
  loading = false;
  submitted = false;
  error = '';
  message = '';

  ngOnInit() {
    this.readMinimum();
    this.readHistory();
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
    this.api.setPasswordMinimumLength(this.form.getRawValue().value).subscribe({
      next: ({ value }) => {
        this.currentMinimum = value;
        this.form.patchValue({ value });
        this.loading = false;
        this.submitted = false;
        this.message = this.language.t.success;
        this.readHistory();
        this.cdr.markForCheck();
      },
      error: (e: HttpErrorResponse) => this.fail(e.status),
    });
  }

  fieldHasError() {
    const control = this.form.controls.value;
    return control.invalid && (control.touched || this.submitted);
  }

  retry() {
    this.error = '';
    this.readMinimum();
    this.readHistory();
  }

  private readMinimum() {
    this.loading = true;
    this.api.passwordMinimumLength().subscribe({
      next: ({ value }) => {
        this.currentMinimum = value;
        this.form.patchValue({ value });
        this.loading = false;
        this.cdr.markForCheck();
      },
      error: (e: HttpErrorResponse) => this.fail(e.status),
    });
  }

  private readHistory() {
    this.api.passwordMinimumLengthHistory().subscribe({
      next: (items) => {
        this.items = items;
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
