import { useCallback, useEffect, useState } from 'react';
import { useAuthStore } from './store/authStore';
import { useUiStore } from './store/uiStore';
import { fetchUserInfo } from './api/auth';
import Sidebar from './components/Sidebar';
import ChatWindow from './components/ChatWindow';
import AdminApprovalPage from './components/AdminApprovalPage';
import MyTravelPage from './components/MyTravelPage';
import PreferencePage from './components/PreferencePage';
import LoginPage from './components/LoginPage';
import './App.css';

export default function App() {
  const { token, tokenName, clearAuth, setAuth } = useAuthStore();
  const isLoggedIn = useAuthStore((s) => s.isLoggedIn());
  const view = useUiStore((s) => s.view);

  /**
   * 页面刷新/首次加载时，若 localStorage 存有 token，则向后端验证登录态。
   * - 验证通过：保持已登录状态，正常展示主界面。
   * - 明确过期：清除本地 token，跳转登录页；连接失败保留登录态供重试。
   * checking 状态技巧：仅当 token 存在时才请求后端，不存在时直接展示登录页。
   */
  const [checking, setChecking] = useState(!!token && !!tokenName);
  const [connectionError, setConnectionError] = useState('');

  const verifySession = useCallback(async () => {
    if (!token || !tokenName) return;
    try {
      const info = await fetchUserInfo(token, tokenName);
      if (useAuthStore.getState().token !== token) return;
      setAuth(token, tokenName, info.userId, info.username, info.admin);
      setConnectionError('');
    } catch (failure) {
      if (useAuthStore.getState().token !== token) return;
      // 后端暂未启动/连接失败不等于令牌失效，保留登录态和审批草稿供服务就绪后重试。
      if (failure instanceof Error && failure.message === 'UNAUTHORIZED') clearAuth();
      else setConnectionError('暂时无法连接服务，请在服务就绪后重试。');
    } finally { setChecking(false); }
  }, [token, tokenName, setAuth, clearAuth]);

  useEffect(() => { void Promise.resolve().then(verifySession); }, [verifySession]);

  // token 正在验证中：返回空白屏避免闪现错误页面。
  if (checking) return null;

  if (!isLoggedIn) {
    return <LoginPage />;
  }

  if (connectionError) return <div className="service-connection-status" role="alert">
    <p>{connectionError}</p>
    <button type="button" onClick={() => { setChecking(true); void verifySession(); }}>重新连接</button>
  </div>;

  return (
    <div className="app-root">
      <Sidebar />
      {view === 'admin' ? (
        <AdminApprovalPage />
      ) : view === 'travel' ? (
        <MyTravelPage />
      ) : view === 'preference' ? (
        <PreferencePage />
      ) : (
        <ChatWindow />
      )}
    </div>
  );
}
