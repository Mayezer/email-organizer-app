import { Component, computed, inject, input, signal } from '@angular/core';
import { EmailMetadata } from './email-page';
import { EmailTrash } from './email-trash';

@Component({
  selector: 'app-email-trash-action',
  templateUrl: './email-trash-action.html',
  styleUrl: './email-trash-action.css',
})
export class EmailTrashAction {
  readonly email = input.required<EmailMetadata>();
  private readonly trash = inject(EmailTrash);
  private readonly attemptedTrash = signal(false);
  protected readonly trashBusy = computed(() => this.trash.pendingId() === this.email().gmail_id);
  protected readonly trashError = computed(() => (this.attemptedTrash() ? this.trash.error() : ''));

  protected openTrash(dialog: HTMLDialogElement): void {
    this.attemptedTrash.set(false);
    dialog.showModal();
  }

  protected confirmTrash(dialog: HTMLDialogElement): void {
    this.attemptedTrash.set(true);
    this.trash.confirm(this.email().gmail_id, () => {
      if (dialog.open) dialog.close();
    });
  }

  protected preventTrashCancel(event: Event): void {
    if (this.trashBusy()) event.preventDefault();
  }
}
