import { test, expect } from '@playwright/test';
import type { Page } from '@playwright/test';

async function login(page: Page, username: string) {
  await page.goto('/');
  await page.getByLabel('账号', { exact: true }).fill(username);
  await page.getByLabel('密码', { exact: true }).fill('TestOnlyE2e-2026');
  await page.getByRole('button', { name: '进入工作空间' }).click();
  await expect(page.getByRole('button', { name: '退出', exact: true })).toBeVisible();
}

async function createDraft(page: Page, title: string) {
  await page.getByRole('link', { name: '提醒任务', exact: true }).click();
  await page.getByRole('link', { name: '创建提醒', exact: true }).click();
  await page.getByLabel('演示一局', { exact: true }).check();
  await page.getByRole('button', { name: '预览接收名单', exact: true }).click();
  await expect(page.getByRole('heading', { name: '名单预览：16 人' })).toBeVisible();
  await page.getByRole('button', { name: '2. 编辑内容', exact: true }).click();
  await page.getByLabel('提醒标题', { exact: true }).fill(title);
  await page.getByRole('textbox', { name: '提醒正文', exact: true }).fill('请严格执行岗位纪律，依规履职。');
  await page.getByRole('button', { name: '4. 保存核对', exact: true }).click();
  await page.getByRole('button', { name: '保存草稿并核对', exact: true }).click();
  await expect(page.getByRole('heading', { name: title, exact: true })).toBeVisible();
  await expect(page.getByText('请严格执行岗位纪律，依规履职。', { exact: true })).toBeVisible();
}

async function confirmAndSubmit(page: Page) {
  await page.getByRole('checkbox', { name: /我已核对最终内容/ }).check();
  await page.getByRole('button', { name: '确认名单与内容', exact: true }).click();
  await expect(page.getByRole('button', { name: '提交审核', exact: true })).toBeEnabled();
  await page.getByRole('button', { name: '提交审核', exact: true }).click();
  await page.getByRole('dialog').getByRole('button', { name: '确认操作', exact: true }).click();
  await expect(page.getByText('第 1 轮审核 · 待审核内容和名单为冻结快照。', { exact: true })).toBeVisible();
}

test('unit creator freezes a task and another authenticated reviewer approves it', async ({ page }, testInfo) => {
  const title = '浏览器独立审核提醒';
  await login(page, 'unit');
  await createDraft(page, title);
  await confirmAndSubmit(page);
  await expect(page.getByRole('button', { name: '审核通过', exact: true })).toHaveCount(0);
  await page.getByRole('button', { name: '退出', exact: true }).click();
  await expect(page.getByRole('heading', { name: '登录明镜' })).toBeVisible();
  await login(page, 'reviewer');
  await page.getByRole('link', { name: '审核工作台', exact: true }).click();
  await page.getByRole('link', { name: title, exact: true }).click();
  await expect(page.getByRole('heading', { name: '最终内容预览', exact: true })).toBeVisible();
  await expect(page.getByRole('link', { name: '编辑草稿', exact: true })).toHaveCount(0);
  await page.getByRole('button', { name: '审核通过', exact: true }).click();
  await page.getByRole('dialog').getByRole('button', { name: '确认操作', exact: true }).click();
  await expect(page.getByText(/审核已通过，发送作业已排队/)).toBeVisible();
  await page.reload();
  await expect(page.getByText(/审核已通过，发送作业已排队/)).toBeVisible();
  await page.screenshot({ path: testInfo.outputPath('review-approved.png'), fullPage: true });
});

test('draft edits invalidate confirmation and explicit exclusion survives a saved edit', async ({ page }) => {
  await login(page, 'unit');
  await createDraft(page, '浏览器编辑名单提醒');
  await page.getByRole('checkbox', { name: /我已核对最终内容/ }).check();
  await page.getByRole('button', { name: '确认名单与内容', exact: true }).click();
  await expect(page.getByRole('button', { name: '提交审核', exact: true })).toBeEnabled();
  await page.getByRole('link', { name: '编辑草稿', exact: true }).click();
  await page.getByRole('button', { name: '移除 演示人员01', exact: true }).click();
  await page.getByRole('button', { name: '重新预览', exact: true }).click();
  await expect(page.getByRole('heading', { name: '名单预览：15 人' })).toBeVisible();
  await page.getByRole('button', { name: '4. 保存核对', exact: true }).click();
  await page.getByRole('button', { name: '保存草稿并核对', exact: true }).click();
  await expect(page.getByRole('button', { name: '提交审核', exact: true })).toBeDisabled();
  await expect(page.getByText('手工排除', { exact: true })).toBeVisible();
});
