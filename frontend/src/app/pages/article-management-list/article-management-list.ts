import { CommonModule } from '@angular/common';
import { ChangeDetectionStrategy, Component, EventEmitter, Input, Output } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { getPageNumbers } from '../../core/pagination';
import { Article } from '../../core/api';

export type LegacyArticleRow = {
  id?: unknown;
  title?: unknown;
  status?: unknown;
  owner?: unknown;
  ownerId?: unknown;
  authorAttribution?: unknown;
  createdAt?: string | number | Date | null;
};
export type ManagementRow = Article | LegacyArticleRow;

@Component({
  selector: 'app-article-management-list',
  standalone: true,
  imports: [CommonModule, FormsModule],
  templateUrl: './article-management-list.html',
  styleUrl: './article-management-list.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ArticleManagementList {
  @Input() items: ManagementRow[] = [];
  @Input() loading = false;
  @Input() totalPages = 0;
  @Input() page = 0;
  @Input() searchTitle = '';
  @Input() canManageArticle: (row: ManagementRow) => boolean = () => false;
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
  @Output() open = new EventEmitter<ManagementRow>();
  @Output() delete = new EventEmitter<{ row: ManagementRow; trigger: EventTarget | null }>();
  @Output() previousPage = new EventEmitter<void>();
  @Output() nextPage = new EventEmitter<void>();
  @Output() selectPage = new EventEmitter<number>();

  readonly dateFormat = 'yyyy-MM-dd';

  get pageNumbers(): number[] {
    return getPageNumbers(this.page, this.totalPages);
  }

  rowId(row: ManagementRow) {
    return row.id;
  }
  rowTitle(row: ManagementRow) {
    return row.title || row.id;
  }
  rowStatus(row: ManagementRow) {
    return row.status;
  }
  rowAuthor(row: ManagementRow) {
    return row.authorAttribution;
  }
  dateValue(row: ManagementRow) {
    return row.createdAt;
  }
}
