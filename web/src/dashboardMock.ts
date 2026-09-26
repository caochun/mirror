export type DashboardPerson = {
  name: string;
  organization: string;
  tag: string;
  state: string;
};

export const dashboardMock = {
  generatedAt: '2026-09-26 16:00:00',
  overview: {
    validObjects: 48620,
    taggedObjects: 46390,
    tagCoverage: 95.4,
    activeReminders: 28,
    delivered: 32144,
    read: 28890,
    readRate: 89.9,
    overdue: 3254,
  },
  organizations: [
    { name: '市级部门', count: 16842, share: 34.6, color: 'blue' },
    { name: '区镇单位', count: 14920, share: 30.7, color: 'cyan' },
    { name: '国有企事业单位', count: 10218, share: 21.0, color: 'violet' },
    { name: '村（社区）', count: 6640, share: 13.7, color: 'amber' },
  ],
  tags: [
    { name: '重点领域岗位', count: 12680, color: '#8b5cf6' },
    { name: '年轻干部', count: 9840, color: '#6366f1' },
    { name: '一把手', count: 2140, color: '#14b8a6' },
    { name: '重大项目阶段', count: 4820, color: '#f59e0b' },
    { name: '新提拔干部', count: 1760, color: '#f97316' },
  ],
  trend: [58, 64, 62, 71, 76, 82, 89],
  reminderFunnel: [
    { label: '目标人数', value: 34820, color: 'bg-blue-600' },
    { label: '已提交', value: 32980, color: 'bg-cyan-500' },
    { label: '送达成功', value: 32144, color: 'bg-teal-500' },
    { label: '最新版本已读', value: 28890, color: 'bg-emerald-500' },
    { label: '逾期未读', value: 3254, color: 'bg-rose-500' },
  ],
  quality: [
    { label: '身份或组织关联异常', value: 428, tone: 'rose', hint: '需要回到来源系统核实' },
    { label: '个人档案未关联', value: 316, tone: 'amber', hint: '不阻断可安全使用的字段' },
    { label: '标签规则无法计算', value: 182, tone: 'violet', hint: '按人员和规则局部处理' },
    { label: '非对象账号', value: 96, tone: 'slate', hint: '不进入标签和提醒名单' },
  ],
  recentTasks: [
    { title: '重大项目实施阶段提醒', organization: '市级部门', target: 4820, read: 4310, state: '部分已读' },
    { title: '节前廉洁自律提醒', organization: '区镇单位', target: 12640, read: 11982, state: '已完成' },
    { title: '新提拔干部任后提醒', organization: '市级部门', target: 1760, read: 1498, state: '部分已读' },
  ],
  overduePeople: [
    { name: '演示人员 018', organization: '演示一局', tag: '重大项目阶段', state: '逾期未读' },
    { name: '演示人员 042', organization: '演示二局', tag: '节前节点', state: '逾期未读' },
    { name: '演示人员 067', organization: '市级部门', tag: '重点领域岗位', state: '逾期未读' },
  ] satisfies DashboardPerson[],
};
