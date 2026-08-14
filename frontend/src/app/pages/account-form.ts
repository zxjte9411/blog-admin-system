import { CommonModule } from '@angular/common';
import {
  ChangeDetectionStrategy,
  Component,
  EventEmitter,
  inject,
  Input,
  Output,
} from '@angular/core';
import { FormGroup, ReactiveFormsModule } from '@angular/forms';
import { Language } from '../core/language';

@Component({
  selector: 'app-account-form',
  standalone: true,
  imports: [CommonModule, ReactiveFormsModule],
  templateUrl: './account-form.html',
  styleUrl: './account-use-case.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class AccountForm {
  readonly language = inject(Language);
  @Input({ required: true }) form!: FormGroup;
  @Input() fields: string[] = [];
  @Input() loading = false;
  @Input() submitAttempted = false;
  @Input() login = false;
  @Input() passwordHint = false;
  @Input() displayNameHint = false;
  @Output() submitted = new EventEmitter<void>();
  @Output() languageChanged = new EventEmitter<string>();

  fieldLabel(field: string) {
    return (this.language.t.field as Record<string, string>)[field] ?? field;
  }

  fieldErrorId(field: string) {
    return `field-${field}-error`;
  }

  fieldInvalid(field: string) {
    const control = this.form.controls[field];
    return !!control && (control.touched || this.submitAttempted) && control.invalid;
  }

  autocomplete(field: string) {
    if (field === 'currentPassword') return 'current-password';
    if (field === 'newPassword' || (!this.login && field === 'password')) return 'new-password';
    if (field === 'password') return 'current-password';
    return field === 'email' ? (this.login ? 'username' : 'email') : null;
  }
}
