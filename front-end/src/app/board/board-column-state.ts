import { EMAIL_CATEGORIES } from '../inbox/email-categories';
import { EmailMetadata } from '../inbox/email-page';

export const COLUMN_PAGE_SIZE = 5;

export interface BoardColumnDefinition {
  id: string;
  label: string;
  category: string | null;
  status: EmailMetadata['processing_status'];
  icon: string;
}

export const BOARD_COLUMNS: readonly BoardColumnDefinition[] = [
  {
    id: 'pending',
    label: 'Em análise',
    category: null,
    status: 'PENDING',
    icon: 'M12 4v8l5 3M5 5a10 10 0 1 1-2 11M3 3v5h5',
  },
  ...EMAIL_CATEGORIES.filter((option) => option.value !== null).map((option, index) => ({
    id: 'category-' + index,
    label: option.label,
    category: option.value,
    status: 'COMPLETED' as const,
    icon: option.icon,
  })),
  {
    id: 'failed',
    label: 'Falha na análise',
    category: null,
    status: 'FAILED',
    icon: 'M12 4 3 20h18zM12 10v4m0 3v.1',
  },
];

export interface BoardColumnState {
  readonly items: readonly EmailMetadata[];
  readonly nextCursor: string | null;
  readonly page: number;
  readonly loaded: boolean;
  readonly loading: boolean;
  readonly failed: boolean;
  readonly animationKey: number;
  readonly direction: string | null;
  readonly selectedId: string | null;
}

export function emptyColumn(): BoardColumnState {
  return {
    items: [],
    nextCursor: null,
    page: 1,
    loaded: false,
    loading: false,
    failed: false,
    animationKey: 0,
    direction: null,
    selectedId: null,
  };
}

export function columnPageCount(state: BoardColumnState): number {
  return (
    Math.max(1, Math.ceil(state.items.length / COLUMN_PAGE_SIZE)) +
    (state.nextCursor === null ? 0 : 1)
  );
}

export function belongsToColumn(email: EmailMetadata, column: BoardColumnDefinition): boolean {
  return (
    !email.trashed_at &&
    email.processing_status === column.status &&
    (column.category === null ||
      (email.board_column ?? email.effective_category) === column.category)
  );
}
