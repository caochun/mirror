import { useEffect, useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import { api } from './api';
import { useSession } from './App';
import { Heading, ErrorBox, Modal } from './ui';
import { included, readingWindows, reminderStates } from './reminderTypes';
import type { ReminderTask, ReminderDetail } from './reminderTypes';

export function ReminderList() {
  const { actor } = useSession();
  const [tasks, setTasks] = useState<ReminderTask[]>([]);
  const [permissions, setPermissions] = useState<string[]>([]);
  const [state, setState] = useState(actor.role === 'REVIEWER' ? 'PENDING_REVIEW' : '');
  const [error, setError] = useState('');
  const [loading, setLoading] = useState(true);
  function load() {
    setLoading(true);
    setError('');
    Promise.all([api<ReminderTask[]>('/reminders'), api<string[]>('/auth/permissions')])
      .then(([t, p]) => {
        setTasks(t);
        setPermissions(p);
      })
      .catch((e) => setError(e.message))
      .finally(() => setLoading(false));
  }
  useEffect(load, []);
  return (
    <>
      <Heading
        title={actor.role === 'REVIEWER' ? '审核工作台' : '提醒任务'}
        subtitle="确认接收名单与最终内容，经创建单位独立审核后进入发送作业。"
      />
      <div className="mb-5 flex flex-wrap justify-between gap-3">
        <select aria-label="任务状态" value={state} onChange={(e) => setState(e.target.value)}>
          <option value="">全部状态</option>
          {Object.entries(reminderStates)
            .slice(0, 6)
            .map(([key, label]) => (
              <option key={key} value={key}>
                {label}
              </option>
            ))}
        </select>
        {permissions.includes('REMINDER_WRITE') && (
          <Link to="/reminders/new" className="primary">
            创建提醒
          </Link>
        )}
      </div>
      {error ? (
        <ErrorBox message={error} retry={load} />
      ) : loading ? (
        <p role="status">正在加载提醒任务…</p>
      ) : (
        <section className="panel overflow-x-auto">
          <table className="w-full min-w-[620px]">
            <thead>
              <tr>
                {['任务', '创建单位 / 人员', '接收人数', '状态', '发送方式'].map((t) => (
                  <th className="p-4" key={t}>
                    {t}
                  </th>
                ))}
              </tr>
            </thead>
            <tbody>
              {tasks
                .filter((t) => !state || t.state === state)
                .map((t) => (
                  <tr key={t.id} className="border-t border-slate-100">
                    <td className="p-4">
                      <Link to={`/reminders/${t.id}`} className="font-medium text-blue-700">
                        {t.title}
                      </Link>
                    </td>
                    <td className="p-4">
                      {t.organizationId} / {t.createdBy}
                    </td>
                    <td className="p-4">{t.recipientCount} 人</td>
                    <td className="p-4">{reminderStates[t.state] ?? t.state}</td>
                    <td className="p-4">
                      {t.sendMode === 'IMMEDIATE' ? '审核通过后立即' : new Date(t.plannedAt).toLocaleString('zh-CN')}
                    </td>
                  </tr>
                ))}
            </tbody>
          </table>
          {tasks.filter((t) => !state || t.state === state).length === 0 && (
            <p className="muted p-8 text-center">当前范围和状态下暂无任务。</p>
          )}
        </section>
      )}
    </>
  );
}

export function ReminderTaskDetail() {
  const { id } = useParams();
  const { actor } = useSession();
  const [detail, setDetail] = useState<ReminderDetail | null>(null);
  const [permissions, setPermissions] = useState<string[]>([]);
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);
  const [decision, setDecision] = useState('');
  const [comment, setComment] = useState('');
  const [acknowledged, setAcknowledged] = useState(false);
  const [duplicateAcknowledged, setDuplicateAcknowledged] = useState(false);
  const [confirmedImages, setConfirmedImages] = useState<string[]>([]);
  const [pendingCommand, setPendingCommand] = useState<{ request: string; key: string } | null>(null);
  function load() {
    setError('');
    Promise.all([api<ReminderDetail>(`/reminders/${id}`), api<string[]>('/auth/permissions')])
      .then(([d, p]) => {
        setDetail(d);
        setPermissions(p);
        setAcknowledged(d.task.confirmed);
        setDuplicateAcknowledged(d.task.confirmed);
        setConfirmedImages(d.task.confirmed ? d.images.map((image) => image.confirmationKey) : []);
      })
      .catch((e) => setError(e.message));
  }
  useEffect(load, [id]);
  async function command(path: string, body: object) {
    setBusy(true);
    setError('');
    const request = JSON.stringify({ path, body });
    const key = pendingCommand?.request === request ? pendingCommand.key : crypto.randomUUID();
    setPendingCommand({ request, key });
    try {
      await api(`/reminders/${id}/${path}`, {
        method: 'POST',
        headers: { 'Idempotency-Key': key },
        body: JSON.stringify(body),
      });
      setDecision('');
      load();
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setBusy(false);
    }
  }
  const task = detail?.task;
  const canWrite = permissions.includes('REMINDER_WRITE') && actor.organizationId === task?.organizationId;
  const canReview =
    permissions.includes('REMINDER_REVIEW') &&
    actor.organizationId === task?.organizationId &&
    actor.username !== task?.createdBy;
  const targets = detail?.entries.filter(included) ?? [];
  const units = new Map<string, number>();
  for (const entry of targets) units.set(entry.organizationName, (units.get(entry.organizationName) ?? 0) + 1);
  return (
    <>
      <Link to="/reminders" className="mb-5 inline-block text-sm text-blue-700">
        ← 返回提醒任务
      </Link>
      {error && (
        <div className="mb-5">
          <ErrorBox message={error} retry={load} />
        </div>
      )}
      {!detail || !task ? (
        <p role="status">正在加载任务…</p>
      ) : (
        <>
          <Heading
            title={task.title}
            subtitle={`${reminderStates[task.state]} · 创建单位 ${task.organizationId} · 创建人 ${task.createdBy}`}
          />
          <div className="mb-5 flex flex-wrap gap-3">
            {canWrite && ['DRAFT', 'REJECTED', 'REVIEW_EXPIRED'].includes(task.state) && (
              <Link className="secondary" to={`/reminders/${id}/edit`}>
                编辑草稿
              </Link>
            )}
            {canWrite && task.state === 'PENDING_REVIEW' && (
              <button className="secondary" disabled={busy} onClick={() => setDecision('WITHDRAW_REVIEW')}>
                撤回本轮审核
              </button>
            )}
            {canWrite && task.state === 'APPROVED_WAITING' && task.sendMode === 'SCHEDULED' && (
              <button className="secondary" onClick={() => setDecision('CANCEL')}>
                取消定时任务
              </button>
            )}
            {canReview && task.state === 'PENDING_REVIEW' && (
              <>
                <button className="primary" onClick={() => setDecision('APPROVE')}>
                  审核通过
                </button>
                <button className="secondary" onClick={() => setDecision('REJECT')}>
                  驳回任务
                </button>
              </>
            )}
          </div>
          {task.reviewRound > 0 && (
            <p className="muted mb-4">第 {task.reviewRound} 轮审核 · 待审核内容和名单为冻结快照。</p>
          )}
          {task.state === 'APPROVED_WAITING' && (
            <p className="mb-4 rounded-lg bg-blue-50 p-4 text-sm text-blue-800">
              审核已通过，发送作业已排队。渠道执行尚未接通，当前不表示已送达。
            </p>
          )}
          <div className="grid gap-6 xl:grid-cols-[minmax(0,1fr)_360px]">
            <section className="panel p-6">
              <h2 className="mb-4 font-semibold">最终内容预览</h2>
              <div className="reminder-body" dangerouslySetInnerHTML={{ __html: detail.bodyHtml }} />
              <p className="muted mt-6 border-t border-slate-100 pt-4">本提醒仅向本人展示，请勿截图外传。</p>
            </section>
            <section className="panel p-6">
              <h2 className="font-semibold">接收名单与发送设置</h2>
              <p className="mt-4 text-3xl font-semibold">
                {targets.length} <span className="text-sm text-slate-500">人 / {units.size} 个单位</span>
              </p>
              <ul className="muted mt-4 space-y-1">
                {[...units].map(([name, count]) => (
                  <li key={name}>
                    {name}：{count} 人
                  </li>
                ))}
              </ul>
              <p className="mt-5 text-sm">阅读时限：{readingWindows[task.readingWindow]}</p>
              <p className="muted mt-2">从每位接收人第一次真实送达成功起算。</p>
              <p className="mt-4 text-sm">
                {task.sendMode === 'IMMEDIATE'
                  ? '审核通过后立即发送'
                  : `计划发送：${new Date(task.plannedAt).toLocaleString('zh-CN')}`}
              </p>
              {canWrite && task.state === 'DRAFT' && (
                <div className="mt-5 border-t border-slate-100 pt-4">
                  <label className="flex items-start gap-2 text-sm">
                    <input type="checkbox" checked={acknowledged} onChange={(e) => setAcknowledged(e.target.checked)} />
                    <span>
                      我已核对最终内容及 {targets.length} 人名单{targets.length === 1 ? '，确认向1人定向提醒' : ''}。
                    </span>
                  </label>
                  <label className="mt-3 flex items-start gap-2 text-sm">
                    <input
                      type="checkbox"
                      checked={duplicateAcknowledged}
                      onChange={(e) => setDuplicateAcknowledged(e.target.checked)}
                    />
                    <span>若存在重复提醒，我已核实并确认继续。</span>
                  </label>
                  {detail.images.map((image) => (
                    <label
                      className="mt-4 block rounded-lg border border-slate-200 p-3 text-sm"
                      key={image.confirmationKey}
                    >
                      <img
                        src={`/api/media/${image.id}`}
                        alt={`待确认配图 ${image.index + 1}`}
                        className="mb-3 max-h-32 max-w-full object-contain"
                      />
                      <span className="flex gap-2">
                        <input
                          type="checkbox"
                          checked={confirmedImages.includes(image.confirmationKey)}
                          onChange={() =>
                            setConfirmedImages((current) =>
                              current.includes(image.confirmationKey)
                                ? current.filter((key) => key !== image.confirmationKey)
                                : [...current, image.confirmationKey],
                            )
                          }
                        />
                        我已确认第 {image.index + 1} 张图片不含身份证号、内部标签、预警原文和台账信息。
                      </span>
                    </label>
                  ))}
                  <button
                    className="secondary mt-4 w-full"
                    disabled={
                      busy || !acknowledged || targets.length === 0 || confirmedImages.length !== detail.images.length
                    }
                    onClick={() =>
                      command('confirm', {
                        expectedVersion: task.version,
                        selectionDigest: task.selectionDigest,
                        recipientCount: targets.length,
                        contentDigest: task.contentDigest,
                        contentAcknowledged: acknowledged,
                        singleRecipientAcknowledged: acknowledged,
                        duplicateAcknowledged,
                        confirmedMediaDigests: confirmedImages,
                      })
                    }
                  >
                    确认名单与内容
                  </button>
                  <button
                    className="primary mt-3 w-full"
                    disabled={
                      busy || !task.confirmed || !acknowledged || confirmedImages.length !== detail.images.length
                    }
                    onClick={() => setDecision('SUBMIT')}
                  >
                    提交审核
                  </button>
                </div>
              )}
            </section>
          </div>
          <section className="panel mt-6 overflow-x-auto">
            <h2 className="p-5 font-semibold">名单明细</h2>
            <table className="w-full">
              <thead>
                <tr>
                  {['人员', '单位', '选入方式', '结果'].map((h) => (
                    <th key={h} className="p-4">
                      {h}
                    </th>
                  ))}
                </tr>
              </thead>
              <tbody>
                {detail.entries.map((entry) => (
                  <tr key={entry.personId} className="border-t border-slate-100">
                    <td className="p-4">{entry.name}</td>
                    <td className="p-4">{entry.organizationName}</td>
                    <td className="p-4">
                      {[entry.matchedByCondition && '条件命中', entry.explicitlyIncluded && '手工指定']
                        .filter(Boolean)
                        .join(' + ')}
                    </td>
                    <td className="p-4">
                      {included(entry) ? '已选入' : entry.manuallyExcluded ? '手工排除' : entry.ineligibleReason}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </section>
          <section className="panel mt-6 p-5">
            <h2 className="font-semibold">审核记录</h2>
            <ol className="mt-4 space-y-3">
              {detail.rounds.map((r) => (
                <li key={r.id} className="text-sm">
                  第 {r.roundNumber} 轮 · {reminderStates[r.state] ?? r.state} · 提交人 {r.submittedBy}
                  {r.decidedBy && ` · 处理人 ${r.decidedBy}`}
                  {r.comment && <p className="muted">{r.comment}</p>}
                </li>
              ))}
            </ol>
            {detail.rounds.length === 0 && <p className="muted mt-3">尚未提交审核。</p>}
          </section>
          {decision && (
            <Modal
              label="确认任务操作"
              onClose={() => {
                if (!busy) setDecision('');
              }}
            >
              <div className="panel p-6">
                <h2 className="text-lg font-semibold">
                  {decision === 'APPROVE'
                    ? '确认审核通过'
                    : decision === 'REJECT'
                      ? '驳回并填写意见'
                      : decision === 'SUBMIT'
                        ? '确认提交本单位独立审核'
                        : decision === 'CANCEL'
                          ? '取消定时任务'
                          : '撤回本轮审核'}
                </h2>
                {['REJECT', 'CANCEL'].includes(decision) && (
                  <label className="mt-4 block text-sm">
                    原因或意见
                    <textarea
                      className="mt-2 w-full rounded-lg border border-slate-200 p-3"
                      maxLength={1000}
                      value={comment}
                      onChange={(e) => setComment(e.target.value)}
                    />
                  </label>
                )}
                {error && <ErrorBox message={error} />}
                <div className="mt-5 flex justify-end gap-3">
                  <button className="secondary" onClick={() => setDecision('')}>
                    返回
                  </button>
                  <button
                    className="primary"
                    disabled={busy || (['REJECT', 'CANCEL'].includes(decision) && !comment.trim())}
                    onClick={() => {
                      if (decision === 'SUBMIT') void command('submit', { expectedVersion: task.version });
                      else if (decision === 'WITHDRAW_REVIEW')
                        void command('withdraw-review', { expectedVersion: task.version });
                      else if (decision === 'CANCEL')
                        void command('cancel', { expectedVersion: task.version, reason: comment });
                      else void command('review', { expectedVersion: task.version, decision, comment });
                    }}
                  >
                    确认操作
                  </button>
                </div>
              </div>
            </Modal>
          )}
        </>
      )}
    </>
  );
}
