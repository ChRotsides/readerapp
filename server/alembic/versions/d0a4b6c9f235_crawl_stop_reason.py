"""Persist crawl stop reasons separately from job status and descriptive errors.

Revision ID: d0a4b6c9f235
Revises: c9f3a5b8e124
"""
from alembic import op
import sqlalchemy as sa

revision = "d0a4b6c9f235"
down_revision = "c9f3a5b8e124"
branch_labels = None
depends_on = None


def upgrade() -> None:
    op.add_column("link_crawl_jobs", sa.Column("stop_reason", sa.String(40), nullable=True))
    # Older code discarded checkpoints even when a guard stopped catalog discovery.
    op.execute("""UPDATE link_crawl_jobs SET stop_reason = 'legacy_completion'
                  WHERE status = 'done' AND scope = 'site' AND checkpoint = '{}'::jsonb""")


def downgrade() -> None:
    op.drop_column("link_crawl_jobs", "stop_reason")
