export type Actor = { username: string; displayName: string; tenantId: string; organizationId: string; role: string };
export type Organization = { id: string; name: string; parentId: string | null; status: string };
export type Person = { id: string; name: string; employeeNo: string; status: string; identityStatus: string; organizationId: string; organizationName: string; title: string; version: number };
export type Page<T> = { items: T[]; total: number; page: number; size: number };
export type Detail = { person: Person; history: { version: number; operation: string; recordedAt: string; validFrom: string; name: string; status: string }[] };
export class ApiError extends Error { constructor(public status: number, message: string) { super(message); } }
export async function api<T>(path: string, options: RequestInit = {}): Promise<T> {
  const headers = new Headers(options.headers);
  if (options.method && options.method !== 'GET') {
    const response = await fetch('/api/auth/csrf', { credentials: 'same-origin' });
    if (!response.ok) throw new ApiError(response.status, '无法校验会话，请刷新重试');
    const csrf = await response.json() as { headerName: string; token: string };
    headers.set(csrf.headerName, csrf.token);
    headers.set('Content-Type', 'application/json');
  }
  const response = await fetch(`/api${path}`, { ...options, headers, credentials: 'same-origin' });
  if (!response.ok) {
    const body = await response.json().catch(() => ({ message: '服务暂时不可用，请稍后重试' }));
    throw new ApiError(response.status, body.message ?? '请求失败');
  }
  if (response.status === 204) return undefined as T;
  return response.json() as Promise<T>;
}
