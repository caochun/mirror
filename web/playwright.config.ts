import { defineConfig } from '@playwright/test';
import { resolve } from 'node:path';
export default defineConfig({
  testDir: './e2e',
  workers: 1,
  use: { baseURL: 'http://127.0.0.1:15173', viewport: { width: 1440, height: 1000 }, trace: 'retain-on-failure' },
  webServer: [
    {
      command: 'java -jar mirror-server/target/mirror-server-0.1.0-SNAPSHOT.jar --server.port=18080 --spring.datasource.url=jdbc:h2:mem:mirror_browser --mirror.demo=true --mirror.delivery-mode=mock --mirror.scheduling-enabled=false --mirror.bootstrap-password=TestOnlyE2e-2026',
      cwd: resolve(import.meta.dirname, '..'),
      url: 'http://127.0.0.1:18080/api/health', timeout: 60000,
      reuseExistingServer: false,
    },
    {
      command: 'npm run dev -- --port 15173 --strictPort',
      env: { MIRROR_API_TARGET: 'http://127.0.0.1:18080' },
      url: 'http://127.0.0.1:15173', reuseExistingServer: false,
    },
  ],
});
