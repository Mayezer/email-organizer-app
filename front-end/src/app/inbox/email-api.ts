import { HttpClient, HttpParams } from '@angular/common/http';
import { inject, Injectable } from '@angular/core';
import { EmailChanges, EmailPage, EmailSyncJob } from './email-page';

@Injectable({ providedIn: 'root' })
export class EmailApi {
  private readonly http = inject(HttpClient);

  listPage(cursor: string | null, category: string | null, at: string | null = null) {
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

  assignCategory(gmailId: string, category: string) {
    return this.http.patch<void>(`/api/emails/${encodeURIComponent(gmailId)}/manual-category`, {
      category,
    });
  }
}
