import { ArticleEditorPage } from './article-editor-page';
import { Component } from '@angular/core';
import { ReactiveFormsModule } from '@angular/forms';
import { AppShell } from '../layouts/app-shell';
@Component({
  selector: 'app-article-create-page',
  standalone: true,
  imports: [ReactiveFormsModule, AppShell],
  templateUrl: './article-editor-page.html',
  styleUrl: './admin-page.scss',
})
export class ArticleCreatePage extends ArticleEditorPage {}
