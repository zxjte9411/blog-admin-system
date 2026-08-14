import { ChangeDetectorRef, inject } from '@angular/core';
import { FormBuilder, FormGroup, Validators } from '@angular/forms';
import { HttpErrorResponse } from '@angular/common/http';
import { Auth } from '../core/auth';
import { Language } from '../core/language';

export type FormValue = Record<string, string>;

export abstract class FormUseCase {
  protected readonly fb = inject(FormBuilder);
  protected readonly cdr = inject(ChangeDetectorRef);
  readonly auth = inject(Auth);
  readonly language = inject(Language);
  readonly fields: string[];
  readonly form: FormGroup;
  loading = false;
  submitAttempted = false;
  error = '';
  message = '';

  protected constructor(fields: string[]) {
    this.fields = fields;
    this.form = this.fb.group(
      Object.fromEntries(
        fields.map((field) => [
          field,
          ['', field === 'email' ? [Validators.required, Validators.email] : Validators.required],
        ]),
      ),
    );
  }

  protected valid(): boolean {
    this.submitAttempted = true;
    this.message = '';
    if (this.form.invalid) {
      this.form.markAllAsTouched();
      return false;
    }
    this.loading = true;
    this.error = '';
    return true;
  }

  protected done() {
    this.loading = false;
    this.error = '';
    this.message = this.language.t.success;
    this.cdr.markForCheck();
  }

  protected fail(status: number) {
    this.loading = false;
    this.message = '';
    this.error =
      status === 401
        ? this.language.t.unauthorized
        : status === 403
          ? this.language.t.forbidden
          : status === 404
            ? this.language.t.notFound
            : status === 409
              ? this.language.t.conflict
              : this.language.t.error;
    this.cdr.markForCheck();
  }

  protected handleError(error: HttpErrorResponse) {
    this.fail(error.status);
  }
}
