import { HttpErrorResponse } from '@angular/common/http';
import { Component, computed, DestroyRef, effect, inject, signal, untracked } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { finalize, Subscription } from 'rxjs';
import { EmailApi } from './email-api';
import { EmailCard } from './email-card';
import { EMAIL_CATEGORIES, MANUAL_CATEGORIES } from './email-categories';
import { EmailChanges, EmailMetadata, EmailSyncJob } from './email-page';
import { EmailSync } from './email-sync';
import { EmailTrash } from './email-trash';

const PAGE_SIZE = 5;

@Component({
  selector: 'app-inbox',
  imports: [EmailCard],
  styleUrls: ['./inbox.css', './inbox-pagination.css', './inbox-categories.css'],
  templateUrl: './inbox.html',
})
export class Inbox {
  private readonly api = inject(EmailApi);
  private readonly destroyRef = inject(DestroyRef);
  private readonly sync = inject(EmailSync);
  private readonly trash = inject(EmailTrash);
  private readonly items = signal<readonly EmailMetadata[]>([]);
  private readonly firstLoaded = signal(false);
  private readonly nextCursor = signal<string | null>(null);
  private readonly terminalPage = signal(1);
  private readonly requestUpdates = new Map<string, EmailMetadata>();
  private liveMerged = false;
  private selectedId: string | null = null;
  protected readonly pageViewKey = signal(0);
  private request?: Subscription;

  protected readonly categories = EMAIL_CATEGORIES;
  protected readonly manualCategories = MANUAL_CATEGORIES;
  protected readonly category = signal<string | null>(null);
  protected readonly savingId = signal<string | null>(null);
  protected readonly categoryFeedback = signal('');
  protected readonly pageNumber = signal(1);
  protected readonly slideOffset = signal<string | null>(null);
  protected readonly failed = signal(false);
  protected readonly changeCursor = signal<string | null>(null);
  protected readonly refreshing = signal(false);
  protected readonly refreshFeedback = signal('');
  protected readonly syncJob = signal<EmailSyncJob | null>(null);
  private readonly pageCount = computed(
    () =>
      Math.max(this.terminalPage(), Math.ceil(this.items().length / PAGE_SIZE)) +
      (this.nextCursor() === null ? 0 : 1),
  );
  protected readonly page = computed(() => {
    const start = (this.pageNumber() - 1) * PAGE_SIZE;
    return !this.firstLoaded() || (start >= this.items().length && this.nextCursor() !== null)
      ? null
      : { items: this.items().slice(start, start + PAGE_SIZE) };
  });
  protected readonly hasMoreThanFivePages = computed(() => this.pageCount() > 5);
  protected readonly hasNextPage = computed(() => this.pageNumber() < this.pageCount());
  protected readonly pageNumbers = computed(() => {
    const first = Math.max(1, Math.min(this.pageNumber() - 2, this.pageCount() - 4));
    const last = Math.min(this.pageCount(), first + 4);
    return Array.from({ length: last - first + 1 }, (_, index) => first + index);
  });

  constructor() {
    this.ensurePage();
    effect(() => {
      const removed = this.trash.removedIds();
      const items = untracked(this.items);
      const visible = items.filter((email) => !removed.has(email.gmail_id));
      if (visible.length !== items.length) {
        untracked(() => {
          this.items.set(visible);
          this.keepPageInRange();
          this.ensurePage();
        });
      }
    });
  }

  protected selectCategory(category: string | null): void {
    if (category !== this.category()) {
      this.categoryFeedback.set('');
      this.category.set(category);
      this.resetPages();
    }
  }

  protected selectPage(number: number): void {
    if (number >= 1 && number <= this.pageCount() && number !== this.pageNumber()) {
      this.selectedId = null;
      this.pageViewKey.update((key) => key + 1);
      this.slideOffset.set(number > this.pageNumber() ? '1.25rem' : '-1.25rem');
      this.pageNumber.set(number);
      this.retry();
    }
  }

  protected retry(): void {
    this.failed.set(false);
    this.ensurePage();
  }

  protected refresh(): void {
    const cursor = this.changeCursor();
    if (cursor === null || this.refreshing()) {
      return;
    }

    this.refreshing.set(true);
    this.refreshFeedback.set('');
    this.sync
      .watch(cursor)
      .pipe(
        takeUntilDestroyed(this.destroyRef),
        finalize(() => this.refreshing.set(false)),
      )
      .subscribe({
        next: (event) => {
          if (event.kind === 'job') {
            this.syncJob.set(event.job);
          } else if (event.kind === 'batch') {
            this.applyChanges(event.batch);
          } else if (event.kind === 'finished') {
            const job = event.job;
            this.refreshFeedback.set(
              job.status === 'FAILED'
                ? 'A sincronização falhou. As mensagens já importadas continuam disponíveis.'
                : 'Sincronização concluída. ' +
                    job.imported_count +
                    (job.imported_count === 1 ? ' mensagem importada.' : ' mensagens importadas.') +
                    (job.analysis_failed_count > 0
                      ? ' Algumas mensagens tiveram falha na análise.'
                      : ''),
            );
          } else {
            this.refreshFeedback.set(
              'O acompanhamento foi pausado. Clique em Atualizar para continuar.',
            );
          }
        },
        error: () => {
          this.refreshFeedback.set(
            'Não foi possível acompanhar a sincronização. As mensagens continuam disponíveis. Clique em Atualizar para tentar novamente.',
          );
        },
      });
  }

