"""search_history: saved discovery searches synced with the phone (tombstoned deletes)

Revision ID: b8e2f4a7d013
Revises: a7d4e1c93f56
"""
from alembic import op
import sqlalchemy as sa
from sqlalchemy.dialects import postgresql

revision = "b8e2f4a7d013"
down_revision = "a7d4e1c93f56"
branch_labels = None
depends_on = None


def upgrade() -> None:
    op.create_table(
        "search_history",
        sa.Column("id", sa.UUID(), nullable=False),
        sa.Column("description", sa.Text(), nullable=False),
        sa.Column("request", postgresql.JSONB(), nullable=False),
        sa.Column("scenes", postgresql.JSONB(), nullable=False),
        sa.Column("results", postgresql.JSONB(), nullable=False),
        sa.Column("result_count", sa.Integer(), nullable=False),
        sa.Column("created_at", sa.DateTime(timezone=True), server_default=sa.text("now()"), nullable=False),
        sa.Column("updated_at", sa.DateTime(timezone=True), server_default=sa.text("now()"), nullable=False),
        sa.Column("deleted_at", sa.DateTime(timezone=True), nullable=True),
        sa.PrimaryKeyConstraint("id"),
    )
    op.create_index("ix_search_history_created", "search_history", ["created_at"])
    op.create_index("ix_search_history_updated", "search_history", ["updated_at"])


def downgrade() -> None:
    op.drop_index("ix_search_history_updated", table_name="search_history")
    op.drop_index("ix_search_history_created", table_name="search_history")
    op.drop_table("search_history")
