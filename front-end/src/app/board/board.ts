import { HttpErrorResponse } from '@angular/common/http';
import {
  Component,
  computed,
  DestroyRef,
  effect,
  ElementRef,
  inject,
  signal,
  untracked,
  viewChild,
} from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { catchError, defer, EMPTY, finalize, mergeMap, Subject, tap, timeout } from 'rxjs';
import { EmailApi } from '../inbox/email-api';
import { CategorySubmission } from '../inbox/email-card';
import { EMAIL_CATEGORIES, MANUAL_CATEGORIES } from '../inbox/email-categories';
import { EmailMetadata, EmailPage, EmailSyncJob } from '../inbox/email-page';
import { EmailSync } from '../inbox/email-sync';
import { EmailTrash } from '../inbox/email-trash';
import { BoardColumn } from './board-column';
import {
  BOARD_COLUMNS,
  BoardColumnState,
  belongsToColumn,
  columnPageCount,
  COLUMN_PAGE_SIZE,
  emptyColumn,
} from './board-column-state';

interface PageRequest {
  id: string;
  cursor: string | null;
  at: string | null;
}

@Component({
  selector: 'app-board',
  imports: [BoardColumn],
  templateUrl: './board.html',
  styleUrls: ['./board.css', '../inbox/inbox-categories.css'],
})
export class Board {
  private readonly api = inject(EmailApi);
  private readonly sync = inject(EmailSync);
  private readonly trash = inject(EmailTrash);
  private readonly destroyRef = inject(DestroyRef);
  private readonly requests = new Subject<PageRequest>();
  private readonly activeUpdates = new Map<string, Map<string, EmailMetadata>>();
  protected readonly states = signal<Record<string, BoardColumnState>>(
    Object.fromEntries(BOARD_COLUMNS.map((column) => [column.id, emptyColumn()])),
  );
  protected readonly categories = EMAIL_CATEGORIES;
  protected readonly filter = signal<string | null>(null);
  protected readonly visibleColumns = computed(() =>
    BOARD_COLUMNS.filter(
      (column) =>
        this.filter() === null ||
        (column.status === 'COMPLETED' && column.category === this.filter()),
    ),
  );
  protected readonly changeCursor = signal<string | null>(null);
  protected readonly initialFailed = signal(false);
  protected readonly initializing = signal(false);
  protected readonly refreshing = signal(false);
  protected readonly job = signal<EmailSyncJob | null>(null);
  protected readonly feedback = signal('');
  protected readonly categoryFeedback = signal('');
  protected readonly savingId = signal<string | null>(null);
  protected readonly movingId = signal<string | null>(null);
  protected readonly moveFeedback = signal('');
  private readonly moveStatus = viewChild.required<ElementRef<HTMLParagraphElement>>('moveStatus');