  protected rememberEmail(gmailId: string): void {
    this.selectedId = gmailId;
  }

  protected assignCategory(event: Event, gmailId: string, form: HTMLFormElement): void {
    event.preventDefault();
    const category = new FormData(form).get('category');
    const option = this.manualCategories.find((candidate) => candidate.value === category);
    if (!option?.value || this.savingId() !== null) {
      return;
    }

    this.savingId.set(gmailId);
    this.categoryFeedback.set('');
    this.api
      .assignCategory(gmailId, option.value)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: () => {
          this.savingId.set(null);
          this.categoryFeedback.set(`E-mail categorizado em ${option.label}.`);
          this.resetPages();
        },
        error: (error: HttpErrorResponse) => {
          this.savingId.set(null);
          if (error.status === 409 || error.status === 404) {
            this.categoryFeedback.set(
              'Este e-mail já foi categorizado ou não está mais disponível.',
            );
            this.resetPages();
          } else if (error.status === 403) {
            this.categoryFeedback.set('Você não tem permissão para categorizar e-mails.');
          } else {
            this.categoryFeedback.set('Não foi possível categorizar o e-mail. Tente novamente.');
          }
        },
      });
  }

  private resetPages(): void {
    if (!this.refreshing()) this.refreshFeedback.set('');
    this.cancelPageRequest();
    this.items.set([]);
    this.firstLoaded.set(false);
    this.nextCursor.set(null);
    this.terminalPage.set(1);
    this.liveMerged = false;
    this.selectedId = null;
    this.pageNumber.set(1);
    this.pageViewKey.update((key) => key + 1);
    this.slideOffset.set(null);
    this.retry();
  }

  private cancelPageRequest(): void {
    this.request?.unsubscribe();
    this.request = undefined;
    this.requestUpdates.clear();
  }

  private matchesCategory(email: EmailMetadata): boolean {
    return (
      !email.trashed_at &&
      !this.trash.isRemoved(email.gmail_id) &&
      (this.category() === null || email.effective_category === this.category())
    );
  }

  private mergeItems(incoming: readonly EmailMetadata[], sort = false): void {
    const merged = new Map(this.items().map((email) => [email.gmail_id, email]));
    incoming.forEach((email) => merged.set(email.gmail_id, email));
    const items = [...merged.values()];
    this.items.set(
      sort
        ? items.sort(
            (a, b) =>
              Date.parse(b.imported_at) - Date.parse(a.imported_at) ||
              b.gmail_id.localeCompare(a.gmail_id),
          )
        : items,
    );
  }

  private applyChanges(batch: EmailChanges): void {
    if (batch.items.length > 0) this.liveMerged = true;
    if (this.request)
      batch.items.forEach((email) => this.requestUpdates.set(email.gmail_id, email));
    const updates = new Map(batch.items.map((email) => [email.gmail_id, email]));
    this.items.update((items) =>
      items.filter((email) => {
        const updated = updates.get(email.gmail_id);
        return !updated || this.matchesCategory(updated);
      }),
    );
    this.mergeItems(
      batch.items.filter((email) => this.matchesCategory(email)),
      true,
    );
    this.changeCursor.set(batch.change_cursor);
    this.keepPageInRange();
    this.ensurePage();
  }

  private keepPageInRange(): void {
    const selected = this.items().findIndex((email) => email.gmail_id === this.selectedId);
    if (selected >= 0) {
      // Keep the focused card in view even when new imports change its page number.
      // Only explicit navigation changes pageViewKey and restarts the animation.
      this.pageNumber.set(Math.floor(selected / PAGE_SIZE) + 1);
    } else {
      this.selectedId = null;
      this.pageNumber.set(Math.min(this.pageNumber(), this.pageCount()));
    }
  }

  private ensurePage(): void {
    if (
      this.request ||
      (this.firstLoaded() &&
        (this.nextCursor() === null || this.pageCount() > Math.max(5, this.pageNumber() + 1)))
    )
      return;

    const request = new Subscription();
    this.request = request;
    request.add(
      this.api
        .listPage(this.nextCursor(), this.category())
        .pipe(takeUntilDestroyed(this.destroyRef))
        .subscribe({
          next: (page) => {
            const incoming = page.items
              .map((email) => this.requestUpdates.get(email.gmail_id) ?? email)
              .filter(
                (email) =>
                  !email.trashed_at &&
                  !this.trash.isRemoved(email.gmail_id) &&
                  (!this.requestUpdates.has(email.gmail_id) || this.matchesCategory(email)),
              );
            const existing = new Map(this.items().map((email) => [email.gmail_id, email]));
            this.mergeItems(
              incoming.map((email) => existing.get(email.gmail_id) ?? email),
              this.liveMerged,
            );
            this.firstLoaded.set(true);
            this.nextCursor.set(page.next_cursor);
            if (page.next_cursor === null && page.items.length === 0) {
              this.terminalPage.set(this.pageNumber());
            }
            if (this.changeCursor() === null) this.changeCursor.set(page.change_cursor);
            this.request = undefined;
            this.requestUpdates.clear();
            this.ensurePage();
          },
          error: () => {
            this.request = undefined;
            this.requestUpdates.clear();
            if (this.page() === null) this.failed.set(true);
          },
        }),
    );
  }
}
