export type Category = 'LIGHTING' | 'ENTRANCE_DOOR' | 'CLEANING' | 'OTHER';
export interface DemoItem {
  detail: { itemId: string; category: Category; location: string; symptom: string; contentVersion: number; workRound: number };
  state: { workStatus: 'NOT_ASSIGNED' | 'PLANNED' | 'REPORTED_DONE'; observation: 'NOT_RECEIVED' | 'RESTORED' | 'PERSISTS' | 'CANNOT_CHECK' };
}
export interface DemoCase {
  caseId: string;
  synthetic: boolean;
  originalText: string;
  reportText: string;
  items: DemoItem[];
  progress: { total: number; confirmed: number; withdrawn: number; remaining: number; allConfirmed: boolean };
}
export interface Comparison {
  mode: 'STUB' | 'RULES' | 'NLI' | 'MANUAL';
  modelVersion: string;
  suggestions: {
    itemId: string;
    relation: 'REPORTED_DONE' | 'PLANNED' | 'SYMPTOM_PERSISTS' | 'INSUFFICIENT_INFORMATION' | 'UNRELATED' | 'CONFLICT';
    evidence: { start: number; end: number; quote: string } | null;
  }[];
}

async function get<T>(path: string, signal?: AbortSignal): Promise<T> {
  const response = await fetch(path, { signal, headers: { Accept: 'application/json' } });
  if (!response.ok) throw new Error(`API вернул ${response.status}. Проверьте запуск backend в профиле local.`);
  return response.json() as Promise<T>;
}

export const loadDemo = (signal?: AbortSignal) => get<DemoCase>('/api/v1/demo/case', signal);
export const compareDemo = (signal?: AbortSignal) => get<Comparison>('/api/v1/demo/compare', signal);
