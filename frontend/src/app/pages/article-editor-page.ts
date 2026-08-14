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
import { ActivatedRoute, CanDeactivateFn, Router } from '@angular/router';
import {
  ArticleApi,
  CreateArticleRequest,
  PublicArticleApi,
  PublicTag,
  UpdateArticleRequest,
} from '../core/api';
import { Language } from '../core/language';
import { AppShell } from '../layouts/app-shell';

@Component({
  standalone: true,
  imports: [CommonModule, ReactiveFormsModule, AppShell],
  templateUrl: './article-editor-page.html',
  styleUrl: './admin-page.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ArticleEditorPage implements OnInit {
  protected readonly language = inject(Language);
  protected readonly route = inject(ActivatedRoute);
  protected readonly router = inject(Router);
  protected readonly api = inject(ArticleApi);
  protected readonly tagsApi = inject(PublicArticleApi);
  protected readonly cdr = inject(ChangeDetectorRef);
  private readonly fb = inject(FormBuilder);
  readonly form = this.fb.nonNullable.group({
    title: ['', Validators.required],
    content: ['', Validators.required],
    tagNames: [''],
    status: ['DRAFT', Validators.required],
    version: [0],
  });
  availableTags: PublicTag[] = [];
  selectedTagIds = new Set<string>();
  loading = false;
  error = '';
  submitted = false;
  protected edit = false;
  ngOnInit() {
    this.edit = this.route.snapshot.routeConfig?.path === 'articles/:id/edit';
    this.tagsApi.tags(undefined, 100).subscribe((r) => {
      this.availableTags = r.content ?? [];
      this.cdr.markForCheck();
    });
    if (this.edit) this.load();
  }
  toggleTag(id: string, event: Event) {
    if ((event.target as HTMLInputElement).checked) this.selectedTagIds.add(id);
    else this.selectedTagIds.delete(id);
    this.form.markAsDirty();
  }
  submit() {
    this.submitted = true;
    if (this.form.invalid) {
      this.form.markAllAsTouched();
      return;
    }
    const value = this.form.getRawValue();
    const body = {
      ...value,
      tagIds: [...this.selectedTagIds],
      tagNames: value.tagNames
        .split(',')
        .map((x) => x.trim())
        .filter(Boolean),
    };
    this.loading = true;
    const request = this.edit
      ? this.api.update(this.route.snapshot.paramMap.get('id')!, body as UpdateArticleRequest)
      : this.api.create(body as CreateArticleRequest);
    request.subscribe({
      next: () => {
        this.form.markAsPristine();
        void this.router.navigateByUrl('/articles');
      },
      error: (e: HttpErrorResponse) => this.fail(e.status),
    });
  }
  fieldHasError(name: string) {
    const c = this.form.get(name);
    return !!c && c.invalid && (c.touched || this.submitted);
  }
  fieldError(name: string) {
    return this.form.get(name)?.hasError('required')
      ? this.language.t.required
      : this.language.t.error;
  }
  private load() {
    this.loading = true;
    this.api.get(this.route.snapshot.paramMap.get('id')!).subscribe({
      next: (a) => {
        this.form.patchValue({
          title: a.title,
          content: a.content,
          status: a.status,
          version: a.version,
          tagNames: a.tagNames.join(', '),
        });
        this.selectedTagIds = new Set(a.tagIds);
        this.loading = false;
        this.cdr.markForCheck();
      },
      error: (e: HttpErrorResponse) => this.fail(e.status),
    });
  }
  private fail(status: number) {
    this.loading = false;
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
}
export const canLeaveArticle: CanDeactivateFn<ArticleEditorPage> = (component) =>
  !component.form.dirty || window.confirm('離開此頁面？未儲存的變更將會遺失。');
