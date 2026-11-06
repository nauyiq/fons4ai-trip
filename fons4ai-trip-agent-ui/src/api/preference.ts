import { storedAuthHeaders } from './auth';
import { getApiBase } from './config';

export type PreferenceValue = string | string[] | null;
export interface PreferenceItem {
  key: string;
  label: string;
  type: 'single' | 'multi';
  options: string[];
  optionLabels: Record<string, string>;
}
export interface PreferenceCategory {
  category: string;
  label: string;
  icon: string;
  items: PreferenceItem[];
}
export interface SavedPreference {
  key: string;
  value: PreferenceValue;
  status: 'UNSET' | 'ACTIVE' | 'CLEARED';
  updatedAt: string | null;
}
export interface PreferenceSnapshot {
  /** 整份偏好版本，页面保存与 Agent 修改共用。 */
  version: string;
  items: SavedPreference[];
  categories: PreferenceCategory[];
}
export interface PreferenceChange {
  key: string;
  operation: 'SET' | 'CLEAR';
  value?: PreferenceValue;
}
export interface PreferenceSaveRequest {
  expectedVersion: string;
  changes: PreferenceChange[];
}
export class PreferenceApiError extends Error {
  readonly code?: string;
  constructor(message: string, code?: string) {
    super(message);
    this.code = code;
  }
}

/** R.success=false 即使 HTTP 200 也必须作为失败；不再从原生记忆解析页面数据。 */
async function request<T>(path: string, body?: PreferenceSaveRequest): Promise<T> {
  const response = await fetch(getApiBase() + path, {
    method: body ? 'POST' : 'GET',
    headers: { ...storedAuthHeaders(), ...(body ? { 'Content-Type': 'application/json' } : {}) },
    ...(body ? { body: JSON.stringify(body) } : {}),
  });
  if (response.status === 401) {
    throw new Error('UNAUTHORIZED');
  }
  const result = await response.json();
  if (result?.code === 'TA100003') {
    throw new Error('UNAUTHORIZED');
  }
  if (!response.ok || result?.success !== true || result?.data == null) {
    throw new PreferenceApiError(result?.message || '偏好请求失败，请稍后重试', result?.code);
  }
  return result.data as T;
}
export const fetchUserPreferences = () => request<PreferenceSnapshot>('/api/preferences');
export const saveUserPreferences = (body: PreferenceSaveRequest) =>
  request<PreferenceSnapshot>('/api/preferences', body);
