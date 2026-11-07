import { DatePipe } from '@angular/common';
import { Component, computed, input, output } from '@angular/core';
import { EMAIL_CATEGORIES, MANUAL_CATEGORIES } from './email-categories';
import { EmailOriginal } from './email-original';
import { EmailMetadata } from './email-page';
import { EmailTrashAction } from './email-trash-action';

export interface CategorySubmission {
  event: Event;
  form: HTMLFormElement;
}

@Component({
  selector: 'li[app-email-card]',
  imports: [DatePipe, EmailOriginal, EmailTrashAction],
  templateUrl: './email-card.html',
  styleUrl: './email-card.css',
})
export class EmailCard {
  readonly email = input.required<EmailMetadata>();
  readonly allowManual = input(false);
  readonly savingId = input<string | null>(null);
  readonly headingLevel = input(3);
  readonly categorySubmitted = output<CategorySubmission>();
  protected readonly manualCategories = MANUAL_CATEGORIES;

  protected indicatorLabel(value: boolean | null | undefined): string {
    if (value == null) return 'Ainda não verificado';
    return value ? 'Sim' : 'Não';
  }
  protected readonly stateLabel = computed(() => {
    const email = this.email();
    if (email.processing_status === 'PENDING') return 'Em análise';
    if (email.processing_status === 'FAILED') return 'Falha na análise';
    return (
      EMAIL_CATEGORIES.find((option) => option.value === email.effective_category)?.label ??
      'Sem categoria'
    );
  });
}
