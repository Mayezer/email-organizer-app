import { HttpErrorResponse } from '@angular/common/http';
import { Component, DestroyRef, inject, input, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { Subscription } from 'rxjs';
import { EmailApi, EmailAttachment, EmailOriginalContent } from './email-api';
import { EmailMetadata } from './email-page';

type OriginalState =
  | { status: 'loading' }
  | ({ status: 'ready' } & EmailOriginalContent)
  | { status: 'unavailable'; message: string }
  | { status: 'failed' };

type DownloadState = {
  id: string;
  status: 'loading' | 'saved' | 'failed';
  message: string;
};

@Component({
  selector: 'app-email-original',
  templateUrl: './email-original.html',
  styleUrl: './email-original.css',
})
export class EmailOriginal {
  readonly email = input.required<EmailMetadata>();
  protected readonly state = signal<OriginalState | null>(null);
  protected readonly download = signal<DownloadState | null>(null);
  private readonly api = inject(EmailApi);
  private readonly destroyRef = inject(DestroyRef);
  private request?: Subscription;
  private transfer?: Subscription;

  protected open(dialog: HTMLDialogElement): void {
    dialog.showModal();
    this.load();
  }

  protected load(): void {
    this.state.set({ status: 'loading' });
    this.request = this.api
      .readOriginal(this.email().gmail_id)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: ({ text, partial, links = [], attachments = [] }) =>
          this.state.set({
            status: 'ready',
            text,
            partial,
            links: browserDestinations(links),
            attachments,
          }),
        error: (error: HttpErrorResponse) => {
          if (error.status === 404 || error.status === 410) {
            this.state.set({ status: 'unavailable', message: 'Esta mensagem está indisponível.' });
          } else if (error.status === 422) {
            this.state.set({
              status: 'unavailable',
              message: 'O texto desta mensagem não pôde ser exibido.',
            });
          } else {
            this.state.set({ status: 'failed' });
          }
        },
      });
  }

  protected save(file: EmailAttachment): void {
    if (file.tooLarge || this.download()?.status === 'loading') {
      return;
    }
    this.download.set({ id: file.id, status: 'loading', message: 'Baixando anexo…' });
    this.transfer = this.api
      .downloadAttachment(file.gmailId, file.id)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (blob) => {
          saveFile(blob, file.filename);
          this.download.set({
            id: file.id,
            status: 'saved',
            message: 'Download enviado ao navegador.',
          });
        },
        error: ({ status }: HttpErrorResponse) => {
          this.download.set({ id: file.id, status: 'failed', message: downloadError(status) });
        },
      });
  }

  protected fileSize(size: number | null): string {
    if (size === null) {
      return 'Tamanho indisponível';
    }
    const unit = size >= 1_048_576 ? 'MiB' : size >= 1024 ? 'KiB' : 'B';
    const value = size / (unit === 'MiB' ? 1_048_576 : unit === 'KiB' ? 1024 : 1);
    return `${value.toLocaleString('pt-BR', { maximumFractionDigits: 1 })} ${unit}`;
  }

  protected close(dialog: HTMLDialogElement): void {
    dialog.close();
    this.clear();
  }

  protected clear(): void {
    this.request?.unsubscribe();
    this.request = undefined;
    this.transfer?.unsubscribe();
    this.transfer = undefined;
    this.download.set(null);
    this.state.set(null);
  }
}

function browserDestinations(links: string[]): string[] {
  const destinations = new Set<string>();
  for (const link of links) {
    if (!/^https?:\/\/[^/?#]/i.test(link) || /[\s\\]|%(?![\da-f]{2})/i.test(link)) {
      continue;
    }
    try {
      const url = new URL(link);
      if (
        (url.protocol === 'http:' || url.protocol === 'https:') &&
        !url.username &&
        !url.password
      ) {
        // Use the browser's exact destination for both the label and the href.
        destinations.add(url.href);
      }
    } catch {
      // An invalid destination never becomes an anchor.
    }
  }
  return [...destinations];
}

function downloadError(status: number): string {
  switch (status) {
    case 401:
    case 403:
      return 'Sua sessão não permite baixar este anexo. Entre novamente e tente baixar.';
    case 404:
    case 410:
      return 'Este anexo não está mais disponível.';
    case 413:
      return 'Este arquivo excede o limite de download.';
    default:
      return 'Não foi possível baixar o anexo. Tente novamente.';
  }
}

function saveFile(blob: Blob, filename: string): void {
  const url = URL.createObjectURL(blob);
  const link = document.createElement('a');
  link.href = url;
  link.download = filename;
  document.body.append(link);
  link.click();
  link.remove();
  // Give the browser time to take ownership of the download before releasing the URL.
  setTimeout(() => URL.revokeObjectURL(url), 1_000);
}
