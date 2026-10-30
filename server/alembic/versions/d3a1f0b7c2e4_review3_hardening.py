"""review-3 hardening: symmetric link separations, deferred resolution, crawl leases, spend reservations

Revision ID: d3a1f0b7c2e4
Revises: c99d09de6654
Create Date: 2026-10-30 12:00:00.000000

"""
from typing import Sequence, Union

from alembic import op
import sqlalchemy as sa

revision: str = 'd3a1f0b7c2e4'
down_revision: Union[str, Sequence[str], None] = 'c99d09de6654'
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None


def upgrade() -> None:
    op.create_table(
        'link_separations',
        sa.Column('record_a_id', sa.Integer().with_variant(sa.BigInteger(), 'postgresql'), nullable=False),
        sa.Column('record_b_id', sa.Integer().with_variant(sa.BigInteger(), 'postgresql'), nullable=False),
        sa.Column('created_at', sa.DateTime(timezone=True), server_default=sa.text('now()'), nullable=False),
        sa.CheckConstraint('record_a_id < record_b_id', name='ck_link_separation_order'),
        sa.ForeignKeyConstraint(['record_a_id'], ['source_records.id'], ondelete='CASCADE'),
        sa.ForeignKeyConstraint(['record_b_id'], ['source_records.id'], ondelete='CASCADE'),
        sa.PrimaryKeyConstraint('record_a_id', 'record_b_id'),
    )
    op.create_index('ix_link_separations_b', 'link_separations', ['record_b_id'])
    # Backfill from existing splits so already-undone merges stay undone in both directions.
    op.execute("""
        INSERT INTO link_separations (record_a_id, record_b_id)
        SELECT DISTINCT LEAST(b.source_record_id, l.source_record_id), GREATEST(b.source_record_id, l.source_record_id)
        FROM link_blocks b JOIN work_links l ON l.work_id = b.work_id AND l.undone_at IS NULL
        WHERE b.source_record_id <> l.source_record_id
        ON CONFLICT DO NOTHING
    """)
    op.add_column('source_records', sa.Column('resolve_retry_at', sa.DateTime(timezone=True), nullable=True))
    op.add_column('source_records', sa.Column('resolve_reason', sa.String(length=20), nullable=True))
    op.add_column('source_records', sa.Column('resolve_attempts', sa.Integer(), server_default=sa.text('0'), nullable=False))
    op.add_column('crawl_state', sa.Column('next_eligible_at', sa.DateTime(timezone=True), nullable=True))
    op.add_column('crawl_state', sa.Column('lease_expires_at', sa.DateTime(timezone=True), nullable=True))
    op.create_table(
        'ai_spend_reservations',
        sa.Column('id', sa.BigInteger(), autoincrement=True, nullable=False),
        sa.Column('ts', sa.DateTime(timezone=True), server_default=sa.text('now()'), nullable=False),
        sa.Column('expires_at', sa.DateTime(timezone=True), nullable=False),
        sa.Column('amount_usd', sa.Numeric(12, 6), nullable=False),
        sa.Column('purpose', sa.String(length=40), nullable=False, server_default=''),
        sa.PrimaryKeyConstraint('id'),
    )


def downgrade() -> None:
    op.drop_table('ai_spend_reservations')
    op.drop_column('crawl_state', 'lease_expires_at')
    op.drop_column('crawl_state', 'next_eligible_at')
    op.drop_column('source_records', 'resolve_attempts')
    op.drop_column('source_records', 'resolve_reason')
    op.drop_column('source_records', 'resolve_retry_at')
    op.drop_index('ix_link_separations_b', table_name='link_separations')
    op.drop_table('link_separations')
