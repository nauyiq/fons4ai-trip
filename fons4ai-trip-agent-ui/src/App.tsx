import { useEffect, useState } from 'react';
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
   * 页面刺新/首次加载时，若 localStorage 存有 token，则立即对后端进行驗证。
   * - 驗证通过（200）：保持已登录状态，正常展示主界面。
   * - 驗证失败（401 / 其他错误）：清除本地 token，跳转登录页。
   * checking 状态技巧：仅当 token 存在时才请求后端，不存在时直接展示登录页。
   */
  const [checking, setChecking] = useState(!!token && !!tokenName);

  useEffect(() => {
    if (!token || !tokenName) {
      // 本地无 token，无需骗证接口，直接进入登录页
      return;
    }
    // 调用 GET /api/auth/info 驗证 token 是否仍有效
    fetchUserInfo(token, tokenName)
      .then((info) => {
        // 以服务端用户信息刷新本地身份与管理员标识
        setAuth(token, tokenName, info.userId, info.username, info.admin);
      })
      .catch(() => {
        // Token 已失效（401）或网络异常：清除本地登录态，跳入登录页
        clearAuth();
      })
      .finally(() => setChecking(false));
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []); // 仅在组件首次挂载（页面初始化/刷新）时运行一次

  // token 正在驗证中：返回空白屏避免闪现错误页面
  if (checking) return null;

  if (!isLoggedIn) {
    return <LoginPage />;
  }

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
