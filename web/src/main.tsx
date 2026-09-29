import React, { useEffect, useRef, useState } from 'react';
import { createRoot } from 'react-dom/client';
import './style.css';
import ObjectsPanel from './ObjectsPanel';

type Session = { authenticated: boolean; csrf: string; username?: string; roles?: string[]; demo: boolean };
type RecordValue = { id: string; version: number; properties: Record<string, any> };
type PersonRow = { id: string; version: number; name: string; organization: string; status: string; effectiveTags: number };
type Tag = { id: string; version: number; tagVersion: string; name: string };
type PersonTag = Tag & { tag: string; suppression: string; effective: boolean; contributions: RecordValue[] };
type Detail = PersonRow & { person: RecordValue; membership: RecordValue; tags: PersonTag[]; history: any[] };
type Catalog = { organizations: RecordValue[]; tags: Tag[] };
const statuses: Record<string, string> = { PENDING: '待确认', IN_SCOPE: '正常管理', EXCLUDED: '非管理对象', SUSPENDED: '暂停管理' };
const migrations: Record<string, string[]> = { PENDING: ['IN_SCOPE', 'EXCLUDED'], IN_SCOPE: ['SUSPENDED', 'EXCLUDED'], SUSPENDED: ['IN_SCOPE', 'EXCLUDED'], EXCLUDED: ['IN_SCOPE'] };
const historyNames: Record<string, string> = { Person: '人员档案', ObjectMembership: '管理状态决定', PersonTag: '标签关联', TagContribution: '标签依据', PersonCurrentOrganization: '当前单位关系', MembershipPerson: '人员管理状态关系', MembershipDecisionOrganization: '状态调整单位', PersonTagPerson: '标签人员关系', PersonTagTag: '标签目录关系', ContributionForPersonTag: '依据与标签关系', ContributionTagVersion: '依据语义版本', ContributionOrganization: '依据来源组织' };
const operations: Record<string, string> = { CREATED: '建立', UPDATED: '变更', DELETED: '结束', RESTORED: '恢复' };
let csrf = '';
async function api<T>(url: string, init: RequestInit = {}): Promise<T> {
  const response = await fetch(url, { ...init, headers: { 'X-CSRF-TOKEN': csrf, ...init.headers } });
  const raw = await response.text();
  let data: any;
  try { data = raw ? JSON.parse(raw) : undefined; } catch { data = undefined; }
  if (!response.ok) throw new Error(data?.error || (response.status === 401 ? '请重新登录' : response.status === 403 ? '当前账号无此权限或登录已失效' : `请求失败（${response.status}）`));
  return data;
}

function App() {
  const [session, setSession] = useState<Session>();
  const [error, setError] = useState('');
  async function loadSession() {
    const current = await api<Session>('/api/session');
    csrf = current.csrf;
    setSession(current);
  }
  useEffect(() => { loadSession().catch(e => setError(e.message)); }, []);
  if (!session) return <p className="p-8" role="status">{error || '正在连接 Mirror…'}</p>;
  if (!session.authenticated) return <Login loggedIn={loadSession} />;
  return <Personnel session={session} logout={async () => { await api('/api/logout', { method: 'POST' }); await loadSession(); }} />;
}

function Login({ loggedIn }: { loggedIn: () => Promise<void> }) {
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);
  async function submit(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const form = new FormData(event.currentTarget);
    setBusy(true); setError('');
    try {
      await api('/api/login', { method: 'POST', body: new URLSearchParams({ username: String(form.get('username')), password: String(form.get('password')) }) });
      await loggedIn();
    } catch { setError('登录失败，请检查账号和密码'); } finally { setBusy(false); }
  }
  return <main className="min-h-screen grid place-items-center p-6"><form className="panel p-8 w-full max-w-sm space-y-5" onSubmit={submit}>
    <div><h1 className="text-2xl font-semibold">Mirror</h1><p className="text-sm text-slate-500 mt-2">政务系统对象库 · 登录业务工作台</p></div>
    <label className="block text-sm">账号<input className="input mt-2" name="username" autoComplete="username" required /></label>
    <label className="block text-sm">密码<input className="input mt-2" name="password" type="password" autoComplete="current-password" required /></label>
    {error && <p role="alert" className="text-red-700 text-sm">{error}</p>}<button disabled={busy} className="btn primary w-full">{busy ? '登录中…' : '登录'}</button>
  </form></main>;
}

