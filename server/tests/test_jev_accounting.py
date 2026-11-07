import importlib.util
from decimal import Decimal
from pathlib import Path

import httpx
import pytest
from alembic.migration import MigrationContext
from alembic.operations import Operations
from sqlalchemy import select

from readerserver.ai.jev import JevClient
from readerserver.ai.spend import BudgetExceeded, SpendTracker
from readerserver.config import Settings
from readerserver.models import AiUsage, LinkCrawlJob


@pytest.mark.parametrize("kind", ["choice", "score"])
@pytest.mark.parametrize("tokens", [1, 123456])
async def test_jev_bills_reported_input_only_with_submicrodollar_precision(maker, kind, tokens):
    spend = SpendTracker(maker, 5)

    async def handler(request):
        # The reservation covers the payload, including instructions and criteria.
        assert await spend._held() > 0
        answer = {"choice": "yes", "probabilities": {"yes": 1}} if kind == "choice" else {"score": 1, "probabilities": {"1": 1}}
        return httpx.Response(200, json={"model": "jev-1.14.0", "answers": {"test": answer},
                                        "usage": {"input_tokens": tokens, "output_tokens": 999999}})

    client = JevClient(Settings(jev_api_key="test"), spend, http=httpx.AsyncClient(transport=httpx.MockTransport(handler)))
    if kind == "choice":
        await client.choose("test", "Choose", {"yes": "Yes"}, "small state", "test")
    else:
        await client.score({"test": "Score"}, ["no", "yes"], "small state", "test")
    async with maker() as session:
        row = (await session.scalars(select(AiUsage))).one()
        assert row.input_tokens == tokens and row.output_tokens == 999999
        assert row.cost_usd == Decimal(tokens) * Decimal("0.042") / Decimal(1_000_000)
    assert await spend._held() == 0


async def test_reservation_includes_questions_and_is_released_on_http_failure(maker):
    spend = SpendTracker(maker, 5)
    held = []

    async def handler(request):
        held.append(await spend._held())
        return httpx.Response(503)

    cfg = Settings(jev_api_key="test", jev_price_per_mtok_input=0.084)
    client = JevClient(cfg, spend, http=httpx.AsyncClient(transport=httpx.MockTransport(handler)))
    for instructions in ["short", "long instructions " * 1000]:
        with pytest.raises(httpx.HTTPStatusError):
            await client.choose("test", instructions, {"yes": "Yes"}, "state", "test")
    assert held[1] > held[0] > 0
    assert await spend._held() == 0


async def test_jev_request_is_blocked_before_network_when_budget_is_exhausted(maker):
    async def handler(request):
        pytest.fail("Budget must be checked before the HTTP call")

    client = JevClient(Settings(jev_api_key="test"), SpendTracker(maker, 0),
                       http=httpx.AsyncClient(transport=httpx.MockTransport(handler)))
    with pytest.raises(BudgetExceeded):
        await client.choose("test", "Choose", {"yes": "Yes"}, "state", "test")


async def test_migration_reprices_known_jev_models_and_refreshes_job_total(session, db_engine):
    job = LinkCrawlJob(url="https://example.com", domain="example.com", scope="site", status="paused", spend_usd=9)
    session.add(job)
    await session.commit()
    tagged = f"lc{job.id}:chapter"
    for provider, model, tokens, cost, purpose in [
        ("jev", "jev-1.14.0", 1, "0.002", tagged),
        ("jev", "jev-1.13.0", 1000000, "0.002", tagged),
        ("jev", "future-model", 1000, "0.003", "other"),
        ("jev", "jev-latest", 0, "0.002", "missing-usage"),
        ("voyage", "voyage-4-large", 100, "0.01", f"lc{job.id}:embed"),
    ]:
        session.add(AiUsage(provider=provider, model=model, purpose=purpose, input_tokens=tokens,
                            output_tokens=100, cost_usd=Decimal(cost)))
    await session.commit()
    job_id = job.id
    path = Path(__file__).parents[1] / "alembic/versions/c9f3a5b8e124_jev_token_costs.py"
    spec = importlib.util.spec_from_file_location("jev_cost_migration", path)
    migration = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(migration)

    def apply(conn):
        with Operations.context(MigrationContext.configure(conn)):
            migration.downgrade()
            migration.upgrade()
            migration.upgrade()  # repeated corrections do not compound or duplicate charges

    async with db_engine.begin() as conn:
        await conn.run_sync(apply)
    session.expire_all()
    entries = {row.model: row for row in (await session.scalars(select(AiUsage))).all()}
    assert entries["jev-1.14.0"].cost_usd == Decimal("0.000000042")
    assert entries["jev-1.13.0"].cost_usd == Decimal("0.042")
    assert entries["future-model"].cost_usd == Decimal("0.003")
    assert entries["jev-latest"].cost_usd == Decimal("0.002")
    assert entries["voyage-4-large"].cost_usd == Decimal("0.01")
    assert (await session.get(LinkCrawlJob, job_id)).spend_usd == Decimal("0.052000")
