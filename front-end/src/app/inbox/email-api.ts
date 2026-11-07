import { HttpClient, HttpParams } from '@angular/common/http';
import { inject, Injectable } from '@angular/core';
import { EmailChanges, EmailMetadata, EmailPage, EmailSyncJob } from './email-page';

export type EmailAttachment = {
  gmailId: string;
  id: string;
  filename: string;
  size: number | null;
  tooLarge: boolean;
};

export type EmailOriginalContent = {
  text: string;
  partial: boolean;
  links: string[];
  attachments: EmailAttachment[];
};

@Injectable({ providedIn: 'root' })
export class EmailApi {
  private readonly http = inject(HttpClient);

  listPage(
    cursor: string | null,
    category: string | null,
    at: string | null = null,
    processingStatus: string | null = null,
    boardColumn: string | null = null,
  ) {
    let params = new HttpParams();
    if (cursor !== null) {
      params = params.set('cursor', cursor);
    }
    if (category !== null) {
      params = params.set('category', category);
    }
    if (at !== null) {
      params = params.set('at', at);
    }
    if (processingStatus !== null) {
      params = params.set('processing_status', processingStatus);
    }
    if (boardColumn !== null) {
      params = params.set('board_column', boardColumn);
    }
    return this.http.get<EmailPage>('/api/email-pages', { params });
  }

  listChanges(
    cursor: string,
    until: string | null,
    category: string | null,
    includeUpdates = false,
  ) {
    let params = new HttpParams().set('cursor', cursor);
    if (until !== null) {
      params = params.set('until', until);
    }
    if (category !== null) {
      params = params.set('category', category);
    }
    if (includeUpdates) {
      params = params.set('include_updates', 'true');
    }
    return this.http.get<EmailChanges>('/api/email-changes', { params });
  }

  startSync() {
    return this.http.post<EmailSyncJob>('/api/email-sync-jobs', {});
  }

  syncStatus(id: string) {
    return this.http.get<EmailSyncJob>(`/api/email-sync-jobs/${encodeURIComponent(id)}`);
  }

  readOriginal(gmailId: string) {
    return this.http.get<EmailOriginalContent>(
      `/api/emails/${encodeURIComponent(gmailId)}/original`,
      { timeout: 60_000 },
    );
  }

  downloadAttachment(gmailId: string, fileId: string) {
    return this.http.get(
      `/api/emails/${encodeURIComponent(gmailId)}/attachments/${encodeURIComponent(fileId)}`,
      { responseType: 'blob', timeout: 30_000 },
    );
  }

  trash(gmailId: string) {
    return this.http.post<void>(`/api/emails/${encodeURIComponent(gmailId)}/trash`, null, {
      observe: 'response',
      timeout: 45_000,
    });
  }

  assignCategory(gmailId: string, category: string) {
    return this.http.patch<void>(`/api/emails/${encodeURIComponent(gmailId)}/manual-category`, {
      category,
    });
  }

  moveCard(gmailId: string, column: string) {
    return this.http.patch<EmailMetadata>(
      `/api/emails/${encodeURIComponent(gmailId)}/board-column`,
      {
        column,
      },
    );
  }
}