  constructor() {
    this.requests
      .pipe(
        mergeMap(
          (request) =>
            defer(() => {
              const column = BOARD_COLUMNS.find((item) => item.id === request.id)!;
              const updates = new Map<string, EmailMetadata>();
              this.activeUpdates.set(request.id, updates);
              return this.api
                .listPage(request.cursor, null, request.at, column.status, column.category)
                .pipe(
                  timeout(6_000),
                  tap((page) => this.acceptPage(request.id, page, updates)),
                  catchError(() => {
                    this.patch(request.id, { loading: false, failed: true });
                    return EMPTY;
                  }),
                  finalize(() => this.activeUpdates.delete(request.id)),
                );
            }),
          3,
        ),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe();
    this.initialize();
    effect(() => {
      const removedIds = this.trash.removedIds();
      const removed = [...untracked(() => this.knownEmails()).values()].filter((email) =>
        removedIds.has(email.gmail_id),
      );
      if (removed.length > 0) untracked(() => this.applyItems(removed));
    });
  }

  protected initialize(): void {
    if (this.initializing()) return;
    this.initializing.set(true);
    this.initialFailed.set(false);
    // Establish one confirmed import snapshot before loading the nine columns.
    this.api
      .listPage(null, null)
      .pipe(
        timeout(6_000),
        takeUntilDestroyed(this.destroyRef),
        finalize(() => this.initializing.set(false)),
      )
      .subscribe({
        next: (page) => {
          this.changeCursor.set(page.change_cursor);
          BOARD_COLUMNS.forEach((column) => this.load(column.id));
        },
        error: () => this.initialFailed.set(true),
      });
  }

  protected selectFilter(value: string | null): void {
    this.filter.set(value);
  }

  protected selectPage(id: string, page: number): void {
    const state = this.states()[id];
    if (page < 1 || page > columnPageCount(state) || page === state.page) return;
    this.patch(id, {
      page,
      selectedId: null,
      animationKey: state.animationKey + 1,
      direction: page > state.page ? '1rem' : '-1rem',
    });
    if (page * COLUMN_PAGE_SIZE > state.items.length && state.nextCursor !== null) this.load(id);
  }

  protected remember(id: string, gmailId: string): void {
    this.patch(id, { selectedId: gmailId });
  }

  protected load(id: string): void {
    const state = this.states()[id];
    if (state.loading || (state.loaded && state.nextCursor === null)) return;
    this.patch(id, { loading: true, failed: false });
    this.requests.next({
      id,
      cursor: state.nextCursor,
      at: state.nextCursor === null ? this.changeCursor() : null,
    });
  }

  protected refresh(): void {
    const cursor = this.changeCursor();
    if (cursor === null || this.refreshing()) return;
    this.refreshing.set(true);
    this.feedback.set('');
    this.sync
      .watch(cursor)
      .pipe(
        takeUntilDestroyed(this.destroyRef),
        finalize(() => this.refreshing.set(false)),
      )
      .subscribe({
        next: (event) => {
          if (event.kind === 'job') this.job.set(event.job);
          else if (event.kind === 'batch') {
            this.applyItems(event.batch.items);
            this.changeCursor.set(event.batch.change_cursor);
          } else if (event.kind === 'finished') {
            this.feedback.set(
              event.job.status === 'FAILED'
                ? 'A sincronização falhou. Os e-mails já importados continuam no quadro.'
                : 'Sincronização concluída. ' +
                    event.job.imported_count +
                    (event.job.imported_count === 1
                      ? ' mensagem importada.'
                      : ' mensagens importadas.') +
                    (event.job.analysis_failed_count
                      ? ' Algumas mensagens tiveram falha na análise.'
                      : ''),
            );
          } else
            this.feedback.set('O acompanhamento foi pausado. Clique em Atualizar para continuar.');
        },
        error: () =>
          this.feedback.set(
            'Não foi possível acompanhar a sincronização. Os e-mails continuam disponíveis. Clique em Atualizar para tentar novamente.',
          ),
      });
  }

  protected assignCategory(gmailId: string, submission: CategorySubmission): void {
    submission.event.preventDefault();
    const value = new FormData(submission.form).get('category');
    const option = MANUAL_CATEGORIES.find((item) => item.value === value);
    if (!option?.value || this.savingId() !== null) return;
    this.savingId.set(gmailId);
    this.categoryFeedback.set('');
    this.api
      .assignCategory(gmailId, option.value)
      .pipe(
        takeUntilDestroyed(this.destroyRef),
        finalize(() => this.savingId.set(null)),
      )
      .subscribe({
        next: () => {
          const email = Object.values(this.states())
            .flatMap((state) => state.items)
            .find((item) => item.gmail_id === gmailId);
          if (email) this.applyItems([{ ...email, effective_category: option.value! }]);
          this.categoryFeedback.set('E-mail categorizado em ' + option.label + '.');
        },
        error: (error: HttpErrorResponse) => {
          this.categoryFeedback.set(
            error.status === 403
              ? 'Você não tem permissão para categorizar e-mails.'
              : error.status === 409 || error.status === 404
                ? 'Este e-mail já foi categorizado ou não está mais disponível.'
                : 'Não foi possível categorizar o e-mail. Tente novamente.',
          );
          if (error.status === 409 || error.status === 404) {
            const source = BOARD_COLUMNS.find((column) => column.category === '')!;
            const destination = BOARD_COLUMNS.find((column) => column.category === option.value)!;
            for (const column of [source, destination]) {
              if (!this.states()[column.id].loading) {
                this.patch(column.id, emptyColumn());
                this.load(column.id);
              }
            }
          }
        },
      });
  }

  private patch(id: string, patch: Partial<BoardColumnState>): void {
    this.states.update((states) => ({ ...states, [id]: { ...states[id], ...patch } }));
  }

  protected moveCard(gmailId: string, column: string): void {
    if (this.movingId() !== null) return;
    this.movingId.set(gmailId);
    this.moveFeedback.set('Movendo cartão…');
    this.api
      .moveCard(gmailId, column)
      .pipe(
        timeout(6_000),
        takeUntilDestroyed(this.destroyRef),
        finalize(() => this.movingId.set(null)),
      )
      .subscribe({
        next: (email) => {
          this.applyItems([email]);
          this.moveFeedback.set('Posição do cartão salva.');
          this.moveStatus().nativeElement.focus();
        },
        error: () => this.moveFeedback.set('Não foi possível mover o cartão. Tente novamente.'),
      });
  }

  private merge(
    existing: readonly EmailMetadata[],
    incoming: readonly EmailMetadata[],
  ): EmailMetadata[] {
    const byId = new Map(existing.map((email) => [email.gmail_id, email]));
    incoming.forEach((email) => byId.set(email.gmail_id, email));
    return [...byId.values()].sort(
      (a, b) =>
        Date.parse(b.imported_at) - Date.parse(a.imported_at) ||
        b.gmail_id.localeCompare(a.gmail_id),
    );
  }

  private acceptPage(id: string, page: EmailPage, updates: Map<string, EmailMetadata>): void {
    const column = BOARD_COLUMNS.find((item) => item.id === id)!;
    const known = this.knownEmails();
    const incoming = page.items
      .map((email) =>
        this.latestEmail(
          updates.get(email.gmail_id),
          this.latestEmail(known.get(email.gmail_id), email),
        ),
      )
      .filter((email) => !this.trash.isRemoved(email.gmail_id) && belongsToColumn(email, column));
    this.applyItems(incoming);
    this.patch(id, {
      nextCursor: page.next_cursor,
      loaded: true,
      loading: false,
      failed: false,
    });
    const latest = this.states()[id];
    this.patch(id, { page: Math.min(latest.page, columnPageCount(latest)) });
  }

  private knownEmails(): Map<string, EmailMetadata> {
    return new Map(
      Object.values(this.states()).flatMap((state) =>
        state.items.map((email) => [email.gmail_id, email] as const),
      ),
    );
  }

  private latestEmail(preferred: EmailMetadata | undefined, other: EmailMetadata): EmailMetadata {
    if (!preferred) return other;
    const first = preferred.update_revision;
    const second = other.update_revision;
    return second != null && (first == null || BigInt(second) > BigInt(first)) ? other : preferred;
  }

  private applyItems(items: readonly EmailMetadata[]): void {
    if (items.length === 0) return;
    const known = this.knownEmails();
    const confirmed = items.filter(
      (email) => this.latestEmail(email, known.get(email.gmail_id) ?? email) === email,
    );
    if (confirmed.length === 0) return;
    for (const updates of this.activeUpdates.values()) {
      confirmed.forEach((email) =>
        updates.set(email.gmail_id, this.latestEmail(email, updates.get(email.gmail_id) ?? email)),
      );
    }
    const ids = new Set(confirmed.map((email) => email.gmail_id));
    // Publish all removals and additions together, so a card never exists in two columns.
    this.states.update((states) =>
      Object.fromEntries(
        BOARD_COLUMNS.map((column) => {
          const state = states[column.id];
          const merged = this.merge(
            state.items.filter((email) => !ids.has(email.gmail_id)),
            confirmed.filter(
              (email) => !this.trash.isRemoved(email.gmail_id) && belongsToColumn(email, column),
            ),
          );
          const selected = merged.findIndex((email) => email.gmail_id === state.selectedId);
          const next = { ...state, items: merged };
          return [
            column.id,
            {
              ...next,
              selectedId: selected < 0 ? null : state.selectedId,
              page:
                selected < 0
                  ? Math.min(state.page, columnPageCount(next))
                  : Math.floor(selected / COLUMN_PAGE_SIZE) + 1,
            },
          ];
        }),
      ),
    );
  }
}
