export interface CustomPrompt {
  id: string;
  name: string;
  promptText: string;
  createdAt: number;
}

const PROMPTS_KEY = 'custom_prompts_v1';
const LAST_PROMPT_KEY = 'last_used_prompt_id';

export function loadCustomPrompts(): CustomPrompt[] {
  try {
    const raw = localStorage.getItem(PROMPTS_KEY);
    if (!raw) return [];
    return JSON.parse(raw) as CustomPrompt[];
  } catch {
    return [];
  }
}

export function saveCustomPrompts(prompts: CustomPrompt[]): void {
  try {
    localStorage.setItem(PROMPTS_KEY, JSON.stringify(prompts));
  } catch { /* ignore */ }
}

export function getLastUsedPromptId(): string | null {
  return localStorage.getItem(LAST_PROMPT_KEY);
}

export function setLastUsedPromptId(id: string | null): void {
  try {
    if (id === null) {
      localStorage.removeItem(LAST_PROMPT_KEY);
    } else {
      localStorage.setItem(LAST_PROMPT_KEY, id);
    }
  } catch { /* ignore */ }
}
