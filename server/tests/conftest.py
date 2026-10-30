import asyncio
import hashlib
import math
import os
import re
from pathlib import Path

import pytest
import pytest_asyncio

TEST_DB = "readerapp_test"
ADMIN_URL = os.environ.get("TEST_ADMIN_DATABASE_URL", "postgresql+asyncpg://readerapp:readerapp@localhost:5435/postgres")
TEST_URL = ADMIN_URL.rsplit("/", 1)[0] + "/" + TEST_DB
os.environ["DATABASE_URL"] = TEST_URL
os.environ["API_TOKEN"] = "test-token"
os.environ["ANTHROPIC_API_KEY"] = ""
os.environ["VOYAGE_API_KEY"] = ""
os.environ["MAL_CLIENT_ID"] = ""

from sqlalchemy import text  # noqa: E402
from sqlalchemy.ext.asyncio import async_sessionmaker, create_async_engine  # noqa: E402

FIXTURES = Path(__file__).parent / "fixtures"


class FakeEmbedder:
    """Deterministic bag-of-words hashing embedder: texts sharing words have high cosine."""

    def __init__(self, dim: int = 1024):
        self.dim = dim
        self.calls: list[tuple[str, int]] = []

    async def embed(self, texts, input_type, purpose="embed"):
        self.calls.append((input_type, len(texts)))
        return [self._vec(t) for t in texts]

    def _vec(self, t: str) -> list[float]:
        v = [0.0] * self.dim
        for w in re.findall(r"\w+", t.lower()):
            h = int(hashlib.md5(w.encode()).hexdigest(), 16)
            v[h % self.dim] += 1.0
        n = math.sqrt(sum(x * x for x in v)) or 1.0
        return [x / n for x in v]


class FakeLLM:
    """Returns queued outputs per schema class name and records calls."""

    def __init__(self):
        self.queue: dict[str, list] = {}
        self.calls: list[dict] = []

    def add(self, schema_name: str, obj):
        self.queue.setdefault(schema_name, []).append(obj)

    async def parse(self, *, model, system, user, schema, effort, max_tokens, purpose):
        self.calls.append({"model": model, "schema": schema.__name__, "user": user, "effort": effort, "purpose": purpose})
        q = self.queue.get(schema.__name__)
        if not q:
            raise AssertionError(f"no fake output queued for {schema.__name__}")
        return q.pop(0) if len(q) > 1 else q[0]


@pytest.fixture
def fake_embedder():
    return FakeEmbedder()


@pytest.fixture
def fake_llm():
    return FakeLLM()


@pytest_asyncio.fixture(scope="session")
async def db_engine():
    admin = create_async_engine(ADMIN_URL, isolation_level="AUTOCOMMIT")
    try:
        async with admin.connect() as c:
            await c.execute(text(f"DROP DATABASE IF EXISTS {TEST_DB} WITH (FORCE)"))
            await c.execute(text(f"CREATE DATABASE {TEST_DB}"))
    except Exception as e:  # pragma: no cover
        pytest.skip(f"Postgres not reachable ({e}); run `docker compose up -d db`")
    await admin.dispose()
    from alembic import command
    from alembic.config import Config

    cfg = Config(str(Path(__file__).parent.parent / "alembic.ini"))
    cfg.set_main_option("script_location", str(Path(__file__).parent.parent / "alembic"))
    await asyncio.to_thread(command.upgrade, cfg, "head")
    engine = create_async_engine(TEST_URL)
    yield engine
    await engine.dispose()


@pytest_asyncio.fixture
async def maker(db_engine):
    m = async_sessionmaker(db_engine, expire_on_commit=False)
    async with db_engine.begin() as c:
        tables = (await c.execute(text("SELECT tablename FROM pg_tables WHERE schemaname='public' AND tablename != 'alembic_version'"))).scalars().all()
        await c.execute(text("TRUNCATE " + ", ".join(tables) + " RESTART IDENTITY CASCADE"))
    return m


@pytest_asyncio.fixture
async def session(maker):
    async with maker() as s:
        yield s
