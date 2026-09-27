import { useEffect, useRef, useState } from 'react';
import type { FormEvent } from 'react';
import { api } from './api';
import type { Organization, Page } from './api';
import type { Tag } from './Tags';
import { ErrorBox, Heading } from './ui';

type Leaf = { field: string; operator: string; value: string | number | (string | number)[] };
type Condition = Leaf | { all: Condition[] } | { any: Condition[] };
type Rule = { id: string; version: number; name: string; tagId: string; tagName: string; state: string; condition: Condition; currentVersionId: string };
type PreviewRow = { personId: string; name: string; organization: string; outcome: string; change: string; suppressed: boolean; reason: string; missingFields: string[] };
type Preview = { id: string; state: string; ruleId: string; ruleVersion: number; digest: string; added: number; expired: number; unchanged: number; unknown: number; skipped: number; suppressed: number; processed: number; items: PreviewRow[]; total: number; page: number; size: number };
type Batch = { id: string; state: string; total: number; processed: number; success: number; unknown: number; skipped: number; startedAt: string; trigger: string };
type ResultRow = { id: string; personId: string; name: string; organization: string; rule: string; outcome: string; reason: string };
const fields: Record<string, string> = { ageYears: '年龄（按可靠出生日期）', serviceMonths: '入职月数', organizationId: '当前单位（精确）', rank: '标准职级', positionCode: '标准岗位编码', organizationNature: '单位性质映射', roleLevel: '最高职务层级映射', positionDomain: '岗位领域映射' };
const states: Record<string, string> = { DRAFT: '未发布', ACTIVE: '已启用', QUEUED: '排队中', RUNNING: '处理中', READY: '可确认发布', STALE: '资料已变化，须重新预览', FAILED: '未能全部完成', PARTIAL_FAILED: '部分无法计算', SUCCEEDED: '处理完成', MATCH: '命中', NO_MATCH: '未命中', UNKNOWN: '无法计算', SKIPPED: '已跳过', ADD: '预计新增', EXPIRE: '预计失效', UNCHANGED: '保持不变' };
const initial = (): Condition => ({ field: 'ageYears', operator: 'LT', value: 40 });

