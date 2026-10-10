import { storedAuthHeaders } from './auth';
import { getApiBase } from './config';

export interface SSEEvent {
  event: string;
  data: string;
}

/** 从 authStore 读取 token，构造带鉴权的请求头 */
function authHeaders(): Record<string, string> {
  return storedAuthHeaders();
}

export async function sendChatMessageSSE(
  conversationId: string | undefined,
  message: string,
  onEvent: (event: SSEEvent) => void,
  onError?: (err: any) => void,
  onComplete?: () => void,
) {
  const response = await fetch(`${getApiBase()}/api/conversation/stream`, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      ...authHeaders(),
    },
    body: JSON.stringify({
      conversationId,
      messages: [{ content: message, messageType: 'TEXT' }],
    }),
  });

  // Token 无效或已过期时返回 HTTP 401
  if (response.status === 401) {
    throw new Error('UNAUTHORIZED');
  }

  if (!response.ok || !response.body) {
    throw new Error(`HTTP ${response.status}`);
  }

  const reader = response.body.getReader();
  const decoder = new TextDecoder('utf-8');
  let buffer = '';
  // currentEvent / pendingDataLines 必须在 while 循环外部声明，确保跨数据块持续有效
  let currentEvent = 'message';
  // 按 SSE 规范，一个事件的多行 data: 字段需要合并后再派发，否则换行符丢失
  let pendingDataLines: string[] = [];

  /** Trip 的 SSE 使用默认 message 事件，实际类型在 JSON 的 type 字段中。 */
  const dispatchTripEvent = (raw: string) => {
    const payload = JSON.parse(raw) as { type?: string; content?: string; data?: unknown };
    switch (payload.type) {
      case 'text':
        if (payload.content) onEvent({ event: 'message', data: payload.content });
        break;
      case 'thinking':
        if (payload.content) {
          onEvent({ event: 'thinking', data: JSON.stringify({ agentName: 'MasterAgent', text: payload.content }) });
        }
        break;
      case 'error':
        onEvent({ event: 'error', data: payload.content || '聊天执行失败' });
        break;
      case 'conversation_ready':
      case 'run_ended':
        onEvent({ event: payload.type, data: JSON.stringify(payload.data ?? {}) });
        break;
      default:
        // 其他 Trip 事件由后续前端能力按需接入。
        break;
    }
  };

  /** 将当前积累的 data 行合并派发，并重置状态 */
  const flushEvent = () => {
    if (pendingDataLines.length > 0) {
      const data = pendingDataLines.join('\n');
      if (data) {
        if (currentEvent === 'message') dispatchTripEvent(data);
        else onEvent({ event: currentEvent, data });
      }
      pendingDataLines = [];
    }
    currentEvent = 'message';
  };

  try {
    while (true) {
      const { done, value } = await reader.read();
      if (done) {
        // 流结束时派发尚未触发的最后一个事件
        flushEvent();
        break;
      }

      buffer += decoder.decode(value, { stream: true });
      const lines = buffer.split('\n');
      buffer = lines.pop() || '';

      for (const line of lines) {
        if (line.startsWith('event:')) {
          currentEvent = line.slice(6).trim();
        } else if (line.startsWith('data:')) {
          // 收集 data 行，等事件边界再合并派发
          pendingDataLines.push(line.slice(5).trim());
        } else if (line.trim() === '') {
          // SSE 事件边界（空行）：合并多行 data 后统一派发
          flushEvent();
        }
      }
    }
  } catch (err) {
    onError?.(err);
  } finally {
    onComplete?.();
  }
}

export async function sendUserResponseSSE(
  sessionId: string,
  toolUseId: string,
  response: unknown,
  onEvent: (event: SSEEvent) => void,
  onError?: (err: any) => void,
  onComplete?: () => void,
) {
  const res = await fetch(`${getApiBase()}/api/chat/respond`, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      ...authHeaders(),
    },
    body: JSON.stringify({ sessionId, toolUseId, response }),
  });

  if (res.status === 401) {
    throw new Error('UNAUTHORIZED');
  }

  if (!res.ok || !res.body) {
    throw new Error(`HTTP ${res.status}`);
  }

  const reader = res.body.getReader();
  const decoder = new TextDecoder('utf-8');
  let buffer = '';
  let currentEvent = 'message';
  let pendingDataLines: string[] = [];

  const flushEvent = () => {
    if (pendingDataLines.length > 0) {
      const data = pendingDataLines.join('\n');
      if (data) onEvent({ event: currentEvent, data });
      pendingDataLines = [];
    }
    currentEvent = 'message';
  };

  try {
    while (true) {
      const { done, value } = await reader.read();
      if (done) {
        flushEvent();
        break;
      }

      buffer += decoder.decode(value, { stream: true });
      const lines = buffer.split('\n');
      buffer = lines.pop() || '';

      for (const line of lines) {
        if (line.startsWith('event:')) {
          currentEvent = line.slice(6).trim();
        } else if (line.startsWith('data:')) {
          pendingDataLines.push(line.slice(5).trim());
        } else if (line.trim() === '') {
          flushEvent();
        }
      }
    }
  } catch (err) {
    onError?.(err);
  } finally {
    onComplete?.();
  }
}

