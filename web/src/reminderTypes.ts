export type SelectionFilter = {
  organizationIds: string[];
  tagIds: string[];
  tagOperator: 'ANY' | 'ALL';
  personIds: string[];
  excludedIds: string[];
};
export type SelectionEntry = {
  personId: string;
  name: string;
  organizationId: string;
  organizationName: string;
  matchedByCondition: boolean;
  explicitlyIncluded: boolean;
  manuallyExcluded: boolean;
  ineligibleReason: string;
};
export function included(entry: SelectionEntry) {
  return (entry.matchedByCondition || entry.explicitlyIncluded) && !entry.manuallyExcluded && !entry.ineligibleReason;
}
export type ReminderTask = {
  id: string;
  version: number;
  state: string;
  title: string;
  organizationId: string;
  createdBy: string;
  reviewRound: number;
  sendMode: string;
  plannedAt: string;
  readingWindow: string;
  category: string;
  recipientCount: number;
  selectionDigest: string;
  contentDigest: string;
  confirmed: boolean;
  revisionState: string;
  publishedVersionId: string;
  submittedBy: string;
};
export type ReminderDetail = {
  task: ReminderTask;
  bodyHtml: string;
  contentDigest: string;
  filter: SelectionFilter;
  entries: SelectionEntry[];
  rounds: { id: string; roundNumber: number; state: string; submittedBy: string; decidedBy: string; comment: string }[];
  reviewComment: string;
  sourceContentVersionId: string;
  previewTitle: string;
  previewVersionId: string;
  images: { index: number; id: string; digest: string; confirmationKey: string }[];
};
export const reminderStates: Record<string, string> = {
  DRAFT: '草稿',
  PENDING_REVIEW: '待审核',
  REJECTED: '已驳回',
  REVIEW_EXPIRED: '审核过期',
  APPROVED_WAITING: '已审核待发送',
  CANCELLED: '已取消',
  SENDING: '发送中',
  ALL_SUCCESS: '全部送达',
  PARTIAL_FAILED: '部分失败',
  ALL_FAILED: '全部失败',
  APPROVED: '已通过',
  WITHDRAWN: '已撤回审核',
  EXPIRED: '已过期',
  PENDING: '待审核',
};
export const readingWindows: Record<string, string> = {
  '1d': '24小时',
  '2d': '48小时',
  '3d': '72小时',
  '1w': '168小时',
};

export const revisionStates: Record<string, string> = {
  NONE: '', DRAFT: '修订草稿', PENDING_REVIEW: '修订待审核', REJECTED: '修订已驳回', WITHDRAWN: '修订审核已撤回', APPROVED: '修订已审核待发布',
};
