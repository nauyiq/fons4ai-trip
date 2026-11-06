import { useCallback, useEffect, useState } from 'react';
import {
  fetchUserPreferences, saveUserPreferences, PreferenceApiError,
  type PreferenceSnapshot, type PreferenceValue, type PreferenceSaveRequest,
} from '../api/preference';
import { useAuthStore } from '../store/authStore';

const values = (snapshot: PreferenceSnapshot) =>
  Object.fromEntries(snapshot.items.map(item => [item.key, item.value]));
const equal = (a: PreferenceValue | undefined, b: PreferenceValue | undefined) =>
  JSON.stringify(Array.isArray(a) ? [...a].sort() : a ?? null) ===
  JSON.stringify(Array.isArray(b) ? [...b].sort() : b ?? null);

export default function PreferencePage() {
  const { clearAuth } = useAuthStore();
  const [baseline, setBaseline] = useState<PreferenceSnapshot | null>(null);
  const [draft, setDraft] = useState<Record<string, PreferenceValue>>({});
  const [dirty, setDirty] = useState<Set<string>>(new Set());
  const [latest, setLatest] = useState<PreferenceSnapshot | null>(null);
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [saved, setSaved] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [reloadKey, setReloadKey] = useState(0);
  const categories = baseline?.categories ?? [];

  const applySnapshot = useCallback((snapshot: PreferenceSnapshot) => {
    setBaseline(snapshot);
    setDraft(values(snapshot));
    setDirty(new Set());
    setLatest(null);
  }, []);
  useEffect(() => {
    let active = true;
    fetchUserPreferences()
      .then(snapshot => {
        if (!active) {
          return;
        }
        setError(null);
        applySnapshot(snapshot);
      })
      .catch((err: unknown) => {
        if (!active) {
          return;
        }
        if (err instanceof Error && err.message === 'UNAUTHORIZED') {
          clearAuth();
        } else {
          setError(err instanceof Error ? err.message : '加载偏好设置失败');
        }
      })
      .finally(() => {
        if (active) {
          setLoading(false);
        }
      });
    return () => {
      active = false;
    };
  }, [applySnapshot, clearAuth, reloadKey]);

  const changeValue = (key: string, next: PreferenceValue) => {
    setDraft(previous => ({ ...previous, [key]: next }));
    setDirty(previous => {
      const changed = new Set(previous);
      const original = baseline?.items.find(item => item.key === key)?.value;
      if (equal(next, original)) {
        changed.delete(key);
      } else {
        changed.add(key);
      }
      return changed;
    });
    setSaved(false);
  };
  const handleSave = async (confirmLatest = false) => {
    if (!baseline || dirty.size === 0) {
      return;
    }
    setSaving(true);
    setError(null);
    const version = confirmLatest && latest ? latest.version : baseline.version;
    const submission: PreferenceSaveRequest = {
      expectedVersion: version,
      changes: [...dirty].map(key => {
        const value = draft[key];
        return value == null || value === '' || Array.isArray(value) && value.length === 0
          ? { key, operation: 'CLEAR' as const }
          : { key, operation: 'SET' as const, value };
      }),
    };
    try {
      const result = await saveUserPreferences(submission);
      applySnapshot(result);
      setSaved(true);
    } catch (err) {
      if (err instanceof Error && err.message === 'UNAUTHORIZED') {
        clearAuth();
        return;
      }
      setError(err instanceof Error ? err.message : '保存失败，请重试');
      if (err instanceof PreferenceApiError && err.code === 'TA400005') {
        try {
          setLatest(await fetchUserPreferences());
        } catch {
          setError('偏好已更新，但暂时无法读取最新值。草稿已保留，请稍后重试。');
        }
      }
    } finally {
      setSaving(false);
    }
  };

  if (loading) {
    return <div className="admin-page pref-page"><div className="admin-empty">加载中...</div></div>;
  }
  return (
    <div className="admin-page pref-page">
      <div className="admin-header">
        <div>
          <h1 className="admin-title">差旅偏好设置</h1>
          <p className="admin-subtitle">设置长期差旅偏好，智能助手会在差旅政策允许范围内参考使用</p>
        </div>
        <button className="pref-save-btn" onClick={() => void handleSave()}
          disabled={saving || !baseline || dirty.size === 0 || latest != null}>
          {saving ? '保存中...' : saved ? '✓ 已保存' : '保存偏好'}
        </button>
      </div>
      {error && <div className="admin-error">{error}</div>}
      {!baseline && <button className="pref-save-btn" onClick={() => { setLoading(true); setReloadKey(key => key + 1); }}>重新加载</button>}
      {latest && (
        <div className="pref-memory-hint">
          <div className="pref-memory-hint-body">
            <div className="pref-memory-hint-title">偏好已发生变化，本次草稿已保留</div>
            {[...dirty].map(key => {
              const definition = categories.flatMap(category => category.items).find(item => item.key === key);
              const current = latest.items.find(item => item.key === key);
              const currentValues = Array.isArray(current?.value) ? current.value : current?.value ? [current.value] : [];
              const text = currentValues.map(value => definition?.optionLabels[value] ?? value).join('、');
              return <div key={key}>{definition?.label}：当前保存为{text || (current?.status === 'CLEARED' ? '已清除' : '未设置')}</div>;
            })}
            <button className="pref-save-btn" disabled={saving} onClick={() => void handleSave(true)}>
              确认本次修改并重新提交
            </button>
          </div>
        </div>
      )}
      <div className="pref-grid">
        {categories.map(category => (
          <div key={category.category} className="pref-category">
            <div className="pref-category-header">
              <span className="pref-category-icon">{category.icon}</span>
              <span className="pref-category-label">{category.label}</span>
            </div>
            <div className="pref-items">
              {category.items.map(item => {
                const stored = baseline?.items.find(entry => entry.key === item.key);
                return (
                  <div key={item.key} className="pref-item">
                    <div className="pref-item-label">{item.label}{dirty.has(item.key) ? ' · 未保存' : ''}</div>
                    <div className="pref-item-options">
                      {item.options.map(code => {
                        const current = draft[item.key];
                        const selected = Array.isArray(current) ? current.includes(code) : current === code;
                        return (
                          <button key={code} disabled={saving || !baseline}
                            className={'pref-option' + (selected ? ' selected' : '') + (item.type === 'multi' ? ' multi' : '')}
                            onClick={() => {
                              if (item.type === 'single') {
                                changeValue(item.key, selected ? null : code);
                              } else {
                                const entries = Array.isArray(current) ? current : [];
                                changeValue(item.key, selected ? entries.filter(entry => entry !== code) : [...entries, code]);
                              }
                            }}>
                            {item.type === 'multi' && <span className="pref-option-check">{selected ? '✓' : ''}</span>}
                            {item.optionLabels[code] ?? code}
                          </button>
                        );
                      })}
                    </div>
                    <div className="pref-memory-hint-tip" style={{ marginTop: 8 }}>
                      {stored?.status === 'ACTIVE' ? '已设置' : stored?.status === 'CLEARED' ? '已清除' : '未设置'}
                      {stored?.updatedAt && ' · ' + new Date(stored.updatedAt).toLocaleString('zh-CN', { timeZone: 'Asia/Shanghai' })}
                    </div>
                  </div>
                );
              })}
            </div>
          </div>
        ))}
      </div>
    </div>
  );
}
