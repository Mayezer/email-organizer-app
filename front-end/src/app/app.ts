import { afterRenderEffect, Component, ElementRef, inject, signal, viewChild } from '@angular/core';
import { AppSession } from './app-session';
import { Inbox } from './inbox/inbox';
import { Board } from './board/board';
import { EmailTrash } from './inbox/email-trash';

@Component({
  imports: [Inbox, Board],
  selector: 'app-root',
  styleUrl: './app.css',
  templateUrl: './app.html',
})
export class App {
  protected readonly view = signal<'list' | 'board'>('list');
  protected readonly session = inject(AppSession);
  protected readonly trash = inject(EmailTrash);
  private readonly trashStatus = viewChild<ElementRef<HTMLParagraphElement>>('trashStatus');

  constructor() {
    this.session.refresh();
    afterRenderEffect(() => {
      if (this.trash.feedback()) this.trashStatus()?.nativeElement.focus();
    });
  }

  protected submitLogin(event: SubmitEvent, username: string, password: HTMLInputElement): void {
    event.preventDefault();
    this.session.signIn(username, password.value);
    password.value = '';
  }
}
