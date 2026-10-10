import { useChatStore } from '../store/chatStore';
import { useAuthStore } from '../store/authStore';
import { useUiStore } from '../store/uiStore';
import { logout } from '../api/auth';
import { fetchConversations, deleteConversation as deleteRemoteConversation } from '../api/chat';
import { isClusterAvailable, getClusterUrls } from '../api/config';
import type { Conversation } from '../store/chatStore';
import { useEffect, useState } from 'react';

function formatTime(ts: number) {
  const d = new Date(ts);
  const now = new Date();
  const isToday =
    d.getDate() === now.getDate() &&
    d.getMonth() === now.getMonth() &&
    d.getFullYear() === now.getFullYear();
  if (isToday) {
    return d.toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit' });
  }
  return d.toLocaleDateString('zh-CN', { month: 'short', day: 'numeric' });
}

function ConversationItem({
  conv,
  isActive,
  onSwitch,
  onDelete,
  disabled,
}: {
  conv: Conversation;
  isActive: boolean;
  onSwitch: () => void;
  onDelete: (e: React.MouseEvent) => void;
  disabled: boolean;
}) {
  return (
    <div className={`conv-item${isActive ? ' active' : ''}`} onClick={() => { if (!disabled) onSwitch(); }} aria-disabled={disabled}>
      <svg className="conv-icon" viewBox="0 0 16 16" fill="none">
        <path
          d="M8 1.5C4.41 1.5 1.5 4.06 1.5 7.25c0 1.66.73 3.15 1.89 4.22L2.5 14.5l3.22-1.61c.71.23 1.47.36 2.28.36 3.59 0 6.5-2.56 6.5-5.75S11.59 1.5 8 1.5z"
          stroke="currentColor"
          strokeWidth="1.2"
          strokeLinejoin="round"
        />
      </svg>
      <div className="conv-body">
        <span className="conv-title">{conv.title}</span>
        <span className="conv-time">{formatTime(conv.updatedAt)}</span>
      </div>
      <button
        className="conv-delete"
        onClick={onDelete}
        title="删除对话"
        disabled={disabled}
      >
        <svg viewBox="0 0 12 12" fill="none" width="12" height="12">
          <path
            d="M2 3h8M5 3V2h2v1M3 3l.6 6.3a.8.8 0 00.8.7h3.2a.8.8 0 00.8-.7L9 3"
            stroke="currentColor"
            strokeWidth="1.2"
            strokeLinecap="round"
            strokeLinejoin="round"
          />
        </svg>
      </button>
    </div>
  );
}

const emptyConversation = (): Conversation => ({
  id: `session_${Date.now()}`,
  title: '新对话',
  messages: [],
  planProgress: null,
  planTasks: [],
  thinkingByAgent: {},
  travelData: [],
  timeline: [],
  isThinking: false,
  activeAgent: 'MasterAgent',
  suggestedQuestions: [],
  createdAt: Date.now(),
  updatedAt: Date.now(),
  isRemote: false,
  isLoaded: false,
});

