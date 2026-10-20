import { Fragment, useEffect, useRef, useState } from 'react';
import type { KeyboardEvent } from 'react';
import { flushSync } from 'react-dom';
import { useChatStore, isPersistedMessageId } from '../store/chatStore';
import { useAuthStore } from '../store/authStore';
import { useUiStore } from '../store/uiStore';
import { logout } from '../api/auth';
import { fetchConversationExecution, sendChatMessageSSE, sendApprovalReplaySSE, interruptAgent, fetchConversations, fetchMessages, mapTripMessage, updateMessageFeedback, fetchDebugAgents, sendDebugAgentMessageSSE, type DebugAgentInfo, type RemoteMessage, type TripHistoryMessage } from '../api/chat';
import AgentMessageBlock, { ThinkingBlock } from './AgentMessageBlock';
import RequestSummary from './RequestSummary';
import ApprovalBatchCard from './ApprovalBatchCard';
import UserInteractionCard from './UserInteractionCard';
import type { Message, PlanTask, UserInteraction, TravelData, BookingResult, PlanHtmlData, ApprovalReplayRequest } from '../types';
import { approvalBatchReady, groupApprovalBatches, hasUnresolvedApproval, sameMessageSet } from '../approval';

/** 保留审批角色/凭证；正文与模型思考仍复用原有消息展示。 */
function historyMessage(message: RemoteMessage): Message {
  return {
    ...message,
    progress: message.extra?.progress,
    thinkingByAgent: message.extra?.thinkingByAgent,
    planTasks: message.extra?.planTasks,
    travelData: message.extra?.travelData,
    timeline: Array.isArray(message.extra?.timeline) ? message.extra.timeline : (message.thinking
      ? [{ kind: 'thinking', agentName: typeof message.extra?.thinkingAgentName === 'string'
        ? message.extra.thinkingAgentName : message.agentName || 'masterAgent', text: message.thinking }] : undefined),
  };
}

/** 只核销同一次 Pipeline 的取消提示，历史记录不能掩盖新请求的错误。 */
function isRunCancellation(message: Pick<RemoteMessage, 'role' | 'extra'>, pipelineRunId?: string): boolean {
  return !!pipelineRunId && message.role === 'system' && message.extra?.runState === 'cancelled'
    && message.extra?.pipelineRunId === pipelineRunId;
}

// ── Message bubble components ─────────────────────────────────────────────
function UserBubble({ msg, username }: { msg: Message; username: string }) {
  // 取用户名首字符作为头像
  const initial = username ? username.charAt(0).toUpperCase() : '我';
  return (
    <div className="msg-row user">
      <div className="msg-user-bubble">
        {msg.messageContentType === 'IMAGE' ? <img className="history-message-image" src={msg.content} alt="用户发送的图片" /> :
          msg.messageContentType === 'VOICE' ? <audio controls src={msg.content} /> : msg.content}
      </div>
      <div className="msg-avatar user-avatar">{initial}</div>
    </div>
  );
}

function SuggestionChips({
  questions,
  onClick,
}: {
  questions: string[];
  onClick: (q: string) => void;
}) {
  if (!questions.length) return null;
  return (
    <div className="msg-row agent">
      <div className="msg-avatar agent-avatar" style={{ visibility: 'hidden' }} />
      <div className="msg-agent-content">
        <div className="suggestion-chips">
          {questions.map((q) => (
            <button
              key={q}
              className="suggestion-chip"
              onClick={() => onClick(q)}
              title={q}
              type="button"
            >
              {q}
            </button>
          ))}
        </div>
      </div>
    </div>
  );
}

function SystemBubble({ msg }: { msg: Message }) {
  return (
    <div className="msg-row system">
      <div className="msg-system-bubble">
        <svg viewBox="0 0 16 16" fill="none" width="13" height="13" style={{ flexShrink: 0 }}>
          <circle cx="8" cy="8" r="6.5" stroke="#d46b08" strokeWidth="1.2" />
          <path d="M8 5v3.5M8 10.5v.5" stroke="#d46b08" strokeWidth="1.4" strokeLinecap="round" />
        </svg>
        {msg.content}
      </div>
    </div>
  );
}

// ── Thinking indicator ────────────────────────────────────────────────────
function ThinkingDots() {
  return (
    <div className="msg-row agent">
      <div className="msg-avatar agent-avatar">
        <svg viewBox="0 0 20 20" fill="none" width="18" height="18">
          <path
            d="M10 2C5.58 2 2 5.36 2 9.5c0 2.1.9 4 2.34 5.35L3.5 18l3.5-1.75c.93.31 1.93.5 2.99.5 4.42 0 8-3.36 8-7.5S14.42 2 10 2z"
            fill="white"
            fillOpacity="0.9"
          />
        </svg>
      </div>
      <div className="msg-agent-content">
        <div className="msg-agent-name">Fons 差旅助手</div>
        <div className="thinking-indicator">
          <span className="dot" />
          <span className="dot" />
          <span className="dot" />
        </div>
      </div>
    </div>
  );
}

// ── Main ChatWindow component ─────────────────────────────────────────────

