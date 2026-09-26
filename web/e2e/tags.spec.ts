import { test, expect } from '@playwright/test';
import type { Page } from '@playwright/test';

async function login(page: Page, username: string) {
  await page.goto('/');
  await page.getByLabel('账号', { exact: true }).fill(username);
  await page.getByLabel('密码', { exact: true }).fill('TestOnlyE2e-2026');
  await page.getByRole('button', { name: '进入工作空间' }).click();
  await expect(page.getByRole('heading', { name: '工作台', exact: true })).toBeVisible();
}
test('administrator configures tag, unit operator adds removes restores and sees history', async ({ page }) => {
  await login(page, 'admin');
  await page.getByRole('link', { name: '标签目录', exact: true }).click();
  await page.getByLabel('标签名称', { exact: true }).fill('测试成长标签');
  await page.getByLabel('稳定编码', { exact: true }).fill('WEB_GROWTH');
  await page.getByRole('button', { name: '创建标签', exact: true }).click();
  await expect(page.getByText('标签已创建，默认支持人工赋标。')).toBeVisible();
  await page.getByRole('button', { name: '退出', exact: true }).click();
  await expect(page.getByRole('heading', { name: '登录明镜', exact: true })).toBeVisible();
  await login(page, 'unit');
  await page.getByRole('link', { name: '标签目录', exact: true }).click();
  await expect(page.getByText('测试成长标签', { exact: true })).toBeVisible();
  await expect(page.getByRole('button', { name: '创建标签', exact: true })).toHaveCount(0);
  await page.goto('/people/demo-person-001');
  await page.getByRole('combobox', { name: '选择末级标签' }).selectOption({ label: '测试成长标签' });
  await page.getByRole('button', { name: '添加标签', exact: true }).click();
  await page.getByRole('button', { name: '确认变更', exact: true }).click();
  await expect(page.getByText('生效中', { exact: true })).toBeVisible();
  await page.getByRole('button', { name: '删除 测试成长标签', exact: true }).click();
  await page.getByRole('button', { name: '确认变更', exact: true }).click();
  await expect(page.getByText('人工删除', { exact: true })).toBeVisible();
  await expect(page.getByText('已人工删除，规则重算不得自动恢复')).toBeVisible();
  await page.getByRole('button', { name: '恢复 测试成长标签', exact: true }).click();
  await page.getByRole('button', { name: '确认变更', exact: true }).click();
  await expect(page.getByText('生效中', { exact: true })).toBeVisible();
  await page.getByRole('button', { name: '查看 测试成长标签 历史', exact: true }).click();
  await expect(page.getByRole('heading', { name: '标签变更历史', exact: true })).toBeVisible();
  await expect(page.getByText('测试成长标签 · 人工删除', { exact: true })).toBeVisible();
  await page.reload();
  await expect(page.getByText('生效中', { exact: true })).toBeVisible();
});
test('catalog edit dialog supports keyboard dismissal and versioned rename', async ({ page }) => {
  await login(page, 'admin');
  await page.getByRole('link', { name: '标签目录', exact: true }).click();
  await page.getByLabel('标签名称', { exact: true }).fill('待更名标签');
  await page.getByLabel('稳定编码', { exact: true }).fill('WEB_RENAME');
  await page.getByRole('button', { name: '创建标签', exact: true }).click();
  await page.getByRole('button', { name: '编辑 待更名标签', exact: true }).click();
  await expect(page.getByRole('dialog', { name: '编辑标签', exact: true })).toBeVisible();
  await page.keyboard.press('Escape');
  await expect(page.getByRole('dialog')).toHaveCount(0);
  await page.getByRole('button', { name: '编辑 待更名标签', exact: true }).click();
  const dialog = page.getByRole('dialog');
  await dialog.getByLabel('名称', { exact: true }).fill('已更名标签');
  await dialog.getByRole('button', { name: '确认保存', exact: true }).click();
  await expect(page.getByRole('table').getByText('已更名标签', { exact: true })).toBeVisible();
});
