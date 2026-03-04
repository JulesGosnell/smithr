# Copyright 2026 Jules Gosnell
# SPDX-License-Identifier: Apache-2.0

FROM mcr.microsoft.com/playwright:v1.58.0-noble

RUN npm install -g pnpm@9

ENV PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD=1 \
    CI=true

COPY entrypoint.sh /usr/local/bin/entrypoint.sh
COPY run-playwright.sh /usr/local/bin/run-playwright.sh
RUN chmod +x /usr/local/bin/entrypoint.sh /usr/local/bin/run-playwright.sh

# Pre-create mount point so named volume inherits correct ownership
RUN mkdir -p /app/node_modules && chown pwuser:pwuser /app /app/node_modules

USER pwuser

ENTRYPOINT ["/usr/local/bin/entrypoint.sh"]
