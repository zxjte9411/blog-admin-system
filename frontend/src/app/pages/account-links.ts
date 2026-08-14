import { ChangeDetectionStrategy, Component, Input } from '@angular/core';
import { RouterLink } from '@angular/router';

export interface AccountLink {
  url: string;
  label: string;
}

@Component({
  selector: 'app-account-links',
  standalone: true,
  imports: [RouterLink],
  template: `
    <nav class="account-links" aria-label="{{ ariaLabel }}">
      @for (link of links; track link.url) {
        <a [routerLink]="link.url">{{ link.label }}</a>
      }
    </nav>
  `,
  styleUrl: './account-use-case.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class AccountLinks {
  @Input() links: AccountLink[] = [];
  @Input() ariaLabel = '';
}