export function Rules() {
  const [rules, setRules] = useState<Rule[]>([]);
  const [tags, setTags] = useState<Tag[]>([]);
  const [organizations, setOrganizations] = useState<Organization[]>([]);
  const [permissions, setPermissions] = useState<string[]>([]);
  const [selected, setSelected] = useState<Rule | null>(null);
  const [condition, setCondition] = useState<Condition>(initial);
  const [tagId, setTagId] = useState('');
  const [name, setName] = useState('');
  const [previewId, setPreviewId] = useState('');
  const [preview, setPreview] = useState<Preview | null>(null);
  const [previewPage, setPreviewPage] = useState(0);
  const [confirmed, setConfirmed] = useState(false);
  const [batches, setBatches] = useState<Batch[]>([]);
  const [resultBatch, setResultBatch] = useState('');
  const [resultPage, setResultPage] = useState(0);
  const [results, setResults] = useState<Page<ResultRow> | null>(null);
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');
  const [busy, setBusy] = useState(false);
  const [revision, setRevision] = useState(0);
  const command = useRef<{ body: string; key: string } | null>(null);
  const canConfigure = permissions.includes('TAG_CONFIGURE');
  const canBatch = permissions.includes('TAG_BATCH_START');

  useEffect(() => {
    Promise.all([api<Rule[]>('/rules'), api<Tag[]>('/tags'), api<Organization[]>('/organizations'), api<string[]>('/auth/permissions')])
      .then(([r, t, o, p]) => { setRules(r); setTags(t); setOrganizations(o); setPermissions(p); })
      .catch(e => setError(e.message));
  }, [revision]);
  useEffect(() => {
    if (!previewId) return;
    let active = true;
    const load = () => api<Preview>(`/rules/previews/${previewId}?page=${previewPage}&size=20`)
      .then(result => { if (active) setPreview(result); }).catch(e => { if (active) setError(e.message); });
    load();
    const timer = window.setInterval(load, 1500);
    return () => { active = false; window.clearInterval(timer); };
  }, [previewId, previewPage]);
  useEffect(() => {
    if (!canBatch) return;
    let active = true;
    const load = () => api<Batch[]>('/rules/batches').then(result => { if (active) setBatches(result); }).catch(e => { if (active) setError(e.message); });
    load(); const timer = window.setInterval(load, 2000);
    return () => { active = false; window.clearInterval(timer); };
  }, [canBatch, revision]);
  useEffect(() => {
    if (!resultBatch) return;
    api<Page<ResultRow>>(`/rules/batches/${resultBatch}?page=${resultPage}&size=20`).then(setResults).catch(e => setError(e.message));
  }, [resultBatch, resultPage, batches]);

  async function send(path: string, body: object) {
    const encoded = JSON.stringify({ path, body });
    if (command.current?.body !== encoded) command.current = { body: encoded, key: crypto.randomUUID() };
    const result = await api<Record<string, string>>(path, { method: 'POST', headers: { 'Idempotency-Key': command.current.key }, body: JSON.stringify(body) });
    command.current = null;
    return result;
  }
  async function create(event: FormEvent) {
    event.preventDefault(); setBusy(true); setError('');
    try {
      await send('/rules', { tagId, name }); setName(''); setRevision(revision + 1); setNotice('规则身份已建立，尚未启用。请选择规则并预览条件。');
    } catch (e) { setError((e as Error).message); } finally { setBusy(false); }
  }
  async function propose() {
    if (!selected) return;
    setBusy(true); setError(''); setConfirmed(false);
    try {
      const result = await send(`/rules/${selected.id}/preview`, { condition });
      setPreview(null); setPreviewPage(0); setPreviewId(result.id);
    } catch (e) { setError((e as Error).message); } finally { setBusy(false); }
  }
  async function publish() {
    if (!selected || !preview || !confirmed) return;
    setBusy(true); setError('');
    try {
      await send(`/rules/${selected.id}/publish`, { previewId: preview.id, expectedVersion: preview.ruleVersion, confirmedDigest: preview.digest });
      setPreviewId(''); setPreview(null); setSelected(null); setRevision(revision + 1); setNotice('规则已发布，重算批次已排队。不会创建提醒任务。');
    } catch (e) { setError((e as Error).message); } finally { setBusy(false); }
  }
  async function recompute(rule: Rule) {
    if (!window.confirm(`确认按当前规则重算「${rule.name}」？人工删除状态不会被恢复。`)) return;
    setBusy(true); setError('');
    try { await send('/rules/batches', { ruleIds: [rule.id], personIds: [] }); setRevision(revision + 1); }
    catch (e) { setError((e as Error).message); } finally { setBusy(false); }
  }

  return <>
    <Heading title="标签规则" subtitle="使用明确字段和标准映射生成可解释标签。发布前核对影响，人工删除始终优先。" />
    {error && <div className="mb-5"><ErrorBox message={error} /></div>}
    {notice && <p role="status" className="mb-5 rounded-lg bg-emerald-50 p-4 text-sm text-emerald-800">{notice}</p>}
    <div className="grid items-start gap-6 xl:grid-cols-[minmax(0,1fr)_330px]">
      <section className="panel overflow-auto"><table className="w-full text-left text-sm"><thead><tr>{['规则', '目标标签', '状态', '操作'].map(label => <th className="p-4" key={label}>{label}</th>)}</tr></thead>
        <tbody>{rules.map(rule => <tr className="border-t border-slate-100" key={rule.id}><td className="p-4">{rule.name}</td><td className="p-4">{rule.tagName}</td><td className="p-4">{states[rule.state]}</td>
          <td className="flex gap-3 p-4">{canConfigure && <button className="text-blue-700" onClick={() => { setSelected(rule); setCondition(Object.keys(rule.condition).length ? rule.condition : initial()); setPreviewId(''); setPreview(null); setConfirmed(false); }}>配置 {rule.name}</button>}
            {canBatch && rule.state === 'ACTIVE' && <button className="text-blue-700" disabled={busy} onClick={() => recompute(rule)}>重算 {rule.name}</button>}</td></tr>)}</tbody>
      </table>{rules.length === 0 && <p className="muted p-6">尚无自动规则。新标签仍可人工维护。</p>}</section>
      {canConfigure && <form className="panel space-y-4 p-5" onSubmit={create}><h2 className="font-semibold">新建规则</h2>
        <label className="block text-sm">规则名称<input className="mt-2 w-full" required maxLength={100} value={name} onChange={event => setName(event.target.value)} /></label>
        <label className="block text-sm">目标末级标签<select className="mt-2 w-full" required value={tagId} onChange={event => setTagId(event.target.value)}><option value="">请选择</option>
          {tags.filter(tag => tag.leaf && tag.status === 'ACTIVE' && !['NEW_PROMOTION', 'RETIREMENT'].includes(tag.code)).map(tag => <option key={tag.id} value={tag.id}>{tag.name}</option>)}</select></label>
        <p className="muted">新提拔、退休过渡期只支持人工维护。映射字段未可靠配置时只会产生待处理项。</p>
        <button className="primary" disabled={busy || !tagId}>建立规则</button>
      </form>}
    </div>
    {selected && canConfigure && <section className="panel mt-6 space-y-5 p-6">
      <h2 className="font-semibold">配置规则 · {selected.name}</h2>
      <ConditionEditor value={condition} onChange={value => { setCondition(value); setPreviewId(''); setPreview(null); setConfirmed(false); }} organizations={organizations} tags={tags} />
      <p className="muted">使用当前有效职务及可靠字段。无法计算只影响本规则；本规则旧贡献将暂停，其他来源保留。年龄以北京时间日期计算。</p>
      <button className="primary" disabled={busy} onClick={propose}>预览全库影响</button>
    </section>}
    {previewId && <section className="panel mt-6 p-6">
      <h2 className="font-semibold">规则影响预览</h2>
      {!preview ? <p role="status" className="muted mt-4">正在读取预览…</p> : <>
        <p role="status" className="mt-3 text-sm">{states[preview.state]} · {preview.processed} / {preview.total} 项</p>
        {['READY', 'STALE'].includes(preview.state) && <>
          <div className="mt-4 flex flex-wrap gap-5 text-sm"><span>预计新增 {preview.added}</span><span>预计失效 {preview.expired}</span><span>保持不变 {preview.unchanged}</span><span>无法计算 {preview.unknown}</span><span>人工抑制 {preview.suppressed}</span><span>跳过 {preview.skipped}</span></div>
          <p className="muted mt-3">新增/失效/不变为标签效果；无法计算、跳过和抑制是原因分类，可与效果重叠。</p>
          <div className="mt-4 overflow-auto"><table className="w-full text-left text-sm"><thead><tr>{['人员', '单位', '计算结果', '标签效果', '解释'].map(label => <th className="p-3" key={label}>{label}</th>)}</tr></thead><tbody>{preview.items.map(row => <tr className="border-t border-slate-100" key={row.personId}>
            <td className="p-3">{row.name}</td><td className="p-3">{row.organization}</td><td className="p-3">{states[row.outcome]}{row.suppressed ? ' · 人工抑制' : ''}</td><td className="p-3">{states[row.change]}</td><td className="p-3">{row.reason}</td>
          </tr>)}</tbody></table></div>
          <div className="mt-4 flex gap-4"><button className="secondary" disabled={previewPage === 0} onClick={() => setPreviewPage(previewPage - 1)}>上页预览</button><button className="secondary" disabled={(previewPage + 1) * preview.size >= preview.total} onClick={() => setPreviewPage(previewPage + 1)}>下页预览</button></div>
          <label className="mt-5 flex items-start gap-2 text-sm"><input type="checkbox" checked={confirmed} onChange={event => setConfirmed(event.target.checked)} />我已核对全库影响，确认发布并开始重算</label>
          <button className="primary mt-4" disabled={busy || !confirmed || preview.state !== 'READY'} onClick={publish}>确认发布规则</button>
        </>}
      </>}
    </section>}
    {canBatch && <section className="panel mt-6 p-6"><h2 className="font-semibold">规则重算批次</h2><p className="muted mt-2">“已计算”包括命中和未命中，不等于全部人员新增标签。</p>
      <div className="mt-4 overflow-auto"><table className="w-full text-left text-sm"><thead><tr>{['状态', '进度', '已计算', '无法计算', '跳过', '明细'].map(label => <th className="p-3" key={label}>{label}</th>)}</tr></thead><tbody>{batches.map(batch => <tr className="border-t border-slate-100" key={batch.id}>
        <td className="p-3">{states[batch.state]}</td><td className="p-3">{batch.processed} / {batch.total}</td><td className="p-3">{batch.success}</td><td className="p-3">{batch.unknown}</td><td className="p-3">{batch.skipped}</td><td className="p-3"><button className="text-blue-700" onClick={() => { setResultBatch(batch.id); setResultPage(0); }}>查看结果</button></td>
      </tr>)}</tbody></table></div>
      {results && <div className="mt-5 rounded-lg bg-slate-50 p-4"><h3 className="font-semibold">逐项评估结果</h3><ul className="mt-3 space-y-2 text-sm">{results.items.map(row => <li key={row.id}>{row.name} · {row.organization} · {states[row.outcome]} · {row.reason}</li>)}</ul>
        <div className="mt-4 flex gap-4"><button className="secondary" disabled={resultPage === 0} onClick={() => setResultPage(resultPage - 1)}>上页结果</button><button className="secondary" disabled={(resultPage + 1) * results.size >= results.total} onClick={() => setResultPage(resultPage + 1)}>下页结果</button></div>
      </div>}
    </section>}
  </>;
}

