import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { vi } from 'vitest';
import { EmailMetadata, EmailPage } from '../inbox/email-page';
import { BOARD_COLUMNS } from './board-column-state';
import { Board } from './board';

const RECRUITMENT = 'professional opportunities and recruitment';
const EVENTS = 'event invitations';
function email(
  id: string,
  category = RECRUITMENT,
  status: EmailMetadata['processing_status'] = 'COMPLETED',
): EmailMetadata {
  return {
    gmail_id: id,
    thread_id: null,
    subject: 'Mensagem ' + id,
    sender: 'equipe@example.test',
    received_at: null,
    imported_at: '2026-11-06T10:00:00Z',
    effective_category: category,
    processing_status: status,
  };
}

describe('Quadro por categoria', () => {
  let http: HttpTestingController;
  beforeEach(() => {
    TestBed.configureTestingModule({
      imports: [Board],
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    http = TestBed.inject(HttpTestingController);
  });
  afterEach(() => {
    vi.useRealTimers();
    http.verify();
  });

  function render(pages: Record<string, Partial<EmailPage>> = {}) {
    const fixture = TestBed.createComponent(Board);
    fixture.detectChanges();
    const root = fixture.nativeElement as HTMLElement;
    http
      .expectOne('/api/email-pages')
      .flush({ items: [], next_cursor: null, change_cursor: 'board-snapshot' });
    let count = 0;
    while (count < BOARD_COLUMNS.length) {
      const active = http.match((req) => req.url === '/api/email-pages');
      expect(active.length).toBeGreaterThan(0);
      expect(active.length).toBeLessThanOrEqual(3);
      for (const request of active) {
        expect(request.request.params.get('at')).toBe('board-snapshot');
        const column = BOARD_COLUMNS.find(
          (column) =>
            column.status === request.request.params.get('processing_status') &&
            column.category === request.request.params.get('board_column'),
        )!;
        expect(column).toBeDefined();
        request.flush({
          items: [],
          next_cursor: null,
          change_cursor: 'board-snapshot',
          ...pages[column.id],
        });
        count++;
      }
    }
    fixture.detectChanges();
    const column = (id: string) =>
      root.querySelector<HTMLElement>(`[aria-labelledby="column-${id}"]`)!;
    return { fixture, root, column };
  }
  function clickPage(section: HTMLElement, number: number) {
    section.querySelector<HTMLButtonElement>(`[aria-label^="Página ${number} de "]`)!.click();
  }

  it('pagina Vagas e Eventos separadamente e mantém os e-mails nas respectivas colunas', () => {
    const vacancies = Array.from({ length: 10 }, (_, i) => email('vaga-' + i));
    const events = Array.from({ length: 10 }, (_, i) => email('evento-' + i, EVENTS));
    const { fixture, column } = render({
      'category-0': { items: vacancies.slice(0, 5), next_cursor: 'vacancies-tail' },
      'category-1': { items: events.slice(0, 5), next_cursor: 'events-tail' },
    });
    const jobs = column('category-0'),
      eventsColumn = column('category-1');
    clickPage(jobs, 2);
    http
      .expectOne(
        (req) =>
          req.params.get('cursor') === 'vacancies-tail' &&
          req.params.get('board_column') === RECRUITMENT &&
          req.params.get('processing_status') === 'COMPLETED',
      )
      .flush({ items: vacancies.slice(5), next_cursor: null, change_cursor: 'board-snapshot' });
    fixture.detectChanges();
    expect(jobs.querySelector('.page-caption')?.textContent).toContain('Página 2');
    expect(eventsColumn.querySelector('.page-caption')?.textContent).toContain('Página 1');
    expect(jobs.textContent).not.toContain('Mensagem evento-');
    expect(eventsColumn.textContent).not.toContain('Mensagem vaga-');
    clickPage(eventsColumn, 2);
    http
      .expectOne(
        (req) =>
          req.params.get('cursor') === 'events-tail' && req.params.get('board_column') === EVENTS,
      )
      .flush({ items: events.slice(5), next_cursor: null, change_cursor: 'board-snapshot' });
    fixture.detectChanges();
    expect(eventsColumn.querySelector('.page-caption')?.textContent).toContain('Página 2');
    clickPage(jobs, 1);
    fixture.detectChanges();
    expect(jobs.querySelector('.page-caption')?.textContent).toContain('Página 1');
    expect(eventsColumn.querySelector('.page-caption')?.textContent).toContain('Página 2');
  });

  it('passa de Em análise para a categoria final sem duplicar o gmail_id', async () => {
    vi.useFakeTimers();
    const pending = email('em-analise', '', 'PENDING');
    const { fixture, root, column } = render({ pending: { items: [pending] } });
    root.querySelector<HTMLButtonElement>('.board-refresh')!.click();
    root.querySelector<HTMLButtonElement>('.board-refresh')!.click();
    http.expectOne('/api/email-sync-jobs').flush({
      id: 'job',
      status: 'ANALYZING',
      imported_count: 1,
      analyzed_count: 0,
      analysis_failed_count: 0,
    });
    http
      .expectOne('/api/email-changes?cursor=board-snapshot&include_updates=true')
      .flush({ items: [pending], change_cursor: 'pending', until_cursor: null });
    fixture.detectChanges();
    expect(column('pending').querySelectorAll('li')).toHaveLength(1);
    await vi.advanceTimersByTimeAsync(2_000);
    http.expectOne('/api/email-sync-jobs/job').flush({
      id: 'job',
      status: 'COMPLETED',
      imported_count: 1,
      analyzed_count: 1,
      analysis_failed_count: 0,
    });
    http.expectOne('/api/email-changes?cursor=pending&include_updates=true').flush({
      items: [email('em-analise', EVENTS)],
      change_cursor: 'completed',
      until_cursor: null,
    });
    fixture.detectChanges();
    expect(column('pending').querySelectorAll('li')).toHaveLength(0);
    expect(column('category-1').querySelectorAll('li')).toHaveLength(1);
    expect(
      [...root.querySelectorAll('li h4')].filter(
        (title) => title.textContent === 'Mensagem em-analise',
      ),
    ).toHaveLength(1);
  });

  it('classifica em Sem categoria e coloca o cartão na coluna de destino', () => {
    const { fixture, root, column } = render({ 'category-6': { items: [email('manual', '')] } });
    const source = column('category-6');
    source.querySelector('summary')!.click();
    const form = source.querySelector('form')!;
    form.querySelector<HTMLInputElement>('input[value="event invitations"]')!.click();
    form.querySelector('button')!.click();
    const save = http.expectOne({ method: 'PATCH', url: '/api/emails/manual/manual-category' });
    expect(save.request.body).toEqual({ category: EVENTS });
    save.flush(null, { status: 204, statusText: 'No Content' });
    fixture.detectChanges();
    expect(source.querySelectorAll('li')).toHaveLength(0);
    expect(column('category-1').textContent).toContain('Mensagem manual');
    expect(root.textContent).toContain('E-mail categorizado em Eventos.');
  });

  it('preserva as páginas ao filtrar o quadro e voltar para Todas', () => {
    const { fixture, root, column } = render({
      'category-0': {
        items: Array.from({ length: 5 }, (_, i) => email('primeira-' + i)),
        next_cursor: 'next',
      },
    });
    clickPage(column('category-0'), 2);
    http
      .expectOne((req) => req.params.get('cursor') === 'next')
      .flush({
        items: [email('seguinte')],
        next_cursor: null,
        change_cursor: 'board-snapshot',
      });
    fixture.detectChanges();
    const select = (label: string) =>
      [...root.querySelectorAll('.category-option')]
        .find((option) => option.textContent.trim() === label)!
        .querySelector('input')!
        .click();
    select('Eventos');
    fixture.detectChanges();
    expect(root.querySelectorAll('app-board-column')).toHaveLength(1);
    select('Todas');
    fixture.detectChanges();
    expect(root.querySelectorAll('app-board-column')).toHaveLength(9);
    expect(column('category-0').querySelector('.page-caption')?.textContent).toContain('Página 2');
    http.expectNone((req) => req.url === '/api/email-pages');
  });

  it('repete só a consulta da coluna que falhou e preserva as demais', () => {
    const { fixture, column } = render({
      'category-0': {
        items: Array.from({ length: 5 }, (_, i) => email('vaga-' + i)),
        next_cursor: 'retry-tail',
      },
      'category-1': { items: [email('evento', EVENTS)] },
    });
    const source = column('category-0');
    clickPage(source, 2);
    http
      .expectOne((req) => req.params.get('cursor') === 'retry-tail')
      .flush(null, { status: 503, statusText: 'Unavailable' });
    fixture.detectChanges();
    expect(source.textContent).toContain('Não foi possível carregar esta coluna');
    expect(column('category-1').textContent).toContain('Mensagem evento');
    [...source.querySelectorAll('button')]
      .find((button) => button.textContent.trim() === 'Tentar novamente')!
      .click();
    http
      .expectOne((req) => req.params.get('cursor') === 'retry-tail')
      .flush({
        items: [email('recuperada')],
        next_cursor: null,
        change_cursor: 'board-snapshot',
      });
    fixture.detectChanges();
    expect(source.textContent).toContain('Mensagem recuperada');
  });

  it('cancela o acompanhamento quando o quadro é fechado', async () => {
    vi.useFakeTimers();
    const { fixture, root } = render();
    root.querySelector<HTMLButtonElement>('.board-refresh')!.click();
    const pending = http.expectOne('/api/email-sync-jobs');
    fixture.destroy();
    expect(pending.cancelled).toBe(true);
    await vi.advanceTimersByTimeAsync(10_000);
    http.expectNone((req) => req.url.includes('email-sync-jobs'));
  });

  it('não recoloca na origem um cartão classificado enquanto sua página carregava', () => {
    const pending = Array.from({ length: 6 }, (_, i) => email('pending-' + i, '', 'PENDING'));
    const { fixture, root, column } = render({
      pending: { items: pending.slice(0, 5), next_cursor: 'pending-tail' },
    });
    clickPage(column('pending'), 2);
    const delayed = http.expectOne((req) => req.params.get('cursor') === 'pending-tail');
    root.querySelector<HTMLButtonElement>('.board-refresh')!.click();
    http.expectOne('/api/email-sync-jobs').flush({
      id: 'job',
      status: 'COMPLETED',
      imported_count: 0,
      analyzed_count: 1,
      analysis_failed_count: 0,
    });
    http.expectOne('/api/email-changes?cursor=board-snapshot&include_updates=true').flush({
      items: [email('pending-5', EVENTS)],
      change_cursor: 'classified',
      until_cursor: null,
    });
    delayed.flush({ items: [pending[5]], next_cursor: null, change_cursor: 'board-snapshot' });
    fixture.detectChanges();
    expect(column('pending').textContent).not.toContain('Mensagem pending-5');
    expect(column('category-1').textContent).toContain('Mensagem pending-5');
    expect(
      [...root.querySelectorAll('li h4')].filter(
        (title) => title.textContent === 'Mensagem pending-5',
      ),
    ).toHaveLength(1);
  });

  it('move após confirmar e descarta uma revisão antiga recebida depois da movimentação', () => {
    const original = { ...email('vaga'), update_revision: '9007199254740992' };
    const { fixture, root, column } = render({ 'category-0': { items: [original] } });
    root.querySelector<HTMLButtonElement>('.board-refresh')!.click();
    http.expectOne('/api/email-sync-jobs').flush({
      id: 'job',
      status: 'COMPLETED',
      imported_count: 0,
      analyzed_count: 0,
      analysis_failed_count: 0,
    });
    const stale = http.expectOne('/api/email-changes?cursor=board-snapshot&include_updates=true');
    const origin = column('category-0');
    const form = origin.querySelector<HTMLFormElement>('.card-move')!;
    form.querySelector('select')!.value = EVENTS;
    form.querySelector('button')!.click();
    const move = http.expectOne({ method: 'PATCH', url: '/api/emails/vaga/board-column' });
    expect(move.request.body).toEqual({ column: EVENTS });
    expect(origin.querySelectorAll('li')).toHaveLength(1);
    move.flush({ ...original, board_column: EVENTS, update_revision: '9007199254740993' });
    stale.flush({ items: [original], change_cursor: 'older', until_cursor: null });
    fixture.detectChanges();
    expect(origin.querySelectorAll('li')).toHaveLength(0);
    const destination = column('category-1');
    expect(destination.querySelectorAll('li')).toHaveLength(1);
    expect(destination.querySelector('.message-state')?.textContent).toContain('Vagas');
    expect(destination.querySelector('select')?.value).toBe(EVENTS);
  });

  it('mantém o cartão e permite tentar novamente quando o servidor recusa o movimento', () => {
    const { fixture, root, column } = render({ 'category-0': { items: [email('vaga')] } });
    const origin = column('category-0');
    const form = origin.querySelector<HTMLFormElement>('.card-move')!;
    const select = form.querySelector('select')!;
    select.value = EVENTS;
    form.querySelector('button')!.click();
    http
      .expectOne('/api/emails/vaga/board-column')
      .flush(null, { status: 503, statusText: 'Unavailable' });
    fixture.detectChanges();
    expect(origin.querySelectorAll('li')).toHaveLength(1);
    expect(column('category-1').querySelectorAll('li')).toHaveLength(0);
    expect(select.disabled).toBe(false);
    expect(root.textContent).toContain('Não foi possível mover o cartão.');
  });
});
