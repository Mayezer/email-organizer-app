import { HttpParams, provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { vi } from 'vitest';
import { App } from './app';
import { expireAppSession } from './app-session';
import { EmailMetadata, EmailPage, EmailSyncJob } from './inbox/email-page';

const receivedAt = new Date(2026, 10, 6, 12).toISOString();
const page: EmailPage = {
  items: Array.from({ length: 5 }, (_, index) => ({
    gmail_id: `synthetic-${index}`,
    thread_id: null,
    sender: index === 4 ? ' ' : 'remetente@example.test',
    subject: index === 4 ? null : `Mensagem de teste ${index + 1}`,
    received_at: index === 4 ? null : receivedAt,
    imported_at: '2026-11-07T00:00:00Z',
    processing_status: 'COMPLETED' as const,
    effective_category: 'other',
  })),
  next_cursor: 'synthetic-cursor',
  change_cursor: 'initial-revision',
};

describe('Caixa de entrada', () => {
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      imports: [App],
      providers: [
        provideHttpClient(withInterceptors([expireAppSession])),
        provideHttpClientTesting(),
      ],
    });
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => {
    vi.useRealTimers();
    http.verify();
  });

  function renderInbox() {
    const fixture = TestBed.createComponent(App);
    fixture.detectChanges();
    http.expectOne('/api/session').flush({ gmailAccount: 'linked@example.test' });
    fixture.detectChanges();
    return {
      fixture,
      root: fixture.nativeElement as HTMLElement,
      request: http.expectOne({ method: 'GET', url: '/api/email-pages' }),
    };
  }

  it('cancela sem enviar, preserva o cartão sem permissão e confirma uma vez', () => {
    const { fixture, root, request } = renderInbox();
    request.flush({ ...page, next_cursor: null });
    fixture.detectChanges();
    const action = root.querySelector('app-email-trash-action')!;
    const dialog = action.querySelector('dialog')!;
    Object.defineProperties(dialog, {
      showModal: { value: () => (dialog.open = true) },
      close: { value: () => (dialog.open = false) },
    });
    const open = action.querySelector<HTMLButtonElement>('.trash-action > button')!;
    const cancel = dialog.querySelector<HTMLButtonElement>('[autofocus]')!;
    const confirm = dialog.querySelector<HTMLButtonElement>('.trash-confirm')!;
    const path = '/api/emails/synthetic-0/trash';
    open.click();
    cancel.click();
    expect(dialog.open).toBe(false);
    http.expectNone(path);

    open.click();
    confirm.click();
    confirm.click();
    http.expectOne({ method: 'POST', url: path }).flush(null, {
      status: 403,
      statusText: 'Forbidden',
    });
    fixture.detectChanges();
    expect(dialog.textContent).toContain('precisa de autorização');
    expect(root.querySelectorAll('li')).toHaveLength(5);

    confirm.click();
    http.expectOne({ method: 'POST', url: path }).flush(null, {
      status: 202,
      statusText: 'Accepted',
    });
    fixture.detectChanges();
    expect(dialog.open).toBe(false);
    expect(root.querySelectorAll('li')).toHaveLength(4);
    expect(root.textContent).toContain('Clique em Atualizar para confirmar a remoção');
    expect(root.textContent).not.toContain('Mensagem de teste 1');
  });

  it('mantém a caixa de entrada fechada até o login e a fecha ao sair', () => {
    const fixture = TestBed.createComponent(App);
    fixture.detectChanges();
    http.expectOne('/api/session').flush({ gmailAccount: null });
    fixture.detectChanges();
    const root = fixture.nativeElement as HTMLElement;
    expect(root.textContent).toContain('Entrar no Email Organizer');
    http.expectNone('/api/email-pages');

    const username = root.querySelector<HTMLInputElement>('#username')!;
    const password = root.querySelector<HTMLInputElement>('#password')!;
    const form = root.querySelector('form')!;
    username.value = 'revisor';
    password.value = 'senha-errada';
    form.dispatchEvent(new Event('submit', { cancelable: true }));
    http.expectOne({ method: 'POST', url: '/api/session/login' }).flush(null, {
      status: 401,
      statusText: 'Unauthorized',
    });
    fixture.detectChanges();
    expect(root.querySelector('[role="alert"]')?.textContent).toContain(
      'Usuário ou senha inválidos',
    );
    http.expectNone('/api/email-pages');

    password.value = 'senha-sintetica';
    form.dispatchEvent(new Event('submit', { cancelable: true }));
    const login = http.expectOne({ method: 'POST', url: '/api/session/login' });
    expect((login.request.body as HttpParams).get('username')).toBe('revisor');
    expect(password.value).toBe('');
    login.flush(null, { status: 204, statusText: 'No Content' });
    http.expectOne('/api/session').flush({ gmailAccount: 'linked@example.test' });
    fixture.detectChanges();
    http.expectOne('/api/email-pages').flush({ ...page, next_cursor: null });
    fixture.detectChanges();
    expect(root.textContent).toContain('Mensagem de teste 1');

    root.querySelector<HTMLButtonElement>('.sign-out')!.click();
    http.expectOne({ method: 'POST', url: '/api/session/logout' }).flush(null);
    http.expectOne('/api/session').flush({ gmailAccount: null });
    fixture.detectChanges();
    expect(root.textContent).toContain('Entrar no Email Organizer');
    expect(root.textContent).not.toContain('Mensagem de teste 1');
  });

  it('fecha a caixa de entrada quando o servidor expira a sessão', () => {
    const { fixture, root, request } = renderInbox();
    request.flush(null, { status: 401, statusText: 'Unauthorized' });
    fixture.detectChanges();
    expect(root.textContent).toContain('Sua sessão expirou');
    expect(root.querySelector('app-inbox')).toBeNull();
  });

  it('exibe o texto consolidado da conversa em um só bloco', () => {
    const { fixture, root, request } = renderInbox();
    request.flush({
      ...page,
      next_cursor: null,
      items: page.items.slice(0, 2).map((email) => ({ ...email, thread_id: 'same-thread' })),
    });
    fixture.detectChanges();
    const cards = root.querySelectorAll('app-email-original');
    const open = (index: number) => {
      const card = cards[index];
      const dialog = card.querySelector('dialog')!;
      Object.defineProperty(dialog, 'showModal', { value: () => (dialog.open = true) });
      Object.defineProperty(dialog, 'close', { value: () => (dialog.open = false) });
      card.querySelector('button')!.click();
      fixture.detectChanges();
      return dialog;
    };
    http.expectNone((candidate) => candidate.url.endsWith('/original'));
    const first = open(0);
    expect(first.textContent).toContain('Buscando o texto');
    const text = 'Primeira mensagem\n\nSegunda mensagem\n<img src="https://example.test/recurso">';
    http.expectOne('/api/emails/synthetic-0/original').flush({
      text,
      partial: true,
      links: [
        'HTTPS://Example.Test:443/agenda?modo=publico',
        'https://example.test/agenda?modo=publico',
        'http://example.test/edicao',
        'javascript:alert(1)',
        'data:text/html,inativo',
        'https:///example.test/invalido',
        'https://example.test/%zz',
        'https://example.test:70000/invalido',
        '//example.test/relativo',
      ],
    });
    fixture.detectChanges();
    expect(first.querySelectorAll('pre')).toHaveLength(1);
    expect(first.querySelector('pre')?.textContent).toBe(text);
    expect(first.textContent).toContain('Exibindo o conteúdo disponível de parte da conversa.');
    expect(first.querySelector('img, pre a')).toBeNull();
    const links = [...first.querySelectorAll('a')];
    expect(links.map((link) => link.href)).toEqual([
      'https://example.test/agenda?modo=publico',
      'http://example.test/edicao',
    ]);
    for (const link of links) {
      expect(link.textContent.trim()).toBe(link.href);
      expect(link.target).toBe('_blank');
      expect(link.rel).toBe('noopener noreferrer');
    }
    first.querySelector('button')!.click();
    fixture.detectChanges();
    expect(first.querySelector('pre')).toBeNull();
    expect(first.querySelector('a')).toBeNull();

    const second = open(1);
    http.expectOne('/api/emails/synthetic-1/original').flush(null, {
      status: 503,
      statusText: 'Service Unavailable',
    });
    fixture.detectChanges();
    Array.from(second.querySelectorAll('button'))
      .find((button) => button.textContent.includes('Tentar novamente'))!
      .click();
    http.expectOne('/api/emails/synthetic-1/original').flush(null, {
      status: 404,
      statusText: 'Not Found',
    });
    fixture.detectChanges();
    expect(second.textContent).toContain('Esta mensagem está indisponível.');
  });

  it('lista os anexos sem baixá-los e mostra falhas no arquivo solicitado', () => {
    const { fixture, root, request } = renderInbox();
    request.flush({ ...page, next_cursor: null, items: page.items.slice(0, 1) });
    fixture.detectChanges();
    const card = root.querySelector('app-email-original')!;
    const dialog = card.querySelector('dialog')!;
    Object.defineProperty(dialog, 'showModal', { value: () => (dialog.open = true) });
    card.querySelector('button')!.click();
    http.expectOne('/api/emails/synthetic-0/original').flush({
      text: '',
      partial: false,
      links: [],
      attachments: [
        {
          gmailId: 'message-from-thread',
          id: 'file-id',
          filename: 'relatório.bin',
          size: 3,
          tooLarge: false,
        },
        {
          gmailId: 'synthetic-0',
          id: 'large-id',
          filename: 'grande.zip',
          size: 11_534_336,
          tooLarge: true,
        },
      ],
    });
    fixture.detectChanges();
    const [file, large] = [...dialog.querySelectorAll('.original-attachments li')];
    expect(file.textContent).toContain('relatório.bin');
    expect(file.textContent).toContain('3 B');
    expect(large.textContent).toContain('11 MiB');
    expect(large.textContent).toContain('Este arquivo excede o limite de download.');
    expect(large.querySelector('button')).toBeNull();
    http.expectNone((candidate) => candidate.url.includes('/attachments/'));

    const download = file.querySelector('button')!;
    download.click();
    download.click();
    const path = '/api/emails/message-from-thread/attachments/file-id';
    const transfer = http.expectOne({ method: 'GET', url: path });
    expect(transfer.request.responseType).toBe('blob');
    fixture.detectChanges();
    expect(file.textContent).toContain('Baixando anexo');
    transfer.flush(new Blob(), { status: 404, statusText: 'Not Found' });
    fixture.detectChanges();
    expect(file.textContent).toContain('Este anexo não está mais disponível.');

    download.click();
    http.expectOne(path).flush(new Blob(), { status: 413, statusText: 'Content Too Large' });
    fixture.detectChanges();
    expect(file.textContent).toContain('Este arquivo excede o limite de download.');
  });

  it('mostra o carregamento enquanto a primeira página está pendente', () => {
    const { root, request } = renderInbox();

    expect(root.querySelector('[role="status"]')?.textContent).toContain('Carregando mensagens');
    expect(root.querySelector('ul')).toBeNull();
    request.flush({ ...page, next_cursor: null });
  });

  it('mostra os cinco e-mails em ordem e trata metadados opcionais', () => {
    const { fixture, root, request } = renderInbox();
    request.flush({ ...page, next_cursor: null });
    fixture.detectChanges();

    const messages = [...root.querySelectorAll('li')];
    expect(messages.map((item) => item.querySelector('h3')?.textContent)).toEqual([
      'Mensagem de teste 1',
      'Mensagem de teste 2',
      'Mensagem de teste 3',
      'Mensagem de teste 4',
      'Sem assunto',
    ]);
    expect(messages[0].textContent).toContain('remetente@example.test');
    const time = messages[0].querySelector('time');
    expect(time?.getAttribute('datetime')).toBe(receivedAt);
    expect(time?.textContent?.trim()).toBe('06/11/2026');
    expect(messages[4].textContent).toContain('Remetente desconhecido');
    expect(messages[4].textContent).toContain('Data indisponível');
    expect(messages[4].querySelector('time')).toBeNull();
  });

  it('diferencia presença, ausência e verificação pendente em cada indicador', () => {
    const { fixture, root, request } = renderInbox();
    const flags = [
      [true, true],
      [true, false],
      [false, true],
      [false, false],
      [null, null],
    ];
    request.flush({
      ...page,
      next_cursor: null,
      items: page.items.map((item, i) => ({
        ...item,
        has_attachments: flags[i][0],
        has_links: flags[i][1],
      })),
    });
    fixture.detectChanges();
    expect(
      [...root.querySelectorAll('li')].map((card) =>
        [...card.querySelectorAll('.message-indicators dd')].map((value) =>
          value.textContent?.trim(),
        ),
      ),
    ).toEqual([
      ['Sim', 'Sim'],
      ['Sim', 'Não'],
      ['Não', 'Sim'],
      ['Não', 'Não'],
      ['Ainda não verificado', 'Ainda não verificado'],
    ]);
  });

  it('mostra um aviso quando a consulta retorna uma lista vazia', () => {
    const { fixture, root, request } = renderInbox();
    request.flush({ items: [], next_cursor: null });
    fixture.detectChanges();

    expect(root.querySelector('[role="status"]')?.textContent).toContain('Nenhum e-mail importado');
    expect(root.querySelector('ul')).toBeNull();
  });

  it('mostra uma falha e permite tentar a consulta novamente', () => {
    const { fixture, root, request } = renderInbox();
    request.flush(null, { status: 503, statusText: 'Service Unavailable' });
    fixture.detectChanges();

    expect(root.querySelector('[role="status"]')?.textContent).toContain(
      'Não foi possível carregar os e-mails',
    );
    const retry = root.querySelector<HTMLButtonElement>('button.action')!;
    expect(retry.textContent).toBe('Tentar novamente');
    retry.click();
    fixture.detectChanges();

    expect(root.querySelector('[role="status"]')?.textContent).toContain('Carregando mensagens');
    http
      .expectOne({ method: 'GET', url: '/api/email-pages' })
      .flush({ ...page, next_cursor: null });
    fixture.detectChanges();

    expect(root.querySelectorAll('li')).toHaveLength(5);
    expect(root.querySelector('button.action')).toBeNull();
  });

  it('revela a aba 3 na abertura e permite acessá-la sem selecionar a aba 2', () => {
    const { fixture, root, request } = renderInbox();
    const tab = (number: number) =>
      root.querySelector<HTMLButtonElement>(`[aria-label="Página ${number}"]`)!;
    request.flush(page);
    fixture.detectChanges();

    expect(tab(1).getAttribute('aria-current')).toBe('page');
    expect(root.querySelectorAll('li')).toHaveLength(5);
    const second = page.items.map((item, index) => ({
      ...item,
      gmail_id: `second-${index}`,
      subject: `Segunda página ${index + 1}`,
    }));
    http.expectOne({ method: 'GET', url: '/api/email-pages?cursor=synthetic-cursor' }).flush({
      items: second,
      next_cursor: 'last-page',
    });
    fixture.detectChanges();

    expect(tab(1).getAttribute('aria-current')).toBe('page');
    expect(root.querySelectorAll('li')).toHaveLength(5);

    tab(3).click();
    tab(3).click();
    http.expectOne({ method: 'GET', url: '/api/email-pages?cursor=last-page' }).flush({
      items: second.slice(0, 2).map((item) => ({ ...item, gmail_id: `last-${item.gmail_id}` })),
      next_cursor: null,
    });
    fixture.detectChanges();

    expect(tab(3).getAttribute('aria-current')).toBe('page');
    expect(tab(4)).toBeNull();
    expect(root.querySelectorAll('li')).toHaveLength(2);
    tab(2).click();
    fixture.detectChanges();

    expect(tab(2).getAttribute('aria-current')).toBe('page');
    expect(root.querySelectorAll('li')).toHaveLength(5);
    expect(root.querySelector('li h3')?.textContent).toBe('Segunda página 1');
    tab(1).click();
    fixture.detectChanges();

    expect(root.querySelectorAll('li')).toHaveLength(5);
    expect(root.querySelector('li h3')?.textContent).toBe('Mensagem de teste 1');
    http.expectNone((req) => req.url === '/api/email-pages');
  });

  it('volta à aba 1 ao filtrar e ignora a página pendente da categoria anterior', () => {
    const { fixture, root, request } = renderInbox();
    request.flush(page);
    fixture.detectChanges();

    root.querySelector<HTMLButtonElement>('[aria-label="Página 2"]')!.click();
    const previous = http.expectOne('/api/email-pages?cursor=synthetic-cursor');

    const label = [...root.querySelectorAll('label')].find(
      (item) => item.textContent.trim() === 'Eventos',
    );
    label?.querySelector('input')?.click();
    fixture.detectChanges();

    expect(previous.cancelled).toBe(true);
    expect(root.querySelector('[aria-current="page"]')?.textContent?.trim()).toBe('1');
    expect(root.querySelector('[aria-label="Página 2"]')).toBeNull();

    const filtered = '/api/email-pages?category=event%20invitations';
    http.expectOne(filtered).flush({ items: [page.items[0]], next_cursor: 'events-cursor' });
    fixture.detectChanges();

    expect(root.querySelectorAll('li')).toHaveLength(1);
    root.querySelector<HTMLButtonElement>('[aria-label="Página 2"]')!.click();
    const filteredPage = '/api/email-pages?cursor=events-cursor&category=event%20invitations';
    http.expectOne(filteredPage).flush(null, { status: 503, statusText: 'Service Unavailable' });
    fixture.detectChanges();

    root.querySelector<HTMLButtonElement>('button.action')!.click();
    http.expectOne(filteredPage).flush({ items: [], next_cursor: null });
    fixture.detectChanges();

    expect(root.querySelector('[aria-current="page"]')?.textContent?.trim()).toBe('2');
    expect(label?.querySelector('input')?.checked).toBe(true);
    expect(root.querySelector('[role="status"]')?.textContent).toContain(
      'Nenhum e-mail encontrado nesta categoria',
    );
  });

  it('reinicia o deslizamento em cada troca entre páginas já carregadas', () => {
    const { fixture, root, request } = renderInbox();
    request.flush({ ...page, next_cursor: 'page-2' });
    for (const number of [2, 3]) {
      http.expectOne(`/api/email-pages?cursor=page-${number}`).flush({
        ...page,
        items: page.items.map((email) => ({
          ...email,
          gmail_id: `page-${number}-${email.gmail_id}`,
          subject: `Mensagem da página ${number}`,
        })),
        next_cursor: number === 2 ? 'page-3' : null,
      });
    }
    fixture.detectChanges();

    let previousList = root.querySelector('ul.messages');
    expect(previousList?.classList.contains('page-slide')).toBe(false);

    for (const [number, direction] of [
      [2, '1.25rem'],
      [3, '1.25rem'],
      [2, '-1.25rem'],
      [1, '-1.25rem'],
    ] as const) {
      root.querySelector<HTMLButtonElement>(`[aria-label="Página ${number}"]`)!.click();
      fixture.detectChanges();

      const list = root.querySelector<HTMLUListElement>('ul.messages')!;
      expect(list).not.toBe(previousList);
      expect(list.classList.contains('page-slide')).toBe(true);
      expect(list.style.getPropertyValue('--page-slide-offset')).toBe(direction);
      previousList = list;
    }
  });

  it('limita a cinco abas e navega até a sexta com Próximo e Anterior', () => {
    const { fixture, root, request } = renderInbox();
    request.flush({ ...page, next_cursor: 'page-2' });
    for (const number of [2, 3, 4, 5]) {
      http.expectOne(`/api/email-pages?cursor=page-${number}`).flush({
        items: page.items.map((item) => ({ ...item, gmail_id: `${number}-${item.gmail_id}` })),
        next_cursor: `page-${number + 1}`,
      });
    }
    fixture.detectChanges();

    const button = (label: string) =>
      [...root.querySelectorAll('nav button')].find((item) => item.textContent.trim() === label)!;
    const previous = button('Anterior') as HTMLButtonElement;
    const next = button('Próximo') as HTMLButtonElement;
    expect(previous.disabled).toBe(true);
    expect(root.querySelectorAll('nav button[aria-label]')).toHaveLength(5);

    root.querySelector<HTMLButtonElement>('[aria-label="Página 5"]')!.click();
    fixture.detectChanges();
    next.click();
    http.expectOne('/api/email-pages?cursor=page-6').flush({
      items: [{ ...page.items[0], gmail_id: 'last-email' }],
      next_cursor: null,
    });
    fixture.detectChanges();

    expect(root.querySelectorAll('nav button[aria-label]')).toHaveLength(5);
    expect(root.querySelector('[aria-current="page"]')?.textContent.trim()).toBe('6');
    expect(next.disabled).toBe(true);
    previous.click();
    fixture.detectChanges();
    expect(root.querySelector('[aria-current="page"]')?.textContent.trim()).toBe('5');
  });

  it('remove a mensagem de Sem categoria após salvar e a encontra no filtro escolhido', () => {
    const { fixture, root, request } = renderInbox();
    request.flush({ ...page, next_cursor: null });
    fixture.detectChanges();
    const filter = [...root.querySelectorAll('label')].find(
      (label) => label.textContent.trim() === 'Sem categoria',
    )!;
    filter.querySelector('input')!.click();
    http.expectOne('/api/email-pages?category=').flush({
      items: [page.items[0]],
      next_cursor: null,
    });
    fixture.detectChanges();

    root.querySelector('summary')!.click();
    const form = root.querySelector('form')!;
    form.querySelector<HTMLInputElement>('input[value="event invitations"]')!.click();
    form.querySelector('button')!.click();
    const save = http.expectOne({
      method: 'PATCH',
      url: '/api/emails/synthetic-0/manual-category',
    });
    expect(save.request.body).toEqual({ category: 'event invitations' });
    save.flush(null, { status: 204, statusText: 'No Content' });
    http.expectOne('/api/email-pages?category=').flush({ items: [], next_cursor: null });
    fixture.detectChanges();

    expect(root.querySelector('form')).toBeNull();
    expect(root.querySelector('.category-feedback')?.textContent).toContain(
      'E-mail categorizado em Eventos',
    );

    const destination = [...root.querySelectorAll('label')].find(
      (label) => label.textContent.trim() === 'Eventos',
    )!;
    destination.querySelector('input')!.click();
    http.expectOne('/api/email-pages?category=event%20invitations').flush({
      items: [page.items[0]],
      next_cursor: null,
    });
    fixture.detectChanges();

    expect(root.querySelector('li h3')?.textContent).toBe('Mensagem de teste 1');
    expect(root.querySelector('form')).toBeNull();
  });

  function job(
    status: EmailSyncJob['status'] = 'IMPORTING',
    imported = 0,
    analyzed = 0,
    failed = 0,
  ): EmailSyncJob {
    return {
      id: 'synthetic-job',
      status,
      imported_count: imported,
      analyzed_count: analyzed,
      analysis_failed_count: failed,
    };
  }

  function freshEmails(count: number): EmailMetadata[] {
    return Array.from({ length: count }, (_, index) => ({
      ...page.items[0],
      gmail_id: `new-${index}`,
      subject: `Novidade ${index + 1}`,
      imported_at: `2026-11-07T01:${String(index).padStart(2, '0')}:00Z`,
      processing_status: 'PENDING',
      effective_category: '',
    }));
  }

  function flushChanges(
    items: readonly EmailMetadata[],
    cursor: string,
    after = 'initial-revision',
    until: string | null = null,
  ) {
    http
      .expectOne(`/api/email-changes?cursor=${after}&include_updates=true`)
      .flush({ items, change_cursor: cursor, until_cursor: until });
  }

  it('exibe 5, depois 5 e o restante de 2 durante a sincronização sem duplicar os antigos', async () => {
    vi.useFakeTimers();
    const { fixture, root, request } = renderInbox();
    request.flush({ ...page, items: page.items.slice(0, 1), next_cursor: null });
    fixture.detectChanges();
    const fresh = freshEmails(12);
    const refresh = root.querySelector<HTMLButtonElement>('button.refresh-action')!;
    refresh.click();
    refresh.click();
    http.expectOne({ method: 'POST', url: '/api/email-sync-jobs' }).flush(job('IMPORTING', 5));
    flushChanges(fresh.slice(0, 5), 'after-five');
    fixture.detectChanges();
    expect(root.querySelectorAll('li')).toHaveLength(5);
    expect(root.querySelector('.sync-progress')?.textContent).toContain('5 importadas');
    expect(root.querySelectorAll('.message-state')[0].textContent).toContain('Em análise');

    await vi.advanceTimersByTimeAsync(2_000);
    http.expectOne('/api/email-sync-jobs/synthetic-job').flush(job('IMPORTING', 10));
    flushChanges(fresh.slice(5, 10), 'after-ten', 'after-five');
    fixture.detectChanges();
    expect(root.querySelector('.sync-progress')?.textContent).toContain('10 importadas');
    expect(root.querySelector('[aria-label="Página 3"]')).not.toBeNull();

    await vi.advanceTimersByTimeAsync(2_000);
    http.expectOne('/api/email-sync-jobs/synthetic-job').flush(job('ANALYZING', 12));
    flushChanges(fresh.slice(10), 'after-twelve', 'after-ten');
    fixture.detectChanges();
    expect(root.querySelector('.sync-progress')?.textContent).toContain('12 importadas');

    await vi.advanceTimersByTimeAsync(2_000);
    http.expectOne('/api/email-sync-jobs/synthetic-job').flush(job('COMPLETED', 12, 12));
    const completed = fresh.map((email) => ({
      ...email,
      processing_status: 'COMPLETED' as const,
      effective_category: 'event invitations',
    }));
    flushChanges(completed.slice(0, 5), 'analysis-five', 'after-twelve', 'analysis-end');
    http
      .expectOne('/api/email-changes?cursor=analysis-five&until=analysis-end&include_updates=true')
      .flush({
        items: completed.slice(5, 10),
        change_cursor: 'analysis-ten',
        until_cursor: 'analysis-end',
      });
    http
      .expectOne('/api/email-changes?cursor=analysis-ten&until=analysis-end&include_updates=true')
      .flush({ items: completed.slice(10), change_cursor: 'analysis-end', until_cursor: null });
    fixture.detectChanges();
    expect(root.querySelector('.refresh-feedback')?.textContent).toContain(
      '12 mensagens importadas',
    );
    expect(root.querySelector('.sync-progress')).toBeNull();

    const subjects: string[] = [];
    for (const number of [1, 2, 3]) {
      root.querySelector<HTMLButtonElement>(`[aria-label="Página ${number}"]`)!.click();
      fixture.detectChanges();
      subjects.push(...[...root.querySelectorAll('li h3')].map((item) => item.textContent!));
    }
    expect(subjects).toHaveLength(13);
    expect(new Set(subjects).size).toBe(13);
    expect(subjects).toContain('Mensagem de teste 1');
    await vi.advanceTimersByTimeAsync(4_000);
    http.expectNone('/api/email-sync-jobs/synthetic-job');
  });

  it('mostra menos de cinco mensagens ao terminar e encerra as consultas', async () => {
    vi.useFakeTimers();
    const { fixture, root, request } = renderInbox();
    request.flush({ ...page, items: [], next_cursor: null });
    fixture.detectChanges();
    root.querySelector<HTMLButtonElement>('button.refresh-action')!.click();
    http.expectOne('/api/email-sync-jobs').flush(job('IMPORTING'));
    flushChanges([], 'waiting');
    await vi.advanceTimersByTimeAsync(2_000);
    http.expectOne('/api/email-sync-jobs/synthetic-job').flush(job('COMPLETED', 3, 3));
    flushChanges(freshEmails(3), 'done', 'waiting');
    fixture.detectChanges();
    expect(root.querySelectorAll('li')).toHaveLength(3);
    expect(root.querySelector('.refresh-feedback')?.textContent).toContain(
      '3 mensagens importadas',
    );
    await vi.advanceTimersByTimeAsync(4_000);
    http.expectNone('/api/email-sync-jobs/synthetic-job');
  });

  it('mantém o mesmo cartão selecionado quando chegam mensagens e a análise termina', async () => {
    vi.useFakeTimers();
    const { fixture, root, request } = renderInbox();
    const fresh = freshEmails(5);
    request.flush({ ...page, items: fresh, next_cursor: null });
    fixture.detectChanges();
    const card = root.querySelector<HTMLLIElement>('li')!;
    card.click();
    root.querySelector<HTMLButtonElement>('button.refresh-action')!.click();
    http.expectOne('/api/email-sync-jobs').flush(job('ANALYZING', 5));
    const newer = freshEmails(5).map((email) => ({
      ...email,
      gmail_id: 'later-' + email.gmail_id,
      imported_at: '2026-11-07T02:00:00Z',
    }));
    flushChanges(newer, 'later');
    fixture.detectChanges();
    expect(root.querySelector('[aria-current="page"]')?.textContent?.trim()).toBe('2');
    expect([...root.querySelectorAll('li')]).toContain(card);

    await vi.advanceTimersByTimeAsync(2_000);
    http.expectOne('/api/email-sync-jobs/synthetic-job').flush(job('COMPLETED', 5, 4, 1));
    flushChanges(
      [
        { ...fresh[0], processing_status: 'COMPLETED', effective_category: 'event invitations' },
        { ...fresh[1], processing_status: 'FAILED' },
      ],
      'analyzed',
      'later',
    );
    fixture.detectChanges();
    expect([...root.querySelectorAll('li')]).toContain(card);
    expect(card.querySelector('.message-state')?.textContent).toContain('Eventos');
    expect(root.textContent).toContain('Falha na análise');
  });

  it('retira do filtro Sem categoria a mensagem classificada depois da importação', () => {
    const { fixture, root, request } = renderInbox();
    request.flush({ ...page, next_cursor: null });
    fixture.detectChanges();
    const filter = [...root.querySelectorAll('.category-option')].find(
      (label) => label.textContent.trim() === 'Sem categoria',
    )!;
    filter.querySelector('input')!.click();
    http
      .expectOne('/api/email-pages?category=')
      .flush({ ...page, items: freshEmails(1), next_cursor: null });
    fixture.detectChanges();
    root.querySelector<HTMLButtonElement>('button.refresh-action')!.click();
    http.expectOne('/api/email-sync-jobs').flush(job('COMPLETED', 1, 1));
    flushChanges(
      [{ ...freshEmails(1)[0], processing_status: 'COMPLETED', effective_category: 'other' }],
      'classified',
    );
    fixture.detectChanges();
    expect(root.querySelectorAll('li')).toHaveLength(0);
    expect(root.textContent).toContain('Nenhum e-mail encontrado nesta categoria');
  });

  it('preserva o primeiro lote quando a sincronização falha', async () => {
    vi.useFakeTimers();
    const { fixture, root, request } = renderInbox();
    request.flush({ ...page, items: [], next_cursor: null });
    fixture.detectChanges();
    root.querySelector<HTMLButtonElement>('button.refresh-action')!.click();
    http.expectOne('/api/email-sync-jobs').flush(job('IMPORTING', 5));
    flushChanges(freshEmails(5), 'first-batch');
    await vi.advanceTimersByTimeAsync(2_000);
    http.expectOne('/api/email-sync-jobs/synthetic-job').flush(job('FAILED', 5));
    flushChanges([], 'failed', 'first-batch');
    fixture.detectChanges();
    expect(root.querySelectorAll('li')).toHaveLength(5);
    expect(root.querySelector('.refresh-feedback')?.textContent).toContain(
      'A sincronização falhou',
    );
  });

  it('mantém o último cursor confirmado se uma consulta falhar e permite retomar', async () => {
    vi.useFakeTimers();
    const { fixture, root, request } = renderInbox();
    request.flush({ ...page, next_cursor: null });
    fixture.detectChanges();
    const refresh = root.querySelector<HTMLButtonElement>('button.refresh-action')!;
    refresh.click();
    http.expectOne('/api/email-sync-jobs').flush(job('IMPORTING', 1));
    flushChanges(freshEmails(1), 'saved-cursor');
    await vi.advanceTimersByTimeAsync(2_000);
    http
      .expectOne('/api/email-sync-jobs/synthetic-job')
      .flush(null, { status: 503, statusText: 'Unavailable' });
    fixture.detectChanges();
    expect(root.querySelector('.refresh-feedback')?.textContent).toContain(
      'As mensagens continuam disponíveis',
    );
    expect(root.querySelectorAll('li')).toHaveLength(5);
    refresh.click();
    http.expectOne('/api/email-sync-jobs').flush(job('COMPLETED', 1, 1));
    flushChanges([], 'resumed', 'saved-cursor');
  });

  it('cancela a consulta pendente e os temporizadores ao sair da tela', async () => {
    vi.useFakeTimers();
    const { fixture, root, request } = renderInbox();
    request.flush({ ...page, next_cursor: null });
    fixture.detectChanges();
    root.querySelector<HTMLButtonElement>('button.refresh-action')!.click();
    http.expectOne('/api/email-sync-jobs').flush(job());
    flushChanges([], 'waiting');
    await vi.advanceTimersByTimeAsync(2_000);
    const pending = http.expectOne('/api/email-sync-jobs/synthetic-job');
    fixture.destroy();
    expect(pending.cancelled).toBe(true);
    await vi.advanceTimersByTimeAsync(6_000);
    http.expectNone('/api/email-sync-jobs/synthetic-job');
  });

  it('continua sincronizando quando a pessoa troca de categoria', async () => {
    vi.useFakeTimers();
    const { fixture, root, request } = renderInbox();
    request.flush({ ...page, next_cursor: null });
    fixture.detectChanges();
    root.querySelector<HTMLButtonElement>('button.refresh-action')!.click();
    http.expectOne('/api/email-sync-jobs').flush(job());
    flushChanges([], 'waiting');
    const events = [...root.querySelectorAll('.category-option')].find(
      (label) => label.textContent.trim() === 'Eventos',
    )!;
    events.querySelector('input')!.click();
    http.expectOne('/api/email-pages?category=event%20invitations').flush({
      ...page,
      items: [],
      next_cursor: null,
    });
    await vi.advanceTimersByTimeAsync(2_000);
    http.expectOne('/api/email-sync-jobs/synthetic-job').flush(job('COMPLETED', 1, 1));
    flushChanges(
      [
        {
          ...freshEmails(1)[0],
          processing_status: 'COMPLETED',
          effective_category: 'event invitations',
        },
      ],
      'done',
      'waiting',
    );
    fixture.detectChanges();
    expect(root.querySelectorAll('li')).toHaveLength(1);
    expect(root.querySelector('li')?.textContent).toContain('Eventos');
  });

  it('ignora os dados antigos de uma página que respondeu depois da classificação', () => {
    const { fixture, root, request } = renderInbox();
    request.flush({ ...page, next_cursor: null });
    fixture.detectChanges();
    const uncategorized = [...root.querySelectorAll('.category-option')].find(
      (label) => label.textContent.trim() === 'Sem categoria',
    )!;
    uncategorized.querySelector('input')!.click();
    const fresh = freshEmails(6);
    http
      .expectOne('/api/email-pages?category=')
      .flush({ ...page, items: fresh.slice(0, 5), next_cursor: 'tail' });
    const pendingPage = http.expectOne('/api/email-pages?cursor=tail&category=');
    fixture.detectChanges();
    root.querySelector<HTMLButtonElement>('button.refresh-action')!.click();
    http.expectOne('/api/email-sync-jobs').flush(job('COMPLETED', 6, 6));
    flushChanges(
      [{ ...fresh[5], processing_status: 'COMPLETED', effective_category: 'other' }],
      'classified',
    );
    pendingPage.flush({ ...page, items: [fresh[5]], next_cursor: null });
    fixture.detectChanges();
    expect(root.querySelectorAll('li')).toHaveLength(5);
    expect(root.textContent).not.toContain('Novidade 6');
  });
});
