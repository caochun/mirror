import { useEffect, useRef, useState } from 'react';
import type { FormEvent, ReactNode } from 'react';
import { api, ApiError } from './api';
import { Modal } from './ui';
import { reminderStates } from './reminderTypes';

type Metric = { code: string; value: number | null; numerator: number; denominator: number | null; grain: string; definition: string; status: string; missingInputs: number };
type Bar = { id: string; label: string; count: number; people: number };
type Filter = { organizationId: string; creatorOrganization: string; recipientOrganization: string; tagId: string; tagVersionId: string; category: string; taskId: string; from: string; to: string; includeWithdrawn: boolean };
type Report = {
  snapshotId: string; asOf: string; scope: string; dataMode: string; metrics: Metric[]; organizations: Bar[]; tags: Bar[];
  sources: Bar[]; pending: Bar[]; integration: Bar[]; counts: Record<string, number>; notes: string[];
  tasks: { id: string; title: string; organization: string; target: number; read: number; state: string; firstPublishedAt: string }[];
  options: Record<string, { id: string; label: string }[]>;
};
type DetailRow = { id: string; personId: string; name: string; organization: string; label: string; state: string; mode: string; time: string; href: string };
type Details = { items: DetailRow[]; total: number; page: number; size: number; asOf: string };
const emptyFilter: Filter = { organizationId: '', creatorOrganization: '', recipientOrganization: '', tagId: '', tagVersionId: '', category: '', taskId: '', from: '', to: '', includeWithdrawn: true };
const names: Record<string, string> = {
  effectivePeople: '有效对象', associationIssues: '已记录关联异常', nonObjectAccounts: '非对象账号', tagCoverage: '标签覆盖率',
  tagSourceDistribution: '赋标来源', tagPending: '标签待处理', targetRecipients: '提醒目标', deliveryRate: '送达成功率',
  readRateAmongDelivered: '送达者最新版本阅读率', readCoverageAmongTargets: '全目标阅读覆盖率', overdueUnread: '逾期未读', integrationIssues: '已记录对接异常',
  organizations: '当前单位对象', tagHierarchy: '标签目录覆盖', notPublished: '已批准未发布', tags: '标签覆盖人员', sources: '标签来源贡献', pending: '标签待处理项', integration: '对接异常事件',
  submitted: '已提交渠道', delivered: '送达成功', failed: '发送失败', unknown: '送达结果未知', unread: '最新版本未读', withdrawn: '已撤回',
};
const categories: Record<string, string> = { MANUAL: '人工', RULE: '规则', AI_REVIEWED: 'AI复核确认', STAGE: '专项', SUPPRESSED: '人工抑制',
  AI_PENDING: 'AI待复核', RULE_UNCOMPUTABLE: '规则无法计算', AI_UNDECIDABLE: 'AI无法判断', AI_FAILED: 'AI调用失败',
  READ_WITHOUT_DELIVERY: '阅读与送达不一致', CONTENT_FAILURE: '正文访问异常', IMAGE_FAILURE: '图片访问异常', PAGE_FAILURE: '页面访问异常' };
const units: Record<string, string> = { PERSON: '人', ACCOUNT: '个账号', CONTRIBUTION: '条来源', PERSON_TAG: '个人员标签项', TASK_PERSON: '人次', EVENT: '个事件' };
const businessStates: Record<string, string> = { ACTIVE: '有效', NON_OBJECT: '非对象', OPEN: '待处理', RESOLVED: '已解决', OVERDUE: '逾期未读',
  DELIVERED: '送达成功', FAILED: '失败', UNKNOWN: '结果未知', UNREAD: '未读', READ: '已读', PENDING: '待发送',
  NOT_PUBLISHED: '未发布', SUBMITTED: '已提交', NONE: '未撤回', WITHDRAWN: '已撤回', REQUESTED: '撤回处理中' };
