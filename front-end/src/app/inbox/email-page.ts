export interface EmailMetadata {
  readonly gmail_id: string;
  readonly thread_id: string | null;
  readonly sender: string | null;
  readonly subject: string | null;
  readonly received_at: string | null;
  readonly imported_at: string;
  readonly processing_status: 'PENDING' | 'COMPLETED' | 'FAILED';
  readonly effective_category: string;
  readonly board_column?: string | null;
  readonly update_revision?: string | null;
  readonly has_attachments?: boolean | null;
  readonly has_links?: boolean | null;
  readonly trashed_at?: string | null;
}

export interface EmailPage {
  readonly items: readonly EmailMetadata[];
  readonly next_cursor: string | null;
  readonly change_cursor: string;
}

export interface EmailChanges {
  readonly items: readonly EmailMetadata[];
  readonly change_cursor: string;
  readonly until_cursor: string | null;
}

export interface EmailSyncJob {
  readonly id: string;
  readonly status: 'IMPORTING' | 'ANALYZING' | 'COMPLETED' | 'FAILED';
  readonly imported_count: number;
  readonly analyzed_count: number;
  readonly analysis_failed_count: number;
}
