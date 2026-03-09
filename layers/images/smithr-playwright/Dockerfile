# Copyright 2026 Jules Gosnell
# SPDX-License-Identifier: Apache-2.0

FROM mcr.microsoft.com/playwright:v1.58.0-noble

# Install @playwright/test at /opt/pw/ — run-playwright.sh sets NODE_PATH
# so it's resolvable from any path. Version matches the base image's browsers.
ENV PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD=1 \
    CI=true
RUN mkdir -p /opt/pw && cd /opt/pw && npm init -y > /dev/null 2>&1 \
    && npm install @playwright/test@1.58.0

COPY entrypoint.sh /usr/local/bin/entrypoint.sh
COPY run-playwright.sh /usr/local/bin/run-playwright.sh
RUN chmod 755 /usr/local/bin/entrypoint.sh /usr/local/bin/run-playwright.sh

# Pre-create mount point so named volume inherits correct ownership
RUN mkdir -p /app && chown pwuser:pwuser /app

USER pwuser

ENTRYPOINT ["/usr/local/bin/entrypoint.sh"]
