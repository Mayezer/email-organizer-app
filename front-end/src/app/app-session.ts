import { HttpClient, HttpErrorResponse, HttpInterceptorFn, HttpParams } from '@angular/common/http';
import { Injectable, inject, signal } from '@angular/core';
import { finalize, switchMap, tap } from 'rxjs';

interface SessionResponse {
  gmailAccount: string | null;
}

@Injectable({ providedIn: 'root' })
export class AppSession {
  private readonly http = inject(HttpClient);
  private readonly account = signal<string | null | undefined>(undefined);
  private readonly pending = signal(false);
  private readonly message = signal('');
  readonly gmailAccount = this.account.asReadonly();
  readonly busy = this.pending.asReadonly();
  readonly error = this.message.asReadonly();

  refresh(): void {
    this.pending.set(true);
    this.message.set('');
    this.readSession()
      .pipe(finalize(() => this.pending.set(false)))
      .subscribe({
        error: () => this.message.set('Não foi possível consultar a sessão. Tente novamente.'),
      });
  }

  signIn(username: string, password: string): void {
    if (this.busy()) return;
    this.pending.set(true);
    this.message.set('');
    const credentials = new HttpParams().set('username', username.trim()).set('password', password);
    this.http
      .post<void>('/api/session/login', credentials)
      .pipe(
        switchMap(() => this.readSession()),
        finalize(() => this.pending.set(false)),
      )
      .subscribe({
        error: (error: HttpErrorResponse) => {
          this.message.set(
            error.status === 401
              ? 'Usuário ou senha inválidos.'
              : 'Não foi possível entrar. Tente novamente.',
          );
        },
      });
  }

  signOut(): void {
    if (this.busy()) return;
    this.pending.set(true);
    this.message.set('');
    this.http
      .post<void>('/api/session/logout', null)
      .pipe(
        tap(() => this.account.set(undefined)),
        switchMap(() => this.readSession()),
        finalize(() => this.pending.set(false)),
      )
      .subscribe({
        error: (error: HttpErrorResponse) => {
          if (error.status === 401) {
            this.expire();
          } else {
            this.message.set('Não foi possível concluir a saída. Tente novamente.');
          }
        },
      });
  }

  expire(): void {
    if (this.account()) {
      this.account.set(null);
      this.message.set('Sua sessão expirou. Entre novamente para continuar.');
    }
  }

  private readSession() {
    return this.http
      .get<SessionResponse>('/api/session')
      .pipe(tap(({ gmailAccount }) => this.account.set(gmailAccount)));
  }
}

export const expireAppSession: HttpInterceptorFn = (request, next) => {
  if (!request.url.startsWith('/api/') || request.url.startsWith('/api/session')) {
    return next(request);
  }
  const session = inject(AppSession);
  return next(request).pipe(
    tap({
      error: (error: HttpErrorResponse) => {
        if (error.status === 401) session.expire();
      },
    }),
  );
};
