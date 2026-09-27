import { useEffect, useState } from 'react';
import { Link, useNavigate, useParams } from 'react-router-dom';
import { api } from './api';
import type { Organization, Page, Person } from './api';
import type { Tag } from './Tags';
import type { ReminderDetail, SelectionEntry, SelectionFilter } from './reminderTypes';
import { included } from './reminderTypes';
import { ErrorBox, Heading } from './ui';
import { RichTextEditor } from './RichTextEditor';

export function ReminderEditor() {
  const { id } = useParams();
  const navigate = useNavigate();
  const [step, setStep] = useState(0);
  const [organizations, setOrganizations] = useState<Organization[]>([]);
  const [tags, setTags] = useState<Tag[]>([]);
  const [filter, setFilter] = useState<SelectionFilter>({
    organizationIds: [],
    tagIds: [],
    tagOperator: 'ANY',
    personIds: [],
    excludedIds: [],
  });
  const [restoreIds, setRestoreIds] = useState<string[]>([]);
  const [entries, setEntries] = useState<SelectionEntry[]>([]);
  const [search, setSearch] = useState('');
  const [people, setPeople] = useState<Person[]>([]);
  const [title, setTitle] = useState('');
  const [body, setBody] = useState('<p></p>');
  const [category, setCategory] = useState('履责提醒');
  const [readingWindow, setReadingWindow] = useState('1d');
  const [sendMode, setSendMode] = useState('IMMEDIATE');
  const [plannedAt, setPlannedAt] = useState('');
  const [version, setVersion] = useState(0);
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);
  const [ready, setReady] = useState(false);
  const [command, setCommand] = useState<{ body: string; key: string } | null>(null);

  useEffect(() => {
    Promise.all([api<Organization[]>('/organizations'), api<Tag[]>('/tags')])
      .then(([o, t]) => {
        setOrganizations(o);
        setTags(t.filter((tag) => tag.leaf && tag.status === 'ACTIVE'));
        setReady(true);
      })
      .catch((e) => setError(e.message));
    if (id)
      api<ReminderDetail>(`/reminders/${id}`)
        .then((d) => {
          setTitle(d.task.title);
          setCategory(d.task.category);
          setBody(d.bodyHtml);
          setVersion(d.task.version);
          setFilter(d.filter);
          setEntries(d.entries);
          setReadingWindow(d.task.readingWindow);
          setSendMode(d.task.sendMode);
          if (d.task.plannedAt) {
            const date = new Date(d.task.plannedAt);
            setPlannedAt(new Date(date.getTime() - date.getTimezoneOffset() * 60000).toISOString().slice(0, 16));
          }
        })
        .catch((e) => setError(e.message));
  }, [id]);

  function toggle(values: string[], id: string) {
    return values.includes(id) ? values.filter((v) => v !== id) : [...values, id];
  }

  async function preview() {
    setBusy(true);
    setError('');
    try {
      const result = await api<{ entries: SelectionEntry[] }>('/reminders/preview', {
        method: 'POST',
        body: JSON.stringify(filter),
      });
      setEntries(result.entries);
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setBusy(false);
    }
  }

  async function findPeople() {
    setError('');
    try {
      setPeople((await api<Page<Person>>(`/people?q=${encodeURIComponent(search)}&size=100`)).items);
    } catch (e) {
      setError((e as Error).message);
    }
  }

  async function save() {
    setBusy(true);
    setError('');
    try {
      if (sendMode === 'SCHEDULED' && !plannedAt) throw new Error('请选择计划发送时间');
      const request = JSON.stringify({
        expectedVersion: version,
        title,
        bodyHtml: body,
        category,
        readingWindow,
        plannedAt: sendMode === 'SCHEDULED' ? new Date(plannedAt).toISOString() : null,
        filter,
        restoreIds,
      });
      const key = command?.body === request ? command.key : crypto.randomUUID();
      setCommand({ body: request, key });
      const result = await api<{ id: string }>(id ? `/reminders/${id}` : '/reminders', {
        method: id ? 'PUT' : 'POST',
        headers: { 'Idempotency-Key': key },
        body: request,
      });
      navigate(`/reminders/${result.id}`);
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setBusy(false);
    }
  }

  return (
    <>
      <Link to="/reminders" className="mb-5 inline-block text-sm text-blue-700">
        ← 返回任务列表
      </Link>
      <Heading
        title={id ? '编辑提醒草稿' : '创建提醒'}
        subtitle="选定人员，编辑内容，再核对发送安排。保存后使用服务端清洗后的最终预览确认。"
      />
      <nav aria-label="创建提醒步骤" className="mb-6 grid grid-cols-2 gap-2 lg:grid-cols-4">
        {['选择人员', '编辑内容', '发送设置', '保存核对'].map((label, index) => (
          <button
            key={label}
            className={`rounded-lg border p-3 text-sm ${step === index ? 'border-blue-600 bg-blue-50 text-blue-700' : 'border-slate-200 bg-white'}`}
            onClick={() => setStep(index)}
          >
            {index + 1}. {label}
          </button>
        ))}
      </nav>
      {error && (
        <div className="mb-4">
          <ErrorBox message={error} />
        </div>
      )}
      {!ready && !error && <p role="status">正在加载授权范围…</p>}
      {step === 0 && (
        <div className="grid gap-5 lg:grid-cols-2">
          <section className="panel space-y-5 p-5">
            <h2 className="font-semibold">按组织与标签筛选</h2>
            <fieldset>
              <legend className="mb-3 text-sm">组织范围（多个组织取并集，含下级）</legend>
              <div className="max-h-52 space-y-2 overflow-y-auto">
                {organizations.map((org) => (
                  <label key={org.id} className="flex items-center gap-2 text-sm">
                    <input
                      type="checkbox"
                      checked={filter.organizationIds.includes(org.id)}
                      onChange={() => setFilter({ ...filter, organizationIds: toggle(filter.organizationIds, org.id) })}
                    />
                    {org.name}
                  </label>
                ))}
              </div>
            </fieldset>
            <fieldset>
              <legend className="mb-3 text-sm">标签条件</legend>
              <select
                aria-label="标签匹配方式"
                value={filter.tagOperator}
                onChange={(e) => setFilter({ ...filter, tagOperator: e.target.value as 'ANY' | 'ALL' })}
              >
                <option value="ANY">满足任一标签</option>
                <option value="ALL">同时满足全部标签</option>
              </select>
              <div className="mt-3 space-y-2">
                {tags.map((tag) => (
                  <label key={tag.id} className="flex gap-2 text-sm">
                    <input
                      type="checkbox"
                      checked={filter.tagIds.includes(tag.id)}
                      onChange={() => setFilter({ ...filter, tagIds: toggle(filter.tagIds, tag.id) })}
                    />
                    {tag.name}
                  </label>
                ))}
                {tags.length === 0 && <p className="muted">尚无可用末级标签，可按组织或指定人员选择。</p>}
              </div>
            </fieldset>
            <button className="primary" disabled={busy} onClick={preview}>
              预览接收名单
            </button>
          </section>
          <section className="panel p-5">
            <h2 className="font-semibold">手工指定人员</h2>
            <p className="muted mt-2">与条件结果叠加，重复人员只计一次；不清空已有名单。</p>
            <div className="mt-4 flex gap-2">
              <input
                className="min-w-0 flex-1"
                aria-label="检索指定人员"
                placeholder="姓名或工号"
                value={search}
                onChange={(e) => setSearch(e.target.value)}
              />
              <button className="secondary" onClick={findPeople}>
                检索人员
              </button>
            </div>
            <ul className="mt-4 max-h-64 space-y-3 overflow-y-auto">
              {people.map((person) => (
                <li key={person.id} className="flex items-center justify-between gap-3 text-sm">
                  <span>
                    {person.name} · {person.organizationName}
                  </span>
                  <button
                    className="text-blue-700"
                    onClick={() => setFilter({ ...filter, personIds: toggle(filter.personIds, person.id) })}
                  >
                    {filter.personIds.includes(person.id) ? '取消指定' : '加入名单'}
                  </button>
                </li>
              ))}
            </ul>
            <p className="muted mt-4">已指定 {filter.personIds.length} 人。调整后重新预览以检查资格。</p>
          </section>
        </div>
      )}
      {step === 1 && (
        <section className="panel space-y-5 p-6">
          <label className="block text-sm">
            提醒标题
            <input className="mt-2 w-full" maxLength={120} value={title} onChange={(e) => setTitle(e.target.value)} />
          </label>
          <label className="block text-sm">
            内容分类
            <input
              className="mt-2 w-full"
              maxLength={100}
              value={category}
              onChange={(e) => setCategory(e.target.value)}
            />
          </label>
          <div>
            <p className="mb-2 text-sm">提醒正文</p>
            <RichTextEditor value={body} onChange={setBody} />
          </div>
          <p className="muted">
            支持格式化文字、列表及已配置的可信HTTPS链接。图片和示例库将随媒体功能接入；当前不上传图片。
          </p>
        </section>
      )}
      {step === 2 && (
        <section className="panel space-y-5 p-6">
          <label className="block text-sm">
            发送方式
            <select className="ml-4" value={sendMode} onChange={(e) => setSendMode(e.target.value)}>
              <option value="IMMEDIATE">审核通过后立即</option>
              <option value="SCHEDULED">指定时间单次发送</option>
            </select>
          </label>
          {sendMode === 'SCHEDULED' && (
            <label className="block text-sm">
              计划发送时间
              <input
                className="ml-4"
                type="datetime-local"
                value={plannedAt}
                onChange={(e) => setPlannedAt(e.target.value)}
              />
            </label>
          )}
          <label className="block text-sm">
            阅读时限
            <select className="ml-4" value={readingWindow} onChange={(e) => setReadingWindow(e.target.value)}>
              <option value="1d">1天（24小时）</option>
              <option value="2d">2天（48小时）</option>
              <option value="3d">3天（72小时）</option>
              <option value="1w">1周（168小时）</option>
            </select>
          </label>
          <p className="muted">每位接收人从首次真实送达成功开始计时。定时任务到点未审核通过不能迟到补发。</p>
        </section>
      )}
      {step === 3 && (
        <section className="panel p-6">
          <h2 className="font-semibold">保存后核对最终名单与内容</h2>
          <p className="muted mt-3">
            服务端会重新计算资格和权限、清洗正文。保存后查看最终预览并确认，再提交本单位独立审核员。
          </p>
          <p className="mt-4 text-sm">标题：{title || '未填写'}</p>
          <button className="primary mt-5" disabled={busy || !title.trim()} onClick={save}>
            保存草稿并核对
          </button>
        </section>
      )}
      <section className="panel mt-5 overflow-x-auto">
        <div className="flex flex-wrap items-center justify-between gap-3 p-5">
          <h2 className="font-semibold">名单预览：{entries.filter(included).length} 人</h2>
          <button className="secondary" disabled={busy} onClick={preview}>
            重新预览
          </button>
        </div>
        <table className="w-full">
          <thead>
            <tr>
              {['人员', '组织', '当前选择', '操作'].map((h) => (
                <th className="p-3" key={h}>
                  {h}
                </th>
              ))}
            </tr>
          </thead>
          <tbody>
            {entries.map((entry) => (
              <tr className="border-t border-slate-100" key={entry.personId}>
                <td className="p-3">{entry.name}</td>
                <td className="p-3">{entry.organizationName}</td>
                <td className="p-3">
                  {filter.excludedIds.includes(entry.personId) ? '手工排除' : entry.ineligibleReason || '可选入'}
                </td>
                <td className="p-3">
                  <button
                    className="text-blue-700"
                    onClick={() => {
                      const excluded = filter.excludedIds.includes(entry.personId);
                      setFilter({ ...filter, excludedIds: toggle(filter.excludedIds, entry.personId) });
                      if (excluded) setRestoreIds((previous) => [...new Set([...previous, entry.personId])]);
                      else setRestoreIds((previous) => previous.filter((id) => id !== entry.personId));
                    }}
                  >
                    {filter.excludedIds.includes(entry.personId) ? '恢复' : '移除'} {entry.name}
                  </button>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
        {entries.length === 0 && <p className="muted p-5">请设置条件或指定人员，然后预览名单。</p>}
      </section>
      <div className="mt-5 flex justify-end gap-3">
        <button className="secondary" disabled={step === 0} onClick={() => setStep((s) => s - 1)}>
          上一步
        </button>
        <button className="primary" disabled={step === 3} onClick={() => setStep((s) => s + 1)}>
          下一步
        </button>
      </div>
    </>
  );
}