function Personnel({ session, logout }: { session: Session; logout: () => Promise<void> }) {
  const [catalog, setCatalog] = useState<Catalog>({ organizations: [], tags: [] });
  const [list, setList] = useState<{ items: PersonRow[]; total: number }>({ items: [], total: 0 });
  const [search, setSearch] = useState('');
  const [status, setStatus] = useState('');
  const [organization, setOrganization] = useState('');
  const [offset, setOffset] = useState(0);
  const [selected, setSelected] = useState('');
  const [detail, setDetail] = useState<Detail>();
  const [refresh, setRefresh] = useState(0);
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');
  const [dialog, setDialog] = useState<'register' | 'membership' | 'assign' | undefined>();
  const [diagnostic, setDiagnostic] = useState<unknown>();
  const [loading, setLoading] = useState(false);
  const [objectsOpen, setObjectsOpen] = useState(false);
  const [initialObject, setInitialObject] = useState<{ type: string; id: string }>();
  const roles = session.roles || [];
  const admin = roles.includes('ADMIN');
  useEffect(() => {
    let active = true;
    setLoading(true); setError('');
    Promise.all([api<Catalog>('/api/mirror/catalog'), api<typeof list>(`/api/mirror/people?${new URLSearchParams({ search, status, organization, offset: String(offset) })}`)])
      .then(([catalog, rows]) => { if (active) { setCatalog(catalog); setList(rows); } })
      .catch(e => { if (active) setError(e.message); }).finally(() => { if (active) setLoading(false); });
    return () => { active = false; };
  }, [search, status, organization, offset, refresh]);
  useEffect(() => {
    let active = true;
    setDetail(undefined);
    if (selected) api<Detail>(`/api/mirror/people/${encodeURIComponent(selected)}`).then(value => { if (active) setDetail(value); }).catch(e => { if (active) setError(e.message); });
    return () => { active = false; };
  }, [selected, refresh]);
  const [suppress, setSuppress] = useState<PersonTag>();
  function completed(result: any) {
    setDialog(undefined); setSuppress(undefined);
    setNotice('操作已提交，事实与变更记录已保存。');
    const created = result.affected?.find((item: any) => item.type === 'Person');
    if (created) setSelected(created.id);
    setRefresh(value => value + 1);
  }
  if (objectsOpen) return <ObjectsPanel initial={initialObject} close={() => setObjectsOpen(false)} />;
  return <div className="min-h-screen">
    <header className="bg-[#14233e] text-white px-5 md:px-8 py-5 flex flex-wrap justify-between gap-4 items-center">
      <div><h1 className="text-xl font-semibold">Mirror · 人员工作台</h1><p className="text-sm text-slate-300 mt-1">人员档案 · 管理状态 · 标签依据 · 变更记录</p></div>
      <div className="flex gap-3 items-center text-sm"><span>{session.username}</span><button className="underline" onClick={() => logout().catch(e => setError(e.message))}>退出登录</button></div>
    </header>
    {session.demo && <p className="bg-amber-50 text-amber-800 px-6 py-2 text-sm">本地验收环境：使用合成数据，数据持久保存，重启不会重置。</p>}
    <main className="max-w-[1600px] mx-auto p-4 md:p-8 space-y-5">
      <div className="flex flex-wrap justify-between gap-3 items-center"><div><h2 className="text-xl font-semibold">人员档案</h2><p className="text-sm text-slate-500 mt-1">已建档不等于正常管理对象库，同名人员需核实身份。</p></div>
        <div className="flex flex-wrap gap-2">{admin && <><button className="btn" onClick={() => { setInitialObject(undefined); setObjectsOpen(true); }}>对象浏览</button><button className="btn" onClick={() => api('/api/model').then(setDiagnostic).catch(e => setError(e.message))}>模型定义</button><button className="btn" onClick={() => api('/api/events').then(setDiagnostic).catch(e => setError(e.message))}>审计与待投递事件</button></>}
          {(admin || roles.includes('DIRECTORY')) && <button className="btn primary" onClick={() => { setNotice(''); setDialog('register'); }}>人工建档</button>}</div></div>
      {error && <p role="alert" className="text-red-700 bg-red-50 p-3 rounded-lg">{error}</p>}
      {notice && <p role="status" className="text-emerald-800 bg-emerald-50 p-3 rounded-lg">{notice}</p>}
      <div className="grid xl:grid-cols-[minmax(0,1fr)_minmax(0,1fr)] gap-5 items-start">
        <section className="panel p-4 min-w-0">
          <div className="grid sm:grid-cols-3 gap-2 mb-4"><input aria-label="搜索姓名" placeholder="搜索姓名" className="input" value={search} onChange={e => { setSearch(e.target.value); setOffset(0); }} />
            <select aria-label="管理状态筛选" className="input" value={status} onChange={e => { setStatus(e.target.value); setOffset(0); }}><option value="">全部管理状态</option>{Object.entries(statuses).map(([value, text]) => <option key={value} value={value}>{text}</option>)}</select>
            <select aria-label="单位筛选" className="input" value={organization} onChange={e => { setOrganization(e.target.value); setOffset(0); }}><option value="">全部可查看单位</option>{catalog.organizations.map(o => <option key={o.id} value={o.id}>{o.properties.name}</option>)}</select></div>
          {loading ? <p className="p-5 text-slate-500">正在加载…</p> : <div className="space-y-2">{list.items.map(person => <button key={person.id} onClick={() => setSelected(person.id)} className={`w-full text-left border rounded-lg p-4 flex justify-between gap-3 ${selected === person.id ? 'border-indigo-400 bg-indigo-50' : 'border-slate-200 hover:bg-slate-50'}`}><div><span className="font-medium">{person.name}</span><p className="text-sm text-slate-500 mt-1">{person.organization || '归属待核实'}</p></div><div className="text-right"><span className="badge">{statuses[person.status]}</span><p className="text-xs text-slate-500 mt-2">{person.effectiveTags} 个有效标签</p></div></button>)}
            {!list.items.length && <p className="text-slate-500 p-6 text-center">当前筛选下没有匹配人员。</p>}</div>}
          <div className="flex justify-between items-center mt-4 text-sm text-slate-500"><span>可查看人员共 {list.total} 人</span><div className="flex gap-2"><button className="btn" disabled={offset === 0} onClick={() => setOffset(offset - 20)}>上一页</button><button className="btn" disabled={offset + 20 >= list.total} onClick={() => setOffset(offset + 20)}>下一页</button></div></div>
        </section>
        <section className="panel p-5 min-w-0">{!detail ? <p className="text-slate-500 py-12 text-center">{selected ? '正在加载人员详情…' : '选择人员，查看管理状态与标签依据'}</p> : <>
          <div className="flex flex-wrap justify-between gap-2"><h2 className="text-lg font-semibold">{detail.name}</h2><div className="flex gap-2">{admin && <button className="btn primary" onClick={() => { setInitialObject({ type: 'Person', id: detail.id }); setObjectsOpen(true); }}>对象关系</button>}<button className="btn" onClick={() => setRefresh(v => v + 1)}>刷新详情</button></div></div>
          <p className="text-sm text-slate-500 mt-2">{detail.organization} · {statuses[detail.status]}</p>
          <dl className="grid grid-cols-2 text-sm gap-2 mt-4"><div>性别：{detail.person.properties.sex || '未填写'}</div><div>出生日期：{detail.person.properties.birthDate || '未填写'}</div><div>入职日期：{detail.person.properties.entryDate || '未填写'}</div><div>个人职级：{detail.person.properties.personalRankCode || '未填写'}</div></dl>
          <div className="border-t border-slate-200 mt-5 pt-4"><h3 className="font-medium">对象库管理状态</h3><p className="text-sm text-slate-500 my-2">{detail.membership?.properties.note || '尚无说明'} · {detail.membership?.properties.decidedBy}</p>
            {admin && detail.membership && <button className="btn" onClick={() => setDialog('membership')}>调整管理状态</button>}</div>
          <div className="border-t border-slate-200 mt-5 pt-4"><div className="flex justify-between items-center"><h3 className="font-medium">标签与依据</h3>{(admin || roles.includes('TAG_EDITOR')) && <button className="btn" disabled={detail.status !== 'IN_SCOPE'} onClick={() => setDialog('assign')}>人工赋标</button>}</div>
            {detail.status !== 'IN_SCOPE' && <p className="text-xs text-amber-700 mt-2">尚未确认、暂停管理或不属于管理对象的人员不参与有效标签选人。</p>}
            {!detail.tags.length && <p className="text-sm text-slate-500 mt-3">暂无标签依据</p>}
            {detail.tags.map(tag => <div key={tag.id} className="border border-slate-200 rounded-lg p-3 mt-3"><div className="flex justify-between gap-2"><span>{tag.name}</span><span className="badge">{tag.effective ? '当前有效' : tag.suppression === 'SUPPRESSED' ? '已人工取消' : '当前不生效'}</span></div>
              {tag.contributions.map(c => <p key={c.id} className="text-xs text-slate-500 mt-2">{c.properties.kind === 'MANUAL' ? '人工依据' : c.properties.kind} · {c.properties.reason} · {c.properties.actorId}</p>)}
              {(admin || roles.includes('TAG_EDITOR')) && tag.suppression === 'NONE' && <button className="btn mt-3" onClick={() => setSuppress(tag)}>取消标签</button>}</div>)}
          </div>
          <details className="border-t border-slate-200 mt-5 pt-4"><summary className="cursor-pointer font-medium">变更记录 · {detail.history.length} 条</summary><div className="space-y-3 mt-3 max-h-96 overflow-y-auto">{[...detail.history].sort((a, b) => b.time.localeCompare(a.time)).map((h, index) => <details key={index} className="border-l-2 border-indigo-200 pl-3"><summary className="text-xs cursor-pointer">{historyNames[h.type] || h.type} · {operations[h.operation] || h.operation} · 第 {h.version} 次记录<span className="block text-slate-500 mt-1">{h.actor} · {new Date(h.time).toLocaleString('zh-CN')}</span></summary><pre className="bg-slate-50 p-2 mt-2">{JSON.stringify(h.state, null, 2)}</pre></details>)}</div></details>
        </>}</section>
      </div>
    </main>
    {dialog && <CommandForm key={dialog} mode={dialog} detail={detail} catalog={catalog} close={() => setDialog(undefined)} completed={completed} />}
    {suppress && <CommandForm mode="suppress" detail={detail} tag={suppress} catalog={catalog} close={() => setSuppress(undefined)} completed={completed} />}
    {diagnostic !== undefined && <Modal title="管理诊断（只读）" close={() => setDiagnostic(undefined)}><p className="text-sm text-slate-500 mb-3">记录代表本地提交；当前未启动外部事件投递器。最多显示最近 200 条。</p><pre>{JSON.stringify(diagnostic, null, 2)}</pre></Modal>}
  </div>;
}

