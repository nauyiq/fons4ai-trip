import { storedAuthHeaders } from './auth';
import { getApiBase } from './config';
import { readTripResponse } from './chat';

function authHeaders(): Record<string, string> {
  return storedAuthHeaders();
}

/** 单条预订记录（机票 / 酒店 / 火车 等） */
export interface MyTravelBooking {
  bookingId: string;
  bizType: string | null;
  bizTypeLabel: string | null;
  title: string | null;
  status: string | null;
  statusLabel: string | null;
  externalStatus: string | null;
  totalAmount: string | null;
  currency: string | null;
  platform: string | null;
  externalOrderNo: string | null;
  paymentStatus: string | null;
  startTime: number | null;
  endTime: number | null;
  bookedAt: number | null;
  /** 第三方平台订单详情页 URL，为 null 时不展示跳转入口 */
  orderUrl: string | null;
}

/** 「我的差旅」列表项：差旅单 + 最新审批 + 预订摘要 */
export interface MyTravelOrder {
  orderId: string;
  destination: string | null;
  departureCity: string | null;
  departureDate: string | null;
  returnDate: string | null;
  purpose: string | null;
  status: string | null;
  statusLabel: string | null;
  createdAt: number | null;
  updatedAt: number | null;
  international: boolean;
  approvalId: string | null;
  approvalStatus: string | null;
  approvalStatusLabel: string | null;
  approvalRemark: string | null;
  approvalSubmitTime: number | null;
  approvalUpdateTime: number | null;
  bookingCount: number;
  bookings: MyTravelBooking[];
  /** 是否存在当前差旅单的已发布方案，不暴露 OSS 路径。 */
  hasPlan: boolean;
}

/** 当前差旅单最新已发布方案的摘要。 */
export interface PublishedPlanSummary {
  orderId: string;
  /** 请求时差旅单的状态，不代表方案生成时的状态。 */
  orderStatus: string | null;
  statusLabel: string | null;
  planId: string;
  generatedAt: string | null;
  reviewId: string;
  recommendedProposalId: string | null;
  verdict: string | null;
  nextAction: string | null;
}

/** 拉取当前登录用户的全部出差申请（含审批状态与预订摘要） */
export async function fetchMyTravelOrders(): Promise<MyTravelOrder[]> {
  const res = await fetch(`${getApiBase()}/api/my-travel/orders`, {
    headers: authHeaders(),
  });
  const rows = await readTripResponse<Array<Omit<MyTravelOrder, 'createdAt' | 'updatedAt' | 'bookings' | 'bookingCount'> & {
    created: number | null; updated: number | null; bookings: MyTravelBooking[] | null; bookingCount: number | null;
  }>>(res);
  return (rows ?? []).map(row => ({
    ...row,
    createdAt: row.created,
    updatedAt: row.updated,
    bookings: row.bookings ?? [],
    bookingCount: row.bookingCount ?? 0,
    hasPlan: row.hasPlan === true,
  }));
}

/** 读取指定差旅单的最新已发布规划和审核摘要。 */
export async function fetchPublishedPlan(orderId: string): Promise<PublishedPlanSummary> {
  const res = await fetch(`${getApiBase()}/api/my-travel/orders/${encodeURIComponent(orderId)}/plan`, {
    headers: authHeaders(),
  });
  return readTripResponse<PublishedPlanSummary>(res);
}

/** 拉取指定差旅单最新已发布的行程方案 HTML，业务失败不能当成页面内容。 */
export async function fetchPlanHtml(orderId: string): Promise<string> {
  const res = await fetch(`${getApiBase()}/api/my-travel/plan-html/${encodeURIComponent(orderId)}`, {
    headers: authHeaders(),
  });
  const html = await readTripResponse<string>(res);
  if (typeof html !== 'string' || !html.trim()) throw new Error('行程方案页面内容为空');
  return html;
}

/** 删除指定预订记录（逻辑删除） */
export async function deleteBooking(bookingId: string): Promise<{ deleted: boolean }> {
  const res = await fetch(`${getApiBase()}/api/my-travel/bookings/${bookingId}`, {
    method: 'DELETE',
    headers: authHeaders(),
  });
  if (res.status === 401) throw new Error('UNAUTHORIZED');
  if (!res.ok) throw new Error(`HTTP ${res.status}`);
  return res.json();
}

/** 取消指定预订记录（同步调用第三方平台取消接口 + 更新内部状态为 CANCELLED） */
export async function cancelBooking(
  bookingId: string,
  reason?: string,
): Promise<{ cancelled: boolean; message: string }> {
  const res = await fetch(`${getApiBase()}/api/my-travel/bookings/${bookingId}/cancel`, {
    method: 'POST',
    headers: { ...authHeaders(), 'Content-Type': 'application/json' },
    body: JSON.stringify(reason ? { reason } : {}),
  });
  if (res.status === 401) throw new Error('UNAUTHORIZED');
  if (!res.ok) {
    const body = await res.json().catch(() => ({}));
    throw new Error(body.message || `HTTP ${res.status}`);
  }
  return res.json();
}
