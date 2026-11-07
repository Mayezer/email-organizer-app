import { Service, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Opportunity } from './opportunity';

@Service()
export class OpportunityApi {
    private readonly http = inject(HttpClient);

    listOpportunities() {
        this.http.get<Opportunity[]>('/api/opportunities')
    }
}
