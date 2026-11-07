import { Component, computed, input, output } from '@angular/core';
import { CategorySubmission, EmailCard } from '../inbox/email-card';
import {
  BoardColumnDefinition,
  BoardColumnState,
  BOARD_COLUMNS,
  COLUMN_PAGE_SIZE,
  columnPageCount,
} from './board-column-state';

@Component({
  selector: 'app-board-column',
  imports: [EmailCard],
  templateUrl: './board-column.html',
  styleUrls: ['./board-column.css', '../inbox/inbox-pagination.css'],
})
export class BoardColumn {
  readonly definition = input.required<BoardColumnDefinition>();
  readonly state = input.required<BoardColumnState>();
  readonly savingId = input<string | null>(null);
  readonly movingId = input<string | null>(null);
  readonly pageSelected = output<number>();
  readonly retried = output<void>();
  readonly remembered = output<string>();
  readonly categorySubmitted = output<{ gmailId: string; submission: CategorySubmission }>();
  readonly moveRequested = output<{ gmailId: string; column: string }>();
  protected readonly destinations = BOARD_COLUMNS.filter((column) => column.status === 'COMPLETED');

  protected submitMove(event: Event, gmailId: string, column: string): void {
    event.preventDefault();
    this.moveRequested.emit({ gmailId, column });
  }
  protected readonly items = computed(() => {
    const state = this.state();
    return state.items.slice((state.page - 1) * COLUMN_PAGE_SIZE, state.page * COLUMN_PAGE_SIZE);
  });
  protected readonly pageCount = computed(() => columnPageCount(this.state()));
  protected readonly numbers = computed(() => {
    const first = Math.max(1, Math.min(this.state().page - 2, this.pageCount() - 4));
    return Array.from(
      { length: Math.min(5, this.pageCount() - first + 1) },
      (_, index) => first + index,
    );
  });
}
