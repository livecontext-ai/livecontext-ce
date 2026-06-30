# Base image is ARG-overridable so CI can substitute the GHCR mirror
# (ghcr.io/<repo>/base/*) and dodge Docker Hub anonymous pull rate limits;
# the Docker Hub default keeps local/CE builds working unauthenticated.
ARG PYTHON_IMAGE=python:3.12-slim
FROM ${PYTHON_IMAGE}

WORKDIR /app

# Install Google Chrome stable + Xvfb (virtual display for headed mode)
RUN apt-get update && apt-get install -y --no-install-recommends \
    wget gnupg2 \
    && wget -q -O - https://dl.google.com/linux/linux_signing_key.pub | gpg --dearmor -o /usr/share/keyrings/google-chrome.gpg \
    && echo "deb [arch=amd64 signed-by=/usr/share/keyrings/google-chrome.gpg] http://dl.google.com/linux/chrome/deb/ stable main" > /etc/apt/sources.list.d/google-chrome.list \
    && apt-get update && apt-get install -y --no-install-recommends \
    google-chrome-stable \
    xvfb \
    # Fonts for proper page rendering
    fonts-liberation fonts-noto-color-emoji \
    && rm -rf /var/lib/apt/lists/*

# Python dependencies. browser-use 0.12.6 hard-pins pydantic==2.12.5 +
# pulls mcp==1.26.0 (starlette>=0.49) which conflicts with fastapi 0.115.6,
# so install the rest first (including the *non-conflicting* browser-use
# transitives we listed in requirements.txt - bubus, browser-use-sdk,
# inquirerpy, ollama, uuid7) and then layer browser-use with --no-deps on
# top. That gives runner.py a working `from browser_use import Agent`
# without breaking FastAPI.
COPY requirements.txt .
RUN grep -v -E '^browser-use(==|>=|$)' requirements.txt > requirements.no-browser-use.txt \
 && pip install --no-cache-dir -r requirements.no-browser-use.txt \
 && pip install --no-cache-dir --no-deps browser-use==0.12.6 \
 && rm requirements.no-browser-use.txt \
 && python -c "from browser_use import Agent, BrowserProfile; print('browser_use OK')"

COPY app/ ./app/

# Persistent Chrome profile for cookies
RUN mkdir -p /app/data/chrome_profile

# Xvfb display for headed (stealth) mode
ENV DISPLAY=:99
ENV BROWSER_HEADLESS=false

EXPOSE 8085

# Start Xvfb + uvicorn (headed Chrome needs a display)
CMD ["sh", "-c", "Xvfb :99 -screen 0 1920x1080x24 -nolisten tcp &  uvicorn app.main:app --host 0.0.0.0 --port 8085 --workers 1"]