const stateName = (value: string) => value.split('/').map(part => part.trim()).map(part => Object.hasOwn(businessStates, part) ? businessStates[part] : categoryName(part)).join(' / ');
const categoryName = (value: string) => Object.hasOwn(categories, value) ? categories[value] : value;
const count = (value: number | undefined) => (value ?? 0).toLocaleString('zh-CN');
const time = (value: string) => value ? new Date(value).toLocaleString('zh-CN') : '—';

export function Dashboard() {
  const [draft, setDraft] = useState<Filter>(emptyFilter);
  const [filter, setFilter] = useState<Filter>(emptyFilter);
  const [report, setReport] = useState<Report | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [access, setAccess] = useState(0);
  const [refresh, setRefresh] = useState(0);
  const [drill, setDrill] = useState<{ code: string; group: string; title: string } | null>(null);
  const sequence = useRef(0);
  useEffect(() => {
    const request = ++sequence.current;
    setLoading(true); setError(''); setAccess(0); setDrill(null);
    const params = new URLSearchParams();
    for (const [key, value] of Object.entries(filter)) {
      if (key === 'from' || key === 'to') { if (value) params.set(key, new Date(String(value)).toISOString()); }
      else params.set(key, String(value));
    }
    api<Report>(`/metrics?${params}`).then(result => {
      if (sequence.current === request) setReport(result);
    }).catch(e => {
      if (sequence.current === request) { setError(e.message); if (e instanceof ApiError) setAccess(e.status); }
    }).finally(() => { if (sequence.current === request) setLoading(false); });
    return () => { sequence.current++; };
  }, [filter, refresh]);

  function apply(event: FormEvent) { event.preventDefault(); setFilter({ ...draft }); }
  function open(code: string, group = '', title = names[code] ?? code) { setDrill({ code, group, title }); }
  const metrics = Object.fromEntries((report?.metrics ?? []).map(metric => [metric.code, metric]));
  const metricValue = (code: string) => {
    const metric = metrics[code];
    if (!metric || metric.value === null) return metric?.status === 'UNKNOWN' ? '待核实' : '—';
    return metric.denominator === null ? count(metric.value) : `${metric.value.toFixed(1)}%`;
  };
  const metricDetail = (code: string) => {
    const metric = metrics[code];
    if (!metric) return '';
    if (metric.status === 'UNKNOWN') return `${metric.missingInputs} 项资料待核实，已确认分子 ${count(metric.numerator)}`;
    if (metric.denominator !== null) return `${count(metric.numerator)} / ${count(metric.denominator)} · ${metric.denominator === 0 ? '分母为0，不适用' : '点击查看分子明细'}`;
    return units[metric.grain] ?? metric.grain;
  };
  const mode = report?.dataMode === 'MOCK_RECORDS' ? 'Mock 业务记录' : report?.dataMode === 'MIXED' ? '包含 Mock 业务记录' : report?.dataMode === 'EMPTY' ? '暂无业务记录' : report?.dataMode === 'UNVERIFIED' ? '存在待核实记录' : '业务记录';

  return <div className="dashboard-shell min-h-[calc(100vh-8rem)] overflow-x-hidden bg-[#07111f] p-5 text-slate-100 lg:p-9">
    <header className="flex flex-wrap items-end justify-between gap-5">
      <div><p className="text-xs tracking-[0.25em] text-cyan-300">MIRROR · OBJECT LIBRARY</p>
        <h1 className="mt-3 text-3xl font-semibold lg:text-4xl">政务系统对象库 · 核心价值总览</h1>
        <p className="mt-3 text-sm text-slate-400">对象 · 关系 · 画像 · 提醒 · 闭环</p></div>
      <div className="text-right text-xs text-slate-400"><span className="rounded-full border border-amber-400/40 bg-amber-400/10 px-3 py-2 text-amber-200">{report ? mode : '按授权范围查询'}</span>
        {report && <p className="mt-4">{report.scope} · 截至 {time(report.asOf)}</p>}</div>
    </header>
    {error && <section className="mt-6 rounded-xl border border-rose-400/30 bg-slate-900 p-6" role="alert">
      <h2 className="font-semibold">{access === 401 ? '请先登录查看业务大屏' : access === 403 ? '当前账号无统计权限' : '统计暂不可用'}</h2>
      <p className="mt-3 text-sm text-slate-400">{access === 401 ? '大屏及明细按当前账号的数据范围提供。' : error}</p>
      {access === 401 || access === 403 ? <a className="mt-5 inline-block text-cyan-300" href="/">进入业务工作台</a>
        : <button className="mt-5 text-cyan-300" onClick={() => setRefresh(refresh + 1)}>重新加载统计</button>}
    </section>}
    {loading && <p className="mt-8 rounded-xl bg-slate-900 p-6 text-slate-300" role="status">正在加载核心价值数据…</p>}
    {report && access !== 401 && access !== 403 && <>
      <form onSubmit={apply} className="mt-7 rounded-xl border border-slate-700/60 bg-slate-900/70 p-5 text-sm">
        <div className="grid gap-4 sm:grid-cols-2 xl:grid-cols-4">
          {([
            ['organizationId', '对象当前单位', 'organizations'], ['tagId', '标签目录', 'tags'], ['creatorOrganization', '任务创建单位', 'creatorOrganizations'],
            ['recipientOrganization', '发送时接收单位', 'organizations'], ['taskId', '提醒任务', 'tasks'], ['category', '内容分类', 'categories'], ['tagVersionId', '提醒标签版本', 'tagVersions'],
          ] as const).map(([key, label, options]) => <label className="min-w-0 text-slate-400" key={key}>{label}
            <select aria-label={label} className="mt-2 w-full min-w-0 !border-slate-700 !bg-slate-950 !text-slate-200" value={draft[key]}
              onChange={event => setDraft({ ...draft, [key]: event.target.value })}><option value="">全部</option>
              {(report.options[options] ?? []).map(option => <option key={option.id} value={option.id}>{option.label}</option>)}
            </select></label>)}
          <label className="text-slate-400">提醒历史范围<select aria-label="提醒历史范围" className="mt-2 w-full !border-slate-700 !bg-slate-950 !text-slate-200" value={String(draft.includeWithdrawn)}
            onChange={event => setDraft({ ...draft, includeWithdrawn: event.target.value === 'true' })}>
            <option value="true">包含撤回历史</option><option value="false">仅未撤回记录</option></select></label>
          <label className="min-w-0 text-slate-400">任务/异常起始时间<input aria-label="统计起始时间" type="datetime-local" className="mt-2 w-full min-w-0 !border-slate-700 !bg-slate-950 !text-slate-200" value={draft.from} onChange={e => setDraft({ ...draft, from: e.target.value })} /></label>
          <label className="min-w-0 text-slate-400">结束时间（不含）<input aria-label="统计结束时间" type="datetime-local" className="mt-2 w-full min-w-0 !border-slate-700 !bg-slate-950 !text-slate-200" value={draft.to} onChange={e => setDraft({ ...draft, to: e.target.value })} /></label>
          <div className="flex items-end gap-3"><button className="primary" disabled={loading}>应用筛选</button><button type="button" className="rounded-lg border border-slate-600 px-4 py-2" disabled={loading} onClick={() => setRefresh(refresh + 1)}>刷新统计</button></div>
        </div>
        <p className="mt-4 text-xs leading-6 text-slate-500">对象总数与质量按当前单位；目录筛选影响标签覆盖和提醒，版本/日期仅影响提醒与对接。已读与撤回为独立事实，历史人数不能直接相加。</p>
      </form>
      {!loading && report.dataMode === 'EMPTY' && <p role="status" className="mt-5 rounded-xl bg-slate-900 p-5">当前统计范围暂无对象或提醒数据。</p>}
      <section className="mt-5 grid gap-4 sm:grid-cols-2 xl:grid-cols-4">
        {['effectivePeople', 'tagCoverage', 'targetRecipients', 'readRateAmongDelivered'].map((code, index) => <button key={code} onClick={() => open(code)} aria-label={`查看${names[code]}明细`}
          className={`rounded-xl border border-slate-700/60 border-t-2 ${['border-t-blue-400', 'border-t-violet-400', 'border-t-cyan-400', 'border-t-emerald-400'][index]} bg-slate-900/70 p-5 text-left`}>
          <p className="text-xs text-slate-400">{names[code]}</p><strong className="mt-3 block text-3xl">{metricValue(code)}</strong>
          <p className="mt-2 text-xs text-slate-500">{metricDetail(code)}{code === 'targetRecipients' && ` · 去重 ${count(report.counts.targetPeople)} 人`}</p>
        </button>)}
      </section>
      <section className="mt-5 grid gap-5 xl:grid-cols-2">
        <Panel title="对象与组织" eyebrow="01 · OBJECTS & RELATIONSHIPS"><Bars values={report.organizations} unit="人" onClick={bar => open('organizations', bar.id, bar.label)} />
          <div className="mt-5 grid grid-cols-2 gap-3">
            <SmallMetric label="已记录关联异常" value={metricValue('associationIssues')} onClick={() => open('associationIssues')} />
            <SmallMetric label="非对象账号" value={metricValue('nonObjectAccounts')} onClick={() => open('nonObjectAccounts')} />
          </div><p className="mt-4 text-xs text-slate-500">档案关联待核实：{count(report.counts.unverifiedProfiles)} 人。异常对象仍保留在库。</p>
        </Panel>
        <Panel title="标签画像" eyebrow="02 · TAGGED PORTRAIT"><Bars values={report.tags} unit="人" onClick={bar => open('tagHierarchy', bar.id, bar.label)} />
          <h3 className="mb-3 mt-5 text-xs text-slate-400">赋标来源 · 来源数与涉及人数分别统计</h3>
          <div className="grid gap-3 sm:grid-cols-2">{report.sources.map(source => <button key={source.id} className="rounded-lg bg-violet-400/10 p-3 text-left" onClick={() => open('sources', source.id, categoryName(source.id))}>
            <p className="text-xs text-violet-200">{categoryName(source.id)}</p><strong className="mt-2 block">{count(source.count)} 条 / {count(source.people)} 人</strong></button>)}</div>
          {report.sources.length === 0 && <p className="text-xs text-slate-500">尚无有效来源贡献记录。</p>}
        </Panel>
      </section>
      <section className="mt-5 grid gap-5 xl:grid-cols-[1.3fr_0.7fr]">
        <Panel title="提醒闭环" eyebrow="03 · REMINDER LOOP">
          <div className="grid gap-5 md:grid-cols-2"><div className="space-y-4">
            {['targetRecipients', 'notPublished', 'submitted', 'delivered', 'readCoverageAmongTargets', 'unread', 'overdueUnread', 'withdrawn'].map(code => {
              const value = metrics[code]?.numerator ?? report.counts[code] ?? 0;
              const maximum = Math.max(metrics.targetRecipients?.numerator ?? 0, report.counts.withdrawn ?? 0, 1);
              return <button key={code} className="flex w-full items-center gap-3 text-left text-xs" onClick={() => open(code)}>
                <span className="w-28 shrink-0 text-slate-400">{code === 'readCoverageAmongTargets' ? '最新版本已读' : names[code]}</span><span className="h-2 flex-1 overflow-hidden rounded-full bg-slate-800"><span className={`block h-full ${code === 'overdueUnread' ? 'bg-amber-400' : 'bg-cyan-400'}`} style={{ width: `${Math.min(100, value / maximum * 100)}%` }} /></span><strong className="w-12 text-right">{metrics[code]?.status === 'UNKNOWN' ? '待核实' : count(value)}</strong>
              </button>;
            })}
          </div><div className="grid gap-3"><SmallMetric label="送达成功率" value={metricValue('deliveryRate')} onClick={() => open('deliveryRate')} />
            <SmallMetric label="全目标阅读覆盖率" value={metricValue('readCoverageAmongTargets')} onClick={() => open('readCoverageAmongTargets')} />
            <p className="text-xs leading-6 text-slate-500">发送失败 {count(report.counts.failed)} · 结果未知 {count(report.counts.unknown)}<br />新增发送重试 {count(report.counts.deliveryRetries)} 人次 · 撤回重试 {count(report.counts.withdrawalRetries)} 人次<br />逾期涉及 {count(report.counts.overduePeople)} 人；已撤回者不再督促。</p>
          </div></div>
        </Panel>
        <Panel title="待处理与对接" eyebrow="04 · DATA QUALITY">
          <button className="mb-4 flex w-full justify-between rounded-lg bg-amber-400/10 p-3 text-sm text-amber-200" onClick={() => open('tagPending')}><span>标签待处理项</span><strong>{metricValue('tagPending')}</strong></button>
          <Bars values={report.pending.map(bar => ({ ...bar, label: categoryName(bar.id) }))} unit="项" onClick={bar => open('pending', bar.id, bar.label)} />
          <h3 className="mb-3 mt-5 text-xs text-slate-400">对接异常 · 包含已解决的历史事件</h3>
          <Bars values={report.integration.map(bar => ({ ...bar, label: categoryName(bar.id) }))} unit="次" onClick={bar => open('integration', bar.id, bar.label)} />
          <p className="mt-4 text-xs text-slate-500">阅读资料待核实：{count(report.counts.unknownReading)} 项；送达资料待核实：{count(report.counts.unknownDelivery)} 项；标签来源待核实：{count(report.counts.tagsWithoutEvidence)} 项。历史标签依据缺失：{count(report.counts.missingTagSnapshots)} 人次。</p>
        </Panel>
      </section>
      <section className="mt-5 overflow-hidden rounded-xl border border-slate-700/60 bg-slate-900/70">
        <h2 className="px-5 py-4 font-medium">最近提醒任务</h2><div className="overflow-x-auto"><table className="w-full min-w-[640px] text-left text-sm">
          <thead className="text-xs text-slate-500"><tr>{['任务', '创建单位', '首次发布 / 创建', '目标人次', '最新版本已读', '状态'].map(label => <th className="px-5 py-3 font-normal" key={label}>{label}</th>)}</tr></thead>
          <tbody>{report.tasks.slice(0, 12).map(task => <tr key={task.id} className="border-t border-slate-800"><td className="px-5 py-4"><a href={`/reminders/${task.id}`} className="text-cyan-200">{task.title}</a></td><td className="px-5 py-4 text-slate-400">{task.organization}</td><td className="whitespace-nowrap px-5 py-4 text-slate-400">{time(task.firstPublishedAt)}</td><td className="px-5 py-4">{task.target}</td><td className="px-5 py-4">{task.read}</td><td className="px-5 py-4 text-emerald-300">{reminderStates[task.state] ?? task.state}</td></tr>)}</tbody>
        </table></div>{report.tasks.length === 0 && <p className="px-5 py-8 text-sm text-slate-500">当前范围暂无已批准或发布的提醒。</p>}
      </section>
      <details className="mt-5 rounded-xl border border-slate-700/60 p-5 text-sm text-slate-400"><summary>统计口径与数据范围</summary>
        <ul className="mt-4 space-y-2 text-xs leading-6">{report.notes.map(note => <li key={note}>{note}</li>)}</ul>
        <dl className="mt-5 space-y-3 text-xs leading-6">{report.metrics.map(metric => <div key={metric.code}><dt className="text-slate-200">{names[metric.code]} · {units[metric.grain]}</dt><dd>{metric.definition}</dd></div>)}</dl>
      </details>
    </>}
    {drill && report && <MetricDetails report={report} drill={drill} close={() => setDrill(null)} />}
  </div>;
}

