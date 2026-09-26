import { useEffect, useState } from 'react';
import type { FormEvent } from 'react';
import { api, ApiError } from './api';
import { Heading, ErrorBox, Modal } from './ui';

export type Tag = { id: string; code: string; name: string; parentId: string; dimension: string; level: number; status: string; description: string; version: number; leaf: boolean };
type Assignment = { id: string; tagId: string; name: string; nameSnapshot: string; state: string; source: string; manualSuppressed: boolean; effectiveFrom: string; effectiveTo: string; tagVersion: string; note: string; version: number };
type TagChange = { version: number; name: string; state: string; source: string; actorId: string; organizationId: string; recordedAt: string; note: string };
const stateNames: Record<string, string> = { ACTIVE: '生效中', REMOVED: '人工删除', EXPIRED: '已失效' };

// Keep a command key while retrying an unchanged request; changing inputs creates a new command.
function useCommand() {
  const [last, setLast] = useState<{ payload: string; key: string } | null>(null);
  return (value: object) => {
    const payload = JSON.stringify(value);
    if (last?.payload === payload) return last.key;
    const key = crypto.randomUUID(); setLast({ payload, key }); return key;
  };
}
export function TagDirectory() {
  const [tags, setTags] = useState<Tag[]>([]); const [permissions, setPermissions] = useState<string[]>([]);
  const [error, setError] = useState(''); const [loading, setLoading] = useState(true); const [revision, setRevision] = useState(0);
  const [form, setForm] = useState<{ code: string; name: string; parentId: string; dimension: string; description: string }>({ code: '', name: '', parentId: '', dimension: 'PERSON', description: '' });
  const [selected, setSelected] = useState<Tag | null>(null); const [busy, setBusy] = useState(false); const [notice, setNotice] = useState('');
  const key = useCommand();
  useEffect(() => {
    setLoading(true); setError('');
    Promise.all([api<Tag[]>('/tags'), api<string[]>('/auth/permissions')]).then(([t, p]) => { setTags(t); setPermissions(p); })
      .catch(e => setError(e.message)).finally(() => setLoading(false));
  }, [revision]);
  const canConfigure = permissions.includes('TAG_CONFIGURE');
  async function create(e: FormEvent) {
    e.preventDefault(); setBusy(true); setError('');
    try {
      await api('/tags', { method: 'POST', headers: { 'Idempotency-Key': key(form) }, body: JSON.stringify(form) });
      setNotice('标签已创建，默认支持人工赋标。'); setForm({ code: '', name: '', parentId: '', dimension: 'PERSON', description: '' }); setRevision(r => r + 1);
    } catch (e) { setError((e as Error).message); } finally { setBusy(false); }
  }
  async function edit(e: FormEvent) {
    e.preventDefault(); if (!selected) return; setBusy(true); setError('');
    const data = new FormData(e.target as HTMLFormElement);
    const value = { name: String(data.get('name')), description: String(data.get('description')), status: String(data.get('status')), expectedVersion: selected.version };
    try {
      await api(`/tags/${selected.id}`, { method: 'PUT', headers: { 'Idempotency-Key': key({ id: selected.id, ...value }) }, body: JSON.stringify(value) });
      setSelected(null); setNotice('标签配置已更新，历史名称与版本仍保留。'); setRevision(r => r + 1);
    } catch (e) { setError((e as Error).message); } finally { setBusy(false); }
  }
  return <><Heading title="标签目录" subtitle="维护分类与末级标签。人员赋标在个人档案中操作。" />
    {error && <div className="mb-5"><ErrorBox message={error} retry={() => setRevision(r => r + 1)} /></div>}
    {notice && <p role="status" className="mb-5 rounded-lg bg-emerald-50 p-4 text-sm text-emerald-800">{notice}</p>}
    <div className="grid items-start gap-6 xl:grid-cols-[minmax(0,1fr)_340px]">
      <section className="panel overflow-x-auto"><table className="w-full"><thead className="bg-slate-50"><tr>{['名称 / 编码', '维度', '层级', '状态', '操作'].map(h => <th className="px-5 py-4" key={h}>{h}</th>)}</tr></thead>
        <tbody>{tags.map(t => <tr key={t.id} className="border-t border-slate-100"><td className="px-5 py-4"><p className="font-medium">{t.name}</p><p className="mt-1 text-xs text-slate-400">{t.code}{t.parentId && ` · 上级：${tags.find(p => p.id === t.parentId)?.name ?? t.parentId}`}</p></td><td className="px-5 py-4">{t.dimension === 'PERSON' ? '因人' : '因事'}</td><td className="px-5 py-4">{t.level} 级{t.leaf ? ' · 末级' : ' · 分类'}</td><td className="px-5 py-4">{t.status === 'ACTIVE' ? '启用' : '停用'}</td><td className="px-5 py-4">{canConfigure ? <button className="text-blue-700" onClick={() => setSelected(t)}>编辑 {t.name}</button> : <span className="text-slate-400">只读</span>}</td></tr>)}</tbody>
      </table>{loading ? <p role="status" className="muted p-6">正在加载标签…</p> : tags.length === 0 && <p className="muted p-8">尚无标签。由超级管理员创建目录后即可开展人工赋标。</p>}</section>
      {canConfigure ? <form onSubmit={create} className="panel space-y-4 p-5"><h2 className="font-semibold">新增标签</h2><label className="block text-sm">标签名称<input className="mt-2 w-full" required maxLength={80} value={form.name} onChange={e => setForm({ ...form, name: e.target.value })} /></label>
        <label className="block text-sm">稳定编码<input className="mt-2 w-full" required pattern="[A-Z][A-Z0-9_]{1,39}" placeholder="如 YOUNG_CADRE" value={form.code} onChange={e => setForm({ ...form, code: e.target.value.toUpperCase() })} /></label>
        <label className="block text-sm">上级目录<select className="mt-2 w-full" value={form.parentId} onChange={e => { const parent = tags.find(t => t.id === e.target.value); setForm({ ...form, parentId: e.target.value, dimension: parent?.dimension ?? form.dimension }); }}><option value="">无 · 一级标签</option>{tags.filter(t => t.level < 3 && t.status === 'ACTIVE').map(t => <option key={t.id} value={t.id}>{t.name}</option>)}</select></label>
        <label className="block text-sm">业务维度<select className="mt-2 w-full" disabled={!!form.parentId} value={form.dimension} onChange={e => setForm({ ...form, dimension: e.target.value })}><option value="PERSON">因人</option><option value="WORK">因事</option></select></label>
        <label className="block text-sm">定义说明<textarea className="mt-2 w-full rounded-lg border border-slate-200 p-3" maxLength={1000} rows={3} value={form.description} onChange={e => setForm({ ...form, description: e.target.value })} /></label><p className="muted">新标签默认仅人工赋标。只有末级标签可以赋予人员，目录最多三级。</p><button className="primary w-full" disabled={busy}>创建标签</button>
      </form> : <div className="panel p-5 muted">标签全局配置由超级管理员维护。可在本人授权范围内为人员添加或删除末级标签。</div>}
    </div>
    {selected && canConfigure && <Modal label="编辑标签" onClose={() => { if (!busy) setSelected(null); }}><form key={`${selected.id}-${selected.version}`} onSubmit={edit} className="panel w-full max-w-lg space-y-4 p-6"><h2 className="font-semibold">编辑标签 · {selected.code}</h2><label className="block text-sm">名称<input name="name" defaultValue={selected.name} required maxLength={80} className="mt-2 w-full" /></label><label className="block text-sm">定义说明<textarea name="description" defaultValue={selected.description} maxLength={1000} className="mt-2 w-full rounded-lg border border-slate-200 p-3" /></label><label className="block text-sm">状态<select name="status" defaultValue={selected.status} className="mt-2 w-full"><option value="ACTIVE">启用</option><option value="INACTIVE">停用</option></select></label><p className="muted">确认停用会同时停用下级目录，使相关人员的当前标签失效，保留历史。重新启用不自动恢复人员标签。</p>{error && <ErrorBox message={error} />}<div className="flex justify-end gap-3"><button type="button" className="secondary" onClick={() => setSelected(null)}>取消</button><button className="primary" disabled={busy}>确认保存</button></div></form></Modal>}
  </>;
}

