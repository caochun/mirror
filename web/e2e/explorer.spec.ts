import { test, expect, type Page } from '@playwright/test';
import { readFileSync } from 'node:fs';
import { join } from 'node:path';

export async function login(page: Page, username: string, directory = process.env.MIRROR_E2E_DIR!) {
  const line = readFileSync(join(directory, 'credentials.txt'), 'utf8').split('\n').find(line => line.startsWith(username + '='))!;
  await page.getByLabel('账号', { exact: true }).fill(username);
  await page.getByLabel('密码', { exact: true }).fill(line.substring(username.length + 1));
  await page.getByRole('button', { name: '登录', exact: true }).click();
  await expect(page.getByRole('heading', { name: 'Mirror · 人员工作台' })).toBeVisible();
}
async function confirm(page: Page) {
  await page.getByRole('dialog').getByRole('checkbox').check();
  await page.getByRole('button', { name: '确认提交', exact: true }).click();
  await expect(page.getByRole('dialog')).toHaveCount(0);
  await expect(page.getByRole('status')).toContainText('操作已提交');
}

test('business pages complete registration, admission, tagging, suppression and restore', async ({ page }) => {
  const errors: string[] = [];
  page.on('pageerror', error => errors.push(error.message));
  const name = `浏览器人员-${Date.now()}`;
  await page.goto('/');
  await login(page, 'editor');
  await page.getByRole('button', { name: '人工建档', exact: true }).click();
  await page.getByLabel('姓名', { exact: true }).fill(name);
  await page.getByLabel('出生日期', { exact: true }).fill('1985-03-02');
  await page.getByLabel('操作说明').fill('浏览器验收：人工核实身份');
  await confirm(page);
  await expect(page.getByRole('heading', { name, exact: true })).toBeVisible();
  await expect(page.getByRole('button', { name: '人工赋标', exact: true })).toBeDisabled();
  await expect(page.getByRole('button', { name: '调整管理状态', exact: true })).toHaveCount(0);
  await page.getByRole('button', { name: '退出登录' }).click();
  await login(page, 'admin');
  await page.getByRole('button', { name: new RegExp(name) }).click();
  await page.getByRole('button', { name: '调整管理状态' }).click();
  await page.getByLabel('目标状态').selectOption('IN_SCOPE');
  await page.getByLabel('调整依据').fill('VERIFIED');
  await page.getByLabel('操作说明').fill('确认纳入管理');
  await confirm(page);
  await page.getByRole('button', { name: '人工赋标', exact: true }).click();
  await page.getByLabel('操作说明').fill('人工岗位依据');
  await confirm(page);
  await expect(page.getByText('当前有效', { exact: true })).toBeVisible();
  await page.getByRole('button', { name: '取消标签', exact: true }).click();
  await page.getByLabel('操作说明').fill('人工核实后暂时抑制');
  await confirm(page);
  await expect(page.getByText('已人工取消', { exact: true })).toBeVisible();
  await page.getByRole('button', { name: '人工赋标', exact: true }).click();
  await expect(page.getByText('将补充人工依据，并恢复已取消的标签；原有依据保留。')).toBeVisible();
  await page.getByLabel('操作说明').fill('复核后明确恢复');
  await confirm(page);
  await expect(page.getByText('当前有效', { exact: true })).toBeVisible();
  await page.getByText(/^变更记录 ·/).click();
  await expect(page.getByText(/当前单位关系 · 建立/)).toBeVisible();
  await page.screenshot({ path: 'test-results/personnel-desktop.png', fullPage: true });
  await page.getByRole('button', { name: '审计与待投递事件' }).click();
  await expect(page.getByRole('dialog')).toContainText('openfoundry.action.completed');
  await page.getByRole('button', { name: '关闭弹窗' }).click();
  await page.getByRole('button', { name: '对象浏览', exact: true }).click();
  await page.getByRole('complementary').getByRole('button', { name, exact: true }).click();
  await expect(page.getByRole('heading', { name, exact: true })).toBeVisible();
  await page.getByRole('article', { name: '所属单位', exact: true }).getByRole('button', { name: /示范单位（一）/ }).click();
  await expect(page.getByRole('heading', { name: '示范单位（一）', exact: true })).toBeVisible();
  await page.getByRole('article', { name: '本单位人员', exact: true }).getByRole('button', { name: new RegExp(name) }).click();
  await page.getByRole('article', { name: '管理状态', exact: true }).getByRole('button', { name: /的管理状态/ }).click();
  await page.getByLabel('查看历史关联').check();
  await page.getByLabel('关联类别', { exact: true }).selectOption('MembershipDecisionOrganization');
  await expect(page.getByText('共 2 项关联（含历史）', { exact: true })).toBeVisible();
  const ended = page.getByRole('article', { name: '状态调整单位', exact: true }).filter({ hasText: '历史关联' });
  await ended.getByRole('button', { name: '查看关联变更' }).click();
  await expect(ended.getByText('结束 · 第 2 次记录', { exact: false })).toBeVisible();
  await page.screenshot({ path: 'test-results/relationships-ended.png', fullPage: true });
  expect(errors).toEqual([]);
});

test('reader sees only scoped facts and mobile page remains usable', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 });
  await page.goto('/');
  await login(page, 'reader');
  await expect(page.getByRole('button', { name: '人工建档', exact: true })).toHaveCount(0);
  await expect(page.getByRole('button', { name: '审计与待投递事件' })).toHaveCount(0);
  await page.getByLabel('搜索姓名').fill('does-not-exist');
  await expect(page.getByText('当前筛选下没有匹配人员。')).toBeVisible();
  const width = await page.evaluate(() => ({ viewport: document.documentElement.clientWidth, content: document.documentElement.scrollWidth }));
  expect(width.content).toBeLessThanOrEqual(width.viewport + 1);
  await page.screenshot({ path: 'test-results/personnel-mobile.png', fullPage: true });
});
