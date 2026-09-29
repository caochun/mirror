export const objectNames: Record<string, string> = {
  Person: '人员', Organization: '单位', Position: '岗位', Appointment: '任职记录',
  ObjectMembership: '对象库管理状态', ExternalIdentity: '来源身份', ProfileReference: '档案关联', DataIssue: '数据问题',
  Tag: '标签', TagVersion: '标签版本', PersonTag: '人员标签', TagContribution: '标签依据',
  TagPolicy: '标签策略', TagPolicyVersion: '策略版本', EvaluationBatch: '评估批次', TagEvaluation: '评估结果', TagSuggestion: '标签建议',
  SupervisionMatter: '专项事项', MatterStage: '事项阶段', MatterParticipation: '事项参与', RiskSignal: '风险线索',
  ReminderTask: '提醒任务', ReminderVersion: '提醒内容版本', AudienceSnapshot: '名单快照', TaskRecipient: '接收记录',
  RecipientSnapshot: '接收人快照', ReviewRound: '审核记录', ReadReceipt: '阅读记录', DeliveryAttempt: '投递尝试',
  DeliveryReceipt: '投递回执', Withdrawal: '撤回记录', WithdrawalAttempt: '撤回尝试', WithdrawalReceipt: '撤回回执',
  OverdueEpisode: '逾期记录', ContentExample: '内容示例', ContentVersion: '内容版本', MediaAsset: '媒体资源'
};
export const relationNames: Record<string, string> = {
  OrganizationParent: '上级单位', PersonCurrentOrganization: '所属单位', AppointmentPerson: '任职人员',
  AppointmentOrganization: '任职单位', AppointmentPosition: '任职岗位', PersonProfile: '关联档案', IdentityPerson: '身份对应人员',
  IssuePerson: '问题涉及人员', IssueOrganization: '问题涉及单位', MembershipPerson: '管理状态对应人员',
  MembershipDecisionOrganization: '状态调整单位', TagParent: '上级标签', TagVersionOf: '版本所属标签',
  TagCurrentVersion: '当前标签版本', PolicyVersionOf: '版本所属策略', PolicyCurrentVersion: '当前策略版本',
  PolicyTargetsTagVersion: '策略目标标签', PolicyAppliesToOrganization: '策略适用单位',
  PersonTagPerson: '标签对应人员', PersonTagTag: '关联标签', ContributionForPersonTag: '依据对应人员标签',
  ContributionTagVersion: '依据引用版本', ContributionPolicyVersion: '依据引用策略', ContributionOrganization: '依据来源单位',
  ContributionSuggestion: '依据来源建议', ContributionEvaluation: '依据来源评估', ContributionBatch: '依据来源批次',
  BatchOrganization: '批次发起单位', BatchPolicyVersion: '批次使用策略', EvaluationBatchOf: '评估所属批次',
  EvaluationPerson: '评估人员', EvaluationPolicyVersion: '评估策略', IssueEvaluation: '问题对应评估',
  SuggestionEvaluation: '建议来源评估', SuggestionTagVersion: '建议目标标签',
  MatterOrganization: '事项负责单位', StageMatter: '阶段所属事项', ParticipationStage: '参与阶段',
  ParticipationPerson: '参与人员', RiskMatter: '风险涉及事项', RiskPerson: '风险涉及人员', RiskOrganization: '风险涉及单位',
  ContributionParticipation: '依据来源参与记录', ContributionRisk: '依据来源风险',
  TaskCreatedIn: '任务创建单位', ReminderVersionOf: '版本所属提醒', TaskPublishedVersion: '已发布内容',
  TaskWorkingVersion: '工作内容版本', AudienceTask: '名单所属任务', VersionAudience: '内容使用名单', TaskApprovedAudience: '已批准名单',
  RecipientTask: '接收所属任务', RecipientPerson: '接收人员', SnapshotAudience: '快照所属名单',
  SnapshotRecipient: '快照对应接收记录', SnapshotOrganization: '快照所属单位', ReviewVersion: '审核内容版本',
  ContentVersionOf: '版本所属内容示例', ContentForTag: '内容对应标签', ContentUsesMedia: '内容使用媒体',
  ContentCurrentVersion: '当前内容版本', ReminderCopiedFrom: '提醒引用示例', ReminderUsesMedia: '提醒使用媒体',
  SnapshotTagVersion: '快照依据标签', DeliveryRecipient: '投递对应接收记录', DeliveryVersion: '投递内容版本',
  DeliveryReceiptAttempt: '回执对应投递尝试', ReadRecipient: '阅读对应接收记录', ReadVersion: '阅读内容版本',
  WithdrawalRecipient: '撤回对应接收记录', WithdrawalOrganization: '撤回操作单位', WithdrawalAttemptOf: '尝试所属撤回',
  WithdrawalTargetsDelivery: '撤回目标投递', WithdrawalReceiptAttempt: '回执对应撤回尝试',
  OverdueRecipient: '逾期对应接收记录', OverdueVersion: '逾期内容版本', OverdueOrganization: '逾期管理单位',
  IssueRecipient: '问题涉及接收记录', IssueReminderVersion: '问题涉及提醒版本'
};
export const stateNames: Record<string, string> = {
  PENDING: '待确认', IN_SCOPE: '正常管理', EXCLUDED: '非管理对象', SUSPENDED: '暂停管理',
  CURRENT: '当前任职', ENDED: '已结束', ACTIVE: '有效', INACTIVE: '已停用',
  ENABLED: '已启用', DISABLED: '已停用', NONE: '未取消', SUPPRESSED: '已人工取消',
  MANUAL: '人工', RULE: '规则', PERSON_SOURCE: '人员来源', MOCK_REFERENCE: '演示来源'
};
export const operationNames: Record<string, string> = { CREATED: '建立', UPDATED: '变更', DELETED: '结束', RESTORED: '恢复' };
export function objectLabel(object: { type: string; id: string; properties: Record<string, unknown>; presentation?: { title: string | null } }) {
  if (object.presentation?.title) return object.presentation.title;
  const properties = object.properties;
  if (properties.name || properties.title) return String(properties.name || properties.title);
  const name = objectNames[object.type] || object.type;
  const hint = properties.status || properties.suppression || properties.kind || properties.sourceSystem || properties.note;
  return hint ? `${name} · ${stateNames[String(hint)] || String(hint)}` : name;
}
export const timeLabel = (time: string | null) => time ? new Date(time).toLocaleString('zh-CN') : '—';

