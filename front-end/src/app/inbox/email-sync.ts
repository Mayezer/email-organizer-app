import { inject, Injectable } from '@angular/core';
import {
  concat,
  defer,
  EMPTY,
  expand,
  map,
  Observable,
  of,
  switchMap,
  tap,
  timeout,
  timer,
} from 'rxjs';
import { EmailApi } from './email-api';
import { EmailChanges, EmailSyncJob } from './email-page';

const POLL_INTERVAL = 2_000;
const MAX_POLLS = 150;
const MAX_BATCHES_PER_POLL = 10;
const REQUEST_TIMEOUT = 6_000;
const MAX_WATCH_TIME = 5 * 60_000;

export type SyncEvent =
  | { kind: 'job'; job: EmailSyncJob }
  | { kind: 'batch'; batch: EmailChanges }
  | { kind: 'finished'; job: EmailSyncJob }
  | { kind: 'paused' };

@Injectable({ providedIn: 'root' })
export class EmailSync {
  private readonly api = inject(EmailApi);

  watch(initialCursor: string): Observable<SyncEvent> {
    return defer(() => {
      let cursor = initialCursor;
      let more = false;
      const deadline = Date.now() + MAX_WATCH_TIME;

      // Each cycle finishes consuming its confirmed window before another
      // progress request is scheduled. Unsubscribing cancels both HTTP and timers.
      const cycle = (job: EmailSyncJob, round: number): Observable<SyncEvent> =>
        concat(
          of<SyncEvent>({ kind: 'job', job }),
          defer(() => this.api.listChanges(cursor, null, null, true)).pipe(
            timeout(REQUEST_TIMEOUT),
            expand(
              (batch, index) =>
                batch.until_cursor === null ||
                index + 1 >= MAX_BATCHES_PER_POLL ||
                Date.now() >= deadline
                  ? EMPTY
                  : this.api
                      .listChanges(batch.change_cursor, batch.until_cursor, null, true)
                      .pipe(timeout(REQUEST_TIMEOUT)),
              1,
            ),
            tap((batch) => {
              cursor = batch.change_cursor;
              more = batch.until_cursor !== null;
            }),
            map((batch): SyncEvent => ({ kind: 'batch', batch })),
          ),
          defer(() => {
            if (!more && (job.status === 'COMPLETED' || job.status === 'FAILED')) {
              return of<SyncEvent>({ kind: 'finished', job });
            }
            if (round >= MAX_POLLS || Date.now() >= deadline) {
              return of<SyncEvent>({ kind: 'paused' });
            }
            return timer(POLL_INTERVAL).pipe(
              switchMap(() => this.api.syncStatus(job.id).pipe(timeout(REQUEST_TIMEOUT))),
              switchMap((next) => cycle(next, round + 1)),
            );
          }),
        );

      return this.api.startSync().pipe(
        timeout(REQUEST_TIMEOUT),
        switchMap((job) => cycle(job, 1)),
      );
    });
  }
}
