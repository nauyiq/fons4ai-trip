import { getApiBase } from './config';
import { useAuthStore } from '../store/authStore';

export interface LoginResult {
  token: string;
  tokenName: string;
}

export interface UserInfo {
  userId: string;
  username: string;
  realName: string | null;
  admin: boolean;
}

interface ApiResponse<T> {
  success: boolean;
  code: string;
  message: string;
  data: T | null;
}

async function responseData<T>(response: Response, fallbackMessage: string): Promise<T> {
  let body: ApiResponse<T> | null;
  try {
    body = await response.json() as ApiResponse<T>;
  } catch {
    throw new Error(fallbackMessage);
  }
  if (!response.ok || body?.success !== true || body.data == null) {
    throw new Error(body?.message || fallbackMessage);
  }
  return body.data;
}

function tokenHeaders(token: string, tokenName: string): Record<string, string> {
  return { [tokenName]: token };
}

/** 使用登录接口返回的请求头名称，供所有需登录的 API 共用。 */
export function storedAuthHeaders(): Record<string, string> {
  const { token, tokenName } = useAuthStore.getState();
  return token && tokenName ? tokenHeaders(token, tokenName) : {};
}

/** 登录，返回 Token */
export async function login(username: string, password: string): Promise<LoginResult> {
  const res = await fetch(`${getApiBase()}/api/auth/login`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
    body: new URLSearchParams({ username, password }),
  });
  const result = await responseData<LoginResult>(res, '登录失败');
  if (!result.token || !result.tokenName) {
    throw new Error('登录响应缺少令牌信息');
  }
  return result;
}

/** 退出登录 */
export async function logout(token: string, tokenName: string | null): Promise<void> {
  await fetch(`${getApiBase()}/api/auth/logout`, {
    method: 'POST',
    headers: tokenName ? tokenHeaders(token, tokenName) : {},
  }).catch(() => {
    // 忽略退出接口网络错误，本地状态照常清除
  });
}

/** 获取当前用户信息（可用于验证 token 有效性） */
export async function fetchUserInfo(token: string, tokenName: string): Promise<UserInfo> {
  const res = await fetch(`${getApiBase()}/api/auth/info`, {
    headers: tokenHeaders(token, tokenName),
  });
  const info = await responseData<UserInfo>(res, '未登录');
  if (!info.userId) throw new Error('用户信息缺少用户 ID');
  return info;
}
