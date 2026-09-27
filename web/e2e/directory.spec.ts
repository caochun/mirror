import { test, expect } from '@playwright/test';
import type { Page } from '@playwright/test';

async function login(page: Page, username: string) {
  await page.goto('/');
  await page.getByLabel('账号', { exact: true }).fill(username);
  await page.getByLabel('密码', { exact: true }).fill('TestOnlyE2e-2026');
  await page.getByRole('button', { name: '进入工作空间' }).click();
  await expect(page.getByRole('heading', { name: username === 'reviewer' ? '审核工作台' : '政务系统对象库 · 核心价值总览', exact: true })).toBeVisible();
}
test('real login, server-scoped pages, search, history and logout', async ({ page }) => {
  await login(page, 'unit');
  await page.getByRole('link', { name: '人员与组织', exact: true }).click();
  await expect(page.getByRole('link', { name: '演示人员01', exact: true })).toBeVisible();
  await page.getByRole('button', { name: '下一页' }).click();
  await expect(page.getByRole('link', { name: '演示人员16', exact: true })).toBeVisible();
  await expect(page.getByRole('link', { name: '演示人员17', exact: true })).toHaveCount(0);
  await page.getByLabel('姓名或工号').fill('演示人员12');
  await page.getByRole('button', { name: '查询', exact: true }).click();
  await page.getByRole('link', { name: '演示人员12', exact: true }).click();
  await expect(page.getByRole('heading', { name: '档案变更记录' })).toBeVisible();
  await expect(page.getByText('建立档案')).toBeVisible();
  await page.reload();
  await expect(page.getByRole('heading', { name: '演示人员12', exact: true })).toBeVisible();
  await page.getByRole('button', { name: '退出', exact: true }).click();
  await expect(page.getByRole('heading', { name: '登录明镜' })).toBeVisible();
});
test('reviewer cannot navigate to or request personnel', async ({ page }) => {
  await login(page, 'reviewer');
  await expect(page.getByRole('link', { name: '人员与组织', exact: true })).toHaveCount(0);
  expect((await page.request.get('/api/people')).status()).toBe(403);
  await page.goto('/people');
  await expect(page.getByRole('alert')).toHaveText('审核员无人员库浏览权限');
});
test('bad credentials, mobile layout, and server errors have explicit states', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 });
  await page.goto('/');
  await page.getByLabel('账号', { exact: true }).fill('admin');
  await page.getByLabel('密码', { exact: true }).fill('incorrect');
  await page.getByRole('button', { name: '进入工作空间' }).click();
  await expect(page.getByRole('alert')).toHaveText('账号或密码错误');
  await page.getByLabel('密码', { exact: true }).fill('TestOnlyE2e-2026');
  await page.getByRole('button', { name: '进入工作空间' }).click();
  await expect(page.getByRole('heading', { name: '政务系统对象库 · 核心价值总览', exact: true })).toBeVisible();
  await page.route('**/api/people?**', route => route.fulfill({ status: 503, contentType: 'application/json', body: '{"message":"服务暂时不可用"}' }));
  await page.getByRole('link', { name: '人员与组织', exact: true }).click();
  await expect(page.getByRole('alert')).toContainText('服务暂时不可用');
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
});
test('desktop directory render', async ({ page }, testInfo) => {
  await login(page, 'admin');
  await page.getByRole('link', { name: '人员与组织', exact: true }).click();
  await expect(page.getByRole('link', { name: '演示人员01', exact: true })).toBeVisible();
  await page.screenshot({ path: testInfo.outputPath('directory.png'), fullPage: true });
});
