import { Component, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MarkdownPipe } from './markdown.pipe';

type Role = 'user' | 'assistant';
type Message = { role: Role; text: string };
type SavedChat = { id: string; title: string; messages: Message[]; updatedAt: number };

@Component({ selector: 'app-root', imports: [FormsModule, MarkdownPipe], templateUrl: './app.html', styleUrl: './app.css' })
export class App {
  protected prompt = '';
  protected readonly conversation = signal<Message[]>([]);
  protected readonly chats = signal<SavedChat[]>([]);
  protected readonly activeChatId = signal<string | null>(null);
  protected readonly sidebarCollapsed = signal(false);
  protected readonly isLoading = signal(false);
  protected readonly error = signal('');
  protected readonly suggestions = ['Create a 14-day OpenAI Academy AI learning plan tied to your Java and Angular background', 'Build a Java + Angular OpenCV mini-project using OpenCV 5.0', 'Build a credible beginner-to-project AI learning path using OpenAI Academy before adding ...'];
  private abortController?: AbortController;
  private readonly clientStorageKey = 'ai-chatbot.client-id';
  private readonly clientId = this.readClientId();

  ngOnInit(): void { void this.loadChats(this.chatIdFromUrl()); }

  protected async sendMessage(message = this.prompt): Promise<void> {
    const text = message.trim();
    if (!text || this.isLoading()) return;
    const history = this.conversation().map(item => ({ role: item.role, content: item.text }));
    const chatId = this.ensureActiveChat(text);
    this.prompt = ''; this.error.set(''); this.isLoading.set(true);
    this.conversation.update(items => [...items, { role: 'user', text }, { role: 'assistant', text: '' }]);
    this.saveActiveChat(chatId); this.abortController = new AbortController(); this.scrollToLatest();
    try {
      const title = this.chats().find(chat => chat.id === chatId)?.title ?? text;
      const response = await fetch('http://localhost:8080/api/chat', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ message: text, history, chatId, clientId: this.clientId, title }), signal: this.abortController.signal });
      if (!response.ok || !response.body) throw new Error('Unable to get a response.');
      const reader = response.body.getReader(); const decoder = new TextDecoder(); let buffer = '';
      while (true) {
        const { value, done } = await reader.read(); if (done) break;
        buffer += decoder.decode(value, { stream: true });
        const lines = buffer.split('\n'); buffer = lines.pop() ?? '';
        lines.forEach(line => this.applyChunk(line, chatId));
      }
    } catch (error: any) {
      if (error.name !== 'AbortError') this.error.set(error.message || 'Unable to contact the chat server.');
    } finally {
      this.saveActiveChat(chatId);
      this.isLoading.set(false);
      this.abortController = undefined;
      void this.loadChats();
    }
  }

  protected newChat(): void {
    this.abortController?.abort(); this.conversation.set([]); this.activeChatId.set(null); this.prompt = ''; this.error.set('');
    if (typeof history !== 'undefined') history.pushState({}, '', '/');
  }
  protected openChat(chat: SavedChat): void {
    if (this.isLoading()) return;
    this.activeChatId.set(chat.id); this.conversation.set(chat.messages); this.error.set(''); this.setChatUrl(chat.id); this.scrollToLatest();
  }
  protected stopGenerating(): void { this.abortController?.abort(); }
  protected toggleSidebar(): void { this.sidebarCollapsed.update(value => !value); }
  protected handleEnter(event: Event): void { if (!(event as KeyboardEvent).shiftKey) { event.preventDefault(); this.sendMessage(); } }

  private ensureActiveChat(firstMessage: string): string {
    const existing = this.activeChatId();
    if (existing) return existing;
    const id = crypto.randomUUID();
    this.activeChatId.set(id);
    this.chats.update(chats => [{ id, title: this.makeTitle(firstMessage), messages: [], updatedAt: Date.now() }, ...chats]);
    this.setChatUrl(id);
    return id;
  }
  private applyChunk(line: string, chatId: string): void {
    if (!line.trim()) return;
    const chunk = JSON.parse(line);
    if (chunk.error) { this.error.set(chunk.error); return; }
    if (!chunk.delta) return;
    this.conversation.update(items => {
      const updated = [...items];
      updated[updated.length - 1] = { role: 'assistant', text: updated[updated.length - 1].text + chunk.delta };
      return updated;
    });
    this.saveActiveChat(chatId); this.scrollToLatest();
  }
  private saveActiveChat(chatId: string): void {
    const messages = this.conversation();
    this.chats.update(chats => chats.map(chat => chat.id === chatId ? { ...chat, messages, updatedAt: Date.now() } : chat)
      .sort((a, b) => b.updatedAt - a.updatedAt));
  }
  private makeTitle(text: string): string { return text.replace(/\s+/g, ' ').trim(); }
  private async loadChats(chatIdToOpen?: string | null): Promise<void> {
    try {
      const response = await fetch(`http://localhost:8080/api/chat/history?clientId=${encodeURIComponent(this.clientId)}`);
      if (response.ok) {
        const chats = await response.json();
        const savedChats = chats.map((chat: any) => ({ ...chat, messages: chat.messages.map((message: any) => ({ role: message.role, text: message.content })) }));
        this.chats.set(savedChats);
        const chatToOpen = savedChats.find((chat: SavedChat) => chat.id === chatIdToOpen);
        if (chatToOpen) { this.activeChatId.set(chatToOpen.id); this.conversation.set(chatToOpen.messages); this.scrollToLatest(); }
      }
    } catch { this.error.set('Unable to load previous chats.'); }
  }
  private readClientId(): string {
    if (typeof localStorage === 'undefined') return '';
    const existing = localStorage.getItem(this.clientStorageKey);
    if (existing) return existing;
    const clientId = crypto.randomUUID(); localStorage.setItem(this.clientStorageKey, clientId); return clientId;
  }
  private chatIdFromUrl(): string | null {
    if (typeof location === 'undefined') return null;
    return location.pathname.match(/^\/chat\/([^/]+)$/)?.[1] ?? null;
  }
  private setChatUrl(chatId: string): void {
    if (typeof history !== 'undefined') history.pushState({}, '', `/chat/${chatId}`);
  }
  private scrollToLatest(): void { setTimeout(() => document.querySelector('.conversation')?.scrollTo({ top: 999999, behavior: 'smooth' })); }
}