function Modal({ title, close, children }: { title: string; close: () => void; children: React.ReactNode }) {
  const ref = useRef<HTMLDialogElement>(null);
  useEffect(() => { ref.current?.showModal(); }, []);
  return <dialog ref={ref} onCancel={e => { e.preventDefault(); close(); }} className="fixed inset-0 m-auto w-[calc(100%-2rem)] max-w-xl max-h-[85vh] overflow-y-auto rounded-xl p-6 shadow-xl backdrop:bg-slate-900/40"><div className="flex justify-between gap-3 mb-5"><h2 className="font-semibold text-lg">{title}</h2><button type="button" className="btn" aria-label="关闭弹窗" onClick={close}>关闭</button></div>{children}</dialog>;
}

function CommandForm({ mode, detail, tag, catalog, close, completed }: { mode: 'register' | 'membership' | 'assign' | 'suppress'; detail?: Detail; tag?: PersonTag; catalog: Catalog; close: () => void; completed: (result: any) => void }) {
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const attempt = useRef<{ fingerprint: string; key: string } | undefined>(undefined);
  const [tagId, setTagId] = useState(catalog.tags[0]?.id || '');
  const existing = detail?.tags.find(t => t.tag === tagId);
  async function submit(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const form = new FormData(event.currentTarget);
    let command: string;
    let input: Record<string, unknown> = { note: String(form.get('note')) };
    if (mode === 'register') {
      command = 'RegisterManualPerson';
      ['name', 'organization', 'sex', 'birthDate', 'entryDate', 'personalRankCode'].forEach(field => { const value = String(form.get(field) || '').trim(); if (value) input[field] = value; });
    } else if (mode === 'membership') {
      command = 'DecideObjectMembership';
      input = { ...input, membership: detail!.membership.id, expectedVersion: detail!.membership.version, status: form.get('status'), decisionCode: form.get('decisionCode') };
    } else if (mode === 'suppress') {
      command = 'SuppressPersonTag';
      input = { ...input, personTag: tag!.id, expectedVersion: tag!.version };
    } else {
      const selected = catalog.tags.find(t => t.id === tagId)!;
      command = existing ? 'ApplyManualTagContribution' : 'AssignManualTag';
      input = existing ? { ...input, personTag: existing.id, expectedVersion: existing.version, tagVersion: selected.tagVersion }
        : { ...input, person: detail!.id, expectedPersonVersion: detail!.version, membership: detail!.membership.id, expectedMembershipVersion: detail!.membership.version, tag: selected.id, expectedTagVersion: selected.version, tagVersion: selected.tagVersion };
    }
    const fingerprint = JSON.stringify({ command, input });
    if (attempt.current?.fingerprint !== fingerprint) attempt.current = { fingerprint, key: crypto.randomUUID() };
    setBusy(true); setError('');
    try {
      completed(await api(`/api/mirror/commands/${command}`, { method: 'POST', headers: { 'Content-Type': 'application/json', 'Idempotency-Key': attempt.current.key }, body: JSON.stringify(input) }));
    } catch (e) { setError((e as Error).message + '。版本冲突时请关闭表单、刷新详情后重新确认；网络失败可重试原请求。'); } finally { setBusy(false); }
  }
  const title = { register: '人工建档', membership: '调整管理状态', assign: '人工赋标', suppress: '取消标签' }[mode];
  return <Modal title={title} close={() => { if (!busy) close(); }}><form className="space-y-4" onSubmit={submit}><fieldset disabled={busy} className="space-y-4">
    {mode === 'register' && <><p className="text-sm text-slate-500">建档后需确认是否纳入对象库管理。同名不自动合并，请核实身份。</p><Field label="姓名" name="name" required /><label className="block text-sm">当前单位<select name="organization" className="input mt-1" required>{catalog.organizations.map(o => <option key={o.id} value={o.id}>{o.properties.name}</option>)}</select></label><div className="grid sm:grid-cols-2 gap-3"><Field label="性别" name="sex" /><Field label="出生日期" name="birthDate" type="date" /><Field label="入职日期" name="entryDate" type="date" /><Field label="个人职级" name="personalRankCode" /></div></>}
    {mode === 'membership' && <><p className="text-sm text-slate-500">当前状态：{statuses[detail!.status]}</p><label className="block text-sm">目标状态<select name="status" className="input mt-1">{migrations[detail!.status].map(status => <option key={status} value={status}>{statuses[status]}</option>)}</select></label><Field label="调整依据" name="decisionCode" required /></>}
    {mode === 'assign' && <><label className="block text-sm">标签<select className="input mt-1" value={tagId} required onChange={e => setTagId(e.target.value)}>{catalog.tags.map(tag => <option key={tag.id} value={tag.id}>{tag.name}</option>)}</select></label><p className="text-sm text-slate-500">{existing ? '将补充人工依据，并恢复已取消的标签；原有依据保留。' : '将同时建立人员标签关联和本次人工依据。'}</p></>}
    {mode === 'suppress' && <p className="text-sm text-amber-800">取消「{tag!.name}」后不再生效，但保留所有来源依据与历史。</p>}
    <label className="block text-sm">操作说明<textarea name="note" className="input mt-1" required maxLength={2000} rows={3} /></label>
    <label className="flex gap-2 text-sm"><input type="checkbox" required />已核实上述信息，确认提交</label>
    {error && <p role="alert" className="text-sm text-red-700">{error}</p>}<button className="btn primary w-full" disabled={busy || (mode === 'assign' && !tagId) || (mode === 'register' && !catalog.organizations.length)}>{busy ? '提交中…' : '确认提交'}</button>
  </fieldset></form></Modal>;
}

function Field({ label, name, required = false, type = 'text' }: { label: string; name: string; required?: boolean; type?: string }) {
  return <label className="block text-sm">{label}<input className="input mt-1" name={name} type={type} required={required} maxLength={2000} /></label>;
}
createRoot(document.getElementById('root')!).render(<App />);
