import { test, expect } from '@playwright/test';

test('public core value dashboard shows the mock supervision chain', async ({ page }) => {
  await page.goto('/dashboard');
  await expect(page.getByRole('heading', { name: '政务系统对象库 · 核心价值总览' })).toBeVisible();
  await expect(page.getByText('演示数据 / Mock', { exact: true })).toBeVisible();
  await expect(page.getByText('48,620', { exact: true })).toBeVisible();
  await expect(page.getByText('标签画像', { exact: true })).toBeVisible();
  await expect(page.getByText('提醒闭环', { exact: true })).toBeVisible();
  await page.getByRole('button', { name: /查看逾期人员/ }).click();
  await expect(page.getByRole('dialog', { name: '逾期未读人员' })).toBeVisible();
  await expect(page.getByText('演示人员 018', { exact: true })).toBeVisible();
  await page.getByRole('button', { name: '关闭' }).click();
  await page.setViewportSize({ width: 390, height: 844 });
  await page.reload();
  await expect(page.getByText('演示数据 / Mock', { exact: true })).toBeVisible();
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
});

test('dashboard exposes loading and empty data states', async ({ page }) => {
  await page.goto('/dashboard?state=loading');
  await expect(page.getByRole('status')).toContainText('正在加载核心价值数据');
  await page.goto('/dashboard?state=empty');
  await expect(page.getByRole('status')).toContainText('当前统计范围暂无数据');
  await expect(page.getByText('演示数据 / Mock', { exact: true })).toBeVisible();
});
