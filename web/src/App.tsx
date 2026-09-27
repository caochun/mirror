import { useEffect, useState, createContext, useContext } from 'react';
import type { FormEvent, ReactNode } from 'react';
import { Navigate, NavLink, Route, Routes, Link } from 'react-router-dom';
import { api, ApiError } from './api';
import type { Actor } from './api';
import { ErrorBox } from './ui';
import { People, PersonDetail } from './People';
import { TagDirectory } from './Tags';
import { Dashboard } from './CoreDashboard';
import { ReminderList, ReminderTaskDetail } from './Reminders';
import { ReminderEditor } from './ReminderEditor';
import { ContentLibrary } from './ContentLibrary';

const Session = createContext<{ actor: Actor; logout: () => void } | null>(null);
const roleNames: Record<string, string> = { SUPER_ADMIN: '超级管理员', UNIT_ADMIN: '单位管理员', AREA_ADMIN: '片区管理员', REVIEWER: '推送审核员' };
export function useSession() { return useContext(Session)!; }

export function App() {
  const [actor, setActor] = useState<Actor | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  function restore() {
    setLoading(true); setError('');
    api<Actor>('/auth/me').then(setActor).catch(e => {
      if (!(e instanceof ApiError && e.status === 401)) setError(e.message);
    }).finally(() => setLoading(false));
  }
  useEffect(restore, []);
  if (loading) return <div className="p-12 text-slate-500" role="status">正在加载工作空间…</div>;
  if (error) return <div className="mx-auto mt-24 max-w-lg"><ErrorBox message={error} retry={restore} /></div>;
  if (!actor) return <Login onLogin={setActor} />;
  const logout = () => { api<void>('/auth/logout', { method: 'POST' }).then(() => setActor(null)).catch(e => setError(e.message)); };
  return <Session.Provider value={{ actor, logout }}><Shell><Routes>
    <Route path="/workbench" element={<Workbench />} />
    <Route path="/people" element={<People />} />
    <Route path="/people/:id" element={<PersonDetail />} />
    <Route path="/tags" element={<TagDirectory />} />
    <Route path="/content-examples" element={<ContentLibrary />} />
    <Route path="/reminders" element={<ReminderList />} />
    <Route path="/reminders/new" element={<ReminderEditor />} />
    <Route path="/reminders/:id/edit" element={<ReminderEditor />} />
    <Route path="/reminders/:id" element={<ReminderTaskDetail />} />
    <Route path="*" element={<Navigate to="/workbench" replace />} />
  </Routes></Shell></Session.Provider>;
}

function Login({ onLogin }: { onLogin: (actor: Actor) => void }) {
  const [error, setError] = useState(''); const [busy, setBusy] = useState(false);
  async function submit(e: FormEvent<HTMLFormElement>) {
    e.preventDefault(); const data = new FormData(e.currentTarget); setBusy(true); setError('');
    try { onLogin(await api<Actor>('/auth/login', { method: 'POST', body: JSON.stringify({ username: data.get('username'), password: data.get('password') }) })); }
    catch (e) { setError((e as Error).message); } finally { setBusy(false); }
  }
  return <main className="grid min-h-screen lg:grid-cols-2">
    <section className="flex flex-col justify-between bg-slate-900 px-10 py-12 text-white lg:px-20">
      <div className="flex items-center gap-3"><span className="grid h-10 w-10 place-items-center rounded-xl bg-blue-600 text-xl">镜</span><span className="text-lg font-semibold">明镜 MIRROR</span></div>
      <div className="py-20"><p className="text-sm tracking-widest text-blue-300">政务系统对象库</p><h1 className="mt-6 text-4xl font-semibold leading-snug">动态建档<br />让每一次提醒有据可循</h1><p className="mt-6 max-w-md text-sm leading-7 text-slate-300">以人员和组织为基础，连接标签画像、精准提醒与阅读反馈，保留业务变化的完整轨迹。</p></div>
      <p className="text-xs text-slate-400">人员 · 组织 · 标签 · 提醒</p>
    </section>
    <section className="flex items-center justify-center px-8 py-16"><form onSubmit={submit} className="w-full max-w-sm">
      <p className="eyebrow">工作空间</p><h2 className="mt-3 text-2xl font-semibold">登录明镜</h2><p className="muted mt-3">使用已授权的管理账号进入。</p>
      <label className="mt-8 block text-sm">账号<input name="username" autoComplete="username" required maxLength={100} className="mt-2 w-full" /></label>
      <label className="mt-5 block text-sm">密码<input name="password" type="password" autoComplete="current-password" required maxLength={128} className="mt-2 w-full" /></label>
      {error && <p role="alert" className="mt-4 text-sm text-red-700">{error}</p>}
      <button disabled={busy} className="primary mt-7 w-full">{busy ? '正在登录…' : '进入工作空间'}</button>
      <p className="mt-6 text-xs leading-6 text-slate-400">账号与数据范围由管理员授权。请妥善保管账号，不与他人共用。</p>
    </form></section>
  </main>;
}

