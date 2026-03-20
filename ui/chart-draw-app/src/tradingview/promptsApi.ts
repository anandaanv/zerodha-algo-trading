import { apiFetch, getApiUrl } from '../config/api';
import { withAuth } from '../utils/apiHelper';

export interface ConversationMessage {
  role: 'user' | 'assistant';
  content: string;
}

export interface PromptBuildResponse {
  message: string;
  extractedPrompt: string;
}

export async function buildPrompt(
  history: ConversationMessage[],
  userMessage: string
): Promise<PromptBuildResponse> {
  const url = getApiUrl('/api/prompts/build');
  const res = await apiFetch(url.toString(), withAuth({
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ history, userMessage }),
  }));
  if (!res.ok) throw new Error(`Prompt build failed: ${res.status}`);
  return res.json();
}
