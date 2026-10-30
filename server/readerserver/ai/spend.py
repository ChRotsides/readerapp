"""Monthly spend cap with per-call usage recording (all providers).

The cap is enforced with atomic cross-process reservations in Postgres: before a paid call the caller reserves the
call's estimated maximum cost (under a transaction-scoped advisory lock, so concurrent API/worker processes cannot all
observe the same free balance); after the call the reservation is settled against the real usage row, or released on
failure. Reservations expire so a crashed process cannot hold budget forever."""
from contextlib import asynccontextmanager
from datetime import UTC, datetime, timedelta

from sqlalchemy import delete, func, select, text
from sqlalchemy.ext.asyncio import AsyncSession, async_sessionmaker

from ..models import AiUsage, SpendReservation

SPEND_LOCK_KEY = 7_270_001
RESERVATION_TTL = timedelta(minutes=15)

# USD per 1M tokens (input, output). Source: Anthropic model pricing as of the claude-api reference.
CLAUDE_PRICES = {
    "claude-sonnet-5-5": (2.00, 10.00),
    "claude-haiku-5-5": (0.10, 0.50),
}


class BudgetExceeded(Exception):
    pass


def claude_cost(model: str, input_tokens: int, output_tokens: int) -> float:
    pin, pout = CLAUDE_PRICES.get(model, CLAUDE_PRICES["claude-sonnet-5-5"])
    return (input_tokens * pin + output_tokens * pout) / 1_000_000


def max_claude_cost(input_tokens: int, max_output_tokens: int) -> float:
    """Upper bound across every known model (a fallback may serve the request on any of them)."""
    return max(claude_cost(m, input_tokens, max_output_tokens) for m in CLAUDE_PRICES)


class Reservation:
    def __init__(self, tracker: "SpendTracker", rid: int):
        self.tracker, self.id, self.done = tracker, rid, False

    async def settle(self, provider: str, model: str, purpose: str, input_tokens: int, output_tokens: int, cost_usd: float) -> None:
        """Turn the reservation into the real usage row (atomically)."""
        await self.tracker.record(provider, model, purpose, input_tokens, output_tokens, cost_usd, reservation_id=self.id)
        self.done = True

    async def settle_many(self, rows: list[tuple[str, str, str, int, int, float]]) -> None:
        await self.tracker.record_many(rows, reservation_id=self.id)
        self.done = True


def month_start(now: datetime | None = None) -> datetime:
    now = now or datetime.now(UTC)
    return now.replace(day=1, hour=0, minute=0, second=0, microsecond=0)


class SpendTracker:
    def __init__(self, maker: async_sessionmaker[AsyncSession], cap_usd: float):
        self.maker = maker
        self.cap_usd = cap_usd

    async def month_total(self) -> float:
        async with self.maker() as s:
            v = await s.scalar(select(func.coalesce(func.sum(AiUsage.cost_usd), 0)).where(AiUsage.ts >= month_start()))
            return float(v or 0)

    async def check(self) -> None:
        """Non-reserving check (kept for callers that only need a cheap gate)."""
        total = await self.month_total() + await self._held()
        if total >= self.cap_usd:
            raise BudgetExceeded(f"monthly AI budget reached (${total:.2f} of ${self.cap_usd:.2f})")

    async def _held(self, s: AsyncSession | None = None) -> float:
        async def q(sess: AsyncSession) -> float:
            v = await sess.scalar(select(func.coalesce(func.sum(SpendReservation.amount_usd), 0)).where(
                SpendReservation.expires_at > datetime.now(UTC)))
            return float(v or 0)
        if s is not None:
            return await q(s)
        async with self.maker() as sess:
            return await q(sess)

    @asynccontextmanager
    async def reserve(self, estimate_usd: float, purpose: str = ""):
        """Atomically reserve `estimate_usd` of the monthly budget or raise BudgetExceeded. Settle the yielded
        reservation after the call; if the block exits without settling (error/cancel) the reservation is released."""
        async with self.maker() as s:
            async with s.begin():
                await s.execute(text("SELECT pg_advisory_xact_lock(:k)"), {"k": SPEND_LOCK_KEY})
                spent = float(await s.scalar(select(func.coalesce(func.sum(AiUsage.cost_usd), 0)).where(AiUsage.ts >= month_start())) or 0)
                held = await self._held(s)
                if spent + held + estimate_usd > self.cap_usd + 1e-12:
                    raise BudgetExceeded(
                        f"monthly AI budget would be exceeded (${spent:.2f} spent + ${held:.2f} reserved + ${estimate_usd:.2f} "
                        f"estimated > ${self.cap_usd:.2f})")
                r = SpendReservation(amount_usd=estimate_usd, purpose=purpose[:40], expires_at=datetime.now(UTC) + RESERVATION_TTL)
                s.add(r)
                await s.flush()
                rid = r.id
        res = Reservation(self, rid)
        try:
            yield res
        finally:
            if not res.done:
                await self.release(rid)

    async def release(self, rid: int) -> None:
        async with self.maker() as s:
            await s.execute(delete(SpendReservation).where(SpendReservation.id == rid))
            await s.commit()

    async def record(self, provider: str, model: str, purpose: str, input_tokens: int, output_tokens: int, cost_usd: float,
                     reservation_id: int | None = None) -> None:
        await self.record_many([(provider, model, purpose, input_tokens, output_tokens, cost_usd)], reservation_id)

    async def record_many(self, rows: list[tuple[str, str, str, int, int, float]], reservation_id: int | None = None) -> None:
        async with self.maker() as s:
            for provider, model, purpose, i, o, c in rows:
                s.add(AiUsage(provider=provider, model=model, purpose=purpose, input_tokens=i, output_tokens=o, cost_usd=c))
            if reservation_id is not None:
                await s.execute(delete(SpendReservation).where(SpendReservation.id == reservation_id))
            await s.commit()

    async def summary(self) -> dict:
        async with self.maker() as s:
            rows = (await s.execute(
                select(AiUsage.provider, AiUsage.purpose, func.count(), func.sum(AiUsage.cost_usd))
                .where(AiUsage.ts >= month_start()).group_by(AiUsage.provider, AiUsage.purpose)
            )).all()
        total = sum(float(r[3] or 0) for r in rows)
        return {
            "month": month_start().strftime("%Y-%m"),
            "cap_usd": self.cap_usd,
            "spent_usd": round(total, 4),
            "remaining_usd": round(max(self.cap_usd - total, 0), 4),
            "breakdown": [{"provider": r[0], "purpose": r[1], "calls": r[2], "cost_usd": round(float(r[3] or 0), 4)} for r in rows],
        }
