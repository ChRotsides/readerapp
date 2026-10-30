FROM python:3.12-slim
COPY --from=ghcr.io/astral-sh/uv:0.11 /uv /usr/local/bin/uv
WORKDIR /app
ENV UV_COMPILE_BYTECODE=1 UV_LINK_MODE=copy PATH="/app/.venv/bin:$PATH"
COPY pyproject.toml uv.lock ./
RUN uv sync --frozen --no-dev --no-install-project
COPY alembic.ini ./
COPY alembic ./alembic
COPY readerserver ./readerserver
RUN uv sync --frozen --no-dev
EXPOSE 8000
CMD ["uvicorn", "readerserver.api.main:app", "--host", "0.0.0.0", "--port", "8000"]