function Shell({ children }: { children: ReactNode }) {
  const { actor, logout } = useSession();
  return <div className="flex min-h-screen">
    <aside className="hidden w-56 shrink-0 border-r border-slate-200 bg-white md:block">
      <div className="flex h-20 items-center gap-3 px-6"><span className="grid h-9 w-9 place-items-center rounded-xl bg-blue-700 text-lg text-white">镜</span><div className="font-semibold">明镜<span className="ml-2 text-xs tracking-wider text-slate-400">MIRROR</span></div></div>
      <p className="eyebrow px-6 py-6">业务工作空间</p><nav className="space-y-2 px-3"><Menu to="/workbench">工作台</Menu><Menu to="/reminders">{actor.role === 'REVIEWER' ? '审核工作台' : '提醒任务'}</Menu>{actor.role !== 'REVIEWER' && <><Menu to="/people">人员与组织</Menu><Menu to="/tags">标签目录</Menu><Menu to="/content-examples">内容示例库</Menu></>}</nav>
      <div className="mx-5 mt-14 border-t border-slate-100 pt-5 text-xs leading-6 text-slate-400">对象信息 · 业务留痕<br />按当前授权范围展示</div>
    </aside>
    <div className="min-w-0 flex-1">
      <header className="flex min-h-20 flex-wrap items-center justify-between gap-4 border-b border-slate-200 bg-white px-5 lg:px-9">
        <div><p className="text-sm font-semibold">政务系统对象库</p><p className="mt-1 text-xs text-slate-500">{actor.role === 'SUPER_ADMIN' ? '全市数据范围' : actor.role === 'UNIT_ADMIN' ? '本单位及全部下级单位' : actor.role === 'AREA_ADMIN' ? '授权组织及下级单位' : '本单位审核职责'}</p></div>
        <div className="flex items-center gap-4"><div className="text-right"><p className="text-sm">{actor.displayName}</p><p className="text-xs text-slate-400">{roleNames[actor.role]}</p></div><button className="secondary" onClick={logout}>退出</button></div>
      </header>
      <nav className="flex gap-4 px-5 py-3 text-sm md:hidden"><Link to="/workbench">工作台</Link><Link to="/reminders">提醒任务</Link>{actor.role !== 'REVIEWER' && <><Link to="/people">人员与组织</Link><Link to="/tags">标签目录</Link></>}</nav>
      <main className="mx-auto max-w-[1600px] p-5 lg:p-9">{children}</main>
    </div>
  </div>;
}
function Menu({ to, children }: { to: string; children: ReactNode }) {
  return <NavLink to={to} className={({ isActive }) => `block rounded-lg px-4 py-3 text-sm ${isActive ? 'bg-blue-50 font-semibold text-blue-700' : 'text-slate-600 hover:bg-slate-50'}`}>{children}</NavLink>;
}
function Workbench() {
  return <Dashboard />;
}
