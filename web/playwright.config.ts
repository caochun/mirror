import { defineConfig } from '@playwright/test';
import { mkdtempSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
process.env.MIRROR_E2E_DIR ||= mkdtempSync(join(tmpdir(), 'mirror-e2e-'));
export default defineConfig({
  testDir: './e2e', workers: 1, timeout: 60000,
  use: { baseURL: 'http://127.0.0.1:8091', viewport: { width: 1440, height: 1000 }, screenshot: 'only-on-failure' },
  webServer: {
    command: 'python3 ../scripts/run-mirror.py --port 8091 --init-local --local-dir "$MIRROR_E2E_DIR"',
    url: 'http://127.0.0.1:8091/api/session', timeout: 30000, reuseExistingServer: false
  }
});
