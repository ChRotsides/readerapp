"""Monthly spend cap with per-call usage recording (all providers)."""
from datetime import UTC, datetime

from sqlalchemy import func, select
from sqlalchemy.ext.asyncio import AsyncSession, async_sessionmaker

from ..models import AiUsage

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
        total = await self.month_total()
        if total >= self.cap_usd:
            raise BudgetExceeded(f"monthly AI budget reached (${total:.2f} of ${self.cap_usd:.2f})")

    async def record(self, provider: str, model: str, purpose: str, input_tokens: int, output_tokens: int, cost_usd: float) -> None:
        async with self.maker() as s:
            s.add(AiUsage(provider=provider, model=model, purpose=purpose, input_tokens=input_tokens,
                          output_tokens=output_tokens, cost_usd=cost_usd))
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
