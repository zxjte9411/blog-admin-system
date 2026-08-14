import { Component } from '@angular/core';
import { ReactiveFormsModule } from '@angular/forms';
import { AppShell } from '../layouts/app-shell';
import { ArticleEditorPage } from './article-editor-page';
@Component({
  selector: 'app-article-edit-page',
  standalone: true,
  imports: [ReactiveFormsModule, AppShell],
  templateUrl: './article-editor-page.html',
  styleUrl: './admin-page.scss',
})
export class ArticleEditPage extends ArticleEditorPage {}