function MetricDetails({ report, drill, close }: { report: Report; drill: { code: string; group: string; title: string }; close: () => void }) {
  const [data, setData] = useState<Details | null>(null);
  const [page, setPage] = useState(0);
  const [error, setError] = useState('');
  useEffect(() => {
    let active = true; setData(null); setError('');
    const params = new URLSearchParams({ metric: drill.code, group: drill.group, page: String(page), size: '20' });
    api<Details>(`/metrics/${report.snapshotId}/details?${params}`).then(result => { if (active) setData(result); }).catch(e => { if (active) setError(e.message); });
    return () => { active = false; };
  }, [report.snapshotId, drill, page]);
  return <Modal label={drill.title} onClose={close}><section className="w-full max-w-3xl rounded-xl border border-slate-700 bg-slate-900 p-6 text-slate-100">
    <div className="flex items-center justify-between gap-4"><h2 className="text-xl font-semibold">{drill.title}</h2><button className="text-slate-300" onClick={close}>关闭明细</button></div>
    <p className="mt-3 text-xs leading-6 text-slate-400">同一统计快照 · {time(report.asOf)}。数据发生变化时请刷新统计，处置前核对当前业务页面。</p>
    {error && <p role="alert" className="mt-5 text-amber-300">{error}</p>}
    {!data && !error && <p className="mt-5" role="status">正在加载明细…</p>}
    {data && <><p className="mt-4 text-sm">明细共 {data.total} 条</p>
      <div className="mt-4 max-h-[55vh] overflow-auto"><table className="w-full min-w-[530px] text-left text-sm"><thead className="text-xs text-slate-400"><tr>{['对象', '单位', '内容/依据', '状态', '时间'].map(label => <th className="p-3" key={label}>{label}</th>)}</tr></thead>
        <tbody>{data.items.map(row => <tr key={`${row.id}/${row.label}`} className="border-t border-slate-800"><td className="p-3">{row.href ? <a className="text-cyan-300" href={row.href}>{row.name}</a> : row.name}{row.mode === 'mock' && <span className="ml-2 text-xs text-amber-300">Mock</span>}</td><td className="p-3 text-slate-400">{row.organization}</td><td className="p-3">{categoryName(row.label)}</td><td className="p-3 text-xs">{stateName(row.state)}</td><td className="whitespace-nowrap p-3 text-xs text-slate-400">{time(row.time)}</td></tr>)}</tbody>
      </table></div>{data.total === 0 && <p className="py-8 text-center text-slate-400">此快照没有对应明细。</p>}
      <div className="mt-5 flex items-center gap-4 text-sm"><button disabled={page === 0} className="disabled:opacity-30" onClick={() => setPage(page - 1)}>上一页</button><span>第 {page + 1} 页</span><button disabled={(page + 1) * data.size >= data.total} className="disabled:opacity-30" onClick={() => setPage(page + 1)}>下一页</button></div>
    </>}
  </section></Modal>;
}