/**
 * 判断条目是否为 MCP 内容块（形如 {type:'text', text:'...'}）。
 * 这类条目是工具的原始输出包装，而非可展示的业务数据。
 */
function isRawContentBlock(item: unknown): boolean {
  if (!item || typeof item !== 'object') return false;
  const obj = item as Record<string, unknown>;
  return obj.type === 'text' && typeof obj.text === 'string';
}

/**
 * 清洗后端推送的 travel_data：
 * - 过滤掉 MCP 内容块类条目，避免将原始 JSON 直接展示为卡片；
 * - 若无有效条目则返回 null（不渲染卡片，由正文 Markdown 展示）。
 */
function sanitizeTravelData(td: TravelData | null | undefined): TravelData | null {
  if (!td || !td.type || !Array.isArray(td.items)) return null;
  const items = (td.items as unknown[]).filter((it) => !isRawContentBlock(it));
  if (items.length === 0) return null;
  return { ...td, items: items as TravelData['items'] };
}

export default function ChatWindow() {
  const [inputText, setInputText] = useState('');
  const [interaction, setInteraction] = useState<UserInteraction | null>(null);
  const [historyError, setHistoryError] = useState('');
  const [historyRetry, setHistoryRetry] = useState(0);
  const [refreshing, setRefreshing] = useState(false);
  const [interrupting, setInterrupting] = useState(false);
  const [executionError, setExecutionError] = useState('');
  const [executionRetry, setExecutionRetry] = useState(0);
  /** 保证网络请求尚未返回时也能挡住第二次点击。 */
  const streamLock = useRef(false);
  const streamConversation = useRef('');
  /** 本次 SSE 的物理执行身份，用于把结束事件与刷新后的持久化结果对应起来。 */
  const streamPipelineRunId = useRef('');
  // 【调试后门】直连的目标子智能体，空字符串表示走默认智能调度（MasterAgent）
  const [debugAgent, setDebugAgent] = useState('');
  const [debugAgents, setDebugAgents] = useState<DebugAgentInfo[]>([]);
  const textareaRef = useRef<HTMLTextAreaElement>(null);
  const bottomRef = useRef<HTMLDivElement>(null);

  const username = useAuthStore((s) => s.username) ?? '我';
  const { token, userId, clearAuth } = useAuthStore();

  // 跨页面预填入口（“我的差旅”快捷动作）：非空则自动发送一次
  const pendingUserMessage = useUiStore((s) => s.pendingUserMessage);
  const consumePendingUserMessage = useUiStore((s) => s.consumePendingUserMessage);

  /** Token 失效时统一登出并跳回登录页 */
  const handleUnauthorized = async () => {
    await logout(token ?? '', useAuthStore.getState().tokenName);
    clearAuth();
  };

  const {
    conversations,
    currentConversationId,
    bindRemoteConversation,
    addMessage,
    appendToLastAgentMessage,
    addProgressStep,
    updatePlanTasks,
    addTravelData,
    snapshotProgressToLastMessage,
    clearProgress,
    appendThinking,
    startThinkingRound,
    setThinking,
    setActiveAgent,
    setSuggestedQuestions,
    clearSuggestedQuestions,
    loadConversationMessages,
    upsertMessages,
    setApprovalSyncRequired,
    setRunError,
    setConversationExecution,
    addTimelineItem,
    updateTimelineTool,
    appendTimelineThinking,
    setTimelinePlan,
    setMessageFeedback,
    setLastAgentMessageId,
  } = useChatStore();

  const currentConv = conversations.find((c) => c.id === currentConversationId);
  const messages = currentConv?.messages ?? [];
  const isThinking = currentConv?.isThinking ?? false;
  const timeline = currentConv?.timeline ?? [];
  const suggestedQuestions = currentConv?.suggestedQuestions ?? [];
  const historyPending = !!currentConv?.isRemote && !currentConv.isLoaded;
  const approvalBatches = groupApprovalBatches(messages);
  const approvalBlocked = hasUnresolvedApproval(messages) || !!currentConv?.approvalSyncRequired;
  const executionState = currentConv?.isRemote ? currentConv.executionState ?? 'UNKNOWN' : 'IDLE';
  const executionBlocked = executionState !== 'IDLE';
  const isStopping = interrupting || executionState === 'STOPPING';
  const canInterrupt = isThinking || executionState === 'RUNNING';

  /** 状态不明时保持禁用；不能用 finally 或连接结束推断后台已经退出。 */
  const syncExecution = async (conversationId: string) => {
    try {
      const status = await fetchConversationExecution(conversationId);
      if (useAuthStore.getState().userId === userId && useAuthStore.getState().token === token)
        setConversationExecution(conversationId, status.state);
    } catch (error) {
      if (useAuthStore.getState().userId === userId && useAuthStore.getState().token === token)
        setConversationExecution(conversationId, 'UNKNOWN');
      if (error instanceof Error && error.message === 'UNAUTHORIZED') await handleUnauthorized();
    }
  };

  // 刷新和断线后仍从服务端核对占用；只读轮询不会启动或重放 Agent。
  useEffect(() => {
    if (!currentConv?.isRemote) return;
    setExecutionError('');
    let cancelled = false;
    let timer: ReturnType<typeof setTimeout> | undefined;
    const controller = new AbortController();
    const poll = async () => {
      let state = 'UNKNOWN';
      try {
        const status = await fetchConversationExecution(currentConversationId, controller.signal);
        if (cancelled || useAuthStore.getState().userId !== userId || useAuthStore.getState().token !== token) return;
        const previous = useChatStore.getState().conversations.find(item => item.id === currentConversationId)?.executionState;
        state = status.state;
        setConversationExecution(currentConversationId, status.state);
        setExecutionError(state === 'UNKNOWN' ? '会话仍有未确认的执行占用，请核对服务端运行状态。' : '');
        // 没有本地 SSE 的任务结束后，补读持久化结果及审批卡片。
        if (state === 'IDLE' && previous && previous !== 'IDLE' && !streamLock.current) {
          const rows = await fetchMessages(currentConversationId, controller.signal);
          if (!cancelled) {
            loadConversationMessages(currentConversationId, rows.map(historyMessage));
            if (streamConversation.current === currentConversationId
                && rows.some(row => isRunCancellation(row, streamPipelineRunId.current))) {
              setRunError(currentConversationId);
            }
            setApprovalSyncRequired(currentConversationId, false);
          }
        }
      } catch (error) {
        if (cancelled) return;
        setConversationExecution(currentConversationId, 'UNKNOWN');
        setExecutionError('暂时无法确认会话是否结束，请稍后刷新。');
        if (error instanceof Error && error.message === 'UNAUTHORIZED') {
          await handleUnauthorized();
          return;
        }
      }
      if (!cancelled && state !== 'IDLE') timer = setTimeout(poll, state === 'UNKNOWN' ? 5000 : 2000);
    };
    void poll();
    return () => { cancelled = true; controller.abort(); if (timer) clearTimeout(timer); };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [currentConversationId, currentConv?.isRemote, userId, token, executionRetry, executionState]);

  const lastMsg = messages.length > 0 ? messages[messages.length - 1] : null;
  const lastAgentIsStreaming =
    isThinking && lastMsg?.role === 'agent' && !lastMsg.timeline;

  // 从后端加载远程会话的历史消息
  useEffect(() => {
    setHistoryError('');
    if (!currentConv || !currentConv.isRemote || currentConv.isLoaded || currentConv.isThinking) return;
    let cancelled = false;
    const controller = new AbortController();
    fetchMessages(currentConversationId, controller.signal)
      .then((remoteMessages) => {
        if (cancelled) return;
        loadConversationMessages(currentConversationId, remoteMessages.map(historyMessage));
        setApprovalSyncRequired(currentConversationId, false);
      })
      .catch((err) => {
        if (cancelled) return;
        if (err?.message === 'UNAUTHORIZED') {
          handleUnauthorized();
          return;
        }
        setHistoryError(err?.message || '聊天记录加载失败');
      });
    return () => {
      cancelled = true;
      controller.abort();
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [currentConversationId, currentConv?.isRemote, currentConv?.isLoaded, currentConv?.isThinking, historyRetry]);

  // Auto-scroll to bottom
  useEffect(() => {
    bottomRef.current?.scrollIntoView({ behavior: 'smooth' });
  }, [messages, isThinking, timeline]);

  // 加载可直连的子智能体列表（供右上角切换使用）
  useEffect(() => {
    fetchDebugAgents()
      .then(setDebugAgents)
      .catch(() => {
        // 调试后门不可用时静默忽略，正常对话不受影响
      });
  }, []);

  // 流式输出结束时，把当前时间轴快照到最后一条 agent 消息，避免正文出现后进度丢失
  const prevIsThinkingRef = useRef(isThinking);
  useEffect(() => {
    if (prevIsThinkingRef.current && !isThinking) {
      snapshotProgressToLastMessage();
    }
    prevIsThinkingRef.current = isThinking;
  }, [isThinking, snapshotProgressToLastMessage]);

  // Auto-resize textarea
  useEffect(() => {
    const ta = textareaRef.current;
    if (!ta) return;
    ta.style.height = 'auto';
    ta.style.height = Math.min(ta.scrollHeight, 140) + 'px';
  }, [inputText]);

  /** 重新读取完整历史，提交后的状态以服务端为准，不能乐观地把票证当成已消费。 */
  const refreshHistory = async (conversationId: string) => {
    setRefreshing(true);
    setApprovalSyncRequired(conversationId, true);
    try {
      const rows = await fetchMessages(conversationId);
      const canonical = rows.map(historyMessage);
      loadConversationMessages(conversationId, canonical);
      if (streamConversation.current === conversationId
          && rows.some(row => isRunCancellation(row, streamPipelineRunId.current))) {
        setRunError(conversationId);
      }
      // 历史已替代本轮临时消息，避免停止思考时再把旧进度快照到审批卡片之后。
      if (useChatStore.getState().currentConversationId === conversationId) clearProgress();
      setApprovalSyncRequired(conversationId, false);
      setHistoryError('');
      return canonical;
    } catch (failure) {
      if (failure instanceof Error && failure.message === 'UNAUTHORIZED') await handleUnauthorized();
      setHistoryError(failure instanceof Error ? failure.message : '状态刷新失败');
      throw failure;
    } finally { setRefreshing(false); }
  };

  const handleEvent = (event: string, data: string) => {
    // 会话绑定后继续使用同一服务端ID；迟到回调不能写入退出登录后的新会话。
    if (streamConversation.current && useChatStore.getState().currentConversationId !== streamConversation.current) return;
    switch (event) {
      case 'conversation_ready': {
        const { conversationId, pipelineRunId } = JSON.parse(data) as { conversationId?: string; pipelineRunId?: string };
        if (conversationId) {
          bindRemoteConversation(streamConversation.current || currentConversationId, conversationId);
          streamConversation.current = conversationId;
          streamPipelineRunId.current = pipelineRunId || '';
        }
        break;
      }
      case 'run_ended': {
        const result = JSON.parse(data) as {
          conversationId?: string; pipelineRunId?: string; state?: string; errorMessage?: string; nextAction?: string;
          pauseId?: string; pauseVersion?: number; approvalReady?: boolean; approvalMessages?: TripHistoryMessage[];
          terminalMessages?: TripHistoryMessage[];
          humanInTheLoopInfos?: Array<{ kind: string; question?: string; data?: Partial<UserInteraction> }>;
        };
        const conversationId = streamConversation.current || currentConversationId;
        setConversationExecution(conversationId, 'IDLE');
        if (result.state === 'waiting_approval') {
          snapshotProgressToLastMessage();
          const cards = result.approvalMessages;
          if (result.approvalReady === true && Array.isArray(cards) && cards.length > 0
              && new Set(cards.map(card => card.id)).size === cards.length
              && cards.every(card => card.role === 'agent_hitl' && card.hitlStatus === 'PENDING'
                && card.pauseId === result.pauseId && card.pauseVersion === result.pauseVersion)) {
            upsertMessages(conversationId, cards.map(card => historyMessage(mapTripMessage(card))));
          } else {
            setApprovalSyncRequired(conversationId, true);
            setHistoryError('审批卡片尚未完整取得，请刷新会话状态');
          }
        }
        if (result.nextAction === 'input_required') {
          const info = result.humanInTheLoopInfos?.find(item => item.kind === 'INPUT_REQUIRED');
          if (info?.question) setInteraction({ ...info.data, toolUseId: '', question: info.question, ui_type: info.data?.ui_type || 'text' });
        }
        if (result.state && result.state !== 'completed' && result.state !== 'waiting_approval') {
          const cancelledMessages = result.state === 'cancelled' && Array.isArray(result.terminalMessages)
            ? result.terminalMessages.filter(message => typeof message.id === 'string' && message.id.length > 0
              && isRunCancellation(message, result.pipelineRunId)) : [];
          if (cancelledMessages.length > 0) {
            // 与历史共用真实消息 ID；重复事件或刷新不再追加一条临时的取消消息。
            upsertMessages(conversationId, cancelledMessages.map(message => historyMessage(mapTripMessage(message))));
            setRunError(conversationId);
          } else {
            const existing = useChatStore.getState().conversations.find(item => item.id === conversationId)?.runError;
            setRunError(conversationId, result.errorMessage || existing || `聊天执行${result.state === 'cancelled' ? '已取消' : '失败'}`);
          }
        }
        break;
      }
      case 'agent-switch':
        setActiveAgent(data);
        break;
      case 'message':
        appendToLastAgentMessage(data);
        break;
      case 'message_id':
        // 后端回复落库后下发的真实 messageId，回填后点赞/点踩才能命中数据库记录
        setLastAgentMessageId(data.trim());
        break;
      case 'thinking': {
        try {
          const d = JSON.parse(data) as { agentName: string; text?: string; roundStart?: string };
          if (!d.agentName) break;
          if (d.roundStart === 'true') {
            // 新一轮思考开始
            startThinkingRound(d.agentName);
            appendTimelineThinking(d.agentName, '', true);
          } else if (d.text) {
            appendThinking(d.agentName, d.text);
            appendTimelineThinking(d.agentName, d.text);
          }
        } catch {
          // ignore malformed
        }
        break;
      }
      case 'plan_update': {
        try {
          const d = JSON.parse(data) as {
            type: string;
            agentName: string;
            planName: string;
            tasks: PlanTask[];
          };
          if (Array.isArray(d.tasks)) {
            try {
              flushSync(() => {
                updatePlanTasks(d.tasks);
                setTimelinePlan(d.tasks);
              });
            } catch {
              updatePlanTasks(d.tasks);
              setTimelinePlan(d.tasks);
            }
          }
        } catch {
          // ignore malformed
        }
        break;
      }
      case 'progress': {
        let progressData: {
          type: string;
          stepId: string;
          agentName: string;
          toolName?: string;
          message: string;
          result?: string;
          arguments?: string;
        };
        try {
          progressData = JSON.parse(data);
        } catch {
          break;
        }
        if (!progressData.stepId) break;
        const isDone = progressData.type === 'tool_done' || progressData.type === 'agent_done';
        const step = {
          id: progressData.stepId,
          title: progressData.message,
          status: (isDone ? 'done' : 'in-progress') as 'done' | 'in-progress',
          agentName: progressData.agentName,
          result: progressData.result,
          arguments: progressData.arguments,
        };
        try {
          flushSync(() => addProgressStep(step));
        } catch {
          addProgressStep(step);
        }
        if (progressData.type === 'tool_call') {
          addTimelineItem({
            kind: 'tool',
            id: progressData.stepId,
            agentName: progressData.agentName,
            title: progressData.message,
            status: 'in-progress',
            arguments: progressData.arguments,
          });
        } else if (progressData.type === 'tool_done') {
          updateTimelineTool(progressData.stepId, {
            status: 'done',
            title: progressData.message,
            result: progressData.result,
          });
        }
        break;
      }
      case 'error':
        setRunError(streamConversation.current || currentConversationId, data);
        break;
      case 'user_interaction': {
        try {
          const d = JSON.parse(data) as UserInteraction;
          // 触发用户交互时立即把当前轮次的进度/时间轴固化为一条消息，
          // 避免因随后 setThinking(false) 导致“流式占位”渲染条件失效，
          // 让前面的进度信息在交互卡片出现后从界面上消失。
          snapshotProgressToLastMessage();
          setInteraction(d);
          setThinking(false);
        } catch {
          // ignore malformed
        }
        break;
      }
      case 'interrupted':
        setThinking(false);
        clearSuggestedQuestions();
        addMessage({
          id: Math.random().toString(36).slice(2, 10),
          role: 'system',
          content: '已停止生成',
          timestamp: Date.now(),
        });
        break;
      case 'suggestions': {
        try {
          const questions = JSON.parse(data) as string[];
          if (Array.isArray(questions)) {
            setSuggestedQuestions(questions.filter((q) => typeof q === 'string' && q.length > 0));
          }
        } catch {
          // ignore malformed
        }
        break;
      }
      case 'travel_data': {
        try {
          const td = JSON.parse(data) as TravelData;
          const sanitized = sanitizeTravelData(td);
          if (sanitized) {
            addTravelData(sanitized);
            addTimelineItem({ kind: 'travel', data: sanitized });
          }
        } catch {
          // ignore malformed
        }
        break;
      }
      case 'booking_result': {
        try {
          const br = JSON.parse(data) as BookingResult;
          if (br && br.orderId) {
            addTimelineItem({ kind: 'booking', data: br });
          }
        } catch {
          // ignore malformed
        }
        break;
      }
      case 'plan_html': {
        try {
          const d = JSON.parse(data) as PlanHtmlData;
          if (d && d.html) {
            addTimelineItem({ kind: 'plan_html', data: d });
          }
        } catch {
          // ignore malformed
        }
        break;
      }
      default:
        break;
    }
  };

  const handleRespond = async (response: unknown) => {
    if (!interaction) return;
    const displayText = typeof response === 'string' ? response : JSON.stringify(response);
    // INPUT_REQUIRED是普通补充消息，不通过工具审批恢复接口提交。
    setInteraction(null);
    await sendText(displayText, true);
  };

  const submitApproval = async (request: ApprovalReplayRequest) => {
    if (streamLock.current || isThinking || executionBlocked) throw new Error('当前任务尚未结束');
    if (request.conversationId !== currentConversationId || useAuthStore.getState().userId !== userId)
      throw new Error('会话或账号已切换，请重新打开审批');
    streamLock.current = true;
    streamConversation.current = request.conversationId;
    streamPipelineRunId.current = '';
    try {
      // 提交前读取全部页面，不能以局部历史或过期卡片拼出不完整批次。
      const fresh = await refreshHistory(request.conversationId);
      const batch = groupApprovalBatches(fresh).find(item => item.pauseId === request.pauseId && item.pauseVersion === request.pauseVersion);
      if (!batch || !approvalBatchReady(batch)
          || !sameMessageSet(request.decisions.map(item => item.messageId), batch.messages.map(item => item.id)))
        throw new Error('审批状态已变化，请按刷新后的卡片处理');
      snapshotProgressToLastMessage(); clearProgress(); clearSuggestedQuestions();
      setRunError(request.conversationId);
      setInteraction(null); setThinking(true); setApprovalSyncRequired(request.conversationId, true);
      setConversationExecution(request.conversationId, 'RUNNING');
      await sendApprovalReplaySSE(request, event => handleEvent(event.event, event.data));
    } catch (failure) {
      if (failure instanceof Error && failure.message === 'UNAUTHORIZED') await handleUnauthorized();
      throw failure;
    } finally {
      // 断线后保留原请求；先读取权威状态，再允许重试同一份决定。
      if (useAuthStore.getState().userId === userId) {
        try { await refreshHistory(request.conversationId); } catch { /* 卡片保持待核对状态。 */ }
        await syncExecution(request.conversationId);
        if (useChatStore.getState().currentConversationId === request.conversationId) setThinking(false);
      }
      streamLock.current = false;
    }
  };

  const handleInterrupt = async () => {
    if (!canInterrupt || interrupting || isStopping) return;
    setInterrupting(true);
    try {
      await interruptAgent(currentConversationId);
      setConversationExecution(currentConversationId, 'STOPPING');
      setExecutionRetry(n => n + 1);
    } catch (err: any) {
      if (err?.message === 'UNAUTHORIZED') {
        await handleUnauthorized();
        return;
      }
      setExecutionError(`停止请求失败：${err?.message || '请稍后重试'}`);
    } finally { setInterrupting(false); }
  };

  /**
   * 处理用户对 AI 回复的点赞/点踩反馈。
   * 先乐观更新本地状态，调用失败时回滚到上一次状态。
   */
  const handleFeedback = async (
    messageId: string,
    next: 'LIKE' | 'DISLIKE' | null,
  ) => {
    // 本地临时 id（未收到后端 message_id）无法反馈，直接忽略避免请求 404
    if (!isPersistedMessageId(messageId)) {
      return;
    }
    const target = messages.find((m) => m.id === messageId);
    const prev = target?.feedback ?? null;
    // 再次点击同一选项视为取消
    const finalNext: 'LIKE' | 'DISLIKE' | null = prev === next ? null : next;
    setMessageFeedback(currentConversationId, messageId, finalNext);
    try {
      await updateMessageFeedback(currentConversationId, messageId, finalNext);
    } catch (err: any) {
      // 失败时回滚
      setMessageFeedback(currentConversationId, messageId, prev);
      if (err?.message === 'UNAUTHORIZED') {
        await handleUnauthorized();
        return;
      }
      addMessage({
        id: Math.random().toString(36).slice(2, 10),
        role: 'system',
        content: `反馈提交失败：${err?.message || '未知错误'}`,
        timestamp: Date.now(),
      });
    }
  };

  const sendText = async (content: string, inputResponse = false) => {
    if (streamLock.current || isThinking || (interaction && !inputResponse) || historyPending || approvalBlocked || refreshing || executionBlocked) return;
    if (!content.trim()) return;
    streamLock.current = true;
    streamConversation.current = currentConversationId;
    streamPipelineRunId.current = '';

    // 先把上一轮进度快照到上一条 agent 消息、清空 store，再追加本轮 user 消息。
    // 顺序不能反：若在追加 user 消息后再 snapshot，此时 messages 末尾是 user，
    // snapshotProgressToLastMessage 会走“无 agent 兜底”分支新建一条空白 agent 消息，
    // 导致上一轮的进度/时间轴以 phantom 消息形式出现在用户对话下方。
    snapshotProgressToLastMessage();
    clearProgress();
    clearSuggestedQuestions();
    setRunError(currentConversationId);

    addMessage({
      id: Math.random().toString(36).slice(2, 10),
      role: 'user',
      content,
      timestamp: Date.now(),
    });
    setThinking(true);
    if (!debugAgent) setConversationExecution(currentConversationId, 'RUNNING');
    // 直连调试模式下，把展示用的 activeAgent 切到目标智能体
    if (debugAgent) {
      setActiveAgent(debugAgent);
    }

    const onEvt = (evt: { event: string; data: string }) => handleEvent(evt.event, evt.data);
    const onErr = (err: any) => {
      if (err?.message === 'UNAUTHORIZED') {
        setThinking(false);
        handleUnauthorized();
        return;
      }
      setRunError(streamConversation.current || currentConversationId, `连接异常：${err?.message || '未知错误'}`);
    };
    const onDone = () => { if (debugAgent) setThinking(false); };

    try {
      if (debugAgent) {
        await sendDebugAgentMessageSSE(debugAgent, currentConversationId, content, onEvt, onErr, onDone);
      } else {
        await sendChatMessageSSE(currentConv?.isRemote ? currentConversationId : undefined, content, onEvt);
      }
    } catch (err: unknown) {
      const failure = err instanceof Error ? err : new Error('请求失败');
      if (failure.message === 'UNAUTHORIZED') {
        // Token 已失效，自动登出（clearAuth 会触发 App 渲染登录页）
        await handleUnauthorized();
        return;
      }
      const id = streamConversation.current || currentConversationId;
      const existing = useChatStore.getState().conversations.find(item => item.id === id)?.runError;
      // 断流可能发生在服务端错误之后，保留先收到的具体错误，不被泛化的EOF覆盖。
      setRunError(id, existing || `发送失败：${failure.message || '请检查后端服务是否启动'}`);
    } finally {
      const id = streamConversation.current;
      if (!debugAgent && useAuthStore.getState().userId === userId && useChatStore.getState().conversations.some(item => item.id === id && item.isRemote)) {
        // 新会话绑定后校准服务端标题，后台刷新不阻塞历史核对或下一次输入。
        if (!currentConv?.isRemote) {
          void fetchConversations().then(remote => {
            const auth = useAuthStore.getState();
            // 迟到响应不能写入退出登录或重新登录后的会话缓存。
            if (auth.userId !== userId || auth.token !== token) return;
            const conversation = remote.find(item => item.id === id);
            if (conversation) useChatStore.getState().updateConversationTitle(id, conversation.title);
          }).catch(error => console.warn('同步会话标题失败', error));
        }
        try { await refreshHistory(id); } catch { /* 不能确认状态时禁用后续审批和普通消息。 */ }
        await syncExecution(id);
      }
      if (useChatStore.getState().currentConversationId === id) setThinking(false);
      streamLock.current = false;
    }
  };

  const handleSend = async () => {
    if (!inputText.trim() || isThinking || approvalBlocked || historyPending || refreshing || streamLock.current || executionBlocked) return;
    const content = inputText.trim();
    setInputText('');
    await sendText(content);
  };

  // 保存最新 sendText 引用，供 pendingUserMessage useEffect 中使用时避免因依赖变化重复触发
  const sendTextRef = useRef(sendText);
  useEffect(() => {
    sendTextRef.current = sendText;
  });

  // 监听“我的差旅”页面写入的 pendingUserMessage，自动发送一次。
  // 依赖 currentConversationId 确保新会话创建后再发送；仅在 message + 会话同时就绪时触发。
  useEffect(() => {
    if (!pendingUserMessage || !currentConversationId) return;
    // 正在处理中或等待用户回答时不自动发送，避免打断
    if (isThinking || executionBlocked || interaction || historyPending || approvalBlocked || refreshing) return;
    const msg = consumePendingUserMessage();
    if (msg) {
      sendTextRef.current(msg);
    }
  }, [pendingUserMessage, currentConversationId, isThinking, executionBlocked, interaction, historyPending, approvalBlocked, refreshing, consumePendingUserMessage]);

  const handleKeyDown = (e: KeyboardEvent<HTMLTextAreaElement>) => {
    if (e.key === 'Enter' && !e.shiftKey) {
      e.preventDefault();
      handleSend();
    }
  };

  return (
    <div className="chat-window">
      {/* Header */}
      <div className="chat-header">
        <div className="chat-header-left">
          <svg viewBox="0 0 20 20" fill="none" width="20" height="20" className="chat-header-icon">
            <path
              d="M10 2C5.58 2 2 5.36 2 9.5c0 2.1.9 4 2.34 5.35L3.5 18l3.5-1.75c.93.31 1.93.5 2.99.5 4.42 0 8-3.36 8-7.5S14.42 2 10 2z"
              fill="#1677ff"
            />
          </svg>
          <span className="chat-header-title">{currentConv?.title || '新对话'}</span>
        </div>
        <div className="chat-header-right">
          {currentConv?.isRemote && <button type="button" className="approval-refresh" disabled={isThinking || refreshing}
            onClick={() => { setExecutionRetry(n => n + 1); void refreshHistory(currentConversationId).catch(() => {}); }}>{refreshing ? '刷新中…' : '刷新会话'}</button>}
          {(isThinking || executionBlocked) && (
            <div className="chat-header-status">
              <span className="status-dot" />
              {isStopping ? '正在停止…' : executionState === 'UNKNOWN' ? '正在核对执行状态…' : 'AI 处理中…'}
            </div>
          )}
          <select
            className="debug-agent-select"
            value={debugAgent}
            onChange={(e) => setDebugAgent(e.target.value)}
            disabled={isThinking || executionBlocked || !!interaction || approvalBlocked || refreshing}
            title="选择直连的子智能体（调试），默认由主智能体智能调度"
          >
            <option value="">🧠 默认（智能调度）</option>
            {debugAgents.map((a) => (
              <option key={a.name} value={a.name}>
                🔌 {a.label} （仅用于调试）
              </option>
            ))}
          </select>
        </div>
      </div>

      {/* Message list */}
      <div className="message-list">
        {currentConv?.runError && <div className="history-status" role="alert">本轮执行未完成：{currentConv.runError}</div>}
        {executionError && <div className="history-status" role="alert">
          执行状态核对失败：{executionError}
          <button type="button" onClick={() => setExecutionRetry(n => n + 1)}>重新核对</button>
        </div>}
        {(historyPending || historyError) && <div className="history-status" role={historyError ? 'alert' : 'status'}>
          {historyError ? `聊天记录加载失败：${historyError}` : '正在加载聊天记录…'}
          {historyError && <button type="button" disabled={isThinking || refreshing} onClick={() => {
            if (historyPending) setHistoryRetry(n => n + 1);
            else void refreshHistory(currentConversationId).catch(() => {});
          }}>刷新状态</button>}
        </div>}
        {messages.map((msg, idx) => {
          // 一个请求可以包含正文、媒体或多张审批卡，摘要只挂在本页第一次出现的位置。
          const summary = msg.runId && !messages.slice(0, idx).some(previous => previous.runId === msg.runId)
            ? <RequestSummary trace={msg.requestTrace} /> : null;
          if (msg.role === 'user' || msg.role === 'user_resume') return <Fragment key={msg.id}>
            <UserBubble msg={msg} username={username} />{summary}</Fragment>;
          if (msg.role === 'agent_hitl') {
            const batch = approvalBatches.find(item => item.messages.some(card => card.id === msg.id));
            if (!batch || batch.messages[0].id !== msg.id) return null;
            // 审批正文属于子 Agent，Master 思考是独立来源；整批卡片上方只渲染一份。
            const thinking = batch.messages.flatMap(card => card.timeline ?? []).find(item => item.kind === 'thinking');
            return <Fragment key={`${currentConversationId}:${batch.key}`}>{summary}
              {thinking?.kind === 'thinking' && <div className="approval-thinking">
                <ThinkingBlock agentName={thinking.agentName} text={thinking.text} />
              </div>}
              <ApprovalBatchCard userId={userId ?? ''}
              conversationId={currentConversationId} batch={batch} busy={isThinking || executionBlocked || refreshing}
              syncRequired={!!currentConv?.approvalSyncRequired} onSubmit={submitApproval}
              onRefresh={async () => { await refreshHistory(currentConversationId); }} /></Fragment>;
          }
          if (msg.role === 'agent') {
            const isLast = idx === messages.length - 1;
            const streaming = isLast && lastAgentIsStreaming;
            return (
              <Fragment key={msg.id}>{summary}<AgentMessageBlock
                msg={msg}
                liveTimeline={streaming ? timeline : undefined}
                isStreaming={streaming}
                onFeedback={handleFeedback}
              /></Fragment>
            );
          }
          return <Fragment key={msg.id}>{summary}<SystemBubble msg={msg} /></Fragment>;
        })}

        {/* 处理中但还没有 agent 消息时，先用占位消息展示时间轴 */}
        {isThinking && !lastAgentIsStreaming && timeline.length > 0 && (
          <AgentMessageBlock
            key="streaming-placeholder"
            msg={{
              id: 'streaming-placeholder',
              role: 'agent',
              content: '',
              agentName: currentConv?.activeAgent,
              timestamp: Date.now(),
            }}
            liveTimeline={timeline}
            isStreaming
          />
        )}

        {/* 兜底思考动画：处理中且没有任何时间轴内容 */}
        {isThinking && !lastAgentIsStreaming && timeline.length === 0 && <ThinkingDots />}

        {/* 推荐问题：MasterAgent 返回后由问题推荐智能体生成 */}
        {!isThinking && !executionBlocked && !approvalBlocked && suggestedQuestions.length > 0 && (
          <SuggestionChips questions={suggestedQuestions} onClick={sendText} />
        )}

        {interaction && (
          <div className="msg-row agent">
            <div className="msg-avatar agent-avatar">
              <svg viewBox="0 0 20 20" fill="none" width="18" height="18">
                <path
                  d="M10 2C5.58 2 2 5.36 2 9.5c0 2.1.9 4 2.34 5.35L3.5 18l3.5-1.75c.93.31 1.93.5 2.99.5 4.42 0 8-3.36 8-7.5S14.42 2 10 2z"
                  fill="white"
                  fillOpacity="0.9"
                />
              </svg>
            </div>
            <div className="msg-agent-content">
              <div className="msg-agent-name">Fons 差旅助手</div>
              <div className="msg-agent-bubble">
                <UserInteractionCard interaction={interaction} onSubmit={handleRespond} />
              </div>
            </div>
          </div>
        )}

        <div ref={bottomRef} />
      </div>

      {/* Input area */}
      <div className="input-area">
        <div className={`input-box${isThinking || executionBlocked || interaction || historyPending || approvalBlocked || refreshing ? ' disabled' : ''}`}>
          <textarea
            ref={textareaRef}
            className="input-textarea"
            placeholder={
              historyPending
                ? '聊天记录尚未加载完成'
                : isStopping
                ? '正在停止，等待当前任务结束…'
                : executionState === 'UNKNOWN'
                ? '正在核对执行状态，请稍候…'
                : executionBlocked
                ? '当前会话仍在执行，请等待或停止任务'
                : isThinking
                ? 'AI 正在处理，请稍候...'
                : approvalBlocked
                ? '请先处理审批卡片；状态不明时请刷新会话'
                : interaction
                ? '请先回答上方问题'
                : '输入您的差旅需求，按 Enter 发送，Shift+Enter 换行'
            }
            value={inputText}
            onChange={(e) => setInputText(e.target.value)}
            onKeyDown={handleKeyDown}
            disabled={isThinking || executionBlocked || !!interaction || historyPending || approvalBlocked || refreshing}
            rows={1}
          />
          <button
            className="send-btn"
            onClick={canInterrupt ? handleInterrupt : handleSend}
            disabled={isStopping || executionState === 'UNKNOWN' || historyPending || refreshing
              || (!canInterrupt && (!!interaction || executionBlocked || approvalBlocked || !inputText.trim()))}
            title={isStopping ? '正在停止' : canInterrupt ? '停止生成' : '发送 (Enter)'}
          >
            {canInterrupt || isStopping ? (
              <svg viewBox="0 0 20 20" fill="none" width="18" height="18">
                <rect x="5" y="5" width="10" height="10" rx="1.5" fill="currentColor" />
              </svg>
            ) : (
              <svg viewBox="0 0 20 20" fill="none" width="18" height="18">
                <path
                  d="M4 10L16 4l-4 6 4 6L4 10z"
                  fill="currentColor"
                />
              </svg>
            )}
          </button>
        </div>
        <div className="input-hint">Enter 发送 · Shift+Enter 换行 · 支持中英文</div>
      </div>
    </div>
  );
}
