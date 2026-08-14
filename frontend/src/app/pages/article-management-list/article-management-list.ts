import { CommonModule } from '@angular/common';
import { ChangeDetectionStrategy, Component, EventEmitter, Input, Output } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { getPageNumbers } from '../../core/pagination';

type Row = Record<string, unknown>;

@Component({
  selector: 'app-article-management-list',
  standalone: true,
  imports: [CommonModule, FormsModule],
  templateUrl: './article-management-list.html',
  styleUrl: './article-management-list.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ArticleManagementList {
  @Input() items: Row[] = [];
  @Input() loading = false;
  @Input() totalPages = 0;
  @Input() page = 0;
  @Input() searchTitle = '';
  @Input() canManageArticle: (row: Row) => boolean = () => false;
  @Input() statusLabel = '';
  @Input() draftLabel = '';
  @Input() publishedLabel = '';
  @Input() titleLabel = '';
  @Input() authorLabel = '';
  @Input() createdLabel = '';
  @Input() actionsLabel = '';
  @Input() caption = '';
  @Input() emptyLabel = '';
  @Input() loadingLabel = '';
  @Input() editLabel = '';
  @Input() deleteLabel = '';
  @Input() searchLabel = '';
  @Input() previousLabel = '';
  @Input() nextLabel = '';
  @Input() pageLabel = '';
  @Input() ofLabel = '';
  @Input() pageSuffix = '';

  @Output() searchChange = new EventEmitter<string>();
  @Output() open = new EventEmitter<Row>();
  @Output() delete = new EventEmitter<Row>();
  @Output() previousPage = new EventEmitter<void>();
  @Output() nextPage = new EventEmitter<void>();
  @Output() selectPage = new EventEmitter<number>();

  readonly dateFormat = 'yyyy-MM-dd';

  get pageNumbers(): number[] {
    return getPageNumbers(this.page, this.totalPages);
  }

  dateValue(row: Row) {
    return row['createdAt'] as string | number | Date | null | undefined;
  }
}