export function PersonTags({ personId, active }: { personId: string; active: boolean }) {
  const [tags, setTags] = useState<Tag[]>([]); const [assignments, setAssignments] = useState<Assignment[]>([]); const [permissions, setPermissions] = useState<string[]>([]);
  const [selected, setSelected] = useState(''); const [error, setError] = useState(''); const [notice, setNotice] = useState(''); const [revision, setRevision] = useState(0); const [loading, setLoading] = useState(true);
  const [pending, setPending] = useState<{ tag: Tag; operation: string; version: number } | null>(null); const [note, setNote] = useState(''); const [busy, setBusy] = useState(false);
  const [history, setHistory] = useState<TagChange[] | null>(null); const key = useCommand();
  useEffect(() => {
    setLoading(true); setError('');
    Promise.all([api<Tag[]>('/tags'), api<Assignment[]>(`/people/${personId}/tags`), api<string[]>('/auth/permissions')])
      .then(([t, a, p]) => { setTags(t); setAssignments(a); setPermissions(p); }).catch(e => setError(e.message)).finally(() => setLoading(false));
  }, [personId, revision]);
  const writable = active && permissions.includes('PERSON_TAG_WRITE');
  const available = tags.filter(t => t.leaf && t.status === 'ACTIVE');
  function propose(tag: Tag, operation: string, version: number) { setNote(''); setError(''); setPending({ tag, operation, version }); }
  async function commit(e: FormEvent) {
    e.preventDefault(); if (!pending) return; setBusy(true); setError('');
    const body = { personIds: [personId], expectedVersions: { [personId]: pending.version }, operation: pending.operation, note, tagVersion: pending.tag.version };
    try {
      await api(`/tags/${pending.tag.id}/assignments`, { method: 'POST', headers: { 'Idempotency-Key': key({ tag: pending.tag.id, ...body }) }, body: JSON.stringify(body) });
      setPending(null); setNotice('人员标签已更新，变更已记录。'); setRevision(r => r + 1);
    } catch (e) { setError((e as Error).message); if (e instanceof ApiError && e.status === 409) setPending(null); } finally { setBusy(false); }
  }
  async function showHistory(tagId: string) { try { setHistory(await api<TagChange[]>(`/people/${personId}/tags/${tagId}/history`)); } catch (e) { setError((e as Error).message); } }
  return <section className="panel mt-6 p-6"><div className="flex flex-wrap items-center justify-between gap-3"><h2 className="font-semibold">人员标签</h2><button className="secondary" onClick={() => setRevision(r => r + 1)}>刷新标签</button></div>
    {error && <div className="mt-4"><ErrorBox message={error} /></div>}{notice && <p role="status" className="mt-3 text-sm text-emerald-700">{notice}</p>}
    {writable && <form className="mt-5 flex flex-wrap gap-3" onSubmit={e => { e.preventDefault(); const tag = tags.find(t => t.id === selected); if (tag) propose(tag, 'ADD', assignments.find(a => a.tagId === tag.id)?.version ?? 0); }}>
      <select aria-label="选择末级标签" required value={selected} onChange={e => setSelected(e.target.value)}><option value="">选择末级标签</option>{available.map(t => <option value={t.id} key={t.id}>{t.name}</option>)}</select><button className="primary" disabled={!selected}>添加标签</button>
    </form>}
    {loading ? <p className="muted mt-5" role="status">正在加载人员标签…</p> : assignments.length === 0 ? <p className="muted mt-5">当前没有标签记录。</p> : <div className="mt-5 divide-y divide-slate-100">{assignments.map(a => {
      const tag = tags.find(t => t.id === a.tagId);
      return <article className="flex flex-wrap items-center justify-between gap-4 py-4" key={a.id}><div><p className="text-sm font-medium">{a.name}<span className={`ml-3 rounded px-2 py-1 text-xs ${a.state === 'ACTIVE' ? 'bg-blue-50 text-blue-700' : 'bg-slate-100 text-slate-500'}`}>{stateNames[a.state] ?? a.state}</span></p><p className="muted mt-2">来源：{a.source === 'MANUAL' ? '人工' : a.source} · 生效时间：{a.effectiveFrom ? new Date(a.effectiveFrom).toLocaleString('zh-CN') : '—'}</p>{a.manualSuppressed && <p className="mt-1 text-xs text-amber-700">已人工删除，规则重算不得自动恢复</p>}</div><div className="flex gap-4 text-sm"><button className="text-blue-700" onClick={() => showHistory(a.tagId)}>查看 {a.name} 历史</button>{writable && tag?.status === 'ACTIVE' && <button className="text-blue-700" onClick={() => propose(tag, a.state === 'ACTIVE' ? 'REMOVE' : 'ADD', a.version)}>{a.state === 'ACTIVE' ? '删除' : '恢复'} {a.name}</button>}</div></article>;
    })}</div>}
    {history && <div className="mt-5 rounded-lg bg-slate-50 p-5"><div className="mb-4 flex justify-between"><h3 className="font-medium">标签变更历史</h3><button className="text-sm text-blue-700" onClick={() => setHistory(null)}>关闭历史</button></div><ol className="space-y-3">{history.map(h => <li key={h.version} className="text-sm"><span className="font-medium">{h.name} · {stateNames[h.state] ?? h.state}</span><span className="ml-3 text-slate-500">{h.actorId} · {new Date(h.recordedAt).toLocaleString('zh-CN')}</span>{h.note && <p className="muted">{h.note}</p>}</li>)}</ol></div>}
    {pending && <form onSubmit={commit} role="dialog" aria-label="确认人员标签变更" className="mt-5 rounded-lg border border-blue-200 bg-blue-50 p-5"><h3 className="font-medium">确认{pending.operation === 'REMOVE' ? '删除' : '添加或恢复'}「{pending.tag.name}」</h3><p className="muted mt-2">{pending.operation === 'REMOVE' ? '删除后规则重算不得自动恢复，原来源和历史继续保留。' : '保存后直接生效，本次操作会记录操作人和单位。'}</p><label className="mt-3 block text-sm">备注（可选）<input className="mt-2 w-full" maxLength={1000} value={note} onChange={e => setNote(e.target.value)} /></label>{error && <p role="alert" className="mt-3 text-red-700">{error}</p>}<div className="mt-4 flex gap-3"><button type="button" className="secondary" disabled={busy} onClick={() => setPending(null)}>取消</button><button className="primary" disabled={busy}>确认变更</button></div></form>}
  </section>;
}
