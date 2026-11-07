import { HttpErrorResponse } from '@angular/common/http';
import { effect, inject, Injectable, signal } from '@angular/core';
import { finalize } from 'rxjs';
import { AppSession } from '../app-session';
import { EmailApi } from './email-api';

@Injectable({ providedIn: 'root' })
export class EmailTrash {
  private readonly api = inject(EmailApi);
  private readonly session = inject(AppSession);
  private readonly removed = signal<ReadonlySet<string>>(new Set());
  readonly removedIds = this.removed.asReadonly();
  readonly pendingId = signal<string | null>(null);
  readonly feedback = signal('');
  readonly error = signal('');

  constructor() {
    effect(() => {
      if (!this.session.gmailAccount()) {
        this.removed.set(new Set());
        this.feedback.set('');
        this.error.set('');
      }
    });
  }

  isRemoved(gmailId: string): boolean {
    return this.removed().has(gmailId);
  }

  confirm(gmailId: string, close: () => void): void {
    if (this.pendingId() !== null || this.isRemoved(gmailId)) return;
    const account = this.session.gmailAccount();
    this.pendingId.set(gmailId);
    this.feedback.set('');
    this.error.set('');
    this.api
      .trash(gmailId)
      .pipe(finalize(() => this.pendingId.set(null)))
      .subscribe({
        next: ({ status }) => {
          if (this.session.gmailAccount() !== account) return;
          close();
          this.removed.update((ids) => new Set([...ids, gmailId]));
          this.feedback.set(
            status === 202
              ? 'E-mail na Lixeira do Gmail. Clique em Atualizar para confirmar a remoção no App.'
              : 'E-mail movido para a Lixeira do Gmail.',
          );
        },
        error: ({ status }: HttpErrorResponse) => {
          if (this.session.gmailAccount() !== account) return;
          this.error.set(
            status === 403
              ? 'A Lixeira precisa de autorização de edição na conta Gmail vinculada.'
              : status === 404
                ? 'Este e-mail não está mais disponível.'
                : 'Não foi possível confirmar a movimentação. Tente novamente ou clique em Atualizar.',
          );
        },
      });
  }
}
