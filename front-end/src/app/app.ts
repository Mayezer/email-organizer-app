import { Component } from '@angular/core';
import { Inbox } from './inbox/inbox';

@Component({
  imports: [Inbox],
  selector: 'app-root',
  styleUrl: './app.css',
  templateUrl: './app.html',
})
export class App {}