export default function Sidebar() {
  const {
    conversations,
    currentConversationId,
    createConversation,
    switchConversation,
    deleteConversation,
    setConversations,
  } = useChatStore();

  const { token, username, userId, isAdmin, clearAuth } = useAuthStore();
  const view = useUiStore((s) => s.view);
  const setView = useUiStore((s) => s.setView);
  const clusterMode = useUiStore((s) => s.clusterMode);
  const toggleClusterMode = useUiStore((s) => s.toggleClusterMode);
  const [historyError, setHistoryError] = useState('');
  const [historyLoading, setHistoryLoading] = useState(false);
  const [historyRetry, setHistoryRetry] = useState(0);
  // 流式回调更新当前会话，执行中不切换，避免把输出写入另一条历史会话。
  const runActive = conversations.some(c => c.isThinking);

  useEffect(() => {
    let cancelled = false;
    const requestedAt = Date.now();
    const controller = new AbortController();
    setHistoryError('');
    setHistoryLoading(true);
    fetchConversations(controller.signal)
      .then((remote) => {
        if (cancelled || remote.length === 0) return;

        const state = useChatStore.getState();
        const remoteIds = new Set(remote.map((c) => c.id));
        const current = state.getCurrentConversation();
        // 初始化时的空白欢迎页不是历史记录，有历史时自动打开最近会话。
        // 请求期间用户新建或发送过消息，则保留用户选择。
        const replaceWelcome = state.conversations.length === 1 && current &&
          !current.isRemote && current.title === '新对话' && !current.isThinking &&
          !current.messages.some(m => m.role === 'user');

        const merged: Conversation[] = remote.map((c) => {
          const local = state.conversations.find((lc) => lc.id === c.id);
          if (local) {
            // 本地已有同一会话，保留已有消息与快照，仅标记为远端 persisted
            return { ...local, title: c.title, updatedAt: Math.max(local.updatedAt, c.updatedAt), isRemote: true };
          }
          return {
            ...emptyConversation(),
            id: c.id,
            title: c.title,
            createdAt: c.createdAt,
            updatedAt: c.updatedAt,
            isRemote: true,
            isLoaded: false,
          };
        });

        // 保留尚未持久化的本地新建会话
        const extraLocal = replaceWelcome ? [] : state.conversations.filter((c) =>
          !remoteIds.has(c.id) && (!c.isRemote || c.isThinking || c.updatedAt > requestedAt),
        );
        const all = [...merged, ...extraLocal].sort((a, b) => b.updatedAt - a.updatedAt);

        setConversations(all);
        if (!replaceWelcome && all.some(c => c.id === state.currentConversationId)) {
          // 保留当前选中的会话，避免加载历史时跳走
          return;
        }
        switchConversation(all[0].id);
      })
      .catch((err) => {
        if (cancelled) return;
        if (err?.message === 'UNAUTHORIZED') {
          clearAuth();
          return;
        }
        setHistoryError(err?.message || '会话列表加载失败');
      })
      .finally(() => { if (!cancelled) setHistoryLoading(false); });
    return () => {
      cancelled = true;
      controller.abort();
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [userId, historyRetry]);

  const handleLogout = async () => {
    await logout(token ?? '', useAuthStore.getState().tokenName);
    clearAuth();
  };

  const initial = username ? username.charAt(0).toUpperCase() : '?';

  return (
    <aside className="sidebar">
      {/* Logo */}
      <div className="sidebar-logo">
        <div className="sidebar-brand">
          <span className="fons-brand-mark" aria-hidden="true">F</span>
          <div>
            <div className="sidebar-brand-name">Fons 智能差旅</div>
            <div className="sidebar-brand-sub">智能差旅助手</div>
          </div>
        </div>
      </div>

      {/* New chat button */}
      <div className="sidebar-new">
        <button
          className="new-chat-btn"
          disabled={runActive}
          onClick={() => {
            setView('chat');
            createConversation();
          }}
        >
          <svg viewBox="0 0 16 16" fill="none" width="15" height="15">
            <path
              d="M8 2v12M2 8h12"
              stroke="currentColor"
              strokeWidth="2"
              strokeLinecap="round"
            />
          </svg>
          新建对话
        </button>
      </div>

      {/* Admin navigation */}
      <div className="sidebar-nav">
        <button
          className={`sidebar-nav-btn${view === 'chat' ? ' active' : ''}`}
          onClick={() => setView('chat')}
        >
          💬 对话助手
        </button>
        <button
          className={`sidebar-nav-btn${view === 'travel' ? ' active' : ''}`}
          onClick={() => setView('travel')}
        >
          💼 我的差旅
        </button>
        <button
          className={`sidebar-nav-btn${view === 'preference' ? ' active' : ''}`}
          onClick={() => setView('preference')}
        >
          ⚙️ 偏好设置
        </button>
        {isAdmin && (
          <button
            className={`sidebar-nav-btn${view === 'admin' ? ' active' : ''}`}
            onClick={() => setView('admin')}
          >
            📋 审批管理
          </button>
        )}
      </div>

      {/* Conversation list */}
      <div className="sidebar-section-label">历史对话</div>
      <div className="conv-list">
        {historyLoading && <div className="history-status">正在加载会话列表…</div>}
        {historyError && <div className="history-status" role="alert">
          会话列表加载失败：{historyError}
          <button type="button" disabled={runActive} onClick={() => setHistoryRetry(n => n + 1)}>重试</button>
        </div>}
        {conversations.map((conv) => (
          <ConversationItem
            key={conv.id}
            conv={conv}
            isActive={conv.id === currentConversationId}
            disabled={runActive}
            onSwitch={() => { setView('chat'); switchConversation(conv.id); }}
            onDelete={async (e) => {
              e.stopPropagation();
              if (conv.isRemote) {
                try {
                  await deleteRemoteConversation(conv.id);
                } catch (err: any) {
                  if (err?.message === 'UNAUTHORIZED') {
                    clearAuth();
                    return;
                  }
                  console.error('删除对话失败', err);
                  setHistoryError(`删除会话失败：${err?.message || '未知错误'}`);
                  return;
                }
              }
              deleteConversation(conv.id);
            }}
          />
        ))}
      </div>

      {/* Footer: user info + logout */}
      <div className="sidebar-footer">
        {/* 集群轮询开关（仅在配置了 VITE_CLUSTER_URLS 时显示） */}
        {isClusterAvailable() && (
          <div className="sidebar-cluster-toggle">
            <button
              className={`cluster-toggle-btn${clusterMode ? ' active' : ''}`}
              onClick={toggleClusterMode}
              title={clusterMode ? '关闭集群轮询' : '开启集群轮询'}
            >
              <span className="cluster-toggle-icon">⚡</span>
              <span className="cluster-toggle-label">
                集群轮询 {clusterMode ? 'ON' : 'OFF'}
              </span>
              <span className={`cluster-toggle-switch${clusterMode ? ' on' : ''}`}>
                <span className="cluster-toggle-knob" />
              </span>
            </button>
            {clusterMode && (
              <div className="cluster-urls-hint">
                {getClusterUrls().map((url, i) => (
                  <span key={i} className="cluster-url-tag">{url}</span>
                ))}
              </div>
            )}
          </div>
        )}

        <div className="sidebar-user">
          <div className="sidebar-user-avatar">{initial}</div>
          <div className="sidebar-user-info">
            <div className="sidebar-user-name">{username}</div>
            <div className="sidebar-user-id">{userId}</div>
          </div>
          <button className="sidebar-logout-btn" onClick={handleLogout} title="退出登录">
            <svg viewBox="0 0 16 16" fill="none" width="14" height="14">
              <path
                d="M10 2h3a1 1 0 011 1v10a1 1 0 01-1 1h-3M7 11l3-3-3-3M10 8H2"
                stroke="currentColor"
                strokeWidth="1.4"
                strokeLinecap="round"
                strokeLinejoin="round"
              />
            </svg>
          </button>
        </div>
      </div>
    </aside>
  );
}