/**
 * 打断指定 session 当前正在执行的 Agent 回复。
 * @returns 是否成功打断了正在运行的流
 */
export async function interruptAgent(sessionId: string): Promise<boolean> {
  const res = await fetch(`${getApiBase()}/api/chat/${sessionId}/interrupt`, {
    method: 'POST',
    headers: authHeaders(),
  });

  if (res.status === 401) {
    throw new Error('UNAUTHORIZED');
  }

  if (!res.ok) {
    throw new Error(`HTTP ${res.status}`);
  }

  const data = await res.json();
  return data.interrupted === true;
}

/**
 * 用户审批敏感工具调用（ToolConfirmationHook 触发的 HITL 确认）。
 *
 * @param sessionId 会话 ID
 * @param decision  'approve' 表示执行原敏感工具，'reject' 表示取消
 */
export async function confirmSensitiveToolSSE(
  sessionId: string,
  decision: 'approve' | 'reject',
  onEvent: (event: SSEEvent) => void,
  onError?: (err: any) => void,
  onComplete?: () => void,
) {
  const res = await fetch(`${getApiBase()}/api/chat/${sessionId}/confirm`, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      ...authHeaders(),
    },
    body: JSON.stringify({ decision }),
  });

  if (res.status === 401) {
    throw new Error('UNAUTHORIZED');
  }

  if (!res.ok || !res.body) {
    throw new Error(`HTTP ${res.status}`);
  }

  const reader = res.body.getReader();
  const decoder = new TextDecoder('utf-8');
  let buffer = '';
  let currentEvent = 'message';
  let pendingDataLines: string[] = [];

  const flushEvent = () => {
    if (pendingDataLines.length > 0) {
      const data = pendingDataLines.join('\n');
      if (data) onEvent({ event: currentEvent, data });
      pendingDataLines = [];
    }
    currentEvent = 'message';
  };

  try {
    while (true) {
      const { done, value } = await reader.read();
      if (done) {
        flushEvent();
        break;
      }

      buffer += decoder.decode(value, { stream: true });
      const lines = buffer.split('\n');
      buffer = lines.pop() || '';

      for (const line of lines) {
        if (line.startsWith('event:')) {
          currentEvent = line.slice(6).trim();
        } else if (line.startsWith('data:')) {
          pendingDataLines.push(line.slice(5).trim());
        } else if (line.trim() === '') {
          flushEvent();
        }
      }
    }
  } catch (err) {
    onError?.(err);
  } finally {
    onComplete?.();
  }
}


export interface RemoteConversation {
  id: string;
  title: string;
  createdAt: number;
  updatedAt: number;
}

export interface RemoteMessage {
  id: string;
  role: 'user' | 'agent' | 'system';
  content: string;
  agentName?: string;
  timestamp: number;
  thinking?: string;
  messageContentType?: 'TEXT' | 'IMAGE' | 'VOICE';
  extra?: Record<string, any>;
  /** 用户反馈：LIKE 点赞 / DISLIKE 点踩 / undefined 未反馈 */
  feedback?: 'LIKE' | 'DISLIKE' | null;
  /** 反馈时间戳（毫秒） */
  feedbackAt?: number;
}

/** 可直连调试的子智能体信息 */
export interface DebugAgentInfo {
  name: string;
  label: string;
}

/** 获取可直连调试的子智能体列表 */
export async function fetchDebugAgents(): Promise<DebugAgentInfo[]> {
  const res = await fetch(`${getApiBase()}/api/debug/agent/list`, {
    headers: authHeaders(),
  });

  if (res.status === 401) {
    throw new Error('UNAUTHORIZED');
  }
  if (!res.ok) {
    throw new Error(`HTTP ${res.status}`);
  }
  return res.json();
}

/**
 * 【调试后门】直连指定子智能体对话（SSE 流式）。
 * 复用与 /api/chat 相同的 SSE 事件契约（message / error / user_interaction）。
 */
export async function sendDebugAgentMessageSSE(
  agentName: string,
  sessionId: string,
  message: string,
  onEvent: (event: SSEEvent) => void,
  onError?: (err: any) => void,
  onComplete?: () => void,
) {
  const response = await fetch(`${getApiBase()}/api/debug/agent/${agentName}`, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      ...authHeaders(),
    },
    body: JSON.stringify({ sessionId, message }),
  });

  if (response.status === 401) {
    throw new Error('UNAUTHORIZED');
  }

  if (!response.ok || !response.body) {
    throw new Error(`HTTP ${response.status}`);
  }

  const reader = response.body.getReader();
  const decoder = new TextDecoder('utf-8');
  let buffer = '';
  let currentEvent = 'message';
  let pendingDataLines: string[] = [];

  const flushEvent = () => {
    if (pendingDataLines.length > 0) {
      const data = pendingDataLines.join('\n');
      if (data) onEvent({ event: currentEvent, data });
      pendingDataLines = [];
    }
    currentEvent = 'message';
  };

  try {
    while (true) {
      const { done, value } = await reader.read();
      if (done) {
        flushEvent();
        break;
      }

      buffer += decoder.decode(value, { stream: true });
      const lines = buffer.split('\n');
      buffer = lines.pop() || '';

      for (const line of lines) {
        if (line.startsWith('event:')) {
          currentEvent = line.slice(6).trim();
        } else if (line.startsWith('data:')) {
          pendingDataLines.push(line.slice(5).trim());
        } else if (line.trim() === '') {
          flushEvent();
        }
      }
    }
  } catch (err) {
    onError?.(err);
  } finally {
    onComplete?.();
  }
}

