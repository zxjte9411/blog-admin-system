import { ChangeDetectionStrategy, Component, Input } from '@angular/core';

@Component({
  selector: 'app-account-layout',
  standalone: true,
  templateUrl: './account-layout.html',
  styleUrl: './account-layout.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class AccountLayout {
  @Input() flat = false;
}
