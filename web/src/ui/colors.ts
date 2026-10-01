import { type CliticRole } from '../core/gloss.ts';
import { Status, highlightIntensity, isLearning } from '../core/vocab.ts';

/** Word background: blue for NEW, fading yellows for LEVEL_1..LEARNED, none for KNOWN/IGNORED (as on Android). */
export function statusBackground(s: number): string | undefined {
  if (s === Status.NEW) return 'rgba(100,181,246,0.40)';
  if (isLearning(s)) return `rgba(255,193,7,${(0.75 * highlightIntensity(s)).toFixed(3)})`;
  return undefined;
}

export function statusSwatch(s: number): string {
  if (s === Status.NEW) return '#64B5F6';
  if (isLearning(s)) return `rgba(255,193,7,${(0.3 + 0.7 * highlightIntensity(s)).toFixed(3)})`;
  if (s === Status.KNOWN) return '#81C784';
  return '#BDBDBD';
}

export const CLITIC_COLOR: Record<CliticRole, string> = {
  DIRECT_OBJECT: '#42A5F5', INDIRECT_OBJECT: '#FFA726', REFLEXIVE: '#66BB6A', RECIPROCAL: '#26A69A',
  PRONOMINAL: '#AB47BC', ACCIDENTAL_SE: '#EC407A', IMPERSONAL_SE: '#78909C', OTHER: '#BDBDBD',
};
