import { CommonModule } from '@angular/common';
import { ChangeDetectionStrategy, Component, EventEmitter, Input, Output } from '@angular/core';
import { FormsModule } from '@angular/forms';

type Row = Record<string, unknown>;
type UserChange = { row: Row; value: unknown };

@Component({
  selector: 'app-admin-user-management',
  standalone: true,
  imports: [CommonModule, FormsModule],
  templateUrl: './admin-user-management.html',
  styleUrl: './admin-user-management.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class AdminUserManagement {
  @Input() items: Row[] = [];
  @Input() dataLabel = '';
  @Input() roleLabel = '';
  @Input() authorLabel = '';
  @Input() adminLabel = '';
  @Input() enabledLabel = '';
  @Input() disabledLabel = '';
  @Input() statusLabel = '';
  @Input() toggleLabel = '';
  @Input() updateLabel = '';
  @Input() canEditRow: (row: Row) => boolean = () => false;

  @Output() updateRow = new EventEmitter<Row>();
  @Output() toggleRow = new EventEmitter<Row>();
  @Output() roleChange = new EventEmitter<UserChange>();
  @Output() enabledChange = new EventEmitter<UserChange>();

  roleText(row: Row) {
    return row['role'] === 'AUTHOR' ? this.authorLabel : this.adminLabel;
  }

  stateText(row: Row) {
    return row['enabled'] === true ? this.enabledLabel : this.disabledLabel;
  }
}