/** Captions describe the relationship from the selected object's perspective. */
export function relationCaption(type: string, direction: string, target?: { type: string; properties: Record<string, unknown> } | null, ended = false) {
  const captions: Record<string, [string, string]> = {
    PersonCurrentOrganization: ['所属单位', '本单位人员'], OrganizationParent: ['上级单位', '下级单位'],
    AppointmentPerson: ['人员', ended ? '曾关联的任职' : target?.properties.status === 'CURRENT' ? '当前任职' : target?.properties.status === 'ENDED' ? '历史任职' : '任职记录'],
    AppointmentOrganization: ['任职单位', '本单位的任职记录'], AppointmentPosition: ['岗位', '担任此岗位的任职记录'],
    MembershipPerson: ['人员', '管理状态'], MembershipDecisionOrganization: ['状态调整单位', '由本单位调整的管理状态'],
    IdentityPerson: ['人员', '数据来源'], PersonProfile: ['关联档案', '档案对应人员'],
    PersonTagPerson: ['人员', '标签记录'], PersonTagTag: ['标签定义', '关联此标签的人员记录'],
    ContributionForPersonTag: ['对应的人员标签', '赋标依据'], ContributionTagVersion: ['赋标时的标签版本', '使用此版本的赋标依据'],
    ContributionOrganization: ['依据来源单位', '本单位提供的赋标依据'], ContributionPolicyVersion: ['依据使用的策略版本', '使用此策略的赋标依据'],
    TagParent: ['上级标签', '下级标签'], TagVersionOf: ['所属标签', '发布版本'], TagCurrentVersion: ['当前标签定义', '采用此版本的标签'],
    PolicyVersionOf: ['所属策略', '发布版本'], PolicyCurrentVersion: ['当前策略版本', '采用此版本的策略'],
    MatterOrganization: ['事项负责单位', '本单位负责的事项'], StageMatter: ['所属事项', '事项阶段'],
    ParticipationPerson: ['参与人员', '参与事项记录'], ParticipationStage: ['参与阶段', '阶段参与记录'],
    RecipientPerson: ['接收人员', '收到的提醒记录'], RecipientTask: ['提醒任务', '接收记录'],
    ReminderVersionOf: ['所属提醒任务', '内容版本'], TaskCreatedIn: ['创建单位', '本单位创建的提醒'],
    ReviewVersion: ['审核的内容版本', '审核记录'], ReadRecipient: ['对应接收记录', '阅读记录'],
    ReadVersion: ['阅读的内容版本', '阅读记录'], TaskPublishedVersion: ['已发布内容', '发布此版本的任务']
  };
  const caption = captions[type];
  if (caption) return caption[direction === 'INBOUND' ? 1 : 0];
  return direction === 'INBOUND' ? `关联的${target ? objectNames[target.type] || '对象' : '对象'}` : relationNames[type] || '关联信息';
}
