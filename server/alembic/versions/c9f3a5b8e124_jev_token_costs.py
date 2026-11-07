"""Correct Jev token pricing and retain sub-microdollar usage costs.

Revision ID: c9f3a5b8e124
Revises: b8e2f4a7d013
"""
from alembic import op
import sqlalchemy as sa

revision = "c9f3a5b8e124"
down_revision = "b8e2f4a7d013"
branch_labels = None
depends_on = None


def upgrade() -> None:
    # Serialize with paid-call reservations while correcting the ledger.
    op.execute("SELECT pg_advisory_xact_lock(7270001)")
    op.alter_column("ai_usage", "cost_usd", existing_type=sa.Numeric(12, 6), type_=sa.Numeric(18, 12), existing_nullable=False)
    op.alter_column("ai_spend_reservations", "amount_usd", existing_type=sa.Numeric(12, 6), type_=sa.Numeric(18, 12), existing_nullable=False)
    # Published pricing for these releases: https://docs.typesafe.ai/models.
    # Preserve unknown models and rows without recoverable input usage.
    op.execute("""
        UPDATE ai_usage SET cost_usd = input_tokens::numeric * 0.042 / 1000000
        WHERE provider = 'jev' AND input_tokens > 0
          AND model IN ('jev-1.13.0', 'jev-1.14.0', 'jev-latest', 'jev-preview')
    """)
    op.execute("""
        UPDATE link_crawl_jobs j SET spend_usd = (
            SELECT coalesce(sum(u.cost_usd), 0) FROM ai_usage u
            WHERE u.ts >= j.created_at
              AND (u.purpose LIKE 'lc' || j.id::text || ':%'
                   OR u.purpose LIKE 'embed:lc' || j.id::text || ':%')
        )
    """)


def downgrade() -> None:
    # Corrected historical costs remain corrected; shrinking precision rounds them.
    op.alter_column("ai_spend_reservations", "amount_usd", existing_type=sa.Numeric(18, 12), type_=sa.Numeric(12, 6), existing_nullable=False)
    op.alter_column("ai_usage", "cost_usd", existing_type=sa.Numeric(18, 12), type_=sa.Numeric(12, 6), existing_nullable=False)