function ConditionEditor({ value, onChange, organizations, tags, depth = 0 }: { value: Condition; onChange: (value: Condition) => void; organizations: Organization[]; tags: Tag[]; depth?: number }) {
  const group = 'all' in value ? 'all' : 'any' in value ? 'any' : null;
  if (group) {
    const children = (value as { all?: Condition[]; any?: Condition[] })[group]!;
    return <div className="space-y-3 rounded-lg border border-slate-200 bg-slate-50 p-4"><select aria-label={`条件组 ${depth + 1}`} value={group} onChange={event => onChange({ [event.target.value]: children } as Condition)}>
      <option value="all">同时满足全部</option><option value="any">满足任一</option></select>
      {children.map((child, index) => <div className="flex items-start gap-2" key={index}><div className="min-w-0 flex-1"><ConditionEditor value={child} organizations={organizations} tags={tags} depth={depth + 1} onChange={next => onChange({ [group]: children.map((c, i) => i === index ? next : c) } as Condition)} /></div>
        <button className="secondary" disabled={children.length === 1} onClick={() => onChange({ [group]: children.filter((_, i) => i !== index) } as Condition)}>移除条件</button></div>)}
      <button className="secondary" disabled={children.length >= 20} onClick={() => onChange({ [group]: [...children, initial()] } as Condition)}>添加条件</button>
      <button className="secondary ml-3" disabled={depth >= 3 || children.length >= 20} onClick={() => onChange({ [group]: [...children, { all: [initial()] }] } as Condition)}>添加条件组</button>
    </div>;
  }
  const leaf = value as Leaf;
  const numeric = ['ageYears', 'serviceMonths'].includes(leaf.field);
  const choices = leaf.field === 'organizationId' ? organizations.map(org => ({ id: org.id, name: org.name }))
    : ['organizationNature', 'roleLevel', 'positionDomain'].includes(leaf.field) ? tags.filter(tag => tag.leaf) : [];
  return <div className="flex flex-wrap items-center gap-3 rounded-lg border border-slate-200 bg-white p-3">
    <select aria-label="规则字段" value={leaf.field} onChange={event => onChange({ field: event.target.value, operator: ['ageYears', 'serviceMonths'].includes(event.target.value) ? 'LT' : 'EQ', value: ['ageYears', 'serviceMonths'].includes(event.target.value) ? 40 : '' })}>
      {Object.entries(fields).map(([code, name]) => <option value={code} key={code}>{name}</option>)}</select>
    <select aria-label="比较方式" value={leaf.operator} onChange={event => onChange({ ...leaf, operator: event.target.value, value: event.target.value === 'BETWEEN' ? [0, 40] : event.target.value === 'IN' ? [] : numeric ? 40 : '' })}>
      {(numeric ? [['EQ', '等于'], ['LT', '小于'], ['LTE', '不大于'], ['GT', '大于'], ['GTE', '不小于'], ['BETWEEN', '区间（含端点）']] : [['EQ', '等于'], ['IN', '在标准值集合中']]).map(([code, label]) => <option value={code} key={code}>{label}</option>)}</select>
    {choices.length > 0 && leaf.operator === 'EQ' ? <select aria-label="规则标准值" value={String(leaf.value)} onChange={event => onChange({ ...leaf, value: event.target.value })}><option value="">请选择</option>{choices.map(choice => <option key={choice.id} value={choice.id}>{choice.name}</option>)}</select>
      : <input aria-label="规则比较值" className="min-w-0 max-w-full" value={Array.isArray(leaf.value) ? leaf.value.join(',') : leaf.value} placeholder={leaf.operator === 'BETWEEN' ? '如 18,40' : leaf.operator === 'IN' ? '多个标准值用逗号分隔' : '标准值'}
          onChange={event => onChange({ ...leaf, value: ['BETWEEN', 'IN'].includes(leaf.operator) ? event.target.value.split(',').map(value => numeric ? Number(value) : value.trim()) : numeric ? Number(event.target.value) : event.target.value })} />}
    {depth === 0 && <button className="secondary" onClick={() => onChange({ all: [leaf, initial()] })}>组合条件</button>}
  </div>;
}