/** Trip 普通接口使用 R<T> 包装；HTTP 200 不等于业务查询成功。 */
async function readTripResponse<T>(res: Response): Promise<T> {
  if (res.status === 401) throw new Error('UNAUTHORIZED');
  const body = await res.json() as { success: boolean; message?: string; data: T };
  if (!res.ok || body.success !== true) {
    throw new Error(body.message || `HTTP ${res.status}`);
  }
  return body.data;
}

export async function fetchConversations(signal?: AbortSignal): Promise<RemoteConversation[]> {
  const res = await fetch(`${getApiBase()}/api/conversation/list`, {
    headers: authHeaders(), signal,
  });
  const rows = await readTripResponse<Array<{
    id: string; title: string; created: number; updated: number;
  }>>(res);
  return (rows ?? []).map(row => ({
    id: row.id, title: row.title || '新对话',
    createdAt: row.created, updatedAt: row.updated,
  }));
}

interface TripHistoryMessage {
  id: string;
  role: 'user' | 'user_resume' | 'agent' | 'agent_hitl' | 'system';
  content: string;
  agentName?: string;
  created?: number;
  thinking?: string;
  messageContentType?: RemoteMessage['messageContentType'];
  extra?: Record<string, any>;
  feedback?: RemoteMessage['feedback'];
  feedbackAt?: number;
}

/** 当前历史页一次展示完整会话，逐页读取，避免只显示前 50 条而遗漏最新回复。 */
export async function fetchMessages(conversationId: string, signal?: AbortSignal): Promise<RemoteMessage[]> {
  const base = getApiBase();
  const messages = new Map<string, RemoteMessage>();
  for (let page = 1; ; page++) {
    const res = await fetch(`${base}/api/conversation/${encodeURIComponent(conversationId)}/messages?page=${page}&pageSize=50`, {
      headers: authHeaders(), signal,
    });
    const data = await readTripResponse<{ pages: number; resultList: TripHistoryMessage[] }>(res);
    const rows = data?.resultList ?? [];
    for (const row of rows) {
      messages.set(row.id, {
        ...row,
        role: row.role === 'user_resume' ? 'user' : row.role === 'agent_hitl' ? 'agent' : row.role,
        content: row.content ?? '',
        timestamp: row.created ?? 0,
      });
    }
    if (rows.length === 0 || page >= data.pages) break;
  }
  // 后端按创建时间和消息 ID 正序分页；保留该顺序，不按收到响应的时间重排。
  return [...messages.values()];
}

export async function deleteConversation(sessionId: string): Promise<void> {
  const res = await fetch(`${getApiBase()}/api/conversation/${encodeURIComponent(sessionId)}`, {
    method: 'DELETE',
    headers: authHeaders(),
  });

  await readTripResponse<void>(res);
}

export async function updateConversationTitle(sessionId: string, title: string): Promise<void> {
  const res = await fetch(`${getApiBase()}/api/chat/${sessionId}/title`, {
    method: 'PUT',
    headers: {
      'Content-Type': 'application/json',
      ...authHeaders(),
    },
    body: JSON.stringify({ title }),
  });

  if (res.status === 401) {
    throw new Error('UNAUTHORIZED');
  }
  if (!res.ok) {
    throw new Error(`HTTP ${res.status}`);
  }
}

/**
 * 提交/更新对某条 AI 回复的用户反馈。
 *
 * @param sessionId 会话 ID
 * @param messageId 消息 ID
 * @param feedback  'LIKE' | 'DISLIKE' | null（null 表示清空）
 */
export async function updateMessageFeedback(
  sessionId: string,
  messageId: string,
  feedback: 'LIKE' | 'DISLIKE' | null,
): Promise<void> {
  const res = await fetch(
    `${getApiBase()}/api/chat/${sessionId}/messages/${messageId}/feedback`,
    {
      method: 'PUT',
      headers: {
        'Content-Type': 'application/json',
        ...authHeaders(),
      },
      body: JSON.stringify({ feedback }),
    },
  );

  if (res.status === 401) {
    throw new Error('UNAUTHORIZED');
  }
  if (!res.ok) {
    throw new Error(`HTTP ${res.status}`);
  }
}
