import { create } from 'zustand';
import { persist } from 'zustand/middleware';
import { useChatStore } from './chatStore';

interface AuthState {
  token: string | null;
  tokenName: string | null;
  userId: string | null;
  username: string | null;
  isAdmin: boolean;

  setAuth: (token: string, tokenName: string, userId: string, username: string, isAdmin?: boolean) => void;
  clearAuth: () => void;
  isLoggedIn: () => boolean;
}

export const useAuthStore = create<AuthState>()(
  persist(
    (set, get) => ({
      token: null,
      tokenName: null,
      userId: null,
      username: null,
      isAdmin: false,

      setAuth: (token, tokenName, userId, username, isAdmin = false) => {
        if (get().userId !== userId) useChatStore.getState().resetConversations();
        set({ token, tokenName, userId, username, isAdmin });
      },

      clearAuth: () => {
        useChatStore.getState().resetConversations();
        set({ token: null, tokenName: null, userId: null, username: null, isAdmin: false });
      },

      isLoggedIn: () => !!get().token && !!get().tokenName,
    }),
    {
      name: 'trip-auth', // 与旧版前端登录态隔离
    },
  ),
);
