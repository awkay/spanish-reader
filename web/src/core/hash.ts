// Same keys as Kotlin GlossCache: sentence fingerprint and word-form key.
import { ktTrim, normalize } from './text.ts';

/** SHA-256 hex of the sentence: trimmed, ASCII whitespace runs collapsed (Java regex \s), NFC-normalized. */
export async function sentenceHash(sentence: string): Promise<string> {
  const canonical = ktTrim(sentence).replace(/[ \t\n\x0B\f\r]+/g, ' ').normalize('NFC');
  const digest = await crypto.subtle.digest('SHA-256', new TextEncoder().encode(canonical));
  return [...new Uint8Array(digest)].map((b) => b.toString(16).padStart(2, '0')).join('');
}

export const formKey = (form: string) => normalize(ktTrim(form));