function Bars({ values, unit, onClick }: { values: Bar[]; unit: string; onClick: (value: Bar) => void }) {
  const maximum = Math.max(...values.map(value => value.count), 1);
  return <div className="space-y-3">{values.slice(0, 10).map(value => <button key={value.id} onClick={() => onClick(value)} className="flex w-full items-center gap-3 text-left text-xs">
    <span className="w-32 shrink-0 truncate text-slate-300" title={value.label}>{value.label}</span><span className="h-2 flex-1 overflow-hidden rounded-full bg-slate-800"><span className="block h-full bg-cyan-400" style={{ width: `${value.count / maximum * 100}%` }} /></span><strong className="w-20 text-right">{count(value.count)} {unit}</strong>
  </button>)}{values.length === 0 && <p className="py-5 text-sm text-slate-500">暂无对应记录。</p>}</div>;
}
function SmallMetric({ label, value, onClick }: { label: string; value: string; onClick: () => void }) {
  return <button className="rounded-lg border border-slate-700 bg-slate-950/50 p-4 text-left" onClick={onClick}><p className="text-xs text-slate-400">{label}</p><strong className="mt-2 block text-2xl">{value}</strong></button>;
}
function Panel({ title, eyebrow, children }: { title: string; eyebrow: string; children: ReactNode }) {
  return <section className="rounded-xl border border-slate-700/60 bg-slate-900/70 p-5"><p className="text-[10px] tracking-[0.2em] text-slate-500">{eyebrow}</p><h2 className="mb-5 mt-2 font-medium">{title}</h2>{children}</section>;
}
