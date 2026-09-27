import { test, expect } from '@playwright/test';
import type { Page } from '@playwright/test';

async function login(page: Page, username: string) {
  await page.goto('/');
  await page.getByLabel('账号', { exact: true }).fill(username);
  await page.getByLabel('密码', { exact: true }).fill('TestOnlyE2e-2026');
  await page.getByRole('button', { name: '进入工作空间', exact: true }).click();
  await expect(page.getByRole('button', { name: '退出', exact: true })).toBeVisible();
}
async function logout(page: Page) {
  await page.getByRole('button', { name: '退出', exact: true }).click();
  await expect(page.getByRole('heading', { name: '登录明镜', exact: true })).toBeVisible();
}

test('administrator previews and publishes a deterministic rule and recalculation respects a human deletion', async ({ page }, testInfo) => {
  await login(page, 'admin');
  const csrf = await (await page.request.get('/api/auth/csrf')).json();
  const response = await page.request.post('/api/tags', { data: { code: 'BROWSER_RULE_TAG', name: '单位规则演示标签', parentId: '', dimension: 'PERSON', description: '按明确单位归属验证' },
    headers: { [csrf.headerName]: csrf.token, 'Idempotency-Key': crypto.randomUUID() } });
  expect(response.ok()).toBeTruthy();
  const tag = await response.json();
  const remindersBefore = await (await page.request.get('/api/reminders')).json();
  await page.getByRole('link', { name: '标签规则', exact: true }).click();
  await page.getByLabel('规则名称', { exact: true }).fill('浏览器单位规则');
  await page.getByRole('combobox', { name: '目标末级标签', exact: true }).selectOption(tag.id);
  await page.getByRole('button', { name: '建立规则', exact: true }).click();
  await page.getByRole('button', { name: '配置 浏览器单位规则', exact: true }).click();
  await page.getByRole('combobox', { name: '规则字段', exact: true }).selectOption('organizationId');
  await page.getByRole('combobox', { name: '规则标准值', exact: true }).selectOption('demo-a');
  await page.getByRole('button', { name: '预览全库影响', exact: true }).click();
  await expect(page.getByText('预计新增 8', { exact: true })).toBeVisible();
  const before = await (await page.request.get('/api/people/demo-person-001/tags')).json();
  expect(before.some((item: { tagId: string }) => item.tagId === tag.id)).toBe(false);
  await page.getByRole('checkbox', { name: '我已核对全库影响，确认发布并开始重算', exact: true }).check();
  await page.screenshot({ path: testInfo.outputPath('rule-impact-preview.png'), fullPage: true });
  await page.getByRole('button', { name: '确认发布规则', exact: true }).click();
  await expect(page.getByRole('cell', { name: '处理完成', exact: true })).toBeVisible();
  const remindersAfter = await (await page.request.get('/api/reminders')).json();
  expect(remindersAfter.length).toBe(remindersBefore.length);
  await logout(page);
  await login(page, 'unit');
  await page.goto('/people/demo-person-001');
  await page.getByRole('button', { name: '删除 单位规则演示标签', exact: true }).click();
  await page.getByRole('button', { name: '确认变更', exact: true }).click();
  await expect(page.getByText('已人工删除，规则重算不得自动恢复', { exact: true })).toBeVisible();
  await logout(page);
  await login(page, 'admin');
  await page.getByRole('link', { name: '标签规则', exact: true }).click();
  page.once('dialog', dialog => dialog.accept());
  await page.getByRole('button', { name: '重算 浏览器单位规则', exact: true }).click();
  await expect(page.getByRole('cell', { name: '处理完成', exact: true })).toHaveCount(2);
  const assignments = await (await page.request.get('/api/people/demo-person-001/tags')).json();
  expect(assignments.find((item: { tagId: string }) => item.tagId === tag.id).state).toBe('SUPPRESSED');
  await page.screenshot({ path: testInfo.outputPath('rule-batch-results.png'), fullPage: true });
  page.once('dialog', dialog => dialog.accept());
  await page.getByRole('button', { name: '停用 浏览器单位规则', exact: true }).click();
  await expect(page.getByRole('cell', { name: '已停用', exact: true })).toBeVisible();
  await expect(page.getByRole('cell', { name: '处理完成', exact: true })).toHaveCount(3);
  const stoppedTags = await (await page.request.get('/api/people/demo-person-002/tags')).json();
  expect(stoppedTags.find((item: { tagId: string }) => item.tagId === tag.id).state).toBe('EXPIRED');
  await page.getByRole('button', { name: '配置 浏览器单位规则', exact: true }).click();
  await expect(page.getByText('规则已停用。核对条件后重新预览并确认发布，才会重新启用。', { exact: true })).toBeVisible();
  await page.getByRole('button', { name: '预览全库影响', exact: true }).click();
  await expect(page.getByText('预计新增 7', { exact: true })).toBeVisible();
  await page.getByRole('checkbox', { name: '我已核对全库影响，确认发布并开始重算', exact: true }).check();
  await page.getByRole('button', { name: '确认发布规则', exact: true }).click();
  await expect(page.getByRole('cell', { name: '已启用', exact: true })).toBeVisible();
  await expect(page.getByRole('cell', { name: '处理完成', exact: true })).toHaveCount(4);
  const reenabled = await (await page.request.get('/api/people/demo-person-001/tags')).json();
  expect(reenabled.find((item: { tagId: string }) => item.tagId === tag.id).state).toBe('SUPPRESSED');
  const restored = await (await page.request.get('/api/people/demo-person-002/tags')).json();
  expect(restored.find((item: { tagId: string }) => item.tagId === tag.id).state).toBe('ACTIVE');
  expect((await (await page.request.get('/api/reminders')).json()).length).toBe(remindersBefore.length);
  await page.screenshot({ path: testInfo.outputPath('rule-deactivation-and-reenable.png'), fullPage: true });

});
