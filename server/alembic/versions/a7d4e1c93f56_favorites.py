"""favorites: starred novels synced with the phone (tombstoned deletes)

Revision ID: a7d4e1c93f56
Revises: f6c3d9b02e45
"""
from alembic import op
import sqlalchemy as sa

revision = "a7d4e1c93f56"
down_revision = "f6c3d9b02e45"
branch_labels = None
depends_on = None


def upgrade() -> None:
    op.create_table(
        "favorites",
        sa.Column("id", sa.BigInteger(), autoincrement=True, nullable=False),
        sa.Column("key", sa.String(length=2100), nullable=False),
        sa.Column("work_id", sa.UUID(), nullable=True),
        sa.Column("novel_url", sa.Text(), nullable=True),
        sa.Column("title", sa.Text(), nullable=False),
        sa.Column("author", sa.Text(), nullable=True),
        sa.Column("cover_url", sa.Text(), nullable=True),
        sa.Column("domain", sa.String(length=255), nullable=True),
        sa.Column("changed_at", sa.DateTime(timezone=True), nullable=False),
        sa.Column("created_at", sa.DateTime(timezone=True), server_default=sa.text("now()"), nullable=False),
        sa.Column("updated_at", sa.DateTime(timezone=True), server_default=sa.text("now()"), nullable=False),
        sa.Column("deleted_at", sa.DateTime(timezone=True), nullable=True),
        sa.ForeignKeyConstraint(["work_id"], ["works.id"], ondelete="SET NULL"),
        sa.PrimaryKeyConstraint("id"),
        sa.UniqueConstraint("key"),
    )
    op.create_index("ix_favorites_updated", "favorites", ["updated_at"])
    op.create_index("ix_favorites_work", "favorites", ["work_id"])


def downgrade() -> None:
    op.drop_index("ix_favorites_work", table_name="favorites")
    op.drop_index("ix_favorites_updated", table_name="favorites")
    op.drop_table("favorites")
