import { TestBed } from '@angular/core/testing';
import { NEVER, of } from 'rxjs';
import { vi } from 'vitest';
import { EmailApi } from './email-api';
import { EmailSync, SyncEvent } from './email-sync';

describe('Acompanhamento da sincronização', () => {
  const job = {
    id: 'synthetic-job',
    status: 'IMPORTING',
    imported_count: 0,
    analyzed_count: 0,
    analysis_failed_count: 0,
  };

  afterEach(() => vi.useRealTimers());

  function setup() {
    const api = {
      startSync: vi.fn(() => of(job)),
      syncStatus: vi.fn(() => of(job)),
      listChanges: vi.fn(() =>
        of({ items: [], change_cursor: 'confirmed', until_cursor: null as string | null }),
      ),
    };
    TestBed.configureTestingModule({
      providers: [EmailSync, { provide: EmailApi, useValue: api }],
    });
    return { api, sync: TestBed.inject(EmailSync) };
  }

  it('pausa o acompanhamento depois do limite sem iniciar outro trabalho', async () => {
    vi.useFakeTimers();
    const { api, sync } = setup();
    const events: SyncEvent[] = [];
    const subscription = sync.watch('initial').subscribe((event) => events.push(event));
    await vi.advanceTimersByTimeAsync(5 * 60_000);
    expect(events.at(-1)?.kind).toBe('paused');
    expect(subscription.closed).toBe(true);
    expect(api.startSync).toHaveBeenCalledTimes(1);
    expect(api.syncStatus).toHaveBeenCalledTimes(149);
  });

  it('encerra uma requisição de progresso que não responde', async () => {
    vi.useFakeTimers();
    const { api, sync } = setup();
    api.syncStatus.mockReturnValue(NEVER);
    const error = vi.fn();
    sync.watch('initial').subscribe({ error });
    await vi.advanceTimersByTimeAsync(8_000);
    expect(error).toHaveBeenCalledTimes(1);
    expect(api.syncStatus).toHaveBeenCalledTimes(1);
  });

  it('consome no máximo dez lotes por consulta de progresso', async () => {
    vi.useFakeTimers();
    const { api, sync } = setup();
    let revision = 0;
    api.listChanges.mockImplementation(() =>
      of({
        items: [],
        change_cursor: String(++revision),
        until_cursor: 'upper',
      }),
    );
    const subscription = sync.watch('initial').subscribe();
    expect(api.listChanges).toHaveBeenCalledTimes(10);
    expect(api.syncStatus).not.toHaveBeenCalled();
    await vi.advanceTimersByTimeAsync(2_000);
    expect(api.syncStatus).toHaveBeenCalledTimes(1);
    expect(api.listChanges).toHaveBeenCalledTimes(20);
    subscription.unsubscribe();
  });
});
